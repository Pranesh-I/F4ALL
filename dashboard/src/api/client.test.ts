import { ApiError, DashboardApi, type TokenStore } from "./client";

function memoryStore(initial: { access: string; refresh: string } | null = null): TokenStore {
  let value = initial;
  return {
    get: () => value,
    set: (tokens) => {
      value = tokens;
    },
    clear: () => {
      value = null;
    },
  };
}

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

const official = {
  official_id: "o1",
  name: "Reviewer",
  email: "r@sai.example",
  role: "regional_reviewer",
  region: "Kerala",
};

describe("DashboardApi", () => {
  it("stores tokens on login and sends them afterwards", async () => {
    const store = memoryStore();
    const calls: RequestInit[] = [];
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      calls.push(init ?? {});
      if (calls.length === 1) {
        return json(200, { access_token: "a1", refresh_token: "r1", token_type: "bearer", expires_in: 3600, official });
      }
      return json(200, official);
    });

    const api = new DashboardApi("", store, () => {}, fetchImpl as typeof fetch);
    await api.login("r@sai.example", "pw");
    await api.me();

    expect(store.get()).toEqual({ access: "a1", refresh: "r1" });
    expect((calls[0]!.headers as Record<string, string>).Authorization).toBeUndefined();
    expect((calls[1]!.headers as Record<string, string>).Authorization).toBe("Bearer a1");
  });

  it("refreshes once on 401 and retries with the new token", async () => {
    const store = memoryStore({ access: "old", refresh: "r1" });
    const fetchImpl = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      const auth = (init?.headers as Record<string, string> | undefined)?.Authorization;
      if (String(url).endsWith("/auth/refresh")) {
        return json(200, { access_token: "new", refresh_token: "r2", token_type: "bearer", expires_in: 3600, official });
      }
      return auth === "Bearer new" ? json(200, official) : json(401, { detail: "Token expired" });
    });

    const api = new DashboardApi("", store, () => {}, fetchImpl as typeof fetch);

    await expect(api.me()).resolves.toMatchObject({ official_id: "o1" });
    expect(store.get()).toEqual({ access: "new", refresh: "r2" });
  });

  it("concurrent 401s share a single refresh", async () => {
    // Presenting one refresh token twice is treated as theft by the server.
    const store = memoryStore({ access: "old", refresh: "r1" });
    let refreshes = 0;
    const fetchImpl = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (String(url).endsWith("/auth/refresh")) {
        refreshes++;
        await new Promise((resolve) => setTimeout(resolve, 10));
        return json(200, { access_token: "new", refresh_token: "r2", token_type: "bearer", expires_in: 3600, official });
      }
      const auth = (init?.headers as Record<string, string> | undefined)?.Authorization;
      return auth === "Bearer new" ? json(200, official) : json(401, { detail: "expired" });
    });

    const api = new DashboardApi("", store, () => {}, fetchImpl as typeof fetch);
    await Promise.all([api.me(), api.me(), api.me()]);

    expect(refreshes).toBe(1);
  });

  it("ends the session when refresh fails", async () => {
    const store = memoryStore({ access: "old", refresh: "dead" });
    const expired = vi.fn();
    const fetchImpl = vi.fn(async () => json(401, { detail: "Invalid refresh token" }));

    const api = new DashboardApi("", store, expired, fetchImpl as typeof fetch);

    await expect(api.me()).rejects.toBeInstanceOf(ApiError);
    expect(store.get()).toBeNull();
    expect(expired).toHaveBeenCalledOnce();
  });

  it("a failed login is an error, not a session expiry", async () => {
    const expired = vi.fn();
    const fetchImpl = vi.fn(async () => json(401, { detail: "Incorrect email or password" }));
    const api = new DashboardApi("", memoryStore(), expired, fetchImpl as typeof fetch);

    await expect(api.login("x@y.z", "bad")).rejects.toThrow("Incorrect email or password");
    expect(expired).not.toHaveBeenCalled();
  });

  it("reads the queue total from X-Total-Count", async () => {
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
      json(200, [], { "X-Total-Count": "57" }),
    );
    const api = new DashboardApi("", memoryStore({ access: "a", refresh: "r" }), () => {}, fetchImpl as typeof fetch);

    const page = await api.reviewQueue({ status: "flagged", limit: 25, offset: 25 });

    expect(page.total).toBe(57);
    const url = String(fetchImpl.mock.calls[0]![0]);
    expect(url).toContain("status=flagged");
    expect(url).toContain("offset=25");
  });

  it("lists submissions with filters and the total from X-Total-Count", async () => {
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
      json(200, [{ result_id: "r1" }], { "X-Total-Count": "31" }),
    );
    const api = new DashboardApi("", memoryStore({ access: "a", refresh: "r" }), () => {}, fetchImpl as typeof fetch);

    const page = await api.submissions({ status: "approved,rejected", sessionId: "s 1", offset: 25 });

    expect(page.total).toBe(31);
    const url = new URL(String(fetchImpl.mock.calls[0]![0]), "http://x");
    expect(url.pathname).toBe("/api/dashboard/submissions");
    expect(url.searchParams.get("status")).toBe("approved,rejected");
    expect(url.searchParams.get("session_id")).toBe("s 1");
    expect(url.searchParams.get("limit")).toBe("25");
    expect(url.searchParams.get("offset")).toBe("25");
  });

  it("asks the server for sessions by status, and ends one by id", async () => {
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => json(200, []));
    const api = new DashboardApi("", memoryStore({ access: "a", refresh: "r" }), () => {}, fetchImpl as typeof fetch);

    await api.sessions({ status: ["active", "scheduled"] });
    await api.sessions();
    await api.endSession("s1");

    expect(String(fetchImpl.mock.calls[0]![0])).toBe("/api/dashboard/sessions?status=active,scheduled");
    expect(String(fetchImpl.mock.calls[1]![0])).toBe("/api/dashboard/sessions");
    expect(String(fetchImpl.mock.calls[2]![0])).toBe("/api/dashboard/sessions/s1/end");
    expect(fetchImpl.mock.calls[2]![1]!.method).toBe("POST");
  });

  it("revokes the refresh token on sign-out and forgets both tokens at once", async () => {
    const store = memoryStore({ access: "a1", refresh: "r1" });
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => json(200, { message: "Signed out" }));
    const api = new DashboardApi("", store, () => {}, fetchImpl as typeof fetch);

    const done = api.logout();
    // Already gone before the server answers.
    expect(store.get()).toBeNull();
    await done;

    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(String(url)).toBe("/api/dashboard/auth/logout");
    expect(JSON.parse(String(init!.body))).toEqual({ refresh_token: "r1" });
    expect((init!.headers as Record<string, string>).Authorization).toBe("Bearer a1");
  });

  it("still revokes on sign-out when the access token has lapsed", async () => {
    const fetchImpl = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (String(url).endsWith("/auth/refresh")) {
        return json(200, { access_token: "a2", refresh_token: "r2", token_type: "bearer", expires_in: 3600, official });
      }
      const auth = (init?.headers as Record<string, string>).Authorization;
      return auth === "Bearer a2" ? json(200, { message: "Signed out" }) : json(401, { detail: "Token expired" });
    });
    const api = new DashboardApi("", memoryStore({ access: "old", refresh: "r1" }), () => {}, fetchImpl as typeof fetch);

    await api.logout();

    const last = fetchImpl.mock.calls.at(-1)!;
    expect(String(last[0])).toBe("/api/dashboard/auth/logout");
    expect(JSON.parse(String(last[1]!.body))).toEqual({ refresh_token: "r2" });
  });

  it("signs out even with no network", async () => {
    const store = memoryStore({ access: "a1", refresh: "r1" });
    const fetchImpl = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    });
    const api = new DashboardApi("", store, () => {}, fetchImpl as typeof fetch);

    await expect(api.logout()).resolves.toBeUndefined();
    expect(store.get()).toBeNull();
  });

  it("sends a decision with its reason, severity and the version it was made against", async () => {
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
      json(200, { result_id: "r1", action: "flagged", status: "flagged", message: "ok", review_status: "needs_review", review_version: 3 }),
    );
    const api = new DashboardApi("", memoryStore({ access: "a", refresh: "r" }), () => {}, fetchImpl as typeof fetch);

    await api.act("r1", { action: "flagged", reason: "form_issue", notes: "  hips drop  ", severity: "high", expectedVersion: 2 });

    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(String(url)).toBe("/api/dashboard/reviews/r1/action");
    expect(JSON.parse(String(init!.body))).toEqual({
      action: "flagged",
      reason: "form_issue",
      notes: "hips drop",
      severity: "high",
      expected_version: 2,
    });
  });

  it("puts every queue filter in the query string for the server to apply", async () => {
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => json(200, [], { "X-Total-Count": "0" }));
    const api = new DashboardApi("", memoryStore({ access: "a", refresh: "r" }), () => {}, fetchImpl as typeof fetch);

    await api.reviewQueue({
      reviewStatus: "needs_review,invalid",
      sessionId: "s1",
      athlete: "Asha K",
      flags: "high",
      submittedFrom: "2026-10-01T00:00:00.000Z",
      submittedTo: "2026-10-02T00:00:00.000Z",
    });

    const url = new URL(String(fetchImpl.mock.calls[0]![0]), "http://x");
    expect(url.pathname).toBe("/api/dashboard/reviews");
    expect(Object.fromEntries(url.searchParams)).toEqual({
      review_status: "needs_review,invalid",
      session_id: "s1",
      athlete: "Asha K",
      flags: "high",
      submitted_from: "2026-10-01T00:00:00.000Z",
      submitted_to: "2026-10-02T00:00:00.000Z",
      limit: "25",
      offset: "0",
    });
  });

  it("surfaces validation messages from the server", async () => {
    const fetchImpl = vi.fn(async () =>
      json(422, { detail: "Explain the decision in the notes — the athlete will see them" }),
    );
    const api = new DashboardApi("", memoryStore({ access: "a", refresh: "r" }), () => {}, fetchImpl as typeof fetch);

    await expect(api.act("id", { action: "rejected", reason: "other", notes: "" })).rejects.toThrow(
      "Explain the decision",
    );
  });
});

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

  it("surfaces validation messages from the server", async () => {
    const fetchImpl = vi.fn(async () =>
      json(422, { detail: "Explain the decision in the notes — the athlete will see them" }),
    );
    const api = new DashboardApi("", memoryStore({ access: "a", refresh: "r" }), () => {}, fetchImpl as typeof fetch);

    await expect(api.act("id", "rejected", "")).rejects.toThrow("Explain the decision");
  });
});

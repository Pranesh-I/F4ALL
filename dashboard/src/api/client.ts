import type {
  AssessmentSession,
  DashboardStats,
  Leaderboard,
  OfficialProfile,
  NewAssessmentSession,
  OfficialTokens,
  PoseSequence,
  ReviewActionName,
  ReviewDetail,
  ReviewItem,
  ReviewQueuePage,
} from "./types";

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/**
 * Session storage, not local storage: the refresh token for an account that
 * approves children's results should not outlive the browser tab on a shared
 * office machine.
 */
export interface TokenStore {
  get(): { access: string; refresh: string } | null;
  set(tokens: { access: string; refresh: string }): void;
  clear(): void;
}

const STORAGE_KEY = "f4all.dashboard.session";

export const sessionTokenStore: TokenStore = {
  get() {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      return raw ? (JSON.parse(raw) as { access: string; refresh: string }) : null;
    } catch {
      return null;
    }
  },
  set(tokens) {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(tokens));
  },
  clear() {
    sessionStorage.removeItem(STORAGE_KEY);
  },
};

type Fetch = typeof fetch;

export interface QueueFilters {
  status?: string;
  testType?: string;
  region?: string;
  limit?: number;
  offset?: number;
}

export interface LeaderboardFilters {
  testType: string;
  region?: string;
  gender?: string;
  ageMin?: number;
  ageMax?: number;
}

export class DashboardApi {
  private refreshing: Promise<boolean> | null = null;

  constructor(
    private readonly baseUrl: string,
    private readonly tokens: TokenStore,
    private readonly onSessionExpired: () => void = () => {},
    private readonly fetchImpl: Fetch = (...args) => fetch(...args),
  ) {}

  // -- auth ----------------------------------------------------------------

  async login(email: string, password: string): Promise<OfficialProfile> {
    const body = await this.send<OfficialTokens>(
      "POST",
      "/api/dashboard/auth/login",
      { email, password },
      { auth: false },
    );
    this.tokens.set({ access: body.access_token, refresh: body.refresh_token });
    return body.official;
  }

  me(): Promise<OfficialProfile> {
    return this.send("GET", "/api/dashboard/auth/me");
  }

  logout(): void {
    this.tokens.clear();
  }

  hasSession(): boolean {
    return this.tokens.get() !== null;
  }

  // -- reviews -------------------------------------------------------------

  async reviewQueue(filters: QueueFilters): Promise<ReviewQueuePage> {
    const params = new URLSearchParams();
    if (filters.status) params.set("status", filters.status);
    if (filters.testType) params.set("test_type", filters.testType);
    if (filters.region) params.set("region", filters.region);
    params.set("limit", String(filters.limit ?? 25));
    params.set("offset", String(filters.offset ?? 0));

    const response = await this.request("GET", `/api/dashboard/reviews?${params}`);
    const items = (await response.json()) as ReviewItem[];
    const total = Number(response.headers.get("X-Total-Count") ?? items.length);
    return { items, total: Number.isFinite(total) ? total : items.length };
  }

  review(resultId: string): Promise<ReviewDetail> {
    return this.send("GET", `/api/dashboard/reviews/${encodeURIComponent(resultId)}`);
  }

  poseSequence(resultId: string): Promise<PoseSequence> {
    return this.send("GET", `/api/dashboard/reviews/${encodeURIComponent(resultId)}/pose`);
  }

  act(
    resultId: string,
    action: ReviewActionName,
    notes: string,
    finalScore?: number,
  ): Promise<{ status: string; message: string }> {
    return this.send("POST", `/api/dashboard/reviews/${encodeURIComponent(resultId)}/action`, {
      action,
      notes: notes.trim() || null,
      ...(finalScore !== undefined ? { final_score: finalScore } : {}),
    });
  }

  leaderboard(filters: LeaderboardFilters): Promise<Leaderboard> {
    const params = new URLSearchParams({ test_type: filters.testType });
    if (filters.region) params.set("region", filters.region);
    if (filters.gender) params.set("gender", filters.gender);
    if (filters.ageMin !== undefined) params.set("age_min", String(filters.ageMin));
    if (filters.ageMax !== undefined) params.set("age_max", String(filters.ageMax));
    return this.send("GET", `/api/dashboard/leaderboard?${params}`);
  }

  stats(): Promise<DashboardStats> {
    return this.send("GET", "/api/dashboard/stats");
  }

  // -- assessment sessions -------------------------------------------------

  sessions(): Promise<AssessmentSession[]> {
    return this.send("GET", "/api/dashboard/sessions");
  }

  createSession(session: NewAssessmentSession): Promise<AssessmentSession> {
    return this.send("POST", "/api/dashboard/sessions", session);
  }

  updateSession(
    sessionId: string,
    changes: Partial<NewAssessmentSession>,
  ): Promise<AssessmentSession> {
    return this.send("PATCH", `/api/dashboard/sessions/${encodeURIComponent(sessionId)}`, changes);
  }

  deleteSession(sessionId: string): Promise<{ message: string }> {
    return this.send("DELETE", `/api/dashboard/sessions/${encodeURIComponent(sessionId)}`);
  }

  // -- plumbing ------------------------------------------------------------

  private async send<T>(
    method: string,
    path: string,
    body?: unknown,
    options: { auth?: boolean } = {},
  ): Promise<T> {
    const response = await this.request(method, path, body, options);
    return (await response.json()) as T;
  }

  private async request(
    method: string,
    path: string,
    body?: unknown,
    { auth = true }: { auth?: boolean } = {},
  ): Promise<Response> {
    let response = await this.raw(method, path, body, auth);

    // One refresh and one retry. More than that and a revoked session loops.
    if (response.status === 401 && auth && (await this.refresh())) {
      response = await this.raw(method, path, body, auth);
    }

    if (response.status === 401 && auth) {
      this.tokens.clear();
      this.onSessionExpired();
    }

    if (!response.ok) {
      throw new ApiError(response.status, await errorMessage(response));
    }

    return response;
  }

  private raw(method: string, path: string, body: unknown, auth: boolean) {
    const headers: Record<string, string> = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";

    const session = this.tokens.get();
    if (auth && session) headers.Authorization = `Bearer ${session.access}`;

    return this.fetchImpl(this.baseUrl + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  /**
   * Shared across concurrent requests. A page firing four queries at once must
   * refresh once: presenting the same refresh token twice is treated by the
   * server as theft and revokes the session.
   */
  private refresh(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = this.doRefresh().finally(() => {
        this.refreshing = null;
      });
    }
    return this.refreshing;
  }

  private async doRefresh(): Promise<boolean> {
    const session = this.tokens.get();
    if (!session) return false;

    try {
      const response = await this.fetchImpl(`${this.baseUrl}/api/dashboard/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: session.refresh }),
      });
      if (!response.ok) return false;
      const body = (await response.json()) as OfficialTokens;
      this.tokens.set({ access: body.access_token, refresh: body.refresh_token });
      return true;
    } catch {
      return false;
    }
  }
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: unknown };
    if (typeof body.detail === "string") return body.detail;
    if (Array.isArray(body.detail)) {
      const first = body.detail[0] as { msg?: string } | undefined;
      if (first?.msg) return first.msg;
    }
  } catch {
    // Not JSON — fall through.
  }
  return `Request failed (${response.status})`;
}

export const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.replace(/\/$/, "") ?? "";

import type {
  AssessmentSession,
  AssessmentSessionChanges,
  DashboardStats,
  Leaderboard,
  OfficialProfile,
  NewAssessmentSession,
  OfficialTokens,
  PoseSequence,
  ReviewActionName,
  ReviewDetail,
  ReviewReason,
  ReviewStatus,
  SessionStatus,
  SubmissionItem,
  SubmissionPage,
  TestInfo,
  VerificationStatus,
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

/** Filters shared by the review queue and the submission list; all applied by the server. */
export interface SubmissionFilters {
  /** Comma-separated result statuses; every status when omitted. */
  status?: string;
  /** Comma-separated review statuses (the queue defaults to work awaiting a decision). */
  reviewStatus?: string;
  testType?: string;
  region?: string;
  sessionId?: string;
  /** An athlete id, or part of the name. */
  athlete?: string;
  /** open | none | low | medium | high (at least that severity, unresolved). */
  flags?: string;
  /** ISO instants: from inclusive, to exclusive. */
  submittedFrom?: string;
  submittedTo?: string;
  limit?: number;
  offset?: number;
}

export type QueueFilters = SubmissionFilters;

/** One review action as the reviewer chose it; the server decides whether it is allowed. */
export interface ReviewDecision {
  action: ReviewActionName;
  reason?: ReviewReason;
  notes: string;
  /** Only when approving a result the server could not score. */
  finalScore?: number;
  /** Only for `flagged`. */
  severity?: "low" | "medium" | "high";
  /** The review_version the reviewer was looking at; a stale one is refused with 409. */
  expectedVersion?: number;
}

export interface ReviewActionResult {
  result_id: string;
  action: ReviewActionName;
  status: string;
  message: string;
  review_status: ReviewStatus | null;
  review_version: number | null;
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

  /**
   * Revoke this tab's refresh token on the server, then forget both tokens.
   *
   * The tokens are dropped before the first await, so signing straight back
   * in cannot be undone by this call finishing late. Never throws: signing out
   * must work offline too, and an unrevoked access token lapses within the hour.
   */
  async logout(): Promise<void> {
    const session = this.tokens.get();
    this.tokens.clear();
    if (!session) return;
    try {
      const response = await this.postLogout(session);
      if (response.status === 401) {
        // The access token lapsed. Exchange the refresh token once so the
        // revocation can be authorised; rotating it has already retired it.
        const renewed = await this.exchange(session.refresh);
        if (renewed) await this.postLogout(renewed);
      }
    } catch {
      // Offline. Nothing else to do.
    }
  }

  /** Forget the tokens without telling the server (they are already invalid). */
  clearSession(): void {
    this.tokens.clear();
  }

  hasSession(): boolean {
    return this.tokens.get() !== null;
  }

  // -- reviews -------------------------------------------------------------

  async reviewQueue(filters: QueueFilters): Promise<SubmissionPage> {
    const response = await this.request("GET", `/api/dashboard/reviews?${listParams(filters)}`);
    const items = (await response.json()) as SubmissionItem[];
    return { items, total: totalCount(response, items.length) };
  }

  // -- submissions (Sprint 13) -----------------------------------------------

  async submissions(filters: SubmissionFilters = {}): Promise<SubmissionPage> {
    const response = await this.request("GET", `/api/dashboard/submissions?${listParams(filters)}`);
    const items = (await response.json()) as SubmissionItem[];
    return { items, total: totalCount(response, items.length) };
  }

  /** The tests the backend can assess — never a hard-coded list. */
  tests(): Promise<TestInfo[]> {
    return this.send("GET", "/api/dashboard/tests");
  }

  review(resultId: string): Promise<ReviewDetail> {
    return this.send("GET", `/api/dashboard/reviews/${encodeURIComponent(resultId)}`);
  }

  verification(resultId: string): Promise<VerificationStatus> {
    return this.send("GET", `/api/verification/${encodeURIComponent(resultId)}`);
  }

  poseSequence(resultId: string): Promise<PoseSequence> {
    return this.send("GET", `/api/dashboard/reviews/${encodeURIComponent(resultId)}/pose`);
  }

  act(resultId: string, decision: ReviewDecision): Promise<ReviewActionResult> {
    return this.send("POST", `/api/dashboard/reviews/${encodeURIComponent(resultId)}/action`, {
      action: decision.action,
      notes: decision.notes.trim() || null,
      ...(decision.reason ? { reason: decision.reason } : {}),
      ...(decision.finalScore !== undefined ? { final_score: decision.finalScore } : {}),
      ...(decision.severity ? { severity: decision.severity } : {}),
      ...(decision.expectedVersion !== undefined ? { expected_version: decision.expectedVersion } : {}),
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

  /** `status` filters on the state the server computes, not the browser's clock. */
  sessions(filters: { status?: SessionStatus[] } = {}): Promise<AssessmentSession[]> {
    const query = filters.status?.length ? `?status=${filters.status.join(",")}` : "";
    return this.send("GET", `/api/dashboard/sessions${query}`);
  }

  session(sessionId: string): Promise<AssessmentSession> {
    return this.send("GET", `/api/dashboard/sessions/${encodeURIComponent(sessionId)}`);
  }

  createSession(session: NewAssessmentSession): Promise<AssessmentSession> {
    return this.send("POST", "/api/dashboard/sessions", session);
  }

  updateSession(sessionId: string, changes: AssessmentSessionChanges): Promise<AssessmentSession> {
    return this.send("PATCH", `/api/dashboard/sessions/${encodeURIComponent(sessionId)}`, changes);
  }

  /** Close an active session now, by the server's clock. */
  endSession(sessionId: string): Promise<AssessmentSession> {
    return this.send("POST", `/api/dashboard/sessions/${encodeURIComponent(sessionId)}/end`);
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

    const renewed = await this.exchange(session.refresh);
    if (!renewed) return false;
    this.tokens.set(renewed);
    return true;
  }

  /** Trade a refresh token for a new pair; null when the server refuses. */
  private async exchange(refreshToken: string): Promise<{ access: string; refresh: string } | null> {
    try {
      const response = await this.fetchImpl(`${this.baseUrl}/api/dashboard/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: refreshToken }),
      });
      if (!response.ok) return null;
      const body = (await response.json()) as OfficialTokens;
      return { access: body.access_token, refresh: body.refresh_token };
    } catch {
      return null;
    }
  }

  private postLogout(session: { access: string; refresh: string }): Promise<Response> {
    return this.fetchImpl(`${this.baseUrl}/api/dashboard/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${session.access}` },
      body: JSON.stringify({ refresh_token: session.refresh }),
    });
  }
}

function listParams(filters: SubmissionFilters): URLSearchParams {
  const params = new URLSearchParams();
  const names: [keyof SubmissionFilters, string][] = [
    ["status", "status"],
    ["reviewStatus", "review_status"],
    ["testType", "test_type"],
    ["region", "region"],
    ["sessionId", "session_id"],
    ["athlete", "athlete"],
    ["flags", "flags"],
    ["submittedFrom", "submitted_from"],
    ["submittedTo", "submitted_to"],
  ];
  for (const [key, name] of names) {
    const value = filters[key];
    if (value !== undefined && value !== "") params.set(name, String(value));
  }
  params.set("limit", String(filters.limit ?? 25));
  params.set("offset", String(filters.offset ?? 0));
  return params;
}

/** Paged lists keep the body a plain array and put the total in a header. */
function totalCount(response: Response, fallback: number): number {
  const total = Number(response.headers.get("X-Total-Count") ?? fallback);
  return Number.isFinite(total) ? total : fallback;
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

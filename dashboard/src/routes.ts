/** Every dashboard address in one place, so links cannot drift from the routes. */
export const paths = {
  login: "/admin/login",
  dashboard: "/admin/dashboard",
  sessions: "/admin/sessions",
  newSession: "/admin/sessions/create",
  session: (id: string) => `/admin/sessions/${encodeURIComponent(id)}`,
  submissions: "/admin/submissions",
  submission: (id: string) => `/admin/submissions/${encodeURIComponent(id)}`,
  reviews: "/admin/reviews",
  review: (id: string) => `/admin/reviews/${encodeURIComponent(id)}`,
  athletes: "/admin/athletes",
  athlete: (id: string) => `/admin/athletes/${encodeURIComponent(id)}`,
  leaderboards: "/admin/leaderboards",
  analytics: "/admin/analytics",
} as const;

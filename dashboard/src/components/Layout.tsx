import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function Layout() {
  const { official, logout } = useAuth();

  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded px-3 py-2 text-sm font-medium ${
      isActive ? "bg-slate-900 text-white" : "text-slate-700 hover:bg-slate-200"
    }`;

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="flex items-center gap-6">
            <span className="font-semibold">SAI Talent Assessment · Review</span>
            <nav className="flex gap-1">
              <NavLink to="/reviews" className={link}>
                Review queue
              </NavLink>
              <NavLink to="/leaderboard" className={link}>
                Leaderboard
              </NavLink>
              <NavLink to="/sessions" className={link}>
                Sessions
              </NavLink>
            </nav>
          </div>
          {official && (
            <div className="flex items-center gap-3 text-sm">
              <span className="text-slate-600">
                {official.name} ·{" "}
                {official.role === "sai_admin" ? "All regions" : official.region ?? "No region assigned"}
              </span>
              <button
                type="button"
                onClick={logout}
                className="rounded border border-slate-300 px-3 py-1.5 hover:bg-slate-100"
              >
                Sign out
              </button>
            </div>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}

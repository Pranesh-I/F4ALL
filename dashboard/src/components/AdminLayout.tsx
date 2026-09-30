import { useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { roleLabel } from "../lib/format";
import { paths } from "../routes";

const NAVIGATION = [
  { to: paths.dashboard, label: "Dashboard" },
  { to: paths.sessions, label: "Sessions" },
  { to: paths.submissions, label: "Submissions" },
  { to: paths.reviews, label: "Review queue" },
  { to: paths.athletes, label: "Athletes" },
  { to: paths.leaderboards, label: "Leaderboards" },
  { to: paths.analytics, label: "Analytics" },
] as const;

/** The admin shell: sidebar navigation, who is signed in, and the page. */
export function AdminLayout() {
  const { official, logout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);

  const link = ({ isActive }: { isActive: boolean }) =>
    `block rounded px-3 py-2 text-sm font-medium ${
      isActive ? "bg-slate-900 text-white" : "text-slate-700 hover:bg-slate-200"
    }`;

  function signOut() {
    void logout();
    navigate(paths.login, { replace: true });
  }

  return (
    <div className="min-h-screen lg:flex">
      <aside className="border-b border-slate-200 bg-white lg:w-60 lg:shrink-0 lg:border-r lg:border-b-0">
        <div className="flex items-center justify-between px-4 py-3">
          <span className="font-semibold">SAI Talent Assessment</span>
          <button
            type="button"
            onClick={() => setMenuOpen((open) => !open)}
            aria-expanded={menuOpen}
            aria-controls="admin-navigation"
            className="rounded border border-slate-300 px-2 py-1 text-sm lg:hidden"
          >
            Menu
          </button>
        </div>
        <nav
          id="admin-navigation"
          aria-label="Admin"
          className={`${menuOpen ? "block" : "hidden"} space-y-1 px-3 pb-3 lg:block`}
        >
          {NAVIGATION.map((item) => (
            <NavLink key={item.to} to={item.to} className={link} onClick={() => setMenuOpen(false)}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      <div className="min-w-0 flex-1">
        <header className="border-b border-slate-200 bg-white">
          <div className="flex flex-wrap items-center justify-end gap-3 px-4 py-3 text-sm">
            {official && (
              <>
                <span className="text-slate-600">
                  <span className="font-medium text-slate-900">{official.name}</span> · {roleLabel(official)}
                </span>
                <button
                  type="button"
                  onClick={signOut}
                  className="rounded border border-slate-300 px-3 py-1.5 hover:bg-slate-100"
                >
                  Sign out
                </button>
              </>
            )}
          </div>
        </header>
        <main className="mx-auto max-w-7xl px-4 py-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

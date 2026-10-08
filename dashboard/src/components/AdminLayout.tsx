import { useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { roleLabel } from "../lib/format";
import { ui } from "../lib/ui";
import { paths } from "../routes";
import { LaneLines } from "./Trials";

const NAVIGATION = [
  { to: paths.dashboard, label: "Dashboard" },
  { to: paths.sessions, label: "Sessions" },
  { to: paths.submissions, label: "Submissions" },
  { to: paths.reviews, label: "Review queue" },
  { to: paths.athletes, label: "Athletes" },
  { to: paths.leaderboards, label: "Leaderboards" },
  { to: paths.analytics, label: "Analytics" },
] as const;

/**
 * The officials' desk: the ink sidebar with the sections, who is signed in,
 * and the page on paper-coloured ground.
 */
export function AdminLayout() {
  const { official, logout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);

  const link = ({ isActive }: { isActive: boolean }) =>
    `flex items-center justify-between rounded-xs px-3 py-2 text-sm font-semibold transition-colors ${
      isActive ? "bg-paper text-ink" : "text-ink-muted hover:bg-ink-raised hover:text-paper"
    }`;

  function signOut() {
    void logout();
    navigate(paths.login, { replace: true });
  }

  return (
    <div className="min-h-screen lg:flex">
      <aside className="on-ink bg-ink text-paper lg:sticky lg:top-0 lg:h-screen lg:w-60 lg:shrink-0">
        <div className="flex items-center justify-between px-4 py-4">
          <div>
            <div className="label-caps text-sm text-paper">SAI Talent Assessment</div>
            <div className="label-caps text-xs text-marigold">Officials&apos; desk</div>
          </div>
          <button
            type="button"
            onClick={() => setMenuOpen((open) => !open)}
            aria-expanded={menuOpen}
            aria-controls="admin-navigation"
            className="rounded-xs border border-ink-muted px-3 py-1 text-sm font-semibold text-paper lg:hidden"
          >
            Menu
          </button>
        </div>
        <LaneLines className="mx-4 text-marigold" />
        <nav
          id="admin-navigation"
          aria-label="Admin"
          className={`${menuOpen ? "block" : "hidden"} space-y-1 px-3 py-4 lg:block`}
        >
          {NAVIGATION.map((item) => (
            <NavLink key={item.to} to={item.to} className={link} onClick={() => setMenuOpen(false)}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      <div className="min-w-0 flex-1">
        <header className="bg-paper">
          <div className="flex flex-wrap items-center justify-end gap-3 px-4 py-3 text-sm">
            {official && (
              <>
                <span className="text-pencil">
                  <span className="font-semibold text-ink">{official.name}</span> · {roleLabel(official)}
                </span>
                <button type="button" onClick={signOut} className={ui.buttonLine}>
                  Sign out
                </button>
              </>
            )}
          </div>
          <LaneLines className="text-ink" />
        </header>
        <main className="mx-auto max-w-7xl px-4 py-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

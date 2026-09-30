import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import type { OfficialRole } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { paths } from "../routes";
import { LoadingState, NotPermitted } from "./StateViews";

/**
 * Only a signed-in official gets past this; `roles` narrows it further.
 *
 * A convenience, not the protection: every endpoint behind these pages checks
 * the token and role itself, so opening a URL directly gets nothing the API
 * would not already give that account.
 */
export function ProtectedRoute({
  children,
  roles,
  deniedMessage,
}: {
  children: ReactNode;
  roles?: OfficialRole[];
  deniedMessage?: string;
}) {
  const { official, restoring, signedOut } = useAuth();
  const location = useLocation();

  if (restoring) return <LoadingState label="Checking your session…" />;

  if (!official) {
    // After an explicit sign-out there is no page to come back to: the next
    // person to sign in on this tab should not land where the last one was.
    const state = signedOut ? null : { from: location.pathname + location.search };
    return <Navigate to={paths.login} replace state={state} />;
  }

  if (roles && !roles.includes(official.role)) return <NotPermitted>{deniedMessage}</NotPermitted>;

  return <>{children}</>;
}

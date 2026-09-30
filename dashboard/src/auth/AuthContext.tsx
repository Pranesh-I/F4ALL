import { useQueryClient } from "@tanstack/react-query";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { DashboardApi } from "../api/client";
import type { OfficialProfile } from "../api/types";

interface AuthState {
  api: DashboardApi;
  official: OfficialProfile | null;
  /** True until a stored session has been checked against the server. */
  restoring: boolean;
  /**
   * True after an explicit sign-out, until the next sign-in. Whoever signs in
   * next on this tab starts at the dashboard, not on the last official's page.
   */
  signedOut: boolean;
  login(email: string, password: string): Promise<void>;
  /** Signs out here at once; the server revokes the refresh token in the background. */
  logout(): Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({
  api,
  children,
}: {
  api: DashboardApi;
  children: ReactNode;
}) {
  const queryClient = useQueryClient();
  const [official, setOfficial] = useState<OfficialProfile | null>(null);
  const [restoring, setRestoring] = useState(() => api.hasSession());
  const [signedOut, setSignedOut] = useState(false);

  useEffect(() => {
    if (!api.hasSession()) return;
    let cancelled = false;
    api
      .me()
      .then((profile) => {
        if (!cancelled) setOfficial(profile);
      })
      .catch(() => {
        if (!cancelled) {
          // The stored session is already dead; there is nothing to revoke.
          api.clearSession();
          setOfficial(null);
        }
      })
      .finally(() => {
        if (!cancelled) setRestoring(false);
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const login = useCallback(
    async (email: string, password: string) => {
      const profile = await api.login(email, password);
      setSignedOut(false);
      setOfficial(profile);
    },
    [api],
  );

  const logout = useCallback(() => {
    // Drops the tokens synchronously, then revokes on the server.
    const revoked = api.logout();
    setSignedOut(true);
    setOfficial(null);
    // Nothing one official loaded may be shown to whoever signs in next.
    queryClient.clear();
    return revoked;
  }, [api, queryClient]);

  const value = useMemo(
    () => ({ api, official, restoring, signedOut, login, logout }),
    [api, official, restoring, signedOut, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error("useAuth must be used inside AuthProvider");
  return state;
}

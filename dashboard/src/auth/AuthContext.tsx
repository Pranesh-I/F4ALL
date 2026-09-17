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
  login(email: string, password: string): Promise<void>;
  logout(): void;
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
          api.logout();
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
      setOfficial(profile);
    },
    [api],
  );

  const logout = useCallback(() => {
    api.logout();
    setOfficial(null);
    // Nothing one official loaded may be shown to whoever signs in next.
    queryClient.clear();
  }, [api, queryClient]);

  const value = useMemo(
    () => ({ api, official, restoring, login, logout }),
    [api, official, restoring, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error("useAuth must be used inside AuthProvider");
  return state;
}

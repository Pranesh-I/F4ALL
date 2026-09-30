import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { Route, RouterProvider, Routes, createMemoryRouter, useLocation } from "react-router-dom";
import { DashboardApi, type TokenStore } from "../api/client";
import type { OfficialRole } from "../api/types";
import { AuthProvider } from "../auth/AuthContext";
import { CATALOG, official } from "./fixtures";

export function memoryStore(signedIn: boolean): TokenStore {
  let value = signedIn ? { access: "a", refresh: "r" } : null;
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

/**
 * An API whose network calls are all stubs. `role: null` is a signed-out
 * browser. Anything a test does not stub rejects, so an unexpected request
 * fails loudly instead of reaching for the network.
 */
export function makeApi(role: OfficialRole | null, stubs: Partial<Record<keyof DashboardApi, unknown>> = {}) {
  const unexpected = (name: string) => vi.fn(async () => {
    throw new Error(`unexpected API call: ${name}`);
  });
  const api = new DashboardApi("", memoryStore(role !== null), () => {}, unexpected("fetch") as typeof fetch);
  api.me = role ? vi.fn(async () => official(role)) : (unexpected("me") as DashboardApi["me"]);
  api.tests = vi.fn(async () => CATALOG);
  api.logout = vi.fn(async () => {});
  Object.assign(api, stubs);
  return api;
}

/** Shows where the router ended up, for assertions about redirects. */
export function CurrentPath() {
  const location = useLocation();
  return <div data-testid="path">{location.pathname + location.search}</div>;
}

export function renderWithProviders(
  ui: ReactElement,
  { api, route = "/", path }: { api: DashboardApi; route?: string; path?: string },
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // A data router, as in main.tsx, so pages using useBlocker work under test.
  const router = createMemoryRouter(
    [
      {
        path: "*",
        element: (
          <AuthProvider api={api}>
            {path ? (
              <Routes>
                <Route path={path} element={ui} />
                <Route path="*" element={<CurrentPath />} />
              </Routes>
            ) : (
              ui
            )}
          </AuthProvider>
        ),
      },
    ],
    { initialEntries: [route] },
  );
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...view, router, queryClient };
}

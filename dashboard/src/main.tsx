import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider, createBrowserRouter } from "react-router-dom";
import { ApiError, DashboardApi, apiBaseUrl, sessionTokenStore } from "./api/client";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthContext";
import { paths } from "./routes";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Retrying a 4xx only repeats a refusal; retry network trouble only.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status < 500) && failureCount < 2,
      refetchOnWindowFocus: false,
    },
  },
});

const api = new DashboardApi(apiBaseUrl, sessionTokenStore, () => {
  // The session is gone; send the official back to sign in.
  if (window.location.pathname !== paths.login) window.location.assign(paths.login);
});

// A data router around the existing <Routes> tree, so pages can use
// useBlocker (the review page warns before unsaved notes are lost).
const router = createBrowserRouter([
  {
    path: "*",
    element: (
      <AuthProvider api={api}>
        <App />
      </AuthProvider>
    ),
  },
]);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);

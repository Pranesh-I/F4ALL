import type { ReactNode } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { Layout } from "./components/Layout";
import { LeaderboardPage } from "./pages/LeaderboardPage";
import { LoginPage } from "./pages/LoginPage";
import { QueuePage } from "./pages/QueuePage";
import { ReviewPage } from "./pages/ReviewPage";

function RequireOfficial({ children }: { children: ReactNode }) {
  const { official, restoring } = useAuth();
  const location = useLocation();

  if (restoring) {
    return <p className="p-6 text-slate-500">Checking your session…</p>;
  }

  if (!official) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }

  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireOfficial>
            <Layout />
          </RequireOfficial>
        }
      >
        <Route path="/reviews" element={<QueuePage />} />
        <Route path="/reviews/:resultId" element={<ReviewPage />} />
        <Route path="/leaderboard" element={<LeaderboardPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/reviews" replace />} />
    </Routes>
  );
}

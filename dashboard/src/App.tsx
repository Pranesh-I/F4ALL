import { Navigate, Route, Routes, useLocation, useParams } from "react-router-dom";
import { AdminLayout } from "./components/AdminLayout";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { DashboardPage } from "./pages/DashboardPage";
import { LeaderboardPage } from "./pages/LeaderboardPage";
import { LoginPage } from "./pages/LoginPage";
import { AnalyticsPage, AthleteDetailPage, AthletesPage, NotFoundPage } from "./pages/PlaceholderPages";
import { QueuePage } from "./pages/QueuePage";
import { ReviewPage } from "./pages/ReviewPage";
import { SessionCreatePage } from "./pages/SessionCreatePage";
import { SessionDetailPage } from "./pages/SessionDetailPage";
import { SessionsPage } from "./pages/SessionsPage";
import { SubmissionDetailPage } from "./pages/SubmissionDetailPage";
import { SubmissionsPage } from "./pages/SubmissionsPage";
import { paths } from "./routes";

/** Addresses from before Sprint 13 keep working, including their filters. */
function MovedTo({ to }: { to: string }) {
  const { search } = useLocation();
  return <Navigate to={to + search} replace />;
}

function MovedReview() {
  const { resultId = "" } = useParams();
  return <Navigate to={paths.review(resultId)} replace />;
}

export function App() {
  return (
    <Routes>
      <Route path={paths.login} element={<LoginPage />} />
      <Route
        path="/admin"
        element={
          <ProtectedRoute>
            <AdminLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to={paths.dashboard} replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="sessions" element={<SessionsPage />} />
        <Route
          path="sessions/create"
          element={
            <ProtectedRoute roles={["sai_admin"]} deniedMessage="Only an SAI admin can create assessment sessions.">
              <SessionCreatePage />
            </ProtectedRoute>
          }
        />
        <Route path="sessions/:sessionId" element={<SessionDetailPage />} />
        <Route path="submissions" element={<SubmissionsPage />} />
        <Route path="submissions/:resultId" element={<SubmissionDetailPage />} />
        <Route path="reviews" element={<QueuePage />} />
        <Route path="reviews/:resultId" element={<ReviewPage />} />
        <Route path="athletes" element={<AthletesPage />} />
        <Route path="athletes/:athleteId" element={<AthleteDetailPage />} />
        <Route path="leaderboards" element={<LeaderboardPage />} />
        <Route path="analytics" element={<AnalyticsPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>

      <Route path="/login" element={<MovedTo to={paths.login} />} />
      <Route path="/reviews" element={<MovedTo to={paths.reviews} />} />
      <Route path="/reviews/:resultId" element={<MovedReview />} />
      <Route path="/leaderboard" element={<MovedTo to={paths.leaderboards} />} />
      <Route path="/sessions" element={<MovedTo to={paths.sessions} />} />
      <Route path="*" element={<Navigate to={paths.dashboard} replace />} />
    </Routes>
  );
}

import { useState, type FormEvent } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { paths } from "../routes";
import { LaneLines } from "../components/Trials";
import { ui } from "../lib/ui";

export function LoginPage() {
  const { official, login } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const from = (location.state as { from?: string } | null)?.from ?? paths.dashboard;

  if (official) return <Navigate to={from} replace />;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : "Could not reach the server. Check your connection.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    // The floodlit field: the officials' desk opens on ink, the form on paper.
    <div className="on-ink flex min-h-screen flex-col items-center justify-center gap-6 bg-ink px-4 py-10">
      <div className="w-full max-w-sm text-paper">
        <div className="label-caps text-sm">SAI Talent Assessment</div>
        <div className="label-caps text-xs text-marigold">Officials&apos; desk</div>
        <LaneLines className="mt-3 text-marigold" />
      </div>
      <form
        onSubmit={submit}
        className="w-full max-w-sm space-y-4 rounded-xs bg-paper p-6 text-ink [&_:focus-visible]:outline-ink"
      >
        <div>
          <h1 className="text-3xl font-bold leading-tight [font-stretch:75%]">Official sign in</h1>
          <p className="mt-1 text-sm text-pencil">SAI talent assessment admin dashboard</p>
        </div>

        <label className="block text-sm">
          <span className="font-medium">Email</span>
          <input
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className={`mt-1 ${ui.input}`}
          />
        </label>

        <label className="block text-sm">
          <span className="font-medium">Password</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className={`mt-1 ${ui.input}`}
          />
        </label>

        {error && (
          <p role="alert" className="text-sm text-redpen">
            {error}
          </p>
        )}

        <button
          type="submit"
          disabled={busy}
          className={`w-full ${ui.buttonMarigold}`}
        >
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}

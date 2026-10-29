import type { ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { PageHeader } from "../components/PageHeader";
import { EmptyState } from "../components/StateViews";
import { paths } from "../routes";

/**
 * Routes whose content belongs to a later sprint. They exist now so the shell,
 * navigation and route protection are complete — and they say plainly that
 * nothing is here yet rather than showing invented numbers.
 */
function NotYetAvailable({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="space-y-5">
      <PageHeader title={title} />
      <EmptyState title="Not available yet.">{children}</EmptyState>
    </div>
  );
}

export function AthletesPage() {
  return (
    <NotYetAvailable title="Athletes">
      The athlete directory is planned for a later sprint. Until then, find an athlete&apos;s tests in{" "}
      <Link to={paths.submissions} className="font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink">
        Submissions
      </Link>
      .
    </NotYetAvailable>
  );
}

export function AthleteDetailPage() {
  const { athleteId = "" } = useParams();
  return (
    <NotYetAvailable title="Athlete">
      Athlete profiles are planned for a later sprint (athlete <span className="font-mono">{athleteId}</span>).
    </NotYetAvailable>
  );
}

export function NotFoundPage() {
  return (
    <NotYetAvailable title="Page not found">
      There is no page at this address.{" "}
      <Link to={paths.dashboard} className="font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink">
        Go to the dashboard
      </Link>
      .
    </NotYetAvailable>
  );
}

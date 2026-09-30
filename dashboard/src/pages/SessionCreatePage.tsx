import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import type { NewAssessmentSession } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { PageHeader } from "../components/PageHeader";
import { SessionForm } from "../components/SessionForm";
import { paths } from "../routes";

/** SAI admins only (the route checks the role; the API checks it again). */
export function SessionCreatePage() {
  const { api } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const create = useMutation({
    mutationFn: (session: NewAssessmentSession) => api.createSession(session),
    onSuccess: (created) => {
      queryClient.setQueryData(["session", created.id], created);
      void queryClient.invalidateQueries({ queryKey: ["sessions"] });
      // The detail page reads it back from the server; the notice is all we carry.
      navigate(paths.session(created.id), { state: { notice: `Session "${created.name}" created.` } });
    },
  });

  return (
    <div className="space-y-5">
      <PageHeader
        title="New assessment session"
        description="Sessions are created switched off unless you enable them. The server decides when an enabled session is active."
        back={
          <Link to={paths.sessions} className="text-blue-700 hover:underline">
            ← Sessions
          </Link>
        }
      />
      <SessionForm
        label="New session"
        submitLabel="Create session"
        pending={create.isPending}
        error={create.error}
        onSubmit={(values) => create.mutate(values)}
        onCancel={() => navigate(paths.sessions)}
      />
    </div>
  );
}

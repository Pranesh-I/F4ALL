import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { testName } from "../lib/format";

/**
 * The tests the backend can assess (GET /api/dashboard/tests). The dashboard
 * offers these, not a list of its own, so it cannot offer a test the server
 * would refuse or miss one it has added. Fetched once per sign-in.
 */
export function useTestCatalog() {
  const { api } = useAuth();
  const query = useQuery({ queryKey: ["tests"], queryFn: () => api.tests(), staleTime: Infinity });
  const names = new Map((query.data ?? []).map((test) => [test.code, test.name]));
  return {
    ...query,
    tests: query.data,
    nameOf: (code: string) => testName(code, names.get(code)),
  };
}

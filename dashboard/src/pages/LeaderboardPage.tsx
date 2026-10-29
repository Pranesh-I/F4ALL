import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useAuth } from "../auth/AuthContext";
import { ErrorState } from "../components/StateViews";
import { REGIONS, TEST_NAMES, formatDateTime, formatScore } from "../lib/format";

const AGE_GROUPS = [
  { key: "", label: "All ages" },
  { key: "9-11", label: "9–11" },
  { key: "12-13", label: "12–13" },
  { key: "14-15", label: "14–15" },
  { key: "16-17", label: "16–17" },
  { key: "18-25", label: "18–25" },
] as const;

export function LeaderboardPage() {
  const { api, official } = useAuth();
  const [testType, setTestType] = useState("SIT_UPS");
  const [region, setRegion] = useState("");
  const [gender, setGender] = useState("");
  const [ageGroup, setAgeGroup] = useState("");
  const [sessionId, setSessionId] = useState("");
  const sessions = useQuery({ queryKey: ["sessions", "all"], queryFn: () => api.sessions() });

  const [ageMin, ageMax] = ageGroup ? ageGroup.split("-").map(Number) : [undefined, undefined];

  const board = useQuery({
    queryKey: ["leaderboard", testType, region, gender, ageGroup, sessionId],
    queryFn: () =>
      api.leaderboard({
        testType,
        region: region || undefined,
        gender: gender || undefined,
        ageMin,
        ageMax,
        sessionId: sessionId || undefined,
      }),
  });

  const select = "rounded-xs border border-line bg-paper px-3 py-2 text-sm";

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold">Leaderboard</h1>
        <p className="text-sm text-pencil">
          Approved results only, best attempt per athlete. Unreviewed scores never appear here.
        </p>
      </div>

      <div className="flex flex-wrap gap-3">
        <select aria-label="Test" value={testType} onChange={(e) => setTestType(e.target.value)} className={select}>
          {Object.entries(TEST_NAMES).map(([code, name]) => (
            <option key={code} value={code}>
              {name}
            </option>
          ))}
        </select>
        {official?.role === "sai_admin" && (
          <select aria-label="Region" value={region} onChange={(e) => setRegion(e.target.value)} className={select}>
            <option value="">All regions</option>
            {REGIONS.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        )}
        <select aria-label="Gender" value={gender} onChange={(e) => setGender(e.target.value)} className={select}>
          <option value="">All genders</option>
          <option value="female">Female</option>
          <option value="male">Male</option>
          <option value="other">Other</option>
        </select>
        <select aria-label="Age group" value={ageGroup} onChange={(e) => setAgeGroup(e.target.value)} className={select}>
          {AGE_GROUPS.map((group) => (
            <option key={group.key} value={group.key}>
              {group.label}
            </option>
          ))}
        </select>
        <select aria-label="Session" value={sessionId} onChange={(e) => setSessionId(e.target.value)} className={select}>
          <option value="">All sessions</option>
          {(sessions.data ?? []).map((session) => (
            <option key={session.id} value={session.id}>
              {session.name}
            </option>
          ))}
        </select>
      </div>

      {board.isError && (
        <ErrorState title="Could not load the leaderboard" error={board.error} onRetry={() => board.refetch()} />
      )}

      <div className="overflow-x-auto rounded-xs border border-ink bg-paper">
        <table className="min-w-full text-sm">
          <thead className="bg-ground text-left text-pencil">
            <tr>
              <th className="px-3 py-2">#</th>
              <th className="px-3 py-2">Athlete</th>
              <th className="px-3 py-2">Region</th>
              <th className="px-3 py-2">Age</th>
              <th className="px-3 py-2 text-right">Score</th>
              <th className="px-3 py-2">Achieved</th>
            </tr>
          </thead>
          <tbody>
            {board.isLoading && (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-pencil">
                  Loading…
                </td>
              </tr>
            )}
            {board.data?.entries.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-pencil">
                  No approved results match these filters.
                </td>
              </tr>
            )}
            {board.data?.entries.map((entry) => (
              <tr key={entry.athlete_id} className="border-t border-rule">
                <td className="px-3 py-2 font-semibold tabular-nums">{entry.rank}</td>
                <td className="px-3 py-2">{entry.athlete_name}</td>
                <td className="px-3 py-2">{entry.region}</td>
                <td className="px-3 py-2 tabular-nums">
                  {entry.age_years} · {entry.gender}
                </td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">
                  {formatScore(entry.score, entry.unit)}
                </td>
                <td className="px-3 py-2 text-pencil">{formatDateTime(entry.achieved_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

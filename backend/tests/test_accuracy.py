"""Sprint 22: the accuracy report's numbers, from a stand-in pipeline.

The real pipeline needs videos and the pose model; what is tested here is
what the report does with its answers — the arithmetic, and the rule that a
recording without a human result is never counted.
"""

from __future__ import annotations

from pathlib import Path

from app import cli
from app.services import accuracy


def row(video, test, human, **extra):
    return {
        "video": video,
        "test": test,
        "human_result": "" if human is None else str(human),
        **extra,
    }


def fake_runner(results: dict[str, tuple]):
    def run(video: Path, test: str, height):
        score, form, flags = results[video.name]
        if isinstance(score, Exception):
            raise score
        return score, "COMPLETE", form, flags

    return run


def test_rep_errors_over_and_under_counts_and_the_target():
    rows = [
        row("a.mp4", "SQUATS", 20, person="P1", device="Redmi 9A", lighting="indoor"),
        row("b.mp4", "SQUATS", 15, person="P2", device="Redmi 9A", lighting="outdoor"),
        row(
            "c.mp4", "SQUATS", 10, person="P2", device="Galaxy A14", notes="baggy clothes"
        ),
        row("d.mp4", "SQUATS", None, person="P3"),  # nobody has counted it yet
    ]
    runner = fake_runner(
        {
            "a.mp4": (19, 90, []),
            "b.mp4": (16, 70, []),
            "c.mp4": (13, 85, []),
            "d.mp4": (40, 90, []),
        }
    )

    cases = accuracy.evaluate(rows, Path("."), runner)
    m = accuracy.metrics_for("SQUATS", cases)

    assert (m.recordings, m.labelled, m.people) == (4, 3, 2)
    # |-1|, |+1|, |+3|: the unlabelled 40 is not in any number.
    assert m.mean_abs_error == round(5 / 3, 2)
    assert m.mean_signed_error == 1.0
    assert m.max_abs_error == 3
    assert (m.over_counted, m.under_counted) == (4, 1)
    assert m.false_positive_rate == 4 / 45
    assert m.within_target == 2 and m.target == 1
    assert [c.row["video"] for c in m.misses] == ["c.mp4"]
    assert [c.row["video"] for c in m.unlabelled] == ["d.mp4"]
    assert m.devices == {"Redmi 9A": 2, "Galaxy A14": 1, "unknown": 1}


def test_form_and_cheat_agreement_only_where_labelled():
    rows = [
        row("a.mp4", "PUSH_UPS", 10, human_form="good", cheat_label="none"),
        row("b.mp4", "PUSH_UPS", 10, human_form="faulty", cheat_label="none"),
        row("c.mp4", "PUSH_UPS", 10, human_form="good", cheat_label="replayed"),
        row("d.mp4", "PUSH_UPS", 10, cheat_label="sped_up"),
        row("e.mp4", "PUSH_UPS", 10),
    ]
    runner = fake_runner(
        {
            "a.mp4": (10, 95, []),
            "b.mp4": (10, 90, []),  # the AI called faulty form good
            "c.mp4": (10, 85, ["looped_frames"]),
            "d.mp4": (10, 85, []),  # a sped-up video not caught
            "e.mp4": (10, 85, ["abrupt_cut"]),  # unlabelled: not counted either way
        }
    )

    m = accuracy.metrics_for("PUSH_UPS", accuracy.evaluate(rows, Path("."), runner))

    assert (m.form_agree, m.form_disagree) == (2, 1)
    assert (m.cheat_true, m.cheat_false, m.cheat_missed) == (1, 0, 1)


def test_a_measured_test_and_a_field_run_entered_by_hand():
    rows = [
        row("j.mp4", "VERTICAL_JUMP", 40.0, height_cm="170"),
        row("", "SHUTTLE_RUN", 11.2, ai_result="11.5"),
    ]
    cases = accuracy.evaluate(rows, Path("."), fake_runner({"j.mp4": (42.5, None, [])}))

    jump = accuracy.metrics_for("VERTICAL_JUMP", cases[:1])
    assert jump.max_abs_error == 2.5 and jump.within_target == 1
    assert jump.over_counted is None  # not a rep test
    shuttle = accuracy.metrics_for("SHUTTLE_RUN", cases[1:])
    assert shuttle.mean_signed_error == 0.3
    assert shuttle.target is None  # no agreed target yet: nothing is called a miss
    assert shuttle.misses == []


def test_a_file_that_fails_is_reported_and_the_rest_carry_on():
    rows = [row("bad.mp4", "SIT_UPS", 12), row("ok.mp4", "SIT_UPS", 12)]
    runner = fake_runner(
        {"bad.mp4": (ValueError("no frames"), None, []), "ok.mp4": (12, 100, [])}
    )

    cases = accuracy.evaluate(rows, Path("."), runner)
    m = accuracy.metrics_for("SIT_UPS", cases)

    assert m.labelled == 1 and m.max_abs_error == 0
    assert m.failed[0].error == "ValueError: no frames"


def test_the_report_claims_nothing_without_a_human_result(tmp_path):
    manifest = tmp_path / "dataset.csv"
    manifest.write_text(
        "# a comment line\nvideo,test,human_result,ai_result\nx.mp4,SIT_UPS,,7\n",
        encoding="utf-8",
    )
    cases = accuracy.evaluate(accuracy.load_manifest(manifest), tmp_path, runner=None)

    text = accuracy.render(cases, manifest)

    assert "**No accuracy claim can be made yet:**" in text
    assert "`x.mp4`: AI 7" in text
    assert "| SIT_UPS | 1 | 0 |" in text


def test_the_cli_writes_the_report_and_the_results(tmp_path, capsys):
    manifest = tmp_path / "dataset.csv"
    manifest.write_text(
        "video,test,human_result,ai_result,person\n"
        "a.mp4,SIT_UPS,12,11,P1\n"
        "b.mp4,SIT_UPS,10,10,P2\n",
        encoding="utf-8",
    )
    report = tmp_path / "REPORT.md"

    assert (
        cli.main(
            [
                "accuracy",
                "--manifest",
                str(manifest),
                "--report",
                str(report),
                "--skip-run",
            ]
        )
        == 0
    )

    text = report.read_text(encoding="utf-8")
    assert "| SIT_UPS | 2 | 2 | 2 | 0.5 reps | 1 reps | 2/2 | ±1 reps |" in text
    assert (tmp_path / "dataset.results.csv").exists()
    assert "2 recording(s), 2 with a human result" in capsys.readouterr().out

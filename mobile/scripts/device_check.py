"""Measure one Android phone for the device matrix (Sprint 21).

    python mobile/scripts/device_check.py                 # the phone plugged in
    python mobile/scripts/device_check.py --apk app-debug.apk --seconds 120
    python mobile/scripts/device_check.py --offline       # adds the guided offline/sync walk

Needs: USB debugging on, the app installed (or --apk), signed in, its language
English (the script finds buttons by their English text), and the camera
permission already granted once. For the pose numbers, stand in front of the
camera (or point it at a person) during the measured run — the model is
slower with nobody in view, which the report also records.

Writes ``docs/device-matrix/<model>.json`` and prints the matrix row. What it
measures, and how:

* **Device:** model, Android version, chipset, RAM, cores, free storage,
  cameras, gyroscope and GPS (the field tests need them).
* **Cold start:** ``am start -W`` after a force-stop, median of 3.
* **Pose pipeline:** the app's ``PosePerf`` log — results per second, frames
  sent, latency from frame to result, frames dropped — and which processor ran
  it (GPU or CPU).
* **Load:** the app's CPU (``top``), memory (PSS, ``dumpsys meminfo``),
  battery temperature and the thermal status, sampled during the run; UI frame
  times (``dumpsys gfxinfo``); battery level change.
* **Camera lifecycle:** released on Home, reopened on resume, then ten
  open/close cycles with no crash and no memory growth beyond 50 MB.
* **Offline (``--offline``):** airplane mode on, you record a practice or
  official test, close and reopen the app, airplane mode off, and the script
  waits for the upload to reach the server.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

PACKAGE = "com.sai.sports"
ACTIVITY = f"{PACKAGE}/.MainActivity"
REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "docs" / "device-matrix"


class Adb:
    def __init__(self, adb: str, serial: str | None) -> None:
        self.base = [adb] + (["-s", serial] if serial else [])

    def run(self, *args: str, timeout: float = 60) -> str:
        done = subprocess.run(
            [*self.base, *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
        return done.stdout

    def sh(self, command: str, timeout: float = 60) -> str:
        return self.run("shell", command, timeout=timeout)


# ---------------------------------------------------------------------------
# Screen navigation, by visible text
# ---------------------------------------------------------------------------


def nodes(adb: Adb) -> list[tuple[str, str, list[int]]]:
    adb.sh("uiautomator dump /sdcard/f4all_ui.xml")
    xml = adb.run("exec-out", "cat", "/sdcard/f4all_ui.xml")
    found = []
    for match in re.finditer(r"<node [^>]*>", xml):
        node = match.group(0)
        text = re.search(r' text="([^"]*)"', node)
        desc = re.search(r'content-desc="([^"]*)"', node)
        bounds = re.search(r'bounds="([^"]*)"', node)
        if bounds:
            found.append(
                (
                    text.group(1) if text else "",
                    desc.group(1) if desc else "",
                    list(map(int, re.findall(r"\d+", bounds.group(1)))),
                )
            )
    return found


def tap_text(adb: Adb, prefix: str, scrolls: int = 6) -> bool:
    for _ in range(scrolls + 1):
        for text, desc, b in nodes(adb):
            if text.startswith(prefix) or desc.startswith(prefix):
                adb.sh(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}")
                return True
        adb.sh("input swipe 540 1800 540 700 300")
        time.sleep(0.8)
    return False


def open_camera(adb: Adb, test_name: str) -> bool:
    adb.sh(f"am start -n {ACTIVITY}")
    time.sleep(2.5)
    if not tap_text(adb, test_name):
        return False
    time.sleep(1.5)
    if not tap_text(adb, "I am ready"):
        return False
    time.sleep(3)
    return camera_open(adb)


def camera_open(adb: Adb) -> bool:
    return f"Client package: {PACKAGE}" in adb.sh("dumpsys media.camera")


# ---------------------------------------------------------------------------
# Measurements
# ---------------------------------------------------------------------------


def device_info(adb: Adb) -> dict:
    prop = lambda name: adb.sh(f"getprop {name}").strip()
    meminfo = adb.sh("cat /proc/meminfo")
    total_kb = int(re.search(r"MemTotal:\s+(\d+)", meminfo).group(1))
    df = adb.sh("df /data").splitlines()[-1].split()
    sensors = adb.sh("dumpsys sensorservice")
    cameras = adb.sh("dumpsys media.camera")
    return {
        "manufacturer": prop("ro.product.manufacturer"),
        "model": prop("ro.product.model"),
        "marketing_name": prop("ro.vendor.oplus.market.name")
        or prop("ro.product.marketname")
        or None,
        "android": prop("ro.build.version.release"),
        "sdk": int(prop("ro.build.version.sdk") or 0),
        "chipset": prop("ro.soc.model") or prop("ro.board.platform"),
        "ram_gb": round(total_kb / 1024 / 1024, 1),
        "cores": int(adb.sh("nproc").strip() or 0),
        "storage_free_gb": round(int(df[3]) / 1024 / 1024, 1) if len(df) > 3 else None,
        "screen": adb.sh("wm size").split(":")[-1].strip(),
        "camera_count": len(set(re.findall(r"Camera ID: (\S+)", cameras))) or None,
        "gyroscope": "gyroscope" in sensors.lower(),
        "gps": "android.hardware.location.gps"
        in adb.sh("pm list features"),
        "app_version": re.search(
            r"versionName=(\S+)", adb.sh(f"dumpsys package {PACKAGE}")
        ).group(1),
    }


def cold_start_ms(adb: Adb, runs: int = 3) -> int:
    times = []
    for _ in range(runs):
        adb.sh(f"am force-stop {PACKAGE}")
        time.sleep(1)
        out = adb.sh(f"am start -W -n {ACTIVITY}")
        match = re.search(r"TotalTime: (\d+)", out)
        if match:
            times.append(int(match.group(1)))
        time.sleep(2)
    return int(statistics.median(times)) if times else -1


def sample(adb: Adb) -> dict:
    pid = adb.sh(f"pidof {PACKAGE}").strip()
    cpu_line = adb.sh(f"top -b -n 1 -p {pid} -o %CPU").strip().splitlines()
    mem = re.search(r"TOTAL PSS:\s+(\d+)", adb.sh(f"dumpsys meminfo {PACKAGE}"))
    battery = adb.sh("dumpsys battery")
    thermal = re.search(r"Thermal Status: (\d+)", adb.sh("dumpsys thermalservice"))
    return {
        "cpu_percent": float(cpu_line[-1]) if cpu_line and cpu_line[-1].strip()
        .replace(".", "", 1).isdigit() else None,
        "pss_mb": round(int(mem.group(1)) / 1024) if mem else None,
        "battery_temp_c": int(re.search(r"temperature: (\d+)", battery).group(1)) / 10,
        "thermal_status": int(thermal.group(1)) if thermal else None,
    }


def battery_level(adb: Adb) -> int:
    return int(re.search(r"level: (\d+)", adb.sh("dumpsys battery")).group(1))


def pose_run(adb: Adb, seconds: int) -> dict:
    adb.run("logcat", "-c")
    adb.sh(f"dumpsys gfxinfo {PACKAGE} reset")
    start_level = battery_level(adb)
    samples = []
    end = time.time() + seconds
    while time.time() < end:
        samples.append(sample(adb))
        time.sleep(8)
    log = adb.run("logcat", "-d", "-s", "PosePerf:I")
    windows = [
        {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", line)}
        for line in log.splitlines()
        if "fps=" in line
    ]
    delegate = re.findall(r"delegate=(\w+)", log)
    gfx = adb.sh(f"dumpsys gfxinfo {PACKAGE}")
    janky = re.search(r"Janky frames: \d+ \(([\d.]+)%\)", gfx)
    p95 = re.search(r"95th percentile: (\d+)ms", gfx)

    def mean(key: str, rows: list[dict]) -> float | None:
        values = [r[key] for r in rows if r.get(key) is not None]
        return round(statistics.mean(values), 1) if values else None

    sent = sum(w.get("sent", 0) for w in windows)
    return {
        "seconds": seconds,
        "delegate": delegate[-1] if delegate else "unknown (app older than Sprint 21)",
        "pose_windows": len(windows),
        "pose_fps_mean": mean("fps", windows),
        "pose_fps_min": min((w["fps"] for w in windows), default=None),
        "latency_ms_mean": mean("latencyAvgMs", windows),
        "latency_ms_worst": max((w["latencyMaxMs"] for w in windows), default=None),
        "dropped_percent": round(100 * sum(w.get("dropped", 0) for w in windows) / sent, 1)
        if sent
        else None,
        "cpu_percent_mean": mean("cpu_percent", samples),
        "pss_mb_mean": mean("pss_mb", samples),
        "pss_mb_max": max((s["pss_mb"] for s in samples if s["pss_mb"]), default=None),
        "battery_temp_start_c": samples[0]["battery_temp_c"] if samples else None,
        "battery_temp_end_c": samples[-1]["battery_temp_c"] if samples else None,
        "thermal_status_max": max(
            (s["thermal_status"] for s in samples if s["thermal_status"] is not None),
            default=None,
        ),
        "battery_level_change": battery_level(adb) - start_level,
        "ui_janky_percent": float(janky.group(1)) if janky else None,
        "ui_frame_p95_ms": int(p95.group(1)) if p95 else None,
    }


def lifecycle(adb: Adb, test_name: str, cycles: int = 10) -> dict:
    adb.run("logcat", "-c")
    result: dict = {}
    adb.sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    result["released_on_home"] = not camera_open(adb)
    adb.sh(f"am start -n {ACTIVITY}")
    time.sleep(3)
    result["reopened_on_resume"] = camera_open(adb)

    pss = []
    opened = 0
    for _ in range(cycles):
        adb.sh("input keyevent KEYCODE_BACK")
        time.sleep(1.2)
        if tap_text(adb, "I am ready", scrolls=1) or (
            tap_text(adb, test_name) and (time.sleep(1.2) or tap_text(adb, "I am ready"))
        ):
            time.sleep(2.5)
            opened += camera_open(adb)
        pss.append(sample(adb)["pss_mb"] or 0)
    log = adb.run("logcat", "-d")
    result.update(
        {
            "cycles": cycles,
            "cycles_camera_opened": opened,
            "pss_growth_mb": (pss[-1] - pss[0]) if pss else None,
            "crashes": len(re.findall(rf"FATAL EXCEPTION|ANR in {PACKAGE}", log)),
        }
    )
    result["passed"] = (
        result["released_on_home"]
        and result["reopened_on_resume"]
        and opened == cycles
        and result["crashes"] == 0
        and (result["pss_growth_mb"] or 0) < 50
    )
    adb.sh("input keyevent KEYCODE_BACK")
    return result


def offline_walk(adb: Adb, api: str) -> dict:
    import urllib.request

    def pending() -> int | None:
        try:
            with urllib.request.urlopen(f"{api}/health/verification", timeout=5) as r:
                body = json.loads(r.read())
            # Submissions the server has: still verifying plus verified today.
            return body["pending"] + body["recent"]["completed"]
        except (OSError, ValueError, KeyError):
            return None

    before = pending()
    adb.sh("cmd connectivity airplane-mode enable")
    input(
        "\nAirplane mode is ON. On the phone: record a test, then close the app "
        "(swipe it away) and open it again. Press Enter here when done… "
    )
    adb.sh("cmd connectivity airplane-mode disable")
    adb.run("reverse", "tcp:8010", "tcp:8010")
    print("Airplane mode OFF. Waiting up to 3 minutes for the upload…")
    deadline = time.time() + 180
    synced = False
    while time.time() < deadline:
        after = pending()
        if before is not None and after is not None and after > before:
            synced = True
            break
        time.sleep(10)
    if before is None:
        synced = input("Could not read the server. Did the result arrive? [y/N] ") == "y"
    return {"recorded_offline": True, "synced_after_reconnect": synced}


# ---------------------------------------------------------------------------


def matrix_row(report: dict) -> str:
    d, p, life = report["device"], report.get("pose", {}), report.get("lifecycle", {})
    off = report.get("offline")

    def pf(ok):
        return "—" if ok is None else ("PASS" if ok else "FAIL")

    return (
        f"| {d.get('marketing_name') or d['model']} ({d['model']}, Android {d['android']},"
        f" {d['chipset']}, {d['ram_gb']} GB) | {pf(life.get('passed'))}"
        f" | {p.get('pose_fps_mean')} ({p.get('delegate')}) | {p.get('latency_ms_mean')} ms"
        f" | {p.get('battery_level_change')}% / {p.get('seconds')} s"
        f" | {p.get('battery_temp_end_c')} °C, status {p.get('thermal_status_max')}"
        f" | {p.get('pss_mb_max')} MB | {pf(off and off['recorded_offline'])}"
        f" | {pf(off and off['synced_after_reconnect'])} |"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--apk", help="install this build first")
    parser.add_argument("--test", default="Squats", help="the test whose camera to open")
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--api", default="http://127.0.0.1:8010")
    parser.add_argument("--note", default="", help="e.g. 'person in view, battery 80%%'")
    args = parser.parse_args()

    adb = Adb(args.adb, args.serial)
    if "device" not in adb.run("get-state"):
        print("No phone found. Turn on USB debugging and allow this computer.")
        return 1
    if args.apk:
        print("Installing…")
        print(adb.run("install", "-r", args.apk, timeout=600).strip())
    adb.run("reverse", "tcp:8010", "tcp:8010")

    report: dict = {
        "measured_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "note": args.note,
        "device": device_info(adb),
        "battery_level_at_start": battery_level(adb),
    }
    print("Device:", json.dumps(report["device"]))
    report["cold_start_ms"] = cold_start_ms(adb)
    print("Cold start:", report["cold_start_ms"], "ms")

    if not open_camera(adb, args.test):
        print("Could not reach the camera screen (signed in? English? permission?)")
        return 1
    print(f"Measuring the pose pipeline for {args.seconds} s…")
    report["pose"] = pose_run(adb, args.seconds)
    print("Pose:", json.dumps(report["pose"]))
    report["lifecycle"] = lifecycle(adb, args.test)
    print("Lifecycle:", json.dumps(report["lifecycle"]))
    if args.offline:
        report["offline"] = offline_walk(adb, args.api)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / f"{report['device']['model']}.json"
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\nWrote {out}\n\nMatrix row:\n{matrix_row(report)}")
    return 0 if report["lifecycle"]["passed"] else 2


if __name__ == "__main__":
    sys.exit(main())

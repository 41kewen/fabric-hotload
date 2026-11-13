"""逐目标构建并运行已有隔离服务器探针；构建成功不能代替服务器行为验证。"""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


def run(command: list[str], project: Path, log: Path, environment: dict[str, str], timeout: int) -> int:
    """保留真实命令和输出；超时会失败而不是变成成功记录。"""
    log.with_suffix(".command.json").write_text(json.dumps(command, ensure_ascii=False, indent=2), encoding="utf-8")
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=project, env=environment, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=os.name != "nt",
                                   creationflags=(subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW) if os.name == "nt" else 0)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            output.write("\nMATRIX_COMMAND_TIMEOUT\n")
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True, timeout=30)
            else:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                if os.name == "nt":
                    process.kill()
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=15)
            return 124


def unit_counts(project: Path, target: str) -> dict[str, int]:
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for module in ("core", "agent", "fabric-host", "probe-mod", "module-api"):
        directory = project / module / "build" / target / "test-results" / "test"
        for path in directory.glob("TEST-*.xml"):
            suite = ET.parse(path).getroot()
            for key in counts:
                counts[key] += int(suite.attrib.get(key, "0"))
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--targets", nargs="+")
    parser.add_argument("--build-java-home", type=Path, required=True, help="Gradle/Loom 使用 JDK 25")
    for major in (17, 21, 25):
        parser.add_argument(f"--java{major}-home", type=Path)
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--accept-eula", action="store_true")
    parser.add_argument("--cycles", type=int, default=100)
    parser.add_argument("--proxy")
    args = parser.parse_args()
    project = args.project.resolve()
    matrix = json.loads((project / "compatibility/targets.json").read_text(encoding="utf-8"))
    targets = args.targets or list(matrix["targets"])
    if len(targets) != len(set(targets)) or any(target not in matrix["targets"] for target in targets):
        parser.error("Targets must be unique declared profiles")
    if not args.build_only and not args.accept_eula:
        parser.error("Server tests require explicit --accept-eula")
    if not 1 <= args.cycles <= 200:
        parser.error("--cycles must be 1..200")
    executable = "java.exe" if os.name == "nt" else "java"
    build_home = args.build_java_home.resolve()
    if not (build_home / "bin" / executable).is_file():
        parser.error("Build Java executable is missing")
    if not args.build_only:
        for target in targets:
            major = matrix["targets"][target]["java"]
            home = getattr(args, f"java{major}_home")
            if home is None or not (home / "bin" / executable).is_file():
                parser.error(f"Target {target} needs --java{major}-home")
    # 复用探针的代理校验，禁止凭据进入 Gradle 的进程参数与日志。
    sys.path.insert(0, str(project / "integration-tests"))
    from run_probes import proxy_arguments
    proxy = proxy_arguments(args.proxy)
    environment = dict(os.environ, JAVA_HOME=str(build_home),
                       PATH=str(build_home / "bin") + os.pathsep + os.environ.get("PATH", ""),
                       PYTHONIOENCODING="utf-8")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    logs = project / "verification" / "raw" / ("matrix-" + stamp)
    logs.mkdir(parents=True, exist_ok=False)
    report_path = project / "verification" / ("matrix-" + stamp + ".json")
    report: dict[str, object] = {"schema": 1, "success": False, "build_only": args.build_only,
                               "platform": sys.platform, "targets": [],
                               "unverified": ["online players", "third-party mods", "Linux server runtime"]}
    rows: list[dict[str, object]] = []
    report["targets"] = rows
    wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    for target in targets:
        print(f"BUILD {target}", flush=True)
        row: dict[str, object] = {"target": target, "baseline": matrix["targets"][target], "status": "failed"}
        rows.append(row)
        build_log = logs / ("build-" + target + ".log")
        command = [str(wrapper), "build", "distribution", f"-Ptarget={target}", *proxy,
                   "--console=plain", "--warning-mode", "all"]
        row["build_exit_code"] = run(command, project, build_log, environment, 1800)
        row["build_log"] = build_log.relative_to(project).as_posix()
        if row["build_exit_code"] == 0:
            row["unit_tests"] = unit_counts(project, target)
            row["status"] = "build-tested"
            check_log = logs / ("distribution-" + target + ".log")
            row["distribution_check_exit_code"] = run(
                [sys.executable, str(project / "scripts/check_distribution.py"), "--target", target],
                project, check_log, environment, 60)
            if row["distribution_check_exit_code"] != 0:
                row["status"] = "distribution-failed"
            if row["distribution_check_exit_code"] == 0 and not args.build_only:
                print(f"SERVER {target}", flush=True)
                test_log = logs / ("server-" + target + ".log")
                home = getattr(args, f"java{matrix['targets'][target]['java']}_home").resolve()
                command = [sys.executable, str(project / "integration-tests/run_probes.py"),
                           "--project", str(project), "--target", target, "--java-home", str(home),
                           "--cycles", str(args.cycles), "--accept-eula"]
                if args.proxy:
                    command.extend(["--proxy", args.proxy])
                row["integration_exit_code"] = run(command, project, test_log, environment, 1800)
                match = re.search(r"^REPORT (.+)$", test_log.read_text(encoding="utf-8", errors="replace"), re.MULTILINE)
                if match:
                    integration_path = Path(match.group(1).strip()).resolve()
                    if not integration_path.is_relative_to(project / "verification"):
                        raise ValueError("Unexpected integration report path")
                    integration = json.loads(integration_path.read_text(encoding="utf-8"))
                    row["integration_report"] = integration_path.relative_to(project).as_posix()
                    row["integration_checks"] = len(integration.get("checks", []))
                    if row["integration_exit_code"] == 0 and integration.get("success") is True:
                        row["status"] = "server-tested"
                    else:
                        row["status"] = "server-failed"
                        row["failure"] = integration.get("failure", "Integration failed")
                else:
                    row["status"] = "server-failed"
                    row["failure"] = "Integration process did not produce a report"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"RESULT {target}: {row['status']}", flush=True)
    expected = "build-tested" if args.build_only else "server-tested"
    report["success"] = all(row["status"] == expected for row in rows)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"MATRIX_REPORT {report_path}", flush=True)
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

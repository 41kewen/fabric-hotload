"""汇总真实矩阵运行与修复后重试；保留来源，不覆写失败记录。"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


def summarize(project: Path, sources: list[Path]) -> dict[str, object]:
    """只采用基线、退出码、单测和实际分发哈希均一致的服务器结果。"""
    project = project.resolve()
    sys.path.insert(0, str(project / "integration-tests"))
    from run_probes import digest, load_target

    matrix = json.loads((project / "compatibility/targets.json").read_text(encoding="utf-8"))
    selected: dict[str, dict[str, object]] = {}
    for source in sources:
        source = source.resolve()
        if not source.is_relative_to(project / "verification"):
            raise ValueError("Source report must be inside verification")
        report = json.loads(source.read_text(encoding="utf-8"))
        if report.get("build_only") is not False:
            continue
        for row in report["targets"]:
            target = row["target"]
            if target not in matrix["targets"] or row.get("baseline") != matrix["targets"][target]:
                continue
            if (row.get("status") != "server-tested" or row.get("build_exit_code") != 0
                    or row.get("integration_exit_code") != 0
                    or row.get("distribution_check_exit_code", 0) != 0):
                continue
            counts = row.get("unit_tests", {})
            if (counts.get("tests", 0) < 1 or counts.get("failures", 1) != 0
                    or counts.get("errors", 1) != 0):
                continue
            integration_path = (project / row["integration_report"]).resolve()
            if not integration_path.is_relative_to(project / "verification"):
                raise ValueError("Integration report escapes verification")
            tested = json.loads(integration_path.read_text(encoding="utf-8"))
            profile = load_target(project, target)
            if (tested.get("success") is not True or tested.get("target") != target
                    or tested.get("minecraft") != profile.minecraft or tested.get("loader") != profile.loader
                    or tested.get("fabric_api") != profile.api or tested.get("project_version") != profile.project_version
                    or not tested.get("checks")):
                continue
            marker = profile.distribution.relative_to(project).as_posix() + "/"
            expected = {item["path"][len(marker):]: item["sha256"] for item in tested["artifacts"]
                        if item["path"].startswith(marker)}
            actual = {path.relative_to(profile.distribution).as_posix(): digest(path)
                      for path in profile.distribution.rglob("*.jar")}
            if len(expected) != 6 or actual != expected:
                # 不允许给已改过的 JAR 复用旧版通过记录。
                continue
            selected[target] = dict(row, integration_checks=len(tested["checks"]),
                                    source_matrix=source.relative_to(project).as_posix(),
                                    platform=report.get("platform"))
    missing = [target for target in matrix["targets"] if target not in selected]
    return {
        "schema": 1,
        "kind": "verified-matrix-summary",
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "success": not missing,
        "build_only": False,
        "sources": [path.resolve().relative_to(project).as_posix() for path in sources],
        "targets": [selected[target] for target in matrix["targets"] if target in selected],
        "missing_targets": missing,
        "unverified": ["online players", "third-party mods", "Linux server runtime", "full JVM class unloading"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--reports", nargs="+", type=Path, required=True, help="按时间先后列出原始矩阵报告")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    project = args.project.resolve()
    if args.output.exists():
        parser.error("Refusing to overwrite an existing summary")
    report = summarize(project, args.reports)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"SUMMARY {args.output.resolve()}\nVERIFIED {len(report['targets'])}\nMISSING {report['missing_targets']}")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

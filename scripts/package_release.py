"""打包源码、已验证的 JAR 和验证记录，不分发服务器、世界或工具缓存。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def included(relative: Path) -> bool:
    """只打包项目资料，排除 Git、运行实例、下载、缓存与构建中间文件。"""
    parts = relative.parts
    if relative.name.startswith(".env") or relative.suffix in {".pem", ".key", ".keystore", ".hprof", ".jfr"}:
        return False
    if any(part in {".git", ".gradle", "tools", "__pycache__", "history", "downloads", "artifact-snapshot", "raw"}
           for part in parts) or any(part.startswith("server-") for part in parts[:-1]):
        return False
    if parts[:2] == ("build", "distribution"):
        return relative.suffix == ".jar"
    if "build" in parts or "run" in parts:
        return False
    if parts[0] == "verification":
        return relative.suffix in {".md", ".json", ".log", ".jsonl"}
    roots = {"module-api", "core", "agent", "fabric-host", "probe-mod", "demo-v1", "demo-v2",
             "gradle", "scripts", "integration-tests", ".github", "compatibility"}
    root_files = {".gitignore", ".gitattributes", "AGENTS.md", "DEVELOPMENT_PLAN.md", "LICENSE", "README.md",
                  "THIRD_PARTY_NOTICES.md", "settings.gradle", "build.gradle", "gradle.properties",
                  "gradlew", "gradlew.bat"}
    return parts[0] in roots or (len(parts) == 1 and parts[0] in root_files)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    project = args.project.resolve()
    output = args.output.resolve()
    if not (project / "settings.gradle").is_file() or not (project / "README.md").is_file():
        parser.error("Project markers are absent")
    if output.exists() or output.with_suffix(output.suffix + ".sha256").exists():
        parser.error("Refusing to overwrite an existing release")
    report = json.loads(args.report.read_text(encoding="utf-8"))
    if report.get("success") is not True:
        parser.error("A successful integration report is required")
    reports = [report]
    if "targets" in report:
        reports = []
        rows = report["targets"]
        if not rows or len({row.get("target") for row in rows}) != len(rows):
            parser.error("Matrix must include nonempty, unique tested targets")
        for row in rows:
            if row.get("status") != "server-tested":
                parser.error("Matrix includes a target without successful server testing")
            path = (project / row["integration_report"]).resolve()
            if not path.is_relative_to(project / "verification"):
                parser.error("Matrix report path escapes verification directory")
            reports.append(json.loads(path.read_text(encoding="utf-8")))
    validated: set[Path] = set()
    checksum_lines: list[str] = []
    for tested in reports:
        if tested.get("success") is not True:
            parser.error("All integration reports must be successful")
        target = tested.get("target")
        distribution = project / "build" / "distribution"
        if target is not None:
            matrix = json.loads((project / "compatibility/targets.json").read_text(encoding="utf-8"))
            if target not in matrix["targets"]:
                parser.error("Unknown report target")
            distribution = project / "build" / "targets" / target / "distribution"
        marker = distribution.relative_to(project).as_posix() + "/"
        expected: dict[str, str] = {}
        for artifact in tested["artifacts"]:
            source = artifact["path"].replace("\\", "/")
            if source.startswith(marker):
                expected[source[len(marker):]] = artifact["sha256"]
            elif "/" + marker in source:
                expected[source.split("/" + marker, 1)[1]] = artifact["sha256"]
        actual = {path.relative_to(distribution).as_posix(): sha256(path) for path in distribution.rglob("*.jar")}
        if len(expected) != 6 or actual != expected:
            parser.error("Distribution differs from the actually tested JAR versions")
        validated.update(distribution.rglob("*.jar"))
        if target is not None:
            manifest_path = distribution / "distribution.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            if manifest["target"] != target or manifest["version"] != tested["project_version"]:
                parser.error("Distribution version differs from integration report")
            validated.add(manifest_path)
        checksum_lines.extend(f"{checksum}  {marker}{name}\n" for name, checksum in sorted(actual.items()))
    if (project / ".git").exists():
        inventory = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                                   cwd=project, capture_output=True, check=True, timeout=30)
        files = {project / name.decode("utf-8") for name in inventory.stdout.split(b"\0") if name}
    else:
        # 源码 ZIP 没有 Git 元数据；先剪枝，不能先遍历世界与下载再过滤。
        files = set()
        for directory, children, names in os.walk(project):
            relative = Path(directory).relative_to(project)
            children[:] = [child for child in children if included(relative / child / "_inventory")]
            files.update(Path(directory) / name for name in names)
    files = sorted({path for path in files if path.is_file() and included(path.relative_to(project))} | validated)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=output.name + "-", suffix=".tmp", dir=output.parent, delete=False) as handle:
        temporary = Path(handle.name)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in files:
                relative = path.relative_to(project).as_posix()
                info = zipfile.ZipInfo("fabric-hotload/" + relative, date_time=(2026, 11, 13, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o755 if relative == "gradlew" else 0o644) << 16
                archive.writestr(info, path.read_bytes())
            archive.writestr("fabric-hotload/SHA256SUMS-jars.txt", "".join(checksum_lines))
        with zipfile.ZipFile(temporary) as archive:
            bad = archive.testzip()
            if bad is not None:
                raise ValueError(f"ZIP CRC validation failed: {bad}")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    output.with_suffix(output.suffix + ".sha256").write_text(
        sha256(output) + "  " + output.name + "\n", encoding="utf-8")
    print(f"RELEASE {output}\nSHA256 {sha256(output)}\nFILES {len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

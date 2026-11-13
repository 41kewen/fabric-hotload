"""打包源码、已验证的 JAR 和验证记录，不分发服务器、世界或工具缓存。"""

from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def included(relative: Path) -> bool:
    """只打包项目资料，排除 Git、运行实例、下载、缓存与构建中间文件。"""
    parts = relative.parts
    if any(part in {".git", ".gradle", "tools", "__pycache__", "history", "downloads", "artifact-snapshot"}
           for part in parts) or any(part.startswith("server-") for part in parts[:-1]):
        return False
    if parts[:2] == ("build", "distribution"):
        return relative.suffix == ".jar"
    if "build" in parts or "run" in parts:
        return False
    if parts[0] == "verification":
        return relative.suffix in {".md", ".json", ".log", ".jsonl"}
    roots = {"module-api", "core", "agent", "fabric-host", "probe-mod", "demo-v1", "demo-v2",
             "gradle", "scripts", "integration-tests", ".github"}
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
    expected: dict[str, str] = {}
    for artifact in report["artifacts"]:
        source = artifact["path"].replace("\\", "/")
        marker = "/build/distribution/"
        if source.startswith("build/distribution/"):
            suffix = source[len("build/distribution/"):]
            expected[suffix] = artifact["sha256"]
        elif marker in source:
            suffix = source.split(marker, 1)[1]
            expected[suffix] = artifact["sha256"]
    actual = {path.relative_to(project / "build" / "distribution").as_posix(): sha256(path)
              for path in (project / "build" / "distribution").rglob("*.jar")}
    if not expected or actual != expected:
        parser.error("Distribution differs from the actually tested JAR versions")
    files = sorted(path for path in project.rglob("*") if path.is_file()
                   and included(path.relative_to(project)))
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

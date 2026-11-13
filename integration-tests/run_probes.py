"""在独立本地测试服对比正常安装、热加载及保存后重启；不连接生产服务器。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
import urllib.request
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import TextIO
from urllib.parse import urlparse


@dataclass(frozen=True)
class TestTarget:
    """固定构建基线与实际要运行的 Loader；每个角色对应一个已校验的 JAR。"""

    name: str
    minecraft: str
    loader: str
    api: str
    java: int
    installer: str
    project_version: str
    distribution: Path
    host: Path
    agent: Path
    ordinary: Path
    failure: Path
    demo_v1: Path
    demo_v2: Path
    api_runtime_url: str | None = None
    api_runtime_sha1: str | None = None

    @property
    def ordinary_supported(self) -> bool:
        return self.loader == "0.20.1"


def load_target(project: Path, name: str, loader: str | None = None) -> TestTarget:
    """拒绝跨版本产物、未完成构建和被替换的 JAR，再创建任何服务器实例。"""
    matrix = json.loads((project / "compatibility/targets.json").read_text(encoding="utf-8"))
    if name not in matrix["targets"]:
        raise ValueError(f"Unknown target: {name}")
    baseline = matrix["targets"][name]
    actual_loader = loader or baseline["loader"]
    if not re.fullmatch(r"0\.\d+\.\d+", actual_loader):
        raise ValueError("Loader version must be a release version such as 0.20.1")
    # Windows CI 的临时根目录可能是 8.3 别名；清单路径和目录枚举须使用同一规范路径。
    directory = (project / "build" / "targets" / name / "distribution").resolve()
    manifest = json.loads((directory / "distribution.json").read_text(encoding="utf-8"))
    if manifest.get("schema") != 1 or manifest.get("target") != name or manifest.get("baseline") != baseline:
        raise ValueError("Distribution manifest does not match the requested target")
    artifacts: list[Path] = []
    for item in manifest["artifacts"]:
        relative = PurePosixPath(item["path"])
        if relative.is_absolute() or ".." in relative.parts or "\\" in item["path"] or relative.suffix != ".jar":
            raise ValueError("Unsafe distribution path")
        path = directory.joinpath(*relative.parts).resolve()
        if not path.is_relative_to(directory.resolve()) or digest(path) != item["sha256"]:
            raise ValueError(f"Distribution checksum mismatch: {relative}")
        artifacts.append(path)
    if len(artifacts) != 6 or len(set(artifacts)) != 6 or set(directory.rglob("*.jar")) != set(artifacts):
        raise ValueError("Distribution must contain exactly the six recorded JARs")

    def role(prefix: str) -> Path:
        matches = [path for path in artifacts if path.name.startswith(prefix)]
        if len(matches) != 1:
            raise ValueError(f"Missing or duplicate distribution role: {prefix}")
        return matches[0]

    return TestTarget(name, baseline["minecraft"], actual_loader, baseline["api"], baseline["java"],
                      matrix["installer"], manifest["version"], directory,
                      role("fabric-hotload-host-"), role("fabric-hotload-agent-"),
                      role("hotload-ordinary-probe-"), role("hotload-failure-probe-"),
                      role("hotload-demo-v1-"), role("hotload-demo-v2-"),
                      baseline.get("api_runtime", {}).get("url"), baseline.get("api_runtime", {}).get("sha1"))


def digest(path: Path, algorithm: str = "sha256") -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def download(url: str, destination: Path, expected_sha1: str | None = None) -> Path:
    """下载到额外文件，完成与校验后替换；已有正确下载直接复用。"""
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if expected_sha1 is None or digest(destination, "sha1") == expected_sha1:
            return destination
        raise ValueError(f"Existing download failed checksum: {destination}")
    temporary = destination.with_suffix(destination.suffix + ".part")
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "FabricHotload-P0"})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as stream:
            if response.status != 200:
                raise RuntimeError(f"HTTP {response.status}: {url}")
            size = 0
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > 256 * 1024 * 1024:
                    raise ValueError("Download exceeds 256 MiB limit")
                stream.write(chunk)
        if expected_sha1 is not None and digest(temporary, "sha1") != expected_sha1:
            raise ValueError(f"Download failed SHA1: {url}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def proxy_arguments(proxy: str | None) -> list[str]:
    """仅支持不带凭据的显式 HTTP 代理，避免令牌进入进程参数和测试记录。"""
    if not proxy:
        return []
    parsed = urlparse(proxy)
    if parsed.scheme != "http" or not parsed.hostname or not parsed.port or parsed.username or parsed.password:
        raise ValueError("Proxy must be http://host:port without credentials")
    return [f"-Dhttps.proxyHost={parsed.hostname}", f"-Dhttps.proxyPort={parsed.port}",
            f"-Dhttp.proxyHost={parsed.hostname}", f"-Dhttp.proxyPort={parsed.port}"]


class Server:
    """只管理本次创建的子进程，原始 stdout 与输入/响应记录分开保存。"""

    def __init__(self, directory: Path, command: list[str], log_path: Path, commands_path: Path) -> None:
        self.directory = directory
        self.log = log_path.open("w", encoding="utf-8")
        self.commands: TextIO = commands_path.open("w", encoding="utf-8")
        self.lines: queue.Queue[str] = queue.Queue()
        self.process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, encoding="utf-8", errors="replace", bufsize=1)
        self.commands.write(json.dumps({"launch": command, "cwd": str(directory),
                                        "pid": self.process.pid}) + "\n")
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        if self.process.stdout is None:
            return
        for line in self.process.stdout:
            self.log.write(line)
            self.log.flush()
            self.lines.put(line.rstrip("\n"))

    def wait_for(self, pattern: str, timeout: float = 30) -> str:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                line = self.lines.get(timeout=0.25)
            except queue.Empty:
                if self.process.poll() is not None:
                    raise RuntimeError(f"Server exited with {self.process.returncode}; see {self.log.name}")
                continue
            if re.search(pattern, line):
                return line
        raise TimeoutError(f"Missing output {pattern!r}; see {self.log.name}")

    def command(self, command: str, pattern: str, timeout: float = 30) -> str:
        if self.process.stdin is None or self.process.poll() is not None:
            raise RuntimeError("Server has no writable stdin")
        started = time.monotonic()
        self.commands.write(json.dumps({"time": datetime.now(timezone.utc).isoformat(),
                                        "input": command, "expected_pattern": pattern}) + "\n")
        self.commands.flush()
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()
        response = self.wait_for(pattern, timeout)
        self.commands.write(json.dumps({"response": response,
                                        "elapsed_ms": round((time.monotonic() - started) * 1000, 2)}) + "\n")
        self.commands.flush()
        return response

    def __enter__(self) -> Server:
        try:
            self.wait_for("HOTLOAD_READY", 240)
        except (TimeoutError, RuntimeError):
            self.shutdown()
            raise
        return self

    def shutdown(self) -> int:
        if self.process.poll() is None:
            try:
                if self.process.stdin is not None:
                    self.process.stdin.write("stop\n")
                    self.process.stdin.flush()
                self.process.wait(timeout=90)
            except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                self.commands.write(json.dumps({"forced_termination": True,
                                                "reason": "Isolated test server did not stop within deadline"}) + "\n")
                self.process.terminate()
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=10)
        self.reader.join(timeout=10)
        self.commands.write(json.dumps({"process_exit_code": self.process.returncode}) + "\n")
        self.commands.close()
        self.log.close()
        if self.process.stdin is not None:
            self.process.stdin.close()
        if self.process.stdout is not None:
            self.process.stdout.close()
        return self.process.returncode

    def __exit__(self, exc_type: object, exc_value: object, traceback: object) -> None:
        code = self.shutdown()
        if exc_type is None and code != 0:
            raise RuntimeError(f"Test server stopped with exit code {code}")


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def prepare(directory: Path, java: Path, jvm_options: list[str], installer: Path,
            minecraft: Path, target: TestTarget, api: Path, baseline: bool, logs: Path) -> None:
    if directory.exists():
        raise FileExistsError(f"Refusing to overwrite existing server: {directory}")
    directory.mkdir(parents=True)
    mods = directory / "mods"
    candidates = directory / "hotmods" / "candidates"
    mods.mkdir()
    candidates.mkdir(parents=True)
    shutil.copy2(minecraft, directory / "server.jar")
    shutil.copy2(api, mods / api.name)
    shutil.copy2(target.host, mods)
    ordinary = target.ordinary
    shutil.copy2(ordinary, mods / ordinary.name if baseline else candidates / "ordinary-probe.jar")
    shutil.copy2(target.demo_v1, candidates / "demo-v1.jar")
    shutil.copy2(target.demo_v2, candidates / "demo-v2.jar")
    shutil.copy2(target.failure, candidates / "failure-probe.jar")
    (directory / "eula.txt").write_text("# Explicitly accepted for this isolated test instance\neula=true\n", encoding="utf-8")
    (directory / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenforce-secure-profile=false\n"
        "enable-rcon=false\nview-distance=2\nsimulation-distance=2\nmax-players=2\n"
        "level-seed=1\nmax-tick-time=180000\n", encoding="utf-8")
    command = [str(java), *jvm_options, "-jar", str(installer), "server", "-mcversion", target.minecraft,
               "-loader", target.loader, "-dir", str(directory)]
    result = subprocess.run(command, cwd=directory, capture_output=True, timeout=240)
    (logs / f"{directory.name}-install.log").write_bytes(result.stdout + result.stderr)
    (logs / f"{directory.name}-install.json").write_text(
        json.dumps({"command": command, "exit_code": result.returncode}, indent=2), encoding="utf-8")
    require(result.returncode == 0 and (directory / "fabric-server-launch.jar").is_file(), "Fabric installer failed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--target", default="26.2")
    parser.add_argument("--loader-version", help="覆盖运行 Loader，用于验证未适配版本的安全降级")
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--minecraft-server", type=Path)
    parser.add_argument("--proxy")
    parser.add_argument("--cycles", type=int, default=100)
    parser.add_argument("--accept-eula", action="store_true")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Explicit --accept-eula is required; review https://www.minecraft.net/eula")
    if not 1 <= args.cycles <= 200:
        parser.error("--cycles must be 1..200")
    project = args.project.resolve()
    java = args.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.is_file():
        parser.error("Java executable is absent")
    try:
        target = load_target(project, args.target, args.loader_version)
    except (OSError, ValueError, KeyError, TypeError) as exception:
        parser.error(str(exception))
    jvm_options = proxy_arguments(args.proxy) + ["-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US"]
    if args.proxy:
        urllib.request.install_opener(urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": args.proxy, "https": args.proxy})))
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-mc" + target.name
    logs = project / "verification" / "raw" / stamp
    logs.mkdir(parents=True, exist_ok=False)
    report_path = project / "verification" / f"integration-{stamp}.json"
    report: dict[str, object] = {"target": target.name, "project_version": target.project_version,
                               "minecraft": target.minecraft, "loader": target.loader, "fabric_api": target.api,
                               "installer": target.installer, "success": False, "checks": [],
                               "unverified": ["online player continuity", "Linux", "third-party mods",
                                              "arbitrary Mixin/registry additions", "full JVM class unloading"]}
    checks: list[dict[str, str]] = []
    report["checks"] = checks

    def checked(name: str, evidence: str) -> None:
        checks.append({"name": name, "evidence": evidence})
        print(f"PASS {name}: {evidence}", flush=True)

    try:
        downloads = project / "integration-tests" / "downloads"
        installer_url = f"https://maven.fabricmc.net/net/fabricmc/fabric-installer/{target.installer}/fabric-installer-{target.installer}.jar"
        with urllib.request.urlopen(installer_url + ".sha1", timeout=30) as response:
            installer_sha1 = response.read().decode("ascii").strip().split()[0]
        installer = download(installer_url, downloads / f"fabric-installer-{target.installer}.jar", installer_sha1)
        if target.api_runtime_url is not None:
            require(target.api_runtime_sha1 is not None, "Runtime API checksum is missing")
            api_url = target.api_runtime_url
            api = download(api_url, downloads / f"fabric-api-{target.api}-runtime.jar", target.api_runtime_sha1)
        else:
            api_url = f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{target.api}/fabric-api-{target.api}.jar"
            with urllib.request.urlopen(api_url + ".sha1", timeout=30) as response:
                api_sha1 = response.read().decode("ascii").strip().split()[0]
            api = download(api_url, downloads / f"fabric-api-{target.api}.jar", api_sha1)
        with zipfile.ZipFile(api) as api_jar:
            require(api_jar.testzip() is None, "Runtime Fabric API CRC failed")
            descriptor = json.loads(api_jar.read("fabric.mod.json"))
            nested = [item["file"] for item in descriptor.get("jars", [])]
            require(descriptor["id"] == "fabric-api" and descriptor["version"] == target.api,
                    "Runtime Fabric API version mismatch")
            require(any("fabric-lifecycle-events" in name and name in api_jar.namelist() for name in nested),
                    "Fabric API is a thin Maven artifact, not a runnable bundle")
        report["fabric_api_download_url"] = api_url
        with urllib.request.urlopen("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", timeout=30) as response:
            versions = json.load(response)["versions"]
        version_url = next((item["url"] for item in versions if item["id"] == target.minecraft), None)
        require(version_url is not None, "Minecraft version is absent from the official manifest")
        with urllib.request.urlopen(version_url, timeout=30) as response:
            metadata = json.load(response)
        require(metadata["id"] == target.minecraft and metadata["javaVersion"]["majorVersion"] == target.java,
                "Unexpected Minecraft metadata or Java baseline")
        if args.minecraft_server:
            minecraft = args.minecraft_server.resolve()
            require(digest(minecraft, "sha1") == metadata["downloads"]["server"]["sha1"], "Minecraft cache SHA1 mismatch")
        else:
            minecraft = download(metadata["downloads"]["server"]["url"], downloads / f"minecraft-{target.minecraft}.jar",
                                 metadata["downloads"]["server"]["sha1"])
        report["artifacts"] = [{"path": str(path), "sha256": digest(path)}
                               for path in [installer, api, minecraft, *sorted(target.distribution.rglob("*.jar"))]]
        for artifact in report["artifacts"]:
            path = Path(artifact["path"])
            artifact["path"] = path.relative_to(project).as_posix() if path.is_relative_to(project) else "external/" + path.name
        java_version = subprocess.run([str(java), "-version"], capture_output=True, timeout=15)
        report["java_version"] = (java_version.stdout + java_version.stderr).decode("utf-8", "replace")
        report["java_version_exit_code"] = java_version.returncode
        require(java_version.returncode == 0, "Java version command failed")
        report["platform"] = os.name
        baseline = project / "integration-tests" / f"server-baseline-{stamp}"
        hot = project / "integration-tests" / f"server-hot-{stamp}"
        failure_server = project / "integration-tests" / f"server-failure-{stamp}"
        for directory, normal in ((baseline, True), (hot, False), (failure_server, False)):
            prepare(directory, java, jvm_options, installer, minecraft, target, api, normal, logs)
        agent = target.agent
        # 多个隔离实例并行时，避免每个 1 GiB JVM 都按整机核心数创建世界生成线程。
        command = [str(java), *jvm_options, "-XX:ActiveProcessorCount=4", f"-javaagent:{agent}", "-Dhotload.experimentalMods=true",
                   "-Xms512M", "-Xmx1G", "-jar", "fabric-server-launch.jar", "nogui"]
        report["jvm_active_processors"] = 4
        report["jvm_max_heap_mb"] = 1024

        def server(directory: Path, phase: str) -> Server:
            return Server(directory, command, logs / f"{phase}-stdout.log", logs / f"{phase}-commands.jsonl")

        with server(baseline, "baseline") as instance:
            time.sleep(0.5)
            result = instance.command("hotmods ordinary", r"ORDINARY loaded=true")
            require("main=1 server=true" in result and "resource=ordinary-resource-ok container=true" in result,
                    "Baseline ordinary mod behavior failed")
            checked("ordinary normal startup", result)
            instance.command("save-all flush", "Saved the game", 90)
        checked("baseline graceful stop", "process exited 0")

        with server(hot, "hot") as instance:
            before = instance.command("hotmods status", "STATUS session=")
            pid = instance.process.pid
            require(f"pid={pid}" in before, "Server PID mismatch")
            instance.command("hotmods ordinary", "ORDINARY loaded=false")
            if target.ordinary_supported:
                require("ordinaryAdapter=loader-0.20.1-experimental" in before, "Wrong ordinary adapter")
                instance.command("hotmods loadmod ordinary-probe.jar", "MOD_STARTED_EXPERIMENTAL hotload_probe")
                time.sleep(0.5)
                result = instance.command("hotmods ordinary", "ORDINARY loaded=true")
                require("main=1 server=true" in result and "resource=ordinary-resource-ok container=true" in result,
                        "Late-loaded ordinary mod metadata/resource/initializers failed")
                tick_match = re.search(r"ticks=(\d+)", result)
                require(tick_match is not None, "Ordinary tick evidence missing")
                ticks = int(tick_match.group(1)) if tick_match else 0
                time.sleep(0.25)
                next_result = instance.command("hotmods ordinary", "ORDINARY loaded=true")
                next_ticks = re.search(r"ticks=(\d+)", next_result)
                require(next_ticks is not None and int(next_ticks.group(1)) > ticks, "Ordinary tick listener did not run")
                checked("ordinary late load matches baseline functionality", next_result)
                rejected = instance.command("hotmods loadmod ordinary-probe.jar", "HOTLOAD_REJECTED Duplicate mod")
                checked("duplicate ordinary mod rejected", rejected)
            else:
                require("ordinaryAdapter=unsupported-loader-" + target.loader in before, "Unsafe Loader fallback")
                rejected = instance.command("hotmods loadmod ordinary-probe.jar", "HOTLOAD_REJECTED UNSUPPORTED_LOADER")
                require("DIRTY_RESTART_REQUIRED" not in rejected, "Preflight rejection changed dirty state")
                checked("unsupported Loader rejects ordinary activation", rejected)
                unchanged = instance.command("hotmods ordinary", "ORDINARY loaded=false")
                require("container=false" in unchanged, "Unsupported Loader installed an ordinary container")
                clean = instance.command("hotmods status", "STATUS session=")
                require("dirty=false" in clean, "Unsupported Loader rejection corrupted managed runtime")
                checked("unsupported Loader preserves managed runtime", clean)
            instance.command("hotmods load demo-v1.jar", "MODULE_STARTED demo_counter version=1.0.0")
            instance.command("hotmods exec demo_counter hello", "demo-v1 ticks=")
            instance.command("hotmods reload demo-v2.jar", "MODULE_STARTED demo_counter version=2.0.0")
            changed = instance.command("hotmods exec demo_counter hello", "demo-v2 ticks=")
            checked("managed version switch", changed)
            instance.command("hotmods disable demo_counter", "MODULE_DISABLED demo_counter")
            rejected = instance.command("hotmods exec demo_counter hello", "HOTLOAD_REJECTED No active module")
            checked("disabled module cannot dispatch", rejected)
            for _ in range(args.cycles):
                instance.command("hotmods load demo-v1.jar", "MODULE_STARTED demo_counter version=1.0.0")
                instance.command("hotmods reload demo-v2.jar", "MODULE_STARTED demo_counter version=2.0.0")
                instance.command("hotmods disable demo_counter", "MODULE_DISABLED demo_counter")
            state = instance.command("hotmods status", "STATUS session=")
            require("resources=0 dirty=false modules=[]" in state, "Managed resources did not return to baseline")
            require(f"pid={pid}" in state and re.search(r"session=([^ ]+)", before).group(1)
                    == re.search(r"session=([^ ]+)", state).group(1), "Server session changed during operations")
            checked(f"{args.cycles} server lifecycle cycles", state)
            patched = instance.command("hotmods patch patch", "BYTECODE_PATCH")
            require("before=original after=patched mixinDelta=2" in patched, "Patch failed or existing Mixin was lost")
            checked("method-body patch preserves startup Mixin", patched)
            negative = instance.command("hotmods patch negative", "STRUCTURE_REJECTED_BY_GUARD_AND_JVM")
            require("before=patched after=patched mixinDelta=2" in negative, "Negative patch changed behavior")
            checked("schema guard and JVM reject added field", negative)
            restored = instance.command("hotmods patch restore", "BYTECODE_RESTORE")
            require("before=patched after=original mixinDelta=2" in restored, "Bytecode restoration failed")
            checked("bytecode restoration", restored)
            instance.command("save-all flush", "Saved the game", 90)
        checked("hot server graceful stop", "process exited 0")
        require((hot / "world" / "level.dat").is_file(), "World save missing")
        with server(hot, "restart") as instance:
            result = instance.command("hotmods status", "STATUS session=")
            require("resources=0 dirty=false modules=[]" in result, "Restart state is inconsistent")
            instance.command("save-all flush", "Saved the game", 90)
            checked("saved world reopens normally", result)
        if target.ordinary_supported:
            with server(failure_server, "failure") as instance:
                failed = instance.command("hotmods loadmod failure-probe.jar", "HOTLOAD_REJECTED DIRTY_RESTART_REQUIRED")
                state = instance.command("hotmods status", "STATUS session=")
                require("dirty=true" in state, "Ordinary initializer failure did not retain dirty state")
                blocked = instance.command("hotmods load demo-v1.jar", "HOTLOAD_REJECTED DIRTY_RESTART_REQUIRED")
                checked("ordinary initializer failure blocks further activation", failed + " | " + blocked)
                journal = (failure_server / "hotmods" / "operations.jsonl").read_text(encoding="utf-8")
                require("COMMITTING_IRREVERSIBLE" in journal and "DIRTY_RESTART_REQUIRED" in journal,
                        "Ordinary failure audit evidence missing")
        for artifact in report["artifacts"]:
            if artifact["path"].startswith("build/targets/"):
                require(digest(project / artifact["path"]) == artifact["sha256"], "Tested source JAR changed during the run")
        report["success"] = True
        return 0
    except (OSError, ValueError, RuntimeError, TimeoutError, subprocess.SubprocessError) as exception:
        report["failure"] = str(exception).replace(str(project), "[project]").replace(str(args.java_home), "[java-home]")
        print(f"FAIL {exception}", flush=True)
        return 1
    finally:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"REPORT {report_path}", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())

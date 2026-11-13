"""检查真实分发包的目标、字节码基线、重映射和 thin Agent；不代替服务器测试。"""

from __future__ import annotations

import argparse
import io
import json
import struct
import sys
import zipfile
from pathlib import Path


def check_archive(data: bytes, maximum_java: int, name: str, depth: int = 0) -> int:
    """核对 CRC 与所有类的 classfile 版本，并检查宿主嵌套的公共 JAR。"""
    if depth > 2:
        raise ValueError("Unexpected nested distribution depth")
    classes = 0
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if archive.testzip() is not None:
            raise ValueError(f"JAR CRC error: {name}")
        for item in archive.infolist():
            if item.filename.endswith(".class"):
                magic, _, major = struct.unpack(">IHH", archive.read(item)[:8])
                if magic != 0xCAFEBABE or major > maximum_java + 44:
                    raise ValueError(f"Unsupported bytecode in {name}: {item.filename}, major={major}")
                classes += 1
            elif item.filename.endswith(".jar"):
                classes += check_archive(archive.read(item), 17, item.filename, depth + 1)
    return classes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--target", default="26.2")
    args = parser.parse_args()
    project = args.project.resolve()
    sys.path.insert(0, str(project / "integration-tests"))
    from run_probes import load_target
    target = load_target(project, args.target)
    baseline = json.loads((project / "compatibility/targets.json").read_text(encoding="utf-8"))["targets"][args.target]
    artifacts = []
    for path in sorted(target.distribution.rglob("*.jar")):
        major = target.java if path in {target.host, target.ordinary, target.failure} else 17
        count = check_archive(path.read_bytes(), major, path.name)
        artifacts.append({"name": path.name, "classes": count, "maximum_java": major})
    for path in (target.host, target.ordinary, target.failure):
        with zipfile.ZipFile(path) as archive:
            descriptor = json.loads(archive.read("fabric.mod.json"))
            if descriptor["depends"]["minecraft"] != "=" + target.minecraft or descriptor["depends"]["java"] != ">=" + str(target.java):
                raise ValueError(f"Descriptor targets another runtime: {path.name}")
    with zipfile.ZipFile(target.host) as archive:
        bytecode = archive.read("dev/fabric/hotload/host/MinecraftPlatform.class")
        # MinecraftServer 在 intermediary 中保留名字，不能把它误判成未重映射。
        if baseline["remap"] and (b"net/minecraft/class_2168" not in bytecode or b"net/minecraft/commands/CommandSourceStack" in bytecode):
            raise ValueError("Legacy platform JAR was not remapped to intermediary")
        mixins = json.loads(archive.read("fabric-hotload.mixins.json"))
        if mixins["compatibilityLevel"] != "JAVA_" + str(target.java):
            raise ValueError("Mixin compatibility level does not match the runtime")
    with zipfile.ZipFile(target.agent) as archive:
        if any(name.startswith(("net/minecraft/", "org/objectweb/asm/")) for name in archive.namelist()):
            raise ValueError("Agent unexpectedly embeds Minecraft or ASM")
    print(json.dumps({"target": target.name, "success": True, "legacy_remap": baseline["remap"], "artifacts": artifacts}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

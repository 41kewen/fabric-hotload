"""不启动服务器的分发边界检查；不替代运行兼容性验证。"""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from run_probes import load_target


class ProfileTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        (self.project / "compatibility").mkdir()
        baseline = {"minecraft": "1.20.1", "java": 17, "loader": "0.20.1", "api": "test", "platform": "supplier-ms", "remap": True}
        matrix = {"schema": 1, "installer": "1.1.2", "targets": {"1.20.1": baseline}}
        (self.project / "compatibility/targets.json").write_text(json.dumps(matrix), encoding="utf-8")
        self.directory = self.project / "build/targets/1.20.1/distribution"
        self.directory.mkdir(parents=True)
        names = ["fabric-hotload-host-test.jar", "fabric-hotload-agent-test.jar", "hotload-ordinary-probe-test.jar",
                 "hotload-failure-probe-test.jar", "hotload-demo-v1-test.jar", "hotload-demo-v2-test.jar"]
        artifacts = []
        for name in names:
            data = name.encode("utf-8")
            (self.directory / name).write_bytes(data)
            artifacts.append({"path": name, "sha256": hashlib.sha256(data).hexdigest()})
        self.manifest = {"schema": 1, "target": "1.20.1", "version": "test", "baseline": baseline, "artifacts": artifacts}
        self.write_manifest()

    def write_manifest(self) -> None:
        (self.directory / "distribution.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_selects_roles_and_unsupported_loader(self) -> None:
        target = load_target(self.project, "1.20.1", "0.18.4")
        self.assertEqual(target.host.name, "fabric-hotload-host-test.jar")
        self.assertFalse(target.ordinary_supported)

    def test_rejects_changed_binary(self) -> None:
        (self.directory / "fabric-hotload-host-test.jar").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            load_target(self.project, "1.20.1")

    def test_rejects_traversal_before_reading(self) -> None:
        self.manifest["artifacts"][0]["path"] = "../outside.jar"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            load_target(self.project, "1.20.1")

    def test_rejects_wrong_baseline(self) -> None:
        self.manifest["baseline"] = {"minecraft": "26.2"}
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "does not match"):
            load_target(self.project, "1.20.1")

    def test_rejects_extra_jar(self) -> None:
        (self.directory / "stale.jar").write_bytes(b"stale")
        with self.assertRaisesRegex(ValueError, "six"):
            load_target(self.project, "1.20.1")

    def test_rejects_unknown_target(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unknown"):
            load_target(self.project, "26.2")


if __name__ == "__main__":
    unittest.main()

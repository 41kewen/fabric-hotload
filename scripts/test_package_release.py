"""用独立合成夹具验证公开打包边界，不把夹具当成服务器测试结果。"""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


class PackageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name) / "source"
        self.project.mkdir()
        (self.project / "README.md").write_text("synthetic fixture", encoding="utf-8")
        (self.project / "settings.gradle").write_text("// synthetic fixture", encoding="utf-8")
        (self.project / "compatibility").mkdir()
        (self.project / "compatibility/targets.json").write_text(json.dumps({"targets": {"1.20.1": {}}}), encoding="utf-8")
        self.distribution = self.project / "build/targets/1.20.1/distribution"
        self.distribution.mkdir(parents=True)
        artifacts = []
        for index in range(6):
            path = self.distribution / f"fixture-{index}.jar"
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr("fixture.txt", str(index))
            artifacts.append({"path": path.relative_to(self.project).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        (self.distribution / "distribution.json").write_text(json.dumps({"target": "1.20.1", "version": "fixture"}), encoding="utf-8")
        self.report = self.project / "verification/integration-fixture.json"
        self.report.parent.mkdir()
        self.report.write_text(json.dumps({"target": "1.20.1", "project_version": "fixture", "success": True, "artifacts": artifacts}), encoding="utf-8")
        for name in ["verification/raw/private.log", "integration-tests/server-fixture/world/level.dat", "tools/cache.txt", "scripts/.env"]:
            path = self.project / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("NEVER_PUBLISH_FIXTURE", encoding="utf-8")
        self.output = Path(self.temporary.name) / "release.zip"

    def package(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(Path(__file__).with_name("package_release.py")),
                               "--project", str(self.project), "--report", str(self.report), "--output", str(self.output)],
                              capture_output=True, text=True, encoding="utf-8", timeout=30)

    def test_source_zip_packaging_excludes_private_content(self) -> None:
        result = self.package()
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.output) as package:
            self.assertIsNone(package.testzip())
            self.assertEqual(len([name for name in package.namelist() if name.endswith(".jar")]), 6)
            self.assertIn("fabric-hotload/SHA256SUMS-jars.txt", package.namelist())
            for name in package.namelist():
                self.assertNotIn(b"NEVER_PUBLISH_FIXTURE", package.read(name))

    def test_changed_binary_prevents_output(self) -> None:
        (self.distribution / "fixture-0.jar").write_bytes(b"changed")
        result = self.package()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())

    def test_empty_matrix_prevents_output(self) -> None:
        self.report.write_text(json.dumps({"success": True, "targets": []}), encoding="utf-8")
        result = self.package()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())

    def test_duplicate_matrix_target_prevents_output(self) -> None:
        row = {"target": "1.20.1", "status": "server-tested", "integration_report": "verification/integration-fixture.json"}
        self.report.write_text(json.dumps({"success": True, "targets": [row, row]}), encoding="utf-8")
        result = self.package()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()

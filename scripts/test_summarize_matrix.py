"""合成夹具只验证汇总筛选规则，不代表真实服务器通过。"""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from summarize_matrix import summarize


class SummaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        (self.project / "compatibility").mkdir()
        self.baseline = {"minecraft": "1.20.1", "java": 17, "loader": "0.20.1", "api": "fixture"}
        matrix = {"installer": "fixture", "targets": {"1.20.1": self.baseline}}
        (self.project / "compatibility/targets.json").write_text(json.dumps(matrix), encoding="utf-8")
        self.directory = self.project / "build/targets/1.20.1/distribution"
        self.directory.mkdir(parents=True)
        artifacts = []
        for prefix in ["fabric-hotload-host", "fabric-hotload-agent", "hotload-ordinary-probe",
                       "hotload-failure-probe", "hotload-demo-v1", "hotload-demo-v2"]:
            path = self.directory / (prefix + "-fixture.jar")
            data = prefix.encode("utf-8")
            path.write_bytes(data)
            artifacts.append({"path": path.name, "sha256": hashlib.sha256(data).hexdigest()})
        manifest = {"schema": 1, "target": "1.20.1", "version": "fixture", "baseline": self.baseline, "artifacts": artifacts}
        (self.directory / "distribution.json").write_text(json.dumps(manifest), encoding="utf-8")
        (self.project / "verification").mkdir()
        self.integration = self.project / "verification/integration.json"
        self.tested = {"success": True, "target": "1.20.1", "minecraft": "1.20.1", "loader": "0.20.1",
                       "fabric_api": "fixture", "project_version": "fixture", "checks": [{"name": "synthetic"}],
                       "artifacts": [dict(item, path="build/targets/1.20.1/distribution/" + item["path"]) for item in artifacts]}
        self.integration.write_text(json.dumps(self.tested), encoding="utf-8")
        self.row = {"target": "1.20.1", "baseline": self.baseline, "status": "server-tested", "build_exit_code": 0,
                    "integration_exit_code": 0, "integration_report": "verification/integration.json",
                    "unit_tests": {"tests": 1, "failures": 0, "errors": 0}}
        self.source = self.project / "verification/matrix.json"
        self.write_source()

    def write_source(self) -> None:
        self.source.write_text(json.dumps({"build_only": False, "platform": "fixture", "targets": [self.row]}), encoding="utf-8")

    def summary(self) -> dict[str, object]:
        # 测试项目不包含工具源码，从真实项目导入验证器。
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration-tests"))
        return summarize(self.project, [self.source])

    def test_accepts_matching_evidence_and_preserves_source(self) -> None:
        report = self.summary()
        self.assertTrue(report["success"])
        self.assertEqual(report["targets"][0]["source_matrix"], "verification/matrix.json")

    def test_failed_process_is_not_promoted(self) -> None:
        self.row["integration_exit_code"] = 1
        self.write_source()
        self.assertFalse(self.summary()["success"])

    def test_changed_tested_hash_is_not_promoted(self) -> None:
        self.tested["artifacts"][0]["sha256"] = "0" * 64
        self.integration.write_text(json.dumps(self.tested), encoding="utf-8")
        self.assertFalse(self.summary()["success"])

    def test_old_baseline_is_not_promoted(self) -> None:
        self.row["baseline"] = {"minecraft": "26.2"}
        self.write_source()
        self.assertFalse(self.summary()["success"])

    def test_report_traversal_is_rejected(self) -> None:
        self.row["integration_report"] = "../outside.json"
        self.write_source()
        with self.assertRaisesRegex(ValueError, "escapes"):
            self.summary()

    def test_normalizes_project_before_report_containment_check(self) -> None:
        self.summary()
        (self.project / "alias-component").mkdir()
        report = summarize(self.project / "alias-component" / "..", [self.source])
        self.assertTrue(report["success"])


if __name__ == "__main__":
    unittest.main()

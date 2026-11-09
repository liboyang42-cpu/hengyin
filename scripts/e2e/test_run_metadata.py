"""Synthetic offline fixtures only: these tests are never three-client acceptance."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from scripts.e2e import run_metadata as rm


class RunMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.run = rm.load(Path(__file__).parent / "fixtures/run_metadata.UNKNOWN.json")
        self.run.update(runId="synthetic-unit-only", purpose="acceptance")
        self.run["backend"] = {"sourceSha": "a" * 40, "jarSha256": "b" * 64,
                               "runtimeSourceSha": "a" * 40, "runtimeJarSha256": "b" * 64,
                               "runtimeEvidence": "evidence.txt"}
        self.run["environment"] = dict(kind="test", target="synthetic", databaseTarget="synthetic-db",
                                       verified=True, verifiedAt="synthetic-time", evidence="evidence.txt")
        self.run["content"] = dict(releaseId="unit-release", bindingRevision="unit-binding",
                                   contentRevisionId="42", contentRevisionNo=2, contentSha256="c" * 64,
                                   bindingIdentity={"topicId": "synthetic", "cityCode": "synthetic"},
                                   contractVersions={key: "unit-contract" for key in self.run["content"]["contractVersions"]},
                                   mappingEvidence="evidence.txt", revisionEvidence="evidence.txt",
                                   bindingEvidence="evidence.txt")
        (self.base / "evidence.txt").write_text("SYNTHETIC; not runtime evidence")
        # Deliberately not an image: existence must never certify rendered equality.
        (self.base / "screen.png").write_text("synthetic placeholder")
        for name in rm.CLIENTS:
            self.run["clients"][name] = dict(
                build="unit-" + name, actor="unit-actor", role="unit-role", device="unit-device",
                os="unit-os", session="unit-session", capturedAt="unit-time", normalEntry=True,
                request={"method": "GET", "path": "/unit-only", "chapterId": "chapter-1"},
                versionEvidence="evidence.txt", screenshot="screen.png",
                observedVersions={key: copy.deepcopy(self.run["content"][key]) for key in rm.VERSION_FIELDS},
                response={"path": name + ".json", "projection": "public", "comparisonRoot": "/data"})
            self.snapshot(name, {"blocks": [{"id": "a", "text": "first"}, {"id": "b", "text": "second"}],
                                 "fallback": "supported"})

    def snapshot(self, name, body):
        (self.base / (name + ".json")).write_text(json.dumps({"data": body}))

    def report(self, **kwargs):
        return rm.build_report(self.run, self.base, **kwargs)

    def test_unknown_template_is_pending_and_never_acceptance(self):
        template = rm.load(Path(__file__).parent / "fixtures/run_metadata.UNKNOWN.json")
        result = rm.build_report(template, self.base)
        self.assertEqual("PENDING", result["dataStatus"])
        self.assertEqual("PENDING", result["acceptanceStatus"])
        self.assertIn("backend/sourceSha", result["pending"])

    def test_identical_data_and_existing_screenshots_do_not_pass_rendering(self):
        result = self.report()
        self.assertEqual("CONSISTENT", result["dataStatus"])
        self.assertEqual([], result["differences"])
        self.assertEqual("PENDING_MANUAL_REVIEW", result["renderStatus"])
        self.assertEqual("PENDING", result["acceptanceStatus"])

    def test_order_text_and_missing_fields_have_locations(self):
        self.snapshot("ios", {"blocks": [{"id": "b", "text": "second"}, {"id": "a", "text": "first"}]})
        result = self.report()
        self.assertTrue(any(row["kind"] == "order" and row["path"] == "/blocks" for row in result["differences"]))
        self.assertTrue(any(row["kind"] == "missing_field" and row["path"] == "/fallback" for row in result["differences"]))
        self.snapshot("ios", {"blocks": [{"id": "a", "text": "changed"}, {"id": "b", "text": "second"}]})
        self.assertTrue(any(row["path"] == "/blocks/0/text" for row in self.report()["differences"]))
        self.assertNotIn("changed", json.dumps(self.report()["differences"]))

    def test_known_differences_require_current_id_and_reason(self):
        self.snapshot("ios", {"blocks": [], "fallback": "unit-fallback"})
        diff = self.report()["differences"]
        self.run["knownDifferences"] = [{"id": row["id"], "reason": "unit approved fallback"} for row in diff]
        self.assertEqual("CONSISTENT_WITH_KNOWN_DIFFERENCES", self.report()["dataStatus"])
        self.run["knownDifferences"][0]["reason"] = "   "
        self.assertEqual("DIFFERENT", self.report()["dataStatus"])
        self.run["knownDifferences"] = [{"id": "stale", "reason": "unit stale"}]
        self.assertIn("stale known differences; review against current snapshots", self.report()["errors"])

    def test_version_mismatch_cannot_be_excused(self):
        for key in rm.VERSION_FIELDS:
            with self.subTest(key=key):
                self.run["clients"]["ios"]["observedVersions"][key] = "different"
                self.assertIn("ios/observedVersions/" + key + " mismatch", self.report()["errors"])
                self.run["clients"]["ios"]["observedVersions"][key] = copy.deepcopy(self.run["content"][key])

    def test_missing_screenshot_and_unknown_clients_are_pending(self):
        (self.base / "screen.png").unlink()
        self.run["clients"]["unknown-client"] = {}
        result = self.report()
        self.assertEqual("PENDING", result["dataStatus"])
        self.assertIn("ios/screenshot", result["pending"])
        self.assertTrue(any("unknown clients" in item for item in result["pending"]))

    def test_private_projection_is_never_compared_as_public(self):
        self.run["clients"]["admin"]["response"]["projection"] = "private"
        self.snapshot("admin", {"answer": "private-author-answer"})
        result = self.report()
        self.assertEqual("PENDING", result["dataStatus"])
        self.assertEqual([], result["differences"])
        self.assertTrue(any("private projection" in item for item in result["pending"]))

    def test_stale_runtime_stops_scenario(self):
        self.run["backend"]["runtimeSourceSha"] = "f" * 40
        self.assertEqual("DIFFERENT", self.report()["dataStatus"])
        self.assertTrue(any("stop scenario" in item for item in self.report()["errors"]))

    def test_reuses_existing_receipt_and_manifest_without_inventing_runtime(self):
        self.run["backend"]["sourceSha"] = rm.UNKNOWN
        self.run["backend"]["jarSha256"] = rm.UNKNOWN
        receipt = {"plan": {"source_sha": "a" * 40}, "stages": {"explode": {"jar_sha256": "b" * 64}}}
        result = self.report(chain_receipt=receipt)
        self.assertEqual("a" * 40, result["metadata"]["backend"]["sourceSha"])
        self.assertEqual(rm.UNKNOWN, self.run["backend"]["sourceSha"])
        manifest = {"public": {"commit": "f" * 40}, "jar": {"sha256": "TO_FILL_FROM_CI_ARTEFACT"}}
        self.assertTrue(any("releaseManifest" in item for item in self.report(chain_receipt=receipt, release_manifest=manifest)["errors"]))

    def test_missing_nested_contract_and_normal_entry_stay_pending(self):
        self.run["content"]["contractVersions"]["C04"] = rm.UNKNOWN
        self.run["clients"]["ios"]["normalEntry"] = False
        self.assertEqual("PENDING", self.report()["dataStatus"])
        self.assertIn("content/contractVersions/C04", self.report()["pending"])
        self.assertIn("ios/normalEntry", self.report()["pending"])

    def test_request_mismatch_and_bad_pointer_are_reported(self):
        self.run["clients"]["ios"]["request"]["chapterId"] = "different-chapter"
        self.assertTrue(any("request mismatch" in item for item in self.report()["errors"]))
        self.run["clients"]["ios"]["response"]["comparisonRoot"] = "/missing"
        self.assertIn("ios/response unreadable: KeyError", self.report()["errors"])

    def test_cli_preserves_prior_report_and_unknown_returns_pending_code(self):
        template = Path(__file__).parent / "fixtures/run_metadata.UNKNOWN.json"
        out = self.base / "out"
        self.assertEqual(2, rm.main(["--metadata", str(template), "--out", str(out)]))
        before = (out / "diff-report.json").read_bytes()
        with self.assertRaises(FileExistsError):
            rm.main(["--metadata", str(template), "--out", str(out)])
        self.assertEqual(before, (out / "diff-report.json").read_bytes())


if __name__ == "__main__":
    unittest.main()

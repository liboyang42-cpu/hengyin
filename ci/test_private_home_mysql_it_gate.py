import tempfile
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
from ci.private_home_mysql_it_gate import EXPECTED, PREFIX, enforce


class PrivateHomeMySqlGateTest(unittest.TestCase):
    def reports(self, directory):
        for name, count in EXPECTED.items():
            root = ET.Element("testsuite", name=PREFIX + name, tests=str(count), failures="0", errors="0", skipped="0")
            ET.ElementTree(root).write(Path(directory) / ("TEST-" + PREFIX + name + ".xml"))

    def test_both_exact_suites_are_required(self):
        self.assertEqual({"PrivateHomeMySqlIT": 31, "PrivateHomeRetentionMySqlIT": 28}, EXPECTED)
        with tempfile.TemporaryDirectory() as directory:
            self.reports(directory)
            self.assertEqual([], enforce(directory))

    def test_each_suite_fails_closed_on_bad_report(self):
        for name, count in EXPECTED.items():
            for kind in ("missing", "malformed", "wrong_name", "short", "extra", "failures", "errors", "skipped", "missing_attribute", "invalid_count"):
                with self.subTest(suite=name, case=kind), tempfile.TemporaryDirectory() as directory:
                    self.reports(directory)
                    path = Path(directory) / ("TEST-" + PREFIX + name + ".xml")
                    if kind == "missing":
                        path.unlink()
                    elif kind == "malformed":
                        path.write_text("<not-xml")
                    else:
                        root = ET.parse(path).getroot()
                        if kind == "wrong_name": root.set("name", "unrelated.Suite")
                        elif kind == "short": root.set("tests", str(count - 1))
                        elif kind == "extra": root.set("tests", str(count + 1))
                        elif kind == "missing_attribute": del root.attrib["skipped"]
                        elif kind == "invalid_count": root.set("tests", "not-an-integer")
                        else: root.set(kind, "1")
                        ET.ElementTree(root).write(path)
                    self.assertTrue(enforce(directory))

    def test_absent_reports_are_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(2, len(enforce(directory)))

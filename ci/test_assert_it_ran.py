import os, sys, tempfile, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
import importlib.util
spec = importlib.util.spec_from_file_location("m", os.environ["GATE"])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

def report(d, short, tests, skipped=0, failures=0, errors=0):
    with open(os.path.join(d, "TEST-x.%s.xml" % short), "w") as h:
        h.write('<testsuite name="x.%s" tests="%d" skipped="%d" failures="%d" errors="%d"></testsuite>'
                % (short, tests, skipped, failures, errors))

class T(unittest.TestCase):
    def setUp(self): self.d = tempfile.mkdtemp()
    def test_green(self):
        report(self.d, "A", 3); report(self.d, "B", 1)
        self.assertEqual([], m.enforce(self.d, ["A", "B"]))
    def test_red_when_missing(self):
        report(self.d, "A", 3)
        self.assertTrue(any("没被执行" in p for p in m.enforce(self.d, ["A", "B"])))
    def test_red_when_skipped(self):
        report(self.d, "A", 3, skipped=3)
        self.assertTrue(any("skipped" in p for p in m.enforce(self.d, ["A"])))
    def test_red_when_failed(self):
        report(self.d, "A", 3, errors=1)
        self.assertTrue(any("errors=1" in p for p in m.enforce(self.d, ["A"])))
    def test_red_when_zero_tests(self):
        report(self.d, "A", 0)
        self.assertTrue(any("tests=0" in p for p in m.enforce(self.d, ["A"])))

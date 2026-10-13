import importlib.util
import pathlib
import tempfile
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("secret-file-gate.py")
SPEC = importlib.util.spec_from_file_location("secret_file_gate", MODULE_PATH)
GATE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(GATE)


class SecretFileGateTest(unittest.TestCase):
    def test_fake_pem_turns_gate_red_then_removal_turns_it_green(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            fake = root / "fake.pem"
            fake.write_text("-----BEGIN " + "PRIVATE KEY-----\nnot-a-real-key\n", encoding="utf-8")  # gitleaks:allow -- negative control

            violations = GATE.scan_paths(root, ["fake.pem"])
            self.assertTrue(violations, "塞入假 pem 时门禁必须判红")

            fake.unlink()
            self.assertEqual(GATE.scan_paths(root, []), [], "移除假 pem 后门禁必须恢复绿色")

    def test_private_key_header_is_blocked_even_with_text_suffix(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            disguised = root / "notes.txt"
            disguised.write_bytes(b"-----BEGIN OPENSSH " + b"PRIVATE KEY-----\nredacted")
            self.assertTrue(GATE.scan_paths(root, ["notes.txt"]))

    def test_normal_source_file_passes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            source = root / "app.js"
            source.write_text("module.exports = {};\n", encoding="utf-8")
            self.assertEqual(GATE.scan_paths(root, ["app.js"]), [])


if __name__ == "__main__":
    unittest.main()

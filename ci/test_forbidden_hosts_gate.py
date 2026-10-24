#!/usr/bin/env python3
"""ci/forbidden_hosts_gate.py 的自测:埋一个地址必须转红,去掉必须转绿。

埋点地址在运行时由片段拼出(TEST-NET-3 段),并把它的哈希临时注入黑名单 ——
走的是与生产地址完全相同的「字面量 → 规范化 → SHA-256 → 比对」路径,但仓库里不出现任何生产地址。
"""
import importlib.util
import io
import os
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

GATE_PATH = Path(__file__).with_name("forbidden_hosts_gate.py")
spec = importlib.util.spec_from_file_location("forbidden_hosts_gate", GATE_PATH)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

PLANTED = ".".join(str(octet) for octet in (203, 0, 113, 77))


class ForbiddenHostsGateTest(unittest.TestCase):
    def repo(self, files):
        tmp = tempfile.TemporaryDirectory(prefix="forbidden-hosts-")
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        for rel, content in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content if isinstance(content, bytes) else content.encode("utf-8"))
        subprocess.run(["git", "add", "-A"], cwd=root, check=True)
        return root

    def planted_denylist(self):
        return gate.denylist({}, frozenset({gate.sha256_hex(PLANTED)}))

    def test_planted_address_turns_the_gate_red(self):
        padded = ".".join("%03d" % int(o) for o in PLANTED.split("."))
        root = self.repo({
            "docs/note.md": "部署到 %s 上\n" % PLANTED,
            "src/Conf.java": 'String url = "jdbc:mysql://%s:3306/db";\n' % padded,
            "ok.txt": "127.0.0.1 and 1.2.3.4.5 are fine\n",
        })
        hits = gate.scan(root, self.planted_denylist())
        self.assertEqual([("docs/note.md", 1), ("src/Conf.java", 1)],
                         sorted((rel, line) for rel, line, _ in hits))

    def test_clean_tree_and_unrelated_addresses_pass(self):
        root = self.repo({"a.md": "127.0.0.1 10.0.0.9 198.51.100.4\n", "b.sql": "-- <生产库主机>\n"})
        self.assertEqual([], gate.scan(root, self.planted_denylist()))

    def test_untracked_and_binary_files_are_not_scanned(self):
        root = self.repo({"bin.dat": b"\0" + PLANTED.encode()})
        (root / "untracked.md").write_text(PLANTED)
        self.assertEqual([], gate.scan(root, self.planted_denylist()))

    def test_environment_denylist_is_honoured(self):
        root = self.repo({"x.md": PLANTED})
        hashes = gate.denylist({gate.FORBIDDEN_HOSTS_ENV: " 198.51.100.1 , %s" % PLANTED}, frozenset())
        self.assertEqual(1, len(gate.scan(root, hashes)))

    def test_cli_exits_non_zero_without_echoing_the_address(self):
        root = self.repo({"x.md": "host=%s\n" % PLANTED})
        out = io.StringIO()
        with mock.patch.dict(os.environ, {gate.FORBIDDEN_HOSTS_ENV: PLANTED}), redirect_stdout(out):
            self.assertEqual(1, gate.main([str(root)]))
        self.assertIn("x.md:1", out.getvalue())
        self.assertNotIn(PLANTED, out.getvalue())

    def test_built_in_denylist_still_covers_both_production_hosts(self):
        self.assertEqual(frozenset({
            "d8c695fe7dd5f6ffc048b72c96621f7e7d13d3ec23b76aab523514183e7474d9",
            "ce1a8527392b68bab1ab6a91238a5e6909cbbb937b9eb077d1025499a59f03fe",
        }), gate.BUILT_IN_SHA256)


if __name__ == "__main__":
    unittest.main()

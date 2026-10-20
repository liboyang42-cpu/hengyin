#!/usr/bin/env python3
"""生产主机地址门禁:受版本控制的文本文件里不许出现生产库主机 / 生产应用机的 IPv4 字面量。

本仓公开。生产地址一旦写进树里就会被索引,所以这道门:
  · 扫 `git ls-files` 列出的全部文本文件(含 NUL 字节的视为二进制,跳过);
  · 找出每个 IPv4 字面量,规范化(去前导零)后算 SHA-256;
  · 哈希命中黑名单就红。黑名单 = 内置两条哈希 + 环境变量 CHENGYIN_FORBIDDEN_DB_HOSTS(逗号分隔)。
门禁本身不含明文地址;报错只打印文件:行号与哈希前缀,不把地址回显进公开 CI 日志。

⚠️ git 历史里仍有这两个地址:本门禁只管当前树,真正的缓解是生产库端口不对公网开放。

用法:
  python3 ci/forbidden_hosts_gate.py           # 扫当前仓库
  python3 ci/test_forbidden_hosts_gate.py      # 自测(埋点必须转红)
"""
from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

FORBIDDEN_HOSTS_ENV = "CHENGYIN_FORBIDDEN_DB_HOSTS"
BUILT_IN_SHA256 = frozenset({
    "d8c695fe7dd5f6ffc048b72c96621f7e7d13d3ec23b76aab523514183e7474d9",  # 生产库主机
    "ce1a8527392b68bab1ab6a91238a5e6909cbbb937b9eb077d1025499a59f03fe",  # 生产应用机
})
# 前后不能紧挨数字或「.数字」,避免把版本号 1.2.3.4.5 之类切出一段来。
IPV4 = re.compile(rb"(?<![0-9.])((?:[0-9]{1,3}\.){3}[0-9]{1,3})(?![0-9]|\.[0-9])")
BINARY_SNIFF_BYTES = 8192


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical_ipv4(raw: bytes) -> str | None:
    octets = raw.decode("ascii").split(".")
    if any(int(o) > 255 for o in octets):
        return None
    return ".".join(str(int(o)) for o in octets)


def denylist(environ=None, built_in=BUILT_IN_SHA256) -> frozenset[str]:
    environ = os.environ if environ is None else environ
    extra = set()
    for entry in environ.get(FORBIDDEN_HOSTS_ENV, "").split(","):
        entry = entry.strip().lower()
        if entry:
            extra.add(sha256_hex(entry))
    return frozenset(built_in) | frozenset(extra)


def tracked_files(root: Path) -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True,
                         capture_output=True).stdout
    return [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]


def scan(root: Path, hashes: frozenset[str]) -> list[tuple[str, int, str]]:
    hits = []
    for rel in tracked_files(root):
        path = root / rel
        if not path.is_file() or path.is_symlink():
            continue
        data = path.read_bytes()
        if b"\0" in data[:BINARY_SNIFF_BYTES]:
            continue
        for match in IPV4.finditer(data):
            address = canonical_ipv4(match.group(1))
            if address is None:
                continue
            digest = sha256_hex(address)
            if digest in hashes:
                line = data.count(b"\n", 0, match.start()) + 1
                hits.append((rel, line, digest))
    return hits


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    root = Path(argv[0]) if argv else Path(__file__).resolve().parents[1]
    hits = scan(root, denylist())
    if not hits:
        print("生产主机地址门禁:通过(当前树没有命中黑名单的 IPv4 字面量)")
        return 0
    print("生产主机地址门禁:未通过 —— 以下位置写了生产主机地址(公开仓,不许出现):")
    for rel, line, digest in hits:
        print(f"  {rel}:{line}  sha256:{digest[:12]}…")
    print("  怎么修:换成占位符 <生产库主机> / <生产应用机>;代码里的拒连判断用 SHA-256 黑名单 "
          f"或环境变量 {FORBIDDEN_HOSTS_ENV}。")
    return 1


if __name__ == "__main__":
    sys.exit(main())

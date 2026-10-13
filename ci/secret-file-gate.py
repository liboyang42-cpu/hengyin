#!/usr/bin/env python3
"""阻断密钥/证书文件和私钥正文进入当前 Git tree。"""

from __future__ import annotations

import pathlib
import subprocess
import sys
from typing import Iterable


FORBIDDEN_SUFFIXES = {".pem", ".p12", ".pfx", ".jks", ".key"}
PRIVATE_KEY_MARKERS = (
    b"-----BEGIN " + b"PRIVATE KEY-----",  # gitleaks:allow -- scanner marker, not a key
    b"-----BEGIN RSA " + b"PRIVATE KEY-----",
    b"-----BEGIN EC " + b"PRIVATE KEY-----",
    b"-----BEGIN OPENSSH " + b"PRIVATE KEY-----",
)


def scan_paths(root: pathlib.Path, paths: Iterable[str]) -> list[str]:
    violations: list[str] = []
    for raw in paths:
        rel = pathlib.PurePosixPath(raw)
        file_path = root / pathlib.Path(*rel.parts)
        if rel.suffix.lower() in FORBIDDEN_SUFFIXES:
            violations.append(f"{raw}: 禁止跟踪密钥/证书扩展名")
            continue
        try:
            data = file_path.read_bytes()
        except (FileNotFoundError, IsADirectoryError, PermissionError):
            continue
        if any(marker in data for marker in PRIVATE_KEY_MARKERS):
            violations.append(f"{raw}: 包含私钥正文")
    return violations


def tracked_paths(root: pathlib.Path) -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        check=True,
        stdout=subprocess.PIPE,
    )
    return [part.decode("utf-8") for part in result.stdout.split(b"\0") if part]


def main() -> int:
    root = pathlib.Path(
        subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
    )
    violations = scan_paths(root, tracked_paths(root))
    if violations:
        print("SECRET-FILE-GATE FAIL", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print("SECRET-FILE-GATE PASS: 当前 Git tree 无密钥/证书文件或私钥正文")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

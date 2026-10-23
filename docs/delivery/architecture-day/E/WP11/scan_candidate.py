"""Limited local candidate scan; no credential values are printed."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
DELIVERY = Path(__file__).resolve().parent
manifest = json.loads((DELIVERY / "candidate.json").read_text())
source_paths = {row["path"] for row in manifest["files"]}
prefix = "docs/delivery/architecture-day/E/WP11/"
paths = subprocess.check_output(["git", "diff", "--name-only", manifest["base_sha"]], cwd=ROOT, text=True).splitlines()
assert paths, "candidate must differ from public baseline"
assert source_paths <= set(paths)
assert all(path in source_paths or path.startswith(prefix) for path in paths), "unexpected changed path"
patterns = {
    "github-token": rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})",
    "aws-access-key": rb"(?:AKIA|ASIA)[A-Z0-9]{16}",
    "google-api-key": rb"AIza[A-Za-z0-9_-]{30,}",
    "credential-literal": rb'''(?i)(?:password|appsecret|api[_-]?key|access[_-]?token|client[_-]?secret)\s*[=:]\s*["'][A-Za-z0-9_+/=-]{20,}["']''',
}
violations = []
for path in paths:
    data = (ROOT / path).read_bytes()
    for rule, pattern in patterns.items():
        for match in re.finditer(pattern, data):
            violations.append((path, rule, data[:match.start()].count(b"\n") + 1))
if violations:
    for path, rule, line in violations:
        print(f"FAIL {path}:{line}: {rule} (value redacted)")
    raise SystemExit(1)
print(f"PASS candidate path allowlist: 8 source/test files plus WP11 delivery only ({len(paths)} candidate files)")
print("PASS limited token/key/credential-literal pattern scan; values never printed")
print("NOT RUN gitleaks: binary unavailable; pinned download blocked by proxy connection failure")

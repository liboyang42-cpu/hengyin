#!/usr/bin/env python3
"""Read-only source checks; never execute Java, storage, processes or CI."""
import hashlib
import json
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[5]
BASE = "cc91b0845e3abd4a2060d54a857c8c179bd22d4f"
PROD = "chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/service/MySqlSchedulerCoordinator.java"
PREFIX = "chengyinhub-admin/src/test/java/com/chengyinhub/quartz/"
TEST = PREFIX + "util/SchedulerWorkFenceTwoProcessMySqlIT.java"
PROBE = PREFIX + "util/SchedulerFenceProcessProbe.java"
UNCHANGED = [
    "chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/util/AbstractQuartzJob.java",
    "chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/util/MySqlSessionLock.java",
    "chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/util/CanonicalJobTarget.java",
    PREFIX + "util/SchedulerHandoffBoundaryMySqlIT.java",
    PREFIX + "util/SchedulerSessionLockMySqlIT.java",
    PREFIX + "service/MySqlSchedulerCoordinatorTest.java",
    "ci/real_storage_it.py", "ci/real-storage-it-registry.json", "pom.xml",
]

def original(path):
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=ROOT)

addition = (
    "                // GET_LOCK returning success does not prove that its session survived until admission.\n"
    "                // Do not consume this fire time or enter business work after the task session was lost.\n"
    '                if (!work.isOwned()) throw new IllegalStateException("任务互斥会话在执行前失效，任务未调用");\n'
)
production = (ROOT / PROD).read_text()
assert production.count(addition) == 1
assert production.replace(addition, "").encode() == original(PROD)
assert production.index(addition) < production.index("lastFire.put(canonical") < production.index("action.run();")
for path in UNCHANGED:
    assert (ROOT / path).read_bytes() == original(path), path
scan = runpy.run_path(str(ROOT / "ci/real_storage_it.py"))["scan_java"]((ROOT / TEST).read_text())
assert scan["declared_tests"] == 3
assert not scan["dynamic"] and not scan["nested"] and not scan["conditions"] and not scan["gates"]
probe = (ROOT / PROBE).read_text()
assert "Thread.sleep" not in probe
assert '"SELECT DB FROM information_schema.processlist WHERE ID=?"' in probe
assert '"KILL CONNECTION " + id' in probe
result = {
    "baseline": BASE,
    "verification": "STATIC_ONLY",
    "actual_java_tests_run": 0,
    "new_declared_tests": scan,
    "unchanged_sources": UNCHANGED,
    "sha256": {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in [PROD, TEST, PROBE]},
    "not_run": ["Java compilation", "JUnit", "MySQL", "child JVMs", "cleanup", "CI", "domain economic effects"],
}
print(json.dumps(result, ensure_ascii=False, indent=2))

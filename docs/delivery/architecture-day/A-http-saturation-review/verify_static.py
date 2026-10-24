#!/usr/bin/env python3
"""Git/Python only: inspect a proposed fixture patch without editing shared source."""
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

BASE = "ef5331db7a4424acca5dfde5325e83149ff3e472"
TARGET = "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/AiHttpAdmissionTest.java"
METHOD = "    @Test\n    void realAdmissionStaysHeldAfterCallerTimeoutAndCleanupRejectionUntilWorkersExit()"
NEXT = "    private HttpServer server("
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def git(*args, env=None):
    return subprocess.check_output(["git", *args], cwd=ROOT, env=env)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def assertions(source):
    # All original assertions remain verbatim except indentation. Do not execute Java.
    statements = []
    for match in re.finditer(r"\bassert\w+\(", source):
        depth, quote, escaped = 1, None, False
        pos = match.end()
        while depth:
            char = source[pos]
            pos += 1
            if quote:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == quote:
                    quote = None
            elif char in "\"'":
                quote = char
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
        statements.append(" ".join(source[match.start():pos].split()))
    return collections.Counter(statements)


def main():
    original = git("show", f"{BASE}:{TARGET}")
    assert (ROOT / TARGET).read_bytes() == original, "shared source must stay untouched"
    patch = HERE / "review.patch"
    with tempfile.TemporaryDirectory(prefix="a-http-fixture-") as directory:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "index"))
        git("read-tree", BASE, env=env)
        git("apply", "--cached", "--check", str(patch), env=env)
        git("apply", "--cached", str(patch), env=env)
        assert git("diff", "--cached", "--name-only", BASE, env=env).decode().splitlines() == [TARGET]
        git("diff", "--cached", "--check", BASE, env=env)
        proposed = git("show", f":{TARGET}", env=env)
    old, new = original.decode(), proposed.decode()
    old_before, old_method = old.split(METHOD, 1)
    old_method, old_after = old_method.split(NEXT, 1)
    new_before, new_method = new.split(METHOD, 1)
    new_method, new_after = new_method.split(NEXT, 1)
    assert old_before == new_before and old_after == new_after
    assert not (assertions(old_method) - assertions(new_method)), "original assertions lost"
    assert old.count("@Test") == new.count("@Test")
    assert "@Disabled" not in new and "Assumptions." not in new
    for budget in ("releaseBody.await(10, TimeUnit.SECONDS)",
                   "cleanupEntered.await(5, TimeUnit.SECONDS)",
                   "providerEntered.await(3, TimeUnit.SECONDS)",
                   "reply.get(4, TimeUnit.SECONDS)",
                   "task.get(5, TimeUnit.SECONDS)",
                   "TimeUnit.SECONDS.toNanos(8)", "Thread.sleep(10)",
                   '"x".repeat(16 * 1024 * 1024)'):
        assert new_method.count(budget) == old_method.count(budget), budget
    assert new_method.index("cleanup.setCorePoolSize(workers)") < new_method.index("cleanup.submit(blocker)")
    assert new_method.index("cleanupEntered.await(5, TimeUnit.SECONDS)") < new_method.index("cleanup.setCorePoolSize(originalCleanupCore)")
    assert new_method.index("cleanup.setCorePoolSize(originalCleanupCore)") < new_method.index("int queued =")
    assert new_method.index("assertEquals(workers, cleanup.getActiveCount())") < new_method.index("replies.add(callers.submit")
    assert new_method.count("cleanup.setCorePoolSize(originalCleanupCore)") == 2
    assert "for (java.util.concurrent.Future<?> reply : replies)" in new_method
    assert "assertAll(\"all owned fixture futures must finish\", joins)" in new_method
    assert "|| cleanup.getActiveCount() != 0 || !cleanup.getQueue().isEmpty())" in new_method
    for forbidden in ("setMaximumPoolSize", "setKeepAliveTime", "setRejectedExecutionHandler",
                      "cleanup.shutdown", "cleanup.getQueue().clear", "cleanup.purge", "task.cancel"):
        assert forbidden not in new_method
    production = "chengyinhub-common/src/main/java/com/chengyinhub/common/utils/http/HttpUtils.java"
    production_bytes = git("show", f"{BASE}:{production}")
    assert (ROOT / production).read_bytes() == production_bytes
    assert b"1, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64)" in production_bytes
    print(json.dumps({
        "base": BASE,
        "changed_in_candidate": "docs only; shared Java source untouched",
        "proposed_patch_targets": [TARGET],
        "old_source_sha256": digest(original),
        "proposed_source_sha256": digest(proposed),
        "patch_sha256": digest(patch.read_bytes()),
        "production_http_utils_sha256": digest(production_bytes),
        "original_method_assertions_preserved": sum(assertions(old_method).values()),
        "test_methods_before_after": [old.count("@Test"), new.count("@Test")],
        "checks": "PASS: temporary-index apply; one method only; assertions and deadlines preserved; original production source untouched",
        "execution": "Java/JUnit/HTTP/MySQL/Redis/CI NOT_RUN",
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Guard the expensive CI jobs from running on unrelated pull requests.

This deliberately uses a small text contract instead of a YAML dependency so it
can run inside the always-cheap `changes` job before any expensive job starts.
"""

from __future__ import annotations

import os
import re
from pathlib import Path


CI = Path(os.environ.get("CI_COST_CONTRACT_FILE", ".github/workflows/ci.yml"))


def job_block(source: str, job: str) -> str:
    marker = re.search(rf"^  {re.escape(job)}:\n", source, re.MULTILINE)
    if marker is None:
        raise AssertionError(f"missing job: {job}")
    start = marker.start()
    body_start = marker.end()
    next_job = re.search(r"^  [A-Za-z0-9_]+:\n", source[body_start:], re.MULTILINE)
    end = body_start + next_job.start() if next_job else len(source)
    return source[start:end]


def filter_block(changes: str, name: str) -> str:
    marker = re.search(
        rf"^            {re.escape(name)}:\n", changes, re.MULTILINE
    )
    if marker is None:
        raise AssertionError(f"missing path filter: {name}")
    start = marker.start()
    body_start = marker.end()
    next_filter = re.search(
        r"^            [A-Za-z0-9_]+:\n", changes[body_start:], re.MULTILINE
    )
    end = body_start + next_filter.start() if next_filter else len(changes)
    return changes[start:end]


def assert_contains(source: str, needle: str) -> None:
    if needle not in source:
        raise AssertionError(f"missing CI contract: {needle}")


def validate(source: str) -> None:
    changes = job_block(source, "changes")

    for output in ("dependency", "migration"):
        assert_contains(changes, f"{output}: ${{{{ steps.filter.outputs.{output} }}}}")
        assert_contains(changes, f"            {output}:\n")

    # The cost-saving paths must be covered by their own routing output.
    dependency = filter_block(changes, "dependency")
    for path in (
        "              - '**/pom.xml'\n",
        "              - '**/package.json'\n",
        "              - '**/package-lock.json'\n",
        "              - '**/yarn.lock'\n",
        "              - '**/pnpm-lock.yaml'\n",
        "              - 'ci/dependency-audit-gate.py'\n",
        "              - 'ci/test_dependency_audit_gate.py'\n",
        "              - 'ci/grype.yaml'\n",
        "              - 'ci/test_ci_cost_contract.py'\n",
        "              - '.github/workflows/ci.yml'\n",
    ):
        assert_contains(dependency, path)

    migration = filter_block(changes, "migration")
    for path in (
        "              - 'chengyinhub-admin/**'\n",
        "              - '**/migration_*.sql'\n",
        "              - 'ci/migration-*.sh'\n",
        "              - 'ci/migration_*.py'\n",
        "              - 'ci/test_migration_*.py'\n",
        "              - 'ci/sql_splitter.py'\n",
        "              - 'ci/migration-dependencies.json'\n",
        "              - 'ci/classify-runtime.sh'\n",
        "              - 'ci/classify-runtime-check.sh'\n",
        "              - 'ci/ci-config-guard.sh'\n",
        "              - 'ci/test_ci_cost_contract.py'\n",
        "              - 'scripts/release-manifest.sh'\n",
        "              - '.github/workflows/ci.yml'\n",
    ):
        assert_contains(migration, path)

    backend = filter_block(changes, "backend")
    for path in (
        "              - 'ci/backend-test.sh'\n",
        "              - 'ci/role_permission_contract.py'\n",
        "              - 'ci/test_role_permission_contract.py'\n",
        "              - '.github/workflows/ci.yml'\n",
    ):
        assert_contains(backend, path)

    # 小程序的 analytics 白名单合同直接读该 Controller；只改服务端
    # 白名单也必须触发 xcx-check，否则跨栈契约会假绿。
    xcx = filter_block(changes, "xcx")
    assert_contains(
        xcx,
        "              - 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAnalyticsController.java'\n",
    )
    assert_contains(xcx, "              - '!chengyinhub-xcx/docs/**'\n")

    # 漫游红线门禁同样跨栈：它的扫描面含这三个后端文件，前瞻指引口径的真源是第四个。
    # 少任何一条，单改 Java/XML 的 PR 就会整个跳过 mini_program job（审计 5 §六.6 的假绿形态）。
    for path in (
        "              - 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRoamController.java'\n",
        "              - 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java'\n",
        "              - 'chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml'\n",
        "              - 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/RoamRouteStoryPolicy.java'\n",
    ):
        assert_contains(xcx, path)

    # H11 禁项可以落在后台 UI / 小程序 / 后端任一栈，所以门禁必须挂在无条件的 changes job 上，
    # 且要有完整历史才算得出 master 合并基线；两者缺一，另外两栈的 PR 会 skip 成假绿。
    assert_contains(changes, "          fetch-depth: 0")
    assert_contains(changes, "        run: |\n          node chengyinhub-xcx/scripts/roam-redline-gate.js --selftest\n          node chengyinhub-xcx/scripts/roam-redline-gate.js\n")

    expected = {
        "backend": "backend",
        "mini_program": "xcx",
        "admin_ui": "ui",
        "admin_ui_contract": "ui",
        "dependency_audit": "dependency",
        "migration_layout": "migration",
        "migration_safety": "migration",
    }
    for job, output in expected.items():
        block = job_block(source, job)
        assert_contains(block, "    needs: changes\n")
        assert_contains(block, "github.event_name == 'workflow_dispatch'")
        assert_contains(
            block,
            f"needs.changes.outputs.{output} == 'true'",
        )
        if "github.ref == 'refs/heads/master'" in block:
            raise AssertionError(
                f"{job} must not run on every master push; path-filter instead"
            )

    # Secret scanning intentionally remains unconditional: path-filtering it
    # would create a blind spot for secrets in docs, fixtures, or new file types.
    secret = job_block(source, "secret_scan")
    if "needs: changes" in secret or "needs.changes.outputs" in secret or "\n    if:" in secret:
        raise AssertionError("secret_scan must remain unconditional")

    mini = job_block(source, "mini_program")
    if "TZ=America/Los_Angeles" in mini:
        raise AssertionError("mini_program must not double-run unit tests in a second timezone")
    if "runs-on: ubuntu-latest" not in mini:
        raise AssertionError("mini_program must run on GitHub-hosted ubuntu-latest")
    for line in source.splitlines():
        stripped = line.split("#", 1)[0]
        if re.match(r"^\s*runs-on:", stripped) and "self-hosted" in stripped:
            raise AssertionError(
                "ci.yml must not use self-hosted runners; deploy.yml keeps the Mac"
            )

    drift = Path(".github/workflows/drift-audit.yml").read_text(encoding="utf-8")
    assert_contains(drift, "- cron: '25 */6 * * *'")
    assert_contains(drift, "  workflow_dispatch:\n")


def remove_from_job(source: str, job: str, needle: str) -> str:
    block = job_block(source, job)
    mutated = block.replace(needle, "", 1)
    if mutated == block:
        raise AssertionError(f"negative control could not mutate {job}: {needle}")
    return source.replace(block, mutated, 1)


def remove_from_filter(source: str, name: str, needle: str) -> str:
    changes = job_block(source, "changes")
    block = filter_block(changes, name)
    mutated = block.replace(needle, "", 1)
    if mutated == block:
        raise AssertionError(f"negative control could not mutate filter {name}: {needle}")
    return source.replace(block, mutated, 1)


def main() -> None:
    source = CI.read_text(encoding="utf-8")
    validate(source)

    # Keep the guard itself honest: each representative routing failure must
    # turn this contract red, rather than merely being asserted in prose.
    negative_controls = (
        (
            "backend needs",
            remove_from_job(source, "backend", "    needs: changes\n"),
        ),
        (
            "dependency path",
            remove_from_filter(source, "dependency", "              - '**/pom.xml'\n"),
        ),
        (
            "migration path",
            remove_from_filter(source, "migration", "              - '**/migration_*.sql'\n"),
        ),
        (
            "xcx analytics backend path",
            remove_from_filter(
                source,
                "xcx",
                "              - 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAnalyticsController.java'\n",
            ),
        ),
        (
            "xcx roam redline backend path",
            remove_from_filter(
                source,
                "xcx",
                "              - 'chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml'\n",
            ),
        ),
        (
            "redline gate on unconditional changes job",
            remove_from_job(
                source,
                "changes",
                "          node chengyinhub-xcx/scripts/roam-redline-gate.js\n",
            ),
        ),
        (
            "changes job full history",
            remove_from_job(source, "changes", "          fetch-depth: 0"),
        ),
    )
    for label, mutated in negative_controls:
        try:
            validate(mutated)
        except AssertionError:
            continue
        raise AssertionError(f"negative control unexpectedly green: {label}")

    print("CI cost routing contract: PASS")


if __name__ == "__main__":
    main()

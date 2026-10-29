#!/usr/bin/env python3
"""Guard the expensive CI jobs from running on unrelated pull requests.

This deliberately uses a small text contract instead of a YAML dependency so it
can run inside the always-cheap `changes` job before any expensive job starts.
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from test_verify_deploy_ci import ManualDeployCiTest


CI = Path(os.environ.get("CI_COST_CONTRACT_FILE", ".github/workflows/ci.yml"))


def job_block(source: str, job: str) -> str:
    marker = re.search(rf"^  {re.escape(job)}:\n", source, re.MULTILINE)
    if marker is None:
        raise AssertionError(f"missing job: {job}")
    start = marker.start()
    body_start = marker.end()
    next_job = re.search(r"^  [A-Za-z0-9_-]+:\n", source[body_start:], re.MULTILINE)
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


# dorny/paths-filter@v3 用 picomatch({dot: true}) 逐条匹配,默认 predicate-quantifier=some:
# 任一变更文件命中任一规则即 true。这里用纯 stdlib 复刻本仓用到的子集(** / * / ? / {a,b}),
# 让 xcx 路由的真/假可以按「一组变更文件」直接断言,而不只是逐字比对 YAML。
def glob_regex(pattern: str) -> re.Pattern:
    out, i = [], 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        elif pattern[i] == "?":
            out.append("[^/]")
            i += 1
        elif pattern[i] == "{":
            end = pattern.index("}", i)
            out.append("(?:" + "|".join(re.escape(part) for part in pattern[i + 1:end].split(",")) + ")")
            i = end + 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.compile("".join(out) + r"\Z")


def filter_patterns(block: str) -> list:
    return re.findall(r"^              - '([^']+)'$", block, re.MULTILINE)


def filter_matches(block: str, changed: list) -> bool:
    # some 量词下 '!x' 的含义是「任一文件不在 x」(几乎恒真);这里按 dorny 的语义如实计算。
    patterns = filter_patterns(block)
    compiled = [(p.startswith("!"), glob_regex(p.lstrip("!"))) for p in patterns]
    return any((not rx.match(f)) if neg else bool(rx.match(f)) for f in changed for neg, rx in compiled)


# 小程序单测/静态门禁实际读取的 chengyinhub-xcx 以外路径(2026-10-22 O16b 盘点:
# strace 全量回放 node --test tests/unit/**(13565/13565)+ mini_static 全部步骤,再加静态 grep 交叉核对)。
# 每条:代表性路径 -> 读它的测试 file:line。这些路径的变更必须让 xcx = true。
XCX_READ_SET = {
    "docs/design/miniprogram-page-cleanup-1009.md": "chengyinhub-xcx/tests/unit/page-cleanup-audit.test.js:15",
    "docs/assets/template-covers/final/p01-tonights-passcode.webp": "chengyinhub-xcx/tests/unit/mock-image-asset-safety.test.js:58",
    "docs/assets/template-covers/final/t25-shanghai-flavor-tour.webp": "chengyinhub-xcx/tests/unit/template-dev-preview-contract.test.js:153",
    "docs/delivery/gg-r5-zeroview-fixtures/team-wait.json": "chengyinhub-xcx/tests/unit/play-zero-hp-recovery.test.js:4",
    "docs/delivery/gg-r5-zeroview-fixtures/team-hp-zero-result-card.json": "chengyinhub-xcx/tests/unit/r5-player-runtime.test.js:232",
    "docs/城瘾小程序_设计系统权威基准_20260801.md": "chengyinhub-xcx/tests/unit/cy-icon-pairing-contract.test.js:178",
    "assets/covers/home-banners/home-banner-club.gif": "chengyinhub-xcx/tests/unit/home-action-banner-contract.test.js:99",
    "assets/covers/topic-templates/shanghai-solo/old-movie.jpg": "chengyinhub-xcx/tests/unit/official-solo-story-seed-contract.test.js:130",
    "design/icons/lucide/png/camera.png": "chengyinhub-xcx/tests/unit/licensed-seed-icon-assets.test.js:9",
    "ci/migration-dependencies.json": "chengyinhub-xcx/tests/unit/official-solo-story-seed-contract.test.js:9",
    "ci/xcx-unit-manifest.js": "chengyinhub-xcx/tests/unit/smoke.test.js:9",
    "ci/silent-error-allowlist.txt": "chengyinhub-xcx/scripts/ui-validity-gates.js:12",
    "ci/test_ds_selftest_temp_safety.py": "ci/xcx-check.sh:93",
    "chengyinhub-admin/sql/migration_seed_official_solo_story_topics_20260823.sql": "chengyinhub-xcx/tests/unit/official-solo-story-seed-contract.test.js:7",
    "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java": "chengyinhub-xcx/tests/unit/backend-miniprogram-route-exists.test.js:23",
    "chengyinhub-system/src/main/java/com/chengyinhub/business/domain/MerchantSubscription.java": "chengyinhub-xcx/tests/unit/batch9-api-contract.test.js:38",
    "chengyinhub-common/src/main/java/com/chengyinhub/common/core/domain/AjaxResult.java": "chengyinhub-xcx/tests/lint/ui-integrity.js:203",
    "chengyinhub-ui/src/main.js": "chengyinhub-xcx/scripts/roam-redline-gate.js:250",
    # page-cleanup-audit 对 git ls-files 全仓 readFileSync 这些扩展名(含 docs/ 下的 .json 等)。
    "docs/delivery/doc2-survey-20261022/survey.json": "chengyinhub-xcx/tests/tools/audit-page-cleanup.js:11",
    "ops/example.yml": "chengyinhub-xcx/tests/tools/audit-page-cleanup.js:11",
    # git grep '*.wxml' 全仓。
    "docs/fixture/page.wxml": "chengyinhub-xcx/tests/unit/roam-citystamp-entries.test.js:12",
    # rg / git --exclude-standard 受根 ignore 文件影响;cwd=仓库根的 python3 -m unittest 会优先导入根目录 *.py。
    ".gitignore": "ci/xcx-check.sh:86",
    "json.py": "ci/xcx-check.sh:93",
    # 小程序本体(含 chengyinhub-xcx/docs:xcx-check.sh 的 JSON 可解析扫描读它)。
    "chengyinhub-xcx/docs/DESIGN.md": "chengyinhub-xcx/tests/unit/ui-governance-docs-contract.test.js:12",
    "chengyinhub-xcx/docs/screenshots/x.json": "ci/xcx-check.sh:52",
}

# 门禁/工作流自身的变更也必须触发 xcx。
XCX_GATE_CHANGES = (
    ".github/workflows/ci.yml",
    ".github/workflows/mini-followup-regression.yml",
    "ci/xcx-check.sh",
    "ci/test_ci_cost_contract.py",
    "scripts/quality-gate.sh",
    "pom.xml",
    "package.json",
)

# 没有任何小程序测试读的变更:必须 xcx = false(这是本次改动要省下的那部分)。
XCX_UNREAD_CHANGES = (
    ("docs/delivery/doc2-survey-20261022/survey.md",),
    ("docs/delivery/release-closeout-20261022/lane-w4.md", "docs/delivery/ci-speed-20261022.md"),
    ("docs/design/other-page-notes.md",),
    ("README.md", "AGENTS.md"),
    ("docs/screenshots/a.png",),
    ("ops/runbook.md",),
)


def validate(source: str, backend_script: str | None = None, production_workflows: dict | None = None,
             mini_script: str | None = None) -> None:
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

    backend_job = job_block(source, "backend")
    backend_step = re.search(
        r"^      - name: Build and test backend\n(.*?)(?=^      - (?:name:|uses:)|\Z)",
        backend_job, re.MULTILINE | re.DOTALL,
    )
    if backend_step is None:
        raise AssertionError("missing backend build/test step")
    for line in (
        "          CHENGYIN_TEST_MYSQL_HOST: 127.0.0.1\n",
        "          CHENGYIN_TEST_MYSQL_PORT: ${{ env.IT_PORT }}\n",
        "          CHENGYIN_TEST_MYSQL_USER: root\n",
        "          CHENGYIN_TEST_MYSQL_PASSWORD: root\n",
    ):
        assert_contains(backend_step.group(1), line)
    if backend_script is None:
        backend_script = (Path(__file__).resolve().parent / "backend-test.sh").read_text()
    assert_contains(backend_script, "\nmvn -B clean test\n")
    assert_contains(backend_script, "\nCHENGYIN_IM_SEND_MYSQL_IT=1 CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_CONSENT_IDEMPOTENCY_MYSQL_IT=1 \\\n  mvn -B -pl chengyinhub-admin -am test")
    assert_contains(backend_script, "\n  ConsentRecordIdempotencyMySqlIT\n)")
    assert_contains(backend_script, "\n  ThemeCompletionRewardCapMySqlIT\n")

    # 小程序的 analytics 白名单合同直接读该 Controller；只改服务端
    # 白名单也必须触发 xcx-check，否则跨栈契约会假绿。
    xcx = filter_block(changes, "xcx")
    assert_contains(
        xcx,
        "              - 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAnalyticsController.java'\n",
    )
    # 不许出现否定规则:默认 some 量词下 '!x' 对几乎所有 PR 都为真(#483 纯 docs PR 跑满小程序门禁的根因)。
    if re.search(r"^              - '!", xcx, re.MULTILINE):
        raise AssertionError("xcx filter must not use negated patterns under predicate-quantifier some")
    if re.search(r"^\s+predicate-quantifier:", changes, re.MULTILINE):
        raise AssertionError("xcx routing assumes the default predicate-quantifier: some")
    for path in (
        "chengyinhub-xcx/**",
        "chengyinhub-admin/**",
        "chengyinhub-system/**",
        "chengyinhub-common/**",
        "chengyinhub-framework/**",
        "chengyinhub-quartz/**",
        "chengyinhub-generator/**",
        "*/src/main/java/**",
        "chengyinhub-ui/**",
        "**/*.{js,json,wxml,vue,html,java,xml,sql,yml,yaml,properties,ts}",
        "**/*.wxss",
        "*.py",
        ".gitignore",
        ".gitattributes",
        ".gitmodules",
        ".mailmap",
        ".ignore",
        ".rgignore",
        ".npmrc",
        "ci/**",
        "scripts/**",
        "sql/**",
        ".github/**",
        "assets/covers/**",
        "design/icons/lucide/**",
        "docs/assets/template-covers/**",
        "docs/delivery/gg-r5-zeroview-fixtures/**",
        "docs/design/miniprogram-page-cleanup-1009.md",
        "docs/城瘾小程序_设计系统权威基准_20260801.md",
    ):
        assert_contains(xcx, f"              - '{path}'\n")
    # 语义正控:每条被小程序测试读到的路径、每类门禁变更都必须让 xcx = true。
    for path, reader in XCX_READ_SET.items():
        if not filter_matches(xcx, [path]):
            raise AssertionError(f"xcx filter misses mini test input {path} (read by {reader})")
    for path in XCX_GATE_CHANGES:
        if not filter_matches(xcx, [path]):
            raise AssertionError(f"xcx filter misses gate change {path}")
    # 语义负控:没人读的纯文档变更必须 xcx = false;混入一个被读文件则必须翻回 true。
    for changed in XCX_UNREAD_CHANGES:
        if filter_matches(xcx, list(changed)):
            raise AssertionError(f"xcx filter is true for unread-only change {changed}")
        if not filter_matches(xcx, list(changed) + ["chengyinhub-xcx/app.js"]):
            raise AssertionError(f"xcx filter is false for mixed change {changed} + chengyinhub-xcx/app.js")

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
    assert_contains(changes, "        run: bash ci/test_control_plane_readbacks.sh\n")
    assert_contains(changes, "    runs-on: ubuntu-latest\n")
    readbacks = (Path(__file__).resolve().parent / "test_control_plane_readbacks.sh").read_text()
    assert_contains(readbacks, 'bash "$ROOT/scripts/test-production-preflight.sh"\n')
    assert_contains(readbacks, 'python3 "$ROOT/scripts/test-production-preflight-service.py"\n')
    assert_contains(readbacks, 'python3 "$ROOT/ci/test_check_deploy_health.py"\n')
    assert_contains(readbacks, 'python3 "$ROOT/ci/test_db_backup_restore.py"\n')
    assert_contains(readbacks, 'bash "$ROOT/ci/test_deploy_sh_backup_rotate.sh"\n')

    expected = {
        "backend": "backend",
        "mini_unit": "xcx",
        "mini_static": "xcx",
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

    audit = job_block(source, "dependency_audit")
    assert_contains(audit, "          output-file: ${{ github.workspace }}/chengyin-dependency-scan.json\n")
    assert_contains(audit, "        run: python3 ci/dependency-audit-gate.py chengyin-dependency-scan.json\n")
    assert_contains(audit, """      - name: Preserve dependency scan evidence
        if: ${{ !cancelled() }}
        uses: actions/upload-artifact@v4
        with:
          name: chengyin-dependency-scan-${{ github.run_attempt }}
          path: chengyin-dependency-scan.json
          if-no-files-found: error
""")

    mini = job_block(source, "mini_program")
    for contract in (
        "    needs: [changes, mini_unit, mini_static, fast_gates, backend, admin_ui, admin_ui_contract, secret_scan, dependency_audit, migration_layout, migration_safety]\n",
        "    if: ${{ always() && (needs.changes.result != 'success' || github.event_name == 'workflow_dispatch' || needs.changes.outputs.xcx == 'true') }}\n",
        '          test "$CHANGES_RESULT" = success\n',
        '          test "$UNIT_RESULT" = success\n',
        '          test "$STATIC_RESULT" = success\n',
        "          UNIT_RESULT: ${{ needs.mini_unit.result }}\n",
        "          STATIC_RESULT: ${{ needs.mini_static.result }}\n",
        "        if: ${{ needs.changes.outputs.selected != 'true' }}\n",
        "        if: ${{ always() && needs.changes.outputs.selected == 'true' }}\n",
        "        run: node ci/ci-selected-validation.js summarize\n",
    ):
        assert_contains(mini, contract)
    unit = job_block(source, "mini_unit")
    for contract in (
        "    name: ${{ github.event_name == 'workflow_dispatch' && inputs.validation_scope == 'selected' && format('PARTIAL SELECTED - Mini shard {0} of 20 partitions', matrix.shard) || format('Mini program unit shard ({0}/20)', matrix.shard) }}\n",
        "      fail-fast: false\n",
        "      max-parallel: 20\n",
        "        shard: [" + ", ".join(map(str, range(1, 21))) + "]\n",
        "        exclude: ${{ fromJSON(needs.changes.outputs.mini_exclude) }}\n",
        "          CI_UNIT_OUTCOME: ${{ steps.unit.outcome }}\n",
        "      TZ: Asia/Shanghai\n",
        "    runs-on: ubuntu-latest\n",
        "        run: node ci/xcx-unit-manifest.js --selftest\n",
        '        run: node --test --test-shard=${{ matrix.shard }}/20 "tests/unit/**/*.test.js"\n',
    ):
        assert_contains(unit, contract)
    static = job_block(source, "mini_static")
    assert_contains(static, "        run: bash ci/xcx-check.sh\n")
    if 'lint:ui' in static or 'ui-integrity.js' in static:
        raise AssertionError("mini_static must run UI integrity once through xcx-check.sh")
    if mini_script is None:
        mini_script = Path('ci/xcx-check.sh').read_text(encoding='utf-8')
    ui_selftest = 'node "$XCX_DIR/tests/lint/ui-integrity.js" --selftest --warn-as-error || mark_fail 自证\n'
    ui_scan = 'if ! node "$XCX_DIR/tests/lint/ui-integrity.js" --warn-as-error; then\n  mark_fail\nfi\n'
    if (mini_script.count(ui_selftest) != 1 or mini_script.count(ui_scan) != 1
            or mini_script.index(ui_selftest) > mini_script.index(ui_scan)):
        raise AssertionError("xcx-check must retain one fail-closed UI selftest followed by one full scan")
    if 'continue-on-error:' in mini + unit + static:
        raise AssertionError("mini program checks must fail closed")
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

    if production_workflows is None:
        production_workflows = workflow_sources()
    guard = ManualDeployCiTest()
    for name, workflow in production_workflows.items():
        events = re.search(r'^on:\n([\s\S]*?)(?=^[A-Za-z]|\Z)', workflow, re.MULTILINE)
        if events is None or re.findall(r'^  ([\w-]+):', events.group(1), re.MULTILINE) != ['workflow_dispatch']:
            raise AssertionError(f'{name}: production workflow must be manual-only')
        jobs = re.findall(r'^  ([\w-]+):\n', workflow.split('\njobs:\n', 1)[1], re.MULTILINE)
        if not jobs:
            raise AssertionError(f'{name}: missing guarded production jobs')
        for job in jobs:
            matched = re.search(r'^    if:([^\n]*)(?:\n((?:      [^\n]*(?:\n|$))*))?',
                                job_block(workflow, job), re.MULTILINE)
            if matched is None:
                raise AssertionError(f'{name}/{job}: missing production job guard')
            condition = matched.group(1).strip()
            if condition in ('>', '>-', '|', '|-'):
                condition = ' '.join(line.strip() for line in (matched.group(2) or '').splitlines())
            guard.assert_private_repository_guard(condition)


def workflow_sources() -> dict:
    return {name: Path('.github/workflows', name).read_text(encoding='utf-8')
            for name in ('deploy.yml', 'deploy-ui.yml', 'codeup-mirror.yml', 'drift-audit.yml', 'upload-template-cover-preview.yml')}


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
            "dependency report retained after gate failure",
            remove_from_job(source, "dependency_audit", "        if: ${{ !cancelled() }}\n"),
        ),
        (
            "dependency report exact JSON path",
            remove_from_job(source, "dependency_audit", "          path: chengyin-dependency-scan.json\n"),
        ),
        (
            "dependency report retained per attempt",
            source.replace("name: chengyin-dependency-scan-${{ github.run_attempt }}",
                           "name: chengyin-dependency-scan"),
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
            "xcx negated docs exclusion is always true",
            source.replace(
                "              - 'chengyinhub-xcx/**'\n",
                "              - 'chengyinhub-xcx/**'\n              - '!chengyinhub-xcx/docs/**'\n", 1),
        ),
        (
            "xcx predicate quantifier changed",
            source.replace("          token: ''\n", "          token: ''\n          predicate-quantifier: every\n", 1),
        ),
        (
            "xcx catch-all makes docs-only PRs run",
            source.replace(
                "              - 'chengyinhub-xcx/**'\n",
                "              - 'chengyinhub-xcx/**'\n              - '**'\n", 1),
        ),
    )
    negative_controls += tuple(
        ("xcx read path " + path, remove_from_filter(source, "xcx", f"              - '{path}'\n"))
        for path in (
            "docs/delivery/gg-r5-zeroview-fixtures/**",
            "docs/assets/template-covers/**",
            "docs/design/miniprogram-page-cleanup-1009.md",
            "docs/城瘾小程序_设计系统权威基准_20260801.md",
            "assets/covers/**",
            "design/icons/lucide/**",
            "**/*.{js,json,wxml,vue,html,java,xml,sql,yml,yaml,properties,ts}",
            "chengyinhub-ui/**",
            ".gitignore",
            "*.py",
        )
    )
    negative_controls += (
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
        (
            "control-plane guard on unconditional changes job",
            remove_from_job(source, "changes", "        run: bash ci/test_control_plane_readbacks.sh\n"),
        ),
        (
            "service environment guard runs on Linux",
            remove_from_job(source, "changes", "    runs-on: ubuntu-latest\n"),
        ),
        (
            "backend independent revision scratch endpoint",
            remove_from_job(source, "backend", "          CHENGYIN_TEST_MYSQL_PORT: ${{ env.IT_PORT }}\n"),
        ),
        (
            "backend independent revision scratch credentials",
            remove_from_job(source, "backend", "          CHENGYIN_TEST_MYSQL_PASSWORD: root\n"),
        ),
    )
    negative_controls += tuple(
        ("mini parallel " + needle.strip(), remove_from_job(source, job, needle))
        for job, needle in (
            ("mini_program", '          test "$UNIT_RESULT" = success\n'),
            ("mini_program", '          test "$STATIC_RESULT" = success\n'),
            ("mini_program", "always() && "),
            ("mini_unit", "19, 20"),
            ("mini_unit", "--test-shard=${{ matrix.shard }}/20 "),
            ("mini_unit", "        run: node ci/xcx-unit-manifest.js --selftest\n"),
            ("mini_unit", "        exclude: ${{ fromJSON(needs.changes.outputs.mini_exclude) }}\n"),
            ("mini_unit", "          CI_UNIT_OUTCOME: ${{ steps.unit.outcome }}\n"),
            ("mini_program", "        if: ${{ needs.changes.outputs.selected != 'true' }}\n"),
            ("mini_program", "        run: node ci/ci-selected-validation.js summarize\n"),
            ("mini_static", "        run: bash ci/xcx-check.sh\n"),
        )
    )
    negative_controls += (("duplicate UI scan", source.replace(
        "      - name: Check mini program RequestBody contracts\n",
        "      - name: Check mini program UI integrity\n"
        "        run: npm --prefix chengyinhub-xcx run lint:ui\n"
        "      - name: Check mini program RequestBody contracts\n", 1)),)
    for label, mutated in negative_controls:
        try:
            validate(mutated)
        except AssertionError:
            continue
        raise AssertionError(f"negative control unexpectedly green: {label}")

    mini_script = Path('ci/xcx-check.sh').read_text(encoding='utf-8')
    ui_selftest = 'node "$XCX_DIR/tests/lint/ui-integrity.js" --selftest --warn-as-error || mark_fail 自证\n'
    ui_scan = 'if ! node "$XCX_DIR/tests/lint/ui-integrity.js" --warn-as-error; then\n  mark_fail\nfi\n'
    for label, mutated in (
        ("missing UI selftest", mini_script.replace(ui_selftest, '', 1)),
        ("missing UI full scan", mini_script.replace(ui_scan, '', 1)),
        ("duplicate UI full scan", mini_script.replace(ui_scan, ui_scan * 2, 1)),
        ("UI selftest after scan", mini_script.replace(ui_selftest, '', 1).replace(ui_scan, ui_scan + ui_selftest, 1)),
    ):
        try:
            validate(source, mini_script=mutated)
        except AssertionError:
            continue
        raise AssertionError(f"negative control unexpectedly green: {label}")

    backend_script = (Path(__file__).resolve().parent / "backend-test.sh").read_text()
    for label, mutated in (
        ("independent revision IT disabled", backend_script.replace("CHENGYIN_FUNDS_MYSQL_IT=1 ", "", 1)),
        ("consent idempotency IT disabled", backend_script.replace("CHENGYIN_CONSENT_IDEMPOTENCY_MYSQL_IT=1 ", "", 1)),
        ("consent idempotency IT unlisted", backend_script.replace("\n  ConsentRecordIdempotencyMySqlIT\n", "\n", 1)),
        ("theme completion cap IT unlisted", backend_script.replace("\n  ThemeCompletionRewardCapMySqlIT\n", "\n", 1)),
        ("full unit accidentally enables funds IT", backend_script.replace("\nmvn -B clean test\n", "\nCHENGYIN_FUNDS_MYSQL_IT=1 mvn -B clean test\n", 1)),
    ):
        if mutated == backend_script:
            raise AssertionError(f"negative control could not mutate backend script: {label}")
        try:
            validate(source, mutated)
        except AssertionError:
            continue
        raise AssertionError(f"negative control unexpectedly green: {label}")

    workflows = workflow_sources()
    for name, workflow in workflows.items():
        mutations = [('automatic push', workflow.replace('on:\n', 'on:\n  push:\n', 1))]
        for job in re.findall(r'^  ([\w-]+):\n', workflow.split('\njobs:\n', 1)[1], re.MULTILINE):
            block = job_block(workflow, job)
            changed = block.replace("github.repository == 'liboyang42-cpu/chengyin' &&", '', 1)
            if changed == block:
                raise AssertionError(f'negative control could not remove guard: {name}/{job}')
            mutations.append((job + ' repository guard', workflow.replace(block, changed, 1)))
        for label, mutated in mutations:
            try:
                validate(source, production_workflows={**workflows, name: mutated})
            except AssertionError:
                continue
            raise AssertionError(f'negative control unexpectedly green: {name}/{label}')

    print("CI cost routing contract: PASS")


if __name__ == "__main__":
    main()

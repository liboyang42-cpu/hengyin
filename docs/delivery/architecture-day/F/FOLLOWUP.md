# F 接线与低成本增量门禁修订

当前结论：真实 `.github/workflows/ci.yml` 已增加架构步骤，替代仅交付 patch；增量逻辑已修正，普通 Java/测试/workflow 变化本身不会阻断，七项审批 JSON 流程已移除。用户已明确批准经过审阅、秘密扫描的隔离 CI 候选提交例外；verifier 固定为 `2cddcd9ee925d9ac10f305a032878576dde6324d`。实际 GitHub Backend dispatch 由协调者单一执行；本线程没有启动 CI、Java、Docker、SSH 或 VPS 活动。

工作树 `/workspace/hengyin-architecture-f`，分支 `codex/architecture-day-f-guardrails`，本轮开工 HEAD 为已推送的 `1ce4132abb4c4c1ceb92c28fd244382c3ff3f45c`；新 verifier 提交为上述 2cddcd9e，最终接线/交付候选 SHA 见交付消息。本轮新增写权仅 `.github/workflows/ci.yml` 架构步骤；其余修改仍在 F 的 `ci/architecture/` 和本交付目录。没有读取私有历史或凭据。

## 这次修正的合同

- 固定 `baseline.json` 字节完全不变，每次从公共 2ec68899 的 git blob 精确再生成；它仍保存 statement/include 指纹及依赖/技术写入口观察，不接受按新候选扩充例外。
- 运行时影响使用 CI 事件提供并被 verifier 重新计算核对的完整比较 SHA。PR 使用实际 head/base 的唯一 merge-base；push 使用事件 before；workflow_dispatch 使用候选第一父提交；校验祖先、事件/仓库身份和 CI checkout 关系，缺上下文默认拒绝。静态 PR 候选是 PR head，Backend 原有测试仍可能是 synthetic merge，两个身份明确输出、不混用结果。
- 历史已经合入可信比较 base 的改动不再重复列。测试在临时仓库中先观察跨 writer 变化为红，再把该提交作为后续正常 PR 的 base，证实后续只列新文档变化、findings/review_items 均空；固定 baseline 不变。
- 阻断限定在能可靠识别的新依赖坐标/数量或已知 scope 扩大、新增已知表的跨 module/Mapper 写入口、字面 UPDATE/DELETE 缺 WHERE 或 WHERE 恒真，以及 verifier/baseline 篡改、候选/比较身份错误。复用既有 SQL splitter 处理引号和注释，select 标签直接伪装 DML 也受检查。
- 动态 SQL、未知表/字段、复杂语义、删除恢复语句、版本变化/复杂 Maven 解析等保留 `review_items`。普通 PR review 负责业务 owner/scope、CAS/版本、原意图、确定业务身份、持久恢复与原子性，不要求另造 JSON 代替代码审阅，也不要求每个低风险改动提供 Java 报告。`review_items` 单独存在时不把它伪称已验证安全。
- 无法完整证明：Java 调用/反射/JDBC、鉴权归属、所有动态 SQL 路径、字段级经济 owner、事务边界、外部 UNKNOWN 查单和恢复调度可达。本工具不声称这些已经自动验收；必要的危险命令审阅和故障/并发测试仍须正常执行。

## 接线形态与预算

架构步骤加入现有无条件 `fast_gates` 尾部，没有新 job/matrix。对旧 HEAD 的 workflow 去掉 F 标记区间后，其他内容逐字一致：job 名、if/needs、Backend 路径路由、已有检查、20 shard 和 `max-parallel: 20` 均未改变。专属自测还检查完整 SHA pin、真实 compare-base 参数、前置自测结果、错误传播和 shell 语法。

可信 verifier 独立 checkout；自测从固定 verifier 执行，候选放本 job 唯一临时 worktree，读取完整提交图，退出时只清理自身目录；不添加 runner、Java 构建或全局清理。findings/review_items 上传 artifact。前序 gate 失败后仍可运行本检查，架构自身失败不被忽略。

旧 `ci-wiring.patch` 已移除，避免协调者误应用旧的“新增 job + 永久 BASE diff”方案。旧 `REPORT.md` 和 941612/1ce413 的验证日志仅为第一轮历史证据，本文件和新版 README 才描述当前实现。

## 实际轻量验证

更新后的 50 项专属自测输出在 `followup-selftests.txt`；既有 `ci/test_assert_it_ran.py` 的 5 项自测输出在 `followup-assert.txt`；`ci/test_ci_cost_contract.py` 输出在 `followup-ci-cost.txt`。`followup-verification.json` 记录取得候选例外前的未提交代码快照 diff 哈希、输出哈希和退出码；取得例外后执行文件/私钥门禁及项目已固定版本和 SHA256 的 Gitleaks 8.21.2 对本轮改动文件扫描，无泄漏，未增加项目依赖。合成 Git/SQL/XML 样本只证明门禁规则和负控，不是 Java/MySQL 结果。固定 baseline 再生成比较、workflow 标记区间外逐字比较及 `git diff --check` 实际执行通过。

当前未执行：新候选实际 CI、Backend tests、Maven/Java 编译、Java 单测、MySQL/Redis、真实 HTTP/资金链。没有用旧 2ec push 的总绿或 Backend skipped 继承为本轮结果。

## Java 合法入口核对结果

仅搜索公共项目脚本和文档；没有读取 SSH/代理/凭据文件，没有运行 Docker 或 SSH 探测。公共工作树的 `AGENTS.md` 和当前 CI 均已使用 Boot 4.1.1 / Java 21；旧材料中的 JDK11、裸 Docker Maven 或本机 Colima 指令不能作为当前准入。

- `docs/verification/mini-bug-arch-followups-20261015/C00_HANDOFF.md:59` 明确：212 守卫/锁/离线预算由本地协调者排队，worker 不连 VPS；network-none 不是真库通道。
- `docs/verification/mini-longrun-20261015/EXTRA6/E02-25.md:76` 明确：现有 212 network-none 入口不能运行真库命令。
- 现有 `ci/`、`scripts/` 未找到 `/home/ubuntu/.local/bin/chengyin-build-budget-212` 的包装实现、合法参数 schema 或可调用入口。不能据旧文档猜参数，也不能凭 Mac 受保护 Docker socket 可读就推导出预算内执行权。
- 协调者反馈批准开发 SSH alias 已 exit255/Operation not permitted；这不是本线程探测结果，且不含地址。本线程未重试或绕过。当前没有确认可达的 VPS 预算构建路径。

## 可执行的既有 GitHub-hosted 方案（由协调者获得候选例外后执行）

仓库仅 `liboyang42-cpu/hengyin`。不触发 deploy、backend-jar 或 master push。不要把 `ci/verify_deploy_ci.py` 当作本次执行器，它只是部署前证据检查且要求面更广。

1. 冻结、推送获准的任务分支，并记录完整 SHA；dispatch 只能选择已推送、可回读的候选，不能让本地未提交补丁冒充 CI 输入。先从远端 refs 核对分支 head 等于计划候选。
2. 使用现有 `ci.yml` 的 `workflow_dispatch`。其 Backend 条件显式包含 workflow_dispatch，因此不受这次只改 CI/Python 路径的过滤跳过；这与旧 2ec push 的 Backend skipped 不同。已有 GitHub-hosted Ubuntu、Temurin21、独占 MySQL8.0.45 动态端口执行 `ci/backend-test.sh`，不是在 Cloud VM/Mac/VPS 裸起后端。

```sh
TASK_BRANCH='codex/architecture-day-f-guardrails'
CANDIDATE_SHA='<获准并已推送的完整40位SHA>'
test "$(gh api 'repos/liboyang42-cpu/hengyin/git/ref/heads/'"$TASK_BRANCH" --jq .object.sha)" = "$CANDIDATE_SHA"
gh workflow run ci.yml --repo liboyang42-cpu/hengyin --ref "$TASK_BRANCH"
gh run list --repo liboyang42-cpu/hengyin --workflow ci.yml \
  --branch "$TASK_BRANCH" --event workflow_dispatch \
  --json databaseId,headSha,event,status,conclusion
```

从输出选择 headSha 精确相等的新 run ID，不能取另一个候选或只看最新绿色。回读该 run 的 jobs 和实际步骤：

```sh
RUN_ID='<上述精确候选的新run ID>'
gh api "repos/liboyang42-cpu/hengyin/actions/runs/$RUN_ID" \
  --jq '{id,head_sha,event,status,conclusion,run_attempt}'
gh api --paginate "repos/liboyang42-cpu/hengyin/actions/runs/$RUN_ID/jobs?per_page=100" \
  --jq '.jobs[] | select(.name=="Backend tests") | {id,status,conclusion,steps}'
```

验收至少要求 `Backend tests` job 为 success，且 `Build and test backend`、`Verify isolated full application startup` 等实际步骤均完成/success，不能是 skipped；保存 Backend job 原始日志，核对 Maven/Surefire 实跑计数及 `assert_it_ran` 零 skip 回读。现有 `chengyin-tested-backend-<attempt>` artifact 的 build.json 应绑定 checkoutCommit、candidateHead、runId/runAttempt；仅 jar 存在、仅 job 总绿或仅 Python 门禁过均不足。PR merge 的 checkoutCommit 与 head SHA 不同必须明确记录，不能复用成单一候选通过。

3. 需要聚焦真库时，现有 `.github/workflows/real-storage-it.yml` 接受完整 `source_sha`、既有登记 `batch`、可选该批次内 `classes`、`timezone`。它在 GitHub-hosted 临时 runner 启动独占 MySQL/Redis、逐类执行、核对原始 XML/存储身份/清理后汇总。保持原有 `max-parallel: 20`，不新增登记/放松计数或门控；未知新测试不冒充现有批次覆盖。

```sh
gh workflow run real-storage-it.yml --repo liboyang42-cpu/hengyin --ref "$TASK_BRANCH" \
  -f source_sha="$CANDIDATE_SHA" -f batch='<已核对适用的现有批次>' \
  -f classes='<该批次内需执行的类，留空为整批>' -f timezone=UTC
```

这里 workflow 的 head SHA 可能是 verifier 所在 ref，真被测候选以每类 receipt/summary 的 source_sha 和 candidate.sha 为准，须与 CANDIDATE_SHA 相等；同时核对 batch、expected/executed、skipped=0、failures/errors=0、cleanup=CLEAN 及真存储 identity。适用测试若未登记则明确覆盖缺口，由集成人决定后续受控入口，不能伪装通过。已有 Funds MySQL 手动入口可用于其固定登记清单；不代替完整 Backend。

## 提交前编译的先后冲突与收尾顺序

公共根 `AGENTS.md` 写明“提交前必须：编译通过 + 相关单测通过”。合法受控构建路径不可达，GitHub-hosted CI 又必须先有完整已推送候选，形成先后冲突。用户已明确批准本次流程例外：允许经过审查/秘密扫描的补丁提交、推送专属隔离 CI 候选分支，未通过不能进入正式集成/合并/部署。本线程按该例外形成候选，不把 Python 结果冒充 Java 编译。

收尾按两个可审阅提交：先冻结 verifier/自测为 2cddcd9e，再将 ci.yml 的 verifier ref 固定到该 SHA、提交接线与交付；gate/baseline 字节与 pin 相等，避免自引用 SHA。最终远端 HEAD 回读一致后由协调者单一 dispatch、核对实际 Backend 步骤。在 Backend/所需真库验证完成前，候选仍不具备正式集成/合并/部署资格。

## A/D 新 MySQL 类的最小登记方案

本 checkout/公共候选中没有收到 A/D 本轮新类的确切完整类名、冻结源码 SHA、门控和用例数；不能猜名字、计数或把旧批次当成新覆盖。建议集成人在获得 A/D 清单、合成候选后，仅做下面三处接线（不修改他们的类、不改根 POM）：

1. `ci/real-storage-it-registry.json` 的 `classes` 新增对应 FQCN，每条填实际普通 `@Test` 数、`storage: ["mysql"]`（确需 Redis 才加入）、源码已有 gate 的精确满足值、该类要求的 database_base，以及真实 `@Nested` 名。由现有 `scan_java/precheck` 核对，动态/参数化测试不能硬套声明数；不扩大/下调旧类计数。
2. 同文件 `batches` 增加一个小批次如 `architecture-day-ad`，`registered_source` 为已推送的合成候选完整 SHA，`classes` 仅 A/D 所需新类。`.github/workflows/real-storage-it.yml` 的 dispatch `batch.options` 只追加该批次名称；其他 job、图像身份、清理、20 并行预算均不变。这两个共享文件留集成人单一应用。
3. 用已有 `python3 ci/real_storage_it.py plan/precheck` 检查该源码的路径、类、门控、精确声明数；随后以精确 source_sha 手动执行该批次。执行器已经对每类用 `-Dtest=<FQCN>`（含显式 `$Nested`）点名并清理旧 reports，`verify_class_reports` 检查真实实跑数、唯一 testcase、零 skip/error/failure；`summarize` 校验每份候选/批次回执。缺报告或 gate 未就绪应红，不能靠默认 Surefire 不含 `*IT` 而让 Backend 总绿掩盖漏跑。

这是最小补丁方案，未修改 registry、real-storage workflow 或 A/D 类。待确切清单到齐后才能形成可执行登记补丁；在此之前 A/D 新 MySQL 合同仍标未验证。

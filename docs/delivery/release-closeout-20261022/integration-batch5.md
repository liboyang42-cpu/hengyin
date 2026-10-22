# 发布收尾 batch5 集成候选（INT5）

- 分支：`integrate/closeout-batch5-20261022`
- 日期：2026-10-22
- 性质：只做集成。没有合并或关闭任何成员 PR，没有推 master，没有删分支；不部署、不连生产，也没有执行任何 DB 操作。

## 1. 基线与堆叠

| 项 | SHA | 状态（集成时） |
|---|---|---|
| origin/master | `38dc1bb7` | batch2 #486 合入后 |
| #512 batch3 `integrate/closeout-batch3-20261022` | `e7fe467a` | open（draft） |
| #530 batch4 `integrate/closeout-batch4-20261022` | `6e9e3cee` | open（draft） |

#530 未合入，因此本分支从 batch4 head `6e9e3cee` 切出，PR base 为 `integrate/closeout-batch4-20261022`。
**#530 合入 master 后，需要把 origin/master 并进本分支，再把 PR base 改为 `master`。**

## 2. 成员（`git merge --no-ff`，按 #532 → #531 → #533 顺序）

| # | PR | Lane | 分支 | 当前 head | 合并提交 |
|---|---|---|---|---|---|
| 1 | #532 | O5a | `feature/doc2-o5a-audit-20261022` | `a41cb501` | `3ec03e67` |
| 2 | #531 | O5b | `feature/doc2-o5b-audit-20261022` | `45f80ba8` | `4990f5e1` |
| 3 | #533 | F6 RPG P1 | `feature/doc2-f6-rpg-p1-20261022` | `563f5581` | `cc197b5d` |

三个 head 都与任务给出的预期 head 一致（RV4 #534 评审、BF3 #535 修复之后的版本）。排除：无。

## 3. 冲突

- `ci/migration-dependencies.json`（#530 × #532）：两侧都只追加条目，保留并集（batch4 的 19 条 + O5a 的 `audit_problem_20261022: []`）。`python -m json.tool` 校验通过。
- `AuditTaskServiceImpl.java` 的 `@Value` 字段块（#531 × #532）：O5a（risk-escalation、item-problems）与 O5b（appeal-attachments、report-sla-inherit）各自追加字段，两侧都保留。
- `ci/backend-test.sh` 的 `MYBATIS_ITS`：三个 lane 都没有改这个文件，无需合并。
- #533 携带的 F2（#524 `f82e1c87`）/ F5（#521 `92aa3703`）内容与 batch4 已并入的 head 完全一致：合并无冲突，相对 batch4 的 diff 只剩 F6 自身的 24 个 Java 文件，没有重复的迁移，也没有重复的类。

## 4. 迁移

- 相对 batch4，本批只新增 1 个迁移：`chengyinhub-admin/sql/migration_audit_problem_20261022.sql`（O5a）。
  - 内容为 `CREATE TABLE IF NOT EXISTS audit_problem`，幂等，无回填。
  - 依赖登记为 `[]`（新表，无前置）。
- O5b、F6 不新增迁移（F6 继承的 F2 两个迁移已在 batch4 中）。
- 门禁（本地，合并树）：
  - `ci/migration-layout-check.sh` 通过：513 个 `migration_*.sql` 全在第一层。
  - `ci/migration-safety-check.sh` 通过：依赖声明只增未减；相对 origin/master 新增的 24 个迁移（batch4 的 23 个 + 本批 1 个）都被共享台账覆盖，依赖边顺序正确。
- 落库：仍须**先于新 jar**，先跑 chengyin-db-migrate 只读预检，用户确认后按 `ci/migration-dependencies.json` 的拓扑序执行。`audit_problem` 必须在打开 `chengyin.audit.item-problems.enabled` 之前落库。

## 5. 开关（全部默认关闭，已在合并树上核对）

| Lane | 开关 | 默认 |
|---|---|---|
| O5a #1 | `chengyin.community.post-limit.enabled` | false |
| O5a #2 | `chengyin.audit.risk-escalation.enabled`（阈值 `...report-threshold=3`） | false |
| O5a #4 | `chengyin.audit.item-problems.enabled` | false |
| O5a #5 | sys_config `official_event.publish_check_warn_enabled`（未配置即关闭） | 关 |
| O5b-1 | `chengyin.audit.o5b.light-edit.enabled` | false |
| O5b-2 | `chengyin.audit.o5b.appeal-attachments.enabled` | false |
| O5b-3 | `chengyin.audit.o5b.report-sla-inherit.enabled` | false |
| O5b-5 | `chengyin.audit.o5b.template-title-similarity.enabled` | false |
| O5b-7 | `chengyin.club.o5b.series-close.enabled` | false |
| F6 | 按主题 `journeyRules.rpg.*`（未配置该块 = 全关）；`chengyin.rpg.weather.fixed` 默认空 = 天气 `unknown`；`chengyin.rpg.weather.timeout-ms` 默认 800 | 关 |

## 6. 再生成与 CY-HANDOFF

- 在合并树 `cc197b5d` 上运行 `build-action-ledger.js --write`（4967 个控件）和 `build-route-matrix.js --json`（145 / 27）：**零差异**，不需要提交。ledger `--check` 与 route-matrix `--check` 都 PASS。
- 本批没有改小程序（`chengyinhub-xcx/` 零改动）。前端只有 O5b 的后台 `chengyinhub-ui/src/views/business/audit/index.vue` 及其单测。
- `ui-integrity-baseline.js` 未改。三个 PR 正文里都没有 CY-HANDOFF 行。

## 7. 本地验证

- `mvn -q -DskipTests test-compile`（全 reactor，合并树）：通过。
- Java 测试结果以 GitHub CI 为准，见第 8 节。

## 8. CI

base 不是 master，所以完整 CI 不会因 PR 自动触发。按惯例在本分支用 `workflow_dispatch` 手动触发 `ci.yml` 与 `funds-mysql-it.yml`，run ID 与结果记在 PR 正文中。

## 9. 需要 owner 裁决的事项（来自 RV4 #534 与 BF3 #535）

RV4 结论：三个 PR 都是 0 BLOCKER，SHOULD-FIX 各 3 项，BF3 已全部修复（带回归测试）。**RV4 的 NIT 项都没有处理。**

### #531 O5b
- 轻改阈值没有改，只是数字和否定词的变化强制判为实质改动。下面两项没做，渐进改写的风险仍在：
  - 与「最近一次过审快照」比对；
  - 先审模式下给轻改挂抽检单。
- CLOSED 系列的候补选了「照常收尾」，没有选「关闭时批量结束并通知」。
- O5b-6 AI 安全预检接入发布检查：停在选项（A fail-open 降 WARN / B 全部 fail-closed / C 只在试检查展示），另需确认额度池归属。
- O5b-4 真源设计只读，实施待定。
- N531-1：申诉附件没有校验 `mediaType`（NIT，未处理）。

### #532 O5a
- LIMITED 帖只开放了举报；互动、评论、分享仍返回 404。
- 开关关闭时，detail 和举报在 `selectById` 未命中时会多一次查询。
- 开关关回后，LIMITED 帖可以直达阅读、举报，但回到 feed 需要逐帖 reconcile。批量回滚作业没有做，需要写进 runbook。
- `beta_flag` 生产者（O5a #3）未实现：A 首次发布即进 Beta / B 首次人工过审进 Beta / C 退役 Beta，待拍板。
- 打开 post-limit 开关后，「多重限制时生命周期保持 PUBLISHED」的旧语义会变为 LIMITED，打开前请确认。

### #533 F6
- 没有本局规则上下文的投影（模板库、商家/报名视角、`/api/topic/info-to-user`、遭遇预载）一律不下发 `check.bargain`。
- C-F6-1 ~ C-F6-5 待定：
  - C-F6-1 进度钟段数有两个来源；
  - C-F6-2 ENV_IN 用在路线边条件上会被发布闸 fail-closed 拒绝；
  - C-F6-3 交易/动量没有接入 attempt 路径；
  - C-F6-4 动量规则是简化版；
  - C-F6-5 城市来源用的是 `address_name`，应在 O-14 之后改为 city_code。N533-5 指出的地址外发问题也属于这一项。
- 回滚注意：开了环境键的局，`state_json` 里会有 `environment` 字段，旧 jar 不认识它。

### 沿用 batch4 的上线前事项
#527 F3 的 K1–K8 待裁决（`reservation-enabled` 保持关闭）；RV3 should-fix；#524 F2 先迁移后发 jar；#519 O14 回填前要做只读预览；#518 O15 的 `revenue_share` 口径待定。详见 `integration-batch4.md` 与 `INT4R.md`。

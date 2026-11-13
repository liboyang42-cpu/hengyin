# 发布迁移包刷新（2026-11-13）

分支 `chore/refresh-release-pack-20261113`，基于 master `66b97c58`。只更新发布迁移包清单，不新增/修改任何 SQL。

## 背景
「Release migration rehearsal」run 43333245336 在 `test_pack_matches_head_release_set` 失败（pack 过期）：11-12 新增的九项迁移未进入 pack。

## 做法（只用现有工具）
1. `f-release/gen_release_set.py --head HEAD`：重生成发布集，165 项；`--check` 输出「OK 165 项，产物一致」。九项的类别/阶段在 `NEW_META` 中早已登记。
2. `qx-final6/extend_release_pack.py`：追加九项，原 156 项仅 `step`/`order` 由 `refresh_pack` 重算，其余已审字段未改（已逐字段比对）。
3. 为让工具处理这九项做了两处最小改动：
   - `ci/release_migration_rehearsal.py` 的 `SINGLE_APPLY` 加入 `20261112_template_usage_count`、`20261112_dec_c_payout`（f-release 中重跑规则为「APPLIED 时必须跳过」）。
   - `extend_release_pack.py`：这九项在 f-rehearsal 之后才进入发布集，step 已漂移，无法按 step 查观测；按「NEEDED→APPLIED」登记，由 rehearsal 工作流复核。

## 九项分类
| 迁移 | 类别 | 阶段 | 幂等 |
|---|---|---|---|
| template_usage_count | 必须 | PRE | 单次（APPLIED 跳过） |
| template_market_status | 必须 | PRE | information_schema 守卫 |
| template_author_title_unique | 必须 | PRE | information_schema 守卫 |
| chapter_reward | 必须 | PRE | information_schema 守卫 |
| dec_c_team_codes | 必须 | PRE | information_schema 守卫 |
| dec_c_safety | 必须 | PRE | information_schema 守卫 |
| dec_c_perks | 必须 | PRE | information_schema 守卫 |
| dec_c_payout | 必须 | POST_DEPLOY | 单次（APPLIED 跳过） |
| dec_c_payout_jobs | 必须 | POST_DEPLOY | 静态幂等 |

## 本地验证
- `python3 ci/test_release_migration_rehearsal.py`：13 项 OK
- `unittest ci.test_migration_ledger / test_migration_gates / test_release_manifest`：218 项 OK
- `ci/migration_gates.py dep-order`、`test_migration_runner_ci_contract` 见提交前输出

## 远端
PR：#811（草稿，已按要求含「## 影响分类」）。

| 演练 run | 结果 |
|---|---|
| 43334717277 | failure：第一遍后预检不符合 expectedAfterApply（报告 artifact 下载被拒，看不到具体项） |
| 43335660771 | failure：同上；本次在 `ci/release_migration_rehearsal.py` 里加了失败时打印 unexpected（仅诊断，不放宽断言），得到 `{"20261101_stagger_sys_job_seconds": "NEEDED"}` |

`test_pack_matches_head_release_set` 已通过，pack 失效问题已解决；PR 的常规检查全部为 success（含小程序 20 分片）。

## 剩余失败的根因（需要决策）
`20261112_dec_c_payout_jobs` 把 `ecommercePayoutTask.shareAndRetry()`（cron `0 0/10 * * * ?`）置为 status='0'。而 `20261101_stagger_sys_job_seconds` 的末态断言是「不存在 status='0' 且 cron 为 `0 0/10 * * * ?` 等整点表达式的任务」。因此全集应用后该项预检回到 NEEDED，第二遍重跑时它的断言也会失败。这是两份已审迁移之间的真实冲突，不是 pack 元数据问题。

不在本任务范围内的修法（任务禁止新增/修改 SQL，也不得放宽测试）：
1. 在 `dec_c_payout_jobs` 里把两条任务的 cron 一并错峰（如 `7 3/10 * * * ?`），或新增一条错峰迁移；
2. 或由负责人决定把 stagger 断言收窄。

## 冲突修复（负责人决定：新增错峰迁移）
不改任何已有迁移、不放宽 stagger 断言。

1. 新增 `chengyinhub-admin/sql/migration_20261113_stagger_payout_jobs.sql`：沿用 `20261101_stagger_sys_job_seconds` 约定（只改秒位与分钟偏移、频率不变，`WHERE invoke_target=… AND cron_expression=原值`，可重复执行），并带 `runner: assert=scalar-true` 断言。
   - `ecommercePayoutTask.shareAndRetry()`：`0 0/10 * * * ?` → `9 5/10 * * * ?`
   - `ecommercePayoutTask.syncOnboarding()`：`0 15/30 * * * ?` → `57 18/30 * * * ?`
   - `committedOfficialNoticeTask.deliverPending()`（11-11 迁移写入的 `0 0/1 * * * ?`）→ `27 0/1 * * * ?`
   - 秒位 9/57/27 与 11-01 已占用的秒位不冲突。
2. `ci/migration-dependencies.json`：依赖 `20261112_dec_c_payout_jobs`、`20261101_stagger_sys_job_seconds`、`zzz_committed_official_notice_job_reregister_20261111`。
3. 发布集：`gen_release_set.py` 登记（必须 / POST_DEPLOY，紧随 dec_c_payout_jobs），重生成后 166 项；`extend_release_pack.py` 刷新 pack。
4. 第一次演练（run 43338591328）越过原冲突，但第二遍失败：`dec_c_payout_jobs` 重跑会刷新 `sys_job.update_time`。按既有机制（同 sys_category / settlement_rule）在 pack 为该项声明 `rerunKnownDelta`（仅忽略 `update_time`，其余列与行集合仍逐字节比对）；并让演练失败时打印 drift 明细（仅诊断）。

## 验证
- `ci/test_release_migration_rehearsal.py` 13 项、`ci/test_migration_gates.py` 146 项、`ci/test_migration_ledger.py` 56 项、`ci/test_release_manifest.py` 16 项均 OK；`migration_gates.py sql / beans / dep-order` 通过。
- 演练 run 43338591328：failure（第二遍非零变更，已按上修复）。
- **最终演练 run 43341298076（workflow_dispatch）：success**；同提交的 pull_request 演练 run 43341304270：success；PR 其余检查全部 success。

STATUS: DONE

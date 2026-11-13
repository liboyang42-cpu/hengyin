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

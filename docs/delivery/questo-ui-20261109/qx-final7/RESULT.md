# qx-final7 · 发布迁移演练（rehearsal）转绿 — RESULT（2026-11-11）

分支 `launch/questo-20261110`（#779，仍为草稿）。修复提交 `b547919c`（rebase 到远端 `9f91019d` 之上，远端新增提交不涉及迁移、`ci/` 和发布包）。

**没有做的事**：没有合进 master；没有标 #779 ready；没有碰生产；没有改任何已有迁移，也没有新增迁移；没有跳过任何发布项；没有放宽任何断言（判定逻辑、负控、风险探针都没动）；没有改 `ci/architecture/*` 和 `.github/workflows/ci.yml`；没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有提交密钥；没有 force push。

## 一、复现

沙箱里用 docker 起与 workflow 相同 digest 的 `mysql:8.0.45`（`--default-time-zone=+08:00`、`mysql_native_password`，回环 33081），`TZ=Asia/Shanghai`，PyMySQL 1.1.1，原样执行 workflow 的命令：

```
python3 -B ci/release_migration_rehearsal.py --schema cy_e2e_release_rehearsal --host 127.0.0.1 --port 33081 --user root --report <tmp>/report.json
```

与 CI（head `844638cf`）完全一致：`[1] 模拟基线：199 个快照后迁移…合成行 16 组` 后 `FAIL: 基线库上预检不符合 pack 的 expectedAtBaseline`。

## 二、根因与修复（逐个失败点，按出现顺序）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 基线预检 8 项判 APPLIED，pack 写 NEEDED：`20261101_prefab_life_template_state_keys`、`20261101_prefab_life_template_copy`、`20261104_invitee_signup_points_off`、`20261105_welcome_points_fifty`、`20261104_topic_total_time_seconds`、`20261102_club_session_gather_point`、`20261104_club_session_gather_point_readback`、`20261111_rule_seed_split_steps` | qx-final6 的 `expectedAtBaseline` 取自 f-rehearsal 实测，而 f-rehearsal 的基线带 `seed_rows.sql`（按迁移文件头造的生产现状行）；CI 演练的合成行里没有这些行，数据类迁移的只读断言在空数据上自然为真。pack 值反映的是生产现状，缺的是演练输入 | `SYNTHETIC_ROWS` 补上这些迁移所需的生产形态行（形状逐条取自 f-rehearsal `seed_rows.sql`）：积分规则 1/6、按分钟误存的主题总时长、缺集合点的俱乐部场次、990059 旧文案与章节、官方模板种子规则原文 |
| 2 | 第一遍在 `20261101_theme_categories` 回读断言为假 | 该迁移依赖生产已有的 `sys_category` type=1 行（1–4、21–23、25），合成库没有 | 同上，补 `sys_category` 8 行；一并补 `settlement_rule` 通用行、35 款商家玩法封面旧地址（`20261103_settlement_rule_three_percent`、`20261030_merchant_game_covers_to_oss` 在 pack 里是 NEEDED→APPLIED，需要有行可改）。**没有**补野生动物主题 / 模板货架行：这两项属于原 88 项，pack 的 APPLIED 是 10-29 无种子演练审过的值，补了会改成 DATA_PREVIEW |
| 3 | 第一遍在 `20261101_stagger_sys_job_seconds` 断言为假（qx-final6 剩余风险第 1 条） | 迁移按 11-01 生产只读预检写死 job_id 102–140；合成库任务编号按迁移顺序自增（124 起），对不上 | 合成行末尾把基线 `sys_job` 改成生产编号形态：102 出发前提醒、105 分润结算、121 D12 采样按迁移文件头点名；其余任务落到同一原 cron 频率组的生产编号（组内对应是合成的，迁移只改秒位不改频率）；文件头之外的任务落到空闲编号。先整体 +100000 再按 `invoke_target` 映射，最后重置 AUTO_INCREMENT。发布集里按 `job_id` 读写的其它迁移只有 g_wp08（只查列定义）和两处注释，不受影响 |
| 4 | 第一遍后 `stagger` 判 APPLIED，pack 写 `expectedAfterApply=[NEEDED]` | 该值取自 f-rehearsal 主演练中断言失败的那一次（编号对不上），不是迁移正确执行后的状态；f-rehearsal 第五节按生产编号单独验证时两遍都成功 | pack 改为 `["APPLIED"]` |
| 5 | 第一遍后 `20261027_template_adoption_receipt` 判 CONFLICT | f-rehearsal D2 已查明：发布集内 `20261107_template_adoption_inherited_config_hash` 会给这张表加 1 列 1 索引，只认建表后形状的预检会误报。D2 只修了 f-release 的 `release-precheck.sql`，pack 的 `release-migration-precheck.sql` 没同步 | pack 预检块加入同一个「13 列 / 6 索引行」第二指纹（与 `gen_release_set.py` 的 `PACK_POST_SHAPES` 逐字相同），其它形状仍判 CONFLICT。生成器改为「pack 块已带该形状就不重复追加」，f-release 产物不变 |
| 6 | 第二遍 `theme_categories`（sys_category）、`settlement_rule_three_percent`（settlement_rule）非零变更 | 两个迁移写绝对值并带 `update_time = NOW()`，重跑只刷新 `update_time`（f-rehearsal 第四节已记录） | pack 为两项声明 `rerunKnownDelta`（只忽略 `update_time`），沿用 10-29 包已有机制；脚本仍逐字节比对其余列与行集合，任何业务值变化照样失败 |

改动文件：`ci/release_migration_rehearsal.py`（只动 `SYNTHETIC_ROWS`）、`release-closeout-20261029/release-migration-pack.json`（3 项元数据）、`release-migration-precheck.sql`（1 个分类块）、`release-migration-pack.md`（由 `render_pack_inventory` 重新生成清单段）、`f-release/gen_release_set.py`（防重复追加，3 行）。

## 三、本机全流程结果（MySQL 8.0.45，同 digest）

```
[1] 模拟基线：199 个快照后迁移（不含发布集），合成行 34 组
[2] 基线预检：156 项全部符合 expectedAtBaseline（NEEDED 145）
[3] 第一遍：156 个迁移全部成功（其中已替代不执行 4 个）
[4] 第一遍后预检：全部符合 expectedAfterApply
[4a] welcome_note 原 SQL 直接重跑：预期 1060 Duplicate column；全库指纹不变
[5] 第二遍：实际重跑 106 个，APPLIED 预检后跳过 46 个，已替代不执行 4 个；零变更（已声明的元数据列除外：theme_categories、o14 回填日志、settlement_rule）
[6] 负控：26/26 被预检判为预期状态
[7] 风险探针：20261013_admin_role_page_grants 预检判 NEEDED，执行在断言处停止，已提交 46 条页面授权
PASS: 156 个发布迁移在 8.0.45 上按序落库、按声明策略重入零变更
```

商家回填、积分配置、品牌相册、测试主题下架四项回读全部符合。

## 四、门禁

| 门禁 | 结果 |
|---|---|
| `ci/test_release_migration_rehearsal.py`（静态 pack 契约，13 项） | OK |
| `python3 -m unittest` 6 个迁移相关套件（rehearsal、ledger、release_manifest、migration_gates、run_migration、runner_ci_contract） | 338 项 OK |
| `gen_release_set.py --head 8dd648bd --check` | OK 156 项，产物一致 |
| `ci/forbidden_hosts_gate.py` | 通过 |
| GitHub `Release migration rehearsal / rehearsal`（#779） | **success**（run 43078323150） |

## 五、CI

`Release migration rehearsal / rehearsal`，run `43078323150`，head `b547919c`：**success**。8 个步骤全部成功，其中「Apply release set, re-run for zero changes, read back precheck」用时约 19 分 40 秒（18:32:20 → 18:52:00 UTC，job 上限 30 分钟）。报告产物 `release-migration-rehearsal-43078323150-a1` 已上传。此前 head `844638cf`、`9f91019d` 等 run 均在基线预检失败。

## 六、说明

同一车道的另一次运行在 `f204610d` 先提交过一版简要 RESULT，把 `b547919c` 当作他人提交来记录，并写明「本地未跑」。`b547919c` 就是本次运行的修复提交（本地 `b220e2e0` rebase 后推送），本机也完成了全流程演练（第三节），所以以本文为准，覆盖那一版。

## 七、剩余风险

1. stagger 的编号映射是合成的：组内哪个任务对哪个生产 id 并无生产依据，只保证「编号 + 原表达式」形态与生产一致。上线时以业主跑只读预检的结果为准。
2. CI 演练的合成行现在与 f-rehearsal `seed_rows.sql` 的第 4 轮前段部分重合（两处维护）。改其中一处时要同步另一处，否则 pack 的 expectedAtBaseline 会和其中一边对不上。
3. pack 新增项生产行数仍为 UNKNOWN（同 qx-final6）。

STATUS: COMPLETE - rehearsal 在 #779（head b547919c，run 43078323150）转绿：基线预检 156 项符合、第一遍 156 项成功、第二遍零变更、负控 26/26、风险探针符合；修法是补生产形态合成行与 sys_job 生产编号、修正 pack 中 stagger 回读值、template_adoption_receipt 第二形状和两项 update_time 重跑元数据；断言未放宽、未跳项、未改迁移

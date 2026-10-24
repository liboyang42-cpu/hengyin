# WP08 #3 schema 前置失败：DAG 定点修补

Parent `ccd55005c75d105f8245b0591e63adcd0dd15280`。工作树 `/workspace/hengyin-g-wp08-schema-dependency`，分支 `codex/architecture-day-g-wp08-schema-dependency`；独立公共候选，未覆盖其他 owner。

实际失败为 run40142606869/job121017302795 的 schema 构建前置，root 已读回 summary：227 base 表/seed 检查通过，WP08 第 3 条 scalar-true=0 后停止；没有进入 app/HTTP/Redis 恢复验证。本任务只读了该已完成 job 文本日志（可见 full schema builder failed），未冒充自己读回原 artifact/XML。

## 唯一生产配置改动

仅在 `ci/migration-dependencies.json` 的 `20261023_g_wp08_delivery_jobs_paused` 项保留原三条依赖并追加：

- `member_exp_earned_20261011`：原 #3 的 14 张 InnoDB 表名单明确含 member_exp_earned；base snapshot 中没有该表，由此 canonical migration 创建。该项已依赖 p3_phase_b_player_world_creator，以满足 growth_mission_progress 来源；本片不改它。
- `20261012_im_media_visibility`：原 #3 明确要求 im_message.visible_at。base 原 im_message 没有 visible_at/client_message_id/request_hash/uk_im_message_client；visibility 原依赖已经包含 `im_send_idempotency_20260913` → `im`，因此同时保证原消息身份两列和唯一索引先安装，不重复造表/补字段。

原三项只创建 im_notification_intent、roam delivery/cursor 表，没有保证这两组 #3 前置。其他 #3 原表如 member_growth/player_badge/reward_drop_log 已在 canonical base；不为此扩改其他 DAG 项。不是依赖日期命名推断顺序，而是依赖实际表/列/索引的生产者。

所有已有 migration 字节与 scalar 条件均未修改；没有 SQL/seed/status/cron/鉴权/cleanup 改动、假表或 baseline 豁免。root 串行合并 DAG 后须重建权威 manifest/plan/checksum，不能沿用旧待回放顺序。

## 配套源码与实际检查

在原 `HistorySchemaContract` 中新增两个纯 Python 测试方法，不改旧断言：

1. `test_wp08_schema_guard_waits_for_original_effect_tables_and_im_identity`：加载完整仓库 canonical inventory 和生产 build_manifest，要求原三项+新增两项显式声明，检查所有提供者及传递的 IM 幂等迁移均先于 WP08。
2. `test_wp08_missing_guard_prerequisite_is_rejected_by_manifest_gate`：分别删掉经验边、visibility 边、两边都删，使用现有 `check_manifest_dependency_order` 拒绝缺依赖的真实 manifest。即使当前路径排序碰巧提前建表，也不能丢失显式依赖合同。

按授权只运行了一批定点纯 Python 检查：排序正控 PASS；首版新负控有一个 FAIL，原假设“单独删经验边就必然排到 WP08 之后”不成立，实际经验迁移位置282 < WP08位置527（新增 visibility 边已把 WP08 延后）。这不是 SQL 验证失败。现已修正负控为上面的生产 manifest 门禁拒绝，不再把间接的路径排序当明确合同；**修后负控未重跑（NOT_RUN）**，没有把首次失败藏成绿。首次输出：`Ran 2 tests in 5.022s; FAILED (failures=1)`。

`git diff --check` 无输出；旧 SQL 无 diff。没有运行 Java/DB/provider/CI，也没有重放完整 schema。最终 DAG/完整 schema/鉴权恢复验证均由 root 统一执行，当前未宣称恢复验收完成。

## 对原聚合断言的证据边界

原 #3 是三项组合：14 表均 InnoDB、3 个 IM 列、唯一索引精确列序；旧 summary 没拆分实值。因此不从 `scalar-true=0` 反推旧库究竟哪项为零，也不把这称为资金算法/迁移 SQL 源码 BUG。原 START TRANSACTION/任务 INSERT 尚未到达。

旧 WP08 只有三个日期前缀的无前置 delivery/intent 父项；生产排序器按“显式依赖→路径字节序”取 ready 项，因缺少这两条边可先于原 IM/经验生产者注册。新负控中“两边都删”精确还原该原项，并要求完整 canonical manifest 的 WP08 实际排在两个缺失提供者之前。没有要求单独删任一边都必须改序。

若 root 同源重放需分项观察，可在原 #3 前只读运行下面一条，不替换/跳过原断言，不改 probe/cleanup：

```sql
SELECT
 (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND engine='InnoDB'
    AND table_name IN ('sys_job','im_notification_intent','im_conversation',
      'im_conversation_member','im_message','roam_reward_delivery',
      'roam_reward_reconcile_cursor','reward_drop_log','roam_badge_delivery',
      'roam_badge_reconcile_cursor','member_growth','member_exp_earned',
      'player_badge','ums_member')) AS required_innodb_tables_expected_14,
 (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='im_message'
    AND column_name IN ('client_message_id','request_hash','visible_at')) AS required_im_columns_expected_3,
 (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
  FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='im_message' AND index_name='uk_im_message_client'
    AND non_unique=0) AS unique_message_columns_expected_conversation_sender_client;
```

本查询源码未执行；旧 run 分项值保持 UNKNOWN。补片只在 root 同源 schema 重放原门禁通过后才能认为该前置失败闭合。

## 获准后的唯一必要定点复验

root 明确允许修后直接负控复验。本次在未改 cfda5cde 的代码/配置前提下，只执行 `test_wp08_missing_guard_prerequisite_is_rejected_by_manifest_gate`，真实输出为 `Ran 1 test in 4.822s; OK`，完整命令/输出见 DIRECT_RECHECK.txt。一个方法包含删经验边、删 visibility 边、两边都删三个 subtest；全过，且旧三依赖项在完整 canonical manifest 中的先后次序负控成立。没有为这次复验改变任何断言或门槛，没有重跑完整已有套件。

这取代此前“修后负控 NOT_RUN”的最新状态；首次 2-method 批次中新假设失败的记录原样保留，不追改为成功。原排序正控此前 PASS，本次没有重复跑。Java/DB/CI/完整 schema/鉴权恢复验证仍未执行。

`wp08-manifest-and-tests.review.patch` 提供相对 CCD5 的完整唯一 DAG 项和两个直接测试方法补丁；新交付提交只补证据/文档，资金/迁移 SQL、其他 DAG 项与 B 的 probe/cleanup 均不动。

# 通知 metadata 引号序列化：受控 v2 源码候选

Parent：`db3eb060e4d589843432521d97f5548eed32c443`。沿原独立工作树继续；没有重置/rebase 或覆盖 root/其他 owner。只修这次已证实的共同根因。

## 原因与代码

已完成诊断 source `aab91b9eb7b2732de09aa5513a5be8d5856c81a2`、run40127844664/job120973091452，MySQL8.0.45 strictmode。原九项只有 `all_3_enforced_exact_check_texts=0`；三个 CHECK 都 ENFORCED=YES，recipient 匹配。state/owner 的 CHECK_CLAUSE 含真实 ASCII 92,39（反斜杠、单引号），旧 normalizer 未去掉元数据的这一层引号转义。摘录在 DIAGNOSTIC_EVIDENCE.txt；只读了已完成日志，不声称原 XML/业务验收通过。

新增 `migration_20261024_g_before_start_notice_metadata_v2.sql`。只在原来四处 CHECK 文本规范化入口增加：

```sql
REPLACE(cc.check_clause,
        CONCAT(CHAR(92 USING utf8mb4),CHAR(39 USING utf8mb4)),
        CHAR(39 USING utf8mb4))
```

只还原精确的反斜杠+单引号序列；其他反斜杠和字符不删。指定文本 charset，使后续 LOWER 保持文本小写语义，不引入 binary 字符串。未转义的单引号原样保留。原 12 列/3 CHECK/enforced/约束与索引冲突/旧数据/ALTER/typed 后检全部保留；不是重新定义业务状态、放宽 owner 或接受任意 CHECK。

静态逐字比对：去掉新说明头并将四处新增 normalizer 还原，v2 与旧 C 完全相同。旧 C SHA256 仍为 `615c8c7883a97ca70e1cc14ff4ac965a895db9ccfea91f6f08e269dbf99e889a`；D 未修改。

## root 串行接线与执行计划

- 新 migration ID：`20261024_g_before_start_notice_metadata_v2`；唯一直接依赖：`20261024_d_committed_official_notice_wx`，**不依赖失败的旧 C**。ci/migration-dependencies.json、registry/ledger 由 root 修改，本候选没有覆盖这些共享文件。
- 对确认旧 C 未执行或仅第一条失败、无成功 receipt 且仍完整 D 原形状的库：批准的执行计划以 v2 **替代**旧 `20261024_c_before_start_notice_typed` 入口；保留旧文件/校验和/失败记录，不把旧失败伪记为成功，不先执行旧 C 再“fallback”。v2 自己仍严格检查完整原形状和旧数据。
- 已 typed 的库：不运行 v2、不重复 ALTER。只读核验原成功 receipt 与实际 typed 列/约束/索引及原业务数据；不明或部分状态停止人工核对，不能无条件 skip/伪记 v2 已执行。v2 自身对已 typed 形状在首条 fail-closed，另有重入负控。
- MySQL DDL 自动提交：若 ALTER 已成功而读回/receipt 失败，停止并保留当前 typed 结构和数据；不得自动重试 ALTER、DROP 新列/CHECK、删原通知或重置 UNKNOWN。无生产 DDL、job 启用或外呼授权。
- 共同 `CommittedOfficialNoticeMySqlFixture` 的 open/attach 都改走新 v2；六套件原调用方式不变。失败 suppressed 及原异常继续保留；未删除诊断围栏。

## 原 case 与新增声明

`CommittedOfficialNoticeMySqlIT` 原 13 个 @Test 方法正文逐字保留；仅追加以下 6 个普通 @Test，合计 **19 个声明**（不是实际执行结果）。其他五个共用 fixture 套件没有改 case/断言。

1. `forwardMetadataMigrationPreservesUnknownOriginalRowAndCreatesTypedShape`：合法 canonical D + 原 UNKNOWN 行迁入 typed，逐字段比较原 hash/owner/时间/来源均不变，14 列/3 enforced CHECK，无外呼/wake。
2. `forwardMetadataMigrationRejectsChangedStateSetBeforeDdl`：允许 OTHER 的 CHECK 被原首条拒绝。
3. `forwardMetadataMigrationRejectsChangedOwnerCheckBeforeDdl`：移除 owner/time 约束的同名 CHECK 被拒绝。
4. `forwardMetadataMigrationRejectsUnsignedOriginalIdBeforeDdl`：原 id 改 unsigned 被拒绝。
5. `forwardMetadataMigrationRejectsUnenforcedOriginalCheckBeforeDdl`：同名但 NOT ENFORCED CHECK 被拒绝。
6. `forwardMetadataMigrationRejectsAlreadyTypedSchemaWithoutSecondAlter`：已 typed 重入在首条拒绝，SHOW CREATE 不变。

四个形状负控还要求异常明确来自新 migration 的原 schema preflight，防止夹具 ALTER 报错冒充拒绝；回读仍 12 列、无 typed 列、无通知/IM/wake、job 保持 paused。没有减少模板/IM/WX/UNKNOWN/并发唯一/rollback 的原场景或断言。

源码/静态范围检查完成，git diff --check 无输出；没有运行 Java/MySQL/Redis/provider/CI。上述新源码验证全部 **NOT_RUN**，六类 OPEN 等 root 最终统一回归裁决；这次诊断失败不算验收。

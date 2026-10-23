# H-G04：空受款平台对账行的最小 schema 候选（未执行）

结论：当前公共 DDL 的 merchant_id NOT NULL 与既有 PLATFORM / DEPOSIT_DEDUCTION helper 的 null 不相容。两者 payee_type=platform/status=1 是不进入会员 payout 的对账行；不是缺失一个可以随便指定的会员。不得填 0、默认会员或放松 strict mode。本批不解除这个正向阻断。

建议由 schema 集成人单独审查：仅允许 `line_type IN ('PLATFORM','DEPOSIT_DEDUCTION') AND payee_type='platform' AND status IN (1,2)` 的行使用 merchant_id=NULL；现金受款行保持正整数受款人。使用 nullable 列配 MySQL 8 CHECK（CASE 分支显式处理 NULL，避免 CHECK 的 UNKNOWN 被视为通过）。不得修改 base_schema 或旧 migration 来伪装已迁移。

预检只读内容：SHOW CREATE TABLE / MySQL 版本 / strict mode / 现有约束与列形状；统计按 line_type、payee_type、status、merchant_id 分类的行；逐行核对所有不满足候选 CHECK 的旧数据、真实会员归属及对应余额流水。发现异常只列清单，禁止清账、补假 ID 或重写原金额。现有 schema 外键、调用者、展示聚合与 payout 查询也需核对。

候选约束核心（仅表达式，不是已批准执行脚本）：

```sql
CASE WHEN merchant_id IS NULL THEN
  CASE WHEN line_type IN ('PLATFORM','DEPOSIT_DEDUCTION')
             AND payee_type='platform' AND status IN (1,2) THEN 1 ELSE 0 END
ELSE
  CASE WHEN line_type IN ('PLATFORM','DEPOSIT_DEDUCTION') THEN 0
       WHEN merchant_id > 0 THEN 1 ELSE 0 END
END = 1
```

在约束预检全部通过后，独立新 migration 才能提出 MODIFY merchant_id nullable + ADD CHECK；完整 SHOW CREATE/原注释、锁表影响、失败恢复、迁移幂等策略由集成人形成可审核脚本并依授权安排。**没有执行生产或测试 DDL。** 还必须用真实 Mapper/helper 验证正额平台头写入/读回和现金缺受款拒绝，以及完整事务任意失败回滚；H 已交的正向 SQL 反例不可改成“预期拒绝”或 skip。

此 schema 修正不得先于或脱离退款/放款安全修复正式放行。若已有合法 null 对账行，无法机械恢复 NOT NULL；应停止回退并保留账本，不能删除这些行或填默认 ID。仅在没有任何 null 行且经对账证明后，才可提出约束/列形状回退；应用代码回退与 schema 回退分别审批。

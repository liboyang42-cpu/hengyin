# B 恢复 writer 精确登记

结论：只批准 `WechatVerifiedPaymentFactMapper.xml#insertRecovery/#completeRecovery` 对 `wx_payment_callback_outbox` 的两个已审写边，不授 namespace/table 通配写权。

- parent：`3d3a63d4120c83eaecd8b2bf70a12b51d5f4988c`；独立分支 `codex/architecture-day-f-payment-recovery-writers`。
- 生产依据：公开 `9b284f0111b4337e388fc18d797b11c62112cd82`，Mapper 原两个语句及 `WechatVerifiedPaymentFactService.enqueueRecovery/completePaymentRecovery`。
- `insertRecovery` 只创建 `biz_type=1/money_affecting=0/state=4` 的原 `VERIFIED:<factId>:<hash>` 恢复意图；原业务锁与持久写仍在原服务事务中。
- `completeRecovery` 在原 MANDATORY 事务中，按原 key/businessId/biz_type/money_affecting/state 将 4→2；原服务在修改前后 current read 验证原事实。这里不批准付款、退款、重开或额外业务表写权。

`owners.statement_write_owners` 冻结 path#id/module/namespace/operation/table/现有 sql_shape 指纹/审阅源SHA。指纹沿用原 statement+include 指纹（包含原 XML tail），没有第二套 SQL parser。可信 gate 与 owners 按原六文件机制冻结；candidate 自改绑定仍 POLICY_CHANGE。精确命中仅消除该语句的 NEW_TABLE_WRITER，原 scope/whole-table/cache 检查和 UNKNOWN/PR 合同审阅保留。

限定 writer 的第三条可确定 outbox 写、原两条身份/正文漂移在比较 base equality 之前检查；不能将前一 feature commit 作为比较 base 洗白。其它 namespace 仍走原 NEW_TABLE_WRITER。删除语句仍要求原 recovery caller 人审；动态/注解SQL与Java方法授权不因此获得完整证明。

实际执行：

- `python -m unittest discover -s ci/architecture -p 'test_*.py'`：273/273 PASS，0 skip，31.556s；保留264原控制，新增9项 source CLI 临时Git/event正负控。
- 首次模块名调用因原 test_locking_select 使用同目录 import 导致导入错误；改用仓库原 discover 入口，没有削减测试或改其导入。
- 对实际9b公开 inventory 做同输入 static_findings 差分：只减少两个上述 NEW_TABLE_WRITER，无新增/其它消失；剩余1项仍保留。见 static-delta.json。这不是可信 CI source gate PASS。
- `git diff --check`、精确公开源语句指纹回读、基线字节保持、相关文件秘密扫描见交付输出。六可信文件SHA256见 trusted-files.json。

未运行 Java/MySQL/Redis/jdeps/CI；无实际新可信事件验收，也不声称系统图无环。G DebtMapper locking flags 由G/root接线，本片未改。无生产/SQL/schema/经济策略修改；回退本片会恢复原两个 source gate 阻断，不能替代原事实/恢复数据处理。root审阅后唯一同步双CI ref及后置控制工具固定ref。

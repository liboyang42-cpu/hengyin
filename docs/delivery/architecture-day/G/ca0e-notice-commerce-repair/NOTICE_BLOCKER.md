# 同一通知前检的精确剩余证据

本增量只继续原六类共用入口，未启动新审查或运行环境。Parent `fff8f312e2f21498001ce0a7ddcf3f3247ad00e3`。

原最深 Caused by 逐字摘录在 `before-start-original-error.txt`：MySQL JSON_EXTRACT 的 Invalid JSON / Invalid value / position 0。错误位于原 C statement #1，尚未执行 C ALTER；它不能指出九项 predicate 中哪项实际不符。不能把这个错误直接解释成缺列或索引冲突。

## D 定义与 C #1 静态对照

|C 原条件|D canonical 源定义|静态结论|
|---|---|---|
|有已选择 schema；存在 InnoDB base table|fixture 已选独占库；D CREATE TABLE / ENGINE=InnoDB|顺序正确；运行时元数据待读|
|共 12 列；无 notice_type/wx_payload_json|D 明确 12 列，未定义两个 typed 列|源定义相符|
|id signed BIGINT NOT NULL auto_increment；recipient/im_intent_id signed BIGINT NOT NULL|D 对应定义完全一致|源定义相符|
|event_key varchar96/hash char64/state varchar16/owner char36 的 ascii_bin；title200/bodytext；state 默认 PENDING；owner 可空|D 四个原身份/状态列显式 ascii_bin；长度/空值/默认与 C 一致|源定义相符|
|三个 datetime(3)，created 非空、attempted/accepted 可空|D 明确对应定义|源定义相符|
|三个 CHECK、enforced YES，表达式规范化严格相等|D 同名 recipient/state/owner 三 CHECK，文本业务表达式相同|必须读取 MySQL 实际 CHECK_CLAUSE；不能把源字符串当运行时规范化结果|
|新 ck_committed_official_notice_typed 不存在|D 没定义该约束|源定义相符|
|新 idx_committed_official_notice_typed_pending 不存在|D 仅 primary/event/im/pending 原索引|源定义相符|

因此没有证据支持重排 D→C、手工造表、修改已有迁移、去掉 CAST 或按猜测接受其他 CHECK。fixture 使用的就是这两个原始 canonical 文件，无中间自定义 notice ALTER。

## root 的一次隔离诊断输入

`notice-preflight-readonly-diagnosis.md` 中的 SQL 代码块：只读、零 DDL/写入，只能在已选的独占 scratch schema 中按原 D 建表后、C 前检前运行。首查询记录版本/schema/sql_mode；第二查询逐字复制原 C #1 的九个条件，各列单独返回 0/1/NULL；再读 SHOW CREATE、12 列实际属性、原始与原 guard 规范化的 CHECK_CLAUSE、原索引。

它不是迁移或替代 assertion，不能登记为 guard 通过。请回传第二查询九列及 CHECK/列元数据，便能指向确切不符项并写必要受控修正；fff8 的失败附加诊断也保留这些元数据。无需用户数据、秘密、付款事实或新的全套回归。

Java/MySQL/CI 仍 NOT_RUN；此静态对照没有证明 guard 的真实执行结果，六类失败保持 OPEN，任务未完成。

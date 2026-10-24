# 通知迁移共同失败的运行证据

来源候选：`aab91b9eb7b2732de09aa5513a5be8d5856c81a2`。仅执行原 `CommittedOfficialNoticeMySqlIT`，不作为最终验收。

[run40127844664](https://github.com/liboyang42-cpu/hengyin/actions/runs/40127844664/job/120973091452) 已完成失败：13 tests / 0 failures / 13 errors / 0 skips，均在原 C 首条前检停止。13 个独占测试库均输出原 CLEAN；不将 CLEAN 当业务通过。

原异常附带实际 MySQL `8.0.45` 元数据。会话开启 `ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`。

| 原前检谓词 | 实际结果 |
|---|---:|
| database_selected | 1 |
| canonical_innodb_table | 1 |
| canonical_12_columns | 1 |
| typed_columns_not_yet_present | 1 |
| all_12_column_shapes | 1 |
| exactly_3_checks | 1 |
| all_3_enforced_exact_check_texts | 0 |
| typed_constraint_name_not_reserved | 1 |
| typed_index_name_not_reserved | 1 |

三项 CHECK 均为 `ENFORCED=YES`。recipient 的原规范化文本为 `recipient>0andim_intent_id>0`；state 为 `wx_statein\'pending\',\'unknown\',\'accepted\'`，owner 的三分支等号右侧同样包含 `\'`。原规范化只处理字符集前缀、空白/括号/反引号，没有处理 MySQL 元数据的引号转义；比较目标不含反斜杠。因此该合法 D 结构在 C 第一条被拒绝。

原 G owner 已收到完整元数据并负责受控前向替代、真实 fixture 调用者及负控源码；保留原 migration 全部字节，不放宽允许的状态集合、owner 组合、列形状、旧数据或后置条件。未执行生产 DDL，没有把原失败改成跳过。修补后仍须冻结完整候选统一验收，其他五个共享套件不能继承该诊断的通过结论。

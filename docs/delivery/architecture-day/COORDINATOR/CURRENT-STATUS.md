# 当前施工状态（2026-10-24 15:45 UTC）

**整体未完成；未合并、未部署、未执行生产DDL或微信发布。** 全范围WP00–WP17 / Q1–Q10 / A01–A15 / T01–T22 / G0–G5保留。

| 层级 | 已有证据 | 尚未完成 |
|---|---|---|
| 当前公开源码 | ad222877；四域使用同一原Reference消费守卫；商城通用写入口保护冻结快照；BEFORE_START复用持久意图；Npc voice/ASR与媒体预算已组合 | 取消调用者统一适配、退款原付款Reference派发接线、新迁移预检收口仍在下一冻结 |
| 核心真库 | ad22/run40106969030：14/14 PASS，0failure/error/skip；原XML逐条核对，独立CLEAN | 不是全资金、HTTP/真机或供应商验收 |
| 三组首次真库 | 押金11实跑10PASS+1Mockito装配ERROR；会费/商城8及通用商城6停在beforeAll缺规范sys_config，尚无业务用例执行 | 两类夹具问题源码已修，原断言保留，待新候选复验；三组CLEAN尚未独立读回 |
| 轻量约束 | 272架构/工具控制PASS；70登记/工作流控制PASS；116已登记类源码预检PASS；NPC组件9和关联Node26 PASS | 源码CI和compiled jdeps仍需真实可信GitHub上下文；没有伪造环境跑本地source gate |
| 大职责归位 | ApiRegistrationController原2096→578行；CmsTopicServiceImpl原6301→3689行 | 行数仅描述范围，不替代业务、边界或可恢复性验收 |
| 共享文件保护 | 原A取消+新A known-success预检+C提醒patch按不可变输入重放，产物与当前TeamGroup逐字一致 | C baseline-only比较器因已审A增量而拒绝，保留原失败；未降低断言以冒充整项通过 |

当前分工：A、C、D本片已交付待验；B继续退款SDK与报名caller；G继续押金退款原事实绑定；E适配两个原取消unit；H适配OuterFence；F补真实Spring新必需依赖断言。文件所有权已明确，root唯一集成和验收，继续使用原8个云端会话。

验证安排：先一次核心编译；通过后并行各独立业务类。只复跑发生改动或实际失败的范围，不做多矩阵同根编译试错。新接口必须补完调用者、装配与fixture，不靠兼容裸写或宽泛默认值绕过。

业务未决：语音识别是否识别前统一占用原NPC功能额度仍待答复；shop原gen池不擅自改成npc池。部分退款取整/负组织者利润、G4d启用条件仍未决。代码/CI/迁移/部署/业务验收分层，不合并不同SHA绿色结果。

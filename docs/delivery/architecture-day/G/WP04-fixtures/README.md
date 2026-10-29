# G / WP04 fixture-only delta

结论：仅适配 root 联合 CI `40025693553` 指出的两个旧正向 fixture；生产 guard、资金算法、R1/R7 registry、root IT harness 均未修改。保留原测试名称和全部原断言，增加原证据/claim 明细/重跑零效果检查。本环境未执行 Java/MySQL，不宣称这两例已绿；由 root 在聚合候选同 SHA 跑 CI。

基线：公共仓库 `https://github.com/liboyang42-cpu/hengyin.git`，本 delta 父提交 `65780b036d9e44e3ab8705bb8c244558a09213af`。独立分支 `codex/architecture-day-g-wp04-fixtures`，工作树 `/workspace/hengyin-g-wp04-fixtures`。只 cherry-pick 本 delta，不能以旧工作树/父提交覆盖 root 的联合组装树；root 对 WP04 MySQL IT 的显式 env 与自身 UUID schema AfterAll 清理必须保留。

## 精确范围与失败机制

仅修改两个测试文件：

- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/DoublePaymentReproductionTest.java`：原 merchantOrder/topic/registration 没有完整冻结合同、逐票证据和 claim 服务依赖，新生产 guard 正确拒绝了该空装配。补齐有效 SUPPLY/TOPIC/GUIDED/REGISTRATION_SCAN/v1、JSON 30% 与冻结标量一致、主题开售及平台/保底参数、原已付 ¥100 报名和实际扫码回执、冻结票归属与履约窗；注入真实 lineup/snapshot/evidence/claim/prepare/persist 服务，Mapper 边界保持可控。没有把真实业务计划替换成 mock plan，也没有调用 legacy fallback。
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/SettlementRateConvergenceTest.java`：原例是活动壳 ¥1000 的合法出款后，另一个零主题票销售 topic 200 收口。只为该零销售收口补显式 `CoopSettlementEvidenceMapper`，明确没有 topic 200 的活动已付资金；不为零销售主题虚构合作单或正额。原 activity 100 的 ¥1000 paid basis、来源 topic 50 和零销售 topic 200 分别保留。

没有修改 production、任何 controller/service、旧 CI/migration、原 051c 七文件、WP04 SQL IT，未加新依赖或跳过测试。

## 保留和增强的行为断言

双付回归仍要求：实际 controller 核销成功；主题正向结算成功；同一张原报名 ¥100 按 30% 产生唯一 MERCHANT_VERIFY ¥30；普通 settlement_detail 全部为空；发布者只发原 topic 的 event15。新增断言要求原始 coop detail ¥30、canonical claim 的 fulfillmentId 是 registration 5001（扫码 ID 刻意为 91）、COOP_ORDER 来源为 41；第二次 CAS 未领取，四分项头、唯一 claim/detail、积分不增，会员余额不直接写。

扫码回执来自实际 registration service 的 writeScanLedger 调用。测试在该调用后装入只读票证据夹具，在调用主题结算之前封窗；原扫码时间保持原值，最多等待 100ms 观察墙钟跨到后续毫秒，防止 start/end 排他端点偶然相等。没有调用票务写侧或修改生产时钟，也不以这个单测证明售前冻结写闸/真实数据库时间行为。

费率回归仍要求：活动支付总额恰好 ¥1000；topic 200 原销售合计为零，不读取包括活动壳的展示聚合；topic 200 无余额写入、不发结算积分；event15 对象为来源 topic 50。新增显式禁止 activityId 100 作为幂等对象，活动及零主题分别重复执行均 CAS 未领取，票款总额、积分、原销售/活动资金查询次数不增。

两例均不是“改为断言拒结”；没有把 guard 改可选，没有减少正金额/唯一账本/身份断言。

## 验证、候选与局限

`source-hashes.json` 是两个测试源文件的 SHA-256/本 delta 内容身份；完整提交 SHA 通过 Git/协调消息交付。`validation-output.txt` 仅记录实际静态检查：diff 空白、仓库 secret-file gate、限定两个测试文件、原 @Test 集合和原断言原文保留、无新增 skip/Disabled/Assumptions。

Java compile/unit、MySQL、资金真实写读回均 NOT_RUN；没有 dispatch CI/启动后端/创建数据库/调用付款。依赖现有批准的 root CI。这里的 Mapper 结果是单测夹具，不能当成真实资金表和余额幂等验收。root 之前的 7 SQL 证据及 27 新 unit 的验证范围不自动覆盖这次新 SHA。

迁移影响：无。回退仅撤回本测试 delta；不更改任何资金记录或回执。生产 guard 与 root IT harness 必须保留，不因测试装配问题回退。剩余工作是 root 对同一聚合 SHA 编译及运行这两例/相关回归；H 的金融链路独立审查继续，不以 fixture 修复代替业务审查。

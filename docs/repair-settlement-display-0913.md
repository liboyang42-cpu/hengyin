# F17 俱乐部分润读模型修复

状态：本地修复与验证完成，冻结待 Standards / Spec 独立审查；未提交、未推送、未合并、未部署、未上传微信，未连接生产。

基线：成功 fetch `github/master` 后建立独立 worktree `repair-settlement-display-0913`，分支 `codex/repair-settlement-display-0913`，固定 SHA `7860bfa0f79ad77d02a4e3051465353ec86cafad`。

## 问题与权威口径

完整阅读桌面 `城瘾全产品审查文档/reports/第五轮_资金与业务实跑.md` 的 F17。旧页面将 12.34 原始应结当作实际入账，忽略已执行 -2.34 调整；status=2 作废被映射为待结算。

- `CoopSettlement.status`、`sql/base_schema_20260818.sql`：0 待打款、1 已打款、2 作废。
- `CmsTopicServiceImpl.payoutOneDueCoopSettlement`：实际打款金额为原始金额加 EXECUTED 调整，正数进入会员余额流水；不会修改原始金额。非正净额可以标记已打款而不生成流水。
- `CoopSettlementClaimServiceImpl.appendExecutedAdjustment`：追加 EXECUTED 调整未检查结算状态，也不写余额；没有找到生产调用方，但不能据此假定已付后绝不会追加。
- 既有余额表 `coop_settlement_payout_id` 生成列仅由 event_type=5、Coop结算打款原因、event_id 形成，受 `uk_coop_settlement_payout` 唯一约束保护。

总控明确确认：累计已入账只认这一精确唯一流水，同时限定收款人、有效记录、收入方向；原始应结、已执行调整、当前核算净额另列。作废保留历史金额但不再打款、不进已入账合计。status=1 无精确流水均未核验，包括零元；不凭无流水推导实际为零。任一未核验行使整个入账合计为 null，并返回缺失数量，不展示部分合计。

## 改动范围

仅以下 9 个文件；未扩展资金写链、退款、对账、提现、结算规则或存量数据：

1. `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubSettlementController.java`：summary 只读投影、验证和状态表达；withdraw 未修改。
2. `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CoopSettlementMapper.java`：新增 summary 查询。
3. `chengyinhub-system/src/main/resources/mapper/business/CoopSettlementMapper.xml`：单条 SQL 联结 EXECUTED 调整与唯一打款流水；旧读写 SQL 不变。
4. `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/ClubSettlementSummaryRow.java`：只读金额事实投影。
5. `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ClubSettlementSummaryMybatisIntegrationTest.java`：真实 MySQL 样板与 API 测试。
6. `chengyinhub-xcx/pages/club/settlement/index.js`：严格接收 verified/unverified 与 void；金额只透传，缺字段不补零。
7. `chengyinhub-xcx/pages/club/settlement/index.wxml`：历史金额、作废及待核验表达，复用现有样式。
8. `chengyinhub-xcx/tests/unit/club-settlement-page-contract.test.js`：响应合同负控和回归。
9. 本文。

## RED → GREEN

环境：本机 `127.0.0.1:3306` MySQL **9.7.1**，不是 MySQL 8.0；复用 MysqlIntegrationTestConfig，独立临时 schema，使用仓库 base_schema 的三张真实表 DDL 和原 MyBatis Mapper。测试结束删除各自 schema。未增加依赖。

- `/tmp/settlement-f17-void-red.log`：旧实现 1 项失败，期望 void，实际 pending。
- `/tmp/settlement-f17-void-green.log`：最小状态修复后 1 项通过。
- `/tmp/settlement-f17-amount-red.log`：2 项中金额断言失败，期望实际入账 10.00，实际 12.34。
- `/tmp/settlement-f17-amount-green.log`：联结读模型后通过。
- `/tmp/settlement-f17-regression.log`：最终真实 MySQL 6 项 + 既有 CoopSettlementMapperSqlTest 2 项，全部通过，0 跳过。
- `/tmp/settlement-f17-js-red.log`：新作废/未知响应和不一致负控两项失败。
- `/tmp/settlement-f17-js-green.log`：JS 合同 6 项全部通过，0 跳过。
- `/tmp/settlement-f17-ui-gate.log`：UI-GATE-0 U1–U5、Legacy 全部 PASS，error/warn 0；`git diff --check` 通过。

真实 MySQL 覆盖：12.34 - 2.34 实付10；再追加 -1 后净额9而实际入账与合计仍10；未付7+1=8，PENDING99不计；作废9.99保留历史、不承诺打款；负净额作废不截成零；已付无流水（包括原始0）令整个合计未知；其他收款人、支出、已删除、非Coop事件/原因、NULL流水金额都不能证明入账；唯一打款事件重复插入确实被 MySQL 拒绝；多笔有效入账合计29；其他俱乐部/角色/删除结算不混入；空列表0与未知状态失败。所有 summary 调用均未触发提现服务。

复跑：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX=_f17_regression mvn -q -pl chengyinhub-admin -am -Dtest=ClubSettlementSummaryMybatisIntegrationTest,CoopSettlementMapperSqlTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test chengyinhub-xcx/tests/unit/club-settlement-page-contract.test.js
cd chengyinhub-xcx
node tests/lint/ui-integrity.js --warn-as-error
```

## 模拟器观测与边界

按 wechat-miniprogram-verify 使用既有 automator harness，独立项目与端口9787。截图均已实际打开查看：

- `/tmp/chengyin-f17-ui/before.png`：旧12.34合计及作废误待结算。
- `/tmp/chengyin-f17-ui/after.png`：实际入账10、净额9分开；作废9.99保留并显示“不再打款”。
- `/tmp/chengyin-f17-ui/unknown.png`：合计“金额待核验”、缺失1笔及行“待核验”，不显示部分合计。

后两图通过受控响应进入真实页面 normalizeSummary 后生成，入口门禁仅为截图放行；这是 UI 表达证据，不是后端联网/权限/资金/真机证明。临时 driver 为 `/tmp/f17-after.js`，日志 `/tmp/f17-after.log`，原请求函数已恢复并断开 automator。设计 skill 引用 DESIGN.md/chrome-spec.md 在当前仓库缺失，未自行设计新皮肤，只复用既有样式。

保留问题：已有 withdraw 方法名义金额求和仍存在，但属资金写入链，按任务边界未改；新读模型不代表该写入口可开放。MySQL 8.0、生产生成列/数据当前状态、发布、微信上传、真机均未验证。已有历史 status1 无流水只展示未核验，不回填、不造账、不追加零元特例。

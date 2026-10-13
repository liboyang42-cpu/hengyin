# 报名队伍 F09 / F10 修复记录

基线：`7860bfa0f79ad77d02a4e3051465353ec86cafad`（`github/master`，2026-09-13 fetch）。需求来自桌面《城瘾全产品审查文档/reports/续跑核验_第二轮.md》的 F09、F10。

## 修复

- F09：`hasPaidRegistration` 不再查询不存在的 `cms_registration.del_flag`。权威快照 `sql/base_schema_20260818.sql` 的建表定义没有此列；`CmsRegistrationMapper.xml` 的报名删除是物理 DELETE。保留 member、场次、已支付、报名成功条件；没有补列或修改迁移。
- F10：MySQL 单表 UPDATE 按顺序赋值。`joined_count` 已经加一后，状态判断直接比较当前值与上限，避免再次加一、提前一个名额锁队。保留原子条件 UPDATE 与 Service 事务。
- 原 SQL 字符串单测曾断言错误的二次加一公式。移除该条实现字符串断言，保留原子写入约束，使用真实 MySQL 回归验证满员行为。

## 验证证据

环境：本机 MySQL **9.7.1**，独立临时 schema；真实 `PlayTeamMapper.xml`、真实 `PlayTeamServiceImpl`、Spring 事务与 MyBatis。报名表直接从权威基线 DDL 提取，队伍表运行现有 core 和 invite_code 迁移。活动/主题读取及 IM 等无关依赖使用测试替身。

1. F10 原代码 RED：`threePersonTeamKeepsTheLastPlaceOpen`，第 2 人加入 3 人队后 `expected: <0> but was: <1>`。日志 `/tmp/team-red.log`。
2. F10 最小修复后 1 项 GREEN。日志 `/tmp/team-green.log`。
3. F09 原代码 RED：`creatingATeamUsesTheAuthoritativeRegistrationSchema`，`Unknown column 'r.del_flag' in 'where clause'`；2 项中 1 个 SQL 错误。日志 `/tmp/team-f09-red.log`。
4. F09 修复后 2 项 GREEN。日志 `/tmp/team-f09-green.log`。
5. 最终 26 项通过、0 skip：真实 MySQL 6 项 + Service 单测 15 项 + SQL 结构测试 5 项。日志 `/tmp/team-all-green.log`。

真实 MySQL 覆盖：2/3/4 人队恰好满员、每种容量两人并发争最后名额仅一人成功、成员表与计数一致、退出重新开放、原成员重入、重复加入事务回滚、正常零元已成功报名可建队、他人/未付/取消/退款/其他场次/物理删除订单拒绝且不产生队伍。

复现命令（从仓库根运行；每次给唯一后缀，测试结束自动删除自身 schema）：

```bash
CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX=_f0910_unique \
mvn -q -pl chengyinhub-admin -am \
  -Dtest=PlayTeamMybatisIntegrationTest,PlayTeamServiceImplTest,PlayTeamMapperSqlTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 边界

尚未验证 MySQL 8.0、生产 schema、真实发布报名上游、微信 UI/真机与 IM。无生产接触、真实资金动作、push、PR、merge 或部署。本地测试通过不代表线上已修。

另发现 `CmsRegistrationMapper.hasActiveTopicStoryAccess` 同样引用 `r.del_flag`，已告知总控；不在本项所有权内，未修改。

独立 Standards / Spec 双轴审阅：已完成。总控协调的 `review_standards_wave1` 与 `review_spec_wave1` 各报告 0 项发现；无阻断项。

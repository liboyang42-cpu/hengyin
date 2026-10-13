# S01 附近跑者店铺照片查询 collation 修复

基线：先成功 fetch `github/master`，再从 `7860bfa0f79ad77d02a4e3051465353ec86cafad` 建立独立 worktree / 分支 `codex/repair-nearby-runner-shops-0913`。

## 问题与最小修复

第九轮本地日志 `runtime/logs/sys-error.log:617–625` 记录 `/api/roam/nearby-runners` 在非空跑者查询时失败。`ApiRoamController.nearbyRunners` 先查附近跑者，非空才调用 `RoamMapper.selectRunnerShops` 取这一场探店照片，空跑者列表不会经过故障 SQL。

`roam_poi.cover_img` 的 `utf8mb4_0900_ai_ci` 与 `cms_registration_merchant.pic_url` 的 `utf8mb4_general_ci` 经 CASE 合并后，MySQL 8.0.45 对结果与空字符串的比较报 `Illegal mix of collations (utf8mb4_bin,NONE) and (utf8mb4_0900_ai_ci,COERCIBLE) for operation '<>'`。

运行代码只改 `RoamMapper.xml` 一处：`t.shopImage <> ''` 改为 `CHAR_LENGTH(t.shopImage) > 0`，避免字符串 collation 比较。保留 NULL 条件、两来源字段原值、会员范围、最新有效会话、同时间会话 ID 排序、每人数量和先取名次再过滤空图的顺序。

不加 TRIM：真实 MySQL 上同源 `utf8mb4_0900_ai_ci` 的旧正常查询接受纯空格值，不能趁修技术错误新增图片合法性规则。新测试明确保留纯空格原值，空串和 NULL 仍过滤。没有删除或禁用功能、吞异常、空列表降级或假成功，也未修改全局 collation、业务 DDL、Controller 或鉴权。

## 实证

新增 `NearbyRunnerShopsMybatisIntegrationTest` 使用真实 MyBatis XML，夹具列保留权威基线 DDL 的两种来源 collation；其余仅建查询需要的列。每项使用全新 UUID scratch schema，结束只删除自身库。测试连接固定 UTC，使 macOS 本机和 UTC 容器的日期夹具一致，不修改服务器全局时区。

1. MySQL **9.7.1** 初始非空两来源查询正常，未复现旧异常；客户端和服务端预编译两种模式均没有原报错，不能作为 RED。
2. 总控授权使用现有本地 `cy-product-audit-0912` / `127.0.0.1:3317` 的 **MySQL 8.0.45**，仅新建自身 scratch schema。旧 SQL 5项中3项报上述相同 collation 错误，0 assertion failure、0 skipped；另2项正常语义检查通过。日志 `/tmp/s01-mysql8-red.log`。
3. 一行修复后，在 **8.0.45 和 9.7.1** 分别运行5项真实MySQL测试及既有5项 `RoamPresenceControllerTest`，每个版本 **10项通过，0 failure/error/skipped**。日志 `/tmp/s01-mysql8-green.log`、`/tmp/s01-mysql9-green.log`。8.0原始JUnit报告另存 `/tmp/s01-mysql8-TEST-*.xml`，避免后续9.7报告覆盖版本证据。

覆盖：实际非空附近跑者→两来源照片；NULL/空串/纯空格/不存在源/未知源；仅指定会员、最新有效会话、同上报时间按会话ID取最新、结束会话排除；每人上限及最新空图仍消耗原名次、不擅自补取更旧图；自己/过期上报/已结束/远处/已删除会员仍排除。

复现（两端口均为授权本地测试实例，默认 root 空密码，唯一临时库）：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_TEST_MYSQL_HOST=127.0.0.1 \
CHENGYIN_TEST_MYSQL_PORT=3317 mvn -q -pl chengyinhub-admin -am \
  -Dtest=NearbyRunnerShopsMybatisIntegrationTest,RoamPresenceControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 本机9.7.1复测将端口改为3306。
```

编译及相关测试通过，`git diff --check` 通过。无生产、原审查库数据读写、容器启停、commit、push、PR、merge或部署；冻结交总控双轴审查。此处真实测试覆盖 Mapper 查询和既有 Controller 单测，不代表 HTTP/JWT、开发者工具/真机完整附近人体验或线上修复验收。

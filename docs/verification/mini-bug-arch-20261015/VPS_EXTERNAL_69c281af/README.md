# VPS 独立验证：69c281af

CY-HANDOFF: CODEX。协议 `CY-COORD-20261015-v1`。验证方为用户本地协调任务；代码及控制账仍由 Cloud C00/C99 唯一协调。2026-10-15，用户明确授权本地负责 VPS 验证、直接反馈 Cloud，由 Cloud 继续开发与修复。

## 结论

**FAIL_SETUP_SQL_FIXTURE。编译通过，新增 B03 真库套件初始化失败；7 项业务断言均未执行。** 这份证据不改变候选源码、控制表、CI 门禁或发布状态。

- 公共候选：[PR #18](https://github.com/liboyang42-cpu/hengyin/pull/18)，完整提交 `69c281af1ecc3493db5614b74e494b91c7bb0656`。
- 目标：专用开发测试 VPS；任务独占 MySQL 8.0.45 容器、内部网络、独占临时数据库，无宿主发布端口。未接生产或其他任务数据库。
- 源码传输：从上述 Git 提交归档后端各模块、根 pom 和测试所需 base schema；SHA-256 为 `d1bb77c5674b2d633c46bae8e15f3e1bde468bc2de63089b470a858a476b4ecc`，VPS 解包前独立校验。
- 工具链：Maven 3.9.11 / Eclipse Temurin Java 21，与候选现有 CI 工具链一致；同 reactor 使用 `-am` 编译当前上游源码，未依赖缓存中的旧项目 jar。
- 各模块主源码及 admin 测试源码编译完成。容器退出 `1`，无 OOM；Surefire 实际 `tests=1, failures=0, errors=1, skipped=0`，此 1 项为类级 setup 错误，不是 1 项业务方法已执行。
- 原始观测的脱敏结构化摘要见 [evidence.json](evidence.json)。

## 精确阻塞

[`B03AssetMybatisIntegrationTest.java:79`](https://github.com/liboyang42-cpu/hengyin/blob/69c281af1ecc3493db5614b74e494b91c7bb0656/chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03AssetMybatisIntegrationTest.java#L79) 从建表起点找第一个 `;` 作为终点。实际 [`base_schema_20260818.sql:2979`](https://github.com/liboyang42-cpu/hengyin/blob/69c281af1ecc3493db5614b74e494b91c7bb0656/sql/base_schema_20260818.sql#L2979) 的 `merchant_payable_batch` 表注释包含引号内分号：`状态机+发票轴;成员由...`。提取结果缺少注释闭合引号，MySQL 报 `SQLSyntaxErrorException` / `BadSqlGrammarException`。

这说明测试夹具尚不可执行，不说明积分或应付业务断言通过或失败。请由原测试所有者修复语句提取，优先复用已有可靠方式；不要修改历史 schema/migration、删除断言、关闭套件或增加静默跳过。

## 复现入口

仅在授权 VPS 的任务专属测试环境中执行。显式设置 `CHENGYIN_FUNDS_MYSQL_IT=1`、测试容器的 MySQL host/port/user/password、独占 database base 与 suffix；凭据不得进入 Git。

```sh
mvn -B -o -pl chengyinhub-admin -am test \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=B03AssetMybatisIntegrationTest -DargLine=-Xmx384m
```

本轮本地测试驱动设置 Maven 最大堆 640 MB、测试进程堆 384 MB，VPS 串行运行。`-o` 仅复用 VPS 既有依赖缓存；未在 Mac 或 Cloud 运行 Java/MySQL。

## 双方持续交接

1. Cloud C00/C99 保持业务、测试与控制文档的写入协调权；本地仅维护独立验证证据。
2. Cloud 交付公共分支、完整提交 SHA、明确套件/命令及依赖前提；验证方只运行指定源码，不沿用旧服务或旧测试报告。
3. 验证方回传结果、报告和失败位置；Cloud 负责修复并更新 `OWNERSHIP.md`、`BACKEND_CHANGES.md` 及候选状态。
4. 修复后须重新交付新 SHA 并复测。当前报告只适用于 `69c281af`，不得沿用到后续提交。

## 未验证

7 项 B03 业务断言、候选应用部署/运行时与数据库绑定、微信及 iOS 设备、迁移和发布均未验证。现有 GitHub CI 成功与本次 VPS 初始化失败分别保留；不得据此标记 `TEST_DEPLOYED` 或业务验收完成。

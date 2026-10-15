# VPS B03 复测：1e403b13

CY-HANDOFF: CODEX；协议 `CY-COORD-20261015-v1`。本地验证方执行，Cloud C00/C99 继续负责候选和控制账。

后续两组验证也已完成，三组合计 231 项的最新签收见 [SUMMARY.md](SUMMARY.md)。本页及 evidence.json 保留第一组完成时的原始快照。

**PASS：7 项业务方法实际执行，0 失败、0 错误、0 跳过。** 完整候选为 `1e403b135206844a34219753a36e2dee39c7dd59`，属于 [PR #18](https://github.com/liboyang42-cpu/hengyin/pull/18)。仅修复上一轮测试夹具提取 SQL 的错误，原 7 项业务断言不变。[上一轮失败证据](../VPS_EXTERNAL_69c281af/README.md)原样保留。

## 环境与源码身份

- 授权开发测试 VPS，独占 MySQL 8.0.45 容器、内部网络与临时 schema，无宿主发布端口，无生产连接。
- 后端源码与 base schema 从该 Git 提交归档，传输后校验 SHA-256：`1362acc7a4b42b7e3b5972838903d0f66b8da9745ead1292d19791635760b2dd`。测试工作副本无补丁。
- Maven 3.9.11 / Eclipse Temurin Java 21，同 reactor `-am` 编译当前源码。编译及测试合计 58.447 秒，Maven `BUILD SUCCESS`，容器退出 0、无 OOM。
- 资源限制：单个 Maven 容器 1152 MiB / 1.5 CPU；MySQL 容器 384 MiB / 0.5 CPU；Maven 堆 640 MiB、测试进程堆 384 MiB。启动前可用内存至少 1800 MiB、空闲磁盘至少 1500 MiB，否则停止启动。

## 实际执行与独立读回

点名 `B03AssetMybatisIntegrationTest`，从 Surefire XML 逐方法读回 7 项，套件耗时 4.046 秒。每个方法无 error/failure/skipped；不是仅凭退出码认定通过。结构化观测见 [evidence.json](evidence.json)。

| 业务断言 | 实际结果 |
|---|---|
| 已有可重复读快照及 MyBatis 缓存不污染扣分后的奖励流水 | 通过 |
| 并发不同事件：积分守恒、流水连续 | 通过 |
| 并发相同事件：只提交一份奖励 | 通过 |
| 空积分或成员不存在：不产生虚假积分流水 | 通过 |
| 商家应付金额被并发冲减至非正：阻止确认、付款、开票 | 通过 |
| 并发冻结后禁止蓝票开具 | 通过 |
| 未变化的正额批次仍可确认、付款、开票 | 通过 |

数据库读回由测试中的独立 JDBC 查询完成；积分规则值使用原有 stub，商家并发窗口使用原有测试控制，因此本结果是该套真实 MyBatis/MySQL 集成测试通过，不是线上 HTTP 或真实付款验收。

## 复测命令

仅在授权 VPS 的独占测试 MySQL 环境，显式设置测试 host/port/user/password、database base/suffix 及 `CHENGYIN_FUNDS_MYSQL_IT=1` 后执行：

```sh
mvn -B -o -pl chengyinhub-admin -am test \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=B03AssetMybatisIntegrationTest -DargLine=-Xmx384m
```

## 范围边界

原始 Maven 日志、Surefire XML 和精确源码保留在 VPS 本轮任务目录。已直接反馈 Cloud C00/C99，由其维护控制账。本地不编辑 Cloud 业务/测试源码或控制文档。

此报告只签收该 SHA 的 B03 7 项集成测试。其他 VPS 套件、候选应用部署和数据库绑定、微信/iOS 设备、迁移、合并与发布均不由本报告签收。

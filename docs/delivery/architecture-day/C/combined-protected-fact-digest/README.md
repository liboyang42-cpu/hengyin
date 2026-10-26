# 组合保护事实 digest：源码修补

父提交：`f41464113e2ba0916e57888349faa9455068890b`。只改两个既有测试源及本目录；旧运行的具体内层异常仍为 UNKNOWN，不把可证的 mapper 缺口冒称该次实际根因。

## 实际链与边界

`CrossDomainCombinedLoadMySqlIT` 的四类调用者继续使用同一个 private `digest(JdbcTemplate,String,Object...)`：

| 调用者 | 原保护范围（SQL/ORDER/参数逐字保留） |
| --- | --- |
| actorDigest | 两个原会员的 ums_member 全行，原两个 memberId，ORDER BY id |
| economicDigest | oms_order、merchant_subscription、wx_verified_payment_fact 全行，ORDER BY id |
| unknownDigest | 原 photo_check_intent 的完整显式字段集合及其原 nonce 对应 ai_quota_reservation 全行，原 session 102..105，ORDER BY id |
| retainedDigest | 原 id 的 photo_check_intent 和其原 nonce 配额全行；先保留 UNKNOWN/RESERVED 原断言，ORDER BY id |

原唯一组合方法正文、HTTP 240、payload、并发、故障/经济/权限/UNKNOWN/清理断言，以及原五个配置方法正文均无修改。四类查询本身没有迁移或过滤列。新 mapper 只供 digest 使用，原 evidence JSON mapper 不变。

## 可证源码缺口与处理

- Spring 7.0.9 ColumnMapRowMapper:48–58 遍历全部列，:94–95 经 JdbcUtils.getResultSetValue；后者:276–305 使用 ResultSet.getObject，并将 Blob/Clob 脱离结果集成为 byte[]/String。不能假定返回值全是 JSON 的原生标量。
- Boot 4.1.1 BOM 管理 Connector/J 9.7.0（服务端仍是既有 MySQL 8，并非驱动版本）。其 ResultSetImpl:1158–1266 对 BIGINT_UNSIGNED 返回 BigInteger，DECIMAL 返回 BigDecimal，二进制返回 byte[]，DATE/TIME/TIMESTAMP 返回对应 JDBC 时间对象，DATETIME 按配置返回 Timestamp 或 LocalDateTime。此处是固定版本源码规则，不是本次运行类型实测。
- 原 plain ObjectMapper 未注册 JSR310。现有 framework -> spring-boot-jackson2 -> jackson-datatype-jsr310 是现成编译依赖；根 Jackson 2 BOM 覆盖到 2.21.7。common/JsonUtils:35–37 已有 JavaTimeModule 与非时间戳输出先例，但其关闭 FAIL_ON_EMPTY_BEANS 且工具方法返回 null/兜底，不适合保护快照；本次只复用其模块/设置，不调用其吞错工具或更改全局 mapper。
- 新独立 FACT_JSON 注册已有 JavaTimeModule，关闭 WRITE_DATES_AS_TIMESTAMPS，按列名排序；仍保留结果集行序。所有列、NULL、BigDecimal 原精度/scale、long/BigInteger、布尔、字符串及 byte[] 原值均参与原 SHA-256。
- Jackson 2.21.7 DateSerializer:40–48 使用 getTime，Timestamp 的毫秒以下精度不能交给它。digest 局部把 JDBC Timestamp 写为 `["java.sql.Timestamp", epochSecond, nanos]`，JDBC Date/Time 写为固定类型标识和完整 epochMillis。没有区域时区格式化、截断 nanos 或 Time.toLocalTime 的毫秒丢失；JSR310 对象仍由原模块序列化。此规范仅用于本次运行内部前后事实比较，不提供跨驱动/跨时区归一化或历史 hash 兼容承诺。
- queryForList/序列化失败继续抛固定 `CONTROLLED_FACT_OBSERVATION_FAILED`，保留原异常为 cause；不返回空 hash、不跳过行、不把错误当空结果。未新增把 cause/message 写入公开 stdout 的路径；私有 XML 的原异常由 root 现有有限投影处理。

## 配套控制 SOURCE

沿用同一个已纳入 profile 的 CombinedContextConfigurationTest，无新编译输入、无新测试入口/嵌套辅助类，原五方法正文保留。新增两个普通无参 @Test：

1. `protectedFactDigestPreservesJdbcScalarAndTimeFacts`：通过 ReflectionTestUtils 调实际 private digest；有限 JdbcTemplate double 返回 20 字段完整行；逐字段变更、Timestamp/JSR310 1ns、SQL Time/Date 毫秒值、金额精度与 scale、超 int/JS 安全整数、binary/NULL 均必须改变 hash；同一行恢复、列插入顺序不变 hash；增行/行序变更不得被吞。核实原 SQL 与原参数传给 JDBC。
2. `protectedFactDigestRetainsQueryAndSerializationFailures`：实际 JDBC 查询异常链保持同一实例；不受支持对象实际触发 InvalidDefinitionException，固定 wrapper/cause 和原字段 path 保留，不能成功返回 digest。

root 负责共享 CONFIGURATION_METHODS 和原配置 5 -> 7 接线。本片不改其 Python/POM/workflow；不增加最低 class 清单，也不借控制结果代替真实 MySQL/HTTP。两个控制仅有限标量/异常 doubles，不证明真实 JDBC 链已经通过。

## 交付与限制

本轮测试、parse/import、编译、Maven、Java/DB/Redis/Docker/HTTP/CI 均 NOT_RUN。仅做源码读取/编辑、Git 差分检查、哈希和限定秘密模式扫描。原运行具体 SQL/driver/序列化首因与真实用例是否完整通过仍 UNKNOWN，等待 root 同源验收。无生产代码、预算、依赖、schema、权限或 UNKNOWN 政策变化。回退只撤本片两个测试源改动，不撤其他 owner 的既有字节修补。

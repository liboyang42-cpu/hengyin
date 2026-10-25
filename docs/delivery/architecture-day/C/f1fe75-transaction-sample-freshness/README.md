# f1fe75 实际失败：统一事务采样 freshness SOURCE

父提交：`f1fe75d646f8ae57864ee5603c68e70e7fabc854`。分支：`codex/architecture-day-c-trx-sample-freshness`。

实际 run `40237886431` / job `121303347255` 停在原校准 released 的零断言（cache 中仍见连接 35、RUNNING、锁行 1、改行 0），尚未进入 held-provider 窗口。本片不把它认定为业务长事务，也不宣称后续 transport/通知链已通过。

## 已核官方源与失败机制

直接读取官方 tag [mysql-8.0.45/trx0i_s.cc](https://github.com/mysql/mysql-server/blob/mysql-8.0.45/storage/innobase/trx/trx0i_s.cc)：`can_cache_be_updated`（769–787）要求距 `last_read` **严格大于** 100ms；`trx_i_s_cache_end_read`（988–996）每次读取结束都会更新它；`fetch_data_into_cache`（890–904）先 clear 再捕获事务列表，支持用本次新物理见证辨认新 cache 代次。取得的完整源文件 SHA-256：`3037386d04be25ba56e18b973355d9362c046de5bcd3e6876ad4aae2ad5e3fd9`。未将 MySQL 源码或新依赖加入仓库。

旧 held/released 两段 20ms 轮询可能不断续住旧 snapshot。这个解释有源码依据；实际业务是否存在长事务仍必须看同源真实新样本，不能由它推定正常。

## 全部真实调用接线

- `assertTransactionOracleDetectsParentTransaction` 的 held 和 released 都调用同一 `observeParentTransactions(deadline)`，共享**原来从取得 holder 前开始的 1 秒截止**；删除两段 20ms metadata 读轮询，不重置校准预算。结果及清理后再检查原截止。
- 原 case 的 held-provider `observeParentTransactions()` 也进入该统一协议，使用有界 1 秒采样入口，原 `@Timeout(150)` 不变。
- 所有 INNODB_TRX 读取只有这个入口，fixture 内串行。每次读取（包括旧样本/读取失败）结束后重新锚定 quiet 时间；下一读必须在上次读结束及本次见证建立之后，严格超过官方 100ms。时限不足直接 `TRANSACTION_SAMPLE_UNKNOWN`，不读出一个空集冒充零。

## 不只凭延时宣称 fresh

全局缓存还可能被别的读者刷新 last_read，因此仅等待 100ms 不够。本入口复用**原 unpooled admin datasource、同一 UUID**的两个临时连接，无新容器/库/表/账号/业务意图：

1. reader 始终 JDBC/server auto-commit、default database=mysql，保持独立 reader!=原业务 holder、reader!=通知连接。
2. 本次全新物理 connection ID 的 freshness witness 也使用 default database=mysql，只在现成自有 `wp14_probe` 原行上 `SELECT ... FOR UPDATE`，不改变行；它必须与 reader/通知连接不同且不复用以往见证 ID。两个实际采样 caller 均位于 scheduler child 启动之前，不与其原业务 work 争抢这行。
3. 同一次原 INNODB_TRX 查询仍读取 **p.DB=精确 owned schema 的全部事务**，额外读取该精确见证 ID。只有本次见证确实出现在 cache、DB=mysql、RUNNING、有真实锁且 rowsModified=0，才接受同一代的业务行。它在 domain rollback 之后才建立，所以旧 held snapshot 不能靠时间或空行通过。
4. 见证属于明确验证的 test-only admin 观察角色；并未排除校准 ID、通知 ID、readonly/零修改/未知角色等任何 owned-schema 事务。看到连接 35 这类原校准行仍计入且仍失败。缺少/重复/错角色见证或异常 scope 都拒绝，不归一化成成功。
5. 见证始终 rollback/close；清理错误不得接受样本，并保留原首因和 suppressed cleanup。真正的 domain released 要求仍为 0。

成功证据含实际 MySQL version、reader/witness IDs、quiet/read/witness-ready 时长、读次数、全部原事务行和来源函数。时长来自客户端 monotonic clock，不冒称读取到服务器内部 last_read；本次物理见证存在才是 cache 已越过因果边界的证据。失败也写入原 `T19_PARTIAL_OBSERVATIONS` 通道的最后采样阶段、时限和原缓存行；没有 SQL 原文、密码、payload 或新 artifact 通道。

## 配套 SOURCE 与原合同

新增 `CrossDomainTransactionSamplingTest` **10 个纯控制源码**：严格 100ms 边界、20ms 续读、见证因果边界、超期/反向时间 UNKNOWN、空 cache 拒绝、旧见证拒绝、真实见证配零行、原校准/其它事务全保留、见证身份/无写/锁条件、重复/异常 scope 拒绝。纯合成行/时间控制不证明数据库已运行。

原唯一存储 case 不减；held 必须看见原 FOR UPDATE holder、holder!=reader、rollback 后**所有 domain TX=0**、原 rowsModified=0、通知线程 Spring/JDBC/MySQL/连接身份、原资金/UNKNOWN/进展、150 秒及 pool 归零/精确清理均保留。没有修改 F transport、生产、B restore、root CI 或已过其它测试包。

仅读取官方源码、Git diff/文本/后像核对及有限秘密模式扫描。所有 parse/测试/编译/Java/数据库/HTTP 业务调用/容器/故障/CI **NOT_RUN**。若其它 cache reader 持续干扰而无法在原截止内取得可信样本，未来运行仍必须失败/UNKNOWN；不承诺该场景已经通过。

`postimages.sha256` 列全部 3 个 SOURCE 及本说明；manifest 自身由提交/tree 绑定。完整 owned patch 是候选相对上述父提交的 Git diff。

# 真MySQL/Redis待验类与现有入口

此表只读核源码/固定CI清单，未执行测试、未改CI或网络。当前候选：CORE e0e3f517、EXTRA faefbaf8、第三bde356f3；完整SHA和逐类门禁/fixture/环境在[JSON](REAL_STORAGE_ENTRY_MAP.json)。旧PASS不继承。23个EXTRA旧deferred逐一核过实际MySQL setup；另两直接IT合并去重后25类。H2与mock Redis另列，不能算真库。

| 待验类/组 | 真存储与门禁 | 已存在适用入口 |
|---|---|---|
| CORE E04_13、OWN_C04_20MySqlIT、OWN_C04_21MySqlIT、OWN_C05_25MySqlIT、OWN_C06_10、OWN_C06_30 | MySQL；CHENGYIN_FUNDS_MYSQL_IT=1，显式六CHENGYIN_TEST_MYSQL_*，每类独占scratch | 现有三workflow及固定脚本均不点名这些类；缺批准真库执行入口 |
| CORE OWN_C04_19Test | MySQL；CHENGYIN_RUN_SESSION_ORDER_IT=1，MYBATIS_IT变量族 | 无现有固定CI selector |
| CORE OWN_C01_51Test$RealMysqlRepeatableRead | MySQL仅嵌套1；CHENGYIN_RUN_OWN_C01_51_MYSQL=true、host必须127.0.0.1 | 无现有固定CI selector；外层4是unit，不能混算真库 |
| AiQuotaReservationRedisIT 15；AiQuotaRecoveryMySqlRedisIT 7 | 真Redis；后者加真MySQL；各自CHENGYIN_AI_RESERVATION_REDIS_IT/CHENGYIN_AI_RECOVERY_IT=1及独占loopback端口/Redis DB | 现有workflow不配置这些gate/Redis服务/selector，缺批准双存储入口 |
| EXTRA E01_06/09/10/18、E04_06/10/13/14、B03CouponLifecycle、B04ScheduleReadback | 真MySQL，CHENGYIN_FUNDS_MYSQL_IT=1 | 现有固定名单不覆盖；门禁同名不代表已覆盖 |
| EXTRA E02_01/06/10/11/15/16/18/20/22、B05CommunityVisibility | 真MySQL，各自CHENGYIN_E02_xx_MYSQL_IT=1或B05 gate | 无现有固定名单；六MySQL绑定/独占schema同样必需 |
| ClubWaitlistTokenMybatisIntegrationTest、CouponLifecycleMybatisIntegrationTest、RoamShareSnapshotConcurrencyMySqlIT | 真MySQL，CHENGYIN_FUNDS_MYSQL_IT=1 | 现有 funds-mysql-it.yml→ci/funds-mysql-it-gate.sh **固定名单包含**，未由本任务触发/重跑 |
| JourneyFullFlowMySqlIT | 真MySQL，MYBATIS_IT显式绑定 | 现有 ci.yml→ci/backend-test.sh固定MYBATIS_ITS包含；不是VPS212入口 |
| A03EndingSnapshotMySqlIT 3 | 真MySQL8.0，CHENGYIN_ENDING_MYSQL_IT=1＋MYBATIS_IT host=127.0.0.1等 | 现有固定名单不覆盖 |
| 第三 forward20261015.PlayerGrowthConcurrencyMySqlIT 11 | 真MySQL8+，CHENGYIN_FUNDS_MYSQL_IT=1＋六显式CHENGYIN_TEST_MYSQL_*，suffix非空、库名前缀≤47后加UUID16，独占建删库权限 | 现有资金固定名单不覆盖；212network none不能替代，入口缺失 |

六CHENGYIN_TEST_MYSQL_*：HOST/PORT/DATABASE_BASE/DATABASE_SUFFIX/USER/PASSWORD（空密码允许的类仍需显式绑定key）。E04_13要求BASE=e04_scratch，E01_06还需PROCESS/innodb_trx锁等待观察；各类schema长度/预删除/仅删自有库边界见JSON。A03仅MySQL8.0；CORE C01 gate是真字符串true，不是1；AI Redis专用DB和loopback限制按源，不默认连任何地址。

**当前缺口**：本地尚无可核批准的真存储资源入口（测试MySQL/Redis隔离服务/共享预算锁范围/可用scratch建删库身份绑定及清理责任）。212离线network none只能ordinary；现有GitHub-hosted固定selector只能证明已列类的既有路径，不能据此扩名单、手动触发CI或增网例外。未获合适入口的项保持NOT_RUN/BLOCKED_RESOURCE_CHANNEL；源码开发继续。新E02-25 MySqlIT尚属已授权待交源码，不在faef候选，不预先计入已交测试。

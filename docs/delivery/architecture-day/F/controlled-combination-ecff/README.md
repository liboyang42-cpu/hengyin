# 固定受控组合负载 — SOURCE / NOT_RUN

Parent：`ecff37372bfae059faa38e9eb420894cde1bcee9`。
分支：`codex/architecture-day-f-combined-workload`。
本片只新增代表性组合验收能力，没有执行任何控制、解析、编译、Java/DB/HTTP、资源或 CI。
协调者已核的旧 CrossDomain / mixed / backend 结果不是本片的新执行证据，也不需为本片重复运行。

## 完整源码与共享接线

| 文件 | 真实合同 / 复用点 |
| --- | --- |
| `business/it/CrossDomainCombinedLoadMySqlIT.java`（admin test） | 唯一新组合 case，原 150 秒 case 观察上界；复用真实完整 HTTP Spring initializer、原 CrossDomain 三个 fixture/stage；不复制启动器。 |
| `business/it/CombinedCoreHttpWorkload.java` | `run(Runnable heldWitness)`、`readOnlyWindow(String,Runnable)`；真实 POST `/api/club/lead/unlock-chapter` / GET 原 receipt；真实 AppLogin JWT。 |
| `business/it/CombinedControlledProviders.java` | task-owned loopback HTTP；固定 DISCONNECT/BAD_RESPONSE/NORMAL/HOLD_MAP，真实 LLM/map/notifier seam；原客户端仍解释响应。 |
| `business/it/CombinedWorkloadContractTest.java` | 5 个新普通 unit 声明，含真实 loopback 客户端、拒绝误认 NOT_SENT、map 实体槽位、canary、private seed 路径负控。 |
| `ci/capacity_mixed_probe.py` | 只追加 `controlled_combination_summary/projection`，复用原 `distribution`；原 CATEGORIES、曲线、mixed、profile 验证和 NOT_COVERED 不改。 |
| `ci/test_controlled_combination_probe.py` | 8 个新纯控制声明；合成记录/回执仅证协议与负控，不是 Java/业务证据。 |
| `ci/test_controlled_combination_entry.py` | 7 个新入口控制声明；原36/新1、XML漏跑/skip/重复/错类、scoped receipt、scope admission、显式 Redis 端口导出。 |

上表 7 文件为 owned patch。以下 4 文件仅在 `shared.review.patch.b64` 提供作者后像，由 root 串行应用：

1. `GameplayProcessRecoveryChild.java`：只提取原 `seedRows`，原 main 仍用原 PRIVATE writer。
   新 `seedCombination(context,JdbcTemplate,Path)` 固定72项，沿原真实 AppLogin；新身份文件限本 JVM
   temp 目录的唯一 `identities.json`，600权限、同目录 atomic move、严格读回，不写 `/recovery-private`。
   两种调用使用相同原角色/章节/command ID 生产代码，原三种物理恢复 seed 未删改。
2. `ci/test_http_e2e_entry.py`：原 EXPECTED 三类26/8/2、默认36保持；只另认可唯一新类单选。
   原 exact XML/source/fixture/cleanup 门槛先执行，cleanup 完成后才投影新 stdout；保原41分类与负控。
   仅旧 workflow 精确字符串断言随新固定分支接线更新，原正常/拒绝范围均保留。
3. `generic-gameplay-http-e2e.sh`：仅新类 whitelist、manual hosted single-class scope、原端口复制、DB15与既有 B事实审批1；无预算段。
4. `.github/workflows/gameplay-http-e2e.yml`：只加固定 manual choice / one-class matrix / 一次 package / 新纯控制调用。
   原 all-three 默认、Generic26、workflow_call 默认、max-parallel20、权限/runner/版本/清理均不改，无新 job。

C 独占真实 hard-cap 执行与 cleanup 的 budget.review.patch。F 不复制它、不将 host Maven 当已受 cgroup 管控。
root 先应用本片 SOURCE+shared，再按 C budget hunk 串接同 source。新 raw JVM 字段固定
`javaPid=ProcessHandle.current().pid()`，source/tree/run/attempt、实际 JAR摘要和14个所选 class摘要一并保留。
这些是实际测试 JVM 的身份，不是另一个空 witness 进程；最终实际 PID/cgroup/CID/network 仍需 C/root 原资源 owner 核验。

## 固定工作负载、分母与前后断言

- 原 seed 两个 player：真实登录，余额/积分0、原角色/状态检查，前后完整 member 行摘要不变。
  主应用 schema 的原9项 no-cash store 先后为空，经济数据不混入该 baseline。
- 72个新原始章节 command（原 seed 同时保留3个未在本片重跑的 crash 场景）。1/3/6并发各24首次写、24读/原号重放/跨人拒绝。
  每个 requestId/hash/actor/perspective/action/APPLIED/result_json 均查原 SQL；唯一变化是原 chapter CAS，读/重放/跨人请求全部 game facts 零增量。
- 原 AI4 / 原map4 / notification1 同窗口 held 时，144个真实 HTTP 调用与正常 payment callback、错误金额零经济增量、同fact重放单次权益并行。
  付款只调用原受审 controlled verified-fact fixture：原 order8、1990fen/CNY、不可变fact ID/hash；不是微信签名或真实收费渠道验收。
- 原 scheduler-stage 同窗口执行旧leader晚到、持锁拒绝、接管、精确自有PID死亡；原 receipt 仅1effect / 3attempts。
  只覆盖该原 scheduler 合同，不等同导演实例 epoch/全玩法 takeover。
- 原 held map seam仍是其既有受控 HttpURLConnection。新增 MAP_HTTP_HELD 则真正经过 loopback TCP、实际 TencentMapClient和MapGeocode；4实体请求占满，额外请求不入provider。
- 后续断连/坏响应/恢复各一窗口，AI/map/notice三个实际本地请求同时 entered 后，真实核心 HTTP先取得一条已校验响应才释放故障响应屏障。
  前两类 AI与notice保持原UNKNOWN，POST不重发；map保原GET有界重试，按实际HTTP handler计数，不把logical GET当wire次数。
  恢复使用显式新动作，不以网络恢复重发旧UNKNOWN。
- 新4个读窗口各24调用：MAP_HTTP_HELD、DISCONNECT、BAD_RESPONSE、RECOVERED。
  全部通过时共有240个显式逻辑HTTP调用/已验证响应：184成功、56合法跨人拒绝。
  计数不冒称底层传输内部wire重试数；所有UNKNOWN/timeout/transport/contract分类仍分开，任一未知立即使该阶段失败，保原身份而不自动重试。
- 6条原UNKNOWN完整photo/ledger记录逐次摘要不变、保持RESERVED，最后单日原counter8（2received+6unknown），不改时间/nonce/day或清次数。
  跨日不重置counter来迎合断言；此固定单日观测不覆盖日界过期政策。
- 原物理SQL事务 oracle/真实freshness witness直接复用，1秒校准上界不变；不通过扩大采样期限或排除通知连接获得0。
  原route会员范围、state/version、其他actor拒绝、0 action行一并保留。

## 资源与证据边界

这是同 JVM 的完整 HTTP应用、providers、经济 fixture；scheduler 仍使用原受控子 JVM。
同一配置端点/native UUID，**两个 schema、两个业务DataSource**，各maxActive3；原经济pool从12收窄。
`domainPoolMaximumSum=6`只是这两个pool上限之和：scheduler lease、DDL与freshness/observer独立连接不在这个数字中。
实际两schema物理连接数另外从processlist COUNT采样，绝不称同一个原parent SQL pool。

采样来自 JVM heap/processCpuTime、Druid active/idle、原HttpUtils active/queue与native连接计数。
CPU/内存cgroup原文件无证据即UNKNOWN，不从JVM heap/CPU线程数推断 hard caps。
P50/P95/P99与throughput只使用各段实际单调时钟耗时和全部已发送结果；并发配置不等于server实测并发或SLO。
额外caller/client队列、provider队列、timer持有均有限；timer取消会移除；close先释放自有屏障、join、恢复原notifier，再按原owner清理。

共享selector合同：`http_scope=controlled-combination` / 唯一 `CrossDomainCombinedLoadMySqlIT`。
固定新scope复制原已验证 `CHENGYIN_HTTP_E2E_REDIS_PORT`，AI端口相等且database显式15。
没有把6387硬编码为唯一值；原standalone DB0/旧HTTP不改。没有新增FLUSH或删除session；原fixture仅删随机MEMBER的验证过的exact keys。
`B_PAYMENT_FACT_DDL_APPROVED=1`只该F分支显式给予原批准fixture，C仅校验/传递。

成功投影须：新1case XML零failure/error/skip、唯一bounded stdout<=128KiB、原XML摘要未漂移、实际JAR摘要一致、same source/run/attempt、原fixture及独立cleanup全满足。
只输出固定结构和数值/hash/原fixture身份；不上传raw XML/stdout/response/SQL/token/JAR/整OUT。
失败保持原非零；缺/伪回执、未知cleanup、少record、换identity或动态category都不能晋升。
新scope汇总始终PARTIAL；不能借旧两类PASS补新source，也不改变原HTTP36分母。

`wholeSystemCapacityAccepted=false`、`q4q8Complete=false` 始终保留。
本片未证明生产provider性能、真实收费/验签、全部玩法、route-cache eviction+long provider、runtime instance takeover或packaged GamePlay整机容量。
C预算proof/source尚需root同源合成及实际读回；本片不自行更改physicalProfile UNKNOWN。

## 一次交付与未运行清单

- `owned.patch.b64`：7个owned源码；`shared.review.patch.b64`：4个共享源码/必要原控制增量。
- `combined.patch.b64`：前两者顺序合并。root选择apply combined或取owned提交再apply shared，不能重复两种。
- `PATCHES.sha256`：解码patch摘要；`OWNED-POSTIMAGES.sha256`：分支作者源码/说明后像。
- `SHARED-POSTIMAGES.sha256`：4个shared作者后像（尚未写入本工作树共享路径）；`COMPOSED-SOURCE-POSTIMAGES.sha256`：合成后11源码预期后像。

只有Git差分/应用检查、摘要和有限秘密模式检查可在本环境执行。
15个纯控制、5个Java unit、1个新实际组合case、编译/HTTP/DB/故障/CI均 **NOT_RUN**。
旧case/断言/production/SQL/资金规则/owner baseline/trusted refs均未编辑。
root齐C预算后才集中选择新scope；新unit5按原批准Backend入口单独定点运行，不能把source声明当已执行或复跑旧18凑数。

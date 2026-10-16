# 当前恢复入口

本轮实际继续：CORE协作者ID形状修复、EXTRA POI查询NULL兼容修复、第三队成长真库回归夹具时钟修正均已push；不是仅恢复旧EXHAUSTED快照。三个原作者现均交付停写、文件锁保留，实际源码writer0/READY0。后续真实失败只回原作者单修复槽；最多每队6个gpt-6.1-sol，不为并行或50项造任务。

## 当前精确候选与本地验证

| 队伍 | 公共候选 | 本轮差异 | 最新验证边界 |
|---|---|---|---|
| CORE / PR44 | `5752ee6fd302dab9e610f65cb8f3dc5b2b66201d` | adf之上仅旧TopicUpdate31 fixture成功生成ID前提，生产0改 | 修后单类31 NOT_RUN；adf原51=35P/1F/15E历史保留，不全51重跑 |
| EXTRA / PR36 | `1bfa81a5a58ffa8112c10414dad9fdb1701ef188` | E02-12：仅POI options状态谓词与既有status1保存/公开合同对齐、2方法H2真实XML回归及说明 | 本地H2普通单类2实际PASS/F0/E0/S0，公开1ae7b60c已核；不继承旧64 |
| FORWARD / PR72 | `d592334abee1a379963e7b72a58eec5e0f7bd5ad` | F3-GROW-03：仅原PlayerGrowthConcurrencyMySqlIT统一固定逻辑日与seed；11方法和全部断言不变 | 最新test-compile guard0通过、fresh主/匿名时钟类及清理已回报，公开报告待补；实际测试0，首次锁拒绝保留；普通两类/生产仍bde且未改，不重跑旧28；真库11仍NOT_RUN |

当前本地[排队记录](LOCAL_VALIDATION_QUEUE_STATUS.json)：共享java-build.lock由其他prelaunch任务持有；CORE5752单类31定向复测待结果，adf51原失败保留；EXTRA普通H2两项已通过、公开证据已核，F3首次test-compile准入拒绝历史保留、最新仅编译通过且实际测试0。不绕锁、不停止其他任务，本地按现有212串行；不因资源拒绝开启作者修复槽。

三分支独立，未整枝集成、未合并部署，TEST_DEPLOYED=NO。源码已交不等于普通/真库/HTTP验收。精确路径、方法、依赖、完整FQN与仅供本地执行的命令见[C03交付](C03_14_SOURCE_DELIVERY.json)、[POI交付](E02_12_SOURCE_DELIVERY.json)、[验证移交](BACKEND_VALIDATION_VPS_HANDOFF.json)、[第三队交接](THIRD_CLOUD_HANDOFF.json)。EXTRA后续`02b99bea07c82aa0b0b0d045b54a26f93fb041de`仅自有三账，第三`5432481a8f9e957ee2dfcb9b531ca754a0395633`仅自有三账；不当新生产修复。


当前已核读EXTRA[1ae7b60c公开证据](https://github.com/liboyang42-cpu/hengyin/blob/1ae7b60cab62696ec0a9259907cfad265496c554/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_1bfa81a5/README.md)并落[E02_12_ORDINARY_VPS_RESULT](E02_12_ORDINARY_VPS_RESULT.json)：仅H2生产XML回归2实际PASS，NULL生产来源未证，不是真MySQL。第三d592仅本地test-compile guard0回报，fresh主/匿名时钟类及精确清理已核、公开报告待补，实际测试0，真MySQL11仍NOT_RUN。CORE5752源包已备，31定向待实际结果；原adf51=35P/1F/15E/0S及[公开原失败](C03_ADF51_VPS_FAILURE.json)永久保留，修复源码push不关闭失败。

## 合同与兼容

- 协作者：null/<=0元素在create/FULL任何聚合写前沿既有IllegalArgumentException/默认AjaxResult code500整包失败。重复正ID及顺序、整个list缺席/[]清空、旧行缺席闸、WHITELIST和原owner/完整性闸保留；不查成员/不添邀请或权限，不把schema允许坏ID说成DB约束失败。真实草稿可恢复原名单，未声称存量用户坏草稿已查到。
- POI：QUERY_CONTRACT_NULL_COMPATIBILITY，旧E02-12续作、不加bugID；保city/projection/order/limit/snapshot/count。NULL生产来源仍未证实，合成NULL仅兼容负控，不称现场复现或扩大snapshot。
- E01-13：WAITING只排队，不hold/下单；销售窗口迁移明确仅存读，不可直接拦购票或候补。临时下架/最终结束、主题未售/售止/signupDeadline、存量WAITING保留或终止仍缺既定合同；不往共享seriesPolicy加条件误伤OFFERED清理/付款。
- 第三队：最大合法日9999-12-31的SQL date_add候选在只读Mapper，需要真实MySQL观察，NEEDS_REACHABILITY；不在Controller拒合法日期。条件性RR/Badge候选有实际票锁/限购反证，不计确认bug、不扩caller/共享Mapper。

## 明确剩余合同决定（已交root，不混为资源问题）

[E02-23](BLOCKER_E02_23_DECISION.json)：旧publisher仍写legacy，收藏消费者把同数值当新postID；需权威legacy→community映射/provenance与创建、编辑、审核、删除责任。切源、持续桥投影或无可信映射明确拒绝的用户影响已列；不凭客户端可声明requestId字符串放行，不擅自双写/DDL。

[E04-07](BLOCKER_E04_07_DECISION.json)：原producer boolean丢historyId、completion日志缺原券ID，时间窗MAX可能误选合法后发券；需决定未来原receipt持久字段/原子写入（若新增history_id有DDL，未授权）与旧NULL身份审计继续或整笔拒绝。保USED只审计，不猜ASC/DESC/找未用券。root已向用户提出3个具体业务问题，尚无答复；不阻塞独立源码，不擅自选择。

## 保持现有验证入口

用户明确“保持现有入口，不新增”，新增MySQL/Redis入口方案已关闭、不得再催批。现有212/CI配置不改；自动CI自然运行是策略，PR36分支root两次观察actions/runs=0，不能说已有自动真库成绩。只由本地root在新真实源码/未解失败时按现有212验证；Cloud不编译/运行Java、Maven、Node、lint、CI、DB/HTTP/VPS，不补跑旧PASS。未覆盖的真MySQL/Redis继续NOT_RUN/现有入口覆盖缺口；运行HTTP/身份业务、设备、候选集成/合并部署仍未验。详见[入口决定](VALIDATION_ENTRY_DECISION.json)。

## 历史证据与真实计数

旧1e403b13原231、0d原8类157、e0新普通10、EXTRA e72两类56/b456单类5/faef60/29fe64、第三2bab普通24/bde普通28，均只在各自SHA与实际层级签收，原失败/锁拒绝保留，不迁移到表中新候选。E02_25MySqlIT2、第三Growth11及其他真实存储未覆盖项仍NOT_RUN。

CORE178历史切片当前136源交付/38反证/2决策/1去重/1委派；EXTRA111历史当前60源交付/40反证/6阻塞/5他队或委派。不是剩余bug数或通过用例数，初始50不是当前50。真实剩余细项见[余量](LONGRUN_REMAINING.json)、[本轮恢复包](RESUME_KNOWN_BLOCKERS.json)、[唯一源账](LONGRUN_SOURCE_OWNERS.json)。Claude UI/新功能、原生iOS和Flutter边界不变。旧恢复页已原样归档[历史](LONGRUN_CURRENT_HISTORY_PRE_RESUME_20261016.md)；平台停止后按本页公共SHA恢复，不宣称永久运行。

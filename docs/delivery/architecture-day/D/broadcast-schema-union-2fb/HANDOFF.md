# D 官方广播 fresh-schema 兼容候选（仅 SOURCE / root review）

基线：`2fbfd6df4c4480d924eddd0984169a8d86877075`，tree `76ba8268302a5b7ab61f10cd676833666d5b7c1e`。
本提交只运输此目录三份文件；正式 SQL、CI、manifest 和测试路径均未直接改写。
`root.review.patch` 是相对该基线的完整 7 路径补丁，可重建全部后像；`IMAGES.sha256` 列出全部前后像（含两份字节不变的历史 SQL）。

## 根因与最小合同

root 提供的 run40772742954/job123113979781 在 fileIndex143、10/26 SQL 第3行报 MySQL1060。
10/23 已新增 delivery_state(VARCHAR16 NOT NULL DEFAULT UNKNOWN)、message_id；
10/26 再 ADD 同名两列。它的第一条 audience_frozen ALTER 已独立提交，失败后不能按“全未执行”自动重跑。
这是 schema-apply 首因；该次实际业务/JVM 负载没有执行，不能签负载或 OOM 结论。

保留原 10/23 / 10/26 文件和 checksum，不删除、不修改、不将1060视为可忽略。
复用 build_schema 已有 notice_forward_plan 的严格“全新 scratch、明确替代未执行迁移”方式：
官方基础表 → 原10/23完整迁移 → 新10/28兼容增量；只将原10/26明确记为 REPLACED_UNAPPLIED。
原10/26仍在 canonical manifest，不进 baselineIncluded/dataOnly/applied/completed。
此方案不改变 production ledger 的 APPLIED 判定，也不在生产历史中伪记旧迁移成功。

新 SQL 原地将已存在 delivery_state 扩到32，保留 NOT NULL/default UNKNOWN，复用原 message_id；
只补 frozen_title/sub、intent_id、attempts、update_time、audience_frozen、创建原请求字段/唯一键/creation表。
原 delivery_title/sub、failure_code、reached_time 和所有原行不动；不生成历史成功时间，不将 subscribe.reached=1 当成功。
新 SQL 有执行前精确前缀守卫；发现 master-only/部分安装/已组合形状，先以临时表唯一键拒绝，任何 ALTER 前停止。
不执行 DDL，不新增运行时 owner/依赖/表平台或自动投递重试。

## 精确调用者与标准

- gameplay-http-e2e.yml → generic-gameplay-http-e2e.sh →
  generic-gameplay-fresh-schema.py build_plan/execute → canonical build_manifest/dependencies。
- scripts/e2e/build_schema.py main 是另一已有 schema 入口，两者都调用同一个 broadcast_forward_plan。
  对三文件 path/id/hash/唯一存在/完整 pending/order/phase/dependencies/真实字节严格核对；
  所有 plan metadata、原 skip/apply payload、计数都在 MySQL 调用前重新比对。
  新库不存在检查、CREATE 竞争失败不接管、原 loopback/schema/profile、SQL splitter、cleanup 不变。
- 原 10/23 deps=[official_broadcast]、原10/26 deps=[official_broadcast] 不改；
  新 20261028_official_broadcast_protocol_union deps=
  [20261023_d_official_broadcast_delivery,20261026_official_broadcast_delivery]。
  依赖是原 manifest 排序/源 custody，不意味着物理执行被替代的10/26。
- 普通 release_migration_rehearsal / plan_release 不增加替代规则；
  已存在库（无论只10/23、只10/26、部分执行或两者曾手改）仍需 root 独立核实其真实 history/shape，
  本补丁不授权它们使用 fresh 替代或继续部署，也不声称完成生产升级兼容。
- OfficialBroadcastLogMapper.xml 保留两族：
  insertPending/lockDeliveryIntent/transition → delivery_title/sub/failure_code/reached_time；
  snapshot/lockDelivery/deliveryState/deliveryRows → frozen_title/sub/intent_id/attempts/update_time。
  二者共用 message_id/delivery_state；32是运行时 REJECTED_<code>/NOT_SUBMITTED_<reason> 的必要容量。
- OfficialBroadcastServiceImpl.usesDurableDelivery 仍以 clientRequestId 或已持久协议证据选择 owner；
  OfficialBroadcastDeliveryService 仍保留固定原身份/事务/UNKNOWN/成员频控锁，不做异常 fallback。
  OfficialBroadcastMapper.reserve/read/lock/finishCreation 继续读取原永久创建回执。
- 这些运行时代码、5次/60秒、成员锁、权限、金额、四路16MiB/1GiB等预算均无修改。

## 配套 SOURCE

原 OfficialBroadcastDeliveryMySqlIT **10个 case/所有断言完整保留**，仅追加2个：
1. 原历史组合确实报1060，已提交 audience_frozen 的部分前缀被新守卫拒绝，未补 frozen_title。
2. 10/23→union 保留全部旧列/行/时间/订阅UNKNOWN；
   两个真实生产 Mapper 的写/锁/统计、32字符状态、同唯一身份不覆盖、原 creation 回执均可装配。
仍使用原 withLegacySchema 的 UUID schema/真实 DataSource/精确 finally cleanup，不接真实WX。
该已登记类声明 **10→12**；registry/工作流由 root 独占，本提交未改。

generic-gameplay-fresh-schema 原测试保留，原 notice 精确断言只增加排除第二个已明确替代路径、
分别严格核一条 notice 和一条 broadcast REPLACED_UNAPPLIED 记录；新增5个控制源码：
完整字段/原文件 hash；缺失/乱序/依赖/hash；注入旧SQL/删metadata；拒绝已有schema；失败停止。
test_build_schema 仅同步原精确 expected 及两条独立 receipt。
HTTP fresh 工具本来就将数据回填/断言明确记作 skipped；该事实保留，不把 schema 投影当数据迁移证明。
严谨数据/双协议证明由上述 MySQL SOURCE 用例承担，尚未执行。

## 执行与回退

已执行：只读源核对、Git diff、git apply --check、文件 SHA256、补丁新增行限定秘密模式扫描(0匹配)。
NOT_RUN：项目 parse/import、Node/Python/Java 测试或编译、MySQL/Redis/Docker/HTTP/CI/负载/DDL。
root.review.patch：34867 bytes；SHA256 93a5f445e2cfbdcef4bcd8650db901969949f8ad78780e237b9383d6cedc0f52。
原运行绿色不转移给此补丁；当前只有 SOURCE 结果。

源码可撤回本包；一旦 root 批准后在真实库执行任何 DDL，不承诺事务回滚：
停止所有广播写入/worker，保留原回执与真实 schema/history，逐项读回已完成前缀；不能 drop 字段/清历史/盲目重跑。
fresh 失败只允许既有 owner 工具清理自己刚创建且身份匹配的 scratch；没有生产清理授权。

前2轮已装配供体：e4011da72cc484f34dbaaf4e85fe3dbf7c26acfb（双协议合并）、
8d3dfad10daaea97adee5ee51848810f9bf935f7（提交生命周期/反馈），
其运输提交81336893dbbb5b04d3ac96354b31e8df143a6c7a、26c623a43fc36a2cff9ed6f4c382b8d364e439f9。
本基线已包含它们，本包不重套任何旧片。

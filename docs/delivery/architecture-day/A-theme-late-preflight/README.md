# A：prepared 消费后的 local-preflight 类型隔离补丁

结论：**Gateway最小边界补丁已编码；新增13个预期展开unit用例，尚未编译/执行。** 只捕获消费后`generateReserved`向外抛出的`AiThemeDraftPlanRejectedException`，转换为普通`AiServiceException(UPSTREAM_UNAVAILABLE)`、固定消息、无cause。不包住前置检查，不捕获UNKNOWN/RECEIVED或其它RuntimeException，不改变原receipt/消费/补偿顺序和经济政策。

## 基线、范围、旧负控证据

- 公共仓库 `liboyang42-cpu/hengyin`；parent **`40fdffaa345c4c239553f54f36a4ae9147191a1b`**，原40fd工作树及分支保持冻结。
- 新独占工作树 `/workspace/hengyin-a-theme-late-preflight`，分支 `codex/architecture-day-a-theme-late-preflight`；创建时干净。
- 唯一生产文件：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java`，8行增加/2行替换，仅消费后调用边界。
- 唯一新增unit文件：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayPreparedLatePreflightTest.java`。复用原66项中的现有Gateway fixture，未改其fixture或任何旧测试方法，不继承/执行其测试方法；纯planner的日期用spy固定，真实Creator/Gateway/消费/stage/review/补偿编排不替换，provider仍为原明确无网络替身。
- E/root/共用异常类/Creator/原37/原66/其它政策源码全部未改。source-evidence.json给出旧文件精确SHA256及唯一生产delta的字节校验。

**先核对旧40fd的实际路径，再修边界**，输出为`baseline-witness.json`：

1. gen `consumePlannedQuota`成功后，prepared直接`return generateReserved(...)`，缺外层local type兜底。
2. 40fd的INPUT_REVIEW_STARTED和review异常**已被输入catch包装成普通AiServiceException**，不能说它们会原样逃逸，也不能把这两例说成旧红测。新例保留它们作为“不能变成local零效果”的回归，允许原普通异常cause，不靠穿透cause重新认定provider阶段。
3. 可精确指证的裸逃逸位于输入明确被拒后、输入catch之外的`retainQuota(...,"INPUT_REJECTED")`。若该调用抛专属PlanRejected，旧Gateway会向外传出该outer type；Creator的gen本地player reservation仍为null，其对应catch会原样重抛；E可能误认任何quota/review之前的零效果preflight。
4. 静态故障交错：合法原gen plan → 原quota prepare/reserved成功 → 输入审核返回拒绝 → INPUT_REJECTED retain抛local type → 旧Gateway/Creator外层类型逃逸。不是已发生的生产事件；没有执行JUnit。按源码，新增retain负控在旧40fd应暴露该缺口，stage/review两例原来就应保持普通异常；不报告任何“旧12通过/1失败”的实测数字。

## 最小修复合同

`generateThemeDraftPrepared`保留原invalid/config/member/截止/状态/完整性检查和原quota消费区段，仅把随后的`generateReserved`调用放进专属catch：

- 仅精确local PlanRejected → 普通AiServiceException、`AI 草稿原计划执行失败`、不传lateFailure cause；所有原quota/review效果已可能发生，不能再导出local零效果许可证。
- 原preflight不在新try内，仍有原typed code及零quota/review/model效果。
- `AiProviderOutcomeUnknownException` / `AiProviderResultReceivedException`均不属于该final local类，原对象继续传出；不会借宽catch吞掉它们或按原因链改分类。
- 原输入stage/review失败仍走原gen原receipt退款意图；原输入拒绝retain故障仍不自动退款。player输入故障仍只由Creator补它的原player receipt，不消费/退gen。不因为新分类给任何原单新增退款/重发。
- 字面callsite参数、原身份/trace、模型阶段和结果政策不变；不改Creator、共用异常类、E映射或共享transport。

## 新unit预期13例（实际执行0）

| 新例 | 源码断言 |
| --- | --- |
| gen stage / review / inputRejectedRetain（3） | 真实Creator→Gateway→quota后注入local type，返回外层不是PlanRejected；原receipt一次消费、原顺序、stage/review原补偿1次、retain故障无新增退款、模型0次；裸retain故障固定消息、cause=null、不泄露sentinel正文 |
| player stage / review（2） | 外层不扩大为local preflight；只用原player receipt，原player补偿1次，没有gen消费/退款 |
| 真实model UNKNOWN / 返回文本后审核故障RECEIVED（2） | Gateway和Creator传递同一异常对象、模型1次、原gen不退款 |
| 未包裹retain处注入UNKNOWN / RECEIVED（2） | 新边界不宽捕获，同一异常对象透传；这是分类故障注入，不声称quota helper现实中产生provider事实 |
| 真gen invalid/config/member preflight（3） | 原typed code仍在，quota/review/model零调用 |
| 合法首次gen（1） | 真实Creator解析出草稿、原nonce trace、原receipt一次消费/retainSUCCESS、模型1次、无退款 |

原Creator37及40fd新增66测试文件全文保留，本片新增13后，新prepared测试源码预期66→79；所有这些数字都是源码预期，**本会话Java/JUnit实跑0**。没有用fixture初始化、Python检查或mock结果冒充JUnit/真实链路通过。

## 实际验证、组合依赖与交付

实际运行Git/Python：在改生产前核对固定40fd并生成故障指证；改后脚本证明生产delta恰好是所列窄catch、旧Creator/共用异常/原37/原66完整hash一致，检查范围/词法括号/新13例数量；secret-file-gate、有限秘密格式、diff与完整增量patch应用检查。静态检查不证明Java语法/类型、并发、HTTP、DB或真实provider行为。

E的AiThemeDraftPlan类型仍由E提供，本独立基线仍无该类型；root需与E整体编译验证。建议root点名原37、原66以及新类13，并回读同源JUnit；若做旧负控，只把新测试叠到旧40fd+同一E依赖上，不能把预期红的隔离证据合入部署。A不dispatch、不运行VM Java/DB/模型。

完整本次两文件增量为`gateway-and-test.patch.gz`，原字节gzip、mtime=0；解压后可对40fd `git apply --check`，文件SHA见SHA256SUMS；新完整commit可直接fetch。上一批e683→40fd源码保持冻结，本新commit是其唯一续接，不混新批次。

本片无schema/依赖/CI/鉴权/资金政策迁移，不部署；撤回catch会重新开放已指证边界，不能以撤测试宣称闭合。v2耐久claim/旧入口关闭/正文TTL等仍由E/root成组验收，A95后台裸POST P1和原待答金额规则不因本补丁关闭。

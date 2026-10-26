# 原退款 SOURCE 的 journal 身份读取修补

公共 parent：`8b150cba25938a10cef8583b51454b6c3a40c864`。
分支：`codex/architecture-day-a-refund-source-8b15`。
仅静态源码合同；不发布内部运行报告、日志、状态文件、SQL 数据、provider journal 或环境值。所有测试/编译/Java/DB/HTTP/CI/parse/import 控制未运行。

## 精确机制与必要共享依赖

`RestoreRefundReconciliationFixture` 的原 SOURCE 214 行是复合求值：先调用 `external.original(...)`，再执行 `findRetainedRefund`，最后才判断 null。该源位置不能单独证明实际读到了 REFUND SUCCESS。

已读实际调用链：原 `paid` 通过真实 fact writer 写 PAYMENT 并保存消费 Reference；原 `save/review` 创建、审批原退款；原 payout 的 submit double 只向独立 journal 追加同一身份的 SUBMIT/ACCEPTED 和 RESULT/UNKNOWN。该路径没有 gap REFUND SUCCESS writer。真实 `findRefundSuccess` 使用 `Kind.REFUND` 加原 mchid/providerId/refundNo 的 SQL，不把 PAYMENT 选为退款成功。

共享 `RestoreProviderJournal.original` 对首条记录执行 `new LinkedHashMap<>(identity)`，后续 identity 仍为 `JSONObject`，再分别以 `JSONWriter.Feature.MapSortField` 序列化比较。父源码 POM 固定 Fastjson **2.0.57**。该版本实际 writer 源码说明它们并不按同一顺序序列化：

- `JSONObject` 继承 `LinkedHashMap<String,Object>`。
- `ObjectWriterImplMap.write` 486–489 行：启用 `MapSortField` 时，**精确类型** `LinkedHashMap.class` 不排序，其子类 `JSONObject` 则复制到 TreeMap 排序；`SortMapEntriesByKeys` 才强制两者都排序。
- 当前原退款 identity 的插入顺序从 refundId/registrationId 开始，并不是字典序。同一身份的首个 SUBMIT 和后续 RESULT 因而会在 `original` 读回时被误判为身份变化，发生在退款 fact SQL 求值之前。

公开依赖源码，仅读源码、没有运行解析器或复现测试：

- <https://github.com/alibaba/fastjson2/blob/2.0.57/core/src/main/java/com/alibaba/fastjson2/writer/ObjectWriterImplMap.java#L486>，Git blob `13f4ae284d508d6435f45f7c8e8923484a9d873c`。
- <https://github.com/alibaba/fastjson2/blob/2.0.57/core/src/main/java/com/alibaba/fastjson2/JSONObject.java#L33>，Git blob `9fba8a7c12b38a07ccd4272654d2873ffa841a3e`。

**root 必须串行应用 `journal-order.review.patch`。** 它只把 journal 比较的两处 `MapSortField` 改为现依赖已有的 `SortMapEntriesByKeys`；不改 append 的原 payload/hash、链、返回身份、namespace、字段内容或写入路径。真实字段变化仍拒绝。共享原文件在本作者分支未修改，不能只合 owned Java 就宣称依赖已齐；新增正向控制刻意使用真实共享 reader，缺少共享修补仍会失败。

共享文件原 Git blob `d73d845b94b622eddb59a4d423861aa8f41700ea`；原 SHA256 `bf0ed4f760a81b3ca94d9b259712b3f9583658c18499321cede5e58847220677`，仅应用该两处替换的预期 SHA256 `b77d215bf18c3e5733e89ecd55bb6f11d65f4ba739439f11fdd4809a4a73b43e`。这是提案后像摘要，不声称共享文件已在本分支修改。

## Owned 增量

- `RestoreRefundReconciliationFixture` 保留原 null 断言，拆开 journal 验证/原付款与退款绑定/SQL 查证三步；新增 gap SOURCE 确切一次 submit、原 Reference/退款号/20 元与 68 元关系，以及零恢复 query 的断言。没有改成 PROCESSING，没有改金额、UNKNOWN、回放身份或清理。
- provider 从 constructor 局部变量保留为同一实例的 final field，供上述真实 caller 交互断言使用；submit/query Answer 未改。
- 原 complete 分支、backup/after-backup/恢复阶段、真实 SDK writer 观察、原事实重放、ack 与经济行等值断言保持。
- `RestoreRefundObservationSourceTest` 原七条方法和断言保持，新增两条普通 `@Test` 源码：真实 journal 同身份重复读/迟到成功且读取不改文件；原 provider ID 改变仍被真实 reader 拒绝且不修文件。
- 新控制使用 JUnit TempDir 的真实 journal 文件与真实 append/original/state 方法。只绕过宿主环境 constructor，明确注入该测试唯一文件/scope/cohort；没有 mock reader、没有数据库、没有伪造本地成功 fact。constructor/host admission 不属于这两个 unit 的运行证明。

## 交付与限制

本提交只包含两个 owned Java 文件和本目录的静态文档/共享 review.patch/后像摘要；生产、共享 journal 实体、root probe/host/diagnostics/workflow、SQL、依赖及其它 owner 均未修改。

仅 git diff 空白检查、共享 patch apply-check、源摘要与秘密扫描允许执行；源码测试声明为原七加二，**不是九条已通过**。没有重复执行已交验证，也不对宿主 Python 失败作判断。root 合成必要共享 patch 后再统一验收；本静态机制结论不能替代同源物理恢复/清理结果。

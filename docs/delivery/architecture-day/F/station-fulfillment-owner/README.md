# G station fulfillment：精确技术职责登记

本片 parent 为已冻结 F `9637bbcb21b822afec83464f8309bef92ab2bc68`；G 实际源码固定 `92829af2f738b0dbf10c14b05d6cb949032e3ba1`、parent `047dff9bd9f64762ce3b7c2f0839d10e803956f4`。独立分支 `codex/architecture-day-f-station-fulfillment-owner`。不复制 G 生产源码/SQL，不改 root canonical、其他 owner、workflow 或 tool pin。

| 技术职责 | exact type | exact consumer |
| --- | --- | --- |
| G_STATION_FULFILLMENT_STORAGE | mapper.CoopOrderStationFulfillmentMapper | ClaimServiceImpl、TopicSettlementEvidenceService |
| G_STATION_SCOPE | service.support.CoopOrderStationScope | ClaimServiceImpl、TopicSettlementEvidenceService、FrozenCoopOrderSettlementSnapshotReader |
| G_STATION_FULFILLMENT_RECORD | domain.CoopOrderStationFulfillment | 上述 Mapper、Scope、ClaimServiceImpl、TopicSettlementEvidenceService |

所有类型均为 `com.chengyinhub.business` 下精确 FQCN，consumer 无 `$` 前缀通配。新 managed service=0：scope 为纯计算/值叶子，record 为纯实体，不改为 Spring service。八条新增方向：Claim/Evidence 各到 Mapper、Scope、Record；Mapper→Record、Scope→Record。原 FrozenReader 未在本技术 cut 声明 owner，其 source→Scope 被 exact consumer 准许，但其完整职责/传递图仍 UNKNOWN，不顺带新增 FrozenReader owner。Scope 的 service 包引用只准自身和实际 CanonicalDigest 复用；三个新叶子禁止反向 service/SDK/Controller，Scope/Record 不得依赖 Mapper；Mapper 不得旁调其他 Mapper。原 owner 记录、方向、exact contracts 全保留。

固定 G 源码的七个实际 blob SHA256、10 个 XML statement 指纹、FOR UPDATE cache 形状和直接 literal 调用位置见 `source-identities.json`。SQL 唯一 writer 是 `CoopOrderStationFulfillmentMapper.xml#insertConfirmed`，literal target 为 `coop_order_station_fulfillment`；源调用仅 ClaimServiceImpl.recordConfirmedStationFulfillment 第195行。TopicEvidence 为已观察只读 current consumer，FrozenReader 不注入 Mapper，只读 Scope。

**自动化边界**：现有引用规则可限制上述 exact class consumer/反向依赖，不能区分同一个 Mapper 的 read 方法与 insertConfirmed。故“只允许 Claim.recordConfirmedStationFulfillment 写、Evidence 不可写”仍是已批准源码/业务测试合同，不伪称 classgraph 已证明 method-level write authorization、纯读传递性、金额或事务。没有为此新增 parser、框架或通配权限。

原 `statement_write_owners()` 仅接受两条 B 原事实恢复语句，并严格固定 key/table/contract/len=2；把 G 新表记录塞入会被拒绝，不能冒用 B contract。新表首个 literal writer 也无需借 B 的 NEW_TABLE_WRITER 精确例外。因此保留整个 statement_write_owners、business_policy、五 facts、三 private ports、scope roots/legacy/public2ec baseline 原字节语义；没有给 G 新表追加 SQL 豁免或 business 授权。G 唯一 write 及 readonly consumer 的方法级边界不在本受信数据格式的表达能力内，保持明确未证明；若未来要自动约束该语义，需要 root 单独分配受控切口，本片不自行扩大工具。

直接 Python 控制仅一次：`python3 -m unittest -v ci.architecture.test_gate.StationFulfillmentTechnicalOwnerTest`，**4/4 PASS，0 skip，0.363 s**，原始输出见 `python-controls.txt`。覆盖 actual primary identity 的 source CLI 登记、外来 Mapper 红、exact 2/3/4 consumers、candidate 自批冻结数据红、复用原依赖 checker 的合法方向/反向/SDK/FrozenReader/嵌套 consumer/cycle 负控与缺图 UNKNOWN。编译规则用明确标记的合成 rule-only refs，不是 JAR/raw/index 真实性证明；源 CLI 的隔离 Git fixtures 不冒充真实 CI context。旧控制未移除，未跑75/全仓回归。

静态核对只新增三个 technical owner 与八方向，五个非 owners 受信文件逐字等于9637，哈希见 `trusted-files.json`；diff/本片秘密扫描通过。Java/真实 JAR/jdeps/新12MySQL/原11/CI/canonical DDL：NOT_RUN。root 合成 G/A/E/canonical 后统一验证。按两步流程仅交独立 policy/test verifier；root 审阅后再决定双 ref/tool pin，不候选自批业务 policy，不用新片借旧候选绿色。

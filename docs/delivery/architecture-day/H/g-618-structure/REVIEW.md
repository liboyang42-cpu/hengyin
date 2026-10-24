# H：G 61834b1 现金证据结构独立复审

结论：新增结构多数防线与原30项真库证据相符，但发现两项**新增P2**：**payer身份校验不足**，以及**可预见索引名冲突漏过预检并缺半迁移空恢复路径**。各附两例未执行反例/恢复用例，不改G实现。原30项已独立读回确认通过；它们未覆盖这四例。现金算法、共同fence、late SUCCESS及政策待决P1仍未实现/未验收，不能由结构绿项抵消；当前没有新增资金派发入口或生产DDL启用。

## 实际基线、源码与证据

- 唯一公共仓库 `liboyang42-cpu/hengyin`，公共参考 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 本次真正比较：提案 `5e2803424f4954d48efa07a7176176a289476d30` → `61834b1ba96fcfb025d8442955b69f1d6b35d5f3`，两个提交、**精确12新增文件，无其他变更**。当前parent `9a08a66cd35b465ce06c2dbdf76727e28e981102`，tree `1d2a5ec54d30d52b5cfe4fd4edd7411854d6433d`。没有套用旧2ec行号或旧算法树审新结构。
- 直接取得12文件当前内容、旧提案原金额/身份说明、当前G的真实base schema及 `CmsRegistrationServiceImpl`；11个文本blob独立校验，JSON清单结构化读取，不把重序列化字节称作原blob。相关精确指纹见 `source-evidence.json`。
- 实际受控测试source `4b85984c7a86af59240ab5698337041b590813bd`；workflow metadata source `0c0d61266b2ae54d77c5ad9cf8b3c8fdb86d4b16`为只改registry的子提交。H对完整tree核对：**G这12文件及base schema与运行source完全相同**。
- H开始HEAD `be8624c5ee73012f8470333ee71e072d16513ce7`，工作树 `/workspace/hengyin-h-review`，分支 `codex/architecture-day-h-review`，干净。只写本H目录报告/证据/未应用测试patch；未改G/其他owner生产、未新增Agent、未派发CI、未在云VM运行Java/DB/DDL、未调用真实支付。

## 原30项：真实通过，但严格限于该结构

H独立下载run `40043432031` 的artifact `12539952874`，解析原XML和receipt，逐case名与当前G的30个@Test匹配：**30pass、0failure/error/skip、Maven exit0、CLEAN，仅4个system schemas，无残留schema**。

原XML SHA256 `194d957b23d50dec6b156663d1c1c02a1b16e35cf8161f3465934f94884a09e7`；ZIP/receipt/source/attempt/全用例名见 `runtime-xml-evidence.json`。receipt实际source及precheck候选都是4b85984，workflow_sha为0c0d，登记source匹配；不把workflow metadata误当实际编译source。误填source后取消的run40043394585不计验收。

这是执行了真实MySQL前向migration/preflight/完整空回退及存储Mapper的证据，不是H本机执行，也不证明未接入的现金服务/外部支付/HTTP授权。新H四例只通过 `git apply --check` 和轻量字面量/SQL段落检查，**未编译、未执行，预期失败只按源码说明**。采纳后原30→34，须root在批准路径登记执行，不弱化原30。

## H-G618-01 / P2：同scope/同parent不足以证明真实payer

位置：`CoopSettlementCashEvidenceMapper.xml:56–64` 的insertContribution。SQL要求payer同owner/member、原号匹配、payer.parent_order_no与r.parent_order_no NULL-safe相等，但**没有非父单必须自指，也没有父单支付主体的正向事实断言**。DDL中 `(close,payerRegistration,payerOrderNo)` → contribution的复合FK只能证明“同close里这行存在”，不能证明它是本订单的付款主体。

确定性反例一，完全复用本候选实际fixture，不需要伪造VERIFIED支付事实：

1. topic11同member101已有两个**独立**订单：101已付原应付10，102待付原应付90，双方parent_order_no都NULL。真实current源关系/状态由本候选fixture提供，不调用付款。
2. close11保留两条原观察；先合法记录102的待付贡献：payment0/reg1、原应付90、销售贡献0，payer自指102。它满足本候选对全状态原观察的合同。
3. 记录101的10元原贡献时，将payerRegistrationId填102、payerOrderNo填G-REG-102。所有owner/member/原号/状态/金额等校验仍通过；`NULL <=> NULL`也为真；FK在同close确有102。当前recordContribution可接受该错误支付归属。

反例二：沿原正控明确的PARENT-302组，302是100元付款主体，301是0元从票且ID更小。先让301的贡献声明payer自指301；当前JOIN相等、self-FK及载荷回读均可通过，并不能因“ID存在/同组”拒绝。原正控仅诚实传302，不测试错误payer被拒绝。实际 `CmsRegistrationServiceImpl.createMultiTicketRegistrationsInternal:746–772` 明确首单汇总承担整单out_trade_no，从单payable0；不是将最小ID定义成payer。

影响仅为**新增证据层可固化错误原支付身份**；当前没有生产消费者，所以不宣称已发生退款串单/资金损失或可外部越权。此项也不是已有PAYING guard绕过。

最小修复：至少对 `r.parent_order_no IS NULL` 强制payer.id=r.id；同父单分支必须引用真实已持久原付款主体关系/原支付号事实，不能仅靠同parent/member、最小ID或调用方声称。正额组可明确拒绝本反例的零价从票充当100元payer；所有零价/历史缺身份组的主体若无法证明，保留阻塞并向root提供具体缺字段方案，不猜通用“最大金额/最小ID”规则。不能要求伪造支付VERIFIED表来完成本结构测试。

patch前两例验证上述拒绝且保留原行/旧账：`independentOrdersWithNullParentCannotBorrowEachOthersPaymentIdentity`、`zeroPayableSiblingCannotSelfDeclareAsTheParentPaymentIdentity`。当前源码预计assertThrows失败；**不是已取得JUnit红例**。若owner采用独立源关联约束返回不同明确异常，应按实际根因调整精确异常，不用宽泛捕获掩盖夹具失败。

## H-G618-02 / P2：预检漏新增索引名，DDL中断后空回退不可用

位置：新precheck `:12–32`、migration首断言 `:17–38` 只排除新表/新列/`fk_g_cash_%`并核对旧unique，**未排除ALTER将新增的索引/检查约束名**；migration约`:145–195`分别ALTER head和adjustment。empty rollback `:4–10` 直接引用两张表的新列，假设两个ALTER都已成功。

最小可触发路径：在尚未应用结构、旧资金行完全合法的scratch库，先有 `coop_settlement_adjustment` 上 `UNIQUE KEY uk_g_cash_adjustment_event(id)`。它只是与计划新增索引重名，不改旧uk_coop_adjustment_event、旧行或claim约束。当前SELECT-only预检返回无阻塞，内置首断言也通过。

随后三个CREATE及head ALTER分别自动提交；adjustment ALTER因重复索引名失败，其整个ALTER原子失败，故adjustment没有cash_close_id/cash_event_id，而前三表/head扩展已留存。再跑前向迁移会因新表存在而拒绝；跑当前空回退则在首SELECT引用不存在的adjustment.cash_close_id时报错，**在其任何DROP前停止**。旧资金数据没有因此被删除，但可预见错误未在第一条DDL前拦住，且留下一组没有已审恢复路径的半迁移结构。

最小修复两部分：

1. precheck与迁移硬断言同时排除所有计划在旧表新增的index/CHECK名（不是自动删除/覆盖这些原对象），继续保留本次已补的旧unique完整列序校验。
2. 给出可执行前供审核的**分阶段空恢复方案**。至少区分close-only、close+contribution、三表/尚未head、三表+head/尚未adjustment、完整结构；按information_schema验证本阶段确切定义，先只查询本阶段确实存在的列并断言新证据/链接/ACCOUNTING/null会员头均空，再按已存在依赖逆序回退。任意有新记录或形状不符即停，不删原账、不为通过而清表。若选择分阶段手工恢复也可，但必须提供具体可审步骤，不能将完整空回退脚本称作半迁移恢复。

patch第三例要求重名索引在任何DDL前被挡住；第四例在**真实执行前五条生产语句**后故意停在adjustment ALTER之前，模拟DDL中途故障的已提交前缀，并要求有安全空恢复且旧列/索引/原金额完整恢复。当前脚本预计在缺列处失败，**尚未运行**。第四例是所需恢复合同的负例：owner若决定使用专门分阶段恢复入口，可替换测试调用为经审入口，保持空数据/旧账守恒断言；不要求脚本盲清任意未知残缺结构。root仍独占批准环境执行，H未执行任何DDL。

## 核对通过的源码/原测试防线

| 关注项 | 独立结论及边界 |
| --- | --- |
| 同close composite FK | contribution→close(topic)、payer→同close contribution、event→同close registration、head→同close topic/initiator、adjustment→同close event/head均实际定义；原跨close/半链接负例属于已实跑30项。payer“真实付款主体”仍是上项P2，不能由存在性FK替代 |
| SQL NULL | CHECK统一CASE…ELSE0，不把UNKNOWN当PASS；POSITIVE必须真实owner/全费率/S>0，ZERO允许源确实NULL而不能补假值。已排除疑似head topic NULL绕过：**base schema.topic_id本来NOT NULL**。ACCOUNTING/platform/line/status缺值拒绝有真实负例 |
| refund source/status/amount | insertEvent精确binary source_type=registration、source_id、status1、del0、refund_amount；再同close contribution FK。独立502/101错配实际负例通过。仅REFUND_APPROVED是审核事实，**不把status1或event称退款已付/VERIFIED支付** |
| 不可变payload | default record在可写实际事务内先自然键current锁读，cache=false/flush=true；同键比较所有DTO字段（去id/createdAt），JSON按树规范化，不能只对hash。原30含同hash改JSON/amount冲突；无UPDATE/upsert覆盖原证据。opaque JSON不是供应商验签或完整事实认证 |
| 小数/旧unique/claim | BigDecimal+DECIMAL；INSERT SELECT与原金额等值、readback全载荷，不用double资金运算。旧uk_settle_order和claim/entry/key列序保留，claim_id NOT NULL未放宽；不用假claim给平台delta占位。更细小数边界及完整金额算法仍需未来服务合同 |
| 预检/回退 | 当前脏旧会员、旧unique变形、已有残缺表拒绝前向覆盖；完整空结构可回退，有证据/ACCOUNTING行拒绝回退，均在原30实跑。**未覆盖上述索引名与DDL已提交前缀** |
| scratch清理 | UUID专属schema，显式环境/本机host门槛，cleanup核对catalog后只DROP该schema并读回0；artifact仅4system schemas/CLEAN，无全局prune或他人资源清理 |

SQL仍是存储原语，不自动证明关账集合完整、Σ贡献=原S、真实活动父关系、原快照已由完整支付事实认证或共同topic fence已经接入。现有三条insert不能冒充这些服务不变量。30项结构测试也没有写钱包、归一退款delta/债务/回执或执行现金payout。

## 不扩大的缺口与交付裁决

ZERO anchor和全状态快照是新增存储能力，不代表真实settleTopic已绕过正常PAYING guard；本轮不据测试能手写ZERO就报告支付绕过。当前退款event无法承接空ZERO之后才到达的完整SUCCESS事实，G已明确未实现，继续列原latefact/fence/P1，不要求制造假PAYMENT_SUCCESS/VERIFIED记录。

退款逐原订单冲回还是净销售重算、多票固定费及后续资金来源/余额守恒，仍由已提出的业务合同与A/B/G/root真实服务接线决定。当前claim非空意味着无claim现金头调整尚不能表达，这是保留旧约束的阶段范围，不借别人的claim填默认。以上未实现项不能仅凭schema存在宣称已关闭，也不制造新的已发生资金事故。

本轮裁决：**原结构30项真实PASS；两项新增P2/四例NOT_RUN，结构部署准备和原payer身份校验仍需收敛。** 生产入口/DDL未启用。H只交四文件：报告、source证据、原XML提取证据、未应用负例patch；无新依赖/权限/schema/生产实现变更。回退不得删除原资金/回执/事件/审计；有新证据时必须保留结构并走原身份恢复，不能清数据换取空回退。

A95仍保持其独立source/run上的94pass+新增1fail、积分37→1037；后续库存/流水断言未执行。本轮没有重跑或把它与G30加成同候选全通过。

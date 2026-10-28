# E gen 联测、fixture 修复与原动作 mode metadata 增量

结论：源码/迁移候选和测试已写，Java/DB/Redis/CI均未运行。交 root 与 A ef080、Controller/Page/维护/registry 的完整同源验收；不据源码声明通过或启用默认关闭的功能。

## 基线与范围

公共 parent `9c2f96e25b43983a536b3627e840c1538c1f1f7f`，tree `947125275feb66184735b58ced75babc112eafac`；独立目录 `/workspace/hengyin-e-theme-gen-it`，分支 `codex/architecture-day-e-theme-gen-it`。原785/011f/9c与A/root冻结身份不改。

本增量9文件：E RequestService、IntentRow、IntentView、RecoveryItem、专属XML、本轮尚未上线的唯一canonical `migration_ai_theme_draft_request_intent_20261024.sql`、既有unit/IT两类、本文。没有修改A五文件、公共异常、Controller/Page、鉴权、额度/退款政策、transport、CI或任何已部署历史迁移。A ef080仍是编译/语义依赖，不在E tree中复制或伪造接口实现。

## fixture 一行修复

`failedTerminalCommitKeepsOriginalClaimWithoutResend` 的 `when(mapper.complete(any())).thenThrow(...)` 会在stubbing时以null触发已有Answer。本次仅改为 `doThrow(...).when(mapper).complete(any())`，原业务断言全保留。另把输入校验增量注释的“原24 byte-for-byte”改为“原24场景/断言语义保留”，如实反映9c已有标识符重命名。

## 两个 gen 真链负控（新增，未跑）

- `genLatePlanRejectedAfterRealRetainIsFailedWithoutUnsentProofOrReplayEffects`：role=club，E.prepare持久原gen计划；E.execute→实际Creator/Gateway→实际quota prepare/reserved/Redis Lua及INPUT_REVIEW_STARTED；审核double明确拒绝。对实际AiQuotaRecoveryService作spy，窄retain hook先 `callRealMethod()` 并SQL确认RETAINED/INPUT_REJECTED真实提交后，仅一次抛PlanRejected。断言E保存FAILED/provider=null，不冒充零效果NOT_SENT；counter=1、原member/day/pool/nonce/receipt/plan与Redis原receipt不变、无新增退款、model0；原key/原id重放审核/Creator/retain不再执行。
- `genReceivedOutputReviewFailurePersistsReceivedAndRetainsOriginalQuotaOnReplay`：实际quota/模型编排，模型double返回有效草稿后才令输出审核double抛异常，实际Recovery提交RETAINED/OUTPUT_REVIEW_UNKNOWN。断言E保存FAILED/RECEIVED、counter=1、原身份全部不变、model1、无退款；重放不再审核/model/扣额。Recovery spy未stub任何成功返回，所有账本/Lua真实执行。

没有mock整个Gateway或用全拒绝/假成功QuotaRecovery替代账本。会员/角色/审核/model外围仍为既有受控无网络边界，不把这两例称为HTTP/RBAC/provider验收。按旧40fd源码，第一例会暴露late local type裸逃；这是源码预期，没有执行旧负控或伪造红绿数字。主业务代码除后续明确授权mode补齐外不改。

## 原 productType 冷恢复合同补齐

协调者后续明确授权：原mode不能由当前入口或结果文本猜测。IntentRow新增nullable Integer productType，prepare前沿只接受null/1/2，其他值400/AI_DRAFT_INVALID_REQUEST、零Mapper/Creator/Redis效果。不把null默认成1/2，不改idea的300原长/trim判空或原payload/hash；context原hash原本就包含productType。

prepare在原短事务INSERT中保存req.productType。所有后续CAS/purge语句均不更新product_type；execute解码context时额外核对原mode与row一致，异常fail-closed，不切换/修复模式。IntentView末尾追加第10字段Integer productType，RecoveryItem末尾追加第9字段同类型；均只从原row输出。legacy/prepare未确认无row明确null。recovery仍一条metadata查询，不读Redis，不N+1；正文过期/丢失后mode事实仍保留。null只读正文/不可进编辑器由root/F调用者接线，本后端不臆造编辑资格或新金额规则。

唯一未上线canonical CREATE加nullable `product_type INT`，仍用原7 CHECK，在plan CHECK内加入null或1/2；postcheck列数30→31及nullable int结构确认。XML resultMap/INSERT/recovery SELECT加入此列；原state/owner/id索引/claim/TTL不改。不执行DDL、不写生产ALTER、不回填猜测mode，也不改任何其它历史migration。旧IT迁移后验故障夹具同步把故意错列数从30→31更新为31→32，原“postcheck失败不被重跑隐藏”断言保留。

## mode 回归与准确数量

新增unit3条：`prepareRejectsUnknownProductTypesBeforeAnyDependencyAccess`、`originalProductTypeSurvivesAllViewsAndNullIsNeverDefaulted`、`mismatchedStoredProductTypeCannotReinterpretOriginalContext`。覆盖非法值零效果、null/1/2跨阶段、原key换mode产生hash冲突、body丢失metadata仍不变、legacy/未确认null、context/row模式不一致不claim不调用Creator。

新增真库1条：`originalProductTypeRemainsAcrossPrepareExecutionRecoveryAndNullableUnknown`，实际E/A/quota DB/Lua处理null/1/2，每份原mode通过prepare/execute/read/recovery，模型入口看到原值；换mode同key冲突、非法DB mode CHECK拒绝、结果body丢失后仍保留原mode、非法mode不增quota/model。用3个gen合法原意图，不修改dailyLimit或其它政策。

原unit28全部场景与业务断言保留；必要的恢复projection字段白名单由8增9，并用WriteNulls明确检验nullable新字段，无删减旧字段检查。原IT19全部场景保留，仅后验fixture列数适配新schema；新增gen2及mode1后IT=22。legacy原7不改。最终声明 **7+31+22=60**（新mode unit3、gen IT2、mode IT1；unit原28含此前新增idea4）。无Disabled/Assumptions/新env gate。root应把真实registry原19最终明确更新为22，而不是误按中间21计数；逐XML/casename/0fail/error/skip与CLEAN核验。

## 静态检查与未验证

实际仅Git/Python：范围/原生产差异、原28/19场景保留、无skip、Java词法/构造器字段数10/9、row/XML/31列/7 CHECK/INSERT且CAS不更新原mode、有限秘密扫描、diff check与secret-file-gate。它们不是Java编译或业务测试；本机未运行Java/JUnit/MySQL/Redis/EXPLAIN/CI/模型或DDL。固定gitleaks由root对最终组合源复扫；原组合扫描7项及旧候选历史缺口不因本补丁消失，未加豁免/改scanner。

无新依赖/新服务层/新平台。清理沿既有exclusive schema与精确Redis keys，未清全Redis。原body24h与永久identity/原quota计划不变；不同prepareKey仍是独立明确动作，原UNKNOWN不删不迁、不自动重发，不加取消状态。原模式/清理caller/flag/新10字段View及9字段RecoveryItem由root/F继续联动，未经真实验收不得启用。

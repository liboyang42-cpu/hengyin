# A：H-ADE31-01 后台裸 POST — 测试先行候选

结论：**已原样采纳 H 第95例负控，原94项测试/fixture/断言逐字保留；生产问题尚未修，等用户业务选择。** 本候选只有资金测试增加51行和本A交付目录，不是报名P1修复完成，也不是95项已通过。禁止将预期红测和源码推导冒充真实资金损失或真实MySQL执行。

## 基线与独占范围

- 唯一公共仓库 `https://github.com/liboyang42-cpu/hengyin`；开工公开root HEAD `05f4aa69e61752dcafb0bec73ca9ae9b2ff7a7f5`，已fetch；参考公共基线2ec68899099a1e250df0949a8383bf85b5d3ffe0。
- 独立工作树 `/workspace/hengyin-a-unbacked-post`，分支 `codex/architecture-day-a-unbacked-post-test`，创建时干净；未切换或覆盖并行工作树。
- 已读 H `c75ab442b572700df1d0289ba636f6f541168e4a` 同目录 `REVIEW.md`、`RUNTIME.md` 和 `unbacked-post.review.patch`，对本root先 `git apply --check` 再原样采纳。
- 唯一源码文件：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationFundsMybatisIntegrationTest.java`；仅新增 `bareAdminPostCannotCreateUnbackedPointsOrInventoryToRelease`。无生产、Vue、Mapper、接口、schema、CI、依赖或权限修改。
- root05f4的Funds94 blob=`deafd09fb0ecb612b050893a9549502c99445c5f`，与已有66e9的94项文件相同；merchant IT blob=`fe26a11461f3417464701119183125bd7c4f5f8b`，与root已修9b92相同，本候选未触碰该文件/219行。

## 真实 caller 与失败合同

已搜索Java生产、Vue/JS/TS和小程序的 `insertCmsRegistration(`、`addRegistration(`，并搜索后台URI直接引用。主要匹配原文见 `callers.txt`。没有把方法声明计成额外活跃入口。

| 调用链/位置（root05f4） | 事实 |
| --- | --- |
| `chengyinhub-ui/src/views/business/registration/index.vue:69–70,819–822,835–849` | 新增按钮使用原add权限；`handleAdd`打开共用表单；`submitForm`在无id时调用`addRegistration(this.form)`，不是不可达的后门方法 |
| 同Vue `:337–377` 等 | 表单实际可编辑memberId、ticketId、orderNum、pointUsed及金额；编辑/新增共用整实体表单 |
| `chengyinhub-ui/src/api/business/registration.js:21–27` | 唯一已发现该wrapper定义；POST `/business/registration`，原body透传。URI其他匹配是GET/PUT/撤销等，不是其他裸建入口；小程序未找到这个后台POST调用 |
| `CmsRegistrationController.add:218–220` | 唯一已发现Java生产裸新增Service调用者；现有`business:registration:add`权限，直接实体透传；当前候选不改它 |
| `CmsRegistrationServiceImpl.insertCmsRegistration:243–245` → `CmsRegistrationMapper.xml:311起` | wrapper只补createTime；动态INSERT可写原会员/票/数量/积分/状态，无原扣分/预占证明；不是正常建单命令 |
| `CmsRegistrationServiceImpl.createRegistrationInternal:574`、`createSelfPlayPassInternal:894` | 两处内部直接Mapper INSERT，属于原计价、预占、原身份/积分流程；必须保留，不能全局禁Mapper或给它补假扣款 |
| `ApiRegistrationController.cancelRegistration:327起` → `CmsRegistrationServiceImpl.cancelRegistration:2667–2702` | 真实会员入口取登录member；已存行只要可取消，按其ticket/qty回库存、按其pointUsed返积分。原maker拥有后台add权限，不代表客户端填写的经济依据已被证明 |

漏洞是**无原扣分/预占依据的新行能成为取消返还的经济依据**。不能只删UI输入，也不能把积分或数量改0就认为安全；无预占的票行仍可能释放别人正在占用的库存。不得为了补外部裸建入口而削弱内部合法Mapper写或取消/退款校验。

## 第95例的最小反例

1. 原fixture会员7有100分，会员8有37分；原票总库存10。先调用真实、有事务的正常建单命令为7占1席、零积分消费，读回remaining=9。
2. 经真实后台handler（注入真实Service/Mapper）POST一条无扣分/无预占的新行：会员8、原票1席、pointUsed1000、pointsReturned0、pay0/reg1，以及原schema合法的号/金额/支付时限。测试不调用微信、不复制生产凭据。
3. 当前裸POST若受理，使用其生成的原id让会员8走真实取消；安全拒绝分支则断言没有新报名行。
4. 不论受理后取消还是安全拒绝，期望会员8仍37、会员7仍100、库存仍9（真实那一席仍被占用），原积分流水数量不变。当前源码预计会使会员8变1037、库存9→10；**未实际执行，不能写成已观察结果。**

库存上限确实存在。此例特意先真实占席，将回补放在9→10合法上限内，不依赖溢出或无限库存。测试使用实际MySQL路径的原表/事务/handler/Service/Mapper；仍非HTTP/AOP/token验收。

原H用例的“200受理时从DTO取得生成id”是当前裸POST适配方式，不是替用户选定未来代报名响应协议。若用户选择新的正式订单或独立补录协议，应只按真实原身份调整这一适配，保留积分/库存/原流水守恒断言；不能因真实接口返回不同就删掉负控或放宽经济断言。

## 待用户选择后的最小候选计划（未实施）

| 用户方向 | 计划触点与先决合同 |
| --- | --- |
| 关闭通用新增，统一正常报名 | 在Controller.add拒绝裸实体建交易行，保留原路由/权限的明确拒绝；后台新增按钮/submitForm同步指向已批准的正常报名流程或移除危险动作。内部建单/Mapper不动。由root审新的Controller精确来源hash；不新增RBAC |
| 后台代建正式订单、买家支付 | 先确认sys_user操作者与买家member的授权/审计、买家是否授权使用积分、原request身份、库存/价格/优惠和支付取回方式。再复用既有`RegistrationOrderServiceImpl.createOrReplay`等正常命令，付款只由买家真实支付事实推进；不能把后台登录主体当买家、代扣积分或写假付款/赠送。涉及B Checkout/D通知接线由root协调 |
| 人工补录独立审计 | 先定义原凭证、操作者/复核/受益人、原单关联及是否需要schema。补录记录不能直接进入会返还积分/库存的正常交易状态；外部/历史经济效果只有原证明核实后才归一，不往CmsRegistration填一个“已付”状态冒充回执。若需schema先提具体候选，不执行DDL |

以上是精确备选触点，不是已获选的业务规则；本批不默认代付、赠送、补录金额/计价/积分授权，也不替root重复向用户发同一问题。生产Controller.add保留原样，故此P1仍阻断同域闭环。

## 共享fence只读结论保持

此前独立报告 `e4830458359e2079a9e40312682a6595dc496184:docs/delivery/architecture-day/A-G-cash-protocol-review/REVIEW.md` 不变：候选DDL的11条单列FK没有单独证明event/effect/head/contribution同close，原effect缺head存在约束；已给未执行SQL负控。正额cash_close之外，真实零销售收口后取得原号SUCCESS事实的归属出口仍须G/B补明确合同。当前cash basis已有pay1拒结，不能把普通PAYING说成已证绕过。两项均为未执行结构/协议审查，不在本测试候选修共享资金代码，不选择退款分账或多票固定费算法。

## 实际检查、真实CI来源与未验证

- 本批实际：Git对象/调用者搜索、H patch应用检查及原样应用；`verify_static.py`证明仅51行新增，去掉该原H新增块后测试文件与root05f4逐字相同（原94/fixture/helper/断言均保留）；源码数量94→95。交付扫描、秘密文件门禁、`diff --check`通过。
- **本批Java/DB/CI执行0**，生产修复0。新95例没有编译/运行；按当前源码预计红，不能作为功能完成或绿色候选正式集成。root统一安排受控验证，A不dispatch、不运行Java/数据库/真实支付。
- H RUNTIME已独立回读：source66e9/run40036839154的Funds94全部通过/CLEAN；同源merchant14pass+1类型failure；source9b92/run40037252877的merchant15全通过/CLEAN。不能拼成同一源109全通过，不能把旧94结果覆盖新增第95例。登记source指针不匹配的原记录由root/H保留，本A不改receipt或豁免门禁。
- 整体上线、HTTP真实身份、供应商、root最新联合CI状态未由本批验证。有限秘密格式扫描不代表完整熵/历史扫描。

无schema/迁移/新依赖，无生产资金效果或部署。此候选可撤测试/本A目录但问题仍在；不得回退原PUT白名单/删除保护，不得清理原报名、资金、库存、回执、审计或猜测回填历史裸建记录。

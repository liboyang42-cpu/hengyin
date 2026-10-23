# H：C Photo 四阶段冻结候选独立审查

结论：确认候选确实拆除了 Photo 外层业务事务，使用 E 原意图/nonce 和首个持久结果；本轮未确认新的后端 P1。**不能宣布 Photo 全链路通过**：C 的 Java/联合 MySQL 尚未执行，且 H 实跑发现一项 P2 登录代次迟到 UI 漏口。125 项现有 Node 用例通过没有覆盖这项新反例。未修改 C/E 生产或测试文件，未新增 Agent、未 dispatch CI。

## 候选与证据身份

- 唯一仓库 `https://github.com/liboyang42-cpu/hengyin`；公共基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 冻结 C commit `9da43f76ddd54bfdf0b24a538a1b80d47c5384bd`，parent `a480e4083618120332539d0ac49c7e51e9db6839`，tree `81663088ecf52cd15b2dee55cb7560d51ada9a23`，分支 `codex/architecture-day-c-play-command`。
- 相对基线207文件、相对parent59文件；独立取得全部206文本和已冻结gzip归档，逐blob核对，再用独立临时index从公共基线重建，**完整tree严格一致**。完整GitHub recursive tree未截断。delta身份清单见 `candidate-identity.json`。
- H 工作树 `/workspace/hengyin-h-review`，分支 `codex/architecture-day-h-review`；本轮开始 HEAD `d8cd0dd6f049157289c03f0aa7b2394b55cfbe68`，开始时干净。独占本 H 目录。运行与故障注入均在独立审查快照/编译钩子中，不修改 owner 工作树。
- C 明确依赖 root Vision/Gateway 适配、E 原key恢复 ffc2 和 E91。C 单独树没有 E 新类/DDL是已声明的集成依赖，不能把未组装的整树当成新的编译漏洞；最终集成仍须核对依赖blob与真实Spring接线，不能只合 C 整树覆盖root。
- 已读 C 实现/原动作合同、相关测试、root-owned夹具patch；实际追 C→E91 IntentService/Mapper→PreparedReview/Vision/Gateway 的链路。E91底层17项SQL回执已由既有独立审查确认，**不提升为C编排已验证**。

## H-C9-01 / P2：A→B→A 的同意记录迟到拒绝仍修改当前 UI

位置：`chengyinhub-xcx/pages/play/index.js:7021` 传给单独同意的额外current predicate仅检查 page-bound operation；`:7032` catch 先settle，再仅检查isAborted，`:7035` 无账号/登录代次/page epoch校验就 `fail(error.message)`。CARD模式的fail经`:6940` 调`:6884` `_notifyPhotoFail`，向当前 `playKit.kit.photoFailSeq` 写入并弹toast。

实际依赖不是假设：`utils/compliance/separate-consent.js` 会冻结member+authorization，在异步recordConsent完成后检测旧登录并拒绝；`utils/page-bound-operation.js` 按页面/member轮询，间隔默认250ms，只比较member、不含登录代次。A→B→A发生在两次轮询之间时，operation可以仍未abort，但Page的 `_isPlayAccountCurrent()` 已因token/generation变化返回false。这个拒绝catch恰好绕过Page已有的 `contextCurrent`。

最小复现（H已实际运行）：

1. 会员A=9在同一Page的CARD Photo发起同意，真实同意helper等待recordConsent响应。
2. 保留页面，在一次owner轮询间隔内切B=8，再以新token/新generation切回A=9，原session/version不改。确认 `_isPlayAccountCurrent() == false`，page-bound operation仍为false（未abort）。
3. 原recordConsent成功响应到达。真实同意helper正确拒绝旧登录；Page catch仍调用fail。
4. 实际观察：`photoFailSeq` 从缺省变1，旧错误toast进入当前登录。**上传0次、game.action 0次**；未证实跨账号提交、模型重复收费或数据外泄，不升级为资金P1。

H追加用例复用原Page、真实separate-consent与真实page-bound-operation，仅控制网络响应/轮询时序；原11条仍过，新1条失败（12项11pass/1fail），原输出在 `consent-red.txt`。这是候选实际失败，不是仅用字符串搜索宣称有风险。

最小修正：在该catch先捕获 `owned = this._kitPhotoIntent === intent && contextCurrent()`，随后允许settle/finish清理旧操作；仅owned时给当前UI fail。成功进入upload前也应重验同一个captured context，而不是只比较session/version或只改通用登录helper。不修改共享上传/RBAC，不用放宽current条件来通过测试。应同时保持正常同意拒绝给本操作可见反馈、离页清理和其他账号pending不变。

为定位根因，H用运行时编译钩子只对上述catch加入owned守卫，未写入生产文件；同一12项变成12pass/0fail，见 `consent-guard-counterfactual.txt`。**这是最小修正反事实定位，不是owner补丁已实现/自批通过。** 交付 `late-consent.review.patch` 仅新增回归，不含生产修复。

## 后端逐阶段源码裁决

| 边界与实际调用位置 | 源码结论 | 运行层级与剩余验证 |
| --- | --- | --- |
| ApiAdvancedPlayController.action → `AdvancedGameRuntimeServiceImpl.act:365` | 原payload先深拷贝；Photo在任何准备DB/HEAD前拒绝已有actual transaction。普通动作走必需TransactionTemplate，没有null-manager直跑fallback | Java未运行；必须用实际Spring代理/真实manager确认，不能靠事务标记桩证明 |
| `photoTransaction:747` → `admitPhoto:790` | 两个短事务明确READ_COMMITTED、readOnly=false；不是暂停已有锁后继续HTTP。公开E.read的真实C入口在此可写事务内，覆盖此前“被readOnlyTx调用锁查询”的接线缺口 | C新增调用边界尚未实际Java/MySQL执行；不泛化成E所有public read可在readOnlyTx调用 |
| `lockPhotoSession:859` | requireAccessible初读仅找scope，按activity→registration→原advanced session→锁定成员授权；之后才查terminal/intent。新GameRuntime锁读与receipt锁读均useCache=false+flushCache=true，成员SQL实际有FOR UPDATE | 资格行锁不是资格通过。跨团队变更、完整Dispatch/退款等全局锁序未实际并发验证，不能宣称全图无死锁 |
| `admitPhoto:796`–`:826` | frozen scope/config/原epoch算advanced-photo:v2；terminal先于当前version/eligibility，校验actor/session/hash。原scopedKey+hash查E意图；已有RESULT_READY/UNKNOWN不prepare新nonce。仅当前两份锁读均无原身份且资格拒绝时给intentNotExecuted | 缺失/损坏/异hash拒绝；DB异常不当作空。E.prepare的安全拒绝与基础设施错误应继续做负例，不能随意扩展“确定未执行”标记 |
| `actPhoto:767`–`:781` → E.executeOnce | 退出准备事务后才HEAD/execute；E独立事务从PREPARED CAS到IN_FLIGHT后调用模型。没有租约过期重发。IN_FLIGHT/UNKNOWN/无持久首结果均520保留原命令，C不合成degraded | E真实SQL已验证的内容不能替代C并发组装；r5重复PREPARED竞态可重复只读HEAD，但原模型调用仍由E claim裁决 |
| E Vision PreparedReview → RESULT_READY | 真实Vision返回的首个degraded（包括providerState=UNKNOWN）由E独立保存；C只使用这个持久result执行原fallback。IN_FLIGHT/UNKNOWN无result与已保存degraded是不同事实 | 保留用户确认的degraded→fallback语义，不能把这两类统一当通过或统一拒绝。没有真实供应商/计费核验 |
| `commitPhoto:829` | 重锁session与授权；校验同原key/hash；terminal先于新version/eligibility；重新current-read E.RESULT_READY，不只信内存ready对象 | 非法输入/原expected变化不能重绑定；已发生其他合法动作导致版本冲突时保留首结果，不把expected替换当前值 |
| `actInTransaction` → `submitPhotoCheck:3144` | 该方法只消费stored.result，本地tries/得分/路线事实/CAS与attemptType8回执在一个事务；没有直接调用Vision。degraded不造xp奖励，保留原配置fallback | 纯本地源码路径已追；普通玩法兼容需Java原用例，不能因Node绿略过新增必需manager夹具 |
| `commitPhoto:848`–`:855` → E.markApplied | 同事务读取真实receipt ID/hash/actor/session，经E countMatchingReceipt关联原intent后APPLIED；错误抛出导致本地事务回滚，首结果独立提交仍保留。第一回包与重放都读原receipt JSON | 当前C新增3个SQL场景只注入CAS/RECEIPT/CONCURRENT，尚无markApplied实际UPDATE后故障回滚证据；H补丁提供第四种故障 |
| Controller返回/重试 | 520原因泛化，不泄露provider/DB原文；不调用finishPendingCardMint。终态成功后铸卡保留提交后逻辑，回放无内部mint marker | 铸卡自身持久意图不在此闭环，不能签外部铸卡exactly-once |

当前源码未证实外层AOP“业务已提交后才写回执”、TTL释放原身份、ServiceException一律退款、IN_FLIGHT超时盲重发或用统计补奖。未发现新后端P1不等于后端验收通过。

## 客户端原动作、跨账号与生命周期

- `advanced-game/index.js:244` 在发送前用 `advanced-photo-pending.js` 保存并回读验证，记录只含主体/原session/key/expected/canonical media ID；不是token、签名URL、答案或照片字节。未确认记录不能被新输入覆盖。
- start有Photo pending时只GET原session，用户核对后POST原命令；个人Photo不再靠version增长认成功，也不换runtime/expected。520、非明确未执行的409/500继续UNKNOWN。terminal回包先校验session，再清捕获的原pending键。
- component context冻结member、login generation、page epoch、scope、session；旧A响应可以只清捕获的A原键，不能清B或A后续key、不能hydrate新登录UI。现有组件新增账号用例实际通过，但**没有覆盖本轮H发现的Page同意catch**。
- Page上传成功回调有captured owner/epoch/session guard；canonical media-asset ID验证无URL fallback。页面销毁/旧会话上传的既有负例通过，不代表所有账号切换窗口都已穷尽。

## 实际执行与反事实解释

1. H使用Node **v24.19.0**，逐文件直接执行仓库原 `node:test` 用例（命令为 `node tests/unit/<name>.test.js`）。9文件合计**125tests/125pass/0fail/0skip**，逐文件原输出见 `node-direct-125.txt`，哈希/退出码见 `node-evidence.json`。本环境最初`node --test`隔离启动只报告9个文件级项目，未作为125项证据；直接执行已核对真实case计数和H失败断言，未修改测试条件。
2. 同一59个现有组件用例，使用Module编译钩子只加载精确parent组件blob `3deb4f8afae73a31a50a8dde100d6d7d76ec8200`，H独立重现**50pass/9fail/0skip**，见 `parent-component-counterfactual.txt`。失败恰为Photo持久重试/账号保护等新要求和共享动作登录代次保护。它证明用例能识别应拒的旧组件，**不是当前候选有9fail，也不是完整旧树或后端Java测试**。
3. H独立新增Page同意迟到用例：12项11pass/1fail；这11项与原125内的上传文件重叠，不能把125+12当137个独立用例。若仅接纳该新用例，原集合变126项。
4. `late-consent.review.patch` 与 `mark-applied-rollback.review.patch` 对精确C9文件 `git apply --check` 均通过。前者已以等价附加测试实际运行并失败；后者未编译/未运行。
5. C16条新编排单元、Controller UNKNOWN新例、6个mapped-statement场景、ThemeRuntime新增3个参数化场景，以及本H新APPLIED场景：**本轮Java/MySQL执行数均为0**。没有运行JVM/数据库，没有启动CI，没有调用真实供应商、付款或微信。

## 必须补齐的回归与集成合同

- owner先修H-C9-01，再跑新Page用例、原125、全量小程序、控件台账及真机/模拟器生命周期检查。Node代理对象不是微信运行时。
- 最终联合源须保留E91、原quota修复及root当前指纹/测试清理。C报告附root-owned旧Photo两测试适配只替换原Vision字段为intent double supplier；它不是durable证明，必须保留root最新quota夹具，不能用C整树/旧patch覆盖掉。
- 在批准CI运行真实C16+Controller新例、全部受影响Java原用例，以及C的CAS/RECEIPT/CONCURRENT真库场景；逐XML检查原业务方法执行数、原nonce/首结果、session/路线/receipt向量与清理。不能把E17或125Node补进C的Java执行数。
- H `mark-applied-rollback.review.patch` 在原参数化用例中增加APPLIED：真实Mapper先更新APPLIED，再故意抛错，要求外层事务同时撤销session/路线/receipt/APPLIED、独立首结果仍RESULT_READY且nonce不变；移除故障后原key仅一次provider并回读原receipt。原C3场景变4；实际批次登记需owner/root更新，不通过跳过解决。
- 补真实账号拒绝向量、成员撤销、同scope切原session、外层readOnlyTx拒绝、空同步上下文锁缓存与最终集成Spring接线。当前短事务锁序符合C所述session→intent，但全局资格/团队/route写者竞争尚未知，不把静态SQL六flags当真实并发证明。

## 同候选矩阵、迁移与回退

| 项目 | 裁决 |
| --- | --- |
| 冻结C9源码/完整tree身份 | 已核验一致 |
| 原125 Node集合 | 实际通过；不是全量/真机 |
| 精确parent组件59反事实 | 实际50pass/9fail，负控有效 |
| 新Page登录代次迟到反例 | **失败：H-C9-01/P2** |
| 后端四阶段/终态优先/首结果/UNKNOWN合同 | 源码可追，未确认新增P1；运行未知 |
| C Java与联合MySQL | **未执行，不能验收** |
| H APPLIED写后故障回归 | patch可应用，Java/MySQL未执行 |
| 供应商/Redis/真机/上线 | 未验证/无授权操作；整体不签上线 |

本H只增报告、证据和未应用测试patch；没有schema、权限、依赖或DDL改动。C启用依赖E intent与C fingerprint migration，预检/生产迁移由集成人安排；本轮没有执行。回退不得删原意图/nonce/结果/回执或释放身份，不应重新启用事务内模型调用。既有首结果与部分实际完成的命令必须保留供原身份核验。

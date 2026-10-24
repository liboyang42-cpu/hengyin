# LANE U1：核实 lane-G「UNVERIFIED」四项（2026-10-22）

- 依据：`feature/lane-g-triage-20261022` 上 `docs/delivery/release-closeout-20261022/disposition.md`「未决阻塞」第 7 项。
- 基线：`origin/master` @ `f0b43d62`（#475 合入后）。
- 方式：静态阅读 + GitHub Actions 只读查询。没有触发或重跑任何 workflow，没有碰任何人的 PR。本机 Maven 离线无依赖缓存，Java 用例交给本 PR 的 GitHub-hosted CI 跑。

## 结论一览

| # | 项 | 结论 |
|---|---|---|
| 1 | `port-f3-growth` 真库 IT 在 master 上是否为绿 | **绿（间接）**。没有任何 job 自动跑它；最近一次手动跑是 run 39576276419（#204），批次 `port-f3-growth`，候选 `b1b93d44`（master 的祖先提交），结果 success。从 `b1b93d44` 到 master 头，该 IT 和它 import 的生产类都没有变 |
| 2 | `onVerification` 被外层死锁重试时有无非 DB 副作用 | **没有**，不需要加守卫。另外发现一个**相邻风险**：savepoint 内发生死锁时，异常会被吞掉，外层的 `@DeadlockRetry` 看不到它。涉及资金路径，**NEEDS_OWNER_APPROVAL** |
| 3 | #398 的 573 个新增文件里是否还藏着修复 | 找到 10 个候选。7 个已经在其他 lane 的开放 PR 里（W1 #470、W2 #473）；**本 PR 移植 1 个**（据点模板 `duration` 丢失）；其余是低优先级或策略性改动 |
| 4 | #21 台账遗留项是否已落地 | lane-G 点名的 3 项里只有 BL-255 落地了。共有 5 个缺口，见第 4 节 |

---

## 1. `port-f3-growth` 真库 IT

**入口**：`.github/workflows/real-storage-it.yml`

- 只能 `workflow_dispatch` 手动触发，不在 required checks 里。
- 批次在 `ci/real-storage-it-registry.json` 里登记为 `port-f3-growth`：
  - 类：`com.chengyinhub.business.forward20261015.PlayerGrowthConcurrencyMySqlIT`；
  - 登记源 `89e87425`。
- `ci.yml` 和 `funds-mysql-it.yml` 都不跑这个类。也就是说：**push 到 master 不会自动跑它。**

**运行记录**：216 次 run 里有 28 次跑过这个类（按 artifact `rs-class-PlayerGrowthConcurrencyMySqlIT-a1` 和 `rs-summary-<batch>-<sha12>` 识别）。和 master 相关的几次：

| run id | # | 时间 (UTC) | 批次 | 候选 sha | 结论 |
|---|---|---|---|---|---|
| 39280426033 | 20 | 10-19 15:45 | f3-growth | 763dcb43 | failure（修复前，已由 #294 / 604b8c66 解决） |
| 39303279030 | 50 | 10-19 18:31 | port-f3-growth | 89e87425（登记源，master 祖先） | success |
| 39451575576 | 101 | 10-20 15:46 | f3-growth | 21aaa153（该 IT 在 master 上的最后一次修改） | success |
| 39560574578 | 193 | 10-21 07:14 | port-f3-growth | 36dabfb8 | success |
| **39576276419** | **204** | **10-21 09:21** | **port-f3-growth** | **b1b93d44（master 祖先）** | **success** |

- 我自己用 `list_workflow_run_artifacts` 复核了 #204 的两个 artifact：`rs-class-PlayerGrowthConcurrencyMySqlIT-a1`、`rs-summary-port-f3-growth-b1b93d441e46-a1`。
- #205–#216 都没有跑这个类。
- 另外 11 次 run 没有 artifact，判断不了批次：#118–127 是预检失败或被取消，#28 被取消；它们都没有真正执行到这个类。

**与 master 头的等价性**

- `git merge-base --is-ancestor b1b93d44 origin/master` 成立。两者之间有 192 个提交。
- `git diff b1b93d44 origin/master` 对以下文件**全部为空**：
  - IT 本身；
  - 它 import 的全部 21 个生产/测试类（其中包括 `TopicCompletionAwardServiceImpl`、`PlayerProgressionServiceImpl` 以及各 growth/badge Mapper 接口）；
  - `DeadlockRetryInterceptor`。
- 同一区间 mapper XML 只改了 4 个：Im*、NpcChatRequest、PlayerNodeProgress。它们不在这个 IT 的调用链上。

**结论**：对 master 头 `f0b43d62` 本身**没有直接跑过**，但最近一次绿跑的候选与 master 头在该 IT 的依赖面上逐字相同。要拿到「master 头直接为绿」的证据，需要 owner 手动 dispatch：`batch=port-f3-growth`、`source_sha=f0b43d62d4d127fcd399f080a54acf9477c5d9fb`。本 lane 不代为触发。

**顺带一个小问题**：登记表 `port-f3-growth.note` 写的「byte-identical to 604b8c66」已经过时。`git diff 89e87425 origin/master` 显示该 IT 后来又改了 89 行（0f4dc94b、21aaa153）。这一点 lane-H3 已经指出过，本 lane 不改登记表。

## 2. `MerchantReferralBountyService.onVerification` 与外层重试

**调用链**

- `CmsRegistrationServiceImpl.java:3139` 调用 `merchantReferralBountyService.onVerification(verifierId)`。
- 这处位于核销收口。它的外层是 `redeemExploreChapterAndRecord`，带 `@DeadlockRetry` 和 `@Transactional`（`:3157-3158`、`:3167-3168`）。
- 拦截器 `DeadlockRetryInterceptor` 只在最外层生效：整笔回滚，再从事务拦截器重新进入，最多 2 次。

**副作用清单**（`MerchantReferralBountyService.java`）

- 注入的协作者只有 5 个：
  - `PlatformTransactionManager`（:47）
  - `MerchantReferralBountyMapper`（:88）
  - `IUmsMemberService`（:91）
  - `UmsMemberBalanceDetailMapper`（:94）
  - `UmsMemberMapper`（:107）
- 没有 Redis、消息、通知、HTTP、`ApplicationEventPublisher`。
- 写操作全部是 MyBatis，并且都在 `PROPAGATION_NESTED` 的 savepoint 里（:136）：
  - `insertBounty`（:407）
  - `markPaidIfPending`（:244）
  - `addBalance`（:445）
  - `insertUmsMemberBalanceDetail`（:459）
  - 其余是 `markExpiredIfPending` 和 `SELECT … FOR UPDATE`
- `IUmsMemberService.addBalance` 和 `selectUmsMemberById`（`UmsMemberServiceImpl.java:206, 625`）也只是 mapper 调用，没有缓存注解，也没有事件。
- 外层失败后整笔回滚，这些行全部撤销；重试时再按 CAS（`markPaidIfPending` 只允许 0→1）重新判定一次。所以**不会重复发钱，也不会重复写流水**。
- 唯一会重复出现的是日志：`[ReferralBounty] paid …`（:251）在被回滚的那次尝试里也会打印。它不是业务副作用，对账应以库为准。
- 核销链路上真正的非 DB 副作用（异常抽检告警）已经走 `committedBusinessNotice.executeAfterCommit`（`CmsRegistrationServiceImpl.java:3147`），提交后才发，回滚不会发。

所以第 2 项**不需要**加 after-commit 或幂等守卫，本 PR 也没有为它改代码。

**相邻风险：savepoint 内死锁被吞，属于资金路径，NEEDS_OWNER_APPROVAL，未改**

1. MySQL 1213（死锁）会回滚整笔物理事务，savepoint 随之失效。
2. Spring 的 `TransactionTemplate` 在异常时会去回滚到 savepoint。savepoint 已不存在，这一步得到 `SAVEPOINT … does not exist`，被包成 `TransactionSystemException`。外层事务不会被标成 rollback-only。
3. `onVerification` 的 `catch (RuntimeException e)`（:123）把它吞掉，只打一条 error 日志。
4. 结果是外层 `@DeadlockRetry` 看不到 `DeadlockLoserDataAccessException`，不会重试。后续语句会在 InnoDB 隐式开启的新事务里执行并被提交。死锁前那部分核销写入已经丢了，接口却可能返回成功。

补充两点：

- 1205（锁等待超时）默认只回滚单条语句，savepoint 仍然有效，吞掉是安全的。
- `docs/verification/mini-longrun-20261015/CORE/OWN-C04-21.md:19` 自己也写明「deadlock … can abort the physical outer transaction」，并且没有真库证明。`withInviterLock` 上的注释（:130-131）也承认这一点。

这条推理来自静态阅读，本 lane 没有真库复现。

建议的最小修复（待 owner 拍板，因为它会改变「赏金永不连累核销」的约定）：在 `onVerification` 的 catch 里，如果异常链中有 `DeadlockLoserDataAccessException`，或者有 `TransactionSystemException` 且其 cause 是 SQLState 40001，就原样抛出，让外层整笔重试。验证需要一条真库 IT：两条连接交叉锁 `ums_member` 和 `merchant_referral_bounty` 制造 1213，断言核销行和赏金行要么都在、要么都不在。

## 3. #398 新增文件里的修复

**范围**：`417ff214..pr/398` 只有一个 squash 提交 `0127e3dc`。573 个新增路径在 master 上都不存在。

**覆盖情况**

- 按名称排除 176 个（questify / marketplace / attest / workshop / plugin / w16 / w17 / w18）。
- 其余 397 个：
  - 66 个测试读了类注释；
  - 约 15 个测试/辅助类全文阅读；
  - 约 300 个按目录归为新功能（city / home / discovery / rendezvous / NonCash / navigation / ContentCapture / PrivateHome / MerchantAuthoring / Native / ThemeTeam / CouponCommand 等）；
  - 14 个 migration 全部检查过，都是新表，没有一个修复 master 既有表的索引或约束。
- 181 个修改文件里有 90 个 main 文件读了 diff，用来定位新增类接进 master 的位置。

**候选与去向**

| # | 修复 | master 证据 | 去向 |
|---|---|---|---|
| 1 | **据点模板 `duration` 丢失**：`CmsMemberTemplate.duration` 是 Long（`CmsMemberTemplate.java:80`），`CmsTemplatePublishRequest.duration` 是 Integer（`:62`）。`BeanUtils.copyProperties` 不做这种转换，跳过后由 `Convert.toLong(null,0L)`（`TemplatePublishServiceImpl.java:516`）落成 0。小程序 `pages/publish/temp/index.js:3250, 3261` 会提交 `duration` | `TemplatePublishServiceImpl.java:403` | **本 PR 移植**（非资金、非鉴权） |
| 2 | 跨夜营业 `次日HH:mm` 解析：`TopicRouteCandidatePolicy.java:25-26, 136`。小程序 `merchant-business-hours.js:21` 会写出 `次日` | 同左 | 已在 **W1 #470** |
| 3 | 商家 NPC 用 member id 当 merchant id 查（`NpcProfileService.java:99` 等） | 同左 | 已在 **W2 #473**（`MerchantNpcIdentityDirectory`） |
| 4 | 重新上架复检读错记录（`ApiTemplateController.java:417`） | 同左 | 已在 **W2 #473**（`TemplateLibraryRepublishReview`） |
| 5 | 据点内容送检漏了 `ruleInstructions`（`ApiMerchantNodeController.java:215-216`） | 同左 | 已在 **W2 #473** |
| 6 | 报名列表在 principal userId 为空时不按 member 过滤（`ApiRegistrationController.java:764-797, 1043-1053`） | 同左 | 已在 **W1 #470**（鉴权类，由该 lane 负责向 owner 确认） |
| 7 | 已发券的有效期可被改写（`SmsCouponHistoryMapper.xml` / `ViewCouponHistoryMapper.xml`） | 同左 | 已在 **W2 #473**（券值相关，**NEEDS_OWNER_APPROVAL**） |
| 8 | LLM 客户端日志回显 provider 响应体（`OpenAiCompatibleLlmClient.java:156, 166`） | 同左 | 已在 **W2 #473** |
| 9 | 主题复制时源模板缺失仍留悬空 templateId（`CmsTopicServiceImpl.java:1034`） | 同左 | 未移植：属于策略改动（改成直接失败），master 上没有看到具体错误行为。另外 W5 #471 正在改 `CmsTopicServiceImpl` |
| 10 | 进阶配置 quantity 用 `asInt()` 比较，超大负数会溢出绕过（`AdvancedGameConfigValidator.java:1861`、`TopicRouteEngine.java:157`） | 同左 | 未移植：边界情况、优先级低；W7 #476 正在改 `TopicRouteEngine` |
| — | RegistrationLifecycleAudit*、MerchantAccrualBasis*、CouponCommand*、TopicCompletionCoupon* | 退款 / 拼团 / 结算 / 追回 | **NEEDS_OWNER_APPROVAL**（资金），而且大多是新的审计/回执功能，不是对既有代码的修复 |

**本 PR 的移植（第 1 项）**

- `TemplatePublishServiceImpl.saveMerchantNodeTemplate`：`copyProperties` 之后，如果 `input.getDuration()` 不为空，就用 `Math.toIntExact` 精确转换后写进发布 DTO；溢出时抛 `ServiceException("时长超出支持范围")`，不截断。同文件 :592-593（复制源模板）已经用了同样的写法。
- 用例：`TemplateEditOwnershipTest` 新增两条。
  - `merchantNodeDurationSurvivesDtoCopy`：新建 45 → 落库 45，改写 30→60 → 落库 60。修复前两次都是 0。
  - `merchantNodeDurationOverflowIsRejectedNotTruncated`：`Long.MAX_VALUE` 被拒绝，并且没有插入任何行。
- 有意没做的：PR #398 还会在「更新时省略 duration」的情况下保留原值。表单总会带上 `duration`，这超出了最小修复的范围，没有带上。

## 4. #21 台账遗留项

台账位置：`pr/21:docs/plans/mini-claude-ui-20261015/TASKS.md`「云端线收尾（2026-10-20）」的「未完成」，加上它引用的 BACKLOG.md「交 Codex」一节。lane-G（`disposition.md:838`）把 #21 定为 REFERENCE_PRESERVED，不合入。

| 项 | 状态 | master 证据 |
|---|---|---|
| BL-230…254 防连点（经 #293） | 已落地 | #293 = `1c469f9e`；每张卡都有 `tests/unit/*bl-2xx*`；抽查 `pages/merchant/index/index.js:303-304` |
| BL-255 商家信息页 12 处 | 已落地 | `b268b5cc`；`pages/topic/merchantinfo/merchantinfo.js:949, 2486, 2514, 2526, 2538, 2619, 3793, 3957, 3970, 4042`；`tests/unit/bl-255-merchantinfo-joinact-nav-guard.test.js` |
| fabu `onCreateGame` 防连点（原 :6821，交 Codex） | **缺** | `pages/publish/fabu/index.js:8353`，`wx.navigateTo` 在 :8362，没有 `_navigating` 锁；绑定在 `index.wxml:973, 974, 1037` |
| BL-185 发布前调用 `GET /api/topic/publish-check` | **缺** | 后端 `ApiTopicStateKeyController.java:164` 已有接口；小程序没有任何调用（只有 official v2 的 `official-detail/index.js:260`、`official-organizer/index.js:101` 用了同名的另一组接口） |
| BL-186 `GET /api/play/journey/stage-end` | **缺** | 后端 `ApiPlayJourneyController.java:27` 已有接口；`git grep -i 'stage-?end' -- chengyinhub-xcx`（不含 tests）没有结果 |
| B9 申请 `refundRejected` 订阅 | **缺** | 只在 `utils/subscribe.js:7, 17` 定义，没有任何 `subscribe.request` 用到它 |
| P1-033 mylike 按 id 删除 | 已落地 | `pages/mylike/mylike.js:379-380, 432` |
| #35 CL6 协办方展示 | **缺**（#35 仍是 draft） | 没有 `utils/official-parties.js` 和 `components/cy/official-coorganizers/`；`official-detail/index.wxml:159-162` 只有 `CL6-SLOT` 注释；公开的 `OfficialEventV2ServiceImpl.detail()`（:574-610）不返回 `parties` / `viewerCanManage` |
| CL5 认领证明材料 | 已落地（契约细节未核） | `pages/merchant/utils/merchant-claim.js:9, 42`；`MerchantClaimDTO.java:10-15` |
| CL2 举报进度通知标记已读 | 已落地 | `subpackageB/pages/im/chat/index.js:211` |
| 收藏点位页 `node-favorites` | 按决定不上 master | master 上没有，`app.json` 里也没有 |

**缺口汇总**：

1. fabu `onCreateGame` 防连点；
2. BL-185；
3. BL-186；
4. B9；
5. CL6，前后端都缺。

前 4 项原先交给 Codex。其中 `fabu/index.js` 不在 C00 保留列表里，但正被其他前端 lane 修改，本 lane 不碰。

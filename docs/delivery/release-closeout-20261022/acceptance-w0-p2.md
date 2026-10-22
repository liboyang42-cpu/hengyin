# LANE AC3 — 发布验收 W0 + P2 组自动化覆盖（2026-10-22）

> 对应《城瘾小程序实施文档一》§9.1 剩余验收组。兄弟 lane：AC1 = P0（#497，含注销真库 IT），AC2 = P1。
> 本 lane 只做后端 + 小程序 node 单测两半；真机/UI 项保留人工。CX-19（两套等级体系）按指令跳过，等产品决定。

- 分支：`feature/lane-ac3-w0-p2-20261022`，基线 master `f0b43d62`（Merge #475）
- 只新增测试与本文档；未改产品代码、CI workflow、migration、C00 预留文件。
- 未运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（按规则）。

## 卡片定义来源与解读

仓库里没有带完整步骤的卡片清单（PR #282 评论说那份清单是私有的，这些评论只是各 lane 的证据表）。所以卡片含义按下列顺序认定：
1. 已有测试文件头注释；
2. PR #282 的证据评论；
3. PR #312 / #306 / #456 的正文。

| 卡 | 本 lane 解读 | 依据 |
|---|---|---|
| CX-11 | 我的模板 → 详情 → 编辑页保存：请求真发出、回包被应用、在途不重发、晚到回包不覆盖 | `v6b-template-edit-save-singleflight.test.js` 头注释；#306 |
| CX-12 | 「我的玩法」草稿确认删除（`subpackageMember/mytemplate`，列表即玩法草稿）+ 俱乐部主题详情刷新失败保留旧内容 | `v6b-mytemplate-delete-refresh.test.js`、`v6b-club-topic-detail-refresh-race.test.js` 头注释；#306、#325 |
| CX-16 | **无法确定**：仓库、523 个远端分支、#282 全部评论与相关 PR 均无此编号（CX-15=社区点赞串帖，CX-17=跨账号晚到回包） | 见文末「未决」 |
| CX-31 | 路由清单：app.json 每条路由有入口；代码/后端引用的路由都真实存在 | #312 正文 CX-31 表 |
| SC-CL-APPLY | 主理人资质申请：必填项服务端同口径；删除最后一张失败资质图后恢复可提交 | `ApiClubBecomeLeaderRequiredFieldsTest` 头注释；#456 / #412 |
| SC-CL-APPROVE | 驳回 → 重新提交同一行、清旧驳回原因、我的申请取最新；另含 #418 的同意补传（退出重登沿用原 requestId）与第二账号隔离 | `ClubRejectedResubmitTest` 头注释；#456 / #418 |
| SC-CL-ENTRY | 俱乐部 tab → 俱乐部详情；首载失败不冒充空态 | `v4-club-entry.test.js` 头注释 |
| SC-DEREG-01 | 设置页注销半屏 / 路由页：状态读取失败 fail-closed，迟到回包不覆盖 | `v4-dereg-status-sheet.test.js` 头注释 |
| SC-PRIV-01 | **推断**：首启使用/隐私同意闸（SC-PRIV-03/04/05 依次是撤回、授权宿主链、同意留痕，01 应是首启同意本身）：未同意不登录、不发公开读以外请求；同意后留痕；拒绝不落同意 | 编号相邻推断；见文末「未决」 |
| SC-TPL-OLD-BACKEND | 新小程序对旧后端：adopt-draft 404/405 明确拒绝、不回退建普通草稿；结果未知不盲重试 | `v4-tpl-old-backend.test.js` 头注释 |
| SC-TPL-OLD-SAVE-ROLLBACK | 旧模板分支选择落库后完成闸 CAS 失败整体回滚、选择不被改写、重试结果一致 | `LegacyChoiceCompletionRollbackAcceptanceTest` 头注释 |
| SC-UI-DATE-COUNT | 主题卡/向导/统计/场次页站数与中国日历日期跨页、跨设备时区一致 | `sc-ui-date-count-cross-page.test.js` 头注释；#344 |
| SC-UI-PROJECT-CARDS | 俱乐部详情项目卡按身份路由，访客进不了管理面 | `v4-club-project-cards.test.js` 头注释 |

## 卡 → 测试 → CI job → 状态

CI job 缩写：
- **MU** = `GitHub CI / Mini program unit shard (n/20)`（`npm --prefix chengyinhub-xcx run test:unit`，glob 自动收录）。
- **BT** = `GitHub CI / Backend tests`。其中 `mvn -B clean test` 跑 *Test；`ci/backend-test.sh` 的 `MYBATIS_ITS` 点名跑真库 *IT，并用 `assert_it_ran.py` 回读。

计数为本机 Node 22.22.0 / JDK 21 实跑。

| 卡 | 测试（file::要点） | 本机结果 | CI | 状态 |
|---|---|---|---|---|
| CX-11 | `v6b-template-edit-save-singleflight.test.js`（在途不重发 / 回包应用 / 失败可重存）；`template-adopt-draft-and-preview-summary-contract.test.js`、`r9-04-own-template-resume-contract.test.js`、`templatedetail-truthful-actions-contract.test.js`（晚到回包、续编带原 id、详情回读）；后端 `TemplateEditOwnershipTest`（带 id 改自己那份 + 归属闸） | 3/3；后端 20/20 | MU, BT | 已覆盖 |
| CX-12 | `v6b-mytemplate-delete-refresh.test.js`（取消不发 / 确认只删一条一次 / 刷新失败非阻塞提示）、`mytemplate-list-recovery-contract.test.js`（删除单飞、失败不摘卡）、`v6b-club-topic-detail-refresh-race.test.js`；**新增** 后端 `TemplateDraftDeleteOwnerAcceptanceTest`（陌生人/未登录删不了且零写入；本人确认删一次，重复确认被拒且不再写；未知 id 零写入） | 3/3、16/16、5/5；**新增 3/3** | MU, BT | 已覆盖（服务端半边本 lane 补齐） |
| CX-16 | — | — | — | **未映射**（编号无定义） |
| CX-31 | `nav-route-exists.test.js`、`backend-miniprogram-route-exists.test.js`、`page-cleanup-audit.test.js`、`club-dissolution-blockers-inline-contract.test.js`；**新增** `ac3-cx31-backend-slash-route-exists.test.js`（后端带前导斜杠的 `"/pages/…"`、`"/subpackageX/…"` 路由必须在 app.json；已删的 dissolution-blockers 只允许由 scene-club-edit 改写承接；资源路径 `.mp3` 不算路由；含负控） | 10/10、3/3、1/1、4/4；**新增 4/4** | MU | 已覆盖（补上 #312 指出的前导斜杠漏扫） |
| SC-CL-APPLY | `club-apply-state-contract.test.js`（含 #456 的 6 条：删最后一张失败图恢复提交、仍有失败/上传中不得提交、索引重排、异常索引、setData 回调延后；临时路径不得提交）；后端 `ApiClubBecomeLeaderRequiredFieldsTest`；**新增** `ApiClubBecomeLeaderCertImagesAcceptanceTest`（`certImages:""` / 缺省照常建档；DEFECT 记录见下） | 24/24；4/4；**新增 3/3** | MU, BT | 已覆盖；**发现 1 项服务端缺口（BUG-AC3-1）** |
| SC-CL-APPROVE | `club-create-state-contract.test.js`（驳回不占名额、重新提交回执）；`usage-consent-app.test.js`（#418：同账号退出重登不重复留痕 / 待补传沿用原两个 requestId / 第二账号不能消费原账号台账 / 冷启动仍走同意门）；`roam-player-memory.test.js`、`logout-server-ack-contract.test.js`；后端 `ClubRejectedResubmitTest`、`ClubApplyStateMapperSqlTest`、`ApiClubMyApplyNewestFirstTest` | 13/13、17/17、9/9、11/11；后端 3/3、5/5、1/1 | MU, BT | 已覆盖 |
| SC-CL-ENTRY | `v4-club-entry.test.js`、`talent-club-list-state-contract.test.js` | 4/4、12/12 | MU | 已覆盖 |
| SC-DEREG-01 | `v4-dereg-status-sheet.test.js`、`deregister-consent-gate.test.js`；后端 `ApiAccountDeregistrationControllerTest`、`AccountDeregistrationServiceImplTest`、`…CompletionPersistenceTest`、`…DueBatchAcceptanceTest`、`…FailureNoticeTest`、`…BatchIsolationAcceptanceTest`、`AccountDeregistrationInterceptorTest`、`AccountDeregistrationJobTest`；真库 `AccountDeregistrationRaceMySqlIT`（BT 点名） | 5/5、9/9；后端 10+16+6+8+4+1+1+1 | MU, BT | 已覆盖；真库「只清本人」由 AC1 #497 `P0DeregistrationMySqlIT` 承担，本 lane 不重复 |
| SC-PRIV-01（推断） | `usage-consent-app.test.js`（游客初始化先于任何登录 / 冷启动同意门 / 拒绝不发登录）；后端 `ConsentRecordServiceImplTest`；相邻 `v4-priv-auth-host-chain.test.js`、`v4-priv-revoke-retry.test.js`、`v4-priv-agree-submit.test.js` | 17/17；9/9；6/6、3/3、4/4 | MU, BT | 按推断已覆盖，待确认卡义 |
| SC-TPL-OLD-BACKEND | `v4-tpl-old-backend.test.js`（404/405 ×2、结果未知 ×3、成功对照）；服务端存在性由 Fast gates「Check every /api call has a backend @*Mapping」保证 | 6/6 | MU, Fast gates | 已覆盖 |
| SC-TPL-OLD-SAVE-ROLLBACK | 后端 `LegacyChoiceCompletionRollbackAcceptanceTest`（Mockito）；真库 `AdvancedGameRuntimeMySqlIT#legacyFrozenBranchCommitsOnceAndInvalidOutcomeRollsBack`（`MYBATIS_ITS` 点名） | 2/2；IT 仅 CI | BT | 已覆盖 |
| SC-UI-DATE-COUNT | `sc-ui-date-count-cross-page.test.js`（3 时区子进程）、`publish-stats.test.js`、`v6b-director-resume-eta-timezone.test.js`；后端 `TopicUpdateByOwnerTest`、`ApiTopicInfoHiddenNodeTest`；真库 `PublicTopicStationCountMySqlIT` | 7/7、12/12、1/1；38/38、2/2 | MU, BT | 已覆盖 |
| SC-UI-PROJECT-CARDS | `v4-club-project-cards.test.js` | 5/5 | MU | 已覆盖 |

**计数**：
- 范围内 13 张卡（CX-19 按指令跳过）：11 张已有自动化映射，其中 3 张由本 lane 补了缺口（CX-12、CX-31、SC-CL-APPLY）；SC-PRIV-01 的映射基于推断；CX-16 未映射。
- 本 lane 新增 3 个文件、10 条测试：小程序 4 条，后端 6 条。
- 映射到的后端测试本机共 156/156 通过。

## 本 lane 发现的问题

### BUG-AC3-1：`/api/club/become-leader` 不校验 certImages（SC-CL-APPLY）

- **严重度**：低到中。客户端已经拦截，这里缺的是服务端兜底。
- **输入**：`POST /api/club/become-leader`，body 为 `{"leaderName":"阿城","phone":"13800000000","experience":"1-3次","hasGuideCert":1,"certImages":"wxfile://tmp/failed-cert.jpg"}`。
- **期望**：服务端拒绝本机临时路径、非本人媒体，或把它们剔除。同一控制器建团时的 logo、cover、caseImages 都经过 `reviewedMedia(...)`（`ApiClubController.java:190-194`、`:354-364`）。
- **实际**：`leaderProfilePatch` 把 `body.getCertImages()` 原样写进档案（`ApiClubController.java:327`），`becomeLeader`（`:433-454`）不做任何媒体校验，也不调用 `submitMediaCheck`。
- **证据**：`ApiClubBecomeLeaderCertImagesAcceptanceTest#defectRecordedLocalTempCertPathIsPersistedWithoutServerSideMediaCheck`。这条测试按 #312 的约定写成 `DEFECT(记录现状)`，钉住的是现状，所以当前为绿；修复后应改写成断言拒绝。
- 本 lane 只做测试，没有修。修复需要动媒体归属校验，属于 AGENTS.md 规定的 Ask first，应另开任务。

### 其他

- 未发现新缺陷。
- #312 和 #306 当时记为 todo 的缺陷（SC-PRIV-04 回退路由、SC-PRIV-05 留痕前结算、CX-12 A/B/C）在当前 master 上已经没有 todo：`v4-priv-*` 共 0 todo，v6b 两个文件全部通过。

### CI 分片竞态（测试隔离问题，本 PR 已修复测试侧）

- **现象**：新增一个测试文件后，CI 分片的成员跟着平移。`roam-comment-scan.test.js` 与 `subscribe-recipient-contract.test.js` 始终落在同一分片（两者文件序号差 180，是 20 的倍数）。新分片 14 里的文件组合改变了并发时序，本机 3 次中有 2 次复现失败：`subscribe-recipient-contract.test.js:225`「负控:删掉结算到账授权入口时…」报 ENOENT，指向 `subpackageRoam/scan-comment-test-*/regex.js`。master 的分片 13 同样跑 3 次，全部通过。
- **根因**：`roam-comment-scan.test.js` 在 `subpackageRoam/` 下临时建、再删 `scan-comment-test-*` 夹具目录；`subscribe-recipient-contract.test.js` 的 `walkJs` 扫描整棵源码树，先列出文件、后读取，两步之间夹具可能已被删掉。
- **修复**：`walkJs` 跳过 `scan-comment-test-*` 临时目录，与已有的 `tests/`、`node_modules` 排除同理。被跳过的只有别的测试的临时夹具，不是产品源码，门禁口径不变。修复后分片 14 连跑 5 次全部通过。

## 仍需真机 / 人工的检查

| 卡 | 真机项 |
|---|---|
| CX-11 | 编辑页保存后返回详情，显示的是新内容；弱网下连点保存只出现一次结果面板 |
| CX-12 | 「我的玩法」删除弹窗的视觉和文案；弱网刷新失败时 toast 可见；俱乐部主题详情刷新失败的非阻塞提示可见 |
| CX-31 | 订阅消息 / 分享 / IM 卡片落地页（含 `/subpackageB/pages/im/list/index`、商家入驻 `/pages/merchant/apply/index`）真机点击能打开；系统返回手势 |
| SC-CL-APPLY | 第 4 步资质图断网上传失败 → 删除 → 按钮恢复；上传中删除其他图（#456 的观察：`certUploading` 索引未随删除平移） |
| SC-CL-APPROVE | 后台驳回 → 小程序「我的申请」显示驳回原因 → 重新提交 → 回执「已重新提交，等待审核」；#418 在开发者工具 Network 中核对 `/consents` 次数与 requestId（步骤见 `lane-e.md`） |
| SC-CL-ENTRY | 俱乐部 tab 冷启动首载、断网首载的错误态视觉 |
| SC-DEREG-01 | 短信/微信二次确认、冷静期展示与撤回、到期后旧登录态被踢（与 AC1 #497 清单重叠） |
| SC-PRIV-01 | 微信原生 `onNeedPrivacyAuthorization` 弹窗；首启拒绝后各入口的降级表现 |
| SC-TPL-OLD-BACKEND | 对旧版后端（无 adopt-draft）真连时的文案与「去我的玩法核对」跳转 |
| SC-TPL-OLD-SAVE-ROLLBACK | 无（真事务回滚已有真库 IT） |
| SC-UI-DATE-COUNT | 真数据下各页渲染出的站数与日期肉眼一致（#306 记为 NEEDS_RUNTIME） |
| SC-UI-PROJECT-CARDS | 访客 / 成员 / 主理人三种身份真机点击各卡 |

## 未决（需要 owner）

1. **CX-16**：编号在仓库与 GitHub 上都没有定义。请提供私有清单里的卡义，本 lane 再补映射。
2. **SC-PRIV-01**：上面的「首启同意闸」是按编号推断的。如果卡义不同，请指出。
3. **CX-19**：按指令跳过（两套等级体系待产品决定）。
4. **BUG-AC3-1**：是否给 `/become-leader` 的 certImages 加服务端媒体校验。

## 本机验证

- 小程序：
  - 上表所列文件逐个 `node --test` 全绿；
  - 新增文件 `ac3-cx31-backend-slash-route-exists.test.js` 4/4；
  - 负控：把 `MerchantPrerequisiteServiceImpl` 的 `/pages/merchant/decor/index` 临时改成不存在的路径，2 条变红，已还原。
- 后端：`mvn -pl chengyinhub-admin test -Dtest=…`，13 组类共 156/156。
  - 负控：把 `TemplatePublishServiceImpl.deleteTemplateWithSync` 的归属判断改成 `if (false)`，`TemplateDraftDeleteOwnerAcceptanceTest` 1 条变红，已还原。

# RV2 复审：batch2 集成候选 #486（2026-10-22）

## RV2 re-review

- 复审对象：`integrate/closeout-batch2-20261022` @ `94e19fd1`（#486 当前 head），对 `master` @ `f0b43d62`。
- 基线：RV1 复审的提交 `2b089767`（见 #506 `review-batch2.md`）。本轮只看 `2b089767..94e19fd1` 的增量：11 个文件，+141 / −32。
- 方式：只读。没有在任何 PR 上推送、评论、批准、合并或关闭，没有连接任何数据库。
- 本地验证：在 `94e19fd1` 的临时 worktree 上运行 `mvn -pl chengyinhub-admin -am -Dtest=…` 跑了 3 个相关测试类，并做了负控（只回退生产代码，保留新测试）。

### 1 B1 / S1 结论

#### B1 俱乐部月历公开视角泄露仅成员主题的场次：**FIXED**

- 修复（`ca8da72e`，经 `46e3238a` 合入）：
  - `ClubCalendarMapper.xml:31-34`：公开分支的父主题子查询别名改为 `cms_topic`，并加上 `and <include refid="com.chengyinhub.business.mapper.CmsTopicMapper.clubMemberOnlyGate"/>`。判据和 `/api/club/topics` 共用同一段 SQL（`CmsTopicMapper.xml:549`），没有另写一套。
  - 观看者的 ID 一路传到 SQL：
    - `ApiClubCalendarController.java:66` 传 `viewer`（登录态派生，不接受客户端传入）。
    - `IClubCalendarService.java:17`、`ClubCalendarServiceImpl.java:40-46`、`ClubCalendarMapper.java` 新增 `@Param("viewerMemberId")`，名字和闸里的 `#{viewerMemberId}` 一致。
  - 治理者分支（`includeUnpublished=true`）不受这道闸影响。这与 RV1 的修复建议一致。
- 测试：
  - `ClubCalendarServiceTest#membersOnlyTopicSessionsAreHiddenFromNonMembersButShownToMembersOwnerAndGovernor`（`ClubCalendarServiceTest.java:163`）。这条测试在 H2 上跑真 SQL，同时加载 `CmsTopicMapper.xml`，所以跨命名空间的 include 是真的解析过的。覆盖的情况：
    - 看不到：非成员、已退出成员、viewer 为 null（fail-closed）。
    - 看得到：在册成员、主理人、主题作者、已付款报名者、治理者。
  - `ApiClubCalendarControllerTest#publicClubGivesOutsidersOnlyThePublicScope` / `#privateClubIsClosedToOutsidersButOpenToActiveMembers`：钉住控制器传的是登录态的 viewer。
- **负控**：只回退 `ClubCalendarMapper.xml` 到 `2b089767`，测试失败在 `:173`：`非成员:仅成员主题的场次名/时间都不能出现 ==> expected: <[102, 101]> but was: <[102, 109, 101]>`。所以这条测试不改实现就会失败。

#### S1 门店 AI 形象头像回执读错送检类型：**FIXED**

- 修复（`55ab4f37`，经 `2d77d32e` 合入）：`ProfileReviewService.java:153-156` 改为 `mediaState(BIZ_TYPE_MERCHANT_NPC, npc.getId(), sha256Hex(avatar.trim()))`。
  - 这和送检端对得上。`ApiMerchantController.java:848-854` 送检用的是 `(BIZ_TYPE_MERCHANT_NPC, npcId, avatar)`。inbox 写入时的 hash 是 `sha256Hex(mediaUrl.trim())`（`WxContentCallbackInboxServiceImpl.java:42`），口径一致。
  - 两个调用方（`npcSave:862`、`npcReview:879`）传入的 `NpcProfile` 都来自 `selectMerchantNpcList(merchantId)`，id 是从库里查出来的，归属不变。
- 测试：
  - `ProfileReviewServiceTest#merchantNpcPendingIsNotVisibleAndPresetNeedsNoMediaReview`：原来 `:162` 把错误的预期 `(MERCHANT_PROFILE, 7L)` 钉成了正确，现在改为 `(MERCHANT_NPC, 91L)`（`:163`）。
  - 新增 `ProfileReviewServiceTest#merchantNpcAvatarReceiptFollowsTheNpcSubmissionThroughPendingToApproved`（`:183`）。它覆盖：
    - 状态全程：未送检 → PENDING → APPROVED → REJECTED。
    - 「logo 恰好是同一张图且已过审」的干扰行不能被读到，用 `verify(never())` 钉住。
- **负控**：只把 `ProfileReviewService` 回退为 `(MERCHANT_PROFILE, merchantId)`，两条测试都失败：
  - `:194`：`还没送检 ==> expected: <NOT_SUBMITTED> but was: <APPROVED>`，正好复现 RV1 说的「同图读到 logo 结论」。
  - `:168`：`expected: <PENDING> but was: <NOT_SUBMITTED>`。

#### 本地运行结果（`94e19fd1`）

| 测试类 | 修复后 | 负控（回退生产代码） |
|---|---|---|
| `ClubCalendarServiceTest` | 6/6 通过 | 1 失败（B1 用例） |
| `ProfileReviewServiceTest` | 7/7 通过 | 2 失败（S1 两条） |
| `ApiClubCalendarControllerTest` | 5/5 通过 | — |

### 2 增量复审（`2b089767..94e19fd1`）

一级提交只有 4 个：`461a6ee5`（docs）、`46e3238a`（合入 #484 @ `ca8da72e`）、`2d77d32e`（合入 #477 @ `84c33fea`）、`94e19fd1`（docs）。

- **冲突解决**：用 `git show --remerge-diff` 检查，只有 `2d77d32e` 有一处，在 `chengyinhub-xcx/tests/unit/get-auth-matrix-contract.test.js`。
  - W10b 的 `db4f3850` 把这个保留契约还原到了合并基线，原来的 4 行改为写进 CY-HANDOFF。集成方保留了集成分支的版本。
  - 核对合并后的树：
    - `:171/173/175/176` 正是 CY-HANDOFF 要求的 4 行 MATRIX（`npc-chat`、`publish/simple`、`gerenziliao`、`merchant/decor/ai-npc`，均为 `required`）。
    - `:310` 是 `directCount, 70`，即 W10b 的 69 再加 W9 的 1。
    - `:315` 的 `MATRIX.length` 等式断言保留。
  - **没有放宽门禁**。
- **台账 / 基线**：增量里没有改 `scripts/uiaudit/*.json`、`tests/lint/ui-integrity-baseline.js`、`ci/`、`sql/`，也没有改任何小程序文件。`2b089767..94e19fd1` 在 `chengyinhub-xcx` 下的净 diff 为空，所以不需要再生成台账。
- **CY-HANDOFF**：本轮新增的交接只有上面这一处 get-auth-matrix，已在树上落地，见 `integration-batch2.md` 交接表新增的那一行。
- **测试是否被削弱**：增量里删除的测试行都是因为签名多了 `viewerMemberId`，相应改了参数（`anyBoolean()` → `anyBoolean(), any()`，`verify(...false)` → `verify(...false, OUTSIDER)`）。改完后断言更严，因为还核对了传入的是哪位 viewer。唯一改了预期值的是 S1 那一行，从错误值改为正确值。**无削弱。**
- **安全 / 隐私 / 资金**：增量不碰资金路径和迁移。B1 的闸使用的是**主题所属俱乐部**（`cms_topic.club_id`），不是月历所查的俱乐部（`a.club_id`）。所以合作俱乐部执行的仅成员主题，对执行方俱乐部的成员同样隐藏。这与 `/api/club/topics` 的口径一致，偏保守，不算问题。

**新发现：blocker 0 · should-fix 0 · nit 2**

- **N6 [nit]** `integration-batch2.md` 的 #484 段写的是「本集成分支的分片组合在 CI 上全绿」，依据是旧 head `2b089767` 的 run。文中记录的 `roam-comment-scan.test.js` 与 `subscribe-recipient-contract.test.js` 的临时目录竞态是 master 上原有的问题，在 #486 的新 head 上同样可能让 unit shard 13 偶发失败。如果出现，应按这个已知竞态处理（跑一次重跑确认），不要当成新回归。建议按文中的方案另开修复：把临时目录放到系统 tmp，或在遍历时跳过 `scan-comment-test-*`。
- **N7 [nit]** #486 的 PR 描述，在「纳入」列表和「落地的 CY-HANDOFF 行」两节里还没有加上 #484 `ca8da72e`、#477 `84c33fea` 的更新 head，也没有写 get-auth-matrix 的冲突怎么解决的。这些只记在 `integration-batch2.md` 第 16/17 行里。合并前建议把描述同步一下，只影响文档。
  - **已解决（15:26 UTC 复核）**：#486 描述现在已列出 `#484 ca8da72e · #477 84c33fea`，并写明了 W10b get-auth-matrix 交接的处理方式和 `94e19fd1` 的 CI 终态。

RV1 的 S2（活动合作者可写入任意会员）、N1–N5，以及第 3 节的 owner 待决事项，本轮都没有改动，状态不变。

### 3 CI（#486 @ `94e19fd1`）

最终状态通过 GitHub check-runs API 查询（本环境没有 `gh` CLI），查询时间 2026-10-22 约 14:45 UTC。

- 共 35 个 check run：**32 个 success、3 个 skipped、0 个失败**。3 个 skipped 是 Admin UI build、Admin UI E2E、SBOM，都是按变更区域跳过的。
- 关键检查全部 success：
  - Backend tests（run `39794837029`）
  - Funds MySQL IT 50/9/30（run `39794837131`）
  - Funds IT gate self-test
  - Followup 39 selected backend cases（run `39794837110`）
  - Migration safety gate、Migration layout gate
  - 20/20 个小程序 unit shard（含 shard 13，N6 所说的竞态这次没有触发）
  - Mini program static gates / static checks
  - Fast gates
  - gitleaks
  - isolated-authenticated-redis-recovery（run `39794837143`）
- **CI 全绿。**

### 4 建议

B1、S1 都已修复，并有会在回退实现时失败的测试；增量里没有新的阻断项。**#486 当前 head 的 CI 已全绿，可以合并**，前提是 owner 已对 RV1 第 3 节的待决事项（以及 S2 的口径）拍板。

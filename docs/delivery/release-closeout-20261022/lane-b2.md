# Lane B2 交付：本地 prelaunch 社区改动核对（实施文档一 §5.2，包 C 续）

- 分支：`feature/lane-b2-community-local-20261022`
- 基线：Lane B 分支 `feature/lane-b-community-20261022` 的 head `b8448277`（Draft PR #455，#377 接续）。Lane B 自身基于 master `063a6c3c`，2026-10-22 核对时 master 仍是这个 SHA。
- 本 PR 是叠在 #455 上的 draft PR（base = Lane B 分支）。Lane B 分支没有任何改动。
- 输入：只读的私有快照，共 8 条脏记录（6 条与 master 不同、2 条已一致），另有对照清单和 Runbook 包 C。本文不引用私有路径。

## 结论

本地增量里的五类修复，当前 master（含 #455）**已全部包含**：删除评论占位、回复父子可见性、Mapper 改动、评论幂等、详情页渲染。所以这次没有改产品代码，没有新 SQL，也没有新依赖。

唯一补上的是一条回归测试：`commentLookaheadDropsOnlyExtraRootWithItsWholeThread`。旧分支有这条用例，master 没有。它锁定的是评论根分页的预读行为：多取的那个根要连同整串子回复一起丢掉；`nextCursor` 指向最后保留的根；空页的 `nextCursor` 为 null。

## 方法

1. 为每个旧文件在 hengyin 全部分支历史里找差异最小的提交，作为它的基线。结果都落在 `feature/prelaunch-template-coupon-fixes-20261013` 上：
   - `138f1cd6`：Service、Service 测试、详情页 JS
   - `2eba8e82`：WXML、MyBatis 测试
   - `a11d35dc`：Mapper XML
2. 用「旧文件 − 基线」得到真正的本地增量，然后在当前代码里逐段查找对应实现。
3. 不整文件对比，也不拷贝旧文件。旧文件和 master 的其余差异都是 master 后来的演进（身份、revision、UNKNOWN 写入恢复、隐私、审核合同），这些全部保留。

## 8 条记录

| # | 记录 | 结论 | 证据 |
|---|---|---|---|
| 1 | `chengyinhub-system/.../service/impl/CommunityPostServiceImpl.java` | EQUIVALENT | 本地增量只有 `visibleCommentThreadRows`（删除祖先转占位并清空 body；删除叶子和整串都删除的线程不显示）以及 `comments()` 里对它的调用。当前代码里两处逐行一致。 |
| 2 | `chengyinhub-system/.../mapper/business/CommunityPostMapper.xml` | EQUIVALENT | 本地增量在 `selectComments` 里，共三处：<br>• `root_page` 中，DELETED 根只有在存在合法可见后代时才占位（后代同样过 block、mute 和作者审批过滤）；<br>• DELETED 行的 body 投影为 `''`；<br>• JOIN 限定 `c.post_id=#{postId}`，`lifecycle IN ('PUBLISHED','DELETED')`。<br>当前 XML 三处全部一致。 |
| 3 | `chengyinhub-admin/.../community/CommunityPostServiceImplTest.java` | EQUIVALENT + PORTED | 本地新增的 3 条用例已在 master 上：`deletedCommentAncestorsAreRedacted…`、`deletedLeavesAndEmptyDeletedThreads…`、`deletedPlaceholderCannotBeReportedByGuessingItsId`。<br>基线里的 `commentLookaheadDropsOnlyExtraRootWithItsWholeThread` 在 master 上缺失，已按当前 fixture 移植，见本 PR 的 `test:` 提交。 |
| 4 | `chengyinhub-admin/.../community/CommunityPostMapperMyBatisTest.java` | EQUIVALENT | 本地新增的 2 条用例已在 master 上：`deletedRootsKeepVisibleDescendantsWithRedactedAncestorsAndStableRootPaging`、`deletedRootsWithoutLegallyVisibleRepliesDoNotConsumePageSlots`。 |
| 5 | `chengyinhub-admin/.../service/CommunityPostCommentIdempotencyMySqlIT.java` | EQUIVALENT | 对照清单标记为与 master 一致（当前 blob `5026dfc5`）。<br>对应的生产修复（`a11d35dc`：重复键之后用 `selectCommentByAuthorRequestForUpdate` 做当前读，找回已提交的胜者；正文或帖子变了就返回 409）已在 master 上，单测 `duplicateCommentRecoversCommittedWinnerWithCurrentRead` / `duplicateCommentCurrentReadStillRejectsChangedBodyOrPost` 也在。 |
| 6 | `chengyinhub-xcx/pages/square/detail/index.js` | EQUIVALENT | 本地增量是 6 处占位守卫：回复提交、`msgClick`、关注、点赞、举报、删除。当前代码在这 6 个入口都有同样的条件（`deletedPlaceholder === true \|\| lifecycle === 'DELETED'`）。<br>写法按当前的处理器结构（如 `addReplyComment`）改写过，语义不变。 |
| 7 | `chengyinhub-xcx/pages/square/detail/index.wxml` | EQUIVALENT | `wx:key="id"`，以及 `wx:if` 占位「该评论已删除」 / `wx:else` 正常卡片，两项都已在 master 上。 |
| 8 | `chengyinhub-xcx/tests/unit/square-comment-deleted-placeholder.test.js` | EQUIVALENT | 对照清单标记为与 master 一致（当前 blob `38890b05`）。本地 `node --test` 结果 9/9 通过。 |

REJECTED：无。

## 范围外的说明（未移植）

旧测试文件里还有 23 个测试方法在当前代码里找不到同名方法：Service 测试 17 个，MyBatis 测试 6 个。另外缺失的还有 1 个辅助方法 `assertNoPostActionWrites`。这些方法都来自旧**基线分支**本身，不属于本地脏改动。涉及的主题是审核中编辑、创建回执终态、收藏、反馈重放、个人列表预读等，不在 §5.2 的五类修复范围内。master 可能已经用别的名字或别的结构覆盖了这些行为。建议在对应的旧 PR 结案任务里逐条核对，本 PR 不扩大范围。

## 验证

- 本地 JDK 21 / Maven：`-Dtest=CommunityPostServiceImplTest` 76/76 通过。
- 先红后绿：把 `comments()` 的截断临时改成 `subList(0, i + 1)`（多带一个根），新用例和原有的 `deletedCommentAncestors…` 一起失败；恢复后全部通过。
- `node --test tests/unit/square-comment-deleted-placeholder.test.js`：9/9 通过。
- 完整 Java 测试以 draft PR 上的 GitHub CI 为准。
- 没有运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`，也没有改 C00 保留文件。没有重新生成 ledger 或 baseline（本 PR 不改小程序文件，计数不变）。

## 需要人工在微信开发者工具里检查

1. 删除一条根评论，且它下面有别人的正常回复：应显示「该评论已删除」占位，子回复仍可见，层级正确。占位不能点赞、回复、举报、关注、删除。
2. 删除一条没有可见回复的根评论：不显示占位，也不占用分页名额。
3. 选好回复对象、输入草稿后，对象被删除并刷新：提交时提示「回复目标已变化，请重新选择」，草稿保留。
4. 被屏蔽或静音用户的回复挂在已删除根下：查看者看不到占位，也看不到这些回复。
5. 弱网下重复点击发送评论：只生成一条评论。

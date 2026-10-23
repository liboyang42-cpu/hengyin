# BUG-COMM 修复交付（社区帖文 / 点赞 / 分享 / IM）

分支：`fix/bug-comm-20261023`（基于 master `b84ed673`）

## 汇总

| 分类 | 数量 | ID |
|---|---|---|
| FIX | 7 | A:P0-56, A:P0-55, B:P2-011, A:P0-82, B:P1-023, C:U-050, C:U-142 |
| OWNER | 3 | A:P0-05, A:P0-77, C:U-147 |
| NOT_REPRODUCIBLE | 3 | A:P0-61, B:P1-038, C:U-044 |
| DEFER | 11 | B:P1-067, B:P2-014, B:P2-041, C:U-027, C:U-037, C:U-042, C:U-047, C:U-051, C:U-074, C:U-162, C:U-172 |

## 明细

| id | triage | what changed (file) | test | notes |
|---|---|---|---|---|
| A:P0-56 | FIX | `ApiCreativeSquareController.java` `/api/creativesquare/like`：同态度再点=取消，赞↔踩改写现有行（原来一律删除，已赞点踩会变成无态度）；历史重复行收敛为一行；回包带 `isLiked`(0/1/2) | `ApiCreativeSquareLikeStateTest`（4 例） | 已切换到新社区接口的客户端不走此端点；旧端点仍在 `legacy-writes-enabled=true` 下可写 |
| A:P0-55 | FIX | 新迁移 `chengyinhub-admin/sql/migration_20261023_creative_square_like_unique.sql`：`ums_creative_square_like(member_id,data_id)` 唯一键，information_schema 幂等；登记 `ci/migration-dependencies.json` | 迁移门禁（layout / safety / ledger） | 与 `cms_topic_like` 先例一致：先断言无重复，有重复则迁移失败、需人工导出核对后再跑，不自动删数据 |
| B:P2-011 | FIX | 同 P0-55 | 同上 | 同一缺陷的 A 栈描述 |
| A:P0-82 | FIX | `ClubPostMapper.xml` 新增 `selectClubPostPage`（置顶 / 非置顶 id 倒序 + `beforeId` 游标 + limit）；`ClubPostServiceImpl.selectClubPostPage`；`ApiClubController /post/list` 接收 `limit`(≤50) / `beforeId`，回包仍为数组；小程序 `pages/club/detail/index.js` 首页 limit=20、触底续页、回读保留已加载深度、过期续页丢弃 | `ClubPostPageMyBatisTest`(H2)、`bl-197-club-post-like-report.test.js` 新增 2 例；`ApiClubPostPrivacyProjectionTest` / `ApiClubTopicVisibilityTest` 改为桩新方法 | 不传 limit 的旧客户端拿到置顶 + 最新 50 条；逐条 isLiked 查询仍在，但已被页大小限住；chat/index.js 拆分属重构，未做 |
| B:P1-023 | FIX | `ApiClubController /post/like` 接收可选目标态 `liked`：已是目标态则不写库（重试幂等）；不带时保持旧切换语义；`ClubPostLike` 增请求字段；小程序点赞带 `liked: 目标态` | `ClubPostWriteLifecycleTest` 新增 2 例；xcx 新增 1 例 | 没有加 request_id 列：目标态幂等已能覆盖重试/重复请求，不需要 DDL |
| C:U-050 | FIX | 新 `chengyinhub-xcx/utils/square-share.js`；`pages/square/detail/index.js`、`pages/square/list/index.js` 转发卡：仅公开且已发布的帖子带正文摘要 / 首图 / 详情路径，私密 / 仅关注者 / 社区可见 / 未发布帖子退回广场通用卡 | `square-share-privacy.test.js`（3 例）；`community-post-cutover-contract`、`c01-home-search-state-contract` 跟随调整（分享路由改在 util 中断言 / 补 require 桩） | share_count 上报未做（统计口径，非上线阻断） |
| C:U-142 | FIX | `subpackageB/pages/im/chat/index.js`：轮询拿到新消息时先读消息流滚动位置，距底部 >120px（用户在看历史）不再强拉到底；不改 WXML | `im-chat-poll-scroll-follow.test.js`（2 例） | 「有新消息」提示条属 UI 新增，留待上线后 |
| A:P0-05 | OWNER | 无 | — | 快照已由服务端 `selectReferenceSnapshot` 派生，且只认公开 / 作者本人可见对象，客户端快照不入库，未见泄露路径。三级隐私分级与 CHECK 属产品规则，需决策 |
| A:P0-77 | OWNER | 无 | — | FOR_YOU 理由码、TRENDING 时间衰减、payment_success 权重、打分任务默认暂停，均为推荐产品规则，需决策 |
| C:U-147 | OWNER | 无 | — | 被拦截消息整条隐藏 vs 保留并加标记属审核产品规则；当前隐藏为保守做法 |
| A:P0-61 | NOT_REPRODUCIBLE | 无 | — | 投票消息(msgType=4)只在群聊产生（`ImPollServiceImpl` 需群成员身份），小程序 `im/list` 的 `tabOf` 明确不展示群会话(type=4)；即便出现，内容 `[投票]` 也按文本渲染。DDL 注释补 4 属文档整洁 |
| B:P1-038 | NOT_REPRODUCIBLE | 无 | — | 两个 `/ending` 位于不同路径，参数按名绑定，顺序不影响调用；改名属重构 |
| C:U-044 | NOT_REPRODUCIBLE | 无 | — | `mylike.js` 两张锁表分别只服务 topic / post 两条链路，按 id 不会互相串；合表属重构 |
| B:P1-067 | DEFER | — | — | GPS=5 前后端取值一致，仅为魔法值整理 |
| B:P2-014 | DEFER | — | — | 草稿 key 已用 `square_post_<memberId>` 前缀，没有实际碰撞；属命名空间整理 |
| B:P2-041 | DEFER | — | — | AI JSON 解析三处实现行为相同，抽公共方法属重构 |
| C:U-027 | DEFER | — | — | 各处 key 风格自洽，无错误读取；属命名统一 |
| C:U-037 | DEFER | — | — | command_type 只由服务端字面量写入，无外部输入；加枚举 / CHECK 属整理 |
| C:U-042 | DEFER | — | — | MEMBER 只出现在可选项白名单，快照查询无 MEMBER 分支，提交时稳定返回 409，不会触发 DDL CHECK 报错；小程序不用该选项接口 |
| C:U-047 | DEFER | — | — | 未知枚举值目前按 400 拒绝，不会崩溃；UNKNOWN 兜底属健壮性整理 |
| C:U-051 | DEFER | — | — | 收藏来源仅用于统计归因，不影响功能 |
| C:U-074 | DEFER | — | — | 新环境按「base + 全部迁移」建库是既定流程；回填 base 与快照头部「勿改」约定冲突，属流程决策 |
| C:U-162 | DEFER | — | — | 创作期校验加强；运行时对不合法条件按不通过处理、走兜底结局，不会崩；共享校验器属重构（剧情域） |
| C:U-172 | DEFER | — | — | 无前后端模板不一致的证据；对账接口属新功能 |

## 验证

- Java：`ApiCreativeSquareLikeStateTest`、`ClubPostPageMyBatisTest`、`ClubPostWriteLifecycleTest`、`ApiClubPostPrivacyProjectionTest`、`ApiClubTopicVisibilityTest`、`ClubPostReviewVisibilityMyBatisTest` 共 32 例全部通过（`LANG=C.UTF-8 mvn -pl chengyinhub-admin test -Dtest=...`）。
- 小程序：受影响单测 512 例全部通过；`npm run lint` 和 `lint:requestbody` 通过（0 error）；`ci/silent-error-gate.js`、`ci/api-existence-gate.js` 通过；本地迁移布局门禁通过。
- 动作台账（C00 保留，未提交重建结果）：`controlCount` 5009 → 5009（不变）；重建后有 262 条控件的源码 digest 变化（club/detail、square、im/chat 的 JS 改动）。因此 `action-ledger.test.js` 中「release 前重建 inventory」一例在 master 重建台账之前会是红的。

<!-- CY-HANDOFF -->
C00：请在合并后重建 `scripts/uiaudit/action-ledger.json`（`npm run audit:actions:update`），预期只有 digest 变化、控件数仍是 5009。本 lane 没有改动 C00 保留文件。
<!-- /CY-HANDOFF -->

## CI

- 运行 ID：见 PR 检查（下文补充）。

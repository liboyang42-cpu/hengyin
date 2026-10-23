# Doc2 O-08 / O-10 — 私密位置读路径审计 + 候补/群发回执幂等核验

- 基线：`master` @ `c225b462`；分支 `feature/doc2-o8-o10-20261022`
- 方法：先核验后修复。每条读路径都给出判定依据（file:line / SQL 片段）；只修有测试证明的泄漏，修复前后都跑过同一测试（负控：撤掉修复，测试变红）。
- 未运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`；未重新生成台账；无新依赖、无新 DDL；不改小程序文件。

## O-08 私密位置引用保护

### 已修复的泄漏（5 条）

| # | 读路径 | 泄漏形态 | 修复 | 证明测试 |
|---|---|---|---|---|
| L1 | `GET /api/play/nodes?activityId=` | 未报名、非带队的旁人传任意 activityId，可拿到草稿/下架/仅成员可见主题全部节点经纬度与地址 | 未报名且非带队时先过 `selectCmsTopicDetailById(topicId, viewer)`（在架 + 圈层闸或作者本人，且过 `clubMemberOnlyGate`），不可见按「活动或主题不存在」处理 | `O08PrivateLocationReadPathTest.playNodes*`（3 条） |
| L2 | `POST /api/activity/info` | 挂在仅成员可见主题下、场次自身无 `club_id` 的场次，非成员可读坐标/地址（控制器社群门卡只看场次 `club_id`） | `CmsActivityServiceImpl.selectCmsActivityDetailById`：这类场次对非成员、非场次作者返回 null；有 `club_id` 的仍走既有门卡（UX 不变） | `activityDetail*`、`clubSessionKeeps*` |
| L3 | `GET /api/roam/hangout/nearby`、`/api/roam/entry/nearby`（地图图层） | `selectNearbyTopics` 和 `selectNearbyActivities` 都没挂 `clubMemberOnlyGate`，仅成员可见主题的首个节点坐标和场次坐标会出现在非成员的地图上 | 主题图层：SQL 内 `exists (... clubMemberOnlyGate)`；场次图层：新增 `CmsTopicMapper.selectClubMemberOnlyHiddenTopicIds`（同一判据）后在 service 过滤。判据只有一份，不跨文件 include（单独加载 RoamHangout/CmsActivity 映射的测试不受影响） | `roamMapDrops*`；真库 `O08MemberOnlyTopicLocationMyBatisTest`（陌生人/未登录/已退出看不到；成员/主理人/作者/已付款玩家看得到） |
| L4 | `POST /api/creativesquare/list`（匿名可达）、`/info` | 旧广场视图下发精确 `address/longitude/latitude`。社区平台对他人只投影城市，作者「撤回位置/改私密」不会回写 `ums_creative_square`，所以撤回过的坐标仍然公开可读 | 旧读面只给作者本人保留精确位置，其他人（含匿名）一律置空。现行小程序已改走 `/api/v1/community`（`utils/community-post-api.js`），不消费这些字段 | `legacySquare*`（3 条） |
| L5 | `POST /api/club/home` 的 `nearby` | 发现列表下发整行 `Club`（含 `address`），没过 `canViewClub`。关了「公开可见」的俱乐部会把地点漏给非成员 | 发现列表加 `canViewClub(c)`（与 `/list`、`/detail` 同一道闸） | `clubHome*`（2 条） |

负控记录：撤掉 5 处生产改动后，`O08PrivateLocationReadPathTest` 有 7 条断言变红；去掉 `selectNearbyTopics` 里的 `exists(clubMemberOnlyGate)` 后，真库测试变红（陌生人看到 `[11, 12]`）。

### 已核验、有闸的读路径（未改）

| 类别 | 路径 | 闸 |
|---|---|---|
| 社区帖文 | `/api/v1/community/posts/{id}`、`/feeds/{mode}`（含 NEARBY / 关键词搜索）、`/me/bookmarks` | `CommunityPostMapper.xml` `visibilityPolicy`（作者本人，或 PUBLISHED 且 PUBLIC/关注者/俱乐部成员，并过拉黑/屏蔽）。`poi_name`、`approximate_geohash` 只给作者；他人只拿 `city_code`。搜索走 `search_document.visibility_state='VISIBLE'`，撤回位置时置 HIDDEN |
| 社区帖文 | `/drafts`、`/me/posts`、`/revisions` | `author_id = principal`；revisions 在 service 层另验作者 |
| 社区引用 | `/reference-options/{type}` 与帖内引用快照 | ROUTE 只给作者；TOPIC/ACTIVITY 只取已发布数据 |
| 俱乐部 | `/api/club/list`、`/detail`、`/post/list`、`/post/feed` | `canViewClub` + `PublicClubVO`（不含地址）；俱乐部帖文没有坐标 |
| 漫游 | `/api/roam/session`、`/tiles`、`/tiles/page`、`/stamp/list`、`/api/track/drafts`、`/to-topic` | 只看本人 `member_id`，有保留期裁剪 |
| 漫游 | `/api/roam/nearby-runners` | `presence_visible=1`、活跃会话、5 分钟 TTL、需同意回执；坐标保留 2 位小数 |
| 主题 | `/api/topic/info-to-user`、`/api/topic/list`、`/like_list`、`/edit-detail`、`/api/team/nearby` | `publicTopicListWhere` / `selectCmsTopicDetailById` 均含 `clubMemberOnlyGate`；编辑详情校验作者 |
| IM | `/api/im/messages`、`/conversations` | 会话成员；路线卡经 `projectRouteMessage` 投影（只出已发布、非仅成员可见，不带坐标） |
| 分享 | `GET /api/roam/share/snapshot`（匿名 token） | 作者主动发布、30 天过期、可撤回；轨迹/POI 经 `RoamSharePrivacyService` 写读两侧都取 3 位小数 |
| 推荐 | `/api/recommendation/list` | 不下发坐标（只有标题/图） |
| 漫游 POI | `/api/roam/pois` | `status=1` + 城市节点内容已审 |

### 已登记、未改（不属私密内容或需产品裁决）

- `/api/topic/list-to-merchant`：商家招募面，会看到下架/仅成员主题的节点位置。这是招商用途，涉及的是门店/路线地点，不是用户私密位置。是否收窄要由产品裁决。
- `/api/map/nearby`：返回报名商家门店坐标，审核态过滤由调用方选择。这是商户营业地址，不是用户私密内容。是否默认只出已审，由产品裁决。
- `/api/roam/shop/visitors`：商家卡头像堆，展示 30 天内到店者的头像（无 id/昵称），没有 `presence_visible`/同意校验。这是社交证明功能，但能凭头像推断「某人去过某店」。是否挂 presence 同意，需要产品裁决。
- `/api/roam/stamp/received`、`/exchange`：`RoamStampListVO` 带对方印章的 `lat/lng/geohash7`。现在创建时从不写这些列，所以恒为 null，属于潜在风险。如以后写入，需要在投影层剥离。
- `/api/club/home` 的 `nearby` 仍下发整行 `Club`，包括公开俱乐部的 `phone`/`certImages`。这不是位置问题，不在 O-08 范围；建议后续改用 `PublicClubVO`（需同步核对商户视角按 `address` 回退的同城过滤）。

## O-10 排期候补与群发回执

结论：现有实现在下列竞态上已经幂等、终态可回读，**没有发现需要改生产代码的缺口**。新增回归测试把这些行为钉住：

| 场景 | 现有保证 | 测试 |
|---|---|---|
| 重复晋级（重复调度/并发维护） | series → club → ticket → waitlist 的 FOR UPDATE 锁序串行化；第二次当前读到库存 0 或无 WAITING 队首，直接 no-op；`offerWaitingCas` 版本 CAS | `O10WaitlistTerminalIdempotencyTest.duplicatePromote*` |
| 取消 vs 认领 | CLAIMED 时取消返回 409，不 CAS、不回补库存 | `cancelAfterOfferWasClaimed*` |
| 取消 CAS 失败 | 409，事务回滚，不回补、不晋级 | `cancelLosingCasRace*` |
| 取消 vs 过期（任一先到） | 后到者 no-op（不二次回补、不二次通知）；`myStatus` 回读 EXPIRED / CANCELLED，不带 token | `cancelAfterExpiry*`、`expiryAfterCancel*`、`cancelledWaitingEntry*` |
| 终态回读归属 | `myStatus` 对他人记录 403 | `anotherMembersTerminalEntry*` |
| 群发重放（串行/并发） | `selectCampaignByRequest` 重放；并发输家吃 `uk_club_event_campaign_request` 唯一键后回读赢家，不建回执、不发送；同 requestId 不同内容返回 409 | `O10BroadcastReceiptIdempotencyTest.*Replay*` |
| 一人一回执 | 受众去重（LinkedHashSet）+ `uk_club_event_delivery_recipient`；已 SENT 的回执重投、迟到的失败回执都是 no-op；手动重试只派发未成功的回执 | `duplicatedAudienceRows*`、`redelivering*`、`lateFailureReceipt*`、`manualRetryOnly*` |

旧 PR #29（E01）的回归测试已经在 master 上（`ClubWaitlistServiceImplTest`、`ClubEventNotificationServiceImplTest` 等），本次未改动。

## 本地验证

- 本会话本地跑过（MariaDB 10.11 作为 MySQL fixture）：`O08PrivateLocationReadPathTest` 12/12、`O08MemberOnlyTopicLocationMyBatisTest` 2/2、`O10WaitlistTerminalIdempotencyTest` 8/8、`O10BroadcastReceiptIdempotencyTest` 7/7；负控见上。
- 权威结果以本 PR 的 GitHub CI 为准。

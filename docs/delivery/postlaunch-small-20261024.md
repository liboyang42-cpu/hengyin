# 上线前小问题快修（2026-10-24）

分支 `fix/postlaunch-small-20261024`（基于 master `05c91471`）。每项都先在 master 上复核，再决定改不改。

| item | status | files | test | notes |
|---|---|---|---|---|
| S1 订单状态标签用服务端时间（U-033） | FIX | 后端 `CmsRegistration.java`（瞬态 `serverNow`）、`ApiRegistrationController.java`（`/list`、`/my-joined`、`/info` 每条带 `serverNow`）；小程序 `utils/order-status.js`（`orderClockNow`）、`subpackageMember/components/cy/profile/order-preview.js` | `ApiRegistrationServerNowTest`（3 例）；`tests/unit/order-status-server-clock.test.js`（5 例，含「手机时钟快一天」负控） | master 复核：三个读接口都**没有**下发服务端时刻或服务端状态，标签只能按 `Date.now()` 算。现在 `summarizeOrderState` 没拿到显式 `now` 时先读 `row.serverNow`，没有才用手机时钟。C00 保留文件 `request-client.js`、`scene-member-order-detail/index.js` **没改**：订单详情本来就不传 `now`，自动吃到 `/info` 的 `serverNow`。支付/退款逻辑没碰。 |
| S2 首页编辑精选空态时隐藏「为什么看到」 | ALREADY_FIXED | —（只加了测试） | `tests/unit/home-reco-why-button-empty-state.test.js`（3 例） | master `pages/index/index.wxml:65` 的按钮已有 `wx:if="{{recoHero}}"`；空态（`:74`）和失败态（`:70`）都只在 `!recoHero` 时出，三者互斥。`_buildRecoHero` 列表为空时会把 `recoHero` 置 null。测试把这条互斥锁住。 |
| S3 动态字符串列表 `wx:key="*this"`（P1-084） | FIX | 新增 `utils/wxs/list-key.wxs`；`pages/play/index.wxml`（章节对白 `item.lines`、规则行 `hero.node.howto`、提示 `shownHints`、7/30 天行动），`pages/play/components/playkit-dailysign/index.wxml`（签文 `lines`），`components/cy/theme-records/index.wxml`（`card.exhibits`），`components/cy/scene-settings-deregister/index.wxml`（`blockers`） | `tests/unit/wx-key-dynamic-string-lists.test.js`（3 例，已改列表回退成 `*this` 即红） | 这几处都是作者写的或服务端下发的文本，可能同文重复（同一句对白、「拍一张合照」写两行）。改法是给每项预算一个下标 key 字段（`{key:'k'+i,text}`），模板改为 `wx:key="key"` 并读 `item.text`，样式不动。星级、刻度、拼图块、日期滚轮、静态 tab 这类静态装饰列表刻意没动。 |
| S4 分享回执 + 章节块流回落埋点（U-171 / P1-021） | FIX | `utils/analytics.js` 与 `ApiAnalyticsController.java` 两侧白名单都加了 5 个事件；`pages/roam/index.js`（`shareTo` 各渠道 `.then(receipt)`）；`pages/square/list/index.js`、`pages/square/detail/index.js`（转发 success/fail）；新增 `pages/play/utils/chapter-blocks-health.js`，接到 `pages/play/index.js` 的 `buildChapter` / `buildChapterCards` | `tests/unit/analytics-share-receipts-chapter-blocks.test.js`（6 例，含真调 `shareTo` 的行为测）；`analytics-whitelist-crossstack.test.js` 仍绿 | 事件：`roam_share_complete` / `roam_share_fail`（properties 只有 `channel`∈square/album/moments/xhs，`reason`∈cancelled/blocked/failed）、`square_share_complete` / `square_share_fail`（只有 bizId）、`chapter_blocks_parse_failed`（`bizType=chapter`、章节 id、`reason`∈unparsed_string/not_array/bad_block，同章同因只报一次）。不带任何正文、文案或 PII。⚠️ 广场转发的 success/fail 只有老基础库会回调，新基础库不回调，回执是尽力而为。⚠️ 服务端 `ChapterBlockReadSupport` 解析库里坏 `blocks_json` 时，回落后下发的是合法数组，客户端看不出来；这条路径的观测仍是服务端 WARN 日志「章节 blocks 解析失败，按未物化数据兜底」。新埋点覆盖的是客户端拿到的块流形状不对的情况。 |
| S5 广场「当前账号未开放社区」 | CONFIG（行为不改） | —（只加了测试） | `CommunityPostLaunchConfigTest`（4 例，钉住键名和上线配置的效果） | 来源链：`pages/square/list` → `app.canUseCommunity(false)` → `globalData.features.communityPostRead` ← `POST /api/config/features` ← `CommunityPostFeatureGate.canRead(memberId)` ← sys_config。这是**刻意的灰度闸**，不是 bug：没有缺默认值，键名也没写错，已有测试 `defaultsClosedAndRequiresPilotMembership` 锁定「默认关」，也没有任何 migration 预置这几个键。**生产上新普通玩家现在会看到「当前账号未开放社区」**，除非 owner 配置下表三项。 |

## S5：owner 上线要配的 sys_config（后台 系统管理 → 参数设置）

| 参数键名 `config_key` | 值 | 作用 |
|---|---|---|
| `feature.flag.communityPostRead` | `true`（或 `1`） | 广场可读 |
| `feature.flag.communityPostWrite` | `true`（或 `1`） | 可发帖（含漫游「发到广场」） |
| `feature.flag.communityPostPilotMembers` | `*` | 全量放开；也可以填逗号分隔的会员 id 做白名单灰度。**为空时即使上面两个开关是 true 也全关** |

- 只开两个开关、不配 `PilotMembers`，仍然全关。测试环境「普通玩家看不到」大概率就是这个原因，或者测试库里没有这几个键。
- `*` 时未登录访客可读、不可写；登录玩家可读可写。
- `selectConfigByKey` 走 Redis 缓存。用后台「参数设置」改会自动刷新；直接改库要再点「刷新缓存」。小程序每次进广场页都会重新拉 `/api/config/features`，不用重新发版。

## 集成与台账

- 动作台账漂移（WXML 行号和 digest 变了），已按要求运行 `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write`。**controlCount 5103 → 5103（无变化）**，所以 `tests/unit/action-ledger.test.js` 的冻结计数不用改。
- 没碰的：支付/退款、鉴权/RBAC/JWT/Redis 配置、既有 migration、C00 保留文件；没加新依赖，也没改样式。

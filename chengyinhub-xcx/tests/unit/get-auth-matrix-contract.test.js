const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { maskJsNonCode } = require('../../../ci/api-existence-gate.js');

const XCX_ROOT = path.resolve(__dirname, '../..');
const SECURITY = path.resolve(
  XCX_ROOT,
  '../chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/SecurityConfig.java'
);
const CLUB_LEAD_CONTROLLER = path.resolve(
  XCX_ROOT,
  '../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubLeadController.java'
);
const ROAM_CONTROLLER = path.resolve(
  XCX_ROOT,
  '../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRoamController.java'
);
const PLAY_PROGRESS_CONTROLLER = path.resolve(
  XCX_ROOT,
  '../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java'
);
// W8 起控制器只把 currentMemberId() 交给应用服务，会员/游玩事实校验在应用服务内完成。
const PLAY_PREFERENCE_APPLICATION = path.resolve(
  XCX_ROOT,
  '../chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayPreferenceApplication.java'
);
const PLAY_PLAYER_QUERY_APPLICATION = path.resolve(
  XCX_ROOT,
  '../chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayPlayerQueryApplication.java'
);

// GET调用点显式权限清单；新增调用逐条复核。static = /profile/** 已公开的平台相册元数据。
// optional = 后端允许匿名，但登录时会按 uid 返回个人态，所以仍使用默认认证；
// required = 后端 anyRequest().authenticated()，绝不能 auth:false。
const MATRIX = [
  // Team board reads retain authenticated access; detail additionally enforces active team membership.
  ['pages/team/detail/index.js', "url: '/api/orient/team/detail'", 'required', 'member', 'active-team-member'],
  ['pages/team/detail/index.js', "url: '/api/orient/team/ranking'", 'required', 'member', 'global-authenticated-read'],
  // 2026-10-28 L6 本场招募:我的队伍按场次读公开队伍(OrientTeamBoardServiceImpl.lobby requireLogin,只列公开招募中的队伍)。
  ['subpackageRoam/my-teams/index.js', "url: '/api/orient/team/lobby'", 'required', 'member', 'global-authenticated-read'],
  // 契约 §1.3 / §4:主题详情开局前准备读 /api/play/setup(requireLogin,只读已发布主题);活动详情联系主办读 /api/play/contact(requireLogin,只回俱乐部名称与电话)。
  ['pages/topic/index/index.js', "url: '/api/play/setup'", 'required', 'member', 'public-detail+viewer-personalization'],
  ['components/cy/event-attendance/index.js', "url:'/api/play/contact'", 'required', 'member', 'public-detail+viewer-personalization'],
  ['subpackageMember/orient-score/index.js', "url: '/api/play/orient/session/current'", 'required', 'member', 'self-account'],
  ['subpackageMember/orient-score/index.js', "url: BASE + '/session/'", 'required', 'member', 'self-account'],
  ['subpackageMember/orient-score/index.js', "url: BASE + '/receipt'", 'required', 'member', 'self-account'],
  ['subpackageMember/character/index.js', "url: '/api/play/character'", 'required', 'member', 'self-account'],
  // 原发送结果只读恢复：本人请求回执，且服务端再次校验当前发布主体/岗位；两端均保留默认认证。
  ['pages/coop/invite/index.js', "url: '/api/coop/invite/request'", 'required', 'member/merchant-operator/club-owner', 'self-account'],
  ['pages/coop/invite/index.js', "url: '/api/coop/invite/requests'", 'required', 'member/merchant-operator/club-owner', 'self-account'],
  ['subpackageMember/components/cy/coop-invite-sheet/index.js', "url: '/api/coop/invite/request'", 'required', 'member/merchant-operator/club-owner', 'self-account'],
  ['subpackageMember/components/cy/coop-invite-sheet/index.js', "url: '/api/coop/invite/requests'", 'required', 'member/merchant-operator/club-owner', 'self-account'],
  ['subpackageRoam/nearby/index.js', "url, method: 'GET', auth: false", 'static', 'visitor/member', 'public-profile-gallery'],
  ['subpackageMember/components/cy/scene-roam-poi-detail/index.js', "url: '/profile/' + key.replace", 'static', 'visitor/member', 'public-profile-gallery'],
  // 实际作者页面的受控requestOwnMedia不属于req简写；五个caller逐个归真实本人资产。
  ['pages/privacy/index.js', "this.requestOwnMedia('/api/media/asset/list', 'GET'", 'required', 'author', 'owned-media-asset'],
  ['pages/privacy/index.js', 'loadOlderMedia() {', 'required', 'author', 'owned-media-asset'],
  ['pages/privacy/index.js', 'checkOwnMedia(event) {', 'required', 'author', 'owned-media-asset'],
  ['pages/privacy/index.js', 'previewOwnMedia(event) {', 'required', 'author', 'owned-media-asset'],
  ['pages/privacy/index.js', 'copyApprovedMedia(event) {', 'required', 'author', 'owned-media-asset'],
  // 媒体上传重试先回读自己本次pending asset：ApiMediaAssetController.status→owned严格作者uid。
  ['utils/transport/upload-client.js', "sender({ url: '/api/media/asset/' + pendingId, method: 'GET'", 'required', 'author', 'owned-media-asset'],
  // U04：本人主题历史、结果卡与四处重玩许可/新局核对；默认认证，服务端按登录会员校验归属。
  ['utils/theme-records.js', "req('/api/play/theme/history'", 'required', 'member', 'self-member-history'],
  ['utils/theme-records.js', "req('/api/play/theme/card'", 'required', 'member', 'self-member-history'],
  ['utils/theme-records.js', "req('/api/play/theme/state', 'GET', { topicId: view.topicId", 'required', 'member', 'self-member-history'],
  ['utils/theme-records.js', "req('/api/play/theme/state', 'GET', { topicId: old.topicId,", 'required', 'member', 'self-member-history'],
  ['utils/theme-records.js', "req('/api/play/theme/state', 'GET', { topicId: old.topicId ||", 'required', 'member', 'self-member-history'],
  ['utils/theme-records.js', "req('/api/play/theme/state', 'GET', { topicId: this._themeReplayAttempt.topicId", 'required', 'member', 'self-member-history'],
  // 共享控制台按本场关联俱乐部 EVENT_OPERATE 校验，保留默认认证。
  ['pages/club/topic-detail/plan-fallback.js', "url: '/api/game/session/shared/control'", 'required', 'club/EVENT_OPERATE', 'club-owned-activity'],
  ['pages/club/topic-detail/plan-fallback.js', 'loadPlanFallback() {', 'required', 'club/EVENT_OPERATE', 'club-owned-activity'],
  ['pages/publish/utils/publish/gameplay-support-matrix.js', "url: '/api/play/theme/support-matrix'", 'required', 'author', 'global-authenticated-read'],
  // 俱乐部直付应收只读：商家岗位 FINANCE_READ 派生店主，俱乐部按当前主理人归属校验。
  ['components/cy/club-direct-ledger/index.js', "url: scope.kind === 'club'", 'required', 'merchant/club', 'owned-merchant-or-club-receivable'],
  ['utils/deregister-flow.js', "sendUiStateRequest(app, '/api/user/deregister/status'", 'required', 'member', 'self-account'],
  ['utils/deregister-flow.js', "sendUiStateRequest(app, '/api/user/deregister/status', { method: 'GET',", 'required', 'member', 'self-account'],
  ['pages/square/list/index.js', "url: '/api/official/events'", 'optional', 'visitor/member', 'public-list+viewer-personalization'],
  ['components/cy/scene-roam-task-list/index.js', "url: '/api/official/events'", 'optional', 'visitor/member', 'public-list+viewer-personalization'],
  ['components/cy/scene-roam-task-list/index.js', "url: '/api/official/my-events'", 'required', 'member', 'self-registration'],
  ['pages/activity/official-inbox/index.js', "url: '/api/official/v2/party-inbox'", 'required', 'member', 'self-party'],
  ['pages/merchant/coop-center/index.js', "url: '/api/official/events'", 'optional', 'visitor/member', 'public-list+viewer-personalization'],
  ['pages/coop/list/index.js', "url: '/api/official/merchant-invites'", 'required', 'merchant', 'self-merchant'],
  // 已认证主题 owner / 商家 PROJECT_MANAGE 派生 owner，仅查该主题和指定商家的合法未占用站点。
  ['pages/coop/invite/index.js', "url: '/api/coop/supply-stations'", 'required', 'topic-owner/merchant-PROJECT_MANAGE', 'owned-topic-target-merchant-stations'],
  ['pages/activity/list/index.js', "url: '/api/official/can-publish'", 'required', 'publisher', 'self-capability'],
  ['pages/activity/list/index.js', "url: '/api/official/events'", 'optional', 'visitor/member', 'public-list+viewer-personalization'],
  ['pages/activity/list/index.js', "url: '/api/official/my-events'", 'required', 'member', 'self-registration'],
  ['pages/activity/official-mine/index.js', "url: '/api/official/my-published'", 'required', 'publisher', 'self-publisher'],
  // CL4 主办控制台:publish-check 由后端 canPublish(白名单)+ canReadControlledEvent(本场主办/协办)双重校验,
  //   非主办一律拒绝;本页与详情页只拿它判断「是否显示主办控件」,不接受前端传身份。
  ['pages/activity/official-organizer/index.js', "url: `/api/official/v2/events/${this._id}/publish-check`", 'required', 'publisher', 'controlled-event-operator'],
  ['pages/activity/official-organizer/index.js', "url: '/api/official/events/' + this._id", 'optional', 'visitor/member', 'public-detail+viewer-personalization'],
  ['pages/activity/official-organizer/index.js', "url: '/api/official/my-published'", 'required', 'publisher', 'self-publisher'],
  ['pages/activity/official-detail/index.js', "url: `/api/official/v2/events/${this.data.id}/publish-check`", 'required', 'publisher', 'controlled-event-operator'],
  // CL4 N5:NPC 对话导出,身份只取登录态(getAppUserId),端点不收任何用户 ID。
  ['subpackageMember/components/cy/npc-chat/index.js', "url: '/api/ai/npc/chat/data/export'", 'required', 'member', 'self-account'],
  ['pages/activity/official-detail/index.js', "url: '/api/official/events/' + id", 'optional', 'visitor/member', 'public-detail+viewer-personalization'],
  ['pages/searchmap/index.js', "url: '/api/city/nodes'", 'required', 'member', 'global-read+viewer-personalization'],
  ['subpackageMember/components/cy/scene-roam-poi-detail/index.js', "url: '/api/city/nodes/' + this.data.poiId", 'required', 'member', 'global-read+viewer-personalization'],
  // 2026-10-25 官方地点:地图点位卡展开读节点(相册 / 玩法 / 完成状态),挑战页读同一节点,与据点详情同端点同权限档。
  ['subpackageRoam/nearby/index.js', "url: '/api/city/nodes/' + id", 'required', 'member', 'global-read+viewer-personalization'],
  ['pages/play/poi-challenge/index.js', "url: '/api/city/nodes/' + this._poiId", 'required', 'member', 'global-read+viewer-personalization'],
  ['utils/game-session-client.js', "url: '/api/game/session/view'", 'required', 'player/merchant/club', 'activity-perspective-access'],
  ['utils/game-session-client.js', "url: '/api/game/session/merchant/entries'", 'required', 'merchant', 'self-merchant-game-entry'],
  /* 2026-09-11 本分支新增:承接页的「本站」入口自己也要按 topicId 过滤一遍本商家的场次
     —— 与上面 game-session-client 那处同一个端点、同一套权限(required/merchant),
     区别只是调用方。后端按当前登录商家派生 activityId,前端不传也不能传别人的。 */
  ['pages/topic/merchantinfo/merchantinfo.js', "url: '/api/game/session/merchant/entries'", 'required', 'merchant', 'self-merchant-game-entry'],
  /* 2026-09-24 CU-C-68:开「更多」抽屉时用**同一端点**预探一次「本人是否承接了本站」,
     没入口就把那四行置灰(原来主办方账号点下去只会得到「当前商家不负责本场站点」)。
     权限档与上面那条逐字相同(required/merchant,后端按登录商家派生),只是第二个调用点;
     marker 带上 silentError 让两条各自可定位。 */
  ['pages/topic/merchantinfo/merchantinfo.js', "url: '/api/game/session/merchant/entries', method: 'GET', hideLoading: true, silentError: true", 'required', 'merchant', 'self-merchant-game-entry'],
  ['utils/game-session-client.js', "url: '/api/game/session/recap/export'", 'required', 'club', 'club-owned-activity'],
  ['utils/game-session-client.js', "url: '/api/game/session/receipt'", 'required', 'player/merchant/club', 'activity-perspective-access'],
  ['utils/game-session-client.js', "url: exported ? '/api/game/session/shared/export'", 'required', 'club/EVENT_OPERATE', 'club-owned-activity'],
  ['pages/play/index.js', "req('/api/play/nodes'", 'required', 'member', 'current-session'],
  /* 2026-09-18 R14/故事变量:profile 提交成功后的补拉 —— 与首屏同端点同权限档,只是第二处调用点。
     行尾「// 补拉」让 marker 唯一,不然两条都会 resolve 到首屏那一处。 */
  ['pages/play/index.js', "req('/api/play/nodes', 'GET', params).then((res) => { // 补拉", 'required', 'member', 'current-session'],
  /* 2026-09-18 R14 旅程检定:节点卡打开时探测这一拍有没有检定。后端 ApiPlayEncounterController
     currentMemberId()<=0 直接拒,resolve(memberId, topicId, nodeId) 读的是本人这一局的 run —— 与 /api/play/nodes 同档。 */
  ['pages/play/index.js', "req('/api/play/encounter'", 'required', 'member', 'current-session'],
  /* 2026-09-24 结局 + 这一生的档案:ApiPlayJourneyController.ending currentMemberId()<=0 直接拒,
     读的是本人本局(完成后才给)的结局与本人各站留下的东西 —— 与 /api/play/nodes 同档。 */
  ['pages/play/index.js', "req('/api/play/journey/ending'", 'required', 'member', 'current-session'],
  /* 2026-10-22 L21 BL-186 章节阶段结算:ApiPlayJourneyController.stageEnd 同一控制器,currentMemberId()<=0 直接拒,
     读本人本局 session 的本章结算 —— 与 /journey/ending 同档。 */
  ['pages/play/index.js', "req('/api/play/journey/stage-end'", 'required', 'member', 'current-session'],
  /* 2026-10-22 L21 BL-185 主题发布前试检查:ApiTopicStateKeyController.publishCheck 走 editableDenial ——
     登录 →(scope=MERCHANT 时 PROJECT_MANAGE 取 Owner 主体)→ 主题本人;只读不改状态。 */
  ['pages/publish/fabu/index.js', "url: '/api/topic/publish-check'", 'required', 'publisher', 'self-publisher'],
  // unknown 回执的权威回读：与 play/index 同一接口、同一 session 参数，权限档位一致。
  ['pages/play/merchant/index.js', "url: '/api/play/nodes'", 'required', 'member', 'current-session'],
  ['pages/play/utils/theme-backpack.js', "req('/api/play/theme/state'", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/play/route-state'", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/club/lead/team-progress'", 'required', 'leader/paid-member', 'activity-lead-access'],
  ['pages/play/index.js', "req('/api/play/companionLine'", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/play/preference/' + nodeId", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/play/ending'", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/play/leaderboard'", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/play/os/' + topicId", 'required', 'member', 'self-member-history'],
  // 2026-09-05 漫游·附近的局:走默认认证(后端 anyRequest().authenticated()),viewer 视角决定 isMember 与拉黑过滤。
  //   (原文写「两条 GET」——另一条 /api/roam/hangout/detail 同日随组局下线撤走,这里只剩 nearby 一条。)
  ['subpackageRoam/nearby/index.js', "url: '/api/roam/hangout/nearby'", 'required', 'member', 'global-authenticated-read'],
  // 2026-09-17 第二轮拍板 22:首页「继续游戏」只列本人仍可继续的游戏会话(服务端按 uid 取,不收 memberId 参数)。
  // 2026-09-17 第二轮拍板 17:朋友分享的足迹快照,未登录可读;只凭随机 32 位令牌取已裁剪/平移的快照,不带分享者身份。
  ['utils/roam-share-snapshot.js', "url: '/api/roam/share/snapshot', method: 'GET'", 'optional', 'visitor/member', 'share-token-snapshot'],
  // 2026-09-15 地图组队 P 方案:附近公开队伍。ApiPlayTeamController#nearby 走 getAppUserId 未登录即拒;viewerStatus/pendingCount 按 viewer 算。
  // (同日下线组局的 /api/roam/pois 与 /api/roam/hangout/detail 两条 GET:地图上不再有局卡与打卡点列表。
  //  同日撤掉漫游地图 f-hangout 一档:roam 不再调 /api/roam/hangout/nearby,搭子局已下线,该卡整卡退场。)
  ['subpackageRoam/nearby/index.js', "url: '/api/team/nearby'", 'required', 'member', 'global-authenticated-read'],
  // 2026-10-14 漫游上线:我的队伍页「附近招募」读同一端点(同一权限档);那年今日 / 月度回顾只读本人完成记录(归档已审分类)。
  ['subpackageRoam/my-teams/index.js', "url: '/api/team/nearby'", 'required', 'member', 'global-authenticated-read'],
  ['subpackageTools/utils/features/exploration-anniversary.js', "url: '/api/exploration-anniversary'", 'required', 'member', 'self-member-history'],
  ['subpackageTools/utils/features/monthly-recap-client.js', "url: '/api/monthly-recap'", 'required', 'member', 'self-member-history'],
  // 2026-10-19 移植共享基线(W2):认领门店状态只读本人认领(ApiMerchantClaimController#status 按 getAppUserId);
  //   主题状态键只读本人可编辑主题(ApiTopicStateKeyController 校验发布者);官方路线列表/详情走 /api/preset 已登录读(非 permitAll)。
  ['pages/merchant/components/merchant-claim-sheet/index.js', "url: '/api/merchant/claims/status', method: 'GET'", 'required', 'member', 'self-account'],
  ['pages/publish/components/topic-state-keys/index.js', "url: '/api/topic/state-keys', method: 'GET'", 'required', 'publisher', 'self-publisher'],
  // 文档二 L3：下一站条件与效果读同一个注册表补名字（主题本人或商家代管 scope）。
  ['pages/publish/fabu/theme-route-names.js', "url: '/api/topic/state-keys', method: 'GET'", 'required', 'publisher', 'self-publisher'],
  ['pages/publish/fabu/theme-d20-page.js', "url: '/api/topic/play-setup', method: 'GET'", 'required', 'publisher', 'self-publisher'],
  ['subpackageRoam/official-routes/index.js', "url: '/api/preset/route/list'", 'required', 'member', 'global-authenticated-read'],
  ['subpackageRoam/official-route/index.js', "url: '/api/preset/route/info'", 'required', 'member', 'global-authenticated-read'],
  ['pages/roam/index.js', "req('/api/ai/npc/profile'", 'required', 'member', 'global-authenticated-read'],
  ['pages/roam/index.js', "req('/api/ai/npc/event'", 'required', 'member', 'global-authenticated-read'],
  ['pages/roam/index.js', "req('/api/roam/tiles/page'", 'required', 'member', 'self-member-history'],
  // 8b0871d2e 漫游恢复核对:后端 ApiRoamController#session 未登录即拒,RoamRecoveryService.read 只按 uid 查本人会话
  ['pages/roam/index.js', "req('/api/roam/session'", 'required', 'member', 'self-member-history'],
  ['pages/roam/index.js', "req('/api/roam/pois'", 'required', 'member', 'global-read+viewer-personalization'],
  ['pages/roam/index.js', "req('/api/official/events/' + eventId", 'optional', 'visitor/member', 'public-detail+viewer-personalization'],
  ['pages/roam/index.js', "req('/api/roam/nearby-exploreday'", 'required', 'member', 'global-authenticated-read'],
  ['pages/roam/index.js', "req('/api/roam/entry/nearby'", 'required', 'member', 'global-authenticated-read'],
  // 2026-10-28 p74:附近 3km 为空时 20km 兜底,同端点同权限档
  ['pages/roam/index.js', "req('/api/roam/entry/nearby', 'GET', { lat: c.lat, lng: c.lng, radius: 20000 }", 'required', 'member', 'global-authenticated-read'],
  ['pages/roam/index.js', "req('/api/team/nearby'", 'required', 'member', 'global-authenticated-read'],
  ['pages/roam/index.js', "req('/api/roam/badge/shop-streak'", 'required', 'member', 'global-authenticated-read'],
  ['pages/merchant/customer/index.js', 'loadSavedSegments() {', 'required', 'merchant/merchant:crm:segment', 'self-merchant'],
  ['pages/shezhi/components/marketing-consent/index.js', "url: '/api/merchant/crm/marketing-consents'", 'required', 'member', 'self-marketing-consent'],
  // 2026-09-17 商家营销同意入口(拍板第 40 条 A):与设置页同一端点、同一权限档 ——
  //   GET 只读本人可管理的商家行,失败即不出现(fail-closed),账号身份由后端 principal 定。
  ['utils/marketing-consent-entry.js', "url: '/api/merchant/crm/marketing-consents'", 'required', 'member', 'self-marketing-consent'],
  // 2026-10-20 FOLLOWUP-A-1:主题/玩法举报先读平台举报原因,与动态举报同一端点(默认认证,无个人态)。
  ['utils/content-report.js', "url: '/api/v1/community/capabilities'", 'required', 'member', 'global-authenticated-read'],
  // 2026-10-20 FOLLOWUP-A-2:作者本人内容的审核时间线,ApiAuditController.timeline 按内容 owner 校验。
  ['subpackageA/pages/myproject/index.js', "url: '/api/audit/timeline'", 'required', 'author', 'self-publisher'],
  // 2026-10-19 Claude 小程序线移植到 master:本线新增 20 处 GET 逐条归类(收藏据点页暂不进 master)。全部不在 SecurityConfig permitAll 清单 ⇒ required;
  //   本人记录(举报/纠错/收藏/通知/审核回读)= self-account;原因表/安全感受摘要/据点公开信息 = global-authenticated-read;
  //   商家触达 = owned-merchant-resource(MARKETING_READ);开通前置 = self-merchant;复盘统计 = self-publisher。
  ['subpackageMember/components/cy/report-sheet/index.js', "url: '/api/community-trust/reports/taxonomy'", 'required', 'member', 'global-authenticated-read'],
  ['pages/activity/official-mine/index.js', "url: '/api/official/broadcast/' + encodeURIComponent(String(id)) + '/stats'", 'required', 'publisher', 'self-publisher'],
  ['pages/merchant/crm-campaigns/index.js', "url: '/api/merchant/crm/campaigns'", 'required', 'merchant', 'owned-merchant-resource'],
  ['pages/merchant/crm-campaigns/index.js', 'url: `/api/merchant/crm/campaigns/${id}`', 'required', 'merchant', 'owned-merchant-resource'],
  ['pages/merchant/index/index.js', "url: '/api/merchant/ops/prerequisites'", 'required', 'merchant', 'self-merchant'],
  ['pages/play/merchant/components/safety-rating-sheet/index.js', "url: '/api/community-trust/safety/taxonomy'", 'required', 'member', 'global-authenticated-read'],
  ['pages/roam/index.js', "req('/api/official/active-for-roam'", 'required', 'member', 'global-authenticated-read'],
  ['subpackageB/pages/im/chat/index.js', 'url: `/api/audit/${auditTaskId}/detail`', 'required', 'member', 'self-account'],
  ['subpackageB/pages/im/chat/index.js', "url: '/api/v1/community/notifications'", 'required', 'member', 'self-account'],
  ['pages/shezhi/components/post-governance/index.js', "url: '/api/v1/community/me/enforcements'", 'required', 'member', 'self-account'],
  ['subpackageMember/components/cy/merchant-correction-sheet/index.js', "url: '/api/community-trust/contributions/taxonomy'", 'required', 'member', 'global-authenticated-read'],
  ['subpackageMember/components/cy/profile/index.js', 'url: `/api/community-trust/safety/merchants/${id}`', 'required', 'member', 'global-authenticated-read'],
  ['subpackageMember/components/cy/profile/index.js', "url: '/api/city/nodes/merchant/' + id", 'required', 'member', 'global-authenticated-read'],
  ['subpackageMember/report-detail/index.js', 'url: `/api/community-trust/reports/${id}`', 'required', 'member', 'self-account'],
  ['subpackageMember/report-detail/index.js', "url: '/api/community-trust/reports/taxonomy'", 'required', 'member', 'global-authenticated-read'],
  ['subpackageMember/report-detail/index.js', 'confirmByReadBack(accepted, receipt) {', 'required', 'member', 'self-account'],
  ['subpackageMember/report-mine/index.js', "url: '/api/community-trust/reports/mine'", 'required', 'member', 'self-account'],
  ['subpackageMember/report-mine/index.js', "url: '/api/community-trust/reports/taxonomy'", 'required', 'member', 'global-authenticated-read'],
  // 2026-10-28 原型 C 我的申诉：本人跨局申诉列表 + 本人账号信誉（两条均为「需后端」新接口，按本人过滤）。
  ['subpackageMember/appeal-mine/index.js', "url: '/api/orient/scoring/mine'", 'required', 'member', 'self-account'],
  ['subpackageMember/appeal-mine/index.js', "url: '/api/member/reputation'", 'required', 'member', 'self-account'],

  // 2026-10-22 W10b #27:NPC 对话原请求只读回读,ApiAiNpcController.chatRequest 只按登录态 + 原会话 + requestId 读本人记录。
  ['subpackageMember/components/cy/npc-chat/index.js', "url: '/api/ai/npc/chat/request'", 'required', 'member', 'self-account'],
  // W10b #22:简易 AI 起草按 requestId 只读回读,ApiAiController.themeDraftRequest 键按登录会员隔离。
  ['pages/publish/simple/index.js', "url: '/api/ai/theme/draft/request'", 'required', 'author', 'self-account'],
  ['pages/publish/simple/index.js', "url: '/api/ai/theme/draft/recovery'", 'required', 'author', 'self-account'],
  // W10b #25:本人资料审核回执(只取登录态)与门店 AI 形象审核回执(商家 PROFILE_WRITE,按登录商家派生)。
  ['pages/gerenziliao/gerenziliao.js', "url: '/api/user/profile/review'", 'required', 'member', 'self-account'],
  ['pages/merchant/decor/ai-npc/index.js', "url: '/api/merchant/npc/review'", 'required', 'merchant', 'self-merchant'],
  // 2026-10-23 v13 动效落地(PR #528,用户批准更新冻结数值):主题页角色卡读 NPC 资料、路线页读附近队伍与站点、地图页读据点,四处直接 GET,
  //   端点与既有登记同一权限档(npc/profile、team/nearby = global-authenticated-read;play/nodes = current-session;roam/pois = global-read+viewer-personalization)。
  ['pages/topic/index/index.js', "url: '/api/ai/npc/profile'", 'required', 'member', 'global-authenticated-read'],
  ['pages/topic/index/index.js', "url: '/api/team/nearby'", 'required', 'member', 'global-authenticated-read'],
  ['pages/topic/index/index.js', "url: '/api/play/nodes'", 'required', 'member', 'current-session'],
  ['subpackageRoam/nearby/index.js', "url: '/api/roam/pois'", 'required', 'member', 'global-read+viewer-personalization'],
  // 2026-10-27 W8 收口逐条复核后端(均未进 SecurityConfig permitAll，全部默认认证)：
  //   赛事联络/签到:玩家读 OfficialEventAttendanceService.read(id, uid, false) 仅公开可见赛事 + 本人 mine 投影;
  //   管理读先 canPublish(uid) 再 organizer(e, uid)。
  ['components/cy/event-attendance/index.js', "url:'/api/official/events/'+id+'/attendance/manage'", 'required', 'publisher', 'controlled-event-operator'],
  ['components/cy/event-attendance/index.js', "url:'/api/official/events/'+id+'/attendance'}", 'required', 'member', 'public-detail+viewer-personalization'],
  //   注册属性绑定:AuthorAttributeRegistryReader.read 校验主题 memberId == 登录人(商家 scope 先换 PROJECT_MANAGE 主体)。
  ['pages/publish/fabu/index.js', "url: '/api/topic/attribute-binding'", 'required', 'publisher', 'self-publisher'],
  //   公共玩法采用来源/原请求结果/采用详情:TemplateAdoptionService principal(actor, owner) + 按 actor 读原请求。
  ['pages/publish/fabu/index.js', "url: '/api/template/adoption-source'", 'required', 'publisher', 'self-publisher'],
  ['pages/publish/fabu/index.js', "url: '/api/template/adoption-receipt'", 'required', 'publisher', 'self-publisher'],
  ['pages/publish/fabu/index.js', "url: '/api/template/adoption-detail'", 'required', 'publisher', 'self-publisher'],
  ['pages/publish/utils/publish/template-adoption.js', "url:'/api/template/adoption-detail'", 'required', 'publisher', 'self-publisher'],
  ['pages/publish/utils/publish/template-adoption.js', "url:'/api/template/adoption-receipt'", 'required', 'publisher', 'self-publisher'],
  ['pages/publish/utils/publish/template-adoption.js', "url:'/api/template/adoption-source'", 'required', 'publisher', 'self-publisher'],
  //   原申诉请求结果:AuditTaskServiceImpl.appealReceipt 按 bizType/bizId/登录 memberId 查本人申诉。
  ['subpackageA/pages/myproject/index.js', "url: '/api/audit/appeal/receipt'", 'required', 'author', 'self-publisher'],
  //   定向计分排名:OrientScoringServiceImpl.leaderboard 先 readOwned(memberId, sessionId) 再 requireLeaderboardAccess。
  ['subpackageMember/orient-score/index.js', "url: BASE + '/session/' + sessionId + '/leaderboard'", 'required', 'member', 'self-account'],
  ['pages/play/index.js', "req('/api/orient/scoring/session/' + originalSessionId + '/leaderboard'", 'required', 'member', 'self-account'],
  //   漫游护照 history/summary/record:ApiRoamPassportController 只取登录 owner，不接收客户端 owner。
  ['utils/roam-passport-read.js', "url: path, method: 'GET'", 'required', 'member', 'self-member-history'],
  //   基础动作上下文/原动作结果:PlayBasicIntentApplication 以 currentMemberId + basicIntentScope 限定本人当前游玩局。
  ['pages/play/index.js', "req('/api/play/action-receipt', 'GET', receiptQuery", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/play/action-receipt', 'GET', { activityId: body.activityId", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/play/action-context'", 'required', 'member', 'current-session'],
  ['pages/play/index.js', "req('/api/club/lead/unlock-chapter-receipt'", 'required', 'leader', 'current-session'],
  //   定向计分明确不适用时回退原排行榜(与上一条 /api/play/leaderboard 同端点同权限档)。
  ['pages/play/index.js', "req('/api/play/leaderboard', 'GET', params);\n    loadBoard", 'required', 'member', 'current-session'],
  // 2026-10-28 文档二 L5 游玩页:状态栏第二行 / 全屏角色卡读本人角色卡(后端按登录会员只读自己),
  //   「…」用时得分 / 全屏本局成绩读本局计分与本场排名(后端 lockOwned 按本人校验对局归属)。
  ['pages/play/utils/play-hud-overlays.js', "req('/api/play/character'", 'required', 'member', 'self-account'],
  ['pages/play/utils/play-hud-overlays.js', "req(base, 'GET'", 'required', 'member', 'self-account'],
  ['pages/play/utils/play-hud-overlays.js', "req(base + '/leaderboard'", 'required', 'member', 'self-account'],
  ['pages/play/utils/play-hud-overlays.js', "req('/api/play/contact'", 'required', 'member', 'self-account'],
];

const VALID_SCOPES = new Set([
  'self-account', 'public-list+viewer-personalization', 'self-registration',
  'self-party', 'self-merchant', 'self-capability', 'self-publisher',
  'public-detail+viewer-personalization', 'global-read+viewer-personalization',
  'current-session', 'activity-lead-access', 'global-authenticated-read',
  'self-member-history', 'activity-perspective-access',
  'self-merchant-game-entry', 'club-owned-activity',
  'owned-merchant-resource', 'self-marketing-consent',
  'share-token-snapshot', 'owned-merchant-or-club-receivable', 'owned-media-asset',
  'controlled-event-operator', 'public-profile-gallery', 'active-team-member', 'owned-topic-target-merchant-stations',
]);

function productionJsFiles(dir) {
  const out = [];
  fs.readdirSync(dir, { withFileTypes: true }).forEach((entry) => {
    // 构建台账的 requiredRequests 是合同元数据，不是小程序运行时的 GET 调用。
    if (['tests', 'scripts', 'node_modules', 'miniprogram_npm'].includes(entry.name)) return;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...productionJsFiles(full));
    else if (entry.isFile() && entry.name.endsWith('.js')) out.push(full);
  });
  return out;
}

function registeredCallSource(source, marker, markerAt) {
  const masked = maskJsNonCode(source);
  let callAt;
  let scanEnd = masked.length;
  if (/\([^)]*\) \{$/.test(marker)) {
    const functionOpenAt = masked.indexOf('{', markerAt);
    assert.ok(functionOpenAt >= 0, `找不到 ${marker} 的函数体`);
    let braceDepth = 0;
    let functionEnd = -1;
    for (let i = functionOpenAt; i < masked.length; i += 1) {
      if (masked[i] === '{') braceDepth += 1;
      else if (masked[i] === '}') {
        braceDepth -= 1;
        if (braceDepth === 0) {
          functionEnd = i;
          break;
        }
      }
    }
    assert.ok(functionEnd >= 0, `${marker} 的函数体没有闭合`);
    scanEnd = functionEnd;
    const candidates = [masked.indexOf('sendRequest(', markerAt), masked.indexOf('this.requestOwnMedia(', markerAt)].filter(at => at >= 0 && at < scanEnd);
    callAt = candidates.length ? Math.min(...candidates) : -1;
    if (callAt >= scanEnd) callAt = -1;
  }
  else if (/^(?:(?:sendUiStateRequest|req|sender)\(|this\.requestOwnMedia\()/.test(marker)) callAt = markerAt;
  else callAt = masked.lastIndexOf('sendRequest(', markerAt);
  assert.ok(callAt >= 0, `找不到 ${marker} 所属请求调用`);

  const openAt = masked.indexOf('(', callAt);
  let depth = 0;
  for (let i = openAt; i < scanEnd; i += 1) {
    if (masked[i] === '(') depth += 1;
    else if (masked[i] === ')') {
      depth -= 1;
      if (depth === 0) return source.slice(callAt, i + 1);
    }
  }
  assert.fail(`${marker} 所属请求调用没有闭合`);
}

test('GET 矩阵窗口覆盖完整当前调用且不跨到下一请求', () => {
  const afterSuccess = `loadItems() {
    app.sendRequest({ method: 'GET', success() {}, auth: false });
  },
  loadNext() { app.sendRequest({ url: '/api/next', method: 'POST', success() {} }); }`;
  const current = registeredCallSource(afterSuccess, 'loadItems() {', afterSuccess.indexOf('loadItems() {'));
  assert.match(current, /auth\s*:\s*false/, 'success 后的 auth:false 也必须留在当前调用窗口');
  assert.throws(() => assert.doesNotMatch(current, /auth\s*:\s*false/),
    '把 success 后的 auth:false 放进真实门禁断言时必须判红');
  assert.doesNotMatch(current, /\/api\/next/, '当前调用窗口不得跨进下一请求');

  const withoutSuccess = `loadItems() {
    app.sendRequest({ method: 'POST', fail() {} });
  },
  loadNext() { app.sendRequest({ url: '/api/next', method: 'GET', success() {} }); }`;
  const noSuccessCall = registeredCallSource(withoutSuccess, 'loadItems() {', withoutSuccess.indexOf('loadItems() {'));
  assert.match(noSuccessCall, /fail\(\)/);
  assert.throws(() => assert.match(noSuccessCall, /['"]GET['"]/),
    '当前 POST 没有 success 时不得借下一请求的 GET 假绿');
  assert.doesNotMatch(noSuccessCall, /\/api\/next/, '当前请求没有 success 时也不得借下一请求过门禁');

  const withoutCurrentRequest = `loadItems() {
    this.setData({ loading: false });
  },
  loadNext() { app.sendRequest({ url: '/api/next', method: 'GET', success() {} }); }`;
  assert.throws(
    () => registeredCallSource(
      withoutCurrentRequest,
      'loadItems() {',
      withoutCurrentRequest.indexOf('loadItems() {')
    ),
    /找不到 .* 所属请求调用/,
    '当前函数没有请求时必须判红，不能借下一函数的 GET 假绿'
  );
});

test('全部 GET 调用点都有 URL + method + auth/角色/scope 分类，新增调用必须先入矩阵', () => {
  let directCount = 0;
  let wrapperCount = 0;
  let ownMediaWrapperCount = 0;
  productionJsFiles(XCX_ROOT).forEach((file) => {
    const source = fs.readFileSync(file, 'utf8');
    directCount += (source.match(/method\s*:\s*['"]GET['"]/g) || []).length;
    ownMediaWrapperCount += (source.match(/\bthis\.requestOwnMedia\([^\n]*?,\s*['"]GET['"]\s*,/g) || []).length;
    wrapperCount += (source.match(/\breq\([\s\S]{0,180}?,\s*['"]GET['"]\s*,/g) || []).length;
  });
  // 2026-09-15 合拢收尾:漫游页 f-hangout 死卡退场,roam 少一条 /api/roam/hangout/nearby GET,28 → 27。
  // 2026-09-17 第二轮拍板 17/22:首页 run-session/list + 足迹分享快照读取,27 → 29。
  // 2026-09-17 商家营销同意入口(拍板第 40 条 A):utils/marketing-consent-entry.js 新增一条
  //   GET /api/merchant/crm/marketing-consents(与设置页同一端点/同一权限档),27 → 28。
  // 2026-09-17 发布版本 release-0917 再合 fix-consent:合并树 29(续玩 list + 足迹快照)+ 营销同意 GET 1 ⇒ 30(测试实跑实测)。
  // 2026-09-18 R14/故事变量:pages/play 新增两处 req 包装 GET —— encounter 探测一条、
  //   profile 提交后补拉 nodes 一条(wrapper 16 → 18);端点权限档与既有 /api/play/nodes 相同。
  // 2026-09-24 CU-C-68:merchantinfo 新增第二处 /api/game/session/merchant/entries GET
  //   (抽屉资格预探,权限档与上一处相同:required/merchant)⇒ 30 → 31。
  // 客户页退役触达：移除券候选、任务列表、任务详情三处 GET，31 → 28。
  // UI 门禁固定默认适配器路径和 GET 方法；适配器与注入调用点均归原 EVENT_OPERATE 权限。
  // 2026-10-12 作者媒体审核pending查询增加1个真实sender GET，33→34。
  // 2026-10-13 注销申请结果未知时只读回查本人 /api/user/deregister/status,同端点同权限档,34→35。
  // 2026-10-14 漫游上线:我的队伍附近招募 + 那年今日 + 月度回顾三处 GET,35 → 38(权限档见 MATRIX 同日注释)。
  // 2026-10-20 合并举报原因与作者审核时间线两处 GET；保留小程序线新增24处逐项权限登记，62 → 64。
  // 2026-10-19 移植共享基线(W2):认领状态、主题状态键、官方路线列表/详情四处直接 GET,38 → 42(权限档见 MATRIX 同日注释)。
  // 合并两线逐项新增：本线64 + 共享基线4 = 68，权限矩阵保留双方全部条目。
  // 2026-10-20 用户要求删掉「我的纠错 / 纠错进度」两页、消息页改收件箱/发件箱(合并 master 584470ad 后按重建实测):contributions/mine 1 处 + contributions/{id} 2 处退场,68 → 65;举报进度通知读取从消息列表移到城瘾小助手(同端点同权限档)。
  // 2026-10-22 W10b:NPC 原请求回读 / AI 起草回读 / 资料审核回执 / 门店形象审核回执四处直接 GET,65 → 69(权限档见 MATRIX 同日注释)。
  // 2026-10-22 W9 帖子处置(F0521):设置页本人处置列表 GET /api/v1/community/me/enforcements(后端 withAuthenticatedPrincipal,按本人过滤),69 → 70(batch2 集成在 W10b 的 69 上叠加)。
  // 2026-10-22 L21 BL-185:fabu 云端草稿发布前 GET /api/topic/publish-check(本人主题/商家 Owner),70 → 71(batch3 集成在 batch2 的 70 上叠加;L21 原写 65 → 66)。
  // 2026-10-23 v13 动效落地(PR #528):主题页 NPC 资料 / 附近队伍 / 站点 + 地图页据点四处直接 GET,71 → 75(权限档见 MATRIX 同日注释)。
  // 2026-10-24 地点详情和地图新增两处已公开 /profile/** 相册元数据 GET。
  // 2026-10-25 官方地点卡片 + 地点挑战页各一处 GET /api/city/nodes/{id}(已入 MATRIX),77 → 79。
  // 上述两端各新增单笔与分页 GET，共四处，79 → 83。
  // 本人角色卡及计分会话列表、明细、回执增加四处认证 GET，83 → 87。
  // Existing team detail adds two authenticated board reads, 87 → 89.
  // 2026-10-27 W8 收口:赛事联络/签到两处、注册属性绑定一处、公共玩法采用六处、原申诉结果一处、定向计分排名一处、
  //   漫游护照读取一处，共 12 处直接 GET(权限档见 MATRIX 同日注释)，89 → 101。
  // 2026-10-28 文档二 L3:下一站名单读状态键注册表一处(同 topic-state-keys 权限档),101 → 102。
  // 2026-10-28 L6:我的队伍「本场招募」GET /api/orient/team/lobby 一处(已入 MATRIX),102 → 103。
  // 2026-10-28 L6:主题详情 /api/play/setup、签到组件 /api/play/contact 各一处(已入 MATRIX),103 → 105。
  // 2026-10-28 原型 C 我的申诉：本人申诉列表、本人账号信誉两处认证 GET(已入 MATRIX)，105 → 107。
  // 2026-10-28 L9 集成:主题详情可玩时段 / 开局前准备读 L8 GET /api/topic/play-setup(创作者,同 state-keys 档),107 → 108。
  // 2026-10-27 真机走查 P-08(owner 要求删除首页「继续探索」卡):首页 /api/play/run-session/list 退场,79 → 78。
  // 2026-10-28 L9 再并 master:P-08 首页 run-session/list 退场 −1,108 → 107。
  // Frozen-master calls + creator-scoped supply stations + V2 original-request recovery.
  // Source accounting only; this merged tree has NOT_RUN validation.
  assert.equal(directCount, 109, '直接 GET 数量变化时必须逐条复核权限并更新矩阵');
  // 2026-10-09 U04 只读主题请求增加6处，22 → 28。
  // 2026-10-19 Claude 小程序线移植到 master(用户批准冻结数值更新):漫游 BL-41 可参与官方活动 req 包装 GET +1(已入 MATRIX),28 → 29。
  // 2026-10-22 L21 BL-186:pages/play 本章结算 GET /api/play/journey/stage-end(与 /ending 同档),29 → 30。
  // 2026-10-28 p74:漫游「附近活动」为空时按 20km 再读一次 /api/roam/entry/nearby(同端点同权限档,已入 MATRIX),30 → 31。
  // 2026-10-27 W8 收口:pages/play 基础动作原结果两处、动作上下文一处、定向计分排名一处、其回退排行榜一处，30 → 35。
  // 2026-10-28 文档二 L5:pages/play 角色卡一处、本局计分与本场排名两处(权限档见 MATRIX 同日注释)，35 → 38。
  // 2026-10-28 L9 集成:游玩菜单联系主办 / 商家改读 L8 GET /api/play/contact(已入 MATRIX),38 → 39。
  // 2026-10-28 并 master(#658 后)合流 integrate/devfix-20261027:master 39 叠加本线 p74 20km 兜底读一处,39 → 40。
  assert.equal(wrapperCount, 41, 'req 包装 GET 数量变化时必须逐条复核权限并更新矩阵');
  assert.equal(ownMediaWrapperCount, 5, '作者媒体实际GET调用点变化需显式复核');
  assert.equal(MATRIX.length, directCount + wrapperCount + ownMediaWrapperCount);

  MATRIX.forEach(([file, marker, mode, role, scope]) => {
    const source = fs.readFileSync(path.join(XCX_ROOT, file), 'utf8');
    const at = source.indexOf(marker);
    assert.notEqual(at, -1, `${file} 缺少已登记 GET: ${marker}`);
    const call = registeredCallSource(source, marker, at);
    assert.match(call, /['"]GET['"]/, `${file} 的 ${marker} method 已漂移`);
    if (mode === 'static') {
      assert.equal(scope, 'public-profile-gallery');
      assert.match(call, /auth\s*:\s*false/, '公开静态资源不携带会话');
      assert.match(source, /(?:url: '\/profile\/'|L\.poiGalleryPath\(poi\.coverImg\))/);
    } else {
      assert.doesNotMatch(call, /auth\s*:\s*false/, `${file} 的 ${marker} 不能降为匿名请求`);
      assert.ok(mode === 'required' || mode === 'optional');
    }
    assert.ok(role.length > 0, `${file} 的 ${marker} 缺角色分类`);
    assert.ok(VALID_SCOPES.has(scope), `${file} 的 ${marker} scope 未进入受控枚举: ${scope}`);
  });
});

// 仅接受两种完整 DSL；参数中不允许嵌套调用，括号必须成对。
// 固定全部 15 条 matcher 的参数、HTTP 方法、权限及顺序，避免跨规则匹配假绿。
const SECURITY_RULES = [
  'url=>permitAll',
  '"/login","/register","/captchaImage"=>permitAll',
  'HttpMethod.GET,"/","/*.html","/**/*.html","/**/*.css","/**/*.js","/profile/**"=>permitAll',
  '"/swagger-ui.html","/swagger-resources/**","/webjars/**","/*/api-docs","/druid/**"=>permitAll',
  '"/dev-api/v3/api-docs/**","/dev-api/swagger-ui/**","/dev-api/webjars/**","/v3/api-docs/**","/swagger-ui/**","/webjars/**"=>permitAll',
  '"/api/common/upload","/api/common/upload/","/api/common/uploads","/api/common/uploads/","/api/common/uploadOSS","/api/common/uploadOSS/"=>authenticated',
  '"/api/creativesquare/list","/api/product/list"=>permitAll',
  '"/api/activity/list","/api/topic/list"=>permitAll',
  'HttpMethod.POST,"/api/template/homeData","/api/template/list","/api/template/info","/api/template/topic-template/list","/api/template/topic-template/info","/api/category/list"=>permitAll',
  'HttpMethod.GET,"/api/official/events","/api/official/events/*"=>permitAll',
  '"/api/common/**","/api/register","/api/login/code","/api/openid","/api/login","/api/login/phone","/api/login/wechat/app","/api/login/apple","/api/sms/send","/api/findpass","/api/comment/list"=>permitAll',
  '"/api/merchant/public-detail"=>permitAll',
  '"/api/merchant/public-home","/api/user/public-info"=>permitAll',
  'HttpMethod.GET,"/api/roam/share/snapshot"=>permitAll',
  'HttpMethod.POST,"/api/merchant/list"=>permitAll'
];

function assertSecurityRules(source) {
  // 保留字符串（含路径里的 /*），只移除 Java 注释，注释不能充当放行证据。
  const code = source.replace(/"(?:\\.|[^"\\])*"|\/\/[^\n]*|\/\*[\s\S]*?\*\//g,
    (token) => token.startsWith('"') ? token : '');
  const matcher = /\.(?:antMatchers\(([^()]*)\)|requestMatchers\(legacyMatchers\(([^()]*)\)\))\s*\.\s*(permitAll|authenticated)\(\)/g;
  const rules = [...code.matchAll(matcher)].map((match) =>
    (match[1] ?? match[2]).replace(/"(?:\\.|[^"\\])*"|\s+/g, (token) => token.startsWith('"') ? token : '') + '=>' + match[3]);
  assert.equal(rules.length, (code.match(/\.(?:antMatchers|requestMatchers)\s*\(/g) || []).length,
    '每条 matcher 必须以完整且受支持的 DSL 解析，不能忽略未知规则');
  assert.deepEqual(rules, SECURITY_RULES, '权限规则、顺序或 HTTP 方法漂移');
  assert.equal((code.match(/\.anyRequest\(\)/g) || []).length, 1);
  assert.match(code, /\.anyRequest\(\)\.authenticated\(\)/);
}

test('后端权限真源：official events/roam share 仅精确 GET 匿名，city nodes 仍需鉴权', () => {
  assertSecurityRules(fs.readFileSync(SECURITY, 'utf8'));
});

for (const dsl of ['legacy', 'boot4']) {
  const current = fs.readFileSync(SECURITY, 'utf8');
  const source = dsl === 'boot4'
    ? current.replace(/\.antMatchers\(([^()]*)\)/g, '.requestMatchers(legacyMatchers($1))')
    : current.replace(/\.requestMatchers\(legacyMatchers\(([^()]*)\)\)/g, '.antMatchers($1)');
  const rule = (args) => dsl === 'boot4'
    ? `.requestMatchers(legacyMatchers(${args})).permitAll()`
    : `.antMatchers(${args}).permitAll()`;

  test(`${dsl} DSL 正例保留全部 15 条权限规则`, () => assertSecurityRules(source));
  const mutations = [
    ['official POST 改写 GET', (s) => s.replace('HttpMethod.GET, "/api/official/events"', 'HttpMethod.POST, "/api/official/events"')],
    ['official 移除 GET 限制', (s) => s.replace('HttpMethod.GET, "/api/official/events"', '"/api/official/events"')],
    ['official 额外 POST 放行', (s) => s.replace('.anyRequest().authenticated()', rule('HttpMethod.POST, "/api/official/events"') + '.anyRequest().authenticated()')],
    ['official 扩大通配符', (s) => s.replace('"/api/official/events/*"', '"/api/official/events/**"')],
    ['city nodes GET 放行', (s) => s.replace('.anyRequest().authenticated()', rule('HttpMethod.GET,\n "/api/city/nodes", "/api/city/nodes/*"') + '.anyRequest().authenticated()')],
    ['city nodes 所有方法放行', (s) => s.replace('.anyRequest().authenticated()', rule('"/api/city/nodes/**"') + '.anyRequest().authenticated()')],
    ['roam share POST 放行', (s) => s.replace('HttpMethod.GET, "/api/roam/share/snapshot"', 'HttpMethod.POST, "/api/roam/share/snapshot"')],
    ['roam share 扩大通配符', (s) => s.replace('"/api/roam/share/snapshot"', '"/api/roam/share/**"')],
    ['兜底匿名', (s) => s.replace('.anyRequest().authenticated()', '.anyRequest().permitAll()')],
    ['GET 正例藏入注释', (s) => s.replace(rule('HttpMethod.GET, "/api/roam/share/snapshot"'), (r) => `/* ${r} */`)],
  ];
  for (const [name, mutate] of mutations) {
    test(`${dsl} DSL 负面控制：${name} 必须判红`, () => {
      const changed = mutate(source);
      assert.notEqual(changed, source, '负面控制必须实际改变输入');
      assert.throws(() => assertSecurityRules(changed), assert.AssertionError);
    });
  }
}

test('team-progress 后端必须同时校验登录身份与本场资源归属', () => {
  const controller = fs.readFileSync(CLUB_LEAD_CONTROLLER, 'utf8');
  const method = controller.slice(controller.indexOf('@GetMapping("/team-progress")'), controller.indexOf('@PostMapping("/settle")'));
  const ownership = controller.slice(controller.indexOf('private boolean canAccessLeadActivity'), controller.indexOf('@PostMapping("/settle")'));

  assert.match(method, /Long memberId = uid\(\)/);
  assert.match(method, /canAccessLeadActivity\(activityId, memberId, st\)/);
  assert.match(ownership, /memberId\.equals\(state\.getLeaderMemberId\(\)\)/);
  assert.match(ownership, /probe\.setOwnerId\(activityId\)/);
  assert.match(ownership, /probe\.setMemberId\(memberId\)/);
  assert.match(ownership, /probe\.setPaymentStatus\(2\)/);
  assert.match(ownership, /probe\.setRegistrationStatus\(2\)/);
});

// Java owners moved out of the HTTP adapter. Inspect complete, comment-free methods
// along the real call chain; another endpoint/comment cannot stand in for a guard.
function playGetSources() {
  const java = (file) => fs.readFileSync(path.resolve(XCX_ROOT, '..', file), 'utf8');
  const app = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/service/';
  const support = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/';
  return {
    controller: fs.readFileSync(PLAY_PROGRESS_CONTROLLER, 'utf8'),
    preference: java(app + 'play/PlayPreferenceApplication.java'),
    player: java(app + 'play/PlayPlayerQueryApplication.java'),
    context: java(app + 'play/PlayNodeContext.java'),
    facts: java(app + 'play/PlayCompletionFacts.java'),
    access: java(support + 'PlayNodeAccessService.java'),
    registration: java(support + 'CurrentPlayRegistration.java'),
    preferenceService: java(app + 'PreferencePlayService.java'),
  };
}

function javaMethod(source, declaration) {
  const code = source.replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/[^\n]*|\/\*[\s\S]*?\*\//g,
    (token) => token.startsWith('"') || token.startsWith("'") ? token : ' '.repeat(token.length));
  assert.equal(code.split(declaration).length, 2, `必须唯一定位 Java 方法 ${declaration}`);
  const start = code.indexOf(declaration);
  const open = code.indexOf('{', start);
  assert.ok(open >= 0, `缺少方法体 ${declaration}`);
  const braces = code.replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, token => ' '.repeat(token.length));
  let depth = 0;
  for (let i = open; i < braces.length; i += 1) {
    if (braces[i] === '{') depth += 1;
    else if (braces[i] === '}' && --depth === 0) return code.slice(start, i + 1);
  }
  assert.fail(`Java 方法未闭合 ${declaration}`);
}

function inOrder(method, ...statements) {
  let after = -1;
  for (const statement of statements) {
    const at = method.indexOf(statement, after + 1);
    assert.ok(at > after, `实际可达方法必须按原顺序包含 ${statement}`);
    after = at;
  }
}

function actorEntry(source, methodName) {
  const method = javaMethod(source, `public AjaxResult ${methodName}(`);
  inOrder(method, 'Long memberId = actorMemberId;', 'if (memberId <= 0) return error("请先登录");');
  return method;
}

function assertPreferenceGetContract(s) {
  const controller = javaMethod(s.controller, 'public AjaxResult preference(');
  assert.match(controller, /return playPreferenceApplication\.preference\(currentMemberId\(\), nodeId, activityId, topicId\);/);
  const osController = javaMethod(s.controller, 'public AjaxResult newLifeOperatingSystem(');
  assert.match(osController, /return playPreferenceApplication\.newLifeOperatingSystem\(currentMemberId\(\), topicId\);/);
  const principal = javaMethod(s.controller, 'private Long currentMemberId(');
  assert.match(principal, /Long id = getAppUserId\(\);/);
  assert.match(principal, /return id == null \? 0L : id;/);
  assert.match(s.controller, /private PlayPreferenceApplication playPreferenceApplication;/);
  assert.match(s.preference, /private PlayNodeContext playNodeContext;/);
  assert.match(s.preference, /private PreferencePlayService preferencePlayService;/);
  inOrder(actorEntry(s.preference, 'preference'),
    'resolvePreferencePlayCtx(memberId, nodeId, activityId, topicId, null)',
    'if (pctx.error != null) return pctx.error;',
    'preferencePlayService.load(', 'memberId, pctx.playCtx.topicId, nodeId, pctx.template');
  const resolverEntry = javaMethod(s.preference, 'public PreferencePlayCtx resolvePreferencePlayCtx(');
  assert.match(resolverEntry, /return resolvePreferencePlayCtx\(memberId, nodeId, activityId, topicId, routeActionId, null\);/);
  // The public GET overload delegates to the same guarded resolver used by original-run submission.
  const resolver = javaMethod(s.preference, 'private PreferencePlayCtx resolvePreferencePlayCtx(');
  inOrder(resolver, 'playNodeContext.resolveCtx(memberId, activityId, topicId, out.node)',
    'if (out.playCtx.error != null) { out.error = out.playCtx.error; return out; }',
    'playNodeContext.leadGateError(out.playCtx.actId, out.node)',
    'if (leadErr != null) { out.error = error(leadErr); return out; }',
    'playNodeContext.unlockGateError(memberId, out.playCtx.actId, out.node)',
    'playNodeContext.routeAccessError(memberId, out.playCtx.actId, out.playCtx.topicId, out.node, routeActionId)',
    'if (unlockErr != null) { out.error = error(unlockErr); return out; }');
  assert.match(resolver, /playNodeContext\.frozenAnswer\(memberId, out\.playCtx\.topicId, out\.playCtx\.actId, out\.node\.getId\(\), originalRun\)/);
  const context = javaMethod(s.context, 'public PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node)');
  assert.match(context, /playNodeAccessService\.resolveContext\(memberId, activityId, topicId, nodeScope\(node\)\)/);
  const access = javaMethod(s.access, 'public Context resolveContext(Long actor, String activityId, String topicId, NodeScope node)');
  assert.match(access, /resolveCtx\(actor,activityId,topicId,nodeEntity\(node\)\)/);
  const entry = javaMethod(s.access, 'private PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node)');
  assert.match(entry, /return resolveCtx\(memberId, activityId, topicId, node, null\);/);
  const admission = javaMethod(s.access, 'private PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node,');
  assert.match(admission, /if \(!activity\.getTopicId\(\)\.equals\(node\.getTopicId\(\)\)\) \{ c\.error = error\("该节点不属于本次活动"\); return c; \}/);
  assert.match(admission, /CurrentPlayRegistration\.require\(\s*currentPlayActivityMapper,cmsRegistrationMapper,memberId,2L,actId,registrationId,new Date\(\)\)/);
  assert.match(admission, /if \(ticket == null\) \{ c\.error = error\("请先报名并完成支付"\); return c; \}/);
  assert.match(admission, /if \(node\.getTopicId\(\) == null \|\| !node\.getTopicId\(\)\.equals\(tId\)\) \{ c\.error = error\("该节点不属于本主题"\); return c; \}/);
  assert.match(admission, /hasRunningSession\(memberId, tId, 0L\)/);
  for (const port of ['requireToContinue', 'require']) {
    assert.match(admission, new RegExp(`CurrentPlayRegistration\\.${port}\\(\\s*currentPlayActivityMapper,cmsRegistrationMapper,memberId,1L,tId,registrationId,new Date\\(\\)\\)`));
  }
  assert.match(admission, /if \(pass == null\) \{ c\.error = new Failure\(402, "请先购买自玩通行证"\); return c; \}/);
  const registrationArgs = 'CmsActivityMapper activities,CmsRegistrationMapper registrations,\n            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now';
  assert.match(javaMethod(s.registration, `public static CmsRegistration require(${registrationArgs})`),
    /return require\(activities,registrations,memberId,ownerType,ownerId,requestedId,now,false\);/);
  assert.match(javaMethod(s.registration, `public static CmsRegistration require(${registrationArgs},boolean originallyRequiresPaid)`),
    /return collect\(activities,registrations,memberId,ownerType,ownerId,requestedId,now,originallyRequiresPaid,true,false\)\.get\(0\);/);
  assert.match(javaMethod(s.registration, `public static CmsRegistration requireToContinue(${registrationArgs})`),
    /return collect\(activities,registrations,memberId,ownerType,ownerId,requestedId,now,false,true,true\)\.get\(0\);/);
  const collect = javaMethod(s.registration, 'private static List<CmsRegistration> collect(');
  inOrder(collect, 'probe.setMemberId(memberId);probe.setOwnerType(ownerType);',
    'probe.setOwnerId(ownerId);probe.setRegistrationStatus(2);',
    'registrations.selectCmsRegistrationByIdForUpdate(snapshot.getId())',
    'if(!currentOwner(current,memberId,ownerType,ownerId)) continue;');
  assert.match(collect, /if\(\(originallyRequiresPaid \|\| Integer\.valueOf\(3\)\.equals\(current\.getPurchaseKind\(\)\)\)\s*&& !Integer\.valueOf\(2\)\.equals\(current\.getPaymentStatus\(\)\)\) continue;/);
  const owner = javaMethod(s.registration, 'public static boolean currentOwner(');
  for (const predicate of ['memberId.equals(current.getMemberId())', 'Long.valueOf(ownerType).equals(current.getOwnerType())',
    'ownerId.equals(current.getOwnerId())', 'Integer.valueOf(2).equals(current.getRegistrationStatus())']) assert.ok(owner.includes(predicate), predicate);
  assert.match(javaMethod(s.context, 'public String unlockGateError(Long memberId, Long actId, CmsTopicNode node)'),
    /return playNodeAccessService\.unlockGateError\(memberId, actId, nodeScope\(node\)\);/);
  assert.match(javaMethod(s.access, 'public String unlockGateError(Long actor,Long activityId,NodeScope node)'),
    /return unlockGateError\(actor,activityId,nodeEntity\(node\)\);/);
  const unlock = javaMethod(s.access, 'private String unlockGateError(Long memberId, Long actId, CmsTopicNode node)');
  inOrder(unlock, 'probe.setMemberId(memberId);', 'probe.setActivityId(actId);',
    'probe.setNodeId(node.getUnlockAfterNodeId());', 'playerNodeProgressService.selectPlayerNodeProgressList(probe)');
  assert.match(unlock, /if \(p\.getStatus\(\) != null && p\.getStatus\(\) == 1\) return null;/);
  inOrder(actorEntry(s.preference, 'newLifeOperatingSystem'), 'preferencePlayService.operatingSystem(memberId, topicId)');
  const os = javaMethod(s.preferenceService, 'public Map<String, Object> operatingSystem(');
  inOrder(os, 'attemptMapper.selectPreferenceResults(memberId, topicId)',
    'if (!completedByMember(memberId, topicId, attempts))',
    'throw new IllegalArgumentException("完成主题后才能生成新生活操作系统");');
  const completed = javaMethod(s.preferenceService, 'private boolean completedByMember(');
  for (const fact of ['probe.setMemberId(memberId);', 'probe.setOwnerType(2L);', 'probe.setRegistrationStatus(2);',
    'registrationService.selectCmsRegistrationList(probe)', 'activityIds.contains(row.getOwnerId())',
    'Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(row.getPurchaseKind())',
    'perkLedgerService.isExploreCompleted(row.getId(), topicId)']) assert.ok(completed.includes(fact), fact);
}

function assertLeaderboardGetContract(s) {
  const controller = javaMethod(s.controller, 'public AjaxResult leaderboard(');
  assert.match(controller, /return playPlayerQueryApplication\.leaderboard\(currentMemberId\(\), activityId, topicId\);/);
  assert.match(s.controller, /private PlayPlayerQueryApplication playPlayerQueryApplication;/);
  assert.match(s.player, /private PlayCompletionFacts playCompletionFacts;/);
  const board = actorEntry(s.player, 'leaderboard');
  inOrder(board, 'if (tId > 0 && !activity.getTopicId().equals(tId)) return error("活动与主题不匹配");',
    'boardTopicId = activity.getTopicId();',
    'if (!playCompletionFacts.isRegistered(memberId, actId)) return error("请先报名并完成支付");',
    '} else if (playCompletionFacts.validSelfPlayPass(memberId, tId) == null)',
    'return new AjaxResult(402, "请先购买自玩通行证");',
    'playerNodeProgressService.selectRouteLeaderboard(boardActivityId, boardTopicId, 50)');
  assert.match(board, /selectActivityLeaderboard\(\s*actId > 0 \? actId : null, actId > 0 \? null : tId, 50\)/);
  assert.match(board, /selectRouteLeaderboardRow\(boardActivityId, boardTopicId, memberId\)/);
  const registered = javaMethod(s.facts, 'public boolean isRegistered(');
  inOrder(registered, 'regProbe.setMemberId(memberId);', 'regProbe.setOwnerType(2L);',
    'regProbe.setOwnerId(actId);', 'regProbe.setRegistrationStatus(2);',
    'cmsRegistrationService.selectCmsRegistrationList(regProbe)', 'return regs != null && !regs.isEmpty();');
  const passEntry = javaMethod(s.facts, 'public CmsRegistration validSelfPlayPass(Long memberId, Long topicId)');
  assert.match(passEntry, /return validSelfPlayPass\(memberId, topicId, null\);/);
  const pass = javaMethod(s.facts, 'public CmsRegistration validSelfPlayPass(Long memberId, Long topicId, Long registrationId)');
  inOrder(pass, 'probe.setMemberId(memberId);', 'probe.setOwnerType(1L);', 'probe.setOwnerId(topicId);',
    'probe.setRegistrationStatus(2);', 'cmsRegistrationService.selectCmsRegistrationList(probe)');
  assert.match(pass, /r\.getExpiresAt\(\) == null \|\| r\.getExpiresAt\(\)\.after\(now\)/);
}

test('偏好题组与新生活 OS GET 都绑定当前会员及其游玩/完成事实', () => {
  assertPreferenceGetContract(playGetSources());
});

test('排行榜 GET 绑定当前会员及本场报名支付或自玩通行证', () => {
  assertLeaderboardGetContract(playGetSources());
});

test('玩法 GET 真实身份和实际 owner 守卫被改动时合同必须判红', () => {
  const sources = playGetSources();
  const mutations = [
    [assertPreferenceGetContract, 'controller', 'playPreferenceApplication.preference(currentMemberId(),', 'playPreferenceApplication.preference(999L,'],
    [assertPreferenceGetContract, 'controller', 'playPreferenceApplication.newLifeOperatingSystem(currentMemberId(),', 'playPreferenceApplication.newLifeOperatingSystem(999L,'],
    [assertPreferenceGetContract, 'preference', 'if (out.playCtx.error != null) { out.error = out.playCtx.error; return out; }', ''],
    [assertPreferenceGetContract, 'preference', 'return resolvePreferencePlayCtx(memberId, nodeId, activityId, topicId, routeActionId, null);', 'return null;'],
    [assertPreferenceGetContract, 'preference', 'return resolvePreferencePlayCtx(memberId, nodeId, activityId, topicId, routeActionId, null);', 'return resolvePreferencePlayCtx(999L, nodeId, activityId, topicId, routeActionId, null);'],
    [assertPreferenceGetContract, 'preference', 'out.node.getId(), originalRun)', 'out.node.getId(), null)'],
    [assertPreferenceGetContract, 'preference', 'playNodeContext.unlockGateError(memberId, out.playCtx.actId, out.node)', 'null'],
    [assertPreferenceGetContract, 'access', 'memberId,2L,actId,registrationId,new Date()', '999L,2L,actId,registrationId,new Date()'],
    [assertPreferenceGetContract, 'registration', 'if(!currentOwner(current,memberId,ownerType,ownerId)) continue;', ''],
    [assertPreferenceGetContract, 'registration', 'return collect(activities,registrations,memberId,ownerType,ownerId,requestedId,now,originallyRequiresPaid,true,false).get(0);', 'return null;'],
    [assertPreferenceGetContract, 'access', 'if (p.getStatus() != null && p.getStatus() == 1) return null;', 'return null;'],
    [assertPreferenceGetContract, 'preferenceService', 'if (!completedByMember(memberId, topicId, attempts))', 'if (false)'],
    [assertLeaderboardGetContract, 'controller', 'playPlayerQueryApplication.leaderboard(currentMemberId(),', 'playPlayerQueryApplication.leaderboard(999L,'],
    [assertLeaderboardGetContract, 'player', 'if (!playCompletionFacts.isRegistered(memberId, actId)) return error("请先报名并完成支付");', ''],
    [assertLeaderboardGetContract, 'player', 'if (!playCompletionFacts.isRegistered(memberId, actId)) return error("请先报名并完成支付");', '/* if (!playCompletionFacts.isRegistered(memberId, actId)) return error("请先报名并完成支付"); */'],
    [assertLeaderboardGetContract, 'player', 'playCompletionFacts.validSelfPlayPass(memberId, tId) == null', 'false'],
    [assertLeaderboardGetContract, 'facts', 'regProbe.setOwnerId(actId);', 'regProbe.setOwnerId(999L);'],
    [assertLeaderboardGetContract, 'facts', 'probe.setMemberId(memberId);\n        probe.setOwnerType(1L); // 1=主题(自玩通行证)', 'probe.setMemberId(999L);\n        probe.setOwnerType(1L); // 1=主题(自玩通行证)'],
  ];
  for (const [check, file, before, after] of mutations) {
    assert.ok(sources[file].includes(before), `负控必须改到真实源码 ${file}: ${before}`);
    const changed = { ...sources, [file]: sources[file].replace(before, after) };
    assert.throws(() => check(changed), { code: 'ERR_ASSERTION' }, `${file}: ${before}`);
  }
});

test('roam GET scope 与后端资源模型一致：tiles 是 member-owned，pois 是全局读取+本人 found 投影', () => {
  const controller = fs.readFileSync(ROAM_CONTROLLER, 'utf8');
  const pois = controller.slice(controller.indexOf('@GetMapping("/pois")'), controller.indexOf('@GetMapping("/nearby-exploreday")'));
  const tiles = controller.slice(controller.indexOf('@GetMapping("/tiles")'), controller.indexOf('@PostMapping("/reveal")'));
  const scopeOf = (marker) => MATRIX.find((row) => row[1] === marker)[4];

  // C-13(2026-09-17):/pois 仍是全局据点列表(不按会员筛资源),只是多带一列「本账号是否已发现」(found)
  // —— 与 /api/city/nodes 同档的 global-read+viewer-personalization;uid 只喂投影,未登录(uid=0)全列 false。
  assert.equal(scopeOf("req('/api/roam/pois'"), 'global-read+viewer-personalization');
  assert.match(pois, /selectPoisNear\([^;]*uid\(\)/);
  assert.equal(scopeOf("req('/api/roam/tiles/page'"), 'self-member-history');
  assert.match(tiles, /Long memberId = uid\(\)/);
  assert.match(tiles, /selectTilesByMember\(memberId,/);
  assert.match(tiles, /selectTilePageByMember\(memberId, cursor, pageSize \+ 1\)/);
});

test('广场商业身份权限仍来自当前账号与授权，不以俱乐部发布角色替代', () => {
  const app = fs.readFileSync(path.join(XCX_ROOT, 'app.js'), 'utf8');
  const policy = app.match(/canUseCommunity:\s*function\s*\(write\)\s*\{([\s\S]*?)\n\s*\},/);
  assert.ok(policy, '必须读取真实当前身份权限投影');
  assert.match(policy[1], /this\._communityFeatureOwner === String\(this\.getUserID\(\) \|\| ''\)/);
  assert.match(policy[1], /this\._communityFeatureAuthorization === this\.getAuthorization\(\)/);
  assert.match(policy[1], /\[write \? 'communityPostWrite' : 'communityPostRead'\] === true/);
  const square = fs.readFileSync(path.join(XCX_ROOT, 'pages/square/list/index.js'), 'utf8');
  assert.match(square, /if \(!write\) this\.setData\(\{ composeShow: false, composeEdit: null \}\)/);
  assert.match(square, /if \(!read\) \{[\s\S]*?list: \[\]/, '读资格失效仍撤下原列表');
  assert.doesNotMatch(square, /submitFormData\s*\(/, '不能复活 master 已迁出的旧发布写入口');
});

test('迁移到真实共用发布器的续登仍绑定原俱乐部、引用、编辑稿与内容，广场写拒绝闸不丢', () => {
  const source = fs.readFileSync(path.join(XCX_ROOT, 'pages/square/components/cy/post-compose/index.js'), 'utf8');
  const publish = source.slice(source.indexOf('    onPublish() {'), source.indexOf('    publishClubPost(content) {'));
  assert.match(publish, /owner: this/);
  assert.match(publish, /target: \(\) => \[this\.data\.clubId, !!this\.data\.editMode, this\._editId/);
  assert.match(publish, /this\.data\.postRef && this\.data\.postRef\.refType/);
  assert.match(publish, /this\.data\.postRef && this\.data\.postRef\.refId/);
  assert.match(publish, /this\.composeSnapshot\(\)/);
  assert.match(publish, /this\._editDataId, this\._editDataType, this\.data\.selectedActivity && this\.data\.selectedActivity\.activityId, this\._composeVisible/);
  assert.match(publish, /\(!that\.data\.clubId \|\| that\.data\.editMode\)[\s\S]*?typeof app\.canUseCommunity !== 'function' \|\| !app\.canUseCommunity\(true\)/);
  assert.ok(publish.indexOf('!app.canUseCommunity(true)') < publish.indexOf('publishIntent.begin('), '拒绝须早于原发布意图与外呼');
  assert.match(publish, /if \(that\.data\.clubId && !that\.data\.editMode\) \{ that\.publishClubPost\(content\); return; \}/);
  const cancel = source.slice(source.indexOf('    cancelPublish() {'), source.indexOf('    isCurrentPublish(pending) {'));
  assert.match(cancel, /this\._loginContinuationEpoch = \(this\._loginContinuationEpoch \|\| 0\) \+ 1/);
  assert.match(cancel, /publishIntent\.settle\(wx, 'square:compose', pending\.memberId, pending\.intent\.key, 'unknown'\)/);
  assert.match(source, /hide\(\) \{ this\._loginContinuationEpoch = \(this\._loginContinuationEpoch \|\| 0\) \+ 1; this\._composeVisible = false; this\.cancelPublish\(\); \}/);
  const club = fs.readFileSync(path.join(XCX_ROOT, 'pages/club/detail/index.js'), 'utf8');
  assert.match(club, /onComposePublished\(\) \{\s*this\.setData\(\{ postRef: null \}\);\s*this\.loadPosts\(\);/);
  assert.doesNotMatch(club, /onPostSubmit\s*\(/, '原俱乐部命令只保留实际组件路径，不恢复被迁出的旧实现');
});

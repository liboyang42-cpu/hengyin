const community = require('../../utils/community-post-api.js').createClient(getApp(), wx);
const { exactSessionId, validRecovery, newSessionKey, archiveRecords } = require('../../utils/roam-recovery.js');
const pixelAvatar = require('../../utils/pixel-avatar.js');
// 城瘾 · 完全自由漫游模式(City Fog Explore)
// 真实腾讯地图 + 地图外屏幕雾;行动揭开城市

const cyModal = require('../../utils/modal.js');
const cyLoading = require('../../utils/loading.js');
const cyToast = require('../../utils/toast.js');
const publishIntent = require('../../utils/publish/publish-intent.js');
const { readPageStyle } = require('../../utils/font-scale.js');
const motion = require('../../utils/motion.js');
const config = require('../../utils/config.js');
const { pickLocation } = require('../../utils/location/location-manager.js');
const analytics = require('../../utils/analytics.js');
// U-171 分享回执的渠道码(埋点只放码,不放中文渠道名以外的任何内容)
const ROAM_SHARE_CHANNEL_CODE = { '广场': 'square', '保存': 'album', '朋友圈': 'moments', '小红书': 'xhs' };
const { activityShareFromEvent } = require('../../utils/activity-share.js');
const { buildPlayHeader, buildPlayHud, createSessionClock } = require('../../utils/play-ui-contract.js');
const {
  PLAY_MAP_STATE_ORDER,
  buildMapA11yLabel,
  buildRoamGoal,
  getPlayMapState,
  getRoamPoiState,
} = require('../../utils/play-state-contract.js');
const { drawPlayStateMarker } = require('../../utils/play-marker-draw.js');
const { readReducedMotion, writeReducedMotion } = require('../../utils/motion-preference.js');
const { readFogOff, writeFogOff } = require('../../utils/roam-fog-preference.js');
const countUp = require('../../utils/count-up.js');
const { selectDemoRoamPois } = require('../../utils/roam-demo-gate.js');
const {
  channelFlags,
  channelsForVisibility,
  clipTrackForSharing,
  clipSavedTrackForSharing,
  isChannelAllowed,
  readSharePrivacy,
} = require('../../utils/roam-route-privacy.js');
const { readNonNegative } = require('../../utils/roam-history-metrics.js');
const { createIdleDetector } = require('../../utils/roam-idle.js');
const { acceptRoamLocation } = require('../../utils/roam-track-filter.js');
const { simplifyTrack, limitTrack } = require('../../utils/roam-track-simplify.js');
const { isRecordList } = require('../../utils/response-shape.js');
const readSession = require('../../utils/session/login-reload.js');
const { fromHttpStatusAbnormal } = require('../../utils/transport/failure-envelope.js');
const { resolveMenuChrome } = require('../../utils/nav-safe-area.js');
const { bindPlayerRoamMemory } = require('../../utils/roam-player-memory.js');
const { syncRoamTileMemory } = require('../../utils/roam-tile-memory-sync.js');
const { ALBUM_SAVE, classifyAlbumSaveError, normalizeAlbumOutcome } = require('../../utils/album-save-outcome.js');
const MOCK_IMG = '/images/index-figma/nearby-card.jpg';
const { onRoamCacheInvalidated } = require('../../utils/roam-cache-epoch.js');
function roamMemory(page) {
  const memory = bindPlayerRoamMemory(page, getApp(), wx);
  if (page._roamCacheOwner !== memory.playerId || !page._roamCacheUnsubscribe) {
    if (page._roamCacheUnsubscribe) page._roamCacheUnsubscribe();
    page._roamCacheOwner = memory.playerId;
    page._roamCacheUnsubscribe = onRoamCacheInvalidated(memory.playerId, () => page._discardRoamLocal());
  }
  return memory;
}

// P2 接真:标准 geohash 编码(7 字符 ≈153m 格),与后端 roam_fog_tile.geohash7 对齐,可互通
// 漫游本地地理数学(geohash 编解码 / 米↔经纬度 / 平面近似距离)收口到 utils/roam-geo(已单测)
const { geohash7, geohashDecode7, m2lat, m2lng, distM } = require('../../utils/roam-geo.js');
const { visitorSourceOf, sourceKey, buildVisitorRequests, shapeVisitorRows } = require('../../utils/roam-shop-visitors.js');
const { fallbackRegion, isPointRevealed, projectRevealCircles } = require('../../utils/roam-screen-fog.js');
// #22:商家 POI 多路来源 id 命名空间互不相干,同一家店会来多条 → 按店名归组(已单测)
const { normName, groupMerchantsByName, stampProgress, doneShopCount, selectNearShopGroups } = require('../../utils/roam-poi-group.js');
const { npcIconKey, pixelDrawSize, collectNpcIconNeeds, markerAvatar } = require('../../utils/roam-npc-marker.js');
// CU-M-54:服务端 POI 行 → 本地点位态(已打卡待核销是独立一档,不能读成 passed)
const { roamPoiLocalState } = require('../../utils/roam-poi-state.js');
const { createRoamSyncStatus, OFFLINE_TEXT: ROAM_SYNC_OFFLINE_TEXT } = require('../../utils/roam-sync-status.js');
const roamPoiFilter = require('../../utils/roam-poi-filter.js');
const { MAP_AVATAR_COLORS } = require('../../utils/play-visual-tokens.js');
// 本地历史只用于下次打开地图时高亮一处已走过的边缘；不画路线、不上报坐标。
const { buildFootprintHint } = require('../../utils/roam-footprint-hint.js');
const { normalizeRunners } = require('../../utils/roam-runners.js');
const presencePrivacy = require('../../utils/roam-presence-privacy.js');
const { currentScene, exitDecision, popScene, pushScene } = require('../../utils/scene-stack.js');
// 个性化地图(暗色底图)与 play 页共用一份配置,见 utils/map-style.js
const { MAP_STYLE } = require('../../utils/map-style.js');
const { getScene } = require('../../utils/scene-registry.js');
const { openScene: openPageScene } = require('../../utils/scene-entry.js');
const { getNodeLevelStyle, buildNodeLevelMarkerStyle } = require('./node-level-style.js'); const entryMap = require('../../utils/roam-entry-map.js');
// 后台定位开关(与 play 页共用,引用计数收口)。★必须在模块顶层:#750 曾把这行插进 req() 函数体,
// bgTracker 变成 req 的局部变量,_startReal 一调就 ReferenceError —— 漫游实时定位整条静默死掉。
const bgTracker = require('../../utils/location/bg-tracker.js');
const lastLocation = require('../../utils/location/last-location.js');

// BL-23:兜底坐标与 utils/location/last-location.js 同源;入口地图优先用上次成功定位
const FALLBACK = { lat: lastLocation.FIXED.latitude, lng: lastLocation.FIXED.longitude };
const REVEAL_M = 55;
// 历史 geohash7 格中心的揭示半径:格宽 ~153m,110m ≈ 半对角线,相邻已探格连成片不留缝。
// 只作用于重开加载的格子历史;实时轨迹仍是 REVEAL_M 细走廊。
const TILE_REVEAL_M = 110;
// 揭雾边缘羽化倍率:核心全透到 1/FOG_FEATHER,向外渐隐到 FOG_FEATHER×半径。
// 纯视觉;isPointRevealed / 到店判定仍用 REVEAL_M 硬半径,不受影响。
const FOG_FEATHER = 1.6;
const FOG_UPDATE_MS = 2000;  // poly 全量替换有一帧透底(闪白),放宽节流降频
const PLAYER_MK = 99;
const STATUS_MARKER_ID = 99997;
/* 后端真 POI(/api/roam/pois)的 marker id 基数。微信 <map> 的 marker id 契约是数字
   (见 utils/roam-hangout.js:4 自述),原来用 'r'+id 字符串下发 —— 真机上针可能点不动,
   而 demo 点位的数字针正常。编进 70 万段:与商家条目(nodeId/id/9000+i)、上面两个固定值、
   下面其他人的头像(800000+下标)都错开。onMarkerTap 仍按 p.id 全等匹配,不需要反解。 */
const ROAM_POI_MK_BASE = 700000;
/* 附近正在漫游的人(原型 otherRunners)。marker id = 基数 + 下标,一次最多 20 个,
   撞不上 POI 的 id(那批来自 nodeId / id / 9000+i / 700000+roamPoiId),也撞不上上面两个固定值。 */
const RUNNER_MK_BASE = 800000;
/* 上报自己的位置:30 s 一次。再密没有意义 —— 服务端把坐标截到约 100 m 一格,
   走不满一格的上报是同一个值。 */
const PRESENCE_MS = 30000;
/* 拉别人的位置:45 s 一次。别人也是 30 s 一报,拉得更勤只会拿到同一批。 */
const RUNNERS_MS = 45000;
// /api/roam/pois 与 nearby-runners 共用查询半径(米)
const ROAM_POI_RADIUS_M = 3000;
const NEAR_M = 80;       // 略放宽,GPS 不准时仍能提示
/* 用户拍板(9-16)「漫游打卡不存在失败态:距离不够就是不能打卡(只提示)」:
   打卡按钮的可用半径必须与后端 /api/roam/shop/visit 的判定同一口径 ——
   SHOP_VISIT_RADIUS_M(100) + ROAM_GPS_TOLERANCE_M(30)。客户端算出来的「还差 X 米」
   和后端拒收的分界点必须一致,不然会出现「提示说能打,服务端说没到」的失败态。 */
const CHECKIN_NEAR_M = 130;
// #23 三店连亮弹层的兜底阈值:配置拉不到时用它(fail-open,不比改动前差)。
// 真源是后端 growth_badge.unlock_rule_json —— 改阈值改那里,这里只是网络挂了时的降级。
const SHOP_BADGE_FALLBACK_THRESHOLD = 3;
const BRAND = '#FFFFFF';
// 星星 = 这家有玩法。金黄取 play-visual-tokens 的单一真源,别在这里再写一遍字面值;
// 原来的奶油 #E2C489 压在深色地图上发灰,已废(2026-09-09)。
const GAME_STAR = MAP_AVATAR_COLORS.gameStar;
// 顶部输入地址那一行占掉的高度。提示栈按它整体下让,不写死绝对 top —— 胶囊位置各机型不同。
const SEARCH_ROW_H = 56;
const SCALE_FAR = 14;        // 开场最远视角(视野半径需 ≤ 迷雾网格 HALF,见 _initFogGrid)
const SCALE_WALK = 17;       // 步行视角(半视野~280m):能看到街道网+POI 标签,对齐 Fog of World 中景
const SCALE_PAUSE_MIN = 13;  // 暂停至少缩到此级别
const OVERVIEW_PAD = 1.55;   // 暂停总览:轨迹半径 × 此系数 + 底栏留白(m)
const CENTER_MS = 500;
// 2026-10-29 V-07:屏幕雾两帧之间最短间隔、离屏画布倍率(见 _scheduleScreenFogRender / _trySetupScreenFog)
const FOG_SCREEN_MIN_MS = 600;
const FOG_SCREEN_DPR = 1;
const LINE_MS = 1500;
const END_HOLD_MS = 3000;
const ROAM_OPENING_MS = 4200;
const ROAM_OPENING_REDUCED_MS = 220;
const OPENING_EMOJI_GLYPHS = ['😆', '🤩', '🥳', '😉', '😂', '😇', '🤗'];
const OPENING_EMOJI_ZONES = [
  { x: [5, 17], y: [14, 25], s: [76, 96] },
  { x: [72, 86], y: [9, 23], s: [54, 72] },
  { x: [11, 29], y: [65, 76], s: [64, 84] },
  { x: [79, 89], y: [52, 68], s: [78, 98] },
  { x: [47, 65], y: [13, 24], s: [54, 72] },
  { x: [3, 14], y: [37, 53], s: [52, 68] },
  { x: [62, 77], y: [70, 79], s: [72, 90] },
];
function openingRandBetween(rand, range) {
  return range[0] + rand() * (range[1] - range[0]);
}
function buildOpeningEmojis(rand) {
  const rng = typeof rand === 'function' ? rand : Math.random.bind(Math);
  return OPENING_EMOJI_GLYPHS.map((glyph, index) => {
    const zone = OPENING_EMOJI_ZONES[index];
    let rotate = Math.round(openingRandBetween(rng, [-29, 29]) + (index - 3) * 6);
    if (Math.abs(rotate) < 8) rotate += rotate < 0 ? -11 : 11;
    return {
      glyph,
      x: Number(openingRandBetween(rng, zone.x).toFixed(1)),
      y: Number(openingRandBetween(rng, zone.y).toFixed(1)),
      size: Math.round(openingRandBetween(rng, zone.s)),
      delay: Math.round(openingRandBetween(rng, [800, 1140])),
      rotate,
      fromX: Math.round(openingRandBetween(rng, [-30, 30])),
      fromY: Math.round(openingRandBetween(rng, [-28, 28])),
      dx: Math.round(openingRandBetween(rng, [-24, 24])),
      dy: Math.round(openingRandBetween(rng, [-24, 24])),
      turn: Math.round(openingRandBetween(rng, [-22, 22])),
    };
  });
}
// 规则改版时递增；说明只在同账号的首次自由漫游展示，玩家可按需展开重看。
const ROAM_INTRO_RULE_VERSION = '2026-07-22';

/** 迷雾渲染契约:
 *  ① screen(主路径):离屏 canvas 生成全黑圆洞 PNG,普通 image 压在 map 上方,真正遮住原生文字。
 *  ② screen 初始化失败:保持全黑 guard + 明示重试,不退回会浮出腾讯文字的 map 内雾。
 *  ⚠️ 旧 overlay/poly 实现【生产已不可达,待删】—— 别照这段注释以为还有一条活的降级路。
 *     `_fogMode` 现在只被赋 null/'screen'/'blocked'/'poly';而 'overlay' **没有任何赋值点**
 *     (screen 接管 setup 后就没了),'poly' 唯一入口 `_fogToPoly` 只被 `_fogFallbackToPoly` 调,
 *     后者三个调用点全在 `_flushFogOverlay` 内、全被 `_fogMode === 'overlay'` 守着 ⇒ 整条链路
 *     (_fogToPoly/_paintFogBase/_stampReveal/_flushFogOverlay/_edgeSlabs/_initFogGrid/
 *      _fogApplyReveal/_updateFogNow/_fogPt + #fogcv + FOG_PX/FOG_ALPHA/FOG_GRID_M/FOG_POLY_LIMIT)
 *     只有单测靠手工 `page._fogToPoly()` / `page._fogMode='overlay'` 点火才跑得到,恒不代表生产行为。
 *     保留只是为了让这次改动的 diff 停在「行为变更」而不混进一次 500 行删除;
 *     删除动作单独走一个 PR,那样它自己的回归才审得清。算法说明留档如下:
 *     overlay:±FOG_HALF_M 世界位图 destination-out 硬边擦除,
 *     导出 PNG → MapContext.addGroundOverlay 贴地(零接缝/边缘平滑)。
 *     poly:±FOG_HALF_M 的 2m 网格合并成矩形 polygon。
 *     ★这条路径 2026-08-04 前被断开(降级 = 不画雾),结果是工具里和 overlay 失败的真机
 *     完全看不到迷雾、且零报错 —— 玩法核心静默消失。改动请保证降级永远仍有雾。
 *  screen 主路径的揭示边缘做径向渐变羽化(FOG_FEATHER);遗留 overlay/poly 仍是清除/全雾硬边。
 *  FOG_ALPHA 统一约束三条路径的浓度(screen 直用,poly 用同档 hex)。 */
const FOG_MAX = 1;           // poly 路径只有「清除 / 全雾」两态,不做透明度过渡环
const FOG_GRID_M = 2;        // poly 硬边完整轮廓误差 ≤1.4m
const FOG_POLY_LIMIT = 500;
const FOG_RENDER_MIN_HALF_M = 80; // 离散历史过多时只缩玩家周边渲染窗,网格/揭雾半径不降级
const FOG_HALF_M = 2500;      // 雾覆盖半径(m),需 ≥ SCALE_FAR 的可视半径
const FOG_PX = 1024;          // 雾位图边长(px),Canvas 圆弧抗锯齿后导出
const FOG_ALPHA = 0.7;        // 未探索区雾浓度。2026-08-20 用户裁决(对比条选档):70% 浓度、留三成透出,雾下地图可辨,不再全遮
                              // 的探索游戏观感,推翻原来「腾讯地图道路与 POI 必须始终可读」的 0.24 上限。
                              // 代价是没走过的区域看不到路 —— 这是有意的,别当 bug 调回去。
const FOG_FLUSH_MS = 2200;    // 位图导出 + overlay 刷新节流
const FOG_OVERLAY_ID = 501;

// m2lat / m2lng / distM 收口到 utils/roam-geo(见文件顶部 require)
function fogLevel(d, R) {
  return d <= R ? 0 : FOG_MAX;
}
// 注:hideLoading 全仓零消费方(死参数);真正抑制自动错误 toast 的开关是 silentError(app.js:343)。
// 要自定义错误文案的调用方须传 { silentError: true },否则会被自动 toast 一次 + 自己再 toast 一次 = 双弹。
function req(url, method, data, opts) {
  const app = getApp();
  return new Promise((resolve) => {
    community.request({
      url, method, data, hideLoading: true,
      ...(opts || {}),
      success: (res) => resolve(res || {}),
      // request-client 对 HTTP 非 200 只调 successStatusAbnormal；必须结束 Promise，
      // 否则揭雾、结算等调用会永久保持进行中。
      successStatusAbnormal: (res, statusCode) => {
        const code = readSession.failureStatus(res, statusCode);
        resolve(code === 401 || code === 403 ? { code } : fromHttpStatusAbnormal(res));
      },
      fail: (res, statusCode) => {
        if (res && res.reason === 'sessionChanged') { resolve(res); return; }
        const code = readSession.failureStatus(res, statusCode);
        if (code === 401 || code === 403) { resolve({ code }); return; }
        resolve({ code: 'fail', netFail: true, msg: '网络异常，请重试' });
      },
    });
  });
}

// ★ 全页错误态的总根因就在上面那个 fail 分支:它把网络故障降格成一个返回值 code:'fail',
//   而这个哨兵此前全仓零处判断 —— 每个调用方都是 if (res.code != '200') return,错误信息就此蒸发。
//   更隐蔽的是:transport/request-client.js:105-111 只在「调用方没给 fail 回调」时才自动 toast
//   网络异常;req() 恒给 fail 回调 ⇒ 本页把那次 toast 也一并吞了 = 断网时彻底无声。
//   (后端业务错 code!=200 仍由 app 自动 toast,除非传 silentError ⇒ 页面别再补第二次,会双弹。)
//   下面两个判据把哨兵变成活的,三态必须分开,否则「错误」会伪装成「空态」:
//     netFail(res)  = 网络挂了(页面必须自己说,没别人会说)
//     httpFail(res) = HTTP 已返回但状态异常；写请求结果未知，不能开放盲重试
//     apiOk(res)    = 后端确认成功
//     三者皆非      = 后端明确说「没有 / 拒绝」——这才是真空态
function apiOk(res) { return !!res && (res.code == 200 || res.code == '200'); }
function netFail(res) { return !!res && res.netFail === true; }
function httpFail(res) { return !!res && res.httpFail === true; }

// ===== 漫游提示层（复用既有 Event Gate）=====
// 复用上面的 req():它已支持 opts 展开,不必另造请求封装。
// GET 由 request-client 默认注入 Authorization；真正匿名的调用点才显式 auth:false。
// 官方活动虽可匿名访问，但登录态会返回报名进度，不能强制降为匿名请求。
const { createRoamNpcCoordinator } = require('../../utils/roam-npc-coordinator.js');
const { activeRoamEventCard, applyEventCard } = require('../../utils/roam-official-active.js');
const { latestTrackThumb, latestStampImage, latestBadgeImage } = require('../../utils/roam-passport-tiles.js');
const { persistPhoto, retainRecentPhotos, archivedSessionPhotos, droppedSavedPhotos, evictedSavedPhotos, releaseSavedPhotos } = require('../../utils/roam-session-photos.js');

const NPC_JSON_HEADER = { header: { 'content-type': 'application/json' } };

function npcOk(res) {
  return res && (res.code == 200 || res.code == '200');
}

function buildNpcPorts(page) {
  return {
    clock: { now: Date.now, setTimeout: setTimeout, clearTimeout: clearTimeout },
    featurePort: { isEventOn: () => ((getApp().globalData.features || {}).roamNpcEvent === true) },
    apiPort: {
      fetchProfile: (cb) => {
        req('/api/ai/npc/profile', 'GET', { scope: 'global' }, NPC_JSON_HEADER).then((res) => {
          const npc = npcOk(res) && res.data && res.data.npcs && res.data.npcs[0];
          npc ? cb(null, npc) : cb(new Error('no_profile'));
        });
      },
      fetchEventLine: (profileId, eventType, cb) => {
        // cacheOnly=true 必传:否则后端查不到话术会回落 greeting,变成开场白复读机
        req('/api/ai/npc/event', 'GET',
          { profileId: String(profileId), eventType, cacheOnly: 'true' }, NPC_JSON_HEADER).then((res) => {
          npcOk(res) && res.data ? cb(null, res.data) : cb(new Error('no_line'));
        });
      },
    },
    viewPort: {
      showBubble: (vm, cb) => {
        page.setData({ roamPrompt: vm ? { show: true, line: vm.line } : { show: false } }, () => cb(null));
      },
      // key 是 finish 不是 fin(fin 是别处的局部变量);用路径写法只补字段,
      // 避免和 _finishRoam 里 setData({finish: fin}) 的整体覆盖打架。
      showFinishLine: (vm) => { page.setData({ 'finish.roamPrompt': vm.line }); },
    },
    analyticsPort: { track: (n, p) => analytics.track(n, p) },
  };
}

Page({
  data: {
    privacyGateShow: false,
    // BL-01 漫游点位筛选(范围 + 只看未到过),值在 utils/roam-poi-filter.js。
    poiFilter: { radiusM: 3000, unvisitedOnly: false }, poiFilterTabs: roamPoiFilter.radiusTabs(),
    screen: 'entry-map', entryMarkers: [], entryCards: [], entryFarCards: [], entryActivity: null, entryTeam: null, entrySheet: '', entrySheetView: 'main', entrySheetIn: false, entryLayers: { activities: true, teams: true, posts: true }, entryLoadError: false, entryReadAccess: '',
    introError: '',
    // 个性化地图透传给本页三个 <free-map>(起始预览 / 主漫游图 / 结算图),三处必须同源,
    // 否则同一次漫游里会出现一亮一暗。值在 utils/map-style.js。
    fmSubkey: MAP_STYLE.subkey,
    fmLayerStyle: MAP_STYLE.layerStyle,
    // 起始页(Figma 3956:12602/12809)。modeTitle 是模式名:本页恒为自由漫游,
    // 城市定向/自由探索复用同一版式时只换这三项(标题 / 卡片 / 主行动文案)。
    modeTitle: '自由漫游',
    introTab: 'start',            // start=开始漫游 | passport=漫游护照
    introTabs: [{ key: 'start', label: '开始漫游' }, { key: 'passport', label: '漫游护照' }],
    avatar: '',
    // 护照统计读取本人服务端完成事实；本机照片保持本机补充。
    passportError: false,
    passport: { tiles: [], trips: [] }, ppOpen: { show: false, on: false, icon: '', title: '', value: '', style: '' }, // ppOpen:护照方卡放大层
    introCardIdx: 0,
    fogOff: false, noFogPolys: [], // P-32:迷雾开关(本机偏好);关时 map-polygons 换成空数组
    introCards: [
      { key: 'start', icon: 'route', title: '开始漫游', sub: '走到哪，雾散到哪' },
      { key: 'map', nav: '/subpackageRoam/nearby/index', icon: 'map', title: '打开地图', sub: '寻找组队' },
      { key: 'my-teams', nav: '/subpackageRoam/nearby/index?sheet=mine', icon: 'tab-club', title: '我的队伍', sub: '已加入 · 申请中' },
    ], introStartLive: '', introTeamsLive: '附近正在组队', // 卡片实时行只放页面已有真数据;空串=隐藏,不摆占位
    boundEvent: null, // 官方活动绑定横幅(带 eventId 进入时显示)
    // V2 活动叠层只在玩家显式从活动进入或重新打开时显示；关闭后不影响日常漫游。
    eventOverlay: { show: false, missions: [], points: [], feedback: null },
    topSafe: 54,
    searchTop: 96,        // 顶部输入地址的顶边:胶囊底下(§6.6)
    searchPlaces: [],     // 顶部输入地址的候选,见 _searchPlaces
    chrome: { actionTop: 28, actionRight: 12, contentTop: 76, sheetTop: 69 },
    mapReady: false,
    heading: 0,
    center: { lat: FALLBACK.lat, lng: FALLBACK.lng },
    mapScale: 15,
    markers: [],
    polyline: [],
    sparkCircles: [],
    fogPolys: [],
    fogScreenReady: false,
    fogScreenLayers: [],
    fogScreenGuard: false,
    paused: false,
    roamIdle: false,
    roamPanelExpanded: false,
    reducedMotion: false,
    openingEmojis: [],
    sceneStack: [],
    sceneCurrent: null,
    sceneConfirm: { show: false, action: null, pending: null },
    playHeader: null,
    playHud: null,
    endHoldPct: 0,
    mapLoad: 'pending',
    // 据点接口失败时为真；它只证明据点没加载出来，不能据此宣称整机或系统离线。
    // 本地进度(roam_tiles 迷雾格)独立保留。
    // 商家图层(/api/map/nearby)失败时为真。与 poiOffline 同一层展示(离线横幅),
    // 但分开记:两个接口一好一坏时不能互相cover,也不能把「这一带没有店」当失败。
    merchantOffline: false,
    poiEmpty: false,
    roamMapA11y: '探索地图：正在加载地点状态',
    /* ⚠️ 读数真值那一份是 this._stats(实例字段,声明在 data 外面),不是 data.stats。
       它一行都不进 wxml —— 渲染的是下面的 statsShown。留在 data 会被 U4
       「死数据字段」判成 A2(只当内部状态却占了一次渲染)。 */
    /* 清空本机缓存的确认(不可逆,必须问一次)。用原型自己那层半屏问,
       不用 wx.showModal —— 仓库禁原生弹窗,而且系统弹窗里的字文案闸也看不见。 */
    clearAsk: false,
    /* 设置页三行权限态。以前 wxml 读 gpsOk 而 js 从没写过这个字段 ⇒ 三行恒显示「关」,
       已授权的用户被告知自己没授权。真值在 onShow / 打开设置时问 wx.getSetting(见 _syncRoamPermission)。 */
    gpsOk: false,
    bgGpsOk: false,
    presenceVisible: false,
    presenceSubmitting: false,
    /* 工具抽屉那几行的副标题(现算,不写死)。 */
    more: { funText: '', runnerText: '', stampText: '' },
    /* 半屏进场用的开关(原型 .sheet 的 @keyframes up 在这儿是 transition)。 */
    sceneIn: false,
    /* 原型的取景卡(camPanel)是浮在地图上的一张卡,不是另开一页。 */
    camOpen: false,
    /* 原型 f-fun / f-discover 的商家横滑白卡。空数组 = 不在,读数卡照常渲。 */
    shopStrip: { rows: [], idx: 0 },
    /* 原型 f-topic / f-city 点主题针出的主题半屏;null = 不在。 */
    topicView: null,
    /* 原型 f-checkin 打卡之后的「这里能做什么」;null = 不在。 */
    checkinView: null,
    /* 原型 f-multi 顶部那张「谁点亮了哪块」;null = 不在。 */
    multiView: null,
    /* 点开的那个人(原型 otherSheet 的正文)。地图上那一层在 markers 里,不在这儿。 */
    runnerView: null,
    /* 读数卡上真正渲染的是这一份:数字滚上去,不是「啪」地出现(原型 countUp)。
       time 带冒号不滚,由 _rollStat 原样透传。 */
    statsShown: { time: '00:00', shops: 0, explorePct: 0, distance: '0.0' },
    drawerModules: [
      { key: 'task', label: '官方活动任务', value: '官方策展活动' },
      { key: 'medal', label: '勋章', value: '探索护照' },
      { key: 'reward', label: '奖励', value: '优惠券' },
      { key: 'record', label: '记录', value: '足迹卡' }
    ],
    currentAddress: '还没定位',
    // Immersive §7「当前目标与下一步始终可见」:常驻目标层(见 _syncGoal)。
    // 本页原本只有过去时战绩(ff-stats/adbar),下一步全靠三张互斥且可关闭的浮卡,关掉即无重入口。
    goal: { stateKey: 'target', icon: 'walk', text: '', act: '', readerLabel: '当前目标。继续行走，点亮这片街区。' },
    // 错误/降级三件套 —— 改动前全页 data 里一个 error/offline 字段都没有:
    // offline = 最近一次请求网络挂了(§7.4 要求的「离线状态」可见;本地进度本来就有,见 _persistTiles)
    // locErr  = 定位没拿到/被拒(不许伪装成空态,见 trackTip)
    // gpsWeak = 到点判定的精度/漂移原因(原本三道守卫全是静默 return)
    offline: false,
    revealWriteUnknown: false,
    // BL-03 位置上报连续网络失败 / 系统断网时的提示(utils/roam-sync-status.js)。
    syncOffline: false, syncOfflineText: ROAM_SYNC_OFFLINE_TEXT,
    recoveryCanContinue: false,
    recoveryError: '',
    // 2026-10-27 owner P-27:「已恢复上次漫游」行已删,recoveryAvailable 无人渲染,随之去掉(死字段门禁 U4)
    roamSessionReady: false,
    locErr: false,
    gpsWeak: '',
    nearestM: 0,
    poiErr: false,
    trackTip: '这次没有记录到轨迹',
    /* 探店打卡卡:active=正在确认/已确认,checkinOk=服务端真的记上了(成功才置真)。
       用户拍板(9-16)打卡无失败态 → 不再有 checkinErr/checkinRetryable 失败字段。 */
    visit: { active: false, checkinOk: false, poi: {}, photos: [] },
    locationPicker: { show: false },
    nearbyBanner: { show: false },
    nearbyPois: [],
    nearbyIdx: 0,
    promptTop: 100,
    paceCard: { show: false, title: '', body: '', demo: false },
    medal: { show: false },
    share: { show: false, idx: 0, squarePostUnknown: false, visibility: 'public', channels: ['广场', '朋友圈', '小红书', '保存'], can: { square: true, moments: true, xhs: true, save: true } },
    discoverReward: { show: false, poi: {} },
    celebrationEvent: '',
    roamPrompt: { show: false },    // 漫游提示（prompt-slot 栈最低优先级）
    footprintHint: { show: false }, // 本地足迹边缘，优先于通用提示、让位于到点/商家/活动
    finish: { photos: [] },
    archSegs: [], archPois: [], archEnd: {},
  },

  // 隐私授权闸:app.js 优先调这里(真弹窗),没有这个方法的页面才回退到 /pages/privacy 路由页。
  showPrivacyGate() {
    this.setData({ privacyGateShow: true });
  },
  onPrivacyGateSettled() {
    this.setData({ privacyGateShow: false });
  },

  buildRoamIntroSeenKey() {
    const app = getApp();
    const userId = (app.getUserID && app.getUserID()) || 'guest';
    return 'roam_intro_seen_' + ROAM_INTRO_RULE_VERSION + '_' + userId + '_roam';
  },
  markRoamIntroSeen() {
    const key = this._introSeenKey || this.buildRoamIntroSeenKey();
    try { wx.setStorageSync(key, true); } catch (e) {}
  },
  openScene(id, params = {}) {
    const next = getScene(id, params);
    // G-1(2026-10-27):page 档场景(漫游历史 / 优惠券)是页面,不在地图上压 psheet
    if (next.variant === 'page' && next.route) { openPageScene(id, params); return true; }
    if (exitDecision(this.data.sceneStack, 'close') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'replace', pending: next } });
      return false;
    }
    const sceneStack = pushScene(this.data.sceneStack, next);
    /* sceneIn 先 false 再置 true:原型 sheet 是 @keyframes up 进场,这里换成同参数的
       transition(仓库 keyframes 只减不增),所以必须留一帧当起点,否则直接跳到终态。 */
    this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneIn: false, sceneConfirm: { show: false, action: null, pending: null } });
    this._sceneInTimer = setTimeout(() => this.setData({ sceneIn: true }), 20);
    return true;
  },
  backScene() {
    if (exitDecision(this.data.sceneStack, 'back') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'back', pending: null } });
      return;
    }
    const sceneStack = popScene(this.data.sceneStack);
    this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
  },
  closeScene() {
    if (this._sceneInTimer) { clearTimeout(this._sceneInTimer); this._sceneInTimer = null; }
    this.setData({ sceneStack: [], sceneCurrent: null, sceneIn: false, sceneConfirm: { show: false, action: null, pending: null } });
  },
  requestSceneClose() {
    if (exitDecision(this.data.sceneStack, 'close') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'close', pending: null } });
      return;
    }
    this.closeScene();
  },
  confirmSceneDiscard() {
    if (this.data.sceneConfirm.action === 'back') {
      const sceneStack = popScene(this.data.sceneStack);
      this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
      return;
    }
    if (this.data.sceneConfirm.action === 'replace' && this.data.sceneConfirm.pending) {
      const sceneStack = pushScene(this.data.sceneStack, this.data.sceneConfirm.pending);
      this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
      return;
    }
    this.closeScene();
  },
  cancelSceneDiscard() { this.setData({ sceneConfirm: { show: false, action: null, pending: null } }); },
  setSceneDirty(dirty = true) {
    if (!this.data.sceneStack.length) return;
    const sceneStack = this.data.sceneStack.slice();
    sceneStack[sceneStack.length - 1] = { ...sceneStack[sceneStack.length - 1], dirty: dirty === true };
    this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack) });
  },
  openChildScene(event) {
    const detail = event.detail || {};
    if (detail.id) this.openScene(detail.id, detail.params || {});
  },
  /* 投一张换一张(§6.4)。整套在分包里 —— 信箱那张实物图 37KB,主包资源棘轮只准降,
   * 而这是点进来才用的一段玩法,分包按需下载正合适。 */
  openCityStamp(e) {
    const place = (e && e.detail && e.detail.place) || this.data.currentAddress || '这一站';
    if (this._navigating) return; this._navigating = true; this.closeScene();
    wx.navigateTo({ url: '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent(place), complete: () => { this._navigating = false; } });
  },
  /* 原型 f-fun(查找附近好玩的)与 f-discover(发现商家)是同一条横滑白卡:
     数据就是地图上那批附近商家,不另拉一份接口 —— 地图上看得见的,卡上就看得见。
     原型的规则是「读数卡让位给横滑条」,所以它在时读数卡不渲染(见 wxml 的 wx:if)。 */
  openShopStrip() {
    const rows = (this._pois || [])
      .filter((p) => p.cat === 'merchant' && p.lat && p.lng)
      .slice(0, 8)
      .map((p) => ({
        ...(visitorSourceOf(p) || {}),
        id: String(p.regId || p.nodeId || p.name),
        name: p.name,
        address: p.desc || '',
        meta: p.metaLine || '商家 · 探店',
        // 原型这枚点用颜色区分状态;现码这一层只知道「点亮没点亮」,所以只有两档。
        dot: p.state === 'fog' ? '#C7C7C7' : '#16A34A',   /* ds-ok 原型 .poicard__dot 未知灰 / 营业绿 */
        img: p.imgUrl || '',
        lat: p.lat, lng: p.lng,
      }));
    if (!rows.length) { cyToast('这一片还没有合作商家'); return; }
    this.closeScene();
    this.setData({ shopStrip: { rows, idx: 0 } });
    this._loadShopStripVisitors(rows);
  },
  closeShopStrip() {
    this._shopStripEpoch = (this._shopStripEpoch || 0) + 1;
    this.setData({ shopStrip: { rows: [], idx: 0 } });
  },
  /* BL-70:横滑卡头像堆 + 打卡人数(/api/roam/shop/visitors)。total=0 不展示;
     失败/迟到回包一律不动卡片 —— 头像堆只是附加信息,不能挡「去这儿」。 */
  _loadShopStripVisitors(rows) {
    const epoch = this._shopStripEpoch = (this._shopStripEpoch || 0) + 1;
    buildVisitorRequests(rows).forEach((body) => {
      req('/api/roam/shop/visitors', 'POST', {
        sourceType: String(body.sourceType),
        sourceIds: body.sourceIds,
      }, { silentError: true }).then((res) => {
        if (epoch !== this._shopStripEpoch || !apiOk(res)) return;
        const byKey = shapeVisitorRows(body.sourceType, res.data);
        const patch = {};
        (this.data.shopStrip.rows || []).forEach((row, i) => {
          const hit = row && row.sourceType === body.sourceType ? byKey[sourceKey(row.sourceType, row.sourceId)] : null;
          if (hit) patch['shopStrip.rows[' + i + '].visitors'] = hit;
        });
        if (Object.keys(patch).length) this.setData(patch);
      });
    });
  },

  /* ══ 原型 f-topic(自由探索)/ f-city(城市定向):点带主题的针,出主题半屏 ══
     判据是这枚针挂在哪个主题上 —— productType 1=城市定向 2=自由探索,
     和地图上那圈描边色、搜索结果针的颜色是同一个字段,不另立一套。 */
  openTopicView(poi) {
    if (!poi || !poi.topicId) return false;
    /* ⚠️ 主题的名字与介绍**不在点位数据里** —— 点位只知道自己挂在哪个 topicId 上。
       所以这里必须真拉一次 /api/topic/info-to-user;拉不到就退回原来那张商家白卡,
       绝不拿点位的店名去冒充主题名(那是画一个看起来对、其实是假的壳)。 */
    const free = Number(poi.productType) === 2;
    const that = this;
    getApp().sendRequest({
      url: '/api/topic/info-to-user', method: 'POST', data: { id: poi.topicId },
      hideLoading: true, silentError: true,
      success(res) {
        const d = res && res.code == 200 && res.data ? (res.data.topic || res.data) : null;
        if (!d || !d.name) { that._fallbackToShopCard(poi); return; }
        const list = Array.isArray(res.data && res.data.activityList) ? res.data.activityList : [];
        that.setData({
          topicView: {
            topicId: String(poi.topicId),
            tone: free ? 'free' : 'city',
            tag: free ? '自由探索' : '城市定向',
            eyebrow: [poi.distM ? poi.distM + 'm' : '', free ? '不限顺序' : '按顺序'].filter(Boolean).join(' · '),
            title: d.name,
            desc: d.description || d.intro || '',
            pics: [d.coverUrl || d.cover || poi.imgUrl].filter(Boolean),
            // 三格只放真读到的:场次数、走法、距你。场次没读到按 UI-04 显示 0;
            // 「距你」是相对位置测量(0m 会读成就在脚下),读不到仍留「—」。
            stats: [{ v: String(list.length || 0), l: '场次' },
                    { v: free ? '不限顺序' : '按顺序', l: '走法' },
                    { v: poi.distM ? poi.distM + 'm' : '—', l: '距你' }],
          },
          nearbyBanner: { show: false }, topicIn: false,
        }, () => that._topicEnter());
      },
      fail() { that._fallbackToShopCard(poi); },
    });
    return true;
  },
  /* 主题拉不到时的退路:还是把这枚针当普通商家点,出原来那张白卡 —— 不留空屏。 */
  _fallbackToShopCard(poi) {
    const group = groupMerchantsByName(this._pois).find((g) => g.all.indexOf(poi) >= 0);
    this.setData({
      nearbyBanner: { show: true },
      nearbyPois: [group ? this._mkShopCard(group) : poi],
      nearbyIdx: 0,
      paceCard: { show: false, title: '', body: '', demo: false },
    });
  },
  closeTopicView() { this._topicLeave(); },
  openTopicPageFromView() {
    const t = this.data.topicView;
    if (!t || !t.topicId) return;
    this.setData({ topicView: null });
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/topic/index/index?id=' + encodeURIComponent(t.topicId), complete: () => { this._navigating = false; } });
  },

  /* 原型 f-game(点商家 · 这家有游戏)在本仓没有单独一屏:
     它的判据「这家挂着玩法」= 这家挂着主题,和上面 f-topic 是同一个条件;
     而「有什么游戏、怎么开始」的正文本来就由据点半屏 roam-poi-detail 渲染。
     再画一张只会是同一状态的两个壳,所以合进上面那一条路。 */

  /* ══ 原型 f-checkin:打卡之后这里能做什么 ══
     两条都是现成的入口,这一屏只是把「接下来干嘛」说出来 —— 原来打完卡就没有下文。 */
  openCheckinView(poi, timeLabel) {
    this.setData({
      checkinView: {
        name: (poi && poi.name) || this.data.currentAddress || '这一站',
        sub: ['已打卡' + (timeLabel ? ' ' + timeLabel : ''), '在这儿留下点什么'].join(' · '),
        place: (poi && poi.name) || this.data.currentAddress || '这一站',
      },
    });
  },
  closeCheckinView() { this.setData({ checkinView: null }); },
  onCheckinStamp() {
    const place = (this.data.checkinView && this.data.checkinView.place) || this.data.currentAddress || '这一站';
    this.setData({ checkinView: null });
    // 不带 kind 会落到默认的「今日城市签」(3-24)
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageRoam/citystamp/index?kind=sticker&place=' + encodeURIComponent(place), complete: () => { this._navigating = false; } });
  },
  onCheckinSign() {
    const place = (this.data.checkinView && this.data.checkinView.place) || this.data.currentAddress || '这一站';
    this.setData({ checkinView: null });
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent(place), complete: () => { this._navigating = false; } });
  },

  /* ══ 原型 f-multi:谁点亮了哪块 ══
     地盘 % 每个人自己那份已经由 /api/roam/nearby-runners 带回来了(explorePct),
     自己那一行取本机读数 —— 不另拉接口,也不编。 */
  openMultiView() {
    const runners = this._runners || [];
    const rows = [{
      key: 'me', name: '你', color: '#4ADE80', avatar: '', initial: '你',   /* ds-ok 原型 f-multi 里「你」是绿的 */
      pct: Math.max(0, Math.min(99, Number(this._stats.explorePct) || 0)),
    }].concat(runners.map((r) => ({
      key: 'r' + r.memberId, name: r.nickname, color: r.color,
      avatar: r.avatar, initial: r.initial, pct: r.explorePct,
    })));
    this.setData({ multiView: { count: rows.length, rows } });
  },
  closeMultiView() { this.setData({ multiView: null }); },

  /* 2026-09-15 地图组队 P 方案:原型 f-hangout「在雾里发现一个局」整卡退场 ——
     搭子局(create/join/report)已下线,后端 /api/roam/hangout/nearby 不再返回局图层,
     这一档永远不会有数据;留着只会是一张点进去落空的死入口。附近的队伍走
     HANGOUT_INTRO_CARD 与 /subpackageRoam/nearby/index,这里不再插一档。 */
  onShopStripSwipe(e) {
    const idx = (e && e.detail && e.detail.current) || 0;
    this.setData({ 'shopStrip.idx': idx });
    const row = this.data.shopStrip.rows[idx];
    if (row && row.lat && row.lng) this.setData({ center: { lat: row.lat, lng: row.lng } });
  },
  /* 「去这儿」= 把地图移过去并打开那家的据点半屏,与点地图上的针同一条路。 */
  onShopStripGo(e) {
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id;
    const row = (this.data.shopStrip.rows || []).find((r) => String(r.id) === String(id));
    if (!row) return;
    this.closeShopStrip();
    const poi = (this._pois || []).find((p) => String(p.regId || p.nodeId || p.name) === String(id));
    if (poi && poi._roamId) this.openScene('roam-poi-detail', { poiId: poi._roamId });
    else if (row.lat && row.lng) this.setData({ center: { lat: row.lat, lng: row.lng } });
  },
  openStampCamera() {
    // 集邮相机是原生能力页,它扛着幂等键与 pending 对账(离页也能续上),不并进浮层。
    // 2026-09-11 换掉的是那页的长相:拟物机身退场,里面也是原型那张取景卡。
    if (this._navigating) return; this._navigating = true; this.closeScene();
    wx.navigateTo({ url: '/subpackageP3/pages/stamp-camera/index/index', complete: () => { this._navigating = false; } });
  },
  blockSceneTouch() {},
  /* 半屏本体吃掉冒泡,免得点正文把自己关了(原型的 .sheet 也不接压暗层那一层点击)。 */
  noop() {},
  /* 原型的 sheet 只有一枚 ✕,它去哪由 sheet(inner, closeTo) 的 closeTo 决定 ——
     有上一层就回上一层,没有就整个收掉。现码把这条判断收在这儿:
     能返回的场景(canBack)走 backScene,否则走 requestSceneClose(脏态确认仍由它管)。 */
  onProtoSheetClose() {
    const cur = this.data.sceneCurrent;
    // 历史分享 sheet(R9-44):它是顶掉历史详情压进来的,关它要还回历史详情,
    // 不能走 requestSceneClose 把整个场景栈清空回地图。
    if (cur && cur.id === 'roam-share' && this._shareReturnStack && this._shareReturnStack.length) {
      this.closeShare();
      return;
    }
    if (cur && cur.canBack) this.backScene();
    else this.requestSceneClose();
  },
  openRoamRules() {
    this.openScene('roam-rules');
  },

  // ===== 起始页(Figma 3956:12602/12809)=====
  goProfile() { wx.switchTab({ url: '/pages/member/index/index' }); },   // tabBar「我的」
  switchIntroTab(e) {
    const introTab = (e.detail && e.detail.key) || (e.currentTarget && e.currentTarget.dataset.tab) || 'start';
    if (introTab === this.data.introTab) return;
    this.setData({ introTab });
    if (introTab === 'passport') { this._loadIntroPassport(); this._loadActiveRoamEvents(); }
  },
  /** 服务端已完成事实作为护照统计；本机照片仅作本机补充，不上传。 */
  async _loadIntroPassport() {
    if (this._passportReader) this._passportReader.dispose();
    const { createReader } = require('../../utils/roam-passport-read.js');
    const reader = this._passportReader = createReader(getApp(), wx);
    const scope = this._roamReadScope(), startGeneration = this._roamStartGen;
    const current = () => reader.current() && scope() && !this._unloaded && !this._hidden
      && startGeneration === this._roamStartGen && reader === this._passportReader;
    this.setData({ passportLoading: true, passportError: false, passport: { stats: [], tiles: [], plays: [] } });
    let stamps = null, localRows = [];
    try { localRows = reader.local(); stamps = localRows.reduce((n, row) => n + (Array.isArray(row.photos) ? row.photos.length : 0), 0); } catch (_) { /* Keep server read independent of local storage. */ }
    let summary;
    try { summary = await reader.summary(); }
    catch (_) {
      if (!current()) return;
      this._activeRoamToken = null; this._activeRoamEventId = '';
      this.setData({ passportLoading: false, passportError: true, passport: { stats: [], tiles: [], plays: [] } });
      return;
    }
    if (!current()) return;
    const value = n => n == null ? '—' : n;
    this.setData({
      passportLoading: false, passportError: false,
      passport: {
        stats: [
          { k: '漫游', v: summary.trips },
          { k: '探店', v: value(summary.shops) },
          { k: '活跃日', v: value(summary.days) },
          { k: '公里', v: summary.distanceM == null ? '—' : (summary.distanceM / 1000).toFixed(1) },
        ],
        // 2026-10-27 owner P-29:三张档案卡用真实图片(最近轨迹 / 最新邮票 / 最新点亮勋章);拿不到用各自的插画图标(2026-10-28 业主:原图标太丑)
        tiles: [
          { key: 'history', icon: 'route', title: '漫游记录', value: summary.trips + ' 次已完成旅程', img: latestTrackThumb(localRows), art: '/images/roam/tile-route.svg' },
          { key: 'stamp', icon: 'image', title: '集邮册', value: stamps == null ? '本机邮票暂不可读' : stamps + ' 张本机邮票', img: latestStampImage(localRows), art: '/images/roam/tile-stamp.svg' },
          { key: 'badge', icon: 'star', title: '勋章墙', value: '我的徽章', img: '', art: '/images/roam/tile-medal.svg' },
          // 2026-10-14 随漫游上线的两项(读数在各自页面现读,方卡不摆数字)
          { key: 'goals', icon: 'flag', title: '探索目标', value: '给自己定个小目标' },
          { key: 'capsule', icon: 'clock', title: '时光胶囊', value: '写给以后的自己' },
        ],
        // 2026-10-28 业主:「官方活动就是漫游」—— 大卡常驻(不再是「暂未开放」灰卡),有可参与的活动时换上它的封面(_loadActiveRoamEvents)
        plays: [
          { key: 'event', icon: 'calendar', title: '官方活动', sub: '限时任务与奖励', cover: '' }, { key: 'discover', icon: 'discover-shop', title: '发现附近店铺', sub: '现在能去哪', cover: '' },
        ],
      },
    });
    this._loadActiveRoamEvents();
    if (typeof getApp().sendRequest === 'function') this._loadPassportBadge();
  },
  // P-29:勋章墙方卡取最新点亮的那枚勋章图;失败静默,方卡保持空态插画
  _loadPassportBadge() {
    const token = {};
    this._passportBadgeToken = token;
    req('/api/medal/wall', 'POST', {}, { silentError: true }).then((res) => {
      if (this._passportBadgeToken !== token || !apiOk(res) || !res.data) return;
      const app = getApp();
      const raw = latestBadgeImage(res.data.medals);
      const img = raw && typeof app.getImgUrl === 'function' ? app.getImgUrl(raw) : raw;
      const tiles = (this.data.passport && this.data.passport.tiles) || [];
      const idx = tiles.findIndex((t) => t.key === 'badge');
      if (img && idx >= 0) this.setData({ ['passport.tiles[' + idx + '].img']: img });
    });
  },
  retryPassport() { return this._loadIntroPassport(); },
  onPassportTileTap(e) {
    const key = e.currentTarget.dataset.key; if (!e.ppExpanded && this._ppExpand(key)) return; // 2026-10-22:先放大成大卡再执行原动作
    if (key === 'history') this.openRoamHistory();
    else if (key === 'stamp') this.goStampAlbum();
    // ⚠️ 勋章墙只能走页面:它是 <canvas type="webgl">,旧式原生组件不支持同层渲染,
    //    塞进场景弹窗会盖在遮罩和关闭钮之上(页面里那层 cover-view 就是为此存在的)
    else if (key === 'badge') { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageP3/pages/badge-wall/index/index', complete: () => { this._navigating = false; } }); }
    else if (key === 'goals') wx.navigateTo({ url: '/subpackageTools/exploration-goals/index' });
    else if (key === 'capsule') wx.navigateTo({ url: '/subpackageTools/capsule/index' });
    else this.openPassport();
  },
  // BL-41:护照「官方活动」大卡读可参与的漫游官方活动;失败静默,卡片保持原文案。
  _loadActiveRoamEvents() {
    if (this.data.passportError) return;
    const current = this._roamReadScope();
    const token = {};
    this._activeRoamToken = token;
    this._activeRoamEventId = '';
    req('/api/official/active-for-roam', 'GET', {}, { silentError: true }).then((res) => {
      if (!current() || this._unloaded || this._activeRoamToken !== token || !apiOk(res)) return;
      const card = activeRoamEventCard(res.data);
      const passport = this.data.passport || {};
      if (!card || !Array.isArray(passport.plays)) return;
      this._activeRoamEventId = card.eventId;
      // P-29:没有可参与活动时不摆灰卡;有活动才把官方活动大卡插到最前
      const plays = passport.plays.some((p) => p && p.key === 'event') ? passport.plays
        : [{ key: 'event', icon: 'calendar', title: '官方活动', sub: '限时任务与奖励', cover: '' }].concat(passport.plays);
      this.setData({ 'passport.plays': applyEventCard(plays, card) });
    });
  },
  onPassportPlayTap(e) {
    const key = e.currentTarget.dataset.key;
    if (key === 'event' && this._activeRoamEventId) { if (this._eventNavigating) return; this._eventNavigating = true; wx.navigateTo({ url: '/pages/activity/official-detail/index?id=' + this._activeRoamEventId, complete: () => { this._eventNavigating = false; } }); return; } // 跳转中不响应连点,免得叠两层活动详情
    if (key === 'event') { this.openRoamTasks(); return; }   // 没有单场可直达时进官方活动任务列表
    // 发现附近店铺:漫游开始前 _pois 为空、横滑条只在 map 态渲染,原 goDiscover 在这里只会弹「没有合作商家」→ 改去地图
    this.openNearbyMap();
  },
  onIntroCardSwipe(e) { this.setData({ introCardIdx: e.detail.current || 0 }); },
  /** B:出示当前选中主题的码(A 扫码进入已按用户裁决移除)。
   *  码图需后端小程序码接口(getWXACodeUnlimit),接上前弹窗如实显示待生成,不摆假码。 */
  // 主题码已移到自由探索(play intro):漫游不需要码(用户裁决)

  // 漫游记录里的那年今日 / 月度回顾 / 导出(2026-10-14 随漫游上线):弹窗组件只发事件,跳转在宿主页
  openRoamTool(e) {
    const key = e && e.detail && e.detail.key; if (this._navigating) return; // 三个工具入口共用跳转锁,连点只开一层
    if (key === 'anniversary') { this._navigating = true; wx.navigateTo({ url: '/subpackageTools/anniversary/index', complete: () => { this._navigating = false; } }); }
    else if (key === 'recap') { this._navigating = true; wx.navigateTo({ url: '/subpackageTools/monthly-recap/index', complete: () => { this._navigating = false; } }); }
    else if (key === 'export') { this._navigating = true; wx.navigateTo({ url: '/subpackageTools/exploration-export/index', complete: () => { this._navigating = false; } }); }
  },
  openNearbyMap() { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageRoam/nearby/index', complete: () => { this._navigating = false; } }); },
  openMyTeams() { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageRoam/my-teams/index', complete: () => { this._navigating = false; } }); },
  /* 设置页的权限状态:位置权限(scope.userLocation)与后台持续定位(scope.userLocationBackground)
     都从系统里读,不猜、不写死。拿不到就保持原值(宁可显示旧值,也不翻成「关」骗人)。 */
  _syncRoamPermission() {
    if (typeof wx === 'undefined' || !wx.getSetting) return;
    wx.getSetting({
      success: (r) => {
        const auth = (r && r.authSetting) || {};
        this.setData({
          gpsOk: auth['scope.userLocation'] === true,
          bgGpsOk: auth['scope.userLocationBackground'] === true,
        });
      },
    });
  },
  openRoamLocationSetting() {
    this._syncRoamPermission();
    wx.openSetting && wx.openSetting({ success: () => this._syncRoamPermission() });
  },
  toggleReducedMotion() {
    const reducedMotion = writeReducedMotion(null, !this.data.reducedMotion); if (reducedMotion === null) { cyToast('设置未保存，请重试'); return; }
    if (reducedMotion && this._zoomAnim) {
      clearInterval(this._zoomAnim);
      this._zoomAnim = null;
      this._zoomLock = false;
      if (this._zoomTarget) {
        this.setData({
          mapScale: this._zoomTarget.scale,
          center: this._zoomTarget.center,
        });
        this._zoomTarget = null;
      }
    }
    this.setData({ reducedMotion });
    cyToast(reducedMotion ? '已减少动态效果' : '已恢复动态效果');
  },
  // 2026-10-27 owner P-32:设置里关掉迷雾 —— 只停画雾层,足迹/点亮照常记;地点搜索本来就不受迷雾影响
  toggleFogOff() {
    const fogOff = writeFogOff(null, !this.data.fogOff); if (fogOff === null) { cyToast('设置未保存，请重试'); return; }
    this.setData({ fogOff });
    cyToast(fogOff ? '已关闭迷雾' : '已开启迷雾');
  },
  _triggerCelebration(moment) {
    if (!moment) return;
    this._celebrationSeq = (this._celebrationSeq || 0) + 1;
    this.setData({ celebrationEvent: moment + ':' + this._celebrationSeq });
  },

  /* 读数真值。渲染的是 data.statsShown(滚动动画的落点),这一份只给逻辑读。
     写在这儿是为了「任何时候都存在」——onLoad 之外被调到也不会读 undefined;
     onLoad 会换成一份新的,避免页面重建时接着上一轮的数往上加。 */
  _stats: { time: '00:00', shops: 0, explorePct: 0, distance: '0.0' },

  onLoad(q) {
    roamMemory(this); // 固定本页玩家归属；切号后的旧异步回包不得写进新玩家桶
    this._bindRoamNetwork(); // BL-03 断网提示
    this._stats = { time: '00:00', shops: 0, explorePct: 0, distance: '0.0' };
    this._elapsedSeconds = 0;
    this._eventId = +((q && q.eventId) || 0) || 0; // 官方活动绑定(可选);无则普通漫游,行为不变
    this._eventMissionCode = (q && q.missionCode) || '';
    let topSafe = 54;
    let promptTop = 100;
    let searchTop = 96;
    let chrome = { actionTop: 28, actionRight: 12, contentTop: 76 };
    try {
      const w = (wx.getWindowInfo && wx.getWindowInfo()) || wx.getSystemInfoSync();
      const getMenuButtonInfo = wx.getMenuButtonBoundingClientRect || wx.getMenuButtonBoundingRect;
      const menuButtonInfo = getMenuButtonInfo && getMenuButtonInfo.call(wx);
      chrome = resolveMenuChrome(w, menuButtonInfo);
      topSafe = chrome.actionTop;
      // 这屏有输入框:提示栈整体让到白框下面,不和它抢同一条位置带(§6.6)
      promptTop = chrome.contentTop + 12 + SEARCH_ROW_H;
      searchTop = chrome.contentTop;
      this._win = { w: w.windowWidth, h: w.windowHeight, dpr: w.pixelRatio || 2 };
    } catch (e) { this._win = { w: 375, h: 750, dpr: 2 }; }
    this._introSeenKey = this.buildRoamIntroSeenKey();
    // 2026-08-10 走查 B04：进页不再自动弹玩法说明。玩法说明仍随时可看，
    // wxml 里有两个手动入口都走 openRoamRules(起始页 go-side / 地图卡头 pcard__head)。
    this.setData({
      topSafe,
      promptTop,
      searchTop,
      chrome,
      isDevtools: this._isDevtools(),
      reducedMotion: readReducedMotion(),
      fogOff: readFogOff(),
    });
    // BL-23:入口地图首屏不再固定落在默认城市坐标 —— 有上次成功定位就从那里开始
    const lastPoint = lastLocation.load();
    if (lastPoint) this.setData({ center: { lat: lastPoint.latitude, lng: lastPoint.longitude } });
    this._entryItems = []; this._entryTeams = []; this._loadEntryMap(this.data.center);
    this._loadIntroPassport();
    this._reveals = [];
    this._track = [];
    this._sessionPhotos = [];
    this._gridHit = {};
    this._nearbyShown = false;
    // P2 接真:本次会话累加器(格子/发现POI/会话id/上报队列)
    this._sessionTiles = {};
    this._sessionTileCount = 0;   // 与 _sessionTiles 同生命周期:重置一个必须重置另一个
    this._roamWriteGeneration = (this._roamWriteGeneration || 0) + 1;
    this._revealPromise = null;
    this._finishPromise = null;
    this._arrivalWait = null;
    this._roamPoiPromise = null;
    this._roamPoiFinishBlocked = false;
    this._revealBootstrapUnknown = false;
    this._pendingTiles = [];
    this._foundRoamPois = {};
    this._roamSid = 0;
    this.setData({ roamSessionReady: false });
    this._historyReveals = [];
    this._revealsSaved = false;
    this._footprintHintPrepared = false;
    this._footprintHintDismissed = false;
    this._footprintHintPoint = null;
    // 漫游提示层：feature 关时 start() 内部直接返回，不拉 profile、不展示提示
    this._npc = createRoamNpcCoordinator(buildNpcPorts(this));
    this._npc.start();
    this._player = { ...FALLBACK };
    try { this._loadLocalHistory(); } catch (e) { console.warn('load history fail'); }
    analytics.track('roam_start', { bizType: 'roam', bizId: this._eventId || 0 });
    this._fetchShopBadgeCfg();   // #23 勋章阈值/文案的真源在后端 def,拉不到用兜底常量
    this._restoreRoamRecovery();
    if (this._eventId) this._loadBoundEvent(this._eventId); // 拉活动名做顶部"正在为…点亮"横幅
    // ?scene=xxx:来自组局页「设置」的定向跳转(它是 tabBar 页,navigateTo 进不来,只能 reLaunch 带参)。
    // 只认白名单内的 scene,别的值一律忽略,不给外部输入开弹层的口子。
    if (q && q.scene === 'roam-settings') this.openScene('roam-settings');
  },

  onUnload() {
    this._unloaded = true;
    this._roamStartGen = (this._roamStartGen || 0) + 1;
    this._roamStartPending = false;
    this._shootAfterStart = false;
    if (this._sessionClock) this._saveRoamRecovery();
    this._unbindRoamNetwork();
    this._cancelShareWork();
    this._stopReal(); this._stopClock();
    this._clearRollTimers();   // 读数滚动的计时器,离页必须掐掉
    this._disposeFogRenderer();
    if (this._npc) this._npc.dispose();   // 停掉气泡定时器 + 之后一切 no-op
    this._flushReveal(); // P2:离开前尽量把未上报格子推上去
    this._persistTiles(); // 已探格子落地(中途退出雾也保持)
    if (!this._revealsSaved && this._reveals && this._reveals.length) {
      try {
        const memory = roamMemory(this);
        memory.writeReveals(memory.readReveals().concat(this._reveals).slice(-2000));
      } catch (e) { /* noop */ }
    }
    if (this._revealTimer) clearTimeout(this._revealTimer);
    if (this._mapWatchTimer) clearTimeout(this._mapWatchTimer); if (this._entryRegionTimer) clearTimeout(this._entryRegionTimer);
    if (this._fogTimer) clearTimeout(this._fogTimer);
    if (this._fogFlushTimer) clearTimeout(this._fogFlushTimer);
    if (this._zoomAnim) clearInterval(this._zoomAnim);
    if (this._footprintHintTimer) clearTimeout(this._footprintHintTimer);
    if (this._sparkInTimer) clearTimeout(this._sparkInTimer);
    if (this._roamOpeningTimer) clearTimeout(this._roamOpeningTimer);
    if (this._roamZoomTimer) clearTimeout(this._roamZoomTimer);
    if (this._shootAfterStartTimer) clearTimeout(this._shootAfterStartTimer);
    if (this._cancelEndHold) this._cancelEndHold();
    if (this._shareUploadOperation) this._shareUploadOperation.abort();
    this._roamWriteGeneration = (this._roamWriteGeneration || 0) + 1;
    if (this._roamCacheUnsubscribe) this._roamCacheUnsubscribe();
    this._roamCacheUnsubscribe = null;
  },
  onHide() {
    this._hidden = true;
    this._roamStartGen = (this._roamStartGen || 0) + 1;
    this._roamStartPending = false;
    this._shootAfterStart = false;
    if (this._sessionClock) this._saveRoamRecovery();
    // P1-6:tab 页常驻不卸载,_clock 的 1s 心跳在兜里照跑(会话语义见下 —— GPS 采集期不能停表)。
    // 停表会改玩法语义,所以只短接 UI 推送:_syncStats 见 _hidden 即返回,onShow 补一次同步。
    // P2-5:切到 discover/据点等页走 onHide 而非 onUnload,若此时被系统回收会丢最近增量;
    // 补一次 flush(与 onUnload 一致,幂等无副作用)
    this._flushReveal();
    this._persistTiles();
    // X10:实时定位开着的时候不自动暂停时钟。
    //
    // onHide 分不清「切到别的 tab」和「锁屏塞兜」—— 而锁屏塞兜边走边点亮
    // 正是 bgTracker 后台定位存在的理由(见 _startReal 处注释)。原来这里
    // 无条件 pause,于是玩家一锁屏:GPS 继续记轨迹、游戏时钟却停了,
    // 而 onShow 并不会自动恢复(恢复只有 togglePause 一条路)——
    // 结果是这段真实走过的时间被静默吞掉,玩家回来才发现 HUD 显示暂停中。
    //
    // 判据用 _realOn:它为真就说明这次会话确实在实时采集中,两者必须同进退。
    // GPS 没开时(手动漫游/演示)照旧暂停,那时「离开页面=离开这次活动」成立。
    if (!this._realOn && this._sessionClock && !this._sessionClock.isPaused()) {
      this._sessionClock.pause();
      this.setData({ paused: true, endHoldPct: 0 }, () => this._syncPlayHud());
    }
  },
  onShow() {
    this._syncRoamPermission();
    // P1-6:兜里短接的读数立即补一次,不等下一拍 1s
    this._hidden = false;
    if (this._clock) this._syncStats();
    this.setData({ fontScaleStyle: readPageStyle() });
    this._syncPresencePrivacy(); this._eventNavigating = false; this._navigating = false; // 返回时兜底清「官方活动」与其余入口的跳转锁
    // 系统定位权限与位置分享偏好分别回读。
    const app = getApp();
    this._syncCommunityEntry();
    const playerId = typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '';
    if (this._roamMemory && this._roamMemory.playerId !== playerId) {
      this._stopReal(); this._stopClock();
      this._resetRoamRecoverySession();
      this._roamMemory = null;
      roamMemory(this);
      this._loadLocalHistory();
      this._restoreRoamRecovery();
    }
    // tabBar 页实例常驻(切 tab 不 onUnload,小程序热启动也保留实例):goStart 的在途标记若没被清,
    // 会让之后每次点 GO/相机都静默 return,且冷启动前无法自愈。回到本页时作废在途流程并解锁:
    // 世代号 +1 让旧 chain 的回调全部弃权,门闩与「开完就拍」的意图一并归零(后者不清会在下一次
    // 成功出发时莫名其妙弹相机)。
    this._roamStartGen = (this._roamStartGen || 0) + 1;
    this._roamStartPending = false;
    this._shootAfterStart = false;
    if (this._shootAfterStartTimer) {
      clearTimeout(this._shootAfterStartTimer);
      this._shootAfterStartTimer = null;
    }
    if (this._roamZoomTimer) {
      clearTimeout(this._roamZoomTimer);
      this._roamZoomTimer = null;
    }
    const av = getApp().globalData.avatar;
    if (av !== this.data.avatar) this.setData({ avatar: av || '' });
    this._loadIntroPassport(); if (this.data.screen === 'entry-map' && this._entryShownOnce && typeof getApp().sendRequest === 'function') this._loadEntryMap(this.data.center); this._entryShownOnce = true;
    // 漫游不自动补造路径；定位恢复后由用户再次主动开启。
    if (this._roamBadgePending && !this._recoveryPromise) this.recoverRoam();
    // switchTab 不带 query 参数,official-detail 经 globalData 暂存 eventId,此处消费后即清
    const pendingEventId = getApp().globalData.roamEventId;
    const pendingMissionCode = getApp().globalData.roamEventMissionCode;
    if (pendingEventId) {
      const changed = pendingEventId !== this._eventId;
      this._eventId = pendingEventId;
      this._eventMissionCode = pendingMissionCode || '';
      delete getApp().globalData.roamEventId;
      delete getApp().globalData.roamEventMissionCode;
      if (changed || this._eventMissionCode) this._loadBoundEvent(this._eventId);
    }
    // 雾遮罩的自愈:.fogscreen-guard 是 inset:0 的不透明层,guard 卡住 = 整页纯黑且 POI 点不动
    // (onPoiTap 首行就按它拦)。多数失败路径都置了 fogScreenError ⇒ 有「点击重试」入口能自救;
    // 但有几条路径会停在 guard:true / error:false —— 入口是 wx:if="{{fogScreenError}}",此时不显示
    // ⇒ 全黑、无提示、无出口。已知至少三条:①首帧既不回 bindload 也不回 binderror 的机型;
    // ②regionchange 的 begin 到了而 end 丢了(手势被来电/切后台打断);③重绘定时器被 dispose 截断。
    // ②已由 _guardScreenViewportChange 的看门狗就地兜住,这里是最后一道:tabBar 页实例常驻,
    // 切走再回来仍是那个 guard,冷启动前不自愈,所以回到本页时补一次重试(retryScreenFog 自带防重入)。
    if (this.data.fogScreenGuard && (this.data.screen === 'map' || this.data.screen === 'arrive')) {
      this.retryScreenFog();
    }
  },

  // 点亮城市「点到店」:点地图原生 POI(bindpoitap)→ 宽松 geo → 参与据点跳任务卡 / 非参与点亮发探索值。
  // native POI 走此路;custom marker 仍走 onMarkerTap,两事件互不干扰。
  onPoiTap(e) {
    const d = (e && e.detail) || {};
    if (d.latitude == null || d.longitude == null) return;
    if (this.data.fogScreenGuard) return;
    if (this._fogMode === 'screen') {
      const reveals = (this._historyReveals || []).concat(this._reveals || []);
      if (!isPointRevealed({ lat: d.latitude, lng: d.longitude }, reveals, REVEAL_M)) return;
    }
    const c = this.data.center || {};
    req('/api/roam/checkin', 'POST', {
      name: d.name || '',
      poiLat: String(d.latitude),
      poiLng: String(d.longitude),
      curLat: String(c.lat != null ? c.lat : d.latitude),
      curLng: String(c.lng != null ? c.lng : d.longitude),
    }).then((res) => {
      const r = (res && res.data) || {};
      if (r.tooFar) { cyToast('离得有点远，走近点'); return; }
      if (r.participating && r.poiId) {
        this.openScene('roam-poi-detail', { poiId: r.poiId });
        return;
      }
      if (r.participating === false) {
        cyToast(r.firstVisit ? ('+' + r.xp + ' 探索值 · 点亮' + (d.name || '')) : '已点亮过');
        // 首亮提示：只有这条分支才有 firstVisit —— participating===true 那支(上面)
        // 已 navigateTo 跳走,在那里冒泡等于用户看不见却白扣配额+发 impression(埋点谎报)。
        // 去重键用坐标:bindpoitap 的 detail 只有 {name,latitude,longitude},没有任何 id。
        if (r.firstVisit === true && this._npc) {
          this._npc.dispatch({
            type: 'POI_VISIT_RECORDED',
            poiKey: Number(d.latitude).toFixed(5) + ',' + Number(d.longitude).toFixed(5),
            firstVisit: true,
          });
        }
        return;   // 3-06:不 return 的话下面兜底 toast 会立刻盖掉首亮提示
      }
      // 其他(后端 error / 未登录 / 该点无记录):原来完全静默,点地图上的原生 POI
      // 像坏了一样没有任何反馈。给一次人话 toast —— 不落错误态、不改本地进度、不重试。
      cyToast('这个地点暂时没有可记录的信息');
    });
  },

  _mergeSessionDonePois(incoming) {
    // 打完卡的点在本地也算「亮过」:done(已完成)与 pendingRedeem(已打卡待核销)都不能因为
    // 商家/POI 图层异步重拉而退回雾里(CU-M-54 的那半个病灶);incoming 若自带同名点位,
    // 保留本地这一档 status,其余字段以服务端为准。
    const completed = new Map((this._pois || []).filter(p => (p.state === 'done' || p.state === 'pendingRedeem') && p.id != null)
      .map(p => [String(p.id), p]));
    const seen = new Set();
    const merged = [];
    (incoming || []).forEach(p => {
      const key = p.id == null ? null : String(p.id);
      const done = completed.get(key);
      if (done && seen.has(key)) return;
      if (done) seen.add(key);
      merged.push(done ? { ...done, ...p, state: done.state, first: false } : p);
    });
    completed.forEach((p, key) => { if (!seen.has(key)) merged.push(p); });
    return merged;
  },

  _initWorld(c) {
    const current = this._roamReadScope();
    this._c = c;
    const P = (x, y) => ({ lat: c.lat + m2lat(y), lng: c.lng + m2lng(x, c.lat) });
    const raw = selectDemoRoamPois(getApp(), [
      { id: 108, cat: 'merchant', name: '转角奶茶', ...P(35, -40), points: 20, desc: '放学路上总要绕的那家。', imgUrl: MOCK_IMG },
      { id: 109, cat: 'merchant', name: '巷子面馆', ...P(-50, 25), points: 25, desc: '老板凌晨四点就开始熬汤。', imgUrl: MOCK_IMG },
      { id: 110, cat: 'merchant', name: '拐角披萨', ...P(20, 55), points: 30, desc: '一整块芝士拉到看不见边。', imgUrl: MOCK_IMG },
      { id: 101, cat: 'merchant', name: '深夜食堂', ...P(-40, -120), points: 30, desc: '凌晨两点还亮着灯的那家。', imgUrl: MOCK_IMG },
      { id: 111, cat: 'merchant', name: '老友烧烤', ...P(-70, -95), points: 20, desc: '啤酒论箱起卖，老板不劝酒也不劝退。', imgUrl: MOCK_IMG },
      { id: 102, cat: 'park', name: '苏堤小游园', ...P(-190, 20), points: 20, desc: '傍晚有人在这里遛狗、下棋、发呆。' },
      { id: 103, cat: 'landmark', name: '城西美术馆', ...P(-320, 230), points: 25, desc: '免费开放的那层，总有学生在临摹。' },
      { id: 104, cat: 'park', name: '梧桐口袋公园', ...P(-130, 390), points: 20, desc: '一小片被高楼夹住的绿。' },
      { id: 105, cat: 'merchant', name: '山丘咖啡', ...P(130, 480), points: 30, desc: '巷口第三家。老板记得每个熟客的豆子。', imgUrl: MOCK_IMG },
      { id: 112, cat: 'merchant', name: '巷角包子', ...P(160, 460), points: 20, desc: '皮薄馅大，早上六点就排队。', imgUrl: MOCK_IMG },
      { id: 106, cat: 'landmark', name: '老钟楼', ...P(290, 310), points: 25, desc: '整点还会响。' },
      { id: 107, cat: 'merchant', name: '无早旧书店', ...P(150, 120), points: 30, desc: '二手书按气味分类的地方。', imgUrl: MOCK_IMG },
      { id: 113, cat: 'merchant', name: '老巷炸串', ...P(120, 145), points: 20, desc: '油锅从下午响到凌晨。', imgUrl: MOCK_IMG },
    ]);
    const catMap = {
      park: { icon: 'poi-park', catLabel: '公园 · 绿地', color: '#A6A6A6' },
      merchant: { icon: 'poi-shop', catLabel: '商家 · 探店', color: '#D9D9D9' },
      landmark: { icon: 'poi-landmark', catLabel: '地标', color: BRAND },
    };
    this._pois = this._mergeSessionDonePois(raw.map((p) => ({ ...p, demo: true,
      ...catMap[p.cat],
      imgUrl: p.imgUrl || MOCK_IMG,
      state: 'fog',
      first: true,
      metaLine: catMap[p.cat].catLabel + ' · 距你很近',
    })));
    this._player = { ...c };
    this.setData({ center: c });
    this._genIcons().then(() => { if (current()) this._syncMarkers(); });
    this._fetchNearbyMerchants(c);
    this._fetchRoamTiles();
    this._applyLocalPoiFound();
    // 等附近商家加载完再拉 roam POI,避免并发修改 this._pois 丢数据
    var self = this;
    this._nearbyReady = new Promise(function (resolve) {
      self._nearbyResolve = resolve;
    });
    this._fetchRoamPois(c);
  },

  _resolveNearbyReady() {
    if (this._nearbyResolve) { this._nearbyResolve(); this._nearbyResolve = null; }
  },

  _isDevtools() {
    try {
      const s = (wx.getDeviceInfo && wx.getDeviceInfo()) || wx.getSystemInfoSync();
      return s.platform === 'devtools';
    } catch (e) { return false; }
  },

  /** 本地历史:geohash7 格子 + 最近轨迹点(精细擦除) */
  _loadLocalHistory() {
    const pts = [];
    const seen = {};
    this._footprintSessions = [];
    const push = (p) => {
      const k = p.lat.toFixed(5) + ',' + p.lng.toFixed(5);
      if (seen[k]) return;
      seen[k] = 1;
      pts.push(p);
    };
    try {
      const memory = roamMemory(this);
      const tileState = memory.readTiles();
      const tiles = tileState.tiles;
      this._tileServerCursor = tileState.serverCursor;
      this._footprintSessions = memory.readSessions();
      this._localTiles = tiles; // 当前玩家本设备已探格子缓存,后续增量写回(重开保持无雾)
      Object.keys(tiles).forEach((h) => { const p = geohashDecode7(h); if (p) push({ lat: p.lat, lng: p.lng, r: TILE_REVEAL_M }); });
      memory.readReveals().slice(-800).forEach((p) => push(p));
    } catch (e) { /* noop */ }
    this._historyReveals = pts;
  },

  _isRoamNpcEventOn() {
    return ((getApp().globalData.features || {}).roamNpcEvent === true);
  },

  _scheduleFootprintHint() {
    this._tryShowFootprintHint();
    if (this._footprintHintTimer) clearTimeout(this._footprintHintTimer);
    // app.loadFeatureFlags 异步到货；短延迟重试一次，仍拿不到开关就保持静默。
    this._footprintHintTimer = setTimeout(() => {
      this._footprintHintTimer = null;
      this._tryShowFootprintHint();
    }, 1200);
  },

  _tryShowFootprintHint() {
    if (!this.data.mapReady || this._footprintHintPrepared || this._footprintHintDismissed || !this._isRoamNpcEventOn()) return;
    const hint = buildFootprintHint(this._footprintSessions || [], this._player, this._localTiles || {});
    this._footprintHintPrepared = true;
    if (!hint) return;
    this._footprintHintPoint = hint.point;
    this.setData({ footprintHint: { show: true, text: hint.text } }, () => {
      this._syncSparkCircles();
      analytics.track('roam_footprint_hint_shown', { bizType: 'roam' });
    });
  },

  closeFootprintHint() {
    this._footprintHintDismissed = true;
    this._footprintHintPoint = null;
    this.setData({ footprintHint: { show: false } }, () => this._syncSparkCircles());
    analytics.track('roam_footprint_hint_dismissed', { bizType: 'roam' });
  },

  _clearFootprintHintForPriority() {
    if (!this.data.footprintHint.show) return;
    this._footprintHintDismissed = true;
    this._footprintHintPoint = null;
    this.setData({ footprintHint: { show: false } }, () => this._syncSparkCircles());
  },

  _mergeHistoryTiles(list) {
    if (!Array.isArray(list) || !list.length) return;
    const seen = {};
    (this._historyReveals || []).forEach((p) => { seen[p.lat.toFixed(5) + ',' + p.lng.toFixed(5)] = 1; });
    list.forEach((h) => {
      const p = geohashDecode7(String(h).trim());
      if (!p) return;
      const k = p.lat.toFixed(5) + ',' + p.lng.toFixed(5);
      if (seen[k]) return;
      seen[k] = 1;
      this._historyReveals.push({ lat: p.lat, lng: p.lng, r: TILE_REVEAL_M });
    });
    if (this.data.screen === 'map' && this._fogMode) this._rebuildFogDisplay();
  },

  /** 历史 + 本会话轨迹重绘雾层(异步拉回 tiles 后调用) */
  _rebuildFogDisplay() {
    if (this._fogMode === 'screen') {
      this._scheduleScreenFogRender();
    } else if (this._fogMode === 'overlay') {
      this._paintFogBase();
      this._applyHistoryFog();
      (this._reveals || []).forEach((p) => this._stampReveal(p, 1));
      this._flushFogOverlay();
    } else if (this._fog) {
      this._initFogGrid();
      this._applyHistoryFog();
      (this._reveals || []).forEach((p) => this._fogApplyReveal(p));
      this._updateFogNow();
    }
  },

  _fetchRoamTiles() {
    const current = this._roamReadScope();
    const memory = roamMemory(this);
    return syncRoamTileMemory({
      memory, isCurrent: current,
      pageSize: 1000,
      fetchPage: (afterId, limit) => req('/api/roam/tiles/page', 'GET', {
        afterId: String(afterId), limit: String(limit),
      }).then((res) => {
        if (!current()) throw new Error('漫游读取上下文已变化');
        if (!apiOk(res) || !res.data) throw new Error('迷雾记忆同步失败');
        return res.data;
      }),
      readPendingTiles: () => {
        if (!current()) throw new Error('漫游读取上下文已变化');
        return this._localTiles || {};
      },
      onPage: (list, state) => {
        this._localTiles = state.tiles;
        this._tileServerCursor = state.serverCursor;
        this._mergeHistoryTiles(list);
      },
    }).catch(() => null); // 离线继续使用当前玩家本地记忆；下一次打开从已落盘游标续传
  },

  /** 本地会话里探过的 POI → passed 常亮 */
  _applyLocalPoiFound() {
    const doneIds = {};
    try {
      roamMemory(this).readSessions().forEach((s) => {
        (s.pois || []).forEach((p) => { if (p.id != null) doneIds[p.id] = 1; });
      });
    } catch (e) { /* noop */ }
    if (!Object.keys(doneIds).length) return;
    this._pois = (this._pois || []).map((p) => {
      if (doneIds[p.id] && p.state === 'fog') return { ...p, state: 'passed', first: false };
      return p;
    });
  },

  _roamReadScope() {
    const generation = this._roamWriteGeneration;
    const owner = () => { const app = getApp(); return typeof app.getUserID === 'function' ? String(app.getUserID() || '') : ''; };
    const playerId = owner();
    return () => generation === this._roamWriteGeneration && playerId === owner();
  },

  _fetchNearbyMerchants(c) {
    const current = this._roamReadScope();
    return req('/api/map/nearby', 'POST', {
      longitude: String(c.lng),
      latitude: String(c.lat),
      radius: '800',
      limit: '20',
    }).then((res) => {
      if (!current()) return;
      this._resolveNearbyReady();  // 无论成功失败都 resolve,避免 _fetchRoamPois 死等
      // 商家图层挂了原来直接 return:地图上只是少了店,一句提示都没有。
      // 并入既有离线横幅(与 _fetchRoamPois 的 poiOffline 同一层展示),失败可见、可重试。
      // 注意只认「请求失败」:200 + 空列表是这一带真没有店,不是错误。
      if (res.code != '200' || !isRecordList(res.data)) {
        this.setData({ merchantOffline: true });
        return;
      }
      this.setData({ merchantOffline: false });
      if (!res.data.length) return;
      const imgBase = config.baseImgUrl || '';
      const merchants = res.data.map((n, i) => {
        if (n.topicId && Number(n.productType) !== 2) return null;
        const lat = parseFloat(n.latitude);
        const lng = parseFloat(n.longitude);
        if (isNaN(lat) || isNaN(lng)) return null;
        let imgUrl = MOCK_IMG;
        if (n.picUrl) imgUrl = n.picUrl.startsWith('http') ? n.picUrl : imgBase + n.picUrl;
        const merchantAvatar = n.merchantAvatar || n.avatarUrl || n.avatar || '';
        const npcAvatar = n.npcAvatar || '';
        return {
          id: n.nodeId || n.id || (9000 + i),
          nodeId: n.nodeId,
          topicId: n.topicId,
          regId: n.id,
          cat: 'merchant',
          name: n.addressName || '商家',
          // address_name 可空(建表 DEFAULT ''、DTO 无 @NotBlank),兜底的 '商家' 是伪造名:
          // 一批无名商家会全叫「商家」→ 按名归组会把它们误并成一家。标出来,归组时跳过。
          nameKnown: !!(n.addressName && String(n.addressName).trim()),
          lat, lng,
          desc: n.address || '附近合作商家',
          imgUrl,
          points: 30,
          icon: '☕',
          catLabel: '商家 · 探店',
          color: '#D9D9D9',
          state: 'fog',
          first: true,
          metaLine: '商家 · 探店 · ' + (n.distance ? Math.round(n.distance) + 'm' : '距你很近'),
          distM: n.distance ? Math.round(n.distance) : null,
          // 商家上传头像优先；没有时才使用门店 NPC 形象。
          mapAvatar: merchantAvatar || npcAvatar,
          mapAvatarKind: merchantAvatar ? 'merchant' : (npcAvatar ? 'npc' : ''),
          npcAvatar,
          // 主题玩法(1 城市定向 / 2 自由探索),决定点位描边色与搜索结果针的颜色
          productType: n.productType,
          // 地址的全拼与首字母,由后端算好带回来(前端不带拼音字典 —— 主包资源棘轮只准降)
          namePinyin: n.namePinyin || '',
          nameInitials: n.nameInitials || '',
        };
      }).filter(Boolean);
      if (!merchants.length) return;
      const others = this._pois.filter((p) => p.cat !== 'merchant');
      this._pois = this._mergeSessionDonePois(merchants.concat(others));
      this._seenSweepHistory(); // 异步并入的报名商家若在历史点亮区,直接带星
      this._syncMarkers();
      this._syncSparkCircles();
      this._resolveNearbyReady();
    });
  },

  // P2 接真①:后端 roam POI(GET /api/roam/pois);正式版只显示后端真数据。
  //
  // ⚠️ 原码把「网络没通」和「后端这一带确实没铺 POI」都走同一条 return,两者都静默保留
  // _initWorld 的 13 家演示商家(转角奶茶/深夜食堂…)—— 用户分不出真假,会走过去打卡。
  // 演示种子仅在开发版且显式打开 roamDemoPois 时注入；网络失败仍需显示离线状态。
  _fetchRoamPois(c) {
    const scope = this._roamReadScope(), seq = this._roamPoiSeq = (this._roamPoiSeq || 0) + 1, current = () => scope() && seq === this._roamPoiSeq; // 连续重拉只认最后一次,旧回包晚到不覆盖新结果
    // 等待附近商家加载完成再拉 POI,避免并发修改 this._pois 丢数据
    const nearbyDone = this._nearbyReady || Promise.resolve();
    return nearbyDone.then(() => {
    if (!current()) return;
    const radiusM = this._poiFilterState().radiusM || ROAM_POI_RADIUS_M; // BL-01 筛选范围,默认仍是 3km
    return req('/api/roam/pois', 'GET', { lat: String(c.lat), lng: String(c.lng), radius: String(radiusM) }).then((res) => {
      if (!current()) return;
      if (netFail(res)) {
        // req 的 fail 哨兵 = 网络故障。此时"地图上这些点"既没被证实也没被证伪。
        this.setData({ poiEmpty: false }, () => this._syncGoal());
        return;
      }
      if (res.code != '200' && res.code != 200) {
        this.setData({ poiEmpty: false }, () => this._syncGoal());
        return;
      }
      if (!isRecordList(res.data)) {
        this.setData({ poiEmpty: false }, () => this._syncGoal());
        return;
      }
      const list = res.data;
      this.setData({ poiEmpty: !list.length }, () => this._syncGoal());
      // BL-01:换范围会重拉,上一轮的漫游点位先摘掉(打完卡的由 _mergeSessionDonePois 保留),否则同 id 叠两份。
      const rest = (this._pois || []).filter((p) => !p._roamId);
      if (!list.length) {
        if (rest.length !== (this._pois || []).length) {
          this._pois = this._mergeSessionDonePois(rest);
          this._syncMarkers(); this._syncSparkCircles();
        }
        return;
      }
      const catMap = {
        1: { icon: 'poi-landmark', catLabel: '地标', color: BRAND, cat: 'landmark' },
        2: { icon: 'poi-shop', catLabel: '商家 · 探店', color: '#D9D9D9', cat: 'merchant' },
      };
      const roamPois = list.map((n) => {
        const m = catMap[n.type] || catMap[1];
        const st = roamPoiLocalState(n);
        const merchantAvatar = n.merchantAvatar || n.avatarUrl || n.avatar || '';
        const npcAvatar = n.npcAvatar || '';
        return {
          id: ROAM_POI_MK_BASE + Number(n.id), _roamId: n.id, cat: m.cat, isCityNode: n.type === 2 || !!n.templateId, name: n.name,
          lat: parseFloat(n.lat), lng: parseFloat(n.lng),
          // 非商户地点的 description 不是审核意义来源，绝不直出；商户说明仍走既有确定性探店信息。
          desc: n.type === 2 ? (n.description || '') : '', points: n.xp || 20,
          icon: m.icon, catLabel: m.catLabel, color: m.color,
          imgUrl: MOCK_IMG, state: st, first: !n.found,
          metaLine: m.catLabel + ' · 距你很近', radiusM: n.radiusM || 80,
          nodeLevel: Number(n.nodeLevel || 1),
          // 商家上传头像优先；没有时才使用门店 NPC 形象。
          mapAvatar: merchantAvatar || npcAvatar,
          mapAvatarKind: merchantAvatar ? 'merchant' : (npcAvatar ? 'npc' : ''),
          npcAvatar,
        };
      });
      // 真 POI 优先,拼在演示 POI 前(演示作兜底底色)
      this._pois = this._mergeSessionDonePois(roamPois.concat(rest));
      this._seenSweepHistory();
      this._genIcons().then(() => { if (current()) { this._syncMarkers(); this._syncSparkCircles(); } });
      this._resolveNearbyReady();
    });
    });
  },

  // P2 接真②:~150m 迷雾格 geohash7 累积 + 节流上报(≥50 格即刻 / 否则 10s)
  _accumulateTile(lat, lng) {
    const key = geohash7(lat, lng);
    if (this._sessionTiles[key]) return;
    this._sessionTiles[key] = 1;
    // 唯一格子数计数器:_sessionTiles 只增不减,这里 ++ 与 Object.keys(...).length 恒等,
    // 但避免在 GPS 回调里对一个持续增长的对象做 O(n) 枚举(整场会话就是 O(n²))。
    this._sessionTileCount = (this._sessionTileCount || 0) + 1;
    // 本地持久化:新格子增量记入 roam_tiles(与后端上报独立,离线也不丢)
    if (!this._localTiles) this._localTiles = {};
    if (!this._localTiles[key]) {
      this._localTiles[key] = 1;
      this._tilesRevision = (this._tilesRevision || 0) + 1;
      this._tilesDirty = (this._tilesDirty || 0) + 1;
      this._scheduleTilePersist();
    }
    // 里程碑口径 = 当前会话唯一格子数(§9.2 明令禁用 _reveals.length)。
    // 排在持久化之后：提示层是增强能力，不能因为它出岔子而连累格子落盘。
    if (this._npc) {
      this._npc.dispatch({ type: 'UNIQUE_TILE_COUNT_CHANGED', count: this._sessionTileCount });
    }
    this._roamRecoveryAbandoned = false;
    this._pendingCollectedAt = this._pendingCollectedAt || {};
    this._pendingCollectedAt[key] = Date.now();
    this._pendingTiles.push(key);
    this._ensureRoamRecovery();
    if (this._pendingTiles.length >= 50) { this._flushReveal(); return; }
    if (this._revealTimer) return;
    this._revealTimer = setTimeout(() => { this._revealTimer = null; this._flushReveal(); }, 10000);
  },
  /** 已探格子落 storage:20 格即刻 / 否则 15s 节流;失败不阻塞游玩 */
  _scheduleTilePersist() {
    if (this._tilesDirty >= 20) { this._persistTiles(); return; }
    if (this._tilePersistTimer) return;
    this._tilePersistTimer = setTimeout(() => { this._tilePersistTimer = null; this._persistTiles(); }, 15000);
  },
  _persistTiles() {
    if (this._tilePersistTimer) { clearTimeout(this._tilePersistTimer); this._tilePersistTimer = null; }
    if (!this._tilesDirty) return;
    const revision = this._tilesRevision || 0;
    const generation = this._roamWriteGeneration;
    try {
      const ok = roamMemory(this).writeTiles({ ...(this._localTiles || {}) }, this._tileServerCursor || 0);
      if (ok && revision === (this._tilesRevision || 0) && generation === this._roamWriteGeneration) this._tilesDirty = 0;
      return ok;
    } catch (e) { return false; }
  },

  _discardRoamLocal() {
    this._roamWriteGeneration = (this._roamWriteGeneration || 0) + 1;
    this._recoveryKeyPromise = this._recoveryPromise = this._revealPromise = this._finishPromise = null;
    if (this._revealTimer) clearTimeout(this._revealTimer);
    if (this._tilePersistTimer) clearTimeout(this._tilePersistTimer);
    this._revealTimer = this._tilePersistTimer = null;
    this._pendingTiles = []; this._pendingCollectedAt = {}; this._roamBadgePending = false;
    if (!exactSessionId(this._roamSid)) this._clientSessionKey = '';
    this._pois = (this._pois || []).filter(p => p.state !== 'done');
    this._localTiles = {}; this._tileServerCursor = 0; this._tilesDirty = 0;
    this._tilesRevision = (this._tilesRevision || 0) + 1;
    this._sessionTiles = {}; this._sessionTileCount = 0;
    this._track = []; this._reveals = []; this._historyReveals = []; this._footprintSessions = [];
    this._sessionPhotos = []; this._foundRoamPois = {}; this._recoveryArchiveTs = 0;
    this._roamRecoveryAbandoned = true; this._shopMedalFromServer = false;
    this._recoveryNeedsLookup = this._revealBootstrapUnknown = this._recoveryReadFailed = false;
    this._finishRequested = false; this._revealInFlight = false;
    this.setData({ recoveryError: '', recoveryCanContinue: false, revealWriteUnknown: false });
  },

  _resetRoamRecoverySession() {
    this._entrySeq = (this._entrySeq || 0) + 1;
    this._entryItems = []; this._entryTeams = [];
    this._entryErrors = { activities: false, teams: false };
    this._cancelShareWork();
    this._shareSource = null; this._shareReturnStack = null;
    const shareOwner = String(getApp().getUserID() || '');
    this._squarePostUnknown = !!(this._squarePostUnknownOwners && this._squarePostUnknownOwners[shareOwner]);
    this._roamWriteGeneration = (this._roamWriteGeneration || 0) + 1;
    this._recoveryKeyPromise = this._recoveryPromise = this._revealPromise = this._finishPromise = null;
    this._arrivalWait = this._roamPoiPromise = null;
    if (this._nearbyResolve) this._nearbyResolve();
    this._nearbyResolve = this._nearbyReady = null;
    this._roamPoiFinishBlocked = this._revealInFlight = false;
    if (this._revealTimer) { clearTimeout(this._revealTimer); this._revealTimer = null; }
    this._clientSessionKey = ''; this._roamSid = 0;
    this._presencePrivacyGeneration = (this._presencePrivacyGeneration || 0) + 1;
    this._runners = [];
    this.setData({ presenceVisible: false, runnerView: null, multiView: null });
    this._pendingTiles = []; this._pendingCollectedAt = {}; this._roamBadgePending = false; this._roamRecoveryAbandoned = this._shopMedalFromServer = false;
    this._sessionTiles = {}; this._sessionTileCount = 0;
    this._track = []; this._sessionPhotos = []; this._foundRoamPois = {}; this._pois = [];
    this._stats = { time: '00:00', shops: 0, explorePct: 0, distance: '0.0' };
    this._elapsedSeconds = 0;
    this._dist = 0; this._lastDistanceM = 0; this._recoveryArchiveTs = 0;
    this._finishRequested = this._roamCompleted = this._recoveryNeedsLookup = false;
    this._recoveryReadFailed = this._revealBootstrapUnknown = false;
    this._eventId = 0; this._eventMissionCode = '';
    this._nearbyShown = false;
    this._pendingDiscover = null; this._shopVisitPromise = null;
    this._exploreDayRequestToken = null; this._exploreDayCandidateId = null;
    /* C-16 账号切换/重新开始后,上一轮的浮层必须一起退场:
       visit 打卡卡 / nearbyBanner 商家浮卡 / paceCard 地标卡 / medal 勋章 / discoverReward 二选一 /
       camOpen 取景卡 / topicView / checkinView / runnerView / multiView / shopStrip 横滑卡。
       原来只清了 screen/finish/scene*,新账号进图还看得见上一轮的卡(甚至在 mask 底下堆着)。
       ⚠️ share 走 scene 栈(sceneStack 已清),不在这里另设 show 位 —— 见 scene-sheet-single-host-contract。 */
    this.setData({ screen: 'entry-map', finish: {}, sceneStack: [], sceneCurrent: null,
      entryMarkers: [], entryCards: [], searchPlaces: [], entryLoadError: false,
      entrySheet: '', entryActivity: null, entryTeam: null, entrySheetView: 'main', entrySheetIn: false,
      camOpen: false, shopStrip: { rows: [], idx: 0 },
      topicView: null, checkinView: null, multiView: null, runnerView: null,
      nearbyBanner: { show: false }, nearbyPois: [], nearbyIdx: 0,
      paceCard: { show: false, title: '', body: '', demo: false },
      medal: { show: false }, discoverReward: { show: false, poi: {} },
      visit: { active: false, checkinOk: false, poi: {}, photos: [] },
      archSegs: [], archPois: [], archEnd: {}, 'share.squarePostUnknown': this._squarePostUnknown,
      recoveryError: '',
      recoveryCanContinue: false, revealWriteUnknown: false, roamSessionReady: false });
  },

  _recoveryOwnerCurrent() {
    const app = getApp();
    return !!roamMemory(this).playerId && String(app.getUserID()) === roamMemory(this).playerId;
  },

  _recoveryFailure(message) {
    this.setData({ recoveryError: message });
    if (this.data.screen === 'arrive') this.setData({ 'finish.settleErr': 'recovery' });
    return false;
  },

  _saveRoamRecovery() {
    if (this._roamRecoveryAbandoned) return true;
    if (!this._recoveryOwnerCurrent()) return this._recoveryFailure('登录账号已变化，请回到漫游页确认');
    const record = {
      clientSessionKey: this._clientSessionKey || '', sessionId: exactSessionId(this._roamSid),
      collectedAt: Object.fromEntries((this._pendingTiles || []).filter(tile => Object.prototype.hasOwnProperty.call(this._pendingCollectedAt || {}, tile)).map(tile => [tile, this._pendingCollectedAt[tile]])),
      pendingTiles: (this._pendingTiles || []).slice(), allTiles: Object.keys(this._sessionTiles || {}),
      finishRequested: !!this._finishRequested, distanceM: this._finishRequested ? this._lastDistanceM : (this._dist || 0),
      archiveTs: this._recoveryArchiveTs || 0, finish: this.data.finish || {}, stats: this._stats, track: this._track || [],
      elapsedSeconds: this._sessionClock ? this._sessionClock.elapsedSeconds() : (this._elapsedSeconds || 0),
      photos: this._sessionPhotos || [], foundPois: this._foundRoamPois || {},
      pois: (this._pois || []).filter(p => p.state === 'done'),
      eventId: this._eventId || 0, missionCode: this._eventMissionCode || '',
    };
    if (!validRecovery(record) || !roamMemory(this).writeRecovery(record))
      return this._recoveryFailure('漫游记录未能保存到本机，请释放存储空间后重试；待确认记录仍保留在本页');
    return true;
  },

  _ensureRoamRecovery() {
    if (this._clientSessionKey || exactSessionId(this._roamSid)) return Promise.resolve(this._saveRoamRecovery());
    if (this._recoveryKeyPromise) return this._recoveryKeyPromise;
    const generation = this._roamWriteGeneration;
    const pending = newSessionKey(wx).then(key => {
      if (generation !== this._roamWriteGeneration || !this._recoveryOwnerCurrent()) return false;
      this._clientSessionKey = key;
      return this._saveRoamRecovery();
    }).catch(error => generation === this._roamWriteGeneration && this._recoveryOwnerCurrent() ? this._recoveryFailure(error.message) : false);
    this._recoveryKeyPromise = pending;
    pending.then(() => { if (this._recoveryKeyPromise === pending) this._recoveryKeyPromise = null; });
    return pending;
  },

  _restoreRoamRecovery() {
    const state = roamMemory(this).readRecoveryState();
    if (!state.ok || (state.record && !validRecovery(state.record))) {
      this._recoveryReadFailed = true;
      return this._recoveryFailure('本机漫游记录暂时无法读取，请重试确认');
    }
    this._recoveryReadFailed = false;
    if (!state.record) return true;
    const r = state.record;
    this._clientSessionKey = r.clientSessionKey || '';
    this._roamSid = exactSessionId(r.sessionId) || 0;
    this._pendingTiles = r.pendingTiles.slice();
    this._pendingCollectedAt = Object.fromEntries(r.pendingTiles.filter(tile => Object.prototype.hasOwnProperty.call(r.collectedAt || {}, tile)).map(tile => [tile, r.collectedAt[tile]]));
    this._sessionTiles = Object.fromEntries((r.allTiles || []).map(t => [t, 1]));
    this._sessionTileCount = Object.keys(this._sessionTiles).length;
    this._finishRequested = r.finishRequested;
    this._recoveryArchiveTs = r.archiveTs || 0;
    this._lastDistanceM = r.distanceM || 0; this._dist = this._lastDistanceM;
    this._track = r.track || []; this._sessionPhotos = r.photos || [];
    this._pois = r.pois || [];
    this._foundRoamPois = r.foundPois || {}; this._stats = r.stats || this._stats;
    this._elapsedSeconds = readNonNegative(r.elapsedSeconds);
    if (this._elapsedSeconds === null) {
      const legacyTime = String(this._stats.time || '').match(/^(\d+):([0-5]\d)$/);
      this._elapsedSeconds = legacyTime ? (readNonNegative(Number(legacyTime[1]) * 60 + Number(legacyTime[2])) || 0) : 0;
    }
    this._eventId = r.eventId || 0; this._eventMissionCode = r.missionCode || '';
    this._recoveryNeedsLookup = true;
    this.setData({ roamSessionReady: false });
    if (r.finishRequested) this.setData({ screen: 'arrive', finish: r.finish || {} });
    this.recoverRoam();
    return true;
  },

  recoverRoam() {
    if (this._recoveryPromise) return this._recoveryPromise;
    if (this._recoveryReadFailed) { this._restoreRoamRecovery(); return Promise.resolve(false); }
    if (!this._clientSessionKey && !exactSessionId(this._roamSid))
      return this._ensureRoamRecovery().then(ok => ok ? this.recoverRoam() : false);
    if (!this._recoveryOwnerCurrent()) return Promise.resolve(false);
    const generation = this._roamWriteGeneration;
    const query = this._clientSessionKey ? { clientSessionKey: this._clientSessionKey } : { sessionId: exactSessionId(this._roamSid) };
    const pending = req('/api/roam/session', 'GET', query).then(async res => {
      if (generation !== this._roamWriteGeneration || !this._recoveryOwnerCurrent()) return false;
      const fact = res && res.data;
      if (!apiOk(res) || !fact || !['ACTIVE', 'FINISHED', 'NOT_FOUND'].includes(fact.state))
        return this._recoveryFailure('漫游状态暂时无法确认，请稍后重试；待确认记录仍保留');
      if (fact.state !== 'NOT_FOUND') {
        const sid = exactSessionId(fact.sessionId);
        if (!sid || (exactSessionId(this._roamSid) && sid !== exactSessionId(this._roamSid)) || (this._clientSessionKey && fact.clientSessionKey !== this._clientSessionKey))
          return this._recoveryFailure('漫游关联响应不完整，请重新确认');
        this._roamSid = sid;
      } else if (!this._clientSessionKey || exactSessionId(this._roamSid)) {
        return this._recoveryFailure('原漫游记录未找到，请稍后重新确认');
      }
      if (fact.state === 'NOT_FOUND' && this._finishRequested && !this._pendingTiles.length) {
        this.setData({ recoveryCanContinue: true });
        return this._recoveryFailure('尚未产生已上报的探索格子，本次会话还未建立；本机记录已保留，可继续探索后再结束');
      }
      this.setData({ recoveryCanContinue: false });
      if (fact.state === 'FINISHED') {
        this._stopReal(); this._stopClock();
        this._finishRequested = true;
        const result = fact.result;
        if (!result || typeof result.totalXp !== 'number' || !Number.isFinite(result.totalXp))
          return this._recoveryFailure('结算事实暂时无法读取，请重新确认');
        if (fact.locationExpired === true) {
          this._track = []; this._pendingTiles = []; this._pendingCollectedAt = {}; this._sessionTiles = {}; this._foundRoamPois = {}; this._pois = [];
          const { stripGeometry } = require('../../utils/roam-location-retention.js');
          this.setData({ finish: stripGeometry(this.data.finish || {}) });
          if (!this._saveRoamRecovery()) return false;
          const memory = roamMemory(this);
          const state = memory.readSessionState();
          if (!state.ok) return this._recoveryFailure('足迹存档暂时无法读取，请重试确认');
          const rows = state.sessions.map(row => row.ts === this._recoveryArchiveTs
            ? { ...stripGeometry(row), finishedAt: fact.finishedAt, locationExpired: true } : row);
          if (!memory.writeSessions(rows)) return this._recoveryFailure('过期足迹清理未保存，请重试确认');
        }
        if (fact.locationExpired !== true && !await this._archiveFinishedRecords()) return false;
        if (generation !== this._roamWriteGeneration || !this._recoveryOwnerCurrent()) return false;
        this._finishedAt = fact.finishedAt || result.finishedAt || 0;
        return this._applyRoamFinishResult(result, fact.resultComplete === true, fact.badgeDelivery);
      }
      this._recoveryNeedsLookup = false; this._revealBootstrapUnknown = false;
      this.setData({ revealWriteUnknown: false, recoveryError: '', roamSessionReady: !!this._roamSid, 'finish.settleErr': '' });
      if (!this._saveRoamRecovery()) return false;
      return this._flushReveal().then(ok => ok && this._finishRequested ? this._postRoamFinish(this._lastDistanceM || 0) : ok);
    }).catch(() => {
      if (generation !== this._roamWriteGeneration || !this._recoveryOwnerCurrent()) return false;
      return this._recoveryFailure('漫游状态暂时无法确认，请稍后重试；原会话记录仍保留');
    });
    this._recoveryPromise = pending;
    const release = () => { if (this._recoveryPromise === pending) this._recoveryPromise = null; };
    pending.then(release, release);
    return pending;
  },

  async _archiveFinishedRecords() {
    const current = this._roamReadScope();
    while (this._pendingTiles.length) {
      const batch = this._pendingTiles.slice(0, 200);
      let records;
      try { records = archiveRecords(batch, this._pendingCollectedAt); }
      catch (error) { return this._recoveryFailure(error.message); }
      const res = await req('/api/roam/session/records', 'POST', { sessionId: exactSessionId(this._roamSid),
        ...(this._clientSessionKey ? { clientSessionKey: this._clientSessionKey } : {}), records: JSON.stringify(records) });
      if (!current() || !this._recoveryOwnerCurrent()) return false;
      if (!apiOk(res) || !res.data || String(res.data.sessionId) !== exactSessionId(this._roamSid)
          || res.data.evidenceType !== 'CLIENT_REPORTED' || res.data.acceptedRecords !== new Set(batch).size)
        return this._recoveryFailure('原行程补传暂未确认，待核记录仍保留');
      const old = this._pendingTiles;
      const oldTimes = this._pendingCollectedAt;
      this._pendingTiles = old.slice(batch.length);
      this._pendingCollectedAt = Object.fromEntries(this._pendingTiles.filter(tile => Object.prototype.hasOwnProperty.call(oldTimes || {}, tile)).map(tile => [tile, oldTimes[tile]]));
      if (!this._saveRoamRecovery()) { this._pendingTiles = old; this._pendingCollectedAt = oldTimes; return false; }
    }
    return true;
  },

  _flushReveal() {
    if (this._revealTimer) { clearTimeout(this._revealTimer); this._revealTimer = null; }
    if (this._revealPromise) return this._revealPromise;
    if (this._recoveryReadFailed) return Promise.resolve(false);
    if (this._recoveryNeedsLookup) return this.recoverRoam();
    if (this._revealBootstrapUnknown) return Promise.resolve(false);
    if (!this._pendingTiles.length) return Promise.resolve(true);
    if (!this._clientSessionKey && !exactSessionId(this._roamSid))
      return this._ensureRoamRecovery().then(ok => ok ? this._flushReveal() : false);
    if (!this._saveRoamRecovery()) return Promise.resolve(false);
    const generation = this._roamWriteGeneration;
    const batch = this._pendingTiles.slice(0, 200);
    const requestSessionId = this._roamSid || 0;
    this._revealInFlight = true;
    const pending = req('/api/roam/reveal', 'POST', { sessionId: String(requestSessionId), tiles: batch.join(','), ...(this._clientSessionKey ? { clientSessionKey: this._clientSessionKey } : {}) }).then((res) => {
      this._markRoamSync(res);
      if (generation !== this._roamWriteGeneration || !this._recoveryOwnerCurrent() || String(this._roamSid || 0) !== String(requestSessionId)) return false;
      this._revealInFlight = false;
      this._revealPromise = null;
      // 首批已确认写入却没有会话身份时，也不能再用 sid=0 重放或结算。
      if (!requestSessionId && apiOk(res) && !(res.data && exactSessionId(res.data.sessionId))) {
        this._revealBootstrapUnknown = true;
        this.setData({ revealWriteUnknown: true });
        return false;
      }
      if ((res.code == '200' || res.code == 200) && res.data) {
        if (res.data.sessionId) {
          this._roamSid = exactSessionId(res.data.sessionId);
          this.setData({ roamSessionReady: true });
          if (this._npc) this._npc.setSessionId(this._roamSid);   // 埋点 bizId 只放 Long
        }
        const beforeAck = this._pendingTiles;
        const beforeTimes = this._pendingCollectedAt;
        this._pendingTiles = beforeAck.slice(batch.length);
        this._pendingCollectedAt = Object.fromEntries(this._pendingTiles.filter(tile => Object.prototype.hasOwnProperty.call(beforeTimes || {}, tile)).map(tile => [tile, beforeTimes[tile]]));
        if (!this._saveRoamRecovery()) { this._pendingTiles = beforeAck; this._pendingCollectedAt = beforeTimes; return false; } // 成功才出队,失败下轮重试
        if (this._pendingTiles.length >= 50 && !this._finishPromise) this._flushReveal();
        return true;
      } else if ((httpFail(res) || netFail(res)) && !requestSessionId) {
        // 首次 reveal 会懒建 session。HTTP 异常时服务端可能已建会话但客户端没拿到 sid；
        // 此时重放 sessionId=0 会再建一条会话，并把首批格子留在旧会话。本页生命周期内必须停止自动重放。
        this._revealBootstrapUnknown = true;
        this.setData({ revealWriteUnknown: true });
      }
      return false;
    });
    this._revealPromise = pending;
    return pending;
  },

  // 官方活动绑定:拉活动名做顶部横幅(best-effort,失败静默)
  // 原码 `}, () => {})` 是空 catch:拉不到活动名就整条横幅静默消失。可用户是**带 eventId 进来的**,
  // 他有权知道"这次点亮到底有没有接上这个活动"。失败保留横幅并标 err,点击重试。
  _loadBoundEvent(eventId) {
    return req('/api/official/events/' + eventId, 'GET', {}).then((res) => {
      if (res && (res.code == 200 || res.code == '200') && res.data) {
        const event = res.data;
        const v2 = Number(event.contractVersion || 1) >= 2;
        this._eventContractVersion = v2 ? 2 : 1;
        const patch = { boundEvent: { id: eventId, title: event.title || '官方活动', err: '', v2 } };
        patch.eventOverlay = v2 ? this._buildEventOverlay(event, eventId) : { show: false, missions: [], points: [], feedback: null };
        this.setData(patch, () => {
          this._syncPlayHeader();
          this._syncSparkCircles();
          this._syncGoal();
        });
        return;
      }
      this._eventContractVersion = 0;
      this.setData({ boundEvent: { id: eventId, title: '', err: 'load' }, eventOverlay: { show: false, missions: [], points: [], feedback: null } }, () => { this._syncPlayHeader(); this._syncSparkCircles(); this._syncGoal(); });
    }, () => {
      this._eventContractVersion = 0;
      this.setData({ boundEvent: { id: eventId, title: '', err: 'load' }, eventOverlay: { show: false, missions: [], points: [], feedback: null } }, () => { this._syncPlayHeader(); this._syncSparkCircles(); this._syncGoal(); });
    });
  },

  _buildEventOverlay(event, eventId) {
    const missions = (event.missions || []).map((mission) => ({
      missionCode: mission.missionCode,
      title: mission.title || mission.missionCode || '活动任务',
      description: mission.description || mission.missionType || '',
      missionType: mission.missionType,
      canVerifyArrival: !!mission.canVerifyArrival,
      complete: !!mission.complete,
    }));
    const points = (event.overlay || []).map((point) => ({
      latitude: Number(point.latitude), longitude: Number(point.longitude), radiusM: Number(point.radiusM || 120),
      name: point.name || point.bindingCode || '活动节点', missionCodes: point.missionCodes || [],
    })).filter((point) => isFinite(point.latitude) && isFinite(point.longitude));
    return {
      show: true, id: eventId, title: event.title || '官方活动', status: event.status,
      signed: !!event.signed, paused: !!event.paused, pausedReason: event.pausedReason || '',
      missions, points, selectedMissionCode: this._eventMissionCode || '', feedback: this._eventOverlayFeedback || null,
    };
  },

  onBoundEventTap() {
    const be = this.data.boundEvent;
    if (!be) return;
    if (be.err) { this._loadBoundEvent(be.id); return; }
    if (be.v2) {
      this.setData({ 'eventOverlay.show': true }, () => this._syncSparkCircles());
    }
  },

  closeEventOverlay() {
    this.setData({ 'eventOverlay.show': false }, () => this._syncSparkCircles());
  },

  verifyEventArrival(e) {
    const overlay = this.data.eventOverlay || {};
    const missionCode = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.mission;
    const mission = (overlay.missions || []).find((item) => item.missionCode === missionCode);
    if (!overlay.id || !mission || !mission.canVerifyArrival) return;
    if (!overlay.signed) {
      this._setEventFeedback('请先报名；报名后的新到达才会计入本场活动。', 'warn');
      return;
    }
    if (overlay.paused) {
      this._setEventFeedback('活动当前暂停，暂不接受新的到达验证。', 'warn');
      return;
    }
    if (overlay.status !== 3) {
      this._setEventFeedback('活动尚未进行中，暂不能验证到达。', 'warn');
      return;
    }
    if (!this._realOn) {
      this._setEventFeedback('请先开启实时定位，再主动验证当前到达。', 'warn');
      this.toggleGps();
      return;
    }
    if (!this._roamSid) {
      this._setEventFeedback('先开始漫游并移动一小段，建立本次会话后再验证。', 'warn');
      return;
    }
    const requestKey = overlay.id + ':' + missionCode;
    if (!this._eventArrivalRequests) this._eventArrivalRequests = {};
    const requestId = this._eventArrivalRequests[requestKey] || ('arrival-' + requestKey + '-' + Date.now());
    this._eventArrivalRequests[requestKey] = requestId;
    this._setEventFeedback('正在确认你是否到达…', 'loading');
    wx.getLocation({
      type: 'gcj02',
      success: (location) => {
        req('/api/official/events/' + overlay.id + '/arrivals', 'POST', {
          missionCode,
          requestId,
          sessionId: String(this._roamSid),
          latitude: location.latitude,
          longitude: location.longitude,
          accuracyM: location.accuracy,
        }, { silentError: true }).then((res) => {
          if (!apiOk(res) || !res.data) {
            this._setEventFeedback((res && res.msg) || '本次验证没有送达，请保持当前位置后重试。', 'error');
            return;
          }
          const data = res.data;
          if (data.accepted) {
            const missions = (overlay.missions || []).map((item) => item.missionCode === missionCode
              ? Object.assign({}, item, { complete: !!data.completed }) : item);
            this._eventOverlayFeedback = { text: '验证成功，已计入本场活动', tone: 'success' };
            this.setData({ 'eventOverlay.missions': missions, 'eventOverlay.feedback': this._eventOverlayFeedback });
            if (data.completed) this._triggerCelebration('task-complete');
            motion.haptic({ type: 'light', reducedMotion: readReducedMotion() });
            cyToast.success('到达验证成功');
            return;
          }
          delete this._eventArrivalRequests[requestKey];
          this._setEventFeedback(this._arrivalReason(data.reason), 'error');
        });
      },
      fail: () => this._setEventFeedback('未能获取当前位置；请检查定位授权后重试。', 'error'),
    });
  },

  _arrivalReason(reason) {
    return ({
      NOT_SIGNED: '当前报名资格无效，不能计入活动',
      ACTIVITY_NOT_LIVE: '活动不在进行中，不能验证到达。',
      LOCATION_INVALID: '定位数据无效，请在地图定位稳定后重试。',
      LOCATION_ACCURACY_TOO_LOW: '定位精度不足，请到开阔处等待定位稳定后重试。',
      DISTANCE_TOO_FAR: '距离活动目标仍有一段距离，靠近后再验证。',
      MOVE_TOO_FAST: '位置变化异常快，本次验证已被风控拦截。',
      ROAM_CONTEXT_REQUIRED: '缺少本次漫游会话，先开始漫游再验证。',
      SESSION_NOT_OWNED: '本次漫游会话不属于当前账号。',
      SESSION_NOT_ACTIVE: '本次漫游已结算，重新开始漫游后再验证。',
      ARRIVAL_TOO_FREQUENT: '验证太频繁了，请在原地稍等片刻再试。',
      MISSION_ALREADY_COMPLETED: '该任务已经完成过了，无需重复验证。',
      ROAM_POI_ADAPTER_DISABLED: '漫游到达验证当前已暂停，请稍后再试。',
      POI_UNAVAILABLE: '活动引用点当前不可用，已反馈给运营。',
      EVENT_POINT_ADAPTER_DISABLED: '活动自有点验证尚未完成安全核验，暂不计奖。',
      EVENT_POINT_CONFIG_INVALID: '活动自有点配置不完整，暂不能验证。',
    })[reason] || '这次到达还没有确认成功，请稍后重试。';
  },

  _setEventFeedback(text, tone) {
    this._eventOverlayFeedback = { text, tone };
    this.setData({ 'eventOverlay.feedback': this._eventOverlayFeedback });
  },

  // P2 接真③:结算上报(POST /api/roam/finish),入参 distanceM 是原始米数(非公里),展示格式化由调用方另做(F20)
  // 原码失败时直接不 setData ⇒ finish.xpAwarded 从不出现 ⇒ wxml 的探索值奖励行静默消失,
  // 用户以为白走了。轨迹/探店都在本机(_saveSession 已落盘),缺的只是这一次结算上报 ⇒ 可重试。
  _postRoamFinish(distanceM) {
    if (this._finishPromise) return this._finishPromise;
    this._finishRequested = true;
    this._lastDistanceM = distanceM;
    if (this._recoveryReadFailed) return Promise.resolve(false);
    if (!this._clientSessionKey && !exactSessionId(this._roamSid))
      return this._ensureRoamRecovery().then(ok => ok ? this._postRoamFinish(distanceM) : false);
    if (!this._saveRoamRecovery()) return Promise.resolve(false);
    if (this._revealBootstrapUnknown || this._recoveryNeedsLookup || this.data.finish.settleErr === 'unknown') {
      this._recoveryNeedsLookup = true;
      return this.recoverRoam();
    }
    if (!exactSessionId(this._roamSid) && !this._pendingTiles.length) {
      this._recoveryNeedsLookup = true;
      return this.recoverRoam();
    }
    const generation = this._roamWriteGeneration;
    const drain = () => this._flushReveal().then((ok) => {
      if (generation !== this._roamWriteGeneration) return false;
      if (!ok) {
        this.setData({ 'finish.settleErr': this._revealBootstrapUnknown ? 'bootstrap' : 'reveal' });
        return false;
      }
      return this._pendingTiles.length ? drain() : sendFinish();
    });
    this._lastDistanceM = distanceM;
    const sendFinish = () => {
      const poiIds = Object.keys(this._foundRoamPois).join(',');
      return req('/api/roam/finish', 'POST', {
        sessionId: String(this._roamSid || 0),
        poiIds,
        distanceM: String(Math.round(distanceM || 0)),
      }).then((res) => {
        if (generation !== this._roamWriteGeneration || !this._recoveryOwnerCurrent()) return false;
        if ((res.code == '200' || res.code == 200) && res.data) {
          return this._applyRoamFinishResult(res.data, true);
        }
        this.setData({ 'finish.settleErr': (httpFail(res) || netFail(res)) ? 'unknown' : 'server' });
        return false;
      });
    };
    const pending = (this._revealPromise || this._revealBootstrapUnknown || (this._pendingTiles || []).length)
      ? drain() : sendFinish();
    this._finishPromise = pending;
    pending.then(() => { if (this._finishPromise === pending) this._finishPromise = null; });
    return pending;
  },

  continueUnstartedRoam() {
    if (!this.data.recoveryCanContinue || exactSessionId(this._roamSid) || this._pendingTiles.length) return false;
    this._finishRequested = false;
    if (!this._saveRoamRecovery()) { this._finishRequested = true; return false; }
    this._recoveryNeedsLookup = false;
    this.setData({ screen: 'entry-map', recoveryCanContinue: false, recoveryError: '', 'finish.settleErr': '' });
    return true;
  },

  _applyRoamFinishResult(result, complete, badgeDelivery) {
    if (typeof result.totalXp !== 'number' || !Number.isFinite(result.totalXp))
      return this._recoveryFailure('结算事实暂时无法读取，请重新确认');
    this._finishedAt = result.finishedAt || this._finishedAt || 0;
    const statuses = Array.isArray(badgeDelivery) ? badgeDelivery : result.badgeDelivery;
    const delivered = (code, sourceId) => Array.isArray(statuses) && statuses.some(row => row && typeof row === 'object'
      && row.badgeCode === code && row.state === 'DELIVERED'
      && (sourceId == null || row.sourceId == null || String(row.sourceId) === sourceId));
    // ApiRoamController freezes this exact name/code pair; another tier is not its receipt.
    const milestone = typeof result.medal === 'string' && result.medal.match(/^城市点亮 ([1-9]\d*) 格$/);
    const medal = milestone && delivered('ROAM_TILES_' + milestone[1], milestone[1]) ? result.medal : '';
    const shopMedal = result.shopMedal && delivered(result.shopMedal.code) ? result.shopMedal : null;
    this.setData({ screen: 'arrive', 'finish.xpAwarded': result.totalXp, 'finish.medal': medal,
      'finish.shopMedal': shopMedal, 'finish.routeStory': result.routeStory || '',
      'finish.resultIncomplete': !complete, 'finish.settleErr': '', recoveryError: '' });
    this._shopMedalFromServer = !!shopMedal;
    const memory = roamMemory(this);
    const archiveState = memory.readSessionState();
    if (!archiveState.ok) return this._recoveryFailure('结算已确认，足迹存档暂时无法读取，请重试确认');
    if (this._recoveryArchiveTs && !archiveState.sessions.some(row => row.ts === this._recoveryArchiveTs)) {
      if (!this._saveSession(this.data.finish)) return false;
    }
    const arr = memory.readSessions();
    const archived = arr.find(row => row.ts === this._recoveryArchiveTs);
    if (archived) {
      archived.finishedAt = this._finishedAt || archived.finishedAt || 0;
      archived.sessionId = exactSessionId(this._roamSid);
      archived.xpAwarded = result.totalXp;
      archived.medal = medal; archived.shopMedalName = shopMedal ? shopMedal.name : '';
      if (!memory.writeSessions(arr)) return this._recoveryFailure('结算已确认，足迹存档更新失败，请重试确认');
    }
    this._roamBadgePending = !!exactSessionId(this._roamSid) && !!((result.medal && !medal) || (result.shopMedal && !shopMedal));
    if (this._roamBadgePending) {
      if (!this._saveRoamRecovery()) return false;
    } else if (!memory.writeRecovery(null)) return this._recoveryFailure('结算已确认，本机恢复记录更新失败，请重试确认');
    if (!this._roamCompleted) {
      if (medal || shopMedal) this._triggerCelebration('medal-earned');
      if (result.routeStory) analytics.track('roam_route_story_shown', { bizType: 'roam' });
      if (medal) cyToast('获得勋章:' + medal);
    }
    this._roamCompleted = true; this._finishRequested = false;
    this._recoveryNeedsLookup = false; this._revealBootstrapUnknown = false;
    return true;
  },

  retrySettle() {
    if (this._finishPromise) return;
    if (['unknown', 'bootstrap', 'recovery'].includes(this.data.finish.settleErr)) {
      this._recoveryNeedsLookup = true;
      return this.recoverRoam();
    }
    this.setData({ 'finish.settleErr': '' });
    return this._postRoamFinish(this._lastDistanceM || 0);
  },

  async _genIcons() {
    this._icons = {};
    this._playerAvatarIcon = false;
    try {
      const cv = await new Promise((res, rej) => {
        wx.createSelectorQuery().select('#iconcv').fields({ node: true }).exec((r) =>
          (r && r[0] && r[0].node ? res(r[0].node) : rej('no iconcv')));
      });
      const S = 108; cv.width = S; cv.height = S;
      const g = cv.getContext('2d');
      const draw = (color, ring, check, gray) => {
        g.clearRect(0, 0, S, S);
        g.beginPath(); g.arc(S / 2, S / 2, 40, 0, 7);
        g.fillStyle = gray ? '#4A4A4A' : color; g.fill();
        g.lineWidth = ring === 'brand' ? 8 : 6;
        g.strokeStyle = ring === 'brand' ? BRAND : (gray ? 'rgba(255,255,255,.35)' : '#fff');
        g.stroke();
        if (check) {
          g.beginPath(); g.arc(S - 26, 26, 18, 0, 7); g.fillStyle = '#FFFFFF'; g.fill();
          g.strokeStyle = '#111111'; g.lineWidth = 5;
          g.beginPath(); g.moveTo(S - 34, 26); g.lineTo(S - 28, 33); g.lineTo(S - 17, 19); g.stroke();
        }
      };
      const save = () => new Promise((res, rej) =>
        wx.canvasToTempFilePath({ canvas: cv, fileType: 'png', success: (r) => res(r.tempFilePath), fail: rej }));
      // 图钉 = 打卡过的地方(实物图抠底的透明 PNG,不是重画的矢量)。拿不到就不画钉,
      // 点位照旧显示 —— 素材缺失不该让一整类点位从地图上消失。
      const pinImg = await new Promise((res) => {
        try {
          const im = cv.createImage();
          im.onload = () => res(im);
          im.onerror = () => res(null);
          im.src = '/images/roam-pushpin.png';
        } catch (e) { res(null); }
      });
      /* 图钉扎在点位右上角:钉头露在外面,针尖插进圆里(稿 5149:13261)。 */
      const stickPin = () => {
        if (!pinImg) return;
        const h = 34; const w = Math.round(h * pinImg.width / pinImg.height);
        g.save(); g.translate(S - 20, 22); g.rotate(Math.PI / 5);
        g.drawImage(pinImg, -w / 2, -h * 0.62, w, h);
        g.restore();
      };
      /* 星星 = 这家有玩法,仅此一义(金黄 GAME_STAR)。同时有图钉时让到左上角,两者不重叠。 */
      const hangStar = (left) => {
        const cx0 = left ? 22 : S - 22; const cy0 = 22;
        g.beginPath();
        for (let k = 0; k < 10; k++) {
          const r = k % 2 === 0 ? 17 : 7;
          const a = -Math.PI / 2 + k * Math.PI / 5;
          const x = cx0 + r * Math.cos(a); const y = cy0 + r * Math.sin(a);
          if (k === 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.closePath();
        g.fillStyle = GAME_STAR; g.fill();
        g.lineWidth = 4; g.strokeStyle = '#FFFFFF'; g.stroke();  /* ds-ok 与同文件既有 marker 描边同值;canvas 拿不到 var() */
      };
      for (const cat of ['merchant', 'park', 'landmark']) {
        const color = cat === 'merchant' ? '#D9D9D9' : cat === 'park' ? '#A6A6A6' : BRAND;
        for (const game of [false, true]) {
          const suffix = game ? '_game' : '';
          draw(color, 'white', false, false); if (game) hangStar(false); this._icons[cat + '_base' + suffix] = await save();
          draw(color, 'white', false, true); if (game) hangStar(false); this._icons[cat + '_passed' + suffix] = await save();
          // done = 打过卡 ⇒ 扎图钉;既有玩法又打过卡时星星让到左上角。
          // 勾选圈只在图钉素材没加载出来时顶上 —— 两个都画会在右上角撞成一团。
          draw(color, 'brand', !pinImg, false); stickPin(); if (game) hangStar(true); this._icons[cat + '_done' + suffix] = await save();
        }
      }
      for (const state of PLAY_MAP_STATE_ORDER) {
        drawPlayStateMarker(g, S, state);
        this._icons['state-' + state] = await save();
      }
      const drawNodeLevelIcon = (style, completed) => {
        g.clearRect(0, 0, S, S);
        g.beginPath();
        if (style.shape === 'circle') {
          g.arc(S / 2, S / 2, S * 0.31, 0, Math.PI * 2);
        } else if (style.shape === 'diamond') {
          g.moveTo(S / 2, S * 0.12); g.lineTo(S * 0.88, S / 2);
          g.lineTo(S / 2, S * 0.88); g.lineTo(S * 0.12, S / 2);
        } else if (style.shape === 'star') {
          for (let k = 0; k < 10; k++) {
            const radius = k % 2 === 0 ? S * 0.42 : S * 0.18;
            const angle = -Math.PI / 2 + k * Math.PI / 5;
            const x = S / 2 + radius * Math.cos(angle);
            const y = S / 2 + radius * Math.sin(angle);
            if (k === 0) g.moveTo(x, y); else g.lineTo(x, y);
          }
        } else {
          g.moveTo(S / 2, S * 0.08); g.lineTo(S * 0.84, S * 0.24);
          g.lineTo(S * 0.78, S * 0.72); g.lineTo(S / 2, S * 0.94);
          g.lineTo(S * 0.22, S * 0.72); g.lineTo(S * 0.16, S * 0.24);
        }
        g.closePath();
        g.fillStyle = style.fillColor; g.fill();
        g.lineWidth = 7; g.strokeStyle = style.borderColor; g.stroke();
        if (completed) {
          g.strokeStyle = style.borderColor; g.lineWidth = 7;
          g.beginPath(); g.moveTo(S * 0.31, S * 0.54);
          g.lineTo(S * 0.45, S * 0.68); g.lineTo(S * 0.72, S * 0.38); g.stroke();
        }
      };
      for (let level = 1; level <= 4; level++) {
        const style = getNodeLevelStyle(level);
        drawNodeLevelIcon(style, false);
        this._icons['node-level-' + level] = await save();
        drawNodeLevelIcon(style, true);
        this._icons['node-level-' + level + '-done'] = await save();
      }
      /* ★ 自己的定位点逐条照原型 .selfdot 抄(2026-09-09 用户裁决:所有页面与 HTML 一模一样):
         18px 蓝点 #2F7BF6 · 3px 白描边 · 外面一圈 8px 的 18% 蓝光晕。
         ⚠️ 现码原来是一枚白点套白圈 —— 地图上分不出「我」和别的白色点位。
         canvas 尺寸 S=108 对应原型那颗 18+3+8=37px 的整体,系数 k=S/37。 */
      g.clearRect(0, 0, S, S);
      {
        const k = S / 37; const cx = S / 2; const cy = S / 2;
        g.beginPath(); g.arc(cx, cy, (9 + 3 + 8) * k, 0, 7);
        g.fillStyle = 'rgba(47,123,246,.18)'; g.fill();          /* ds-ok 原型 box-shadow 0 0 0 8px rgba(47,123,246,.18) */
        g.beginPath(); g.arc(cx, cy, (9 + 3) * k, 0, 7);
        g.fillStyle = '#FFFFFF'; g.fill();                        /* ds-ok 原型 border:3px solid #fff */
        g.beginPath(); g.arc(cx, cy, 9 * k, 0, 7);
        g.fillStyle = '#2F7BF6'; g.fill();                        /* ds-ok 原型 .selfdot 背景 */
      }
      this._icons.player = await save();
      // 2026-09-09 删:原来这里生成一枚整星 merchant_star,给「雾揭开过的商家(seen)」当整个点位用 ——
      // 于是星星表示「去过了」。星星现在只表示「这家有玩法」(挂在角上,见 hangStar),
      // 「打过卡」改由图钉表示(stickPin)。一枚符号两个意思,看不出区别。
    } catch (e) { console.warn('icon gen fail'); this._icons = {}; }
  },

  // 门店 AI 形象 marker。判定逻辑在 utils/roam-npc-marker.js(那边有单测),这里只接线。
  _collectNpcIconNeeds() {
    return collectNpcIconNeeds({
      pois: this._pois, visit: this.data.visit, icons: this._icons,
      failed: this._npcIconFailed, getState: getRoamPoiState,
    });
  },

  async _genNpcIcons() {
    if (this._npcIconPending) return false;
    this._npcIconFailed = this._npcIconFailed || {};
    const need = this._collectNpcIconNeeds();
    const playerAvatar = String(this.data.avatar || '').trim();
    const playerKey = playerAvatar ? npcIconKey(playerAvatar, 1, false, 'player') : '';
    if (playerKey && !this._icons[playerKey] && !this._npcIconFailed[playerAvatar]) {
      need.set(playerKey, { url: playerAvatar, level: 1, done: false, kind: 'player' });
    }
    if (!need.size) return false;
    this._npcIconPending = true;
    let made = 0;
    try {
      const cv = await new Promise((res, rej) => {
        wx.createSelectorQuery().select('#iconcv').fields({ node: true }).exec((r) =>
          (r && r[0] && r[0].node ? res(r[0].node) : rej('no iconcv')));
      });
      const S = 108; cv.width = S; cv.height = S;
      const g = cv.getContext('2d');
      const save = () => new Promise((res, rej) =>
        wx.canvasToTempFilePath({ canvas: cv, fileType: 'png', success: (r) => res(r.tempFilePath), fail: rej }));
      const roundRect = (ctx, x, y, width, height, radius) => {
        const r = Math.min(radius, width / 2, height / 2);
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.arcTo(x + width, y, x + width, y + height, r);
        ctx.arcTo(x + width, y + height, x, y + height, r);
        ctx.arcTo(x, y + height, x, y, r);
        ctx.arcTo(x, y, x + width, y, r);
        ctx.closePath();
      };
      const drawCover = (ctx, img, x, y, width, height, pixelated) => {
        const natural = Math.max(1, Math.min(img.width, img.height));
        const sx = Math.max(0, (img.width - natural) / 2);
        const sy = Math.max(0, (img.height - natural) / 2);
        if (pixelated) {
          const drawSize = pixelDrawSize(natural, Math.min(width, height));
          ctx.imageSmoothingEnabled = false;
          ctx.drawImage(img, sx, sy, natural, natural,
            x + (width - drawSize) / 2, y + (height - drawSize) / 2, drawSize, drawSize);
          ctx.imageSmoothingEnabled = true;
          return;
        }
        ctx.imageSmoothingEnabled = true;
        ctx.drawImage(img, sx, sy, natural, natural, x, y, width, height);
      };
      // 网络图进 canvas 走的是 downloadFile 通道,跟 <image> 不是一回事:
      // 域名没进「downloadFile 合法域名」就会 onerror,而且不会有任何提示。
      // 所以失败必须记名单、不重试,并让 marker 静默回落既有状态图标 —— 地图不能因为
      // 一张头像没下下来就少一个点位。
      const loadImage = (src) => new Promise((res, rej) => {
        const img = cv.createImage();
        const timer = setTimeout(() => rej(new Error('timeout')), 6000);
        img.onload = () => { clearTimeout(timer); res(img); };
        img.onerror = () => { clearTimeout(timer); rej(new Error('load fail')); };
        img.src = src;
      });

      for (const [key, spec] of need) {
        let img;
        try { if (!pixelAvatar.isPixelAvatar(spec.url)) img = await loadImage(spec.url); }
        catch (e) { this._npcIconFailed[spec.url] = true; continue; }

        g.clearRect(0, 0, S, S);
        const kind = spec.kind || 'npc';
        if (kind === 'player') {
          g.shadowColor = MAP_AVATAR_COLORS.player;
          g.shadowBlur = 14;
          g.beginPath(); g.arc(54, 54, 43, 0, Math.PI * 2);
          g.fillStyle = MAP_AVATAR_COLORS.panel; g.fill();
          g.shadowColor = 'transparent'; g.shadowBlur = 0;
          g.lineWidth = 7; g.strokeStyle = MAP_AVATAR_COLORS.player; g.stroke();
          g.save();
          g.beginPath(); g.arc(54, 54, 34, 0, Math.PI * 2); g.clip();
          drawCover(g, img, 20, 20, 68, 68, false);
          g.restore();
        } else {
          const accent = kind === 'merchant' ? MAP_AVATAR_COLORS.merchant : MAP_AVATAR_COLORS.npc;
          roundRect(g, 9, 6, 90, 96, 18);
          g.fillStyle = MAP_AVATAR_COLORS.panel; g.fill();
          g.lineWidth = 6; g.strokeStyle = accent; g.stroke();
          g.save();
          roundRect(g, 16, 14, 76, 68, 12); g.clip();
          if (pixelAvatar.isPixelAvatar(spec.url)) pixelAvatar.drawAvatarInBox(g, spec.url, 16, 14, 76, 68);
          else drawCover(g, img, 16, 14, 76, 68, kind === 'npc');
          g.restore();
          if (kind === 'merchant') {
            const stripeWidth = 76 / 5;
            for (let stripe = 0; stripe < 5; stripe++) {
              g.fillStyle = stripe % 2 ? MAP_AVATAR_COLORS.cream : MAP_AVATAR_COLORS.merchantAccent;
              g.fillRect(16 + stripe * stripeWidth, 14, stripeWidth + 1, 13);
            }
          }
          roundRect(g, 28, 86, 52, 12, 6);
          g.fillStyle = accent; g.fill();
          if (kind === 'npc') {
            g.beginPath(); g.arc(90, 18, 13, 0, Math.PI * 2);
            g.fillStyle = MAP_AVATAR_COLORS.cream; g.fill();
            g.fillStyle = MAP_AVATAR_COLORS.panel;
            g.font = 'bold 18px sans-serif';
            g.textAlign = 'center'; g.textBaseline = 'middle';
            g.fillText('!', 90, 18);
          }
        }

        if (spec.done) {
          g.beginPath(); g.arc(S - 20, S - 20, 15, 0, Math.PI * 2);
          g.fillStyle = MAP_AVATAR_COLORS.cream; g.fill();
          g.strokeStyle = MAP_AVATAR_COLORS.panel; g.lineWidth = 4;
          g.beginPath(); g.moveTo(S - 27, S - 20); g.lineTo(S - 22, S - 15);
          g.lineTo(S - 13, S - 26); g.stroke();
        }
        this._icons[key] = await save();
        if (kind === 'player') {
          this._icons.player = this._icons[key];
          this._playerAvatarIcon = true;
        }
        made++;
      }
    } catch (e) {
      console.warn('npc icon gen fail');
    } finally {
      this._npcIconPending = false;
    }
    return made > 0;
  },

  /* 顶部输入地址的候选(§6.1)。喂的是这张图上真有的点位:
   *   挂着主题的 = 按玩法给绿 / 蓝针;没挂主题的 = 灰针 + 副标题「没有局」。
   * 2026-10-27 owner P-32:搜地点不受迷雾影响 —— 雾里的点也进候选,开着迷雾也能搜、能导航(取代原「雾里的点不进候选」)。 */
  _searchPlaces() {
    return (this._pois || []).filter((p) => p.lat && p.lng).map((p) => ({
      id: String(p.id),
      name: p.name || '未命名地点',
      address: p.desc || '',
      kind: p.topicId ? (Number(p.productType) === 2 ? 'free' : 'city') : 'none',
      lat: p.lat, lng: p.lng, poiId: p.id,
      // 敲字母也能搜:xhbj → 徐汇滨江。字段是后端给的,没给就退回只按中文匹配
      pinyin: p.namePinyin || '', initials: p.nameInitials || '',
    }));
  },

  /** 选中一条地址 = 把地图挪过去。不跳页:这一屏本身就是要看的东西。 */
  onSearchPick(e) {
    const p = e.detail || {};
    const lat = Number(p.lat); const lng = Number(p.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
    const center = { lat, lng }; this.setData({ center }); if (this.data.screen === 'entry-map' && typeof this._loadEntryMap === 'function') this._loadEntryMap(center);
  },

  /**
   * 把 stats.<key> 滚到新值。逐条照原型 countUp:760ms、缓出 1-(1-p)³、
   * 带冒号的时间与 0 不滚、减少动态效果直接落定。
   * 每个 key 一个计时器,新值来了就把旧的掐掉 —— 两个动画抢同一个字段会来回跳。
   */
  _rollStat(key, value) {
    this._rollTimers = this._rollTimers || {};
    if (this._rollTimers[key]) { clearInterval(this._rollTimers[key]); this._rollTimers[key] = null; }
    /* ⚠️ 刻意整对象重写,而不是 setData({['statsShown.'+key]: v}):
       U4 门禁认得出 setData 的顶层字段名,但认不出对象字面量里的计算键 ——
       写成计算键能过闸,可那正是这道闸要防的东西。整对象只有四个字段,重写不值几个钱。 */
    const put = (v) => this.setData({ statsShown: Object.assign({}, this.data.statsShown, { [key]: v }) });
    if (this.data.reducedMotion || !countUp.shouldCount(value)) { put(value); return; }
    const t0 = Date.now();
    this._rollTimers[key] = setInterval(() => {
      const elapsed = Date.now() - t0;
      put(countUp.frame(value, elapsed));
      if (elapsed >= countUp.DURATION) { clearInterval(this._rollTimers[key]); this._rollTimers[key] = null; }
    }, 40);
  },

  _clearRollTimers() {
    Object.keys(this._rollTimers || {}).forEach((k) => {
      if (this._rollTimers[k]) clearInterval(this._rollTimers[k]);
    });
    this._rollTimers = {};
  },

  _syncMarkers() {
    const mk = [];
    const mapStates = [];
    const visit = this.data.visit || {};
    const poiFilter = this._poiFilterState();
    (this._pois || []).forEach((p) => {
      if (p.state === 'fog') return;
      const stateKey = getRoamPoiState(p, visit);
      if (roamPoiFilter.hiddenByFilter(p, poiFilter, stateKey)) return;
      const state = getPlayMapState(stateKey);
      mapStates.push({ nodeId: p.id, state: stateKey });
      // 有玩法 = 这个点挂着主题(topicId)。星星只认这一件事,和去没去过无关。
      const hasGame = !!p.topicId;
      const legacyKey = p.cat + '_' + (p.state === 'done' ? 'done' : p.state === 'passed' ? 'passed' : 'base')
        + (hasGame ? '_game' : '');
      const stateIconKey = 'state-' + (state.markerKey || state.key);
      const nodeStyle = buildNodeLevelMarkerStyle(p.nodeLevel, stateKey);
      const size = state.key === 'target' ? 50 : (p.isCityNode ? nodeStyle.size : 34);
      const m = {
        id: p.id,
        latitude: p.lat,
        longitude: p.lng,
        width: size,
        height: size,
        anchor: { x: 0.5, y: 0.5 },
        zIndex: state.key === 'target' || state.key === 'checkin-failed' ? 20 : (p.isCityNode ? nodeStyle.zIndex : 8),
      };
      /* CU-M-54:待核销跟 target / checkin-failed 同类 —— 它是一个「你还有一步没做」的安全反馈
         (券还没到手),必须保持同一枚状态图标;换成店头图形象就会被认成「普通一家店」。 */
      const keepStateIcon = stateKey === 'target' || stateKey === 'checkin-failed' || stateKey === 'redeem-pending';
      // 游戏化头像优先于通用点位图标:商家上传头像用店铺框,NPC 形象用剧情框。
      // 没配 / 没下下来时 npcKey 取不到,自动落回下面原有的三级链,一个点位都不会少。
      const avatar = markerAvatar(p);
      const avatarKind = p.mapAvatarKind || (p.npcAvatar === avatar ? 'npc' : 'merchant');
      const npcKey = (!keepStateIcon && p.cat === 'merchant' && avatar)
        ? npcIconKey(avatar, Number(p.nodeLevel || 1), stateKey === 'completed', avatarKind)
        : null;
      if (npcKey && this._icons && this._icons[npcKey]) {
        m.iconPath = this._icons[npcKey];
        const rawDist = Number(p.distM);
        const avatarSize = Number.isFinite(rawDist) && rawDist <= 300 ? 52 : 44;
        m.width = avatarSize;
        m.height = avatarSize;
      }
      else if (p.isCityNode && !keepStateIcon && this._icons && this._icons[nodeStyle.iconKey]) {
        m.iconPath = this._icons[nodeStyle.iconKey];
      }
      else if (this._icons && this._icons[stateIconKey]) m.iconPath = this._icons[stateIconKey];
      else if (this._icons && this._icons[legacyKey]) m.iconPath = this._icons[legacyKey];
      if (stateKey === 'actionable') {
        // 真 POI 只显店名；演示种子必须从地图首层就表明非真实，不能等点开卡片才澄清。
        m.callout = {
          content: p.demo ? '演示点 · 非真实\n' + p.name : p.name,
          color: '#FFFFFF',
          fontSize: 11,
          borderRadius: 10,
          bgColor: '#141414CC',
          padding: 6,
          display: 'ALWAYS',
        };
      }
      if (stateKey === 'target') {
        m.callout = {
          content: '正在打卡',
          color: '#FFFFFF',
          fontSize: 11,
          borderRadius: 10,
          bgColor: '#000000CC',
          padding: 6,
          display: 'ALWAYS',
        };
      }
      if (stateKey === 'checkin-failed') {
        m.callout = {
          content: '打卡未记录',
          color: '#FFFFFF',
          fontSize: 11,
          borderRadius: 10,
          bgColor: '#000000CC',
          padding: 6,
          display: 'ALWAYS',
        };
      }
      if (stateKey === 'completed') {
        m.callout = {
          content: p.cat === 'merchant' ? '已探店' : '已点亮',
          color: '#FFFFFF',
          fontSize: 11,
          borderRadius: 10,
          bgColor: '#000000CC',
          padding: 6,
          display: 'ALWAYS',
        };
      }
      if (p.isCityNode) {
        m.callout = {
          content: nodeStyle.glyph + ' ' + nodeStyle.label + ' · ' + p.name
            + (stateKey === 'completed' ? ' · 已探店' : ''),
          display: 'ALWAYS',
        };
      }
      mk.push(m);
    });
    const goalStateKey = this.data.goal && this.data.goal.stateKey;
    if (goalStateKey === 'location-off' || goalStateKey === 'offline') {
      const state = getPlayMapState(goalStateKey);
      const anchor = this._player || this.data.center;
      mapStates.push({ nodeId: STATUS_MARKER_ID, state: goalStateKey });
      if (anchor && anchor.lat != null && anchor.lng != null) {
        const status = {
          id: STATUS_MARKER_ID,
          latitude: anchor.lat + m2lat(42),
          longitude: anchor.lng,
          width: 38,
          height: 38,
          anchor: { x: 0.5, y: 0.5 },
          zIndex: 1000,
        };
        const key = 'state-' + (state.markerKey || state.key);
        if (this._icons && this._icons[key]) status.iconPath = this._icons[key];
        mk.push(status);
      }
    }
    // 别人(原型 otherRunners)。排在自己之前 push,zIndex 也压自己一头之下 ——
    // 屏上永远分得清哪一个是「我」。
    (this._runners || []).forEach((r, i) => {
      const key = 'runner-' + r.memberId;
      if (!this._icons || !this._icons[key]) return;   // 图还没画出来就先不画这个人,不放一个空白方块上去
      mk.push({
        id: RUNNER_MK_BASE + i,
        latitude: r.lat,
        longitude: r.lng,
        width: 44,
        height: 44,
        anchor: { x: 0.5, y: 0.5 },
        zIndex: 900,
        iconPath: this._icons[key],
        callout: {
          content: r.nickname,
          color: '#FFFFFF', /* ds-ok 与本页其它 marker callout 同一套(地图气泡不吃 wxss token) */
          fontSize: 11,
          borderRadius: 10,
          bgColor: '#000000CC', /* ds-ok 同上,与「已探店 / 正在打卡」两个气泡一字不差 */
          padding: 6,
          display: 'ALWAYS',
        },
      });
    });
    const p = this._player;
    if (p && this.data.mapReady) {
      const playerSize = this._playerAvatarIcon ? 48 : 40;
      const pm = {
        id: PLAYER_MK,
        latitude: p.lat,
        longitude: p.lng,
        width: playerSize,
        height: playerSize,
        anchor: { x: 0.5, y: 0.5 },
        zIndex: 999,
      };
      if (this._heading != null && !this._playerAvatarIcon) pm.rotate = Math.round(this._heading);
      if (this._icons && this._icons.player) pm.iconPath = this._icons.player;
      mk.push(pm);
    }
    const summary = buildMapA11yLabel(mapStates, null);
    const goalLabel = this.data.goal && this.data.goal.readerLabel;
    // 候选跟着点位一起刷:雾揭开一片、来了新商家,搜索里立刻能搜到
    this.setData({ markers: mk, roamMapA11y: goalLabel ? summary + '。' + goalLabel : summary, searchPlaces: this._searchPlaces() });
    // 形象图标按需补齐:只有真生成出新图标才重刷一次。_genNpcIcons 自己会判「没活干就早退」,
    // 这里不要再判一遍 —— 那会让每次 _syncMarkers 把 POI 白走两遍。
    // 失败的 URL 进黑名单不再重试,所以需求集会收敛到空,不存在「生成→重刷→再生成」的来回。
    this._genNpcIcons().then((made) => { if (made) this._syncMarkers(); });
  },

  /* ── 附近正在漫游的人(原型 otherRunners / otherSheet)────────────────────────
     两条心跳,都挂在真实位移上,不另起定时器:
       · _reportPresence 把自己报上去 —— 30 s 一次
       · _fetchRunners 把别人拉下来 —— 45 s 一次
     分享默认关闭，只有单独同意端点能开启；心跳永不修改分享偏好。
     暂停后 5 分钟内下线，结束会话立即下线；设置中的关闭立即停止公开。 */
  _reportPresence(p) {
    if (!this._roamSid || !p) return;
    const now = Date.now();
    if (this._presenceAt && now - this._presenceAt < PRESENCE_MS) return;
    this._presenceAt = now;
    return req('/api/roam/presence', 'POST', {
      sessionId: String(this._roamSid),
      lat: p.lat, lng: p.lng,
      explorePct: this._stats.explorePct || 0,
    }, { silentError: true }).then((res) => this._markRoamSync(res));
  },

  /* BL-03:只改提示,不改计时/重试。值没变就不 setData(GPS 回调里高频进来)。 */
  _roamSyncStatus() {
    if (!this._syncStatus) this._syncStatus = createRoamSyncStatus();
    return this._syncStatus;
  },
  _applyRoamSyncOffline(offline) {
    if (offline !== this.data.syncOffline) this.setData({ syncOffline: offline });
  },
  _markRoamSync(res) { this._applyRoamSyncOffline(this._roamSyncStatus().report(res)); },
  _bindRoamNetwork() {
    if (this._onRoamNetwork || typeof wx.onNetworkStatusChange !== 'function') return;
    this._onRoamNetwork = (res) => this._applyRoamSyncOffline(this._roamSyncStatus().setConnected(!!(res && res.isConnected)));
    wx.onNetworkStatusChange(this._onRoamNetwork);
  },
  _unbindRoamNetwork() {
    if (this._onRoamNetwork && typeof wx.offNetworkStatusChange === 'function') wx.offNetworkStatusChange(this._onRoamNetwork);
    this._onRoamNetwork = null;
  },

  _fetchRunners(p) {
    if (!p || !this.data.presenceVisible) return;
    const now = Date.now();
    if (this._runnersAt && now - this._runnersAt < RUNNERS_MS) return;
    this._runnersAt = now;
    const sid = this._roamSid;
    req('/api/roam/nearby-runners', 'POST', { lat: p.lat, lng: p.lng, radius: ROAM_POI_RADIUS_M },
      { silentError: true }).then((res) => {
      if (!this.data.presenceVisible || String(this._roamSid) !== String(sid)) return;
      if (!res || res.code != '200') return;   // 拉不到就保持上一批,不清空 —— 闪掉再回来更难看
      const rows = normalizeRunners(res.data);
      // 一个人都没有也要落:上一批里走远的人得消失
      this._runners = rows;
      this._genRunnerIcons().then(() => this._syncMarkers());
    });
  },

  /* 别人的头像画成「彩色描边圆」,和自己的蓝点、点位的方框都分得开。
     头像下不下得来都要有 marker —— 下不来就画首字,地图上不能少一个人。 */
  async _genRunnerIcons() {
    const rows = this._runners || [];
    if (!rows.length) return false;
    this._runnerIconFailed = this._runnerIconFailed || {};
    const todo = rows.filter((r) => !this._icons || !this._icons['runner-' + r.memberId]);
    if (!todo.length) return false;
    let made = 0;
    try {
      const cv = await new Promise((res, rej) => {
        wx.createSelectorQuery().select('#iconcv').fields({ node: true }).exec((r) =>
          (r && r[0] && r[0].node ? res(r[0].node) : rej('no iconcv')));
      });
      const S = 108; cv.width = S; cv.height = S;
      const g = cv.getContext('2d');
      const save = () => new Promise((res, rej) =>
        wx.canvasToTempFilePath({ canvas: cv, fileType: 'png', success: (r) => res(r.tempFilePath), fail: rej }));
      const loadImage = (src) => new Promise((res, rej) => {
        const img = cv.createImage();
        const timer = setTimeout(() => rej(new Error('timeout')), 6000);
        img.onload = () => { clearTimeout(timer); res(img); };
        img.onerror = () => { clearTimeout(timer); rej(new Error('load fail')); };
        img.src = src;
      });
      for (const r of todo) {
        let img = null;
        if (r.avatar && !this._runnerIconFailed[r.avatar]) {
          try { img = await loadImage(r.avatar); }
          catch (e) { this._runnerIconFailed[r.avatar] = true; }
        }
        g.clearRect(0, 0, S, S);
        g.beginPath(); g.arc(54, 54, 43, 0, Math.PI * 2);
        g.fillStyle = MAP_AVATAR_COLORS.panel; g.fill();
        g.lineWidth = 7; g.strokeStyle = r.color; g.stroke();
        if (img) {
          const natural = Math.max(1, Math.min(img.width, img.height));
          const sx = Math.max(0, (img.width - natural) / 2);
          const sy = Math.max(0, (img.height - natural) / 2);
          g.save();
          g.beginPath(); g.arc(54, 54, 34, 0, Math.PI * 2); g.clip();
          g.drawImage(img, sx, sy, natural, natural, 20, 20, 68, 68);
          g.restore();
        } else {
          g.fillStyle = r.color;
          g.font = 'bold 40px sans-serif';
          g.textAlign = 'center'; g.textBaseline = 'middle';
          g.fillText(String(r.nickname || '·').slice(0, 1), 54, 56);
        }
        this._icons['runner-' + r.memberId] = await save();
        made++;
      }
    } catch (e) {
      console.warn('runner icon gen fail');
    }
    return made > 0;
  },

  /** 点地图上的别人 → 半屏。数据取本地那一批,不再回一次接口(那批 45 s 才换一次)。 */
  openRunner(memberId) {
    if (!this.data.presenceVisible) return;
    const row = (this._runners || []).find((r) => r.memberId === memberId);
    if (!row) return;
    this.setData({ runnerView: row });
    this.openScene('roam-runner');
  },

  /** 「打个招呼」= 开一条真的私信会话。开不起来就说清楚,不假装发出去了。 */
  runnerSayHi() {
    const row = this.data.runnerView;
    if (!row || !row.memberId || this._navigating) return; this._navigating = true; // 开会话到跳转完成前不响应连点
    req('/api/im/start', 'POST', { target_member_id: row.memberId }).then((res) => {
      const cid = res && res.code == '200' && res.data && res.data.conversationId;
      if (!cid) { this._navigating = false; cyToast((res && res.msg) || '这会儿开不了会话，晚点再试'); return; }
      wx.navigateTo({
        url: '/subpackageB/pages/im/chat/index?conversationId=' + cid
          + '&name=' + encodeURIComponent(row.nickname || '')
          + '&avatar=' + encodeURIComponent(row.avatar || '') + '&type=1', complete: () => { this._navigating = false; },
      });
    });
  },

  /** 「看 TA 的漫游」= 去 TA 的主页。这里没有第二个人的漫游详情页,不编一个。 */
  runnerOpenProfile() {
    const row = this.data.runnerView;
    if (!row || !row.memberId) return;
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/userinfo/userinfo?userId=' + row.memberId, complete: () => { this._navigating = false; } });
  },

  _updateHeading(from, to) {
    if (!from || !to) return;
    const dy = to.lat - from.lat;
    const dx = to.lng - from.lng;
    if (Math.hypot(dx, dy) < 1e-7) return;
    let deg = Math.atan2(dx, dy) * 180 / Math.PI;
    if (deg < 0) deg += 360;
    if (Math.abs(deg - (this._heading || 0)) > 1.5) {
      this._heading = deg;
      this.setData({ heading: Math.round(deg) });
      this._syncMarkers();
    }
  },

  _buildOpeningEmojis(rand) { return buildOpeningEmojis(rand); },

  goStart() {
    if (this.data.entrySheet) this.closeEntrySheet();
    if (this._roamStartPending) return;
    // 未登录不开漫游:没有会话时 /api/roam/* 全被后端 401 挡下 —— 据点层永远「没加载出来」、
    // 每次揭雾上报弹一次「请先登录」、结算永远失败,而页面自己没有任何出路。
    // 2026-10-24 owner 拍板「游客可浏览，写入时弹窗登录」:开漫游会写足迹进度,游客先弹「登录后继续」
    // (app.requireLogin,单航班 firstLogin),登录成功恢复本次开局;点「暂不登录」页面原样不动。
    // 仍没会话就把门闩放掉,给一句人话让人再点。
    // ⚠️ 只在能拿到会话 API 时才判:getApp 抛 / getUserID 不存在(测试沙箱、极早期初始化)
    //    一律放行走原流程 —— 门闩语义不能因为判登录这层新闸被改变。
    let loginApp = null;
    try { loginApp = getApp(); } catch (e) { loginApp = null; }
    if (loginApp && typeof loginApp.getUserID === 'function' && !loginApp.getUserID()) {
      this._roamStartPending = true;
      const asked = typeof loginApp.requireLogin === 'function'
        ? loginApp.requireLogin({ reason: 'roam' })
        : Promise.resolve(loginApp.firstLogin ? loginApp.firstLogin() : null);
      Promise.resolve(asked).then((ok) => {
        this._roamStartPending = false;
        if (typeof loginApp.getUserID === 'function' && loginApp.getUserID()) { this.goStart(); return; }
        if (ok === false) return; // 用户选「暂不登录」:不报错,留在游客浏览态
        this.setData({ introError: '登录失败，请重试', locErr: false });
      });
      return;
    }
    if (this._roamCompleted) this._resetRoamRecoverySession();
    if (this._recoveryReadFailed || this._recoveryNeedsLookup || this._revealBootstrapUnknown || this.data.recoveryError) {
      this.recoverRoam();
      return;
    }
    if (!loginApp || typeof loginApp.recordConsent !== 'function') {
      this.setData({ introError: '登录失败，请重试', locErr: false });
      return;
    }
    this._roamStartPending = true;
    const gen = (this._roamStartGen = this._roamStartGen || 0);
    const locationRevision = bgTracker.revision('roam');
    const identity = () => {
      try {
        const app = getApp();
        return [typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '',
          (app.globalData || {}).authorization || ''].join('|');
      } catch (e) { return null; }
    };
    const owner = identity();
    this._observeLocationRevocation();
    const stale = () => gen !== this._roamStartGen || locationRevision !== bgTracker.revision('roam')
      || this._hidden || this._unloaded || owner !== identity();
    const abandon = (message) => {
      if (gen !== this._roamStartGen) return;
      this._roamStartPending = false;
      this._shootAfterStart = false;
      if (message && !stale()) this.setData({ introError: message, locErr: false });
    };
    try { this.markRoamIntroSeen(); } catch (e) { /* 引导标记失败不阻断用户明确同意后的出发 */ }
    const locate = () => {
      if (stale()) { abandon(); return; }
      wx.getLocation({
        type: 'gcj02',
        complete: () => { if (stale()) abandon(); else this._roamStartPending = false; },
        success: (r) => {
          if (stale()) { abandon(); return; }
          this._roamStartPending = false;
          lastLocation.save(r);
          this.setData({ introError: '', locErr: false });
          this._openRoamMap({ lat: r.latitude, lng: r.longitude }, true);
        },
        fail: () => {
          if (stale()) { abandon(); return; }
          this._roamStartPending = false;
          this._shootAfterStart = false;
          this.setData({ introError: '请开启定位后重试', locErr: true });
        },
      });
    };
    // Existing system permission does not replace a fresh explicit choice after a recorded revoke.
    try { cyModal.show({
      title: '开启实时定位',
      content: '用于实时定位、打卡和轨迹记录，锁屏后仍会记录，结束漫游即停止。',
      confirmText: '同意并开启',
      cancelText: '暂不开启',
      success: (result) => {
        if (stale()) { abandon(); return; }
        if (!result.confirm) { abandon(); return; }
        Promise.resolve().then(() => {
          if (stale()) { abandon(); return; }
          return loginApp.recordConsent({ docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE' });
        }).then(locate).catch(() => abandon('同意记录失败，请检查网络后重试'));
      },
      fail: () => abandon('定位同意未完成，请重试'),
    }); } catch (e) { abandon('定位同意未完成，请重试'); }
  },

  _openRoamMap(startCenter, startTracking) {
    const p = startCenter || FALLBACK;
    this._disposeFogRenderer();
    const fogGeneration = this._fogLifecycleGen;
    const locationRevision = bgTracker.revision('roam');
    const trackingCurrent = () => !startTracking || locationRevision === bgTracker.revision('roam');
    this._fogStartPending = { p, startTracking: !!startTracking, generation: fogGeneration, locationRevision };
    if (startTracking) this._observeLocationRevocation();
    this._initWorld(p);
    this._hold = 0;
    this._centerTimer = 0;
    this._lineTimer = 0;
    this.setData({
      // 定位成功后先进入弧线切场。真实地图已经在这层下面挂载并预热，
      // 但交互由开场遮罩承接；动画结束才把状态交给正式 HUD。
      screen: 'opening',
      openingEmojis: this._buildOpeningEmojis(),
      mapReady: true,
      paused: false,
      endHoldPct: 0,
      mapScale: SCALE_FAR,
      center: { lat: p.lat, lng: p.lng },
      fogScreenReady: false,
      fogScreenLayers: [],
      fogScreenGuard: true,
      currentAddress: startTracking ? '当前位置' : '尚未开启定位',
    }, () => { this._syncPlayHeader(); this._scheduleFootprintHint(); });
    if (this._roamOpeningTimer) clearTimeout(this._roamOpeningTimer);
    if (this._roamZoomTimer) {
      clearTimeout(this._roamZoomTimer);
      this._roamZoomTimer = null;
    }
    this._roamOpeningTimer = setTimeout(() => {
      this._roamOpeningTimer = null;
      if (fogGeneration !== this._fogLifecycleGen || this.data.screen !== 'opening') return;
      this.setData({ screen: 'map' }, () => {
        // 开场只覆盖转场。地图露出来之后仍走原来的远景→步行缩放，不把缩完的迷雾圈当作新样式。
        this._roamZoomTimer = setTimeout(() => {
          this._roamZoomTimer = null;
          if (fogGeneration !== this._fogLifecycleGen || this.data.screen !== 'map') return;
          this._animateZoom(SCALE_FAR, SCALE_WALK, 1400, p);
        }, 650);
        if (!this._shootAfterStart) return;
        this._shootAfterStart = false;
        this._shootAfterStartTimer = setTimeout(() => {
          this._shootAfterStartTimer = null;
          if (this.data.screen === 'map') this.onTakePhoto();
        }, 400);
      });
    }, this.data.reducedMotion ? ROAM_OPENING_REDUCED_MS : ROAM_OPENING_MS);
    this._syncGoal();   // 目标层进地图即有内容,不是空条(§7 常驻)
    if (this._npc) this._npc.dispatch({ type: 'SESSION_STARTED' });   // 进图开场
    this._mapLoadWatch();
    this._fogOpenTimer = setTimeout(() => {
      this._fogOpenTimer = null;
      if (!trackingCurrent() || fogGeneration !== this._fogLifecycleGen
        || (this.data.screen !== 'opening' && this.data.screen !== 'map' && this.data.screen !== 'arrive')) return;
      const mapComponent = this.selectComponent('#roamMap');
      this._mapCtx = mapComponent && mapComponent.getMapContext();
      this._setupFog(fogGeneration).then((ready) => {
        if (!ready || !trackingCurrent() || fogGeneration !== this._fogLifecycleGen
          || (this.data.screen !== 'opening' && this.data.screen !== 'map' && this.data.screen !== 'arrive')) return;
        this._activateRoamAfterFog(p, startTracking, fogGeneration);
      });
    }, 120);
  },

  _activateRoamAfterFog(p, startTracking, generation) {
    if (generation !== this._fogLifecycleGen || this._fogMode !== 'screen') return;
    const pending = this._fogStartPending || { p, startTracking: !!startTracking, generation };
    this._fogStartPending = pending;
    if (pending.revealAdded) { this._renderScreenFog(); return; }
    pending.revealAdded = true;
    this._addReveal(p); // 先产出首张圆洞图；clock/location 必须等当前 image bindload 后再启动
  },

  _completeRoamAfterFogLoad(generation) {
    const pending = this._fogStartPending;
    if (!pending || pending.generation !== generation || generation !== this._fogLifecycleGen
      || this._fogMode !== 'screen' || !this.data.fogScreenReady) return;
    this._fogStartPending = null;
    this._fogDirty = false;
    if (this._fogTimer) { clearTimeout(this._fogTimer); this._fogTimer = null; }
    this._seenSweepHistory(); // 历史点亮区的商家开局即带星
    this._syncSparkCircles();
    this._syncMarkers();
    this._startClock();
    if (pending.startTracking && pending.locationRevision === bgTracker.revision('roam')) this._startReal(true);
  },

  /** 底图首帧看门狗:5s 内没等到 map updated → 显示重试提示,黑屏不再无解释。
   *  局限:只能测"地图从未渲染"(418/断网类);瓦片缺失但组件已渲染测不到 */
  _mapLoadWatch() {
    if (this._mapWatchTimer) clearTimeout(this._mapWatchTimer);
    this._mapWatchTimer = setTimeout(() => {
      this._mapWatchTimer = null;
      if (!this._mapUpdatedOnce) this.setData({ mapLoad: 'slow' });
    }, 5000);
  },
  onMapUpdated() {
    this._mapUpdatedOnce = true;
    if (this._mapWatchTimer) { clearTimeout(this._mapWatchTimer); this._mapWatchTimer = null; }
    if (this.data.mapLoad !== 'ok') this.setData({ mapLoad: 'ok' });
    // 若 feature flags 比首屏慢到，地图后续刷新仍可补一次；展示成功后 prepared 会封顶为一次。
    this._tryShowFootprintHint();
  },
  onMapRetry() {
    const s = this.data.mapScale; // 轻抖 scale 触发瓦片重拉,再走一轮看门狗
    this.setData({ mapLoad: 'pending', mapScale: s > 3 ? s - 1 : s + 1 });
    setTimeout(() => this.setData({ mapScale: s }), 350);
    this._mapLoadWatch();
  },

  // 离线条的重试(§7.4:给恢复动作)。没有中心点就无从拉起,静默返回而不是假装重试。
  // 两个图层都重拉:横幅现在是 poiOffline || merchantOffline 合起来的,只重拉一个会留下另一半永远卡着。
  onPoiRetry() {
    if (!this._c) return;
    this._fetchRoamPois(this._c);
    if (this.data.merchantOffline) this._fetchNearbyMerchants(this._c);
  },

  /** 视距(中心到边缘 m) → 微信 map scale */
  _halfViewM(scale) {
    if (scale >= 20) return 35;
    if (scale >= 19) return 70;
    if (scale >= 18) return 140;
    if (scale >= 17) return 280;
    if (scale >= 16) return 560;
    if (scale >= 15) return 1100;
    if (scale >= 14) return 2200;
    if (scale >= 13) return 4400;
    if (scale >= 12) return 8800;
    return 12000;
  },

  _scaleForHalfViewM(halfM) {
    if (halfM <= 35) return 20;
    if (halfM <= 70) return 19;
    if (halfM <= 140) return 18;
    if (halfM <= 280) return 17;
    if (halfM <= 560) return 16;
    if (halfM <= 1100) return 15;
    if (halfM <= 2200) return 14;
    if (halfM <= 4400) return 13;
    if (halfM <= 8800) return 12;
    return SCALE_FAR;
  },

  _playerCenter() {
    const p = this._player;
    return p ? { lat: p.lat, lng: p.lng } : this.data.center;
  },

  _animateZoom(from, to, duration, center) {
    if (this._zoomAnim) clearInterval(this._zoomAnim);
    this._zoomAnim = null;
    const p = center || this._playerCenter();
    if (!p) {
      this._zoomTarget = null;
      return;
    }
    this._zoomTarget = { scale: to, center: { lat: p.lat, lng: p.lng } };
    if (this.data.reducedMotion) {
      this._zoomLock = false;
      this.setData({ mapScale: to, center: this._zoomTarget.center });
      this._zoomTarget = null;
      return;
    }
    this._zoomLock = true; // 动画期间地图跟随镜头让路,避免两边抢 setData 造成画面抽动
    // ★ 缩放动画有 12 步,每步都改 mapScale ⇒ 每步派发一次 regionchange end。若照常按
    //   scaleChanged 拉黑,而重绘又被 160ms 去抖一次次重置,整个动画期间就是纯黑
    //   (回中 550ms / 暂停概览 650ms,实测体感约 1s 全黑)。onRegionChange 因此在 _zoomLock
    //   期间整段跳过,这里按缩放方向决定要不要遮一次:
    //   · 拉近(to > from):屏幕像素对应的米数变少 ⇒ 旧帧的洞覆盖的地理范围**变小**,
    //     露出的比该露的少,偏保守、不泄露 ⇒ 不遮,旧帧顶到新帧画好,零黑闪。
    //   · 拉远(to < from):同样像素对应更多米 ⇒ 洞被放大成一片本不该露的区域,会把没探过的
    //     地名成片亮出来 ⇒ 必须遮,这一次黑是有代价换来的,不能省。
    if (this._fogMode === 'screen' && to < from) this._guardScreenViewportChange();
    const steps = 12;
    const stepMs = Math.max(40, Math.floor(duration / steps));
    let i = 0;
    this._zoomAnim = setInterval(() => {
      i++;
      const t = i / steps;
      const ease = 1 - Math.pow(1 - t, 3);
      const scale = Math.round(from + (to - from) * ease);
      this.setData({ mapScale: scale, center: { lat: p.lat, lng: p.lng } });
      if (i >= steps) {
        clearInterval(this._zoomAnim);
        this._zoomAnim = null;
        this._zoomLock = false;
        this.setData({ mapScale: to, center: { lat: p.lat, lng: p.lng } });
        this._zoomTarget = null;
        // 动画期间 onRegionChange 整段跳过了,收尾必须自己补一次重绘 —— 否则雾停在起始那一帧,
        // 拉近时看着"洞变小了"、拉远时干脆一直黑着没人撤。
        if (this._fogMode === 'screen') this._scheduleScreenFogRender();
      }
    }, stepMs);
  },

  _setPlayerZoom(scale, animate) {
    const p = this._playerCenter();
    if (!p) return;
    if (animate) {
      this._animateZoom(this.data.mapScale, scale, 550, p);
    } else {
      this.setData({ mapScale: scale, center: p });
    }
  },

  /* ============ 迷雾 · 屏幕遮罩(主路径) / map overlay 与 polygon(降级) ============ */

  _deleteScreenFogFile(src) {
    if (!src || !wx.getFileSystemManager) return;
    try { wx.getFileSystemManager().unlinkSync(src); } catch (e) { /* 已删 / 不支持时无需处理 */ }
  },

  _disposeFogRenderer() {
    this._fogLifecycleGen = (this._fogLifecycleGen || 0) + 1;
    this._fogScreenRegionSeq = (this._fogScreenRegionSeq || 0) + 1;
    ['_fogOpenTimer', '_fogScreenSetupTimer', '_fogScreenRegionTimer', '_fogScreenRenderTimer', '_fogScreenGuardWatchdog', '_fogTimer', '_fogFlushTimer'].forEach((key) => {
      if (this[key]) clearTimeout(this[key]);
      this[key] = null;
    });
    if (this._fogScreenSetupResolve) {
      const resolve = this._fogScreenSetupResolve;
      this._fogScreenSetupResolve = null;
      resolve(false);
    }
    (this.data.fogScreenLayers || []).forEach((layer) => this._deleteScreenFogFile(layer.src));
    if (this._fogOverlayAdded && this._mapCtx && typeof this._mapCtx.removeGroundOverlay === 'function') {
      try { this._mapCtx.removeGroundOverlay({ id: FOG_OVERLAY_ID }); } catch (e) { /* 组件可能已卸载 */ }
    }
    this._fogMode = null;
    this._fogScreenCv = null;
    this._fogScreenG = null;
    this._fogScreenSize = null;
    this._fogScreenVersion = (this._fogScreenVersion || 0) + 1;
    this._fogCv = null;
    this._fogG = null;
    this._fog = null;
    this._fogOverlayAdded = false;
    this._fogExporting = false;
    this._fogDirty = false;
    this._fogFlushDirty = false;
    this._fogStartPending = null;
    this._mapCtx = null;
  },

  /**
   * 主路径把雾画在原生 map 上方。map 内部的 polygon / groundOverlay 会被腾讯底图文字再次压住，
   * 所以即使不透明也会出现“黑底上浮着密密麻麻文字”；屏幕 canvas 才能真正遮住原生文字。
   * 离屏 canvas 生成遮罩 PNG，普通 image 上屏且不吃点击；底下的原生 POI / bindpoitap 仍保留。
   * canvas 节点挂载有短暂时序差；有限重试后仍不可用就全黑并明示重试，不能回到浮字路径。
   */
  _setupFog(generation) {
    const expected = generation == null ? this._fogLifecycleGen : generation;
    return new Promise((resolve) => {
      this._fogScreenSetupResolve = resolve;
      this._trySetupScreenFog(0, expected);
    });
  },

  _finishFogSetup(ready, generation) {
    if (generation !== this._fogLifecycleGen || !this._fogScreenSetupResolve) return;
    const resolve = this._fogScreenSetupResolve;
    this._fogScreenSetupResolve = null;
    resolve(ready);
  },

  _trySetupScreenFog(attempt, generation) {
    if (generation !== this._fogLifecycleGen) { this._finishFogSetup(false, generation); return; }
    wx.createSelectorQuery().select('#fogscreen').fields({ node: true, size: true }).exec((r) => {
      if (generation !== this._fogLifecycleGen) { this._finishFogSetup(false, generation); return; }
      const item = r && r[0];
      const node = item && item.node;
      const width = Number(item && item.width);
      const height = Number(item && item.height);
      if (node && width > 0 && height > 0) {
        try {
          // 2026-10-29 V-07:雾是羽化遮罩,不需要视网膜清晰度。按 1 倍像素画,
          // 每帧 PNG 编码+写盘的像素量降到原来的 1/4,松手后的重绘不再卡一下。
          const dpr = FOG_SCREEN_DPR;
          node.width = Math.round(width * dpr);
          node.height = Math.round(height * dpr);
          const g = node.getContext('2d');
          g.scale(dpr, dpr);
          this._fogMode = 'screen';
          this._fogScreenCv = node;
          this._fogScreenG = g;
          this._fogScreenSize = { width, height };
          if (this._fogOverlayAdded && this._mapCtx && typeof this._mapCtx.removeGroundOverlay === 'function') {
            try { this._mapCtx.removeGroundOverlay({ id: FOG_OVERLAY_ID }); } catch (e) { /* 降级层可能已卸载 */ }
          }
          this._fog = null;
          this._fogCv = null;
          this._fogG = null;
          this._fogOverlayAdded = false;
          this.setData({ fogPolys: [], fogScreenReady: false, fogScreenLayers: [], fogScreenGuard: true });
          this._finishFogSetup(true, generation);
          return;
        } catch (e) {
          this._fogScreenErr = 'init:' + (e && e.message);
        }
      } else {
        this._fogScreenErr = 'node:not-ready:' + attempt;
      }
      if (attempt < 3) {
        this._fogScreenSetupTimer = setTimeout(() => {
          this._fogScreenSetupTimer = null;
          this._trySetupScreenFog(attempt + 1, generation);
        }, 80);
        return;
      }
      this._fogMode = 'blocked';
      this.setData({ fogPolys: [], fogScreenLayers: [], fogScreenReady: false, fogScreenGuard: true });
      this._finishFogSetup(false, generation);
    });
  },

  _drawScreenFog(region) {
    const g = this._fogScreenG;
    const size = this._fogScreenSize;
    if (!g || !size) return;
    const frameStartedAt = Date.now();
    this._fogScreenLastDrawAt = frameStartedAt;
    const reveals = (this._historyReveals || []).concat(this._reveals || []);
    const circles = projectRevealCircles(region, size, reveals, REVEAL_M, FOG_FEATHER);
    this._fogScreenCircleCount = circles.length;
    this._fogScreenAnchor = circles[0] || null;
    this._fogScreenLastRegion = region;
    g.save();
    g.globalCompositeOperation = 'source-over';
    g.clearRect(0, 0, size.width, size.height);
    g.fillStyle = 'rgba(6,6,6,' + FOG_ALPHA + ')';
    g.fillRect(0, 0, size.width, size.height);
    g.globalCompositeOperation = 'destination-out';
    // 羽化擦除:单位空间的径向渐变笔刷,核心全擦、外缘渐隐。dstOut 下 alpha 即擦除比例,
    // 重叠圆的羽化带按 (1-a) 连乘自然融合,轨迹走廊不会出现硬接缝。
    const brush = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    brush.addColorStop(0, 'rgba(0,0,0,1)');
    brush.addColorStop(1 / FOG_FEATHER, 'rgba(0,0,0,1)');
    brush.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = brush;
    circles.forEach((circle) => {
      g.save();
      g.translate(circle.x, circle.y);
      g.scale(circle.radiusX * FOG_FEATHER, circle.radiusY * FOG_FEATHER);
      g.beginPath();
      g.arc(0, 0, 1, 0, Math.PI * 2);
      g.fill();
      g.restore();
    });
    g.restore();
    try {
      const dataUrl = this._fogScreenCv.toDataURL('image/png');
      const b64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
      this._fogScreenBytes = Math.ceil(b64.length * 3 / 4);
      const version = (this._fogScreenVersion || 0) + 1;
      const path = wx.env.USER_DATA_PATH + '/roam_screen_fog_' + version + '.png';
      wx.getFileSystemManager().writeFileSync(path, b64, 'base64');
      this._fogScreenPublishMs = Date.now() - frameStartedAt;
      const oldLayers = (this.data.fogScreenLayers || []).slice();
      const previous = (this.data.fogScreenLayers || []).filter((layer) => layer.loaded).slice(-1);
      const generation = this._fogLifecycleGen;
      this._fogScreenVersion = version;
      this._fogScreenDraws = (this._fogScreenDraws || 0) + 1;
      this.setData({
        fogScreenLayers: previous.concat([{ generation, version, src: path, loaded: false, frameStartedAt }]),
        fogScreenReady: previous.length > 0,
      }, () => {
        if (generation !== this._fogLifecycleGen) { this._deleteScreenFogFile(path); return; }
        const kept = new Set((this.data.fogScreenLayers || []).map((layer) => layer.src));
        oldLayers.forEach((layer) => { if (!kept.has(layer.src)) this._deleteScreenFogFile(layer.src); });
      });
    } catch (e) {
      this._fogScreenErr = 'publish:' + (e && e.message);
      this.setData({ fogScreenGuard: true });
    }
  },

  onFogScreenLoad(e) {
    const generation = Number(e && e.currentTarget && e.currentTarget.dataset.generation);
    if (generation !== this._fogLifecycleGen) return;
    const version = Number(e && e.currentTarget && e.currentTarget.dataset.version);
    const oldLayers = (this.data.fogScreenLayers || []).slice();
    const layers = oldLayers.map((layer) => Number(layer.version) === version ? { ...layer, loaded: true } : layer);
    const found = layers.find((layer) => Number(layer.version) === version);
    if (!found) return;
    if (version === this._fogScreenVersion) {
      this._fogScreenLoadMs = Math.max(0, Date.now() - Number(found.frameStartedAt || Date.now()));
      this.setData({ fogScreenLayers: [found], fogScreenReady: true, fogScreenGuard: false }, () => {
        oldLayers.forEach((layer) => { if (layer.src !== found.src) this._deleteScreenFogFile(layer.src); });
        this._completeRoamAfterFogLoad(generation);
      });
      return;
    }
    this.setData({ fogScreenLayers: layers.slice(-2), fogScreenReady: layers.some((layer) => layer.loaded) });
  },

  onFogScreenError(e) {
    const generation = Number(e && e.currentTarget && e.currentTarget.dataset.generation);
    if (generation !== this._fogLifecycleGen) return;
    const version = Number(e && e.currentTarget && e.currentTarget.dataset.version);
    const failed = (this.data.fogScreenLayers || []).find((layer) => Number(layer.version) === version);
    if (version !== this._fogScreenVersion) {
      const layers = (this.data.fogScreenLayers || []).filter((layer) => Number(layer.version) !== version);
      this.setData({ fogScreenLayers: layers, fogScreenReady: layers.some((layer) => layer.loaded) }, () => {
        if (failed) this._deleteScreenFogFile(failed.src);
      });
      return;
    }
    this._fogScreenErr = 'image:' + ((e && e.detail && e.detail.errMsg) || 'load-failed');
    this.setData({ fogScreenGuard: true }, () => {
      if (failed) this._deleteScreenFogFile(failed.src);
    });
  },

  retryScreenFog() {
    if (this.data.screen !== 'map' && this.data.screen !== 'arrive') return;
    if (this._fogScreenSetupResolve) return;
    this.setData({ fogScreenGuard: true });
    if (this._fogMode === 'screen' && this._fogScreenG) {
      this._renderScreenFog();
      return;
    }
    const generation = this._fogLifecycleGen;
    this._fogStartPending = this._fogStartPending || {
      p: this._playerCenter() || this.data.center,
      startTracking: false,
      generation,
    };
    this._setupFog(generation).then((ready) => {
      if (!ready || generation !== this._fogLifecycleGen || this._fogMode !== 'screen') return;
      const pending = this._fogStartPending;
      if (pending && pending.generation === generation) {
        this._activateRoamAfterFog(pending.p, pending.startTracking, generation);
      } else {
        this._renderScreenFog();
      }
    });
  },

  _renderScreenFog() {
    if (this._fogMode !== 'screen' || !this._fogScreenG) return;
    const generation = this._fogLifecycleGen;
    const size = this._fogScreenSize;
    const fallback = fallbackRegion(this.data.center, this._halfViewM(this.data.mapScale), size);
    const seq = (this._fogScreenRegionSeq || 0) + 1;
    this._fogScreenRegionSeq = seq;
    let settled = false;
    const draw = (region) => {
      if (settled || generation !== this._fogLifecycleGen || this._fogMode !== 'screen'
        || seq !== this._fogScreenRegionSeq) return;
      settled = true;
      if (this._fogScreenRegionTimer) {
        clearTimeout(this._fogScreenRegionTimer);
        this._fogScreenRegionTimer = null;
      }
      this._drawScreenFog(region || fallback);
    };
    this._fogScreenRegionTimer = setTimeout(() => draw(fallback), 500);
    try {
      if (!this._mapCtx || typeof this._mapCtx.getRegion !== 'function') { draw(fallback); return; }
      this._mapCtx.getRegion({ success: draw, fail: () => draw(fallback) });
    } catch (e) { draw(fallback); }
  },

  _scheduleScreenFogRender(delay) {
    if (this._fogMode !== 'screen') return;
    // 2026-10-29 V-07:走路时定位(500ms)和揭示各自都会排一次重绘。原来每次都重置去抖,
    // 全屏 PNG 编码+写盘几乎不停 = 卡顿。改为合并:已有在排的就不再排,两帧至少隔
    // FOG_SCREEN_MIN_MS;只有显式要求立即重画(手势松手 delay=0)才插队。
    if (this._fogScreenRenderTimer) {
      if (delay !== 0) return;
      clearTimeout(this._fogScreenRenderTimer);
    }
    const since = Date.now() - (this._fogScreenLastDrawAt || 0);
    const wait = delay == null ? Math.max(160, FOG_SCREEN_MIN_MS - since) : delay;
    this._fogScreenRenderTimer = setTimeout(() => {
      this._fogScreenRenderTimer = null;
      this._renderScreenFog();
    }, wait);
  },

  /** 取消在途的重绘(旧视口的 region 已经不对了),但不拉黑 —— 旧帧继续留在屏上。 */
  _cancelScreenFogRender() {
    this._fogScreenRegionSeq = (this._fogScreenRegionSeq || 0) + 1;
    if (this._fogScreenRegionTimer) { clearTimeout(this._fogScreenRegionTimer); this._fogScreenRegionTimer = null; }
    if (this._fogScreenRenderTimer) { clearTimeout(this._fogScreenRenderTimer); this._fogScreenRenderTimer = null; }
  },

  _guardScreenViewportChange() {
    if (this._fogMode !== 'screen') return;
    this._fogScreenVersion = (this._fogScreenVersion || 0) + 1;
    this._fogScreenRegionSeq = (this._fogScreenRegionSeq || 0) + 1;
    if (this._fogScreenRegionTimer) { clearTimeout(this._fogScreenRegionTimer); this._fogScreenRegionTimer = null; }
    if (this._fogScreenRenderTimer) { clearTimeout(this._fogScreenRenderTimer); this._fogScreenRenderTimer = null; }
    if (!this.data.fogScreenGuard) this.setData({ fogScreenGuard: true });
    // ★ 本方法自己不安排任何重绘 —— 重绘只挂在 regionchange 的 end 分支上。所以「begin 来了、
    //   end 丢了」(手势被来电/切后台打断、动画被 dispose 截断)就会留下:全屏黑 + fogScreenError
    //   仍是 false ⇒ 连「点击重试」入口都不显示 ⇒ 除非离开本页触发 onShow,否则永远黑着。
    //   补一个看门狗把「必须离开本页才能自愈」降级成就地自愈。
    if (this._fogScreenGuardWatchdog) clearTimeout(this._fogScreenGuardWatchdog);
    this._fogScreenGuardWatchdog = setTimeout(() => {
      this._fogScreenGuardWatchdog = null;
      if (this._fogMode !== 'screen' || !this.data.fogScreenGuard) return;
      if (this.data.screen !== 'map' && this.data.screen !== 'arrive') return;
      this._renderScreenFog();
    }, 1500);
  },

  /** 降级到 poly 网格雾。★不要改回「什么都不画」——那等于迷雾静默消失,
   *  开发者工具与 overlay 失败的设备会看到一张裸地图,玩法核心不见了还零报错。 */
  _fogToPoly() {
    if (this._fogFlushTimer) { clearTimeout(this._fogFlushTimer); this._fogFlushTimer = null; }
    this._fogMode = 'poly';
    this._initFogGrid();
    this._applyHistoryFog();
    (this._reveals || []).forEach((p) => this._fogApplyReveal(p));
    this._updateFogNow();
  },

  /** 回放历史探索区(格子中心 + 本地轨迹),开局即亮 */
  _applyHistoryFog() {
    (this._historyReveals || []).forEach((p) => {
      if (this._fogMode === 'overlay') this._stampReveal(p, 1);
      else this._fogApplyReveal(p);
    });
  },

  _paintFogBase() {
    const g = this._fogG;
    if (!g) return;
    g.globalCompositeOperation = 'source-over';
    g.clearRect(0, 0, FOG_PX, FOG_PX);
    g.fillStyle = 'rgba(6,6,6,' + FOG_ALPHA + ')';
    g.fillRect(0, 0, FOG_PX, FOG_PX);
  },

  _fogPt(p) {
    const c = this._c;
    const x = (p.lng - c.lng) / m2lng(1, c.lat);
    const y = (p.lat - c.lat) / m2lat(1);
    return {
      px: (x + FOG_HALF_M) / (2 * FOG_HALF_M) * FOG_PX,
      py: (FOG_HALF_M - y) / (2 * FOG_HALF_M) * FOG_PX,
    };
  },

  _stampReveal(p, strength) {
    const g = this._fogG;
    if (!g) return;
    const pt = this._fogPt(p);
    // 逐点半径与 screen 路径同口径：历史格 r=110 才能连片。写死 REVEAL_M 的话，
    // overlay 降级下重开仍是一片互不相接的 55m 圆点 —— 正是本批要根治的症状。
    const rM = Number(p && p.r) > 0 ? Number(p.r) : REVEAL_M;
    const rPx = rM / (2 * FOG_HALF_M) * FOG_PX;
    if (pt.px < -rPx || pt.py < -rPx || pt.px > FOG_PX + rPx || pt.py > FOG_PX + rPx) return;
    const a = strength == null ? 1 : strength;
    g.globalCompositeOperation = 'destination-out';
    g.fillStyle = 'rgba(0,0,0,' + a + ')';
    g.beginPath(); g.arc(pt.px, pt.py, rPx, 0, Math.PI * 2); g.fill();
  },

  _scheduleFogFlush() {
    this._fogFlushDirty = true;
    if (this._fogFlushTimer) return;
    this._fogFlushTimer = setTimeout(() => {
      this._fogFlushTimer = null;
      if (this._fogFlushDirty) { this._fogFlushDirty = false; this._flushFogOverlay(); }
    }, FOG_FLUSH_MS);
  },

  _flushFogOverlay() {
    if (this._fogMode !== 'overlay' || !this._fogCv || this._fogExporting) return;
    this._fogExporting = true;
    let path;
    try {
      const dataUrl = this._fogCv.toDataURL('image/png');
      const b64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
      this._fogFileFlip = 1 - (this._fogFileFlip || 0);
      path = wx.env.USER_DATA_PATH + '/roam_fog_' + this._fogFileFlip + '.png';
      wx.getFileSystemManager().writeFileSync(path, b64, 'base64');
    } catch (e) {
      this._fogExporting = false;
      this._fogErr = 'export:' + (e && e.message);
      this._fogFallbackToPoly();
      return;
    }
    const c = this._c;
    const args = {
      id: FOG_OVERLAY_ID,
      src: path,
      bounds: {
        southwest: { latitude: c.lat - m2lat(FOG_HALF_M), longitude: c.lng - m2lng(FOG_HALF_M, c.lat) },
        northeast: { latitude: c.lat + m2lat(FOG_HALF_M), longitude: c.lng + m2lng(FOG_HALF_M, c.lat) },
      },
      opacity: 1,
      zIndex: 1,
    };
    const done = () => { this._fogExporting = false; this._fogFlushCount = (this._fogFlushCount || 0) + 1; };
    if (!this._fogOverlayAdded) {
      const watchdog = setTimeout(() => {
        if (this._fogOverlayAdded || this._fogMode !== 'overlay') return;
        this._fogExporting = false;
        this._fogFallbackToPoly();
      }, 5000);
      args.success = () => {
        clearTimeout(watchdog);
        if (this._fogMode !== 'overlay') return;
        this._fogOverlayAdded = true;
        done();
      };
      args.fail = () => {
        clearTimeout(watchdog);
        this._fogExporting = false;
        this._fogFallbackToPoly();
      };
      this._mapCtx.addGroundOverlay(args);
    } else {
      args.complete = done;
      this._mapCtx.updateGroundOverlay(args);
    }
  },

  /** overlay 路径挂了(导出失败 / addGroundOverlay 失败或超时)→ 退到 poly 网格,而不是无雾 */
  _fogFallbackToPoly() {
    if (this._fogMode === 'poly') return;
    this._fogToPoly();
  },

  _edgeSlabs() {
    const c = this._c;
    const B = 0.2;
    const swLat = c.lat - m2lat(FOG_HALF_M); const neLat = c.lat + m2lat(FOG_HALF_M);
    const swLng = c.lng - m2lng(FOG_HALF_M, c.lat); const neLng = c.lng + m2lng(FOG_HALF_M, c.lat);
    const slab = (a, b, d, e) => ({
      points: [
        { latitude: a, longitude: d }, { latitude: a, longitude: e },
        { latitude: b, longitude: e }, { latitude: b, longitude: d }],
      fillColor: '#060606B3', strokeColor: '#00000000', strokeWidth: 0, zIndex: 1,
    });
    return [
      slab(neLat, neLat + B, swLng - B, neLng + B),
      slab(swLat - B, swLat, swLng - B, neLng + B),
      slab(swLat, neLat, swLng - B, swLng),
      slab(swLat, neLat, neLng, neLng + B),
    ];
  },

  /* ============ 迷雾 · poly 路径(开发者工具 + 降级) ============ */

  _initFogGrid() {
    // HALF 需覆盖 SCALE_FAR 对应的最大可视半径(_halfViewM),否则远景会看到雾区边界的硬线
    // 2m 网格把硬边圆的完整阶梯轮廓误差压到 1.4m 内;矩形仍按连续行合并。
    const HALF = FOG_HALF_M, CELL = FOG_GRID_M;
    const N = Math.ceil(HALF * 2 / CELL);
    const st = new Uint8Array(N * N);
    st.fill(FOG_MAX); // 未探索区域默认最浓,而不是 Uint8Array 默认的 0(那是"完全清除")
    this._fog = { HALF, CELL, N, st };
  },

  /** lvl: 0 完全清除(不画) → 1 全雾;硬边不做透明度过渡。 */
  _fogBandColor(lvl) {
    if (lvl <= 0) return null;
    return '#060606B3'; // 约 70%,与 FOG_ALPHA 同档,三条路径观感必须一致
  },

  _scheduleFogUpdate() {
    if (!this._fog) return;
    this._fogDirty = true;
    if (this._fogTimer) return;
    this._fogTimer = setTimeout(() => {
      this._fogTimer = null;
      if (this._fogDirty) {
        this._fogDirty = false;
        this._updateFogNow();
      }
    }, FOG_UPDATE_MS);
  },

  _updateFogNow(clipping, renderHalfM) {
    const F = this._fog;
    if (!F) return;
    if (!clipping) this._fogUpdates = (this._fogUpdates || 0) + 1; // 全量替换次数(闪白事件代理指标,_dbg 可读)
    const c = this._c;
    const focus = this._player || this._reveals[this._reveals.length - 1] || c;
    const focusX = (focus.lng - c.lng) / m2lng(1, c.lat);
    const focusY = (focus.lat - c.lat) / m2lat(1);
    const renderHalf = Math.min(F.HALF, renderHalfM || F.HALF);
    const clampI = (i) => Math.max(0, Math.min(F.N - 1, i));
    const iStart = clampI(Math.floor((focusX - renderHalf + F.HALF) / F.CELL));
    const iEnd = clampI(Math.floor((focusX + renderHalf + F.HALF) / F.CELL));
    const jStart = clampI(Math.floor((focusY - renderHalf + F.HALF) / F.CELL));
    const jEnd = clampI(Math.floor((focusY + renderHalf + F.HALF) / F.CELL));
    const lngAt = (i) => c.lng + m2lng(i * F.CELL - F.HALF, c.lat);
    const latAt = (j) => c.lat + m2lat(j * F.CELL - F.HALF);
    // 不再外扩重叠:半透明矩形一旦重叠,透明度叠加会形成明显的深色横线,
    // 比浮点缝隙的细白线更刺眼;相邻矩形直接共享同一条边界坐标
    const OV_M = 0;
    const ovLat = m2lat(OV_M);
    const ovLng = m2lng(OV_M, c.lat);
    const rect = (i0, i1, j0, j1, fill) => ({
      points: [
        { latitude: latAt(j0) - ovLat, longitude: lngAt(i0) - ovLng },
        { latitude: latAt(j0) - ovLat, longitude: lngAt(i1 + 1) + ovLng },
        { latitude: latAt(j1 + 1) + ovLat, longitude: lngAt(i1 + 1) + ovLng },
        { latitude: latAt(j1 + 1) + ovLat, longitude: lngAt(i0) - ovLng },
      ],
      fillColor: fill, strokeColor: '#00000000', strokeWidth: 0, zIndex: 1,
    });
    const rowRuns = [];
    for (let j = jStart; j <= jEnd; j++) {
      const runs = [];
      let i = iStart;
      while (i <= iEnd) {
        const s = F.st[j * F.N + i];
        if (s === 0) { i++; continue; }
        let e = i;
        while (e + 1 <= iEnd && F.st[j * F.N + e + 1] === s) e++;
        runs.push([i, e, s]);
        i = e + 1;
      }
      rowRuns.push(runs);
    }
    const polys = [];
    let j = 0;
    while (j < rowRuns.length) {
      const sig = JSON.stringify(rowRuns[j]);
      let je = j;
      while (je + 1 < rowRuns.length && JSON.stringify(rowRuns[je + 1]) === sig) je++;
      for (const [i0, i1, s] of rowRuns[j]) {
        const fill = this._fogBandColor(s);
        if (fill) polys.push(rect(i0, i1, jStart + j, jStart + je, fill));
      }
      j = je + 1;
    }
    const B = 0.2;
    const worldSwLat = latAt(0); const worldNeLat = latAt(F.N);
    const worldSwLng = lngAt(0); const worldNeLng = lngAt(F.N);
    const swLat = latAt(jStart); const neLat = latAt(jEnd + 1);
    const swLng = lngAt(iStart); const neLng = lngAt(iEnd + 1);
    const slab = (a, b, d, e) => ({
      points: [
        { latitude: a, longitude: d }, { latitude: a, longitude: e },
        { latitude: b, longitude: e }, { latitude: b, longitude: d }],
      fillColor: '#060606B3', strokeColor: '#00000000', strokeWidth: 0, zIndex: 1,
    });
    polys.push(slab(neLat - ovLat, worldNeLat + B, worldSwLng - B, worldNeLng + B));
    polys.push(slab(worldSwLat - B, swLat + ovLat, worldSwLng - B, worldNeLng + B));
    polys.push(slab(swLat - ovLat, neLat + ovLat, worldSwLng - B, swLng + ovLng));
    polys.push(slab(swLat - ovLat, neLat + ovLat, neLng - ovLng, worldNeLng + B));
    if (polys.length > FOG_POLY_LIMIT) {
      if (renderHalf > FOG_RENDER_MIN_HALF_M) {
        const nextHalf = Math.max(FOG_RENDER_MIN_HALF_M, Math.floor(renderHalf / 2));
        this._updateFogNow(true, nextHalf);
      } else {
        this._fogRenderClipped = true;
        this.setData({ fogPolys: [slab(
          worldSwLat - B, worldNeLat + B, worldSwLng - B, worldNeLng + B
        )] });
      }
      return;
    }
    this._fogRenderClipped = renderHalf < F.HALF;
    this.setData({ fogPolys: polys });
  },

  _syncSparkCircles() {
    const sparks = (this._pois || []).filter((p) => p.state === 'fog' && p.cat === 'merchant').map((p) => ({
      latitude: p.lat, longitude: p.lng, radius: 9,
      fillColor: '#FFFFFFB8', color: '#FFFFFF30', strokeWidth: 1,
    }));
    const eventOverlay = this.data.eventOverlay || {};
    const activityPoints = eventOverlay.show ? (eventOverlay.points || []).map((point) => ({
      latitude: point.latitude, longitude: point.longitude, radius: Math.max(12, point.radiusM || 120),
      fillColor: '#0000001A', color: '#00000088', strokeWidth: 2,
    })) : [];
    const footprint = this.data.footprintHint && this.data.footprintHint.show && this._footprintHintPoint ? [{
      latitude: this._footprintHintPoint.lat, longitude: this._footprintHintPoint.lng, radius: 72,
      fillColor: '#FFFFFF1F', color: '#FFFFFFB8', strokeWidth: 1,
    }] : [];
    this._paintSparkCircles(sparks.concat(activityPoints, footprint));
  },

  /**
   * ADA:新出现的据点光斑做 200ms 半径入场(与 --cy-motion-fast 同档)。
   * 原生 <map> 的 circle 不吃 CSS 动画,唯一能动的是逐帧改 radius —— 故只改半径,
   * 不碰 latitude/longitude、不碰 POI 状态、不触发任何上报。
   * 已出现过的光斑(this._sparkSeen)直接按终值画,避免每次 sync 都重播。
   */
  _paintSparkCircles(list) {
    const keyOf = (c) => c.latitude + ',' + c.longitude;
    const seen = this._sparkSeen || (this._sparkSeen = {});
    const fresh = {};
    let hasFresh = false;
    list.forEach((c) => {
      const k = keyOf(c);
      if (!seen[k]) { fresh[k] = c.radius; hasFresh = true; }
      seen[k] = 1;
    });
    if (this._sparkInTimer) { clearTimeout(this._sparkInTimer); this._sparkInTimer = null; }
    if (!hasFresh || this.data.reducedMotion) {
      this.setData({ sparkCircles: list });
      return;
    }
    const at = (scale) => list.map((c) => {
      const full = fresh[keyOf(c)];
      return full == null ? c : Object.assign({}, c, { radius: Math.max(1, Math.round(full * scale)) });
    });
    this.setData({ sparkCircles: at(0.3) });
    this._sparkInTimer = setTimeout(() => {
      this.setData({ sparkCircles: at(0.7) });
      this._sparkInTimer = setTimeout(() => {
        this._sparkInTimer = null;
        this.setData({ sparkCircles: list });
      }, 100);
    }, 100);
  },

  /** @returns {boolean} 是否有格子真的变清(false=区域早已探过,无需重建 poly) */
  _fogApplyReveal(p) {
    const F = this._fog; if (!F) return false;
    const c = this._c;
    const x = (p.lng - c.lng) / m2lng(1, c.lat);
    const y = (p.lat - c.lat) / m2lat(1);
    const R = Number(p && p.r) > 0 ? Number(p.r) : REVEAL_M;   // 同上：poly 降级也认逐点半径
    const R2 = R + F.CELL;
    const lo = (v) => Math.max(0, Math.floor((v + F.HALF) / F.CELL));
    const hi = (v) => Math.min(F.N - 1, Math.floor((v + F.HALF) / F.CELL));
    let changed = false;
    for (let i = lo(x - R2); i <= hi(x + R2); i++) {
      for (let j = lo(y - R2); j <= hi(y + R2); j++) {
        const cx = (i + 0.5) * F.CELL - F.HALF;
        const cy = (j + 0.5) * F.CELL - F.HALF;
        const d = Math.hypot(cx - x, cy - y);
        const nl = fogLevel(d, R);
        const k = j * F.N + i;
        if (nl < F.st[k]) { F.st[k] = nl; changed = true; } // 只会变得更清,不会重新变浓
      }
    }
    return changed;
  },

  _flushTrackLine() {
    if (!this._track || !this._track.length) return;
    this.setData({
      polyline: [{
        points: this._track.map((q) => ({ latitude: q.lat, longitude: q.lng })),
        color: '#FFFFFF',
        width: 3,
        borderColor: '#FFFFFF66',
        borderWidth: 1,
      }],
    });
  },

  _enterWalkZoom() {
    this._hold = 0;
    this._setPlayerZoom(SCALE_WALK, true);
  },

  _fitRouteOverview() {
    this._hold = Date.now() + 86400000;
    const p = this._player;
    if (!p) return;
    const track = this._track || [];
    let target = SCALE_PAUSE_MIN;
    if (track.length >= 1) {
      let maxD = 60;
      for (const q of track) maxD = Math.max(maxD, distM(p, q));
      const halfNeed = maxD * OVERVIEW_PAD + 80;
      target = this._scaleForHalfViewM(halfNeed);
      target = Math.min(target, SCALE_WALK - 2);
    }
    target = Math.max(SCALE_FAR, Math.min(target, SCALE_WALK - 2));
    this._animateZoom(this.data.mapScale, target, 650, p);
  },

  onRegionChange(e) {
    if (this.data.screen === 'entry-map' && this._entryShownOnce) { this._onEntryRegionChange(e); return; } const detail = (e && e.detail) || {};
    const phase = detail.phase || detail.type || (e && e.type);
    const causedBy = detail.causedBy || (e && e.causedBy);
    const byGesture = causedBy === 'gesture' || causedBy === 'drag' || causedBy === 'scale';
    // ★ 程序自己的缩放动画(_animateZoom)期间整段让路:它 12 步逐帧改 mapScale,每步都会走到
    //   这里、每步 scaleChanged 都为真,照常处理就是「全程黑 + 重绘去抖被反复重置」。
    //   遮不遮由 _animateZoom 按缩放方向判一次,收尾由它补一次重绘,这里不必掺和。
    if (this._zoomLock && this._fogMode === 'screen') {
      // 但 _mapScale 必须照常跟上:漏了它,动画收尾后的第一次 end 会拿旧 scale 比出
      // scaleChanged=true,把刚刚省下来的那次全黑原样加回去。
      if (detail.scale != null) this._mapScale = detail.scale;
      return;
    }
    if (phase === 'begin') {
      // 2026-10-29 V-07(业主:地图滑不动、一闪一闪、时不时黑一下):
      // ① 手指一按下就停掉跟随回中。原来 _hold 只在松手时才设,拖动中每 500ms 的定位回调
      //    照样改 center,地图被拽回玩家位置 = 「滑不动」。松手(end)再从那一刻起算 3s;
      //    end 若丢失,60s 后自动恢复跟随。
      // ② 手势期间不再拉全屏黑 guard:旧雾帧留在屏上,松手后按新视口立即重画一帧,
      //    新帧 load 完才替换旧帧(双缓冲),全程不出现纯黑。只取消在途的旧视口重绘。
      //    程序跟随(causedBy=update)照旧不遮,交给 end 阶段按真实漂移量判。
      if (byGesture) {
        this._hold = Date.now() + 60000;
        if (this._fogMode === 'screen') this._cancelScreenFogRender();
      }
      return;
    }
    if (phase !== 'end') return;
    const scaleChanged = detail.scale != null && detail.scale !== this._mapScale;
    if (detail.scale != null) this._mapScale = detail.scale;
    if (byGesture) this._hold = Date.now();
    if (this._fogMode === 'screen') {
      // 程序移动(回中跳远、程序缩放)仍按「旧帧还能不能顶着用」判;手势松手只重画、不遮。
      if (!byGesture && (scaleChanged || this._screenFogDriftTooFar())) this._guardScreenViewportChange();
      this._scheduleScreenFogRender(byGesture ? 0 : undefined);
    }
  },

  /** 旧帧还能不能顶着用:当前帧对应视口的中心,和现在的中心差了多远。
   *  阈值取半个揭示半径 —— 边缘多露出的窄边宽度就是这个漂移量,压在半径以内时
   *  露出的几乎都是已探区外沿,不足以剧透;超过就说明视口真的走远了,老实遮住。
   *  拿不到帧 region(还没画过第一帧)一律按「太远」处理,宁遮勿漏。 */
  _screenFogDriftTooFar() {
    const region = this._fogScreenLastRegion;
    const center = this.data.center;
    const sw = region && region.southwest;
    const ne = region && region.northeast;
    if (!sw || !ne || !center) return true;
    const midLat = (Number(sw.latitude) + Number(ne.latitude)) / 2;
    const midLng = (Number(sw.longitude) + Number(ne.longitude)) / 2;
    const lat = Number(center.lat);
    const lng = Number(center.lng);
    if (!Number.isFinite(midLat) || !Number.isFinite(midLng)
      || !Number.isFinite(lat) || !Number.isFinite(lng)) return true;
    return distM({ lat: midLat, lng: midLng }, { lat, lng }) > REVEAL_M / 2;
  },

  toggleGps() {
    if (this._realOn) {
      this._stopReal();
      this._syncGoal();
      cyToast('已停止实时定位');
    } else {
      this._startReal();
    }
  },

  /**
   * Immersive §7「当前目标与下一步始终可见」。
   *
   * 原页没有目标层:底栏 ff-stats 显示的是**已完成战绩**(已探 N 家 / 探索度 / 已走 km)= 过去时;
   * 唯一像"下一步"的 nearbyBanner / paceCard / roamPrompt 三者互斥**且全部可关闭、关掉无重入口**;
   * currentAddress 明明有值却 wxml 零引用 —— 用户永远看不到自己在哪、也不知道下一步该干嘛。
   *
   * 目标层常驻不可关,并承担被关掉的卡的重入口(见 onGoalTap)。
   */
  _syncGoal() {
    const d = this.data;
    let goal;
    const eventOverlay = d.eventOverlay || {};
    const pendingArrival = (eventOverlay.missions || []).find((mission) => mission.canVerifyArrival && !mission.complete);
    if (eventOverlay.id && !eventOverlay.paused && eventOverlay.status === 3 && pendingArrival) {
      goal = eventOverlay.signed
        ? {
            stateKey: 'actionable',
            icon: 'flag',
            text: '官方活动 · 验证“' + pendingArrival.title + '”',
            act: 'event',
            readerLabel: '可行动。官方活动任务“' + pendingArrival.title + '”可以验证到达。',
          }
        : {
            stateKey: 'restricted',
            icon: 'flag',
            text: '官方活动 · 先报名再完成任务',
            act: 'event',
            readerLabel: '受限。请先报名官方活动，再完成任务。',
          };
    } else {
      goal = buildRoamGoal({
        visitActive: !!(d.visit && d.visit.active),
        poiName: d.visit && d.visit.poi && d.visit.poi.name,
        gpsEnabled: this._realOn,
        locationError: d.locErr,
        offline: false, // 据点加载失败不展示离线提示,按常规目标显示
        poiEmpty: d.poiEmpty,
        nearbyCount: d.nearbyPois ? d.nearbyPois.length : 0,
      });
    }
    this.setData({ goal }, () => this._syncMarkers());
  },

  // 目标层点击 = 被关掉的卡/流程的重入口(原来关掉就再也回不去)
  onGoalTap() {
    const act = this.data.goal && this.data.goal.act;
    if (act === 'gps') { this.toggleGps(); return; }
    if (act === 'nearby') { this.setData({ nearbyBanner: { show: true } }); return; }
    if (act === 'retry-poi') { this.onPoiRetry(); return; }
    // buildRoamGoal 的 poiEmpty 状态会产出 discover,
    // 这里复用工具面板的同一发现入口。
    if (act === 'discover') { this.goDiscover(); return; }
    if (act === 'event') { this.setData({ 'eventOverlay.show': true }, () => this._syncSparkCircles()); }
  },
  _observeLocationRevocation() {
    if (this._realRevokeOff) return;
    this._realRevokeOff = bgTracker.observeRevocation('roam', () => {
      this._roamStartGen = (this._roamStartGen || 0) + 1;
      this._roamStartPending = false;
      if (this._fogStartPending) this._fogStartPending.startTracking = false;
      this._stopReal();
      this._syncGoal();
    });
  },
  _startReal(consentAlreadyRecorded) {
    if (this._unloaded || this._realOn || this._realStarting) return;
    const that = this;
    const epoch = this._realStartEpoch = (this._realStartEpoch || 0) + 1;
    const locationRevision = bgTracker.revision('roam');
    const owner = String(getApp().getUserID() || '');
    const live = () => !that._unloaded && epoch === that._realStartEpoch && locationRevision === bgTracker.revision('roam') && owner === String(getApp().getUserID() || '');
    this._realStarting = true;
    // 2026-10-29 V-06:「仅前台记录」提示已按业主删除,前台降级只作实例状态保留,不再进渲染数据
    this._locationForegroundOnly = false;
    this._observeLocationRevocation();
    const abandon = () => {
      if (!live()) return;
      that._realStarting = false;
      that._locationForegroundOnly = false;
      if (that._realRevokeOff) { that._realRevokeOff(); that._realRevokeOff = null; }
    };
    const onOk = (usingBackground) => {
      if (!live()) return;
      that._realStarting = false; that._realAnchor = null;
      that._realOn = true;
      wx.onLocationChange(that._onRealLoc = (loc) => { if (live() && that._realOn) that._realTick(loc); });
      that._locationForegroundOnly = usingBackground === false;
      that.setData({ locErr: false });
      that._syncGoal();
      wx.setKeepScreenOn && wx.setKeepScreenOn({ keepScreenOn: true });
      // 不再弹「真实定位已开启」:计时与定位点已说明,toast 会压住暂停键。
    };
    // 主动开启后允许后台定位，结束行程即停止；用途必须先向玩家说明。
    // 后台定位:漫游是边走边点亮,玩家锁屏塞兜是常态;前台版一锁屏就断,轨迹丢段。
    // 拿不到后台权限自动降级前台(功能仍可用,只是锁屏会断)。停的时机只有 _stopReal。
    const startLocation = () => {
      if (!live()) return;
      bgTracker.acquire('roam', {
      success: onOk,
      fail() {
        if (!live()) return;
        abandon();
        that.setData({ locErr: true });
        that._syncGoal();
        cyToast('未授权定位，无法开启实时漫游');
      },
      });
    };
    const recordAndStart = () => getApp().recordConsent({ docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE' })
      .then(startLocation)
      .catch(() => { if (live()) { abandon(); cyToast('同意记录失败，请检查网络后重试'); } });
    if (consentAlreadyRecorded) { startLocation(); return; }
    cyModal.show({
      title: '开启实时定位',
      content: '用于实时定位、打卡和轨迹记录，锁屏后仍会记录，结束漫游即停止。',
      confirmText: '同意并开启',
      cancelText: '暂不开启',
      success(result) {
        if (!live()) return;
        if (!result.confirm) { abandon(); return; }
        recordAndStart();
      },
      fail: abandon,
    });
  },
  _stopReal() {
    this._realAnchor = null;
    bgTracker.release('roam');
    this._realStartEpoch = (this._realStartEpoch || 0) + 1;
    this._realStarting = false;
    this._locationForegroundOnly = false;
    if (this._realRevokeOff) { this._realRevokeOff(); this._realRevokeOff = null; }
    const onLocationChange = this._onRealLoc;
    this._realOn = false;
    this._onRealLoc = null;
    if (onLocationChange) {
      try { wx.offLocationChange(onLocationChange); } catch (e) {}
    }
    this._syncIdle(null);
    wx.setKeepScreenOn && wx.setKeepScreenOn({ keepScreenOn: false });
  },
  _realTick(loc) {
    if (!this._realOn || this._blocked()) { this._syncIdle(null); return; }
    const now = Date.now();
    const sample = acceptRoamLocation(loc, null, now);
    if (!sample) return;
    // BL-02 静止提示包含有效的 <3m 点，仅用于 HUD，不改变轨迹过滤。
    this._syncIdle(sample.point, now);
    const accepted = acceptRoamLocation(loc, this._realAnchor, now);
    if (!accepted) return;
    const prev = this._realAnchor;
    const p = accepted.point;
    this._realAnchor = p; this._player = p;
    this._dist = (this._dist || 0) + accepted.distance;
    this._track.push({ lat: p.lat, lng: p.lng });
    const last = this._reveals[this._reveals.length - 1];
    if (!last || distM(last, p) > 15) this._addReveal(p);
    this._applyMovePatch(p, prev);
  },

  // BL-02:位移 < 3m 持续 60s 时 HUD 显示「原地停留中」;不碰计时/距离/点亮规则
  _syncIdle(point, now) {
    if (!point) {
      if (this._idle) this._idle.reset();
    } else {
      if (!this._idle) this._idle = createIdleDetector();
      const idle = this._idle.update(point, now);
      if (idle !== this.data.roamIdle) this.setData({ roamIdle: idle });
      return;
    }
    if (this.data.roamIdle) this.setData({ roamIdle: false });
  },

  _blocked() {
    return this.data.paused || this.data.medal.show || this.data.screen !== 'map';
  },

  _applyMovePatch(p, prev) {
    if (this.data.paused) return;
    this._updateHeading(prev, p);
    this._syncStats();
    this._checkNear(p);
    // 两条都自带节流,放心每步调(原型 otherRunners:地图上跑着别的玩家)
    this._reportPresence(p);
    this._fetchRunners(p);

    const km = ((this._dist || 0) / 1000).toFixed(2);
    const patch = {};
    if (km !== this._stats.distance) { this._stats.distance = km; this._rollStat('distance', km); }

    const now = Date.now();
    if (!this._zoomLock && (!this._hold || now - this._hold > 3000)) {
      if (!this._centerTimer || now - this._centerTimer >= CENTER_MS) {
        this._centerTimer = now;
        patch.center = { lat: p.lat, lng: p.lng };
      }
    }
    if (!this._lineTimer || now - this._lineTimer >= LINE_MS) {
      this._lineTimer = now;
      if (this._track.length > 1) {
        patch.polyline = [{
          points: this._track.map((q) => ({ latitude: q.lat, longitude: q.lng })),
          color: '#FFFFFF',
          width: 3,
          borderColor: '#FFFFFF66',
          borderWidth: 1,
        }];
      }
    }

    if (Object.keys(patch).length) this.setData(patch);
    // markers 全量重发节流(每 tick 重发是图层闪烁源之一);转向时 _updateHeading 会即时同步
    if (!this._mkTimer || now - this._mkTimer >= 600) {
      this._mkTimer = now;
      this._syncMarkers();
    }
  },

  _syncStats() {
    // P1-6:onHide 期间只保会话/采集语义,UI 推送短接(onShow 会立即补一次)。
    // 不短接的话:tab 页永不卸载 + GPS 会话在兜里继续跑 = 每秒一组无人接收的 setData 跨序列化线。
    if (this._hidden) return;
    // P1-4 后 HUD 只渲染 shops/explorePct/distance;avgSpeed/shopsPad 已无渲染方,不再计存
    // #22:按店名去重后再数 —— 同一家店的多源副本各自 done 会被数成两家
    const shops = doneShopCount(this._pois);
    if (this._stats.shops !== shops) { this._stats.shops = shops; this._rollStat('shops', shops); }
    this._syncPlayHud();
    this._syncPlayHeader();
  },

  /** 雾揭开处的商家 fog→seen(星标+店名浮现);探店完成才 done。用户拍板:雾中不可见,走近才现 */
  _markSeenNear(pt) {
    let hit = false;
    (this._pois || []).forEach((p) => {
      if (p.cat !== 'merchant' || p.state !== 'fog') return;
      if (distM(p, pt) <= REVEAL_M) { p.state = 'seen'; hit = true; }
    });
    return hit;
  },
  /** 历史揭开区扫一遍(开局/异步 POI 并入后调):曾点亮过的商家直接带星入场 */
  _seenSweepHistory() {
    const H = (this._historyReveals || []).concat(this._reveals || []);
    if (!H.length) return false;
    let hit = false;
    (this._pois || []).forEach((p) => {
      if (p.cat !== 'merchant' || p.state !== 'fog') return;
      for (let i = 0; i < H.length; i++) {
        if (distM(p, H[i]) <= REVEAL_M) { p.state = 'seen'; hit = true; break; }
      }
    });
    return hit;
  },

  _addReveal(p) {
    this._reveals.push({ lat: p.lat, lng: p.lng });
    if (this._markSeenNear(p)) { this._syncMarkers(); this._syncSparkCircles(); }
    if (this._fogMode === 'screen') {
      if (this.data.fogScreenReady) this._scheduleScreenFogRender();
      else this._renderScreenFog();
    } else if (this._fogMode === 'overlay') {
      this._stampReveal(p, 1);
      this._scheduleFogFlush();
    } else {
      // 只有格子真的变清了才重建 poly(走已探索区时跳过全量替换 → 不闪白)
      // 但 polygon 已裁窗时,回走旧区域也必须让渲染窗跟玩家走,否则外侧全雾挡板会盖住玩家。
      if (this._fogApplyReveal(p) || this._fogRenderClipped) this._scheduleFogUpdate();
    }
    this._accumulateTile(p.lat, p.lng); // P2 接真:格子入上报队列
    const c = this._c; const cell = 60; const half = 600;
    const px = (p.lng - c.lng) / m2lng(1, c.lat);
    const py = (p.lat - c.lat) / m2lat(1);
    for (let gx = Math.floor((px - REVEAL_M) / cell); gx <= Math.floor((px + REVEAL_M) / cell); gx++) {
      for (let gy = Math.floor((py - REVEAL_M) / cell); gy <= Math.floor((py + REVEAL_M) / cell); gy++) {
        if (Math.abs(gx * cell) > half || Math.abs(gy * cell) > half) continue;
        this._gridHit[gx + ',' + gy] = 1;
      }
    }
    const total = Math.pow(half * 2 / cell, 2);
    const pct = Math.min(99, Math.round(Object.keys(this._gridHit).length / total * 100));
    if (pct !== this._stats.explorePct) { this._stats.explorePct = pct; this._rollStat('explorePct', pct); }
  },

  /**
   * #22 商家浮卡数据:代表条目 + 逐卡集章进度。
   * data-id 仍取 rep.id —— startVisit 靠它 find 回原 poi,且 rep 优先带 nodeId(打卡载体)。
   */
  _mkShopCard(group, distMeters) {
    const stamp = stampProgress(this._pois);
    const dist = distMeters != null ? distMeters : group.rep.distM;
    // 与后端同一可用半径:够不到就是按钮不可用(只提示还差多远),不发请求、不产生失败态。
    const gapM = this._checkinGap(group.rep, dist);
    return {
      ...group.rep,
      distM: dist,
      gapM,
      canCheckin: gapM === 0,
      groupKey: group.key,
      collected: group.collected,
      stampDone: stamp.done,
      stampTotal: stamp.total,
    };
  },

  /** 距可打卡还差多少米;0 = 现在就能打。优先用实时玩家位置,没有才退回卡上的粗略距离。
   *  位置未知时返回 0(不拦)——与改动前一致,交给服务端判定。 */
  _checkinGap(poi, fallbackDist) {
    let dist = null;
    if (poi && this._player && Number.isFinite(poi.lat) && Number.isFinite(poi.lng) && !poi.demo) {
      dist = distM(poi, this._player);
    }
    if (dist == null) dist = Number.isFinite(fallbackDist) ? fallbackDist : null;
    if (dist == null) return 0;
    return dist > CHECKIN_NEAR_M ? Math.ceil(dist - CHECKIN_NEAR_M) : 0;
  },

  /* ============ 附近 POI:商家横滑 banner / 地标 pace 卡 ============ */
  _checkNear(p) {
    // 二选一卡开着时不刷新附近 banner:banner 会在 mask(z90)下静默堆积成过期提示;GPS/雾照常走
    if (this.data.visit.active || this.data.discoverReward.show) return;
    let landmark = null;
    for (const poi of this._pois) {
      // fog=雾中未见,seen=星标已现未探店 —— 两态都要弹探店浮卡
      if (poi.state !== 'fog' && poi.state !== 'seen') continue;
      if (poi.cat === 'merchant') continue;   // 商家侧交给 selectNearShopGroups(纯函数,已单测)
      const d = distM(poi, p);
      if (d >= NEAR_M) continue;
      if (!landmark || d < landmark.distM) landmark = { ...poi, distM: Math.round(d) };
    }
    // #22 按店名归组:同店多源(nearby 数字 id / roam 'r' 前缀 id / 同店多条报名)只弹一张卡
    const merchants = selectNearShopGroups(this._pois, p, NEAR_M)
      .map((s) => this._mkShopCard(s.group, s.distM));

    if (merchants.length) {
      // 签名要用去重后的组 key(沿用旧的 map(m=>m.id) 会让去重前后签名相同 → 跳过刷新),
      // 且必须带上进度/集章态:它们是 _pois 全集的派生量,近店组没变但全集变了(如 roam POI
      // 晚到并入)时,只比 key 会让卡上的「附近已集 N/M」永远停在旧值。
      // 还带上打卡可用态与 10m 步进的差距:走近到能打卡/差多少米都要如实刷新(拍板 9-16)。
      const sig = (l) => (l || []).map((m) => m.groupKey + ':' + m.stampDone + '/' + m.stampTotal + ':' + (m.collected ? 1 : 0)
        + ':' + (m.canCheckin ? 1 : 0) + ':' + Math.ceil((m.gapM || 0) / 10)).join(',');
      const ids = sig(merchants);
      const prev = sig(this.data.nearbyPois);
      if (ids !== prev) {
        if (!this._nearbyShown) {
          wx.vibrateShort && wx.vibrateShort({ type: 'light' });
          this._nearbyShown = true;
        }
        this.setData({
          nearbyPois: merchants,
          nearbyIdx: 0,
          nearbyBanner: { show: true },
          paceCard: { show: false, title: '', body: '', demo: false },
        });
        this._syncGoal();
      }
      return;
    }

    if (landmark) {
      this.setData({
        nearbyPois: [],
        nearbyBanner: { show: false },
        paceCard: this._paceCardFor(landmark),
      });
      return;
    }

    if (this.data.nearbyPois.length || this.data.nearbyBanner.show) {
      this.setData({ nearbyPois: [], nearbyBanner: { show: false } });
    }
  },

  onMarkerTap(e) {
    if (e.detail.markerId === PLAYER_MK || e.detail.markerId === STATUS_MARKER_ID) return;
    // 别人的头像:id 是 RUNNER_MK_BASE + 下标,按下标回本地那一批取人
    if (e.detail.markerId >= RUNNER_MK_BASE) {
      const row = (this._runners || [])[e.detail.markerId - RUNNER_MK_BASE];
      if (row) this.openRunner(row.memberId);
      return;
    }
    const poi = (this._pois || []).find((p) => p.id === e.detail.markerId);
    if (!poi || poi.state === 'done') return;
    // 商户据点(漫游 type=2)→ 进商家主页任务卡完成互动领券
    if (poi.isCityNode && poi._roamId) {
      this.openScene('roam-poi-detail', { poiId: poi._roamId });
      return;
    }
    if (poi.cat === 'merchant') {
      // 原型 f-topic / f-city:挂着主题的针点开是主题半屏,不是商家白卡。
      if (this.openTopicView(poi)) return;
      // 点图标也走归组:同店多源合成一张卡,且带上集章进度(passed 店点开会显示"已集")。
      // 按【成员身份】找组,不能按店名找 —— 同名不同店时按名会拿回别家店的卡。
      const group = groupMerchantsByName(this._pois).find((g) => g.all.indexOf(poi) >= 0);
      // 组内已有本次会话探完的副本 → 代表条目是 done 的,卡上「→」按下去 startVisit 直接 return
      // (无 toast 无反馈=死按钮)。等同于上面 poi.state==='done' 的守卫,只是升到组的粒度。
      if (group && group.doneInSession) return;
      this.setData({
        nearbyBanner: { show: true },
        nearbyPois: [group ? this._mkShopCard(group) : poi],   // 无名店归不了组 → 退回原样,行为不变
        nearbyIdx: 0,
        paceCard: { show: false, title: '', body: '', demo: false },
      });
    } else {
      this.setData({
        paceCard: this._paceCardFor(poi),
        nearbyBanner: { show: false },
      });
    }
  },

  onNearbySwipe(e) { this.setData({ nearbyIdx: e.detail.current }); },

  closeNearbyBanner() {
    this.setData({ nearbyBanner: { show: false } });
  },
  closePaceCard() { this.setData({ paceCard: { show: false, title: '', body: '', demo: false } }); },

  _paceCardFor(poi, patch) {
    return Object.assign({
      show: true,
      title: poi.name || '地点',
      body: poi.desc || '',
      demo: !!poi.demo,
      poiId: poi.id,
      // 只有服务端 roam 非商户 POI 需要这条到点校验；商家仍走既有确定性探店链路。
      canDiscover: !!(poi._roamId && poi.cat !== 'merchant' && !poi.demo),
      meaning: '',
      checkinErr: '',
      checkinRetryable: true,
    }, patch || {});
  },

  discoverRoamPoi(e) {
    const poiId = e && e.currentTarget && e.currentTarget.dataset ? e.currentTarget.dataset.id : e;
    this._discoverRoamPoiById(poiId);
  },

  retryRoamPoiDiscover() {
    if (this.data.paceCard && this.data.paceCard.checkinRetryable === false) return;
    this.setData({ 'paceCard.checkinRetryable': false });
    this._discoverRoamPoiById(this.data.paceCard && this.data.paceCard.poiId);
  },

  _discoverRoamPoiById(poiId) {
    if (this._roamPoiDiscovering) return;
    const poi = (this._pois || []).find((item) => item.id === poiId);
    if (!poi || !poi._roamId || poi.cat === 'merchant' || poi.demo) return;
    if (!this._roamSid) {
      this.setData({
        'paceCard.checkinErr': '先移动一小段，建立本次漫游记录后再确认地点。',
        'paceCard.checkinRetryable': true,
      });
      return;
    }
    this._roamPoiDiscovering = true;
    cyLoading.show('确认地点');
    const generation = this._roamWriteGeneration;
    const pending = this._requestRoamPoiDiscover(poi).then((result) => {
      if (generation !== this._roamWriteGeneration) return false;
      this._roamPoiDiscovering = false;
      cyLoading.hide();
      if (!result.ok) {
        const unknown = result.reason === 'unknown';
        let tip = result.msg || '暂时无法确认这次到点，可以保持当前位置后重试。';
        if (result.reason === 'net') tip = '确认没有送出去，网络恢复后可以重试。';
        if (unknown) tip = result.msg || '服务响应异常，确认结果待确认。请稍后重新进入查看，暂请勿重复提交。';
        this.setData({ 'paceCard.checkinErr': tip, 'paceCard.checkinRetryable': !unknown });
        return false;
      }
      this._roamPoiFinishBlocked = false;
      poi.state = 'done';
      poi.first = false;
      this._foundRoamPois[poi._roamId] = 1;
      analytics.track('roam_poi_found', { bizType: 'roam', bizId: poi._roamId });
      this.setData({ paceCard: this._paceCardFor(poi, {
        meaning: result.meaning || '',
        confirmed: true,
      }) });
      if (result.meaning) {
        analytics.track('roam_poi_meaning_shown', { bizType: 'roam' });
      }
      this._syncMarkers();
      this._syncSparkCircles();
      cyToast(result.meaning ? '地点已点亮' : '地点已确认');
      return true;
    }).catch(() => {
      if (generation !== this._roamWriteGeneration) return false;
      this._roamPoiDiscovering = false;
      cyLoading.hide();
      this.setData({
        'paceCard.checkinErr': '确认没有送出去，请检查网络后重试。',
        'paceCard.checkinRetryable': true,
      });
      return false;
    });
    this._roamPoiPromise = pending;
    pending.then(() => { if (this._roamPoiPromise === pending) this._roamPoiPromise = null; });
    return pending;
  },

  _requestRoamPoiDiscover(poi) {
    return req('/api/roam/poi/discover', 'POST', {
      sessionId: String(this._roamSid),
      poiId: String(poi._roamId),
      lat: String(this._player.lat),
      lng: String(this._player.lng),
    }, { silentError: true }).then((res) => {
      if (apiOk(res) && res.data) return { ok: true, meaning: res.data.meaning || '' };
      if (netFail(res)) return { ok: false, reason: 'net' };
      if (httpFail(res)) return { ok: false, reason: 'unknown', msg: res.msg };
      return { ok: false, reason: 'server', msg: res && res.msg };
    });
  },

  startVisit(e) {
    const id = e && e.currentTarget ? e.currentTarget.dataset.id : e;
    const poi = this._pois.find((p) => p.id === id);
    if (!poi || poi.state === 'done') return;
    if (poi.demo) {
      cyToast('演示点仅供浏览，暂不支持打卡');
      return;
    }
    // 用户拍板(9-16):距离不够就是不能打卡,只提示还差多远 —— 不发请求、不开打卡卡、
    // 不产生任何失败态。分界点与后端 shop/visit 同一口径(见 CHECKIN_NEAR_M)。
    const gapM = this._checkinGap(poi);
    if (gapM > 0) {
      // 用整表重建而不是 nearbyPois[i].gapM 路径写法:保持 setData 顶层字段静态可核对
      // (UI-GATE-0 U4 不许新增动态 setData 债务)。
      const rows = (this.data.nearbyPois || []).map((m) => (
        String(m.id) === String(poi.id) ? Object.assign({}, m, { gapM, canCheckin: false }) : m
      ));
      if (rows.length) this.setData({ nearbyPois: rows });
      cyToast('还差 ' + gapM + ' 米，走近后再打卡');
      return;
    }
    wx.vibrateShort && wx.vibrateShort({ type: 'light' });
    this.setData({
      visit: { active: true, checkinOk: false, poi, photos: [], startedAt: Date.now() },
      nearbyBanner: { show: false },
      paceCard: { show: false, title: '', body: '', demo: false },
    });
    this._syncGoal();
    // 一次请求两个用途:完成态(checkinOk)与「探店日导流卡该不该出」都取同一条 recorded ——
    // 曾经是两条并行请求各说一套(play 域决定完成态、roam 域决定入账),两边不一致就是谎报。
    this._shopVisitPromise = this._sendCheckin(poi);
  },

  /**
   * 打卡上报 + 如实反馈。
   *
   * 用户拍板(9-16):打卡不存在失败态。距离不够在 startVisit 就被拦下(按钮不可用,只提示差多少米);
   * 真发出去的请求只有成功一条路会留在屏上 —— 网络/会话/服务端异常一律收起打卡卡并给一句
   * toast,不再有常驻的失败浮卡与「重试」按钮。照片不参与任何审核,也不会因为打卡失败被丢:
   * 失败时同样把它们留在本次足迹里。
   *
   * 为什么成功必须停在 checkinOk 由用户确认:_finishVisit() 会 poi.state='done' 并把
   * stats.shops 加一,而它只在 checkinOk 为真时被调(onCenterTap / endVisit)。
   *
   * 文案不承诺补传:全页没有任何重试/补传队列(grep 补传|重传|retry|queue 零命中),
   * 说「恢复后会自动补传」只是把一个谎换成另一个谎。HTTP 状态异常时写入结果未知,
   * toast 照旧提示核对、不开放盲重试。
   */
  _sendCheckin(poi) {
    const generation = this._roamWriteGeneration;
    cyLoading.show('打卡中');
    return this._apiShopVisit(poi).then((r) => {
      if (generation !== this._roamWriteGeneration) { cyLoading.hide(); return false; }
      cyLoading.hide();
      if (r.ok) {
        this.setData({ 'visit.checkinOk': true });
        this._triggerCelebration('checkin-success');
        this._syncGoal();
        return true;
      }
      // 失败不落失败态:照片留下,打卡卡收掉,只留一句提示。
      (this.data.visit.photos || []).forEach((photo) => {
        this._appendSessionPhoto(photo, (poi && poi.name) || '漫游打卡');
      });
      let tip = r.msg || '这次打卡还没有确认成功，走近一点再试。';
      if (r.reason === 'net') tip = '打卡没送出去，网络不通。这一站还没记上，恢复网络后走近再试。';
      if (r.reason === 'nosession') tip = '这次漫游还没正式开始记录。先在地图上走一小段，再回来打卡。';
      if (r.reason === 'nolocal') tip = '这个节点不是合作商家，打不了卡。换一个节点或稍后再试。';
      if (r.reason === 'unknown') tip = r.msg || '服务响应异常，打卡结果待确认。请稍后重新进入查看，暂请勿重复提交。';
      this.setData({ visit: { active: false, checkinOk: false, poi: {}, photos: [] } });
      this._syncGoal();
      cyToast(tip);
      return false;
    });
  },

  onPlayHudToggle() {
    this.togglePause();
  },

  // 底栏右键「打卡」。⚠️ 旧中键的复合语义(else 暂停)已剥离:标着打卡按下去却暂停,是接线事故
  onCenterTap() {
    const v = this.data.visit;
    if (v.checkinOk) { this._finishVisit(); return; }
    if (v.active) { cyToast('正在确认打卡，稍等片刻'); return; }
    cyToast('走近合作商家会弹出探店卡');
  },

  /** 长按主键 3 秒结束本次漫游。进行中和已暂停都能按 —— 一枚圆钮承担开始/暂停/结束(9-09 裁决),
   *  不再要求先暂停。打卡确认那一刻除外,那时中键是「完成打卡」。 */
  onCenterHoldStart() {
    if (this.data.visit.checkinOk) return;
    this._cancelEndHold();
    this._holdDelay = setTimeout(() => {
      this._holdDelay = null;
      this._endHoldStart = Date.now();
      this.setData({ endHoldPct: 0 });
      this._endHoldTimer = setInterval(() => {
        const elapsed = Date.now() - this._endHoldStart;
        const pct = Math.min(100, Math.round((elapsed / END_HOLD_MS) * 100));
        this.setData({ endHoldPct: pct, 'playHud.endHoldPct': pct });
        if (elapsed >= END_HOLD_MS) {
          this._endHoldTriggered = true;
          this._cancelEndHold();
          wx.vibrateShort && wx.vibrateShort({ type: 'heavy' });
          this._arrive();
        }
      }, 40);
    }, 180);
  },

  onCenterHoldEnd() {
    if (this._holdDelay) {
      clearTimeout(this._holdDelay);
      this._holdDelay = null;
    }
    if (this.data.endHoldPct > 0 && this.data.endHoldPct < 100) {
      this._cancelEndHold();
    }
  },

  _cancelEndHold() {
    if (this._holdDelay) {
      clearTimeout(this._holdDelay);
      this._holdDelay = null;
    }
    if (this._endHoldTimer) {
      clearInterval(this._endHoldTimer);
      this._endHoldTimer = null;
    }
    if (this.data.endHoldPct) this.setData({ endHoldPct: 0, 'playHud.endHoldPct': 0 });
  },

  _finishVisit() {
    const visit = this.data.visit;
    if (!visit.active || !visit.poi) return;
    const poiRef = visit.poi;
    (visit.photos || []).forEach((photo) => {
      this._appendSessionPhoto(photo, poiRef.name || '漫游打卡');
    });
    const poi = this._pois.find((p) => p.id === poiRef.id);
    // P0-1:first 在下一行被清掉,先抓首发状态,末尾判是否弹二选一仪式卡
    const wasFirst = !!(poi && poi.first);
    if (poi) { poi.state = 'done'; poi.first = false; }
    // P2 接真:发现的是后端 roam POI(有 _roamId)→ 记入待结算,发 XP 埋点
    if (poi && poi._roamId) {
      this._foundRoamPois[poi._roamId] = 1;
      analytics.track('roam_poi_found', { bizType: 'roam', bizId: poi._roamId });
    }
    const shops = doneShopCount(this._pois);   // #22:按店名去重,否则同店两条 done = 探 2 家就误弹"三店连亮"
    this._stats.shops = shops;
    this.setData({
      visit: { active: false, checkinOk: false, poi: {}, photos: [] },
      nearbyPois: [],
    });
    this._syncGoal();
    this._syncMarkers();
    this._syncSparkCircles();
    this._syncStats();
    cyToast('+' + (poiRef.points || 30) + ' 探索值');  // 奖励文案统一:全程「探索值」,不再混用「分」
    // 首次发现商家 → 券/任务二选一仪式卡;park/landmark 只 +分
    // #23:阈值读配置(_shopBadgeThreshold),不再写死 3。用 === 而非 >= 保持"只在跨过那一刻弹一次"。
    const hitStreak = shops === this._shopBadgeThreshold() && this._shopMedalFromServer === true && !!(this.data.finish && this.data.finish.shopMedal);
    if (wasFirst && poi && poi.cat === 'merchant') {
      // 商家二选一卡是高优先级仪式，旧足迹提示不能在关闭后重新浮出抢走收尾注意力。
      this._clearFootprintHintForPriority();
      const reward = { show: true, poi: { name: poi.name, imgUrl: poi.imgUrl } };
      // 与三店徽章同帧触发时徽章优先(z 92 > 90 会盖卡),收下徽章后 closeMedal 再补弹
      if (hitStreak) this._pendingDiscover = reward;
      else this.setData({ discoverReward: reward });
      const recorded = this._shopVisitPromise || Promise.resolve(false);
      this._shopVisitPromise = null;
      Promise.resolve(recorded).then((ok) => this._attachExploreDayCandidate(ok, reward)).catch(() => null);
    }
    if (hitStreak) this._popMedal();
    /* 原型 f-checkin:打完卡要有下文 —— 这一站能做什么。
       仪式在场时不出:首次发现的二选一卡与三店徽章都比它高一档,同帧弹两张就是相撞。 */
    if (!hitStreak && !(wasFirst && poi && poi.cat === 'merchant')) {
      const now = new Date();
      const pad = (v) => (v < 10 ? '0' + v : String(v));
      this.openCheckinView(poiRef, pad(now.getHours()) + ':' + pad(now.getMinutes()));
    }
  },

  closeDiscoverReward() {
    this._exploreDayRequestToken = null;
    this._exploreDayCandidateId = null;
    this.setData({ 'discoverReward.show': false });
  },
  onDiscoverCoupon() {
    analytics.track('roam_discover_reward_click', { bizType: 'roam', bizId: 0, properties: { choice: 'coupon' } });
    this.closeDiscoverReward();
    this.openRoamReward();
  },
  onDiscoverTask() {
    analytics.track('roam_discover_reward_click', { bizType: 'roam', bizId: 0, properties: { choice: 'task' } });
    this.closeDiscoverReward();
    this.openRoamTasks();
  },
  _attachExploreDayCandidate(recorded, reward) {
    if (recorded !== true || !reward) return Promise.resolve(null);
    const token = {};
    this._exploreDayRequestToken = token;
    return req('/api/roam/nearby-exploreday', 'GET', {
      lat: String(this._player.lat),
      lng: String(this._player.lng),
    }, { silentError: true }).then((res) => {
      if (this._exploreDayRequestToken !== token || !apiOk(res) || !res.data) return null;
      const activityId = Number(res.data.activityId);
      if (!Number.isInteger(activityId) || activityId <= 0) return null;
      const candidate = {
        activityId,
        title: res.data.title || '',
        subtitle: res.data.subtitle || '',
        coverImg: res.data.coverImg || '',
        meetingPoint: res.data.meetingPoint || '',
        distanceM: Number(res.data.distanceM) || 0,
      };
      this._exploreDayCandidateId = activityId;
      const enriched = Object.assign({}, reward, { exploreDay: candidate });
      if (this._pendingDiscover === reward) this._pendingDiscover = enriched;
      else {
        this.setData({ discoverReward: enriched });
        this._trackExploreDayShown(candidate);
      }
      return candidate;
    });
  },
  _trackExploreDayShown(candidate) {
    if (!candidate || !candidate.activityId) return;
    analytics.track('roam_exploreday_reco_shown', {
      sessionId: String(this._roamSid || ''),
      bizType: 'activity',
      bizId: candidate.activityId,
      properties: { placement: 'first_merchant_discover' },
    });
  },
  onDiscoverExploreDay() {
    const candidate = this.data.discoverReward && this.data.discoverReward.exploreDay;
    const activityId = this._exploreDayCandidateId;
    if (!candidate || !activityId || Number(candidate.activityId) !== activityId) return;
    analytics.track('roam_exploreday_reco_click', {
      sessionId: String(this._roamSid || ''),
      bizType: 'activity',
      bizId: activityId,
      properties: { placement: 'first_merchant_discover' },
    });
    this.closeDiscoverReward();
    this.openScene('play-activity-detail', { id: activityId });
  },

  async endVisit() {
    if (this.data.visit.checkinOk) this._finishVisit();
  },

  /**
   * 漫游到访上报 —— 免费层的**唯一**一条打卡写入,只写漫游自己的账。
   *
   * ★ 这里曾经并行发两条:一条 `/api/play/arrive`(play 域)+ 一条 `/api/roam/shop/visit`(roam 域),
   *   而完成态 checkinOk 由前者决定。两个后果:
   *   ① 免费漫游玩家(没有任何主题通行证)对报名商家打卡,play 域的会话闸会回 402「请先购买」——
   *      免费层里弹出付费墙,而 roam 域其实已经记上了到访 ⇒ 后端记到访、前端叫买票。
   *   ② 持探店日票的玩家在漫游中走到某家探店日商家附近,play 域会按 GPS 写下一条
   *      player_node_progress 进店行 ⇒ 一条从没扫过店内静态码的「进店」事实进库,
   *      而它正是 G2 硬门 queue_joined_at 的取值来源。零告警、零资损。
   *   现在完成态改由 `/api/roam/shop/visit` 的 `recorded` 决定 —— 它是漫游结算
   *   (selectFoundPoisBySession)真正数得到的那条账,前端说的「记上了」与后端一致。
   *   服务端归属校验保留:sourceType/sourceId 由后端按 roam_poi / cms_registration_merchant 反查。
   *
   * 返回 { ok, reason } 而不是裸 bool:调用方要能把「网络没送到」和「服务端拒了」分开说,
   * 原来一律压成 false,于是上游只能笼统地谎称「已记录」。
   * reason: 'net'(请求没送到)| 'unknown'(HTTP 异常,写入结果待核对)
   *       | 'server'(送到了,后端不认)| 'nolocal'(本地就没有可上报的来源,压根没发)
   */
  _apiShopVisit(poi) {
    // ★ 入参用 regId / _roamId,不是 nodeId:/shop/visit 后端按 roam_poi 或
    //   cms_registration_merchant 主键查,而 nodeId 是 cms_topic_node.id(张冠李戴且 mode=2 恒空)。
    //   用户拍板口径「任意真商家」,故 roam 据点(_roamId)与报名商家(regId)都要入账;
    //   演示种子两者都没有 → 天然不入账(服务端也会因查不到来源而拒)。
    const sourceType = poi && poi._roamId ? 1 : (poi && poi.regId ? 2 : 0);
    const sourceId = poi && (poi._roamId || poi.regId);
    if (!sourceType || !sourceId) return Promise.resolve({ ok: false, reason: 'nolocal' });
    // 还没有漫游会话(刚进图没走动/还没走到建会话的格子)与「这个点根本不是合作商家」
    // 是两件事,原来共用 nolocal 一句「不是合作商家」——把用户劝去换点位,而其实只需再走两步。
    if (!this._roamSid) return Promise.resolve({ ok: false, reason: 'nosession' });
    return req('/api/roam/shop/visit', 'POST', {
      sessionId: String(this._roamSid),
      sourceType: String(sourceType),
      sourceId: String(sourceId),
      lat: String(this._player.lat),
      lng: String(this._player.lng),
    }, { silentError: true }).then((res) => {
      if (apiOk(res) && res.data && res.data.recorded === true) return { ok: true };
      if (netFail(res)) return { ok: false, reason: 'net' };
      if (httpFail(res)) return { ok: false, reason: 'unknown', msg: res.msg };
      return { ok: false, reason: 'server', msg: res && res.msg };
    });
  },

  closeLocationPicker() {
    this.closeScene();
  },

  chooseLocationFromTencent() {
    this.closeLocationPicker();
    pickLocation({
      onPick: (poi) => {
        const p = { lat: poi.latitude, lng: poi.longitude };
        this._player = p;
        this.setData({
          currentAddress: poi.name || poi.address || '已选位置',
          center: p,
        });
        this._addReveal(p);
        this._syncMarkers();
        this._checkNear(p);
      }
    });
  },

  _appendSessionPhoto(photo, name) {
    const source = typeof photo === 'string' ? { path: photo, persisted: false } : photo;
    const candidates = (this._sessionPhotos || []).concat(Object.assign({}, source, { name }));
    const kept = retainRecentPhotos(candidates);
    const dropped = droppedSavedPhotos(candidates);
    releaseSavedPhotos(wx, dropped);
    this._sessionPhotos = kept;
  },

  /* 2026-09-11 用户裁决「不是 canon 了 用原型的」:漫游的拍照不再弹微信原生选图器,
     开的是原型那张取景卡(cy-proto-cam,与组局、集邮册同一件)。 */
  onTakePhoto() { this.setData({ camOpen: true }); },
  closeProtoCam() { this.setData({ camOpen: false }); },
  onProtoCamShot(e) {
    const raw = (e && e.detail && e.detail.path) || '';
    this.setData({ camOpen: false });
    if (raw) this._acceptPhoto(raw);
  },
  /* 拿到一张原图之后的去处:裁剪 → 落盘 → 进探店照片或本次足迹。
     取景卡与(历史上的)原生选图器共用这一段,所以单独抽出来。 */
  _acceptPhoto(raw) {
    const scope = this._roamReadScope();
    const sessionId = this._roamSid, sessionKey = this._clientSessionKey;
    const visit = this.data.visit;
    const address = this.data.currentAddress || '漫游打卡';
    const current = () => scope() && !this._unloaded && sessionId === this._roamSid
      && sessionKey === this._clientSessionKey && visit === this.data.visit;
    // 打卡照走自由比例裁剪(与全站上传同一套裁剪页);取消 = 放弃这张,不静默存原图
    return Promise.resolve().then(() => {
      if (current()) return getApp().cropAll([raw], 'free');
    }).then((out) => {
      if (!current()) return;
      const path = out && out[0];
      if (!path) return;
      return persistPhoto(wx, path).then((photo) => {
        if (!current() || !photo.path) return;
        if (visit.active) {
          const candidates = (this.data.visit.photos || []).concat(photo);
          const photos = retainRecentPhotos(candidates);
          const dropped = droppedSavedPhotos(candidates);
          releaseSavedPhotos(wx, dropped);
          this.setData({ 'visit.photos': photos });
        } else {
          this._appendSessionPhoto(photo, address);
        }
        cyToast(photo.persisted ? (this.data.visit.active ? '探店照片已保存' : '照片已保存') : '照片仅本次可用');
      });
    }).catch(() => { if (current()) cyToast('照片未保存，请重试'); });
  },

  endRoamSession() {
    // 从抽屉那行进来时先把抽屉收掉,否则确认框背后还压着一层九行抽屉。
    // 长按中键进来时本来就没有开的场景,closeScene 是空操作。
    this.closeScene();
    cyModal.show({
      dangerKey: 'roam.finish',   // 三段式文案在 utils/danger-actions.js
      success: (r) => { if (r.confirm) this._arrive(); },
    });
  },

  /**
   * #23:三店连亮弹层。阈值/文案不再硬编码,读 /api/roam/badge/shop-streak 的 def
   * (真源 = growth_badge.unlock_rule_json,改配置 = UPDATE 一行 SQL,不发版)。
   * 原交付收据为 DELIVERED 才显示获得；到达阈值不代表交付。
   */
  _popMedal() {
    if (!this._shopMedalFromServer || !(this.data.finish && this.data.finish.shopMedal)) return false;
    const cfg = this.data.finish.shopMedal;
    this.setData({
      medal: {
        show: true,
        short: cfg.nameEn || '三店连亮',
        name: cfg.name || '初探 · 连亮三店',
        sub: cfg.statement || '一口气点亮 3 处,城市开始记得你',
      },
    });
    this._triggerCelebration('medal-earned');
    return true;
  },

  /** 拉勋章配置(阈值+文案)。拿不到就用兜底常量,fail-open:不比改动前差。 */
  _fetchShopBadgeCfg() {
    return req('/api/roam/badge/shop-streak', 'GET', {}, { silentError: true }).then((res) => {
      if ((res.code == '200' || res.code == 200) && res.data && res.data.threshold > 0) {
        this._shopBadgeCfg = res.data;
      }
    });
  },
  /** 弹层阈值:配置优先,拿不到用兜底(与迁移里的 def 初值一致) */
  _shopBadgeThreshold() {
    const t = this._shopBadgeCfg && this._shopBadgeCfg.threshold;
    return t > 0 ? t : SHOP_BADGE_FALLBACK_THRESHOLD;
  },
  closeMedal() {
    this.setData({ medal: { show: false } });
    // 徽章期间攒下的首次发现卡补弹(见 _finishVisit 的 _pendingDiscover)
    if (this._pendingDiscover) {
      const pending = this._pendingDiscover;
      this.setData({ discoverReward: pending });
      this._pendingDiscover = null;
      this._trackExploreDayShown(pending.exploreDay);
    }
  },
  // 徽章卡真图导出,复用足迹卡的 sharecv 管线
  // 先关弹层再生成:medal.show 会让 _blocked() 丢 GPS 点,窗口开着越久丢得越多
  shareMedal() {
    const medal = this.data.medal || {};
    this.closeMedal();
    return this._exportAndSave('生成徽章卡', () => this._drawMedalCard(medal));
  },

  /** 徽章分享卡(750x1000):光晕+徽章圆(与 CSS .medal-coin 同构)+名称+水印 */
  _drawMedalCard(medal) {
    return this._getShareCanvas().then((cv) => {
      const W = 750; const H = 1000;
      const SS = 2; // 同 _drawShareCard:2x 超采样防真机导出发糊
      cv.width = W * SS; cv.height = H * SS;
      const g = cv.getContext('2d');
      g.scale(SS, SS);
      const bg = g.createLinearGradient(0, 0, W, H);
      bg.addColorStop(0, '#171717'); bg.addColorStop(1, '#0B0B0B');
      g.fillStyle = bg; g.fillRect(0, 0, W, H);
      const cx = W / 2; const cy = 330; const R = 150;
      const glow = g.createRadialGradient(cx, cy, R * 0.6, cx, cy, R * 2.2);
      glow.addColorStop(0, 'rgba(255,255,255,.45)'); glow.addColorStop(1, 'rgba(255,255,255,0)');
      g.fillStyle = glow; g.fillRect(0, 0, W, H);
      const coin = g.createRadialGradient(cx, cy - R * 0.24, R * 0.15, cx, cy, R);
      coin.addColorStop(0, '#FFFFFF'); coin.addColorStop(0.62, BRAND); coin.addColorStop(1, '#4A4A4A');
      g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2);
      g.fillStyle = coin; g.fill();
      g.textAlign = 'center';
      g.fillStyle = '#fff'; g.font = 'bold 44px sans-serif';
      g.fillText(medal.short || '徽章', cx, cy + 14);
      g.fillStyle = BRAND; g.font = '24px sans-serif';
      g.fillText('徽 章 到 手', cx, 590);
      g.fillStyle = '#fff'; g.font = 'bold 48px sans-serif';
      g.fillText(medal.name || '', cx, 660);
      g.fillStyle = '#999999'; g.font = '26px sans-serif';
      g.fillText(medal.sub || '', cx, 716);
      g.fillStyle = '#5A5A5A'; g.font = '22px sans-serif';
      g.fillText('城瘾 · CityFog', cx, H - 70);
      return this._canvasExport(cv);
    });
  },

  _arrive() {
    if (this._arrivalWait) return this._arrivalWait;
    if (this._roamPoiPromise) {
      const generation = this._roamWriteGeneration;
      const pending = this._roamPoiPromise.then((ok) => {
        if (this._arrivalWait === pending) this._arrivalWait = null;
        if (generation !== this._roamWriteGeneration) return;
        if (ok) return this._arrive();
        this._roamPoiFinishBlocked = true;
        cyToast('暂未结算，请先处理地点卡');
      });
      this._arrivalWait = pending;
      return pending;
    }
    if (this._roamPoiFinishBlocked) {
      cyToast('暂未结算，请先处理地点卡');
      return;
    }
    if (this.data.screen === 'arrive') return;
    if (this.data.visit.active) {
      cyToast('请先结束当前探店');
      return;
    }
    this._stopClock(); this._stopReal();
    const shops = doneShopCount(this._pois);   // #22:结算屏/足迹卡的探店数同样按店名去重
    const now = new Date();
    const hh = String(now.getHours()).padStart(2, '0');
    const mm = String(now.getMinutes()).padStart(2, '0');
    const fin = {
      zone: '这片街区',
      date: (now.getMonth() + 1) + '月' + now.getDate() + '日',
      dateLine: '今天 · ' + (now.getHours() < 12 ? '上午' : '下午') + ' ' + hh + ':' + mm,
      shops,
      distance: ((this._dist || 0) / 1000).toFixed(1),
      explorePct: this._stats.explorePct,
      time: this._stats.time,
      photos: (this._sessionPhotos || []).slice(-8),
    };
    this.setData({ screen: 'arrive', finish: fin });
    this._triggerCelebration('roam-finish');
    // 结算提示必须在上面 setData 之后派发 —— 那次 setData 整体覆盖 finish，
    // 而 showFinishLine 用路径写法只补提示字段。先覆盖再补，顺序反了提示会被冲掉。
    if (this._npc) this._npc.dispatch({ type: 'SESSION_FINISHED' });
    this._saveSession(fin);
    // P2 接真:结算上报后端(发探索值 + 里程碑勋章),回填真实值到结束屏;上报走原始米数 this._dist,fin.distance 仅展示(F20)
    this._postRoamFinish(this._dist || 0);
    analytics.track('roam_finish', { bizType: 'roam', bizId: this._roamSid || 0, properties: { shops, pois: Object.keys(this._foundRoamPois).length } });
    // 旧 V1 才保留“漫游结束→complete”的兼容投影。V2 只能在玩家主动点击某个任务时，
    // 由服务端到达验证写 evidence；绝不能把会话结束、雾格或前端回调当作完成事实。
    const explored = (this._reveals && this._reveals.length) || shops || Object.keys(this._foundRoamPois).length;
    if (this._eventId && explored && this._eventContractVersion === 1) this._reportEvent();
  },

  _reportEvent() {
    if (this._eventContractVersion !== 1) return Promise.resolve(false);
    return req('/api/official/events/' + this._eventId + '/complete', 'POST', {}).then((res) => {
      const ok = res && (res.code == 200 || res.code == '200');
      this.setData({ 'finish.eventErr': ok ? '' : (httpFail(res) ? 'unknown' : (netFail(res) ? 'net' : 'server')) });
      return !!ok;
    }, () => {
      this.setData({ 'finish.eventErr': 'net' });
      return false;
    });
  },

  retryEventReport() {
    if (!this._eventId || this._eventContractVersion !== 1) return;
    if (this.data.finish.eventErr === 'unknown') return;
    this.setData({ 'finish.eventErr': '' });
    this._reportEvent();
  },

  // 原拱形 finish 屏入口。屏已被结算页(3992:18005)替代;_calcArch 仍要跑 —— C2 分享预览靠 archSegs。
  openFinish() { this.setData({ ...this._calcArch() }); },

  // archSegs 只喂 C2 分享预览和导出 canvas(拱形结算屏已退场),所以位置隐私闸放在这里:
  // 进 _calcArch 的就是**要被别人看到**的那条轨迹,首尾各裁掉设置里的百分比。
  // 放在更下游(画 canvas 时)会漏掉 sheet 里那张预览卡 —— 预览=导出,两处必须同源。
  _calcArch() {
    const track = simplifyTrack(clipTrackForSharing(this._track, readSharePrivacy(wx)));
    return this._buildArchFrom(track, this._pois.filter((p) => p.state === 'done'), this._c);
  },

  // 轨迹 + POI 共用的 bbox 归一化:实时结束页(_calcArch)与历史足迹卡(R9-44)同源,
  // 差别只在传进来的 track/pois/center。不生成轨迹里没有的点,首尾必进(见 _calcArch 的 tail 补点)。
  _buildArchFrom(track, pois, center) {
    const W = 576; const H = 520; const padT = 100; const padB = 50; const padX = 60;
    const points = limitTrack(track);
    if (points.length < 2) return { archSegs: [], archPois: [], archEnd: {} };
    const c = center || points[0];
    const M = (p) => ({ x: (p.lng - c.lng) / m2lng(1, c.lat), y: -(p.lat - c.lat) / m2lat(1) });
    const pts = points.map(M);
    let x0 = 1e9; let y0 = 1e9; let x1 = -1e9; let y1 = -1e9;
    pts.forEach((p) => {
      x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y);
      x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y);
    });
    const s = Math.min((W - padX * 2) / Math.max(1, x1 - x0), (H - padT - padB) / Math.max(1, y1 - y0));
    const ox = (W - (x1 - x0) * s) / 2 - x0 * s;
    const oy = padT + (H - padT - padB - (y1 - y0) * s) / 2 - y0 * s;
    const X = (p) => p.x * s + ox; const Y = (p) => p.y * s + oy;
    const archSegs = [];
    for (let i = 1; i < pts.length; i++) {
      const ax = X(pts[i - 1]); const ay = Y(pts[i - 1]);
      const bx = X(pts[i]); const by = Y(pts[i]);
      const len = Math.hypot(bx - ax, by - ay);
      if (len === 0) continue;
      archSegs.push({
        i, x: Math.round(ax), y: Math.round(ay), len: Math.round(len) + 2,
        deg: Math.round(Math.atan2(by - ay, bx - ax) * 180 / Math.PI),
      });
    }
    const archPois = (Array.isArray(pois) ? pois : [])
      .filter((p) => p && p.lat != null && p.lng != null)
      .map((p, i) => {
        const m = M(p);
        return { i, x: Math.round(X(m)), y: Math.round(Y(m)), color: p.color };
      });
    const e = pts[pts.length - 1];
    return { archSegs, archPois, archEnd: { x: Math.round(X(e)), y: Math.round(Y(e)) } };
  },

  // share.idx / squarePostUnknown 仍是页面数据;只有「显不显示」交给场景栈(#572 单宿主收口)。
  openShare() {
    this._cancelShareWork();
    // 实时结束页的分享:源就是本次漫游(data.finish / archSegs)。
    this._shareSource = null;
    this._shareReturnStack = null;
    // 分享预览的真路线(archSegs)原来由拱形屏铺垫;屏退场后在这里补算。
    // ⚠️ 每次打开都重算,不再「已有就不算」:裁剪比例/开关可能在这中间被改过,
    //    缓存住的是**上一次设置**下的轨迹,用户以为调了设置其实分享的还是老那条。
    this.setData({ ...this._calcArch() });
    this._openShareSheet();
  },

  /**
   * R9-44:历史详情「分享足迹卡」。
   * 旧码只 wx.showShareMenu —— 既不生成卡、也不打开分享面板,点了没反应。
   * 这里复用实时结束页那套分享 sheet,但源换成用户选中的那条历史 session,
   * 日期/轨迹/成绩都来自它,并且**不写** this.data.finish / archSegs:
   * 取消/返回不能把当前漫游的结算状态串掉。
   */
  onSceneSessionShare(event) {
    const session = event && event.detail && event.detail.session;
    const memory = roamMemory(this);
    const state = memory.readSessionState();
    const record = state.ok && session && state.sessions.find(row => String(row.ts) === String(session.ts));
    const source = record && this._historyShareSource(record);
    if (!source) { cyToast('这次漫游记录无法生成足迹卡'); return; }
    this._cancelShareWork();
    this._shareSource = source;
    // 历史详情在场景栈第 2 层,分享 sheet 会顶掉它(MAX_DEPTH=2);记住来路,取消时还回去。
    this._shareReturnStack = this.data.sceneStack.slice();
    this._openShareSheet();
  },

  _syncCommunityEntry() {
    const app = getApp();
    const owner = String(app.getUserID && app.getUserID() || '');
    const sync = () => {
      if (this._hidden || owner !== String(app.getUserID && app.getUserID() || '')) return;
      const open = typeof app.canUseCommunity === 'function' && app.canUseCommunity(true);
      const visibility = this.data.share.visibility;
      this.setData({ 'share.communityWrite': open,
        'share.channels': channelsForVisibility(visibility).filter(channel => channel !== '广场' || open),
        'share.can': Object.assign(channelFlags(visibility), { square: open && channelFlags(visibility).square }) });
    };
    const pending = typeof app.loadFeatureFlags === 'function' ? app.loadFeatureFlags() : null;
    sync();
    if (pending && typeof pending.then === 'function') pending.then(sync);
  },
  _openShareSheet() {
    const app = getApp();
    const communityWrite = typeof app.canUseCommunity === 'function' && app.canUseCommunity(true);
    const privacy = readSharePrivacy(wx);
    this.setData({
      share: {
        show: true,
        idx: 0,
        squarePostUnknown: !!this._squarePostUnknown,
        visibility: privacy.visibility,
        channels: channelsForVisibility(privacy.visibility).filter(channel => channel !== '广场' || communityWrite),
        can: Object.assign(channelFlags(privacy.visibility), { square: communityWrite && channelFlags(privacy.visibility).square }),
        communityWrite,
      },
    });
    this.openScene('roam-share');
  },

  /** 历史分享的源:日期/轨迹/成绩都取自选中 session,不掺当前漫游。轨迹同样过位置隐私闸。 */
  _historyShareSource(session) {
    if (!session || typeof session !== 'object' || Array.isArray(session)) return null;
    const timestamp = Number(session.ts);
    if (!Number.isFinite(timestamp) || timestamp <= 0) return null;
    const date = new Date(timestamp);
    const validDate = !Number.isNaN(date.getTime());
    const distance = readNonNegative(session.distance);
    const shops = readNonNegative(session.shops);
    const explorePct = readNonNegative(session.explorePct);
    const clipped = clipSavedTrackForSharing(session.track, readSharePrivacy(wx));
    return {
      ownerId: String(getApp().getUserID() || ''),
      sessionId: exactSessionId(session.sessionId),
      finish: {
        zone: (typeof session.zone === 'string' && session.zone.trim()) || '这片街区',
        date: validDate ? (date.getMonth() + 1) + '月' + date.getDate() + '日' : '',
        shops: shops === null ? null : shops,
        distance: distance === null ? null : distance,
        explorePct: explorePct === null ? null : explorePct,
      },
      arch: this._buildArchFrom(clipped, session.pois, clipped[0]),
    };
  },

  /** 当前生效的分享源:历史分享用 _shareSource,否则用本次漫游的 data。 */
  _activeShareFinish() {
    return (this._shareSource && this._shareSource.finish) || this.data.finish || {};
  },
  _activeShareArch() {
    if (this._shareSource) return this._shareSource.arch;
    return { archSegs: this.data.archSegs, archPois: this.data.archPois, archEnd: this.data.archEnd };
  },
  _activeShareSessionId() {
    if (this._shareSource) return this._shareSource.sessionId || '';
    return exactSessionId(this._roamSid);
  },

  _shareScope() {
    const sameRoamOwner = this._roamReadScope();
    const epoch = this._shareEpoch || 0;
    const source = this._shareSource;
    return () => sameRoamOwner() && epoch === (this._shareEpoch || 0)
      && (!source || !source.ownerId || source.ownerId === String(getApp().getUserID() || ''));
  },

  _cancelShareWork() {
    this._shareEpoch = (this._shareEpoch || 0) + 1;
    const flow = this._squarePostFlow;
    if (flow && flow.sent && !flow.settled) {
      this._squarePostUnknownOwners = this._squarePostUnknownOwners || {};
      this._squarePostUnknownOwners[flow.ownerId] = true;
      if (flow.ownerId === String(getApp().getUserID() || '')) this._squarePostUnknown = true;
    }
    this._squarePostFlow = null;
    this._squarePostInFlight = false;
    if (this._shareUploadOperation) this._shareUploadOperation.abort();
    if (this._shareLoading) { cyLoading.hide(); this._shareLoading = false; }
  },

  /** C2 分享 sheet 里切「谁能看到」。只改本次分享,不落盘 —— 落盘的默认值在设置页。 */
  onShareVisibility(e) {
    const visibility = (e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.v) || '';
    if (!visibility || visibility === this.data.share.visibility) return;
    this.setData({
      'share.visibility': visibility,
      'share.channels': channelsForVisibility(visibility).filter(channel => channel !== '广场' || (typeof getApp().canUseCommunity === 'function' && getApp().canUseCommunity(true))),
      'share.can': Object.assign(channelFlags(visibility), { square: typeof getApp().canUseCommunity === 'function' && getApp().canUseCommunity(true) && channelFlags(visibility).square }),
      'share.communityWrite': typeof getApp().canUseCommunity === 'function' && getApp().canUseCommunity(true),
    });
  },
  closeShare() {
    const returnStack = this._shareReturnStack;
    this._cancelShareWork();
    this._shareSource = null;
    this._shareReturnStack = null;
    if (returnStack && returnStack.length) {
      // 历史分享:还原打开分享前的场景栈(通常是 roam-history + roam-session),
      // 不整个清空回地图;当前漫游的 data 从未被动过。sheet 的显隐由场景栈决定,
      // 不另设 share.show 位(见 scene-sheet-single-host-contract)。
      this.setData({ sceneStack: returnStack, sceneCurrent: currentScene(returnStack), sceneIn: true, sceneConfirm: { show: false, action: null, pending: null } });
      return;
    }
    this.closeScene();
  },
  onShareSwipe(e) { this.setData({ 'share.idx': e.detail.current }); },
  /**
   * P0-2 所见即所得:按当前预览卡(share.idx)画同版式 canvas 导出,预览=导出图。
   * #25 渠道收口 —— 每条渠道只承诺它真能做到的事:
   *   广场   = 真发帖(图传 OSS → /api/creativesquare/action,机审直发,发完即可见)
   *   朋友圈 = 存图 + 引导。小程序无法代码触发发朋友圈图文(onShareTimeline 只能分享
   *            小程序卡片,不是发图),这是平台边界,不是没接完。
   *   小红书 = 存图 + 引导。无跳转 scheme。
   *   保存   = 存相册(原样)
   * 统一返回 Promise<{ok, channel, saved, posted, postId?}> 供 automator 断言。
   */
  shareTo(e) {
    const ch = (e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.ch) || '';
    if (!ch) return Promise.resolve({ ok: false, channel: '', saved: false, posted: false, blocked: true }); // 没带渠道不是用户选了渠道,不拼「不发布到」
    // 可见性是**闸**不是标签(选了「仅自己」还能点进广场,选择器就是假的);按钮已按 share.channels 置灰,这里再拦一道:置灰挡不住 automator/深链。
    if (!isChannelAllowed(ch, this.data.share.visibility)) {
      cyToast('当前可见范围不发布到' + (ch === '保存' ? '相册' : ch));
      return Promise.resolve({ ok: false, channel: ch, saved: false, posted: false, blocked: true });
    }
    const shareBizId = Number(this._activeShareSessionId()) || 0;
    analytics.track('roam_share_channel', { bizType: 'roam', bizId: shareBizId, properties: { channel: ch } });
    // U-171 分享回执:选了渠道之后的成败也要落埋点;properties 只放渠道码/结果码,不放文案与帖子内容。
    const receipt = (r) => {
      const res = r || {};
      const channel = ROAM_SHARE_CHANNEL_CODE[ch] || 'other';
      if (res.ok) analytics.track('roam_share_complete', { bizType: 'roam', bizId: shareBizId, properties: { channel } });
      else analytics.track('roam_share_fail', { bizType: 'roam', bizId: shareBizId,
        properties: { channel, reason: res.cancelled ? 'cancelled' : (res.blocked ? 'blocked' : 'failed') } });
      return r;
    };
    if (ch === '广场') return this._shareToSquare().then(receipt);
    if (ch === '保存') {
      return this._exportAndSave('生成卡片', () => this._drawShareCard(this.data.share.idx), this._shareScope())
        .then((r) => ({ ok: r.saved, channel: ch, saved: r.saved, posted: false, cancelled: r.outcome === ALBUM_SAVE.CANCELLED }))
        .then(receipt);
    }
    return this._shareToAlbumGuide(ch).then(receipt);
  },

  /**
   * 朋友圈/小红书:存图 + 引导。文案显示在 modal 里让用户长按复制(不写剪贴板)。
   * ★ 只有真存进相册才弹引导:存失败时 _saveToAlbum 自己会弹权限引导/toast,
   *   这里再弹就会把它吞掉,并且是在对用户说谎(相册里没有那张图)。
   */
  _shareToAlbumGuide(ch) {
    const current = this._shareScope();
    const copy = this._shareCopy();
    return this._exportAndSave('生成卡片', () => this._drawShareCard(this.data.share.idx), current).then((r) => {
      if (!current() || !r.saved) return { ok: false, channel: ch, saved: false, posted: false, cancelled: r.outcome === ALBUM_SAVE.CANCELLED };
      cyModal.show({
        title: '去' + ch + '发布',
        content: '足迹卡已存到相册，打开' + ch + '选它就行。文案参考(可长按复制):\n' + copy,
        confirmText: '知道了', showCancel: false,
      });
      return { ok: true, channel: ch, saved: true, posted: false };
    });
  },

  /** 分享默认文案(广场发帖初值 / 朋友圈引导参考),取当前分享源(历史选中记录或本次战绩) */
  _shareCopy() {
    const f = this._activeShareFinish();
    const metric = (value) => (value == null ? 0 : value);
    return (this._shareSource ? f.date : '今天') + '点亮了 ' + metric(f.shops) + ' 家店 · ' + metric(f.distance) + 'km · 探索度 ' + metric(f.explorePct) + '% #城瘾CityFog';
  },

  /**
   * 广场真发帖:画卡 → 传 OSS → 发帖。
   * 后端 /api/creativesquare/action 是先发后审:文本机审命中即拦下(status=2 + 返错),
   * 否则 status=1 发布即可见、图片异步送检(违规回调再下架)。所以成功文案是「已发到广场」。
   */
  _shareToSquare() {
    const app = getApp();
    const writeAllowed = () => typeof app.canUseCommunity === 'function' && app.canUseCommunity(true);
    if (!writeAllowed()) { cyToast('当前账号未开放广场发布'); return Promise.resolve({ ok: false, channel: '广场', posted: false, saved: false, blocked: true }); }
    const scope = this._shareScope();
    const current = () => scope() && writeAllowed();
    const cancelled = () => ({ ok: false, channel: '广场', saved: false, posted: false, cancelled: true });
    const fail = (msg) => { cyToast(msg); return { ok: false, channel: '广场', saved: false, posted: false }; };
    if (!current()) return Promise.resolve(cancelled());
    if (this._squarePostUnknown) return Promise.resolve(fail('上次发布结果待确认，请先到广场查看，暂不要重复发布'));
    if (this._squarePostInFlight) return Promise.resolve(fail('正在发布，请勿重复操作'));
    const sessionId = this._activeShareSessionId();
    if (!sessionId) return Promise.resolve(fail(this._shareSource ? '这条漫游记录还没有关联线上会话，暂时不能发到广场' : '漫游记录尚未生成，请稍后再试'));
    const state = { ownerId: String(getApp().getUserID() || ''), sent: false, settled: false };
    this._squarePostFlow = state;
    this._squarePostInFlight = true;
    const requireCurrent = () => { if (!current()) { const error = new Error('分享已取消'); error.cancelled = true; throw error; } };
    const rememberUnknown = () => {
      this._squarePostUnknownOwners = this._squarePostUnknownOwners || {};
      this._squarePostUnknownOwners[state.ownerId] = true;
      if (current()) { this._squarePostUnknown = true; this.setData({ 'share.squarePostUnknown': true }); }
    };
    const flow = new Promise((resolve) => {
      cyModal.show({
        title: '发到广场', editable: true, placeholderText: '说点什么…',
        content: this._shareCopy(), confirmText: '发布',
        success: (m) => resolve(m.confirm ? (m.content || '').trim() : null),
        fail: () => resolve(null),
      });
    }).then((contents) => {
      if (!current() || contents === null) return cancelled();
      if (!contents) return fail('说点什么再发吧');
      this._shareLoading = true;
      cyLoading.show('发布中');
      return this._drawShareCard(this.data.share.idx)
        .then((path) => { requireCurrent(); return this._uploadOne(path); })
        .then((url) => {
          requireCurrent();
          state.sent = true;
          // R10-03:稳定发布意图键;同一未决分享重试复用,丢响应不会再多发一条
          const shareMemberId = String(getApp().getUserID() || '');
          const shareIntent = publishIntent.begin(wx, {
            scope: 'square:roam:' + sessionId,
            memberId: shareMemberId,
            payload: [contents, url, String(sessionId), '3'],
          });
          state.intentKey = shareIntent.key;
          state.intentMemberId = shareMemberId;
          if (shareIntent.previousUnknown) cyToast('上次分享未确认，重试不重复发');
          return req('/api/creativesquare/action', 'POST', {
            contents, pics: url,
            data_id: String(sessionId), data_type: '3',
            request_id: shareIntent.key,
          }, { silentError: true });
        })
        .then((res) => {
          const settleIntent = (status) => {
            if (state.intentKey) publishIntent.settle(wx, 'square:roam:' + sessionId, state.intentMemberId, state.intentKey, status);
          };
          if (!current()) { rememberUnknown(); settleIntent('unknown'); return cancelled(); }
          if (httpFail(res) || netFail(res)) {
            rememberUnknown();
            settleIntent('unknown');
            return fail('发布响应异常，帖子可能已经发出，请到广场确认，暂不要重复发布');
          }
          state.settled = true;
          if (res && (res.code == '200' || res.code == 200)) {
            settleIntent('success');
            cyToast(res.data && res.data.lifecycle === 'PUBLISHED' ? '已发到广场' : '已提交审核');
            return { ok: true, channel: '广场', saved: false, posted: true, postId: (res.data && res.data.id) || 0 };
          }
          if (res && res.intentDiscarded === true) {
            // 服务端明确该发布已软删:丢弃意图键,重新分享换新键(旧帖不自动复活)
            if (state.intentKey) publishIntent.discard(wx, 'square:roam:' + sessionId, state.intentMemberId, state.intentKey);
          } else {
            settleIntent('rejected');
          }
          return fail((res && res.msg) || '发布失败');
        })
        .catch((err) => {
          if (state.sent && !state.settled) {
            if (state.intentKey) publishIntent.settle(wx, 'square:roam:' + sessionId, state.intentMemberId, state.intentKey, 'unknown');
            rememberUnknown();
          }
          if (!current() || (err && err.cancelled)) return cancelled();
          console.warn('square share fail');
          return fail(state.sent ? '发布结果待确认，请到广场查看，暂不要重复发布' : ((err && err.message) || '发布失败'));
        });
    });
    return flow.finally(() => {
      if (this._squarePostFlow === state) {
        this._squarePostFlow = null;
        this._squarePostInFlight = false;
        if (this._shareLoading) { cyLoading.hide(); this._shareLoading = false; }
      }
    });
  },

  /** 单图上传 OSS,返回 URL;失败抛错(交给上游统一兜) */
  _uploadOne(path) {
    if (!path) return Promise.reject(new Error('生成图片失败'));
    return new Promise((resolve, reject) => {
      const app = getApp();
      const operation = app.createPageBoundOperation();
      this._shareUploadOperation = operation;
      const cancelledError = new Error('页面已离开');
      cancelledError.cancelled = true;
      operation.onAbort(() => reject(cancelledError));
      operation.attach(app.getUploadClient().uploadAll([path], {
        bizType: 'COMMUNITY_POST', mapResult: community.captureUpload,
        // results 是按下标赋值的稀疏数组,只有 ok 时才保证 [0] 有值
        onDone: (r) => {
          if (operation.isAborted()) return;
          operation.finish();
          if (this._shareUploadOperation === operation) this._shareUploadOperation = null;
          if (r && r.ok && r.results[0]) resolve(r.results[0]);
          else reject(new Error((r && r.failures && r.failures[0] && r.failures[0].msg) || '图片上传失败'));
        },
      }));
    });
  },

  /* ============ 分享卡 canvas 导出(P0-2 足迹卡 / P1-5 徽章卡共用) ============ */

  /**
   * 统一导出流程:loading → 画卡 → 存相册。
   * 返回 Promise<{path, saved}>:path 只证明画出来了,saved 才证明写进了相册。
   * ★ 必须等 _saveToAlbum 落定再 resolve:此前这里是 fire-and-forget(promise 丢掉),
   *   调用方拿到 path 时存图结果还不存在 —— 拒过相册授权的用户会看到"已保存"类提示,
   *   而图根本没进相册,且后到的权限弹窗会被调用方的 modal 吞掉(同时只允许一个 modal)。
   */
  _exportAndSave(loadingTitle, drawFn, current = this._roamReadScope()) {
    if (!current()) return Promise.resolve({ path: null, saved: false });
    // 每次 show 配一次 hide(cyLoading 按层计数):作用域失效走提前返回也要收,
    // 否则换号/离开漫游后遮罩一直挂着;_cancelShareWork 已收过的不重复 hide。
    const releaseLoading = require('../../utils/roam-share-loading.js').loadingRelease(this, cyLoading, loadingTitle);
    return Promise.resolve().then(drawFn).then((path) => {
      releaseLoading();
      if (!current()) return { path: null, saved: false };
      return this._saveToAlbum(path, current).then((r) => { const outcome = current() ? normalizeAlbumOutcome(r) : ALBUM_SAVE.FAILED; return { path, saved: outcome === ALBUM_SAVE.SAVED, outcome }; });
    }).catch((err) => {
      releaseLoading();
      if (!current()) return { path: null, saved: false };
      // 只有仍是当前作用域的失败才提示
      cyToast('生成失败');
      console.warn('card export fail');
      return { path: null, saved: false };
    });
  },

  _getShareCanvas() {
    if (this._shareCv) return Promise.resolve(this._shareCv);
    return new Promise((resolve, reject) => {
      wx.createSelectorQuery().select('#sharecv').fields({ node: true }).exec((r) => {
        const node = r && r[0] && r[0].node;
        if (!node) { reject(new Error('no sharecv')); return; }
        this._shareCv = node;
        resolve(node);
      });
    });
  },

  _canvasExport(cv) {
    return new Promise((resolve, reject) => {
      wx.canvasToTempFilePath({
        canvas: cv, fileType: 'png',
        // 显式 dest=画布物理尺寸:不传时默认 ×dpr 空放大,真机(dpr3)导出发糊
        destWidth: cv.width, destHeight: cv.height,
        success: (r) => resolve(r.tempFilePath), fail: reject,
      });
    });
  },

  _saveToAlbum(path, current = this._roamReadScope()) {
    return new Promise((resolve) => {
      if (!current()) { resolve(ALBUM_SAVE.FAILED); return; }
      wx.saveImageToPhotosAlbum({
        filePath: path,
        success: () => { if (!current()) { resolve(ALBUM_SAVE.FAILED); return; } cyToast('已保存到相册'); resolve(ALBUM_SAVE.SAVED); },
        fail: (e) => {
          if (!current()) { resolve(ALBUM_SAVE.FAILED); return; }
          const outcome = classifyAlbumSaveError(e);
          if (outcome === ALBUM_SAVE.DENIED) {
            cyModal.show({
              title: '需要相册权限', content: '在设置里允许保存到相册后重试。',
              confirmText: '去设置', cancelText: '取消',
              success: (m) => { if (m.confirm && current()) wx.openSetting({}); },
            });
          } else if (outcome === ALBUM_SAVE.FAILED) {
            cyToast('保存失败');
          }
          resolve(outcome);
        },
      });
    });
  },

  /** 拱形卡外轮廓(顶部半圆穹顶 + 底部小圆角),与 CSS .arch 同构 */
  _archPath(g, x, y, w, h, rBot) {
    const r = w / 2;
    g.beginPath();
    g.moveTo(x, y + h - rBot);
    g.lineTo(x, y + r);
    g.arc(x + r, y + r, r, Math.PI, 0);
    g.lineTo(x + w, y + h - rBot);
    g.arcTo(x + w, y + h, x + w - rBot, y + h, rBot);
    g.lineTo(x + rBot, y + h);
    g.arcTo(x, y + h, x, y + h - rBot, rBot);
    g.closePath();
  },

  /** 单行 value+label 对(与预览 .sh-nums 同排布):居中绘制 */
  _drawNumsInline(g, cols, cx, y) {
    const VF = 'bold 40px sans-serif'; const LF = '20px sans-serif';
    const GAP_VL = 10; const GAP_G = 26;
    let total = 0;
    const parts = cols.map((c, i) => {
      g.font = VF; const vw = g.measureText(c.v).width;
      g.font = LF; const lw = g.measureText(c.l).width;
      total += vw + GAP_VL + lw + (i < cols.length - 1 ? GAP_G : 0);
      return { v: c.v, l: c.l, vw, lw };
    });
    let x = cx - total / 2;
    g.textAlign = 'left';
    parts.forEach((p) => {
      g.fillStyle = '#ffffff'; g.font = VF; g.fillText(p.v, x, y); x += p.vw + GAP_VL;
      g.fillStyle = '#888888'; g.font = LF; g.fillText(p.l, x, y); x += p.lw + GAP_G;
    });
    g.textAlign = 'center';
  },

  /** story 卡的拱形卡(与预览 .sh-arch 同构:上迷你地图 + 卡内数字行):复用 C1 的 archSegs 缩放绘制 */
  _drawShareArch(g, cols, arch) {
    const { archSegs, archPois, archEnd } = arch || this.data;
    const x = 165; const y = 140; const w = 420; const h = 560; const rBot = 26;
    this._archPath(g, x, y, w, h, rBot);
    g.fillStyle = '#0D0D0D'; g.fill();
    g.strokeStyle = 'rgba(255,255,255,.12)'; g.lineWidth = 2; g.stroke();
    const ix = x + 14; const iy = y + 14; const iw = w - 28; const ih = 380;
    this._archPath(g, ix, iy, iw, ih, 20);
    const mg = g.createLinearGradient(ix, iy, ix, iy + ih);
    mg.addColorStop(0, '#1A1A1A'); mg.addColorStop(1, '#0C0C0C');
    g.fillStyle = mg; g.fill();
    g.save();
    this._archPath(g, ix, iy, iw, ih, 20);
    g.clip();
    if (archSegs && archSegs.length) {
      const k = Math.min(iw / 576, ih / 520);
      const ox = ix + (iw - 576 * k) / 2;
      const oy = iy + (ih - 520 * k) / 2;
      g.strokeStyle = BRAND; g.lineWidth = 3.5; g.lineCap = 'round';
      g.shadowColor = 'rgba(255,255,255,.7)'; g.shadowBlur = 8;
      archSegs.forEach((s) => {
        const rad = s.deg * Math.PI / 180;
        g.beginPath();
        g.moveTo(ox + s.x * k, oy + s.y * k);
        g.lineTo(ox + (s.x + s.len * Math.cos(rad)) * k, oy + (s.y + s.len * Math.sin(rad)) * k);
        g.stroke();
      });
      g.shadowBlur = 0;
      (archPois || []).forEach((p) => {
        g.beginPath(); g.arc(ox + p.x * k, oy + p.y * k, 7, 0, Math.PI * 2);
        g.fillStyle = p.color || '#D9D9D9'; g.fill();
        g.strokeStyle = '#fff'; g.lineWidth = 2.5; g.stroke();
      });
      if (archEnd && archEnd.x != null) {
        g.beginPath(); g.arc(ox + archEnd.x * k, oy + archEnd.y * k, 6, 0, Math.PI * 2);
        g.fillStyle = '#fff'; g.fill();
        g.strokeStyle = BRAND; g.lineWidth = 3; g.stroke();
      }
    } else {
      g.fillStyle = '#686868'; g.font = '24px sans-serif'; g.textAlign = 'center';
      g.fillText('今日足迹', ix + iw / 2, iy + ih / 2);
    }
    g.restore();
    // 数字行画在卡内、地图下方(与预览一致;日期只出现在卡下水印行,不进穹顶)
    this._drawNumsInline(g, cols, x + w / 2, iy + ih + 92);
  },

  /** idx 0=story(750x1000 竖版,拱形卡含路线+卡内数字) 1=square(750x750,数字行);版式与 swiper 预览卡一致 */
  _drawShareCard(idx) {
    const current = this._shareScope();
    // 历史分享时 fin/arch 来自选中 session(_activeShare*),不读也不会改当前漫游的 data。
    const fin = this._activeShareFinish();
    const arch = this._activeShareArch();
    return this._getShareCanvas().then((cv) => {
      if (!current()) { const error = new Error('share cancelled'); error.cancelled = true; throw error; }
      const W = 750; const H = idx === 1 ? 750 : 1000;
      const SS = 2; // 2x 超采样,配合 _canvasExport 的显式 dest 输出 1500/2000px 清晰图
      cv.width = W * SS; cv.height = H * SS;
      const g = cv.getContext('2d');
      g.scale(SS, SS);
      const bg = g.createLinearGradient(0, 0, W, H);
      if (idx === 1) { bg.addColorStop(0, '#242424'); bg.addColorStop(1, '#0B0B0B'); }
      else { bg.addColorStop(0, '#171717'); bg.addColorStop(1, '#0B0B0B'); }
      g.fillStyle = bg; g.fillRect(0, 0, W, H);
      // UI-04(2026-09-18):没取到的数字统计显示 0(原为「—」,R9-42 的旧口径已被本批覆盖)。
      const cols = [
        { v: fin.shops == null ? '0' : String(fin.shops), l: '探店' },
        { v: fin.distance == null ? '0' : String(fin.distance), l: 'km' },
        { v: fin.explorePct == null ? '0' : String(fin.explorePct) + '%', l: '探索度' },
      ];
      g.textAlign = 'center';
      if (idx !== 1) {
        this._drawShareArch(g, cols, arch); // 卡内含数字行(140..700)
        g.fillStyle = '#5A5A5A'; g.font = '22px sans-serif';
        g.fillText('城瘾 · ' + (fin.date || 'CityFog'), W / 2, 772); // 卡下水印,同预览 sh-wm
      } else {
        this._drawNumsInline(g, cols, W / 2, 368);
        g.fillStyle = '#5A5A5A'; g.font = '22px sans-serif';
        g.fillText('城瘾 · CityFog', W / 2, 436);
      }
      return this._canvasExport(cv);
    });
  },

  _saveSession(fin) {
    try {
      const memory = roamMemory(this);
      this._recoveryArchiveTs = this._recoveryArchiveTs || Date.now();
      const state = memory.readSessionState();
      if (!state.ok) return this._recoveryFailure('足迹存档暂时无法读取，本次记录保留待重试');
      const s = state.sessions;
      const session = {
        ts: this._recoveryArchiveTs, ...fin, finishedAt: this._finishedAt || 0,
        // R9-42:落盘用统一数值合同(number)。展示用的 fin.distance 是 "0.2" 字符串,
        // 直接存会让只读 number 的组件把它当成未知;这里归一成 number,老记录的字符串
        // 由读方(readNonNegative)兼容,0 与未知分开。
        distance: readNonNegative(fin.distance),
        sessionId: exactSessionId(this._roamSid), clientSessionKey: this._clientSessionKey || '',
        photos: archivedSessionPhotos(fin.photos),
        pois: (this._pois || []).filter((p) => p.state === 'done').map((p) => ({
          // id 必须存:_applyLocalPoiFound 靠它跨会话判「已探过」,否则首次发现卡每次进页重弹
          id: p.id, name: p.name, cat: p.cat, icon: p.icon, catLabel: p.catLabel, color: p.color, lat: p.lat, lng: p.lng,
        })),
        // R9-43:按转折抽稀并**永远保留首尾**。旧的 i % 3 固定抽样会丢终点和转折,
        // 回放出来是一条不保真的斜直线。落盘的这条就是回放/分享共用的有效路线。
        track: simplifyTrack(this._track),
      };
      const evicted = evictedSavedPhotos(s, session);
      const existing = s.findIndex(row => row.ts === this._recoveryArchiveTs);
      if (existing >= 0) s[existing] = session; else s.unshift(session);
      if (!memory.writeSessions(s.slice(0, 50))) return this._recoveryFailure('足迹存档保存失败，本次记录保留待重试');
      releaseSavedPhotos(wx, evicted);
      if (!memory.writeReveals(memory.readReveals().concat(this._reveals).slice(-2000)))
        return this._recoveryFailure('足迹轨迹保存失败，本次记录保留待重试');
      this._revealsSaved = true; // onUnload 不再重复追加
      return true;
    } catch (e) { return this._recoveryFailure('足迹存档保存失败，本次记录保留待重试'); }
  },

  /** 原「探索护照」场景已删(内容只剩四项读数,与漫游护照 tab 顶部完全重复)。
   *  起始页/结算页 → 切到漫游护照 tab;运行态切不了 tab(intro 不渲染)→ 开漫游记录。 */
  openPassport() {
    this.openRoamHistory();
  },

  // 自动化探针:清掉一切雾层看纯底图(仅验证脚本用)
  _probeClearFog() {
    if (this._fogFlushTimer) { clearTimeout(this._fogFlushTimer); this._fogFlushTimer = null; }
    this.setData({ fogPolys: [] });
  },
  // 自动化探针:导出雾位图 base64(仅验证脚本用)
  _probeFogPng() {
    if (!this._fogCv) return null;
    const u = this._fogCv.toDataURL('image/png');
    return u.slice(u.indexOf(',') + 1);
  },

  _dbg() {
    return {
      fogMode: this._fogMode || 'none',
      fogFlushes: this._fogFlushCount || 0,
      fogCv: !!this._fogCv,
      fogErr: this._fogErr || null,
      fogPolys: (this.data.fogPolys || []).length,
      fogUpdates: this._fogUpdates || 0,
      reveals: (this._reveals || []).length,
      history: (this._historyReveals || []).length,
      pois: (this._pois || []).length,
      poisReal: (this._pois || []).filter((p) => p.nodeId).length, // /api/map/nearby 报名商家
      poisRoam: (this._pois || []).filter((p) => p._roamId).length, // /api/roam/pois(未部署=0)
      poisSeen: (this._pois || []).filter((p) => p.state === 'seen').length, // 雾揭开过的商家
      hasPlayer: !!this._player,
      // #22 去重自证:卡数 vs 卡上店名去重后的个数,相等即无同名重复
      nearbyCards: (this.data.nearbyPois || []).length,
      nearbyNames: (this.data.nearbyPois || []).map((m) => m.name).join('|'),
      shopsDedup: doneShopCount(this._pois || []),
      shopsRaw: (this._pois || []).filter((p) => p.state === 'done' && p.cat === 'merchant').length,
      stamp: stampProgress(this._pois || []),
      // #23 自证:阈值是否真来自配置 + 结算勋章是否来自后端(而非本地造)
      shopBadgeThreshold: this._shopBadgeThreshold(),
      shopBadgeCfgLoaded: !!this._shopBadgeCfg,
      shopMedalFromServer: !!this._shopMedalFromServer,
    };
  },

  _startClock() {
    this._stopClock();
    this._sessionClock = createSessionClock();
    this._sessionClock.restorePaused(this._elapsedSeconds || 0);
    this._sessionClock.resume();
    this._clock = setInterval(() => {
      this._syncStats();
    }, 1000);
    this._syncStats();
  },
  _stopClock() {
    if (this._sessionClock) this._elapsedSeconds = this._sessionClock.elapsedSeconds();
    if (this._clock) { clearInterval(this._clock); this._clock = null; }
    this._sessionClock = null;
  },
  _syncPlayHud() {
    if (!this._sessionClock) return;
    const playHud = buildPlayHud({
      mode: 'explore',
      elapsedSeconds: this._sessionClock.elapsedSeconds(),
      distanceKm: Number(this._stats.distance) || 0,
      paused: this._sessionClock.isPaused(),
      endHoldPct: this.data.endHoldPct,
    });
    // 时间带冒号,_rollStat 会原样透传 —— 走同一条路只是为了让镜像永远跟得上
    this._stats.time = playHud.right.value;
    this.setData({ playHud });
    this._rollStat('time', playHud.right.value);
  },
  _syncPlayHeader() {
    const event = this.data.boundEvent;
    this.setData({
      playHeader: buildPlayHeader({
        section: '自由漫游',
        modeLabel: event ? '官方活动' : '实时探索',
        title: event ? (event.err ? '活动信息未加载' : event.title) : this.data.currentAddress,
        current: this._stats.explorePct,
        total: 100,
        progressKind: 'percent',
        actionLabel: event && event.err ? '重试活动' : '',
      })
    });
  },

  togglePause() {
    // 长按满 3 秒已经走了结束链路,松手时浏览器还会补一记 tap ——
    // 不吞掉它，结束完会紧接着又把暂停切回去。
    if (this._endHoldTriggered) { this._endHoldTriggered = false; return; }
    const paused = !this.data.paused;
    this._cancelEndHold();
    if (paused) {
      if (this._sessionClock) this._sessionClock.pause();
      this._flushTrackLine();
      this._fitRouteOverview();
    } else {
      if (this._sessionClock) this._sessionClock.resume();
      this._enterWalkZoom();
    }
    this.setData({ paused, endHoldPct: 0 }, () => this._syncPlayHud());
  },

  // 2026-10-27 owner P-35:结算页右上角 × 只关掉结算页,回到漫游首页;不再走 backToSquare(单页栈时会 redirect 到无 tab 栏的动态页)
  closeSettle() {
    if (this.data.screen !== 'arrive') return;
    this.setData({ screen: 'entry-map', introTab: 'start', sceneStack: [], sceneCurrent: null });
    this._loadIntroPassport();
    if (typeof getApp().sendRequest === 'function') this._loadEntryMap(this.data.center);
  },
  // 2026-10-29 V-08 业主:结束漫游后不要跳到「动态」二级页(pages/square/list),跳漫游记录。
  // 先关掉结算页回漫游首页,再打开漫游记录页;从记录页返回落在漫游首页。
  openSettleHistory() {
    if (this.data.screen !== 'arrive') return;
    this.closeSettle();
    this.openRoamHistory();
  },

  // 发现商家:漫游里按调性(标签/城市角色)挑店(discover 列表页)
  goDiscover() { this.openShopStrip(); },
  goSearchMap() { wx.navigateTo({ url: '/pages/searchmap/index' }); },

  // ADA:地图右下常驻集邮入口。纯跳转,不改任何漫游状态/上报。
  goStampAlbum() { this.openScene('roam-stamp-album'); },

  openRoamReward() { this.openScene('game-coupon-wallet'); },
  openRoamTasks() { this.openScene('roam-task-list'); },
  openRoamHistory() { this.openScene('roam-history'); },

  /* ── 工具抽屉(原型 f-more)与它通向的几处 ────────────────────────────────
     三键左边那枚「更多」拉开;九行的去处逐条对到现有场景,没有一行是新编的。
     副标题里的数字现算(附近几处能去 / 几个人在走 / 几枚邮票),不写死。 */
  openRoamMore() {
    const pois = (this._pois || []).filter((p) => p.state !== 'fog');
    const runners = (this._runners || []).length;
    this.setData({
      more: {
        funText: pois.length ? ('这一片有 ' + pois.length + ' 处能去') : '这一片还没点亮',
        runnerText: runners ? (runners + ' 人在这片') : '这会儿没人在附近走',
        stampText: '投一张换一张',
        filterText: this._poiFilterSummary(),
      },
    });
    this.openScene('roam-more');
  },
  /* 抽屉第一行「查找附近好玩的」= 原型 f-fun:开的是横滑白卡,不是 DS 的半屏列表。
     ⚠️ roam-discover 那件半屏没删 —— 游玩页仍在用它(那边原型没有对应屏)。 */
  openRoamDiscover() { this.openShopStrip(); },
  openRoamTasks() { this.openScene('roam-task-list'); },
  openOfficialEvent(e) { const id = e && e.detail && e.detail.id; if (!id || this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/activity/official-detail/index?id=' + id, complete: () => { this._navigating = false; } }); },
  openRoamCoupons() { this.openScene('game-coupon-wallet'); },
  openStampAlbum() { this.openScene('roam-stamp-album'); },
  /* ── BL-01 点位筛选(.psheet 半屏)──────────────────────────────────────────
     范围只改 /api/roam/pois 的 radius;「只看未到过」只在本地藏 marker,不改任何进度。 */
  _poiFilterState() {
    const app = typeof getApp === 'function' ? getApp() : null;
    const owner = app && typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '';
    if (!this._roamPoiFilter || this._roamPoiFilterOwner !== owner) {
      this._roamPoiFilter = roamPoiFilter.readFilter(owner, typeof wx !== 'undefined' ? wx : null);
      this._roamPoiFilterOwner = owner;
    }
    return this._roamPoiFilter;
  },
  _poiFilterSummary() {
    const f = this._poiFilterState();
    return '范围 ' + roamPoiFilter.radiusLabel(f.radiusM) + (f.unvisitedOnly ? ' · 只看未到过' : ' · 全部节点');
  },
  _savePoiFilter(next) {
    this._roamPoiFilter = roamPoiFilter.normalizeFilter(next);
    roamPoiFilter.writeFilter(this._roamPoiFilterOwner, this._roamPoiFilter, typeof wx !== 'undefined' ? wx : null);
    this.setData({ poiFilter: this._roamPoiFilter });
  },
  openPoiFilter() {
    this.setData({ poiFilter: this._poiFilterState() });
    this.openScene('roam-poi-filter');
  },
  onPoiFilterRadius(e) {
    const radiusM = Number(e && e.detail && e.detail.key);
    const prev = this._poiFilterState();
    if (!radiusM || radiusM === prev.radiusM) return;
    this._savePoiFilter(Object.assign({}, prev, { radiusM }));
    if (this._c) this._fetchRoamPois(this._c);
  },
  togglePoiFilterUnvisited() {
    const prev = this._poiFilterState();
    this._savePoiFilter(Object.assign({}, prev, { unvisitedOnly: !prev.unvisitedOnly }));
    this._syncMarkers();
  },
  _clearNearbyRunners() {
    this._runners = [];
    this.setData({ runnerView: null, multiView: null });
    this._syncMarkers();
  },
  _syncPresencePrivacy() {
    const sid = this._roamSid;
    const generation = this._presencePrivacyGeneration || 0;
    if (!sid) { this.setData({ presenceVisible: false }); return Promise.resolve(false); }
    return presencePrivacy.readSharing(getApp(), sid).then(visible => {
      if (String(this._roamSid) !== String(sid) || generation !== (this._presencePrivacyGeneration || 0)) return false;
      this.setData({ presenceVisible: visible });
      if (!visible) this._clearNearbyRunners();
      return visible;
    }).catch(() => {
      this.setData({ presenceVisible: false });
      this._clearNearbyRunners();
      return false;
    });
  },
  togglePresenceSharing() {
    if (this.data.presenceSubmitting) return;
    const apply = visible => {
      this._presencePrivacyGeneration = (this._presencePrivacyGeneration || 0) + 1;
      const sid = this._roamSid;
      this.setData({ presenceSubmitting: true });
      presencePrivacy.setSharing(getApp(), sid, visible).then(saved => {
        if (String(this._roamSid || '') !== String(sid || '')) return;
        this.setData({ presenceVisible: saved });
        this._presenceAt = 0; this._runnersAt = 0;
        if (!saved) this._clearNearbyRunners();
        else if (this._player) {
          Promise.resolve(this._reportPresence(this._player)).then(() => this._fetchRunners(this._player));
        }
        cyToast(saved ? '已开启附近的人位置分享' : '已关闭附近的人位置分享');
      }).catch(error => cyToast(error.message)).finally(() => this.setData({ presenceSubmitting: false }));
    };
    if (this.data.presenceVisible) { apply(false); return; }
    cyModal.show({
      title: '单独同意位置分享', content: presencePrivacy.DISCLOSURE,
      confirmText: '同意并开启', cancelText: '暂不开启',
      success: result => { if (result.confirm) apply(true); },
    });
  },
  openRoamSettings() { this._syncRoamPermission(); this._syncPresencePrivacy(); this.openScene('roam-settings'); },
  /* 抽屉只在地图运行态出现,intro 不渲染 ⇒ 不能切护照 tab(置空 screen = 整页黑屏)。
     去勋章墙页面:navigateTo 保留本页,计时/定位照常,返回即回地图。 */
  openRoamPassport() {
    this.closeScene();
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageP3/pages/badge-wall/index/index', complete: () => { this._navigating = false; } });
  },
  /* 「附近正在走的人」:人在地图上,这一行给最近的那个开半屏;一个都没有就说清楚,
     不做那种点下去毫无反应的行。 */
  /* 抽屉「附近正在走的人」照原型 f-multi:先出「这片街区有几个人在点亮」那张顶部白卡,
     卡上列出每个人的地盘 %;点地图上某个人的头像才是 f-other 那张个人半屏。
     原来这一行直接开了「最近一个人」的半屏 —— 跳过了「这一片有谁」这一层。 */
  openNearestRunner() {
    if (!this.data.presenceVisible) { this.openRoamSettings(); return; }
    if (!(this._runners || []).length) { cyToast('这会儿附近没人在走'); return; }
    this.closeScene();
    this.openMultiView();
  },
  /* 清空本机缓存(原型 setScreen 最后一行)。只清这台机器上的三样,服务端那份不动 ——
     所以「不影响已上传的记录」是真话。不可逆,先确认。 */
  clearRoamLocal() { this.setData({ clearAsk: true }); },
  cancelClearRoamLocal() { this.setData({ clearAsk: false }); },
  confirmClearRoamLocal() {
    if (!require('../../utils/roam-share-snapshot.js').prepareShareRevocation(getApp(), wx)) {
      cyToast('未能保存分享撤销记录，请重试');
      return Promise.resolve(false);
    }
    const ok = roamMemory(this).clearLocal();
    this.setData({ clearAsk: false });
    if (!ok) { cyToast('清不干净，存储读写被拒了'); return Promise.resolve(false); }
    // 第二轮拍板 17:足迹删了,分享出去的足迹链接同步失效。没作废成功要说出来(下次分享前会补做)。
    return require('../../utils/roam-share-snapshot.js').revokeShareSnapshots(getApp(), wx).then((revoked) => {
      cyToast(revoked ? '已清空本机缓存' : '分享过的足迹链接暂未失效，下次分享前会先作废');
      return revoked;
    });
  },

  /* 右键:打卡待确认时先完成打卡,否则开相机(原型的「拍照」通向 f-visit 探店那一步)。 */
  onRoamRightAction() {
    if (this.data.visit && this.data.visit.checkinOk) { this.onCenterTap(); return; }
    this.onTakePhoto();
  },
  toggleRoamPanel() {
    this.setData({ roamPanelExpanded: !this.data.roamPanelExpanded });
  },
  openBadge3d() {
    const m = this.data.medal || {};
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageP3/pages/badge-3d/index?name=' + encodeURIComponent(m.name || '徽章')
      + (m.sub ? '&sub=' + encodeURIComponent(m.sub) : ''), complete: () => { this._navigating = false; } });
  },
  /* 结束本次漫游的入口只剩两个:主键长按 3 秒(onCenterHoldStart)、抽屉里的 endRoamSession。
     原来那枚「结束」方键连同 onRoamStop 一起删了 —— 它和 endRoamSession 是同一段代码的两份。 */
  onRoamDrawerTap(e) {
    const key = e.currentTarget.dataset.key;
    if (key === 'task') this.openRoamTasks();
    else if (key === 'reward') this.openRoamReward();
    else if (key === 'record') this.openRoamHistory();
    else this.openPassport();
  },
  onShareAppMessage(event) {
    const activityShare = activityShareFromEvent(event);
    return activityShare || require('../../utils/roam-menu-share.js').roamMenuShare(); // 不给 imageUrl 微信会截地图(实时位置)当封面
  },

  // P0(2026-09-05 审核):据点详情弹层里的打卡走 /api/city/nodes/{id}/complete,和本页的
  // /api/roam/poi/discover 是两条写路径。以前它成功只改自己的 node.completed,地图 marker 与足迹
  // 不知道 ⇒ 用户关掉弹层看到这一站还是没点亮。这里按它回报的 poiId 把本页的同一站记成 done。
  onScenePoiCompleted(e) {
    const poiId = e && e.detail && e.detail.poiId;
    if (!poiId) return;
    // 只按 _roamId 匹配:p.id 在 merchant 条目上是 regId||9000+i,与城市节点 id 数值撞车会误点亮
    const poi = (this._pois || []).find((p) => p._roamId && String(p._roamId) === String(poiId));
    if (!poi) return;
    // CU-M-54:有券据点打完卡只是「待核销」,券还没发 —— 地图上不能写成已完成,
    // 否则用户以为券到手;重进时服务端 pendingRedeem 回填的是同一档。
    poi.state = (e.detail && e.detail.needRedeem === true) ? 'pendingRedeem' : 'done';
    poi.first = false;
    if (poi._roamId) this._foundRoamPois[poi._roamId] = 1;
    this._syncMarkers();
    if (typeof this._syncSparkCircles === 'function') this._syncSparkCircles();
    if (typeof this._syncGoal === 'function') this._syncGoal();
  },

  _entryIdentity() { return [readSession.memberId(getApp()), (getApp().globalData || {}).authorization || ''].join('|'); },
  _clearEntryRead() {
    this._entryItems = []; this._entryTeams = [];
    this.setData({ introTeamsLive: '附近正在组队', entryMarkers: [], entryCards: [], searchPlaces: [], entryActivity: null, entryTeam: null, entrySheet: '' });
  },
  loginForEntryRead() {
    if (this.data.entryReadAccess === 'denied') return;
    return readSession.retryLogin(this, getApp(), function () { this._loadEntryMap(this.data.center); });
  },
  _loadEntryMap(center) {
    if (typeof getApp().sendRequest !== 'function') return;
    const c = center || FALLBACK;
    const owner = this._entryIdentity();
    const seq = (this._entrySeq = (this._entrySeq || 0) + 1);
    if (this._entryReadOwner !== owner) this._clearEntryRead();
    this._entryReadOwner = owner;
    if (!readSession.memberId(getApp())) { this.setData({ entryReadAccess: 'login', entryLoadError: false }); return; }
    this._entryErrors = { activities: false, teams: false };
    this.setData({ entryLoadError: false, entryReadAccess: '' });
    const settle = (layer, failed) => {
      if (this._unloaded || seq !== this._entrySeq || owner !== this._entryIdentity()) return;
      this._entryErrors[layer] = !!failed;
      this.setData({ entryLoadError: entryMap.hasLayerError(this._entryErrors) });
      this._syncEntryMap();
    };
    const auth = (res) => {
      if (this._unloaded || seq !== this._entrySeq) return true;
      if (owner !== this._entryIdentity() || res && res.reason === 'sessionChanged') {
        this._entrySeq += 1;
        this._clearEntryRead();
        this.setData({ entryReadAccess: readSession.memberId(getApp()) ? '' : 'login', entryLoadError: false });
        if (!this._hidden && readSession.memberId(getApp())) this._loadEntryMap(c);
        return true;
      }
      const code = readSession.failureStatus(res);
      if (code !== 401 && code !== 403) return false;
      this._entrySeq += 1;
      this._clearEntryRead();
      this.setData({ entryReadAccess: code === 401 ? 'login' : 'denied', entryLoadError: false });
      return true;
    };
    req('/api/roam/entry/nearby', 'GET', { lat: c.lat, lng: c.lng, radius: 3000 }, { silentError: true }).then((res) => {
      if (auth(res)) return;
      const data = apiOk(res) && res.data;
      const ok = data && isRecordList(data.items);
      if (ok) this._entryItems = data.items.filter((row) => row && row.kind !== 'hangout');
      settle('activities', !ok);
    });
    req('/api/team/nearby', 'GET', { lat: c.lat, lng: c.lng, radius: 3000 }, { silentError: true }).then((res) => {
      if (auth(res)) return;
      const ok = apiOk(res) && Array.isArray(res.data);
      if (ok) this._entryTeams = res.data.filter(Boolean);
      settle('teams', !ok);
    });
  },
  _syncEntryMap() {
    const app = getApp();
    const getImgUrl = typeof app.getImgUrl === 'function' ? app.getImgUrl.bind(app) : (url) => url;
    const view = entryMap.build(this._entryItems, this._entryTeams, this.data.entryLayers, getImgUrl);
    this.setData({ entryMarkers: view.markers, entryCards: view.cards, searchPlaces: view.searchPlaces, introTeamsLive: this._entryTeams.length ? '附近 ' + this._entryTeams.length + ' 支队伍在组队' : '附近正在组队' }); if (!view.cards.length && this._entryErrors && !this._entryErrors.activities) this._loadEntryFallback(); else if (view.cards.length) this.setData({ entryFarCards: [] }); // 2026-10-28 附近为空走兜底
  },
  _showEntrySheet(entrySheet, entryActivity) {
    this.setData({ entrySheet, entryActivity: entryActivity || null, entryTeam: null, entrySheetView: 'main', entrySheetIn: false }, () => {
      wx.nextTick(() => {
        if (this.data.entrySheet === entrySheet) this.setData({ entrySheetIn: true });
      });
    });
  },
  openEntryRoamSheet() { this._showEntrySheet('roam'); },
  openEntryLayers() { this._showEntrySheet('layers'); },
  closeEntrySheet() { this.setData({ entrySheet: '', entryActivity: null, entryTeam: null, entrySheetView: 'main', entrySheetIn: false }); },
  backEntrySheetView() { this.setData({ entrySheetView: 'main', entryTeam: null }); },
  toggleEntryLayer(e) {
    const key = e.currentTarget.dataset.key;
    if (key !== 'activities' && key !== 'teams' && key !== 'posts') return;
    const entryLayers = Object.assign({}, this.data.entryLayers, { [key]: !this.data.entryLayers[key] });
    this.setData({ entryLayers }, () => this._syncEntryMap());
  },
  openEntryActivityList() { wx.navigateTo({ url: '/pages/activity/list/index' }); },
  onEntryCardTap(e) { const d = e.currentTarget.dataset; if (d.kind === 'far-topic') { wx.navigateTo({ url: '/pages/topic/index/index?id=' + d.id }); return; } this.openEntryActivity(d.kind, d.id); },
  openEntryActivity(kind, id) {
    const row = (this._entryItems || []).find((item) => item && item.kind === kind && String(item.id) === String(id));
    if (!row) return;
    this._showEntrySheet('activity', entryMap.activityView(row, this._entryTeams, getApp().getImgUrl.bind(getApp())));
  },
  onEntryMarkerTap(e) {
    const hit = entryMap.parseMarker(e.detail && e.detail.markerId);
    if (!hit) return;
    if (hit.kind === 'team') { this._openEntryTeamById(hit.id); return; }
    if (hit.kind === 'post') {
      const row = (this._entryItems || []).find((item) => item && String(item.id) === String(hit.id));
      if (row) this.openEntryActivity(row.kind, row.id);
      return;
    }
    this.openEntryActivity(hit.kind, hit.id);
  },
  openEntryActivityDetail() {
    const activity = this.data.entryActivity;
    if (!activity) return;
    const path = activity.topicId
      ? '/pages/topic/index/index?id=' + encodeURIComponent(activity.topicId)
      : '/pages/activity/detail/index?id=' + encodeURIComponent(activity.id);
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: path, complete: () => { this._navigating = false; } });
  },
  openEntryTeam(e) {
    const teamId = e.currentTarget.dataset.id;
    const team = this.data.entryActivity && this.data.entryActivity.teams.find((row) => String(row.teamId) === String(teamId));
    if (team) this.setData({ entryTeam: team, entrySheetView: 'team' });
  },
  openEntryTeamAction() {
    const team = this.data.entryTeam;
    if (!team) return;
    if (!team.canApply) { this.openEntryActivityDetail(); return; }
    this._openEntryTeamById(team.teamId);
  },
  _openEntryTeamById(teamId) {
    if (!teamId || this._navigating) return;
    const team = (this._entryTeams || []).find((row) => row && String(row.teamId) === String(teamId));
    let url = '/subpackageRoam/nearby/index?teamId=' + encodeURIComponent(teamId);
    if (team && team.latitude != null && team.latitude !== '' && team.longitude != null && team.longitude !== '') {
      url += '&lat=' + encodeURIComponent(team.latitude) + '&lng=' + encodeURIComponent(team.longitude) + '&radius=3000';
    }
    this._navigating = true; wx.navigateTo({ url, complete: () => { this._navigating = false; } });
  },
  openEntryRoamRules() { this.setData({ entrySheetView: 'rules' }); },
  _onEntryRegionChange(e) {
    const detail = (e && e.detail) || {};
    const phase = detail.phase || detail.type || (e && e.type);
    const causedBy = detail.causedBy || (e && e.causedBy);
    if (phase !== 'end' || (causedBy !== 'gesture' && causedBy !== 'drag' && causedBy !== 'scale') || !detail.centerLocation) return;
    const center = { lat: detail.centerLocation.latitude, lng: detail.centerLocation.longitude };
    if (this._entryRegionTimer) clearTimeout(this._entryRegionTimer);
    this._entryRegionTimer = setTimeout(() => {
      this._entryRegionTimer = null;
      this.setData({ center });
      this._loadEntryMap(center);
    }, 600);
  },


  /* ===== 2026-10-22 业主:护照方卡点开 —— 方卡原地放大成一张大卡(整面渐变 + 底部模糊),
     放大到位后照原样执行这张卡的动作;只加动画,不改版式、不加步骤。减少动态效果时直接执行。 */
  _ppExpand(key) {
    if (this.data.reducedMotion || this._ppBusy || typeof wx.createSelectorQuery !== 'function') return false;
    const tile = ((this.data.passport && this.data.passport.tiles) || []).find((t) => t.key === key);
    if (!tile) return false;
    this._ppBusy = true;
    const idx = this.data.passport.tiles.indexOf(tile);
    wx.createSelectorQuery().in(this).selectAll('.pp-tile__box').boundingClientRect().exec((res) => {
      const r = res && res[0] && res[0][idx];
      if (!r || this._hidden) { this._ppBusy = false; this.onPassportTileTap({ ppExpanded: true, currentTarget: { dataset: { key } } }); return; }
      let win = { windowWidth: 375, windowHeight: 667 }, cap = null;
      try { win = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync(); } catch (err) {}
      try { cap = wx.getMenuButtonBoundingClientRect(); } catch (err) {}
      const top = cap && cap.bottom ? cap.bottom + 12 : 96;
      const w = win.windowWidth - 32;
      const h = Math.min(win.windowHeight - top - 40, w * 1.25);
      const box = (x, y, bw, bh, rad) => 'left:' + x + 'px;top:' + y + 'px;width:' + bw + 'px;height:' + bh + 'px;border-radius:' + rad + 'px;';
      const ease = 'transition:left .46s cubic-bezier(.2,.9,.25,1),top .46s cubic-bezier(.2,.9,.25,1),width .46s cubic-bezier(.2,.9,.25,1),height .46s cubic-bezier(.2,.9,.25,1),border-radius .46s ease;';
      this.setData({ ppOpen: { show: true, on: false, icon: tile.icon, title: tile.title, value: tile.value, style: box(r.left, r.top, r.width, r.height, 14) + 'transition:none;' } });
      setTimeout(() => this.setData({ 'ppOpen.on': true, 'ppOpen.style': box(16, top, w, h, 28) + ease }), 30);
      setTimeout(() => {
        this.onPassportTileTap({ ppExpanded: true, currentTarget: { dataset: { key } } });
        setTimeout(() => { this.setData({ 'ppOpen.show': false, 'ppOpen.on': false }); this._ppBusy = false; }, 380);
      }, 560);
    });
    return true;
  },

  /* 2026-10-23 弹层动效:主题半屏原来类名是静态 is-in,开合都没有过渡(一帧出现、一帧消失)。
     现在挂载时是起点态,渲染完再置 topicIn 升起;关闭先落下(half 档 250ms = --cy-motion-sheet-out)再卸载。
     照片条贴在半屏上沿:共用样式按 70vh 定位,半屏现在贴内容高,量真实高度再放(同 subpackageRoam/nearby)。 */
  _topicEnter() {
    if (this._topicOutTimer) { clearTimeout(this._topicOutTimer); this._topicOutTimer = null; }
    const show = () => { if (!this._unloaded && this.data.topicView) this.setData({ topicIn: true }); };
    if (typeof wx.createSelectorQuery === 'function') {
      try {
        wx.createSelectorQuery().in(this).select('#roam-topic-sheet').boundingClientRect((r) => {
          if (r && r.height && !this._unloaded) this.setData({ tstripStyle: 'bottom:' + Math.round(r.height + 10) + 'px;' });
        }).exec();
      } catch (e) { /* 量不到就用共用样式的位置 */ }
    }
    if (this.data.reducedMotion) show(); else setTimeout(show, 20);
  },
  _topicLeave() {
    if (!this.data.topicView) return;
    if (this.data.reducedMotion) { this.setData({ topicView: null, topicIn: false }); return; }
    this.setData({ topicIn: false });
    if (this._topicOutTimer) clearTimeout(this._topicOutTimer);
    this._topicOutTimer = setTimeout(() => {
      this._topicOutTimer = null;
      if (!this._unloaded && !this.data.topicIn) this.setData({ topicView: null });
    }, 250);
  },
  /* 2026-10-28 业主:「附近活动」总是有的 —— 3 公里内没有就放同城(20 公里,接口上限)的,
     同城也没有就放其他城市的公开主题。只进底部卡片带,不往地图上打点(离得远的点放在附近地图上是误导)。 */
  _loadEntryFallback() {
    const seq = this._entrySeq, c = this.data.center || FALLBACK, owner = this._entryIdentity();
    if (this._entryFallbackSeq === seq) return;
    this._entryFallbackSeq = seq;
    const app = getApp(), img = typeof app.getImgUrl === 'function' ? app.getImgUrl.bind(app) : (url) => url;
    const stale = () => this._unloaded || seq !== this._entrySeq || owner !== this._entryIdentity();
    req('/api/roam/entry/nearby', 'GET', { lat: c.lat, lng: c.lng, radius: 20000 }, { silentError: true }).then((res) => {
      if (stale()) return;
      const data = apiOk(res) && res.data;
      const rows = data && isRecordList(data.items) ? data.items.filter((row) => row && row.kind !== 'hangout') : [];
      if (rows.length) {
        this._entryItems = (this._entryItems || []).concat(rows.filter((row) => !(this._entryItems || []).some((it) => it.kind === row.kind && String(it.id) === String(row.id))));
        this.setData({ entryFarCards: rows.slice(0, 8).map((row) => entryMap.activityView(row, this._entryTeams, img)) });
        return;
      }
      req('/api/topic/list', 'POST', { pageNum: 1, pageSize: 8, sort_type: 1 }, { silentError: true, auth: false }).then((r2) => {
        if (stale()) return;
        const list = apiOk(r2) && r2.data && isRecordList(r2.data.rows) ? r2.data.rows : [];
        this.setData({ entryFarCards: list.map((t) => ({ key: 'far-topic-' + t.id, id: t.id, kind: 'far-topic', title: String(t.name || '未命名主题'),
          cover: img(String(t.imgArr || t.imgUrl || '').split(/[,;]/)[0].trim()), tag: Number(t.productType) === 2 ? '自由探索' : '城市定向', meta: String(t.city || t.addressName || '') })) });
      });
    });
  }
});

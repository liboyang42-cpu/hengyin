// 城瘾 · 游玩页(深色地图沉浸版)
// 视觉真值:设计原型工具「城瘾游玩页.dc.html」;对接说明见 docs/游玩页-深色地图沉浸版-对接说明.md
// 数据:GET /api/play/nodes(现网) — 缺坐标/章节/模板呈现字段时做降级;扫码 /checkin、答题 /answer、到达 /arrive。
const cyModal = require('../../utils/modal.js');
const cyLoading = require('../../utils/loading.js');
const cyToast = require('../../utils/toast.js');
const motion = require('../../utils/motion.js');
const app = getApp();
const bgTracker = require('../../utils/location/bg-tracker.js');
let playLocationOwnerSequence = 0; // 每次实际持有使用独立key，旧页面不能释放新会话。
const analytics = require('../../utils/analytics.js');
const { activityShareFromEvent } = require('../../utils/activity-share.js');
const { bundledMerchantGameAudioPath } = require('./utils/merchant-game-assets.js');
const geo = require('../../utils/geo.js'); // 纯地理/时间工具(haversine/营业窗判定),可单测
const recommend = require('./utils/play-recommend.js'); // P0 推荐评分(纯函数,已单测)
const { buildPlayRoute, createSessionClock, formatElapsed } = require('../../utils/play-ui-contract.js');
const { readPausedRun, writePausedRun, clearRunSession, saveServerPausedRun, clearServerRunSession, readServerPausedRun, newerPausedRun } = require('./utils/play-run-session.js');
const { resolveLeadMemberTicket } = require('./utils/lead-ticket-scan.js');
const { seatTarget, seatKey, seatRecovery, seatChoice, captureLogin, loginCurrent } = require('../../utils/registration-seat.js');
const { classifyVerificationOutcome, createVerificationWorkflow } = require('../../utils/merchant-verification.js');
const { routeColorForKey, ROUTE_BORDER_COLOR } = require('../../utils/play-visual-tokens.js');
const { getPlayMapState, getPlayRouteNodeState } = require('../../utils/play-state-contract.js');
const gameSessionClient = require('../../utils/game-session-client.js');
const orientLifecycle = require('./utils/orient-lifecycle.js');
const arrivalGuardView = require('./utils/arrival-guard-view.js');
const guardAppeal = require('./utils/guard-appeal.js');
const playBoardView = require('./utils/play-board-view.js');
const { id: precisePlayId } = require('../../utils/orient-score-view.js');
const journeyCheckRecovery = require('./utils/journey-check-recovery.js');
const { reportBlocksParseFailure } = require('./utils/chapter-blocks-health.js');   // P1-021 块流回落埋点
const audioSession = require('./utils/audio-session.js');       // 音频打断/恢复 + 长音频不熄屏
const { createPoller } = require('./utils/poller.js');           // 带领场进度:退避轮询
const playAccuracy = require('./utils/play-accuracy.js');       // 到达定位精度阈值(80m)与提示
const {
  normalizePlayerProjection,
  buildPlayerChoiceInput,
  buildPlayerSubmissionInput,
  buildPlayerRoleConfirmInput,
  buildPlayerHintInput,
  buildPlayerRevealInput,
  mergeConfirmedReceipt,
  completedRouteSegment,
  normalizePlayerPendingWrite,
  normalizePlayerPendingReceiptIndex,
} = require('./utils/player-game-module.js');
const { readReducedMotion, writeReducedMotion } = require('../../utils/motion-preference.js');
const { chinaParts, formatDayDots, toTimestamp } = require('../../utils/datetime.js');
const npcFigure = require('./utils/npc-figure.js');   // 低模人形:几何代码里生成,不依赖模型资产
const { pickPlayKit, pickJourneyCheck, checkReceiptView, serverAction, serverPayload, dailySignRevealState, applyStoryVars } = require('./utils/playkit-view.js'); // v5.1 新玩法:会话视图→可渲染 kit
const { canContinue, feedbackWritable, submitAlbum } = require('./utils/playkit-r5-segments.js');

const THOUGHT_SYNC_WAIT_MS = 8000;   // 伏笔/步数同步最长等待,超时不拦玩家动作
const RUN_HOLD_MS = 3000;   // 主键长按结束:与漫游页 END_HOLD_MS 同一口径

const { buildMilestone } = require('./utils/play-milestone.js'); // C 分享卡里程碑轴(纯函数,已单测)
const { fromHttpStatusAbnormal } = require('../../utils/transport/failure-envelope.js');
const { getScene } = require('../../utils/scene-registry.js');
const { resolveOpenState } = require('./utils/play-open-state.js');
const { pushScene, popScene, currentScene, exitDecision } = require('../../utils/scene-stack.js');
const finishRouteRecommendation = require('./finish-route-recommendation.js');
const { buildPlayRecap } = require('./recap.js'); const { buildFinishMedals } = require('./utils/finish-medals.js'); // 2026-10-22 完成页勋章堆
const { summarizePuzzleScores } = require('./utils/play-puzzle-summary.js'); const chapterFlip = require('./utils/chapter-flip-data.js'); // 2026-10-28 章节翻转过场
const { normalizeAtmosphere, atmosphereClass, PRESETS: CHAPTER_ATMOSPHERES } = require('../../utils/chapter-atmosphere.js');
const CHAPTER_ATMOSPHERE_VALUES = CHAPTER_ATMOSPHERES.map((item) => item.value);
const {
  normalizeRouteState,
  applyRouteStateToNodes,
  resolveNextRouteNode,
  orderedJourneyNodes,
  buildVisibleRoutePath,
  createRouteActionStore,
  scopedRouteActionStore,
  isRouteConflict,
} = require('./utils/play-route-state.js');
// 顶部浮层的纵向起点:必须由微信胶囊的真实位置算出。原来 .play-header-wrap 写死 top:104rpx,
// 实拍就是压在胶囊底下、「更多」被吃掉半个字(2026-08-04)。漫游页早就用这套,这里对齐。
const { resolveMenuChrome } = require('../../utils/nav-safe-area.js');
const { isRecord, isRecordList } = require('../../utils/response-shape.js');

// 氛围跟着正文流走,读到哪一段就是哪一段的基调。配色沿用章节那套预设,
// 野值不往下带 —— 服务端已经拦过一道,客户端不拿没见过的名字去渲染。

// canvas(足迹卡截图)只能吃字面色,拿不到 CSS 变量 ⇒ 下面两组必须是字面值,
// 取值与 style/tokens.wxss 及 index.wxss 的叙事层色板保持同步。
// mode2 开卡包的四拍时长(ms)。这套数是在动效台上逐档试出来的定稿,别单独改一个:
// 蓄力太短就没有「憋住」的张力,发牌间隔一小就退回「一起掉」。
const PACK_CHARGE = 1000;   // ② 蓄力:摇 + 四周压暗
const PACK_TEAR = 640;      // ③ 横撕:封口条向右扯走 + 撕口自左向右露出 + 爆炸
const DEAL_STAGGER = 240;   // ④ 每张卡的发牌间隔(动效台 120ms × 0.5× 速度)
const DEAL_FLIGHT = 1600;   // ④ 单张飞行总时长(动效台 800ms × 0.5×;弧顶在 52%,见 index.wxss)
const DEAL_ARC = 0.52;      // 弧顶所处的进度,WXSS 两段 transition 的分界必须与之一致
// 弧顶态的缩放,必须与 index.wxss 的 .fx-tile.is-flying 一致:决定夹取时按多大的卡片留边。
// (待机态的 scale 不需要在这里出现 —— 量尺寸时用 .is-measuring 把 transform 整个关掉,
//  比从变换后的框反推可靠得多:那个框同时吃了 scale 和 rotate,反推必然带误差。)
const DEAL_MID_SCALE = 0.78;
// 扇形半跨:4 家和 6 家都用同一个角。原来 4 家 36°/6 家 54° 摊得太开,
// 收到 ±14° 之后是「从包口窄窄地喷出来」,不是「甩出去一个扇面」。
const DEAL_SPREAD = 14;
// ⑤ 点卡 → hero 展开(2026-09-05,参考 cibby · shelf):卡片从格子原位放大飞到屏幕上部,
//   背景换成这家的模糊图,文字在卡片落定后依次上滑。WXSS .fx-hero__card 的 transform 过渡
//   时长必须与此同步(play-seat-hero-contract 锁)。
const HERO_DUR = 420;
/* 故事流「快滑」阈值:每帧位移超过它就不就地显形,一律等停下来那一刻再补。
   18px/frame 是样机定的那条线 —— 再高文字会在飞驰中一闪而过,再低慢滑也被判成快滑。 */
const STORY_FAST_PX = 18;
// ③′ 拖拽撕开(2026-08-20):横撕从「点一下自动播」升级为「手指跟随」。
// tap 四拍原样保留 —— 拖拽是并行入口,不动 PACK_* 那组定稿。
const TEAR_MAX_PX = 170;    // 撕满需要的水平拖距(px)≈ 卡包宽的 80%
const TEAR_DONE_PCT = 60;   // 松手时撕过这个进度就算撕开,不足弹回
const TEAR_SNAP = 180;      // 松手后补完/弹回的过渡时长(ms),与 WXSS .is-snap 同步
// 爆炸强度(动效台 180):光条数和碎片飞行距离都按它缩放。
const BLAST_POWER = 1.8;
const BLAST_SHARDS = 60;
// 爆炸的镭射色谱:碎片/光条都从这条色谱取色,不是随机彩虹 ——
// 同一条色谱才读作「包装膜炸碎了」,随机彩虹读作「撒了彩纸」。
const HOLO = ['#BFE8E0', '#C9D8EE', '#D8C8E8', '#F5D6C8', '#F2E3B8'];

const BRAND = '#FFFFFF';                                              // = --cy-color-play-accent
// Canvas 不能读取 WXSS 变量；逐字同步 style/tokens.wxss 的 play-story 色板。
// 这组只服务 Figma 220:1415/1437 完赛路线缩略图与保存卡，不改变游玩主舞台主题。
const PLAY_STORY_CARD = Object.freeze({
  page: '#FFFFFF',
  surface: '#F6F7FA',
  text: '#111111',
  muted: '#5E6470',
  line: '#8B8F99',
  accent: '#4B46F5',
  fade: '#8C91A0',
  ring: '#FFFFFF',
  footer: 'rgba(17,17,17,.35)',
});

// 营业态是「状态」,即便在沉浸页也必须服从系统(迁移矩阵 §2.3)。
// 这三个值只进 wxml 的 inline style(<view style="background:{{openDot}}">),CSS 变量在那里能正常解析。
// 营业态三色已随换算逻辑一起搬进 utils/play-open-state.js —— 本文件不再自留副本。

// S9 暗色底图配置已抽到 utils/map-style.js(roam 页共用同一份,避免只改一页导致半暗半亮)。
// 接入文档见 components/cy/free-map/DARK_MAP.md。
const { MAP_STYLE: MAP_DARK } = require('../../utils/map-style.js');
const storyAudio = require('./utils/story-audio.js');
const storyScene = require('./utils/story-scene.js');
const lifeArchive = require('./utils/life-archive.js');
const storyResume = require('./utils/story-resume.js');
const STORY_SOUND_KEY = 'cy_story_sound_off';
/* 内心声音(原型 VOICES 表原值);第 3 档异常时两个声音改名(越往后越像机器)。 */
const STORY_VOICE_COLORS = { 规矩: '#B9A7FF', 窗外: '#5CD6C8', 心: '#FF9F80', 精密: '#7EB6FF', 咔哒: '#FF6B6B' };   /* ds-ok 原型 VOICES */
const STORY_VOICE_ALT = { 规矩: '进程', 窗外: '传感器' };
/** 咔哒改写记录 → 故事流里那几句内心声音(会话视图与 /nodes 回包共用一份写法) */
/** 初始状态页的几行:作者开了的精力 / 幸运 + 可见属性,取回执里的当前值 */
function startStateRows(routeState, vitalsOn) {
  const rows = [];
  const on = vitalsOn || {};
  if (on.hp && routeState && routeState.hp != null) rows.push({ label: '精力', value: routeState.hp });
  if (on.luck && routeState && routeState.luck != null) rows.push({ label: '幸运', value: routeState.luck });
  ((routeState && routeState.attributes) || []).forEach((a) => rows.push({ label: a.label, value: a.value }));
  return rows;
}

/** 开场页那一句副标:主题简介的第一句(40 字内),没有就不放 */
function introLine(desc) {
  // 只取第一行:换行是作者分的段,压成空格会把下一行的半句拼进来,
  // 40 字一刀切在「｜」上,看着像没删干净的光标(2026-10-27 南京路开场页实测)。
  const first = String(desc || '').split(/\n/).map((x) => x.trim()).find(Boolean) || '';
  const text = first.replace(/\s+/g, ' ');
  const m = text.match(/^[^。！？!?]{1,40}[。！？!?]?/);
  return m ? m[0].replace(/[\s｜|·、，,：:；;—-]+$/, '') : '';
}

function kacchaLines(hits) {
  return (hits || []).map((h) => ({
    who: '咔哒', color: STORY_VOICE_COLORS['咔哒'],
    text: String(h.voice || '这一栏已经填好了。')
      .replace(/\{picked\}/g, String(h.picked || '')).replace(/\{assigned\}/g, String(h.assigned || '')),
  }));
}
// 地点卡的兜底说明;故事玩法入口卡不借用它(那是「走到某处」的话,玩法不需要走)。
const NODE_DESC_FALLBACK = '走到这里，留意四周——故事就藏在细节里。';
/* 入口卡按玩法类型换图标和动作词(2026-09-23 用户:拍照类要是相机)。 */
const GAME_KINDS = {
  photo: { icon: 'camera', label: '在现场 · 拍一张', go: '打开相机' },
  note: { icon: 'edit', label: '留一句话', go: '去写' },
  walk: { icon: 'walk', label: '走一走', go: '开始' },
  play: { icon: 'play', label: '故事玩法', go: '进入' },
};
/** 玩法配置 → 入口卡类型。配置读不出来就按普通玩法。 */
function gameKindOf(vm, cfg, previewType) {
  const on = (k) => !!(cfg && cfg[k] && cfg[k].enabled);
  if (vm === 2 || on('photoCheck') || previewType === 'photocheck' || (on('qa') && cfg.qa.mode === 'shot')) return 'photo';
  if (on('note') || previewType === 'note') return 'note';
  if (on('steps') || previewType === 'steps') return 'walk';
  return 'play';
}
/** 故事流里的玩法入口卡(原型 fsEntry;2026-09-24 用户:居中,不写「故事玩法」)。 */
function storyGameEntry(n, mood) {
  const k = GAME_KINDS[n.gameKind || gameKindOf(0, null, n.previewKit && n.previewKit.type)] || GAME_KINDS.play;
  return { kind: 'game', nodeId: n.nodeId, text: n.name || '开始玩法',
    sub: n.hookText || (n.description !== NODE_DESC_FALLBACK ? n.description : '') || '',
    cover: String(n.imgUrl || '').split(',')[0], mood: mood, icon: k.icon, go: k.go };
}

/** 这一站玩法的呈现方式:真实游玩读服务端投影进节点配置的 present;试玩读草稿预览 kit 上按编辑器默认表算好的那一份 */
function presentOf(n) {
  return (n && n.advancedConfig && n.advancedConfig.present) || (n && n.previewKit && n.previewKit.present) || '';
}
// 服务端 UUID_PATTERN 只收 v4 UUID —— 自造的 'shop-xxx' 会被入参校验直接判 INVALID_REQUEST
/** 卡片转到什么角度才该露出侧脊。
 *  正面/背面朝前时侧脊在几何上是零宽,但实测 ry=0 仍会在卡片两侧漏出两条竖条,
 *  所以按角度显式收掉 —— 别指望 3D 变换刚好把它压没。 */
function turnedAt(ry) {
  const a = ((Number(ry) || 0) % 180 + 180) % 180;
  return a > 8 && a < 172;
}

function uuid4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : ((r & 0x3) | 0x8)).toString(16);
  });
}

function req(url, method, data, header, silentError) {
  const basic = method === 'POST' && (['/api/play/answer', '/api/play/checkin', '/api/play/arrive', '/api/play/photo', '/api/play/puzzle/reveal', '/api/play/sensor-result'].includes(url) || /^\/api\/play\/preference\/[1-9][0-9]*\/submit$/.test(url));
  if (basic && data && typeof data._intentCurrent === 'function' && !data._intentCurrent()) return Promise.resolve({ obsolete: true });
  if (basic && data && data._intentOwner != null && data._intentOwner !== String((app.getUserID && app.getUserID()) || '')) return Promise.resolve({ obsolete: true });
  if (basic && data && data.receiptOnly) {
    return req('/api/play/action-receipt', 'GET', { activityId: data.activityId, topicId: data.topicId, nodeId: data.nodeId, routeActionId: data.routeActionId }, undefined, true).then(response => {
      return response.intentTerminal ? response : Object.assign({}, response, { code: 520, intentUnknown: true, httpFail: true });
    });
  }
  if (basic && data && data.routeSessionId == null) {
    const owner = data._intentOwner;
    return req('/api/play/action-context', 'GET', { activityId: data.activityId, topicId: data.topicId, nodeId: data.nodeId }, undefined, true).then(response => {
      if (typeof data._intentCurrent === 'function' && !data._intentCurrent()) return { obsolete: true };
      if (owner != null && owner !== String((app.getUserID && app.getUserID()) || '')) return { obsolete: true };
      if (!(response.code == 200 || response.code == '200') || !response.data) return response;
      const context = response.data;
      if (data._expectedSessionId && String(context.routeSessionId) !== data._expectedSessionId) return { code: 409, intentNotExecuted: true, msg: '原游玩局已变化，请确认后重新操作' };
      const fields = Object.assign({}, context, { expectedRouteVersion: data.expectedRouteVersion == null ? context.expectedRouteVersion : data.expectedRouteVersion });
      if (typeof data._bindRouteContext !== 'function') return { code: 520, httpFail: true, msg: '原命令身份未保存，请重新进入确认结果' };
      try { data._bindRouteContext(fields); } catch (error) { return { code: 520, httpFail: true, msg: error.message }; }
      if (url === '/api/play/answer') return req('/api/play/answer', 'POST', data, header, silentError);
      if (url === '/api/play/checkin') return req('/api/play/checkin', 'POST', data, header, silentError);
      if (url === '/api/play/arrive') return req('/api/play/arrive', 'POST', data, header, silentError);
      if (url === '/api/play/photo') return req('/api/play/photo', 'POST', data, header, silentError);
      if (url === '/api/play/puzzle/reveal') return req('/api/play/puzzle/reveal', 'POST', data, header, silentError);
      if (url === '/api/play/sensor-result') return req('/api/play/sensor-result', 'POST', data, header, silentError);
      return req('/api/play/preference/' + data.nodeId + '/submit', 'POST', data, header, silentError);
    });
  }
  if (basic && data && data._intentOwner != null && data._intentOwner !== String((app.getUserID && app.getUserID()) || '')) return Promise.resolve({ obsolete: true });
  return new Promise((resolve) => {
    const settle = response => {
      if (basic && !response.intentTerminal && !response.intentNotExecuted && !response.obsolete) {
        response = Object.assign({}, response, { code: 520, httpFail: true, intentUnknown: true,
          msg: response.msg || '结果还没确认，请稍后再查看' });
      }
      if (basic && data && typeof data._markIntentUnknown === 'function'
          && (response.netFail || response.httpFail || Number(response.code) === 520)) {
        try { data._markIntentUnknown(); } catch (error) { response.msg = error.message + '；原结果仍待确认'; }
      }
      resolve(response);
    };
    app.sendRequest({
      url, method, data, header, hideLoading: true, silentError: !!silentError,
      success: (res) => settle(basic && res && Number(res.code) === 520 ? Object.assign({}, res, { httpFail: true, intentUnknown: true }) : res || {}),
      // request-client 对 HTTP 非 200 只调 successStatusAbnormal；必须结束 Promise，
      // 否则首屏加载与节点提交的恢复逻辑会永久等待。
      // HTTP 已返回并不等于请求没送达；尤其 POST 可能已经执行，不能开放盲重试。
      // 强制覆盖 body.code，避免网关错误体里的 200/402 被误当成业务成功/购票态。
      successStatusAbnormal: (res) => settle(fromHttpStatusAbnormal(res)),
      // netFail 让调用方能区分「请求没送到」与「服务端说不行」—— 二者都走 code!=200,
      // 光看 code 分不出来(submitGame 曾因此把断网当成答错)。
      fail: () => settle({ code: 'fail', netFail: true, msg: '网络异常，请重试' })
    });
  });
}

function isScanCancelled(error) {
  const message = (error && error.errMsg) || '';
  return /cancel/i.test(message);
}

function parseGameCompletedAt(value) {
  const parsed = toTimestamp(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function matchingPlayerTerminalReceipt(result, pending, expectedOutcome) {
  const command = pending && pending.command;
  const receipt = result && result.receipt;
  const activityId = command ? command.activityId : (pending && pending.activityId);
  if (!pending || !receipt) return null;
  const receiptId = Number(receipt.receiptId);
  const revision = Number(receipt.revision);
  if (!Number.isSafeInteger(receiptId) || receiptId <= 0
    || activityId == null || receipt.activityId == null
    || String(receipt.activityId) !== String(activityId)
    || !pending.requestId || (command && command.requestId !== pending.requestId)
    || receipt.requestId !== pending.requestId
    || !pending.action || (command && command.action !== pending.action)
    || receipt.action !== pending.action
    || String(receipt.outcome || '').toUpperCase() !== expectedOutcome
    || receipt.revision == null || receipt.revision === ''
    || !Number.isSafeInteger(revision) || revision < 0) return null;
  if (result.requestId != null && result.requestId !== pending.requestId) return null;
  return receipt;
}

function normalizePlayerRevealText(value) {
  if (typeof value !== 'string') return '';
  const text = value.trim();
  return text && text.length <= 200 ? text : '';
}

// 2026-09-18 UI-20 返修:发布端历史默认章节名是英文占位「Chapter N」,原样上屏就是玩家看到的
// 「Chapter 1」。占位名一律显示成「第 N 章」(N 取占位里的数字,没有就用章节序号);
// 真实章节名原样保留;空名回落到调用方给的兜底文案。
const CHAPTER_PLACEHOLDER_NAME = /^chapter\s*(\d+)?$/i;
function displayChapterName(raw, index, fallback) {
  const name = String(raw == null ? '' : raw).trim();
  const matched = CHAPTER_PLACEHOLDER_NAME.exec(name);
  if (matched) return '第 ' + (matched[1] ? Number(matched[1]) : index + 1) + ' 章';
  return name || fallback || '';
}

function isChapterPlaceholderName(raw) {
  return CHAPTER_PLACEHOLDER_NAME.test(String(raw == null ? '' : raw).trim());
}

function buildMockPlayData(kind) {
  const free = kind !== 'city';
  const nodes = [
    {
      nodeId: 1001, sortId: 1, name: '街角绿地补给点', address: '复兴公园南门',
      latitude: 31.21784, longitude: 121.46423, imgUrl: '/pages/play/images/d_topic1.png',
      description: '找到最安静的一张长椅，拍下今天的城市颜色。', hookText: '离你最近，适合先热身',
      cardHookLong: '这里不需要按路线走，靠近后完成一次观察打卡。', validationMethod: 2,
      gameTitle: '城市颜色采样', photoRequireDesc: '拍一张能代表此刻心情的城市细节', storyText: '树影、路灯和行人的脚步，会把城市切成很多层。',
      fragmentText: '你拿到了一枚「午后光斑」。'
    },
    {
      nodeId: 1002, sortId: 2, name: '梧桐下的橱窗', address: '思南路街区',
      latitude: 31.21932, longitude: 121.46716, imgUrl: '/pages/play/images/d_topic2.png',
      description: '观察一处橱窗或门牌，找出它和这条街的关系。', hookText: '有合作商家标记', merchantId: 10001,
      cardHookLong: '如果正在营业，可以把这里当成路线中的停靠点。', validationMethod: 1,
      gameTitle: '门牌线索', question: '这条街最常出现的树是什么?', answerReveal: '梧桐', feedbackText: '答案藏在抬头能看到的树冠里。',
      fragmentText: '你拿到了一枚「旧街门牌」。'
    },
    {
      nodeId: 1003, sortId: 3, name: '隐蔽展墙', address: '淮海中路支弄',
      latitude: 31.22224, longitude: 121.47042, imgUrl: '/pages/play/images/d_topic4.png',
      description: '进入支弄，找到一面被海报或涂鸦覆盖的墙。', hookText: '更适合自由绕路发现',
      cardHookLong: '不是必经站，但完成后会解锁一段城市手记。', validationMethod: 5,
      storyText: '支弄里的墙面会保存很多短暂出现过的生活痕迹。', fragmentText: '你拿到了一枚「支弄回声」。'
    },
    {
      nodeId: 1004, sortId: 4, name: '黄昏观景口', address: '新天地北里',
      latitude: 31.22458, longitude: 121.47518, imgUrl: '/pages/play/images/d_topic4.png',
      description: '在天色变化时停留两分钟，记录你看到的城市边界。', hookText: '适合终点收束',
      cardHookLong: '城市定向模式里，这里会作为最后一站。', validationMethod: 3,
      gameTitle: '黄昏选择题', question: '此刻更适合记录什么?', options: { A: '灯光', B: '声音', C: '人流' }, answerReveal: '都可以，关键是停下来观察。',
      fragmentText: '你拿到了一枚「黄昏切面」。'
    }
  ];
  return {
    topicId: free ? 'mock-free-explore' : 'mock-city-route',
    topicName: free ? '自由探索 · 城市附近发现' : '城市定向 · 复兴路微路线',
    mode: free ? 2 : 1,
    selfPlay: free,
    expiresAt: Date.now() + 7 * 24 * 3600 * 1000,
    chapters: [{
      idxLabel: free ? '附近探索' : '第一章',
      name: free ? '今天先从附近开始' : '顺着旧街走到黄昏',
      description: free ? '没有固定路线，挑一个想去的节点出发，完成附近观察打卡。' : '按推荐顺序走完四站，把线索串成一段完整城市故事。',
      cover: '/pages/play/images/d_topic5.png', totalMileage: free ? 1.8 : 2.4, totalTime: free ? 2700 : 3600
    }],
    nodes
  };
}

const themeBackpack = require('./utils/theme-backpack.js');
const r5PlayerRuntime = require('./utils/r5-player-runtime.js');
const themeRecords = require('../../utils/theme-records.js');
const themeTrade = require('./utils/theme-trade.js');
const themePlans = require('./utils/theme-plans.js');
const playerOps = require('./utils/theme-player-ops.js');
const playOverlays = require('./utils/play-hud-overlays.js');
Page({
  ...journeyCheckRecovery.methods(req),
  ...orientLifecycle.methods((url, method, data) => {
    const header = { 'content-type': 'application/x-www-form-urlencoded' };
    switch (url) {
      case '/api/play/orient/session/current': return req('/api/play/orient/session/current', method, data, header, true);
      case '/api/play/orient/session/prepare': return req('/api/play/orient/session/prepare', method, data, header, true);
      case '/api/play/orient/session/start': return req('/api/play/orient/session/start', method, data, header, true);
      case '/api/play/orient/session/abandon': return req('/api/play/orient/session/abandon', method, data, header, true);
      case '/api/play/orient/session/receipt': return req('/api/play/orient/session/receipt', method, data, header, true);
    }
    const task = /^\/api\/play\/orient\/session\/task\/([1-9][0-9]*)\/result$/.exec(url);
    if (task) return req('/api/play/orient/session/task/' + task[1] + '/result', method, data, header, true);
    const score = /^\/api\/orient\/scoring\/session\/([1-9][0-9]*)$/.exec(url);
    if (score) return req('/api/orient/scoring/session/' + score[1], method, data, header, true);
    throw new Error('Unknown orient lifecycle endpoint');
  }),
  ...r5PlayerRuntime.methods(), closeR5Verdict() { this.setData({ 'r5Verdict.show': false }); },  // 结果反馈判定屏「继续」(状态见 r5-player-runtime)
  ...themeBackpack.methods(req),
  ...themeRecords.methods(req, true),
  openThemeRecordCard(e) { return this.openThemeResultCard({ currentTarget: { dataset: e.detail } }); },
  ...themeTrade.methods(req),
  ...themePlans.methods(req),
  ...playerOps.methods(req),
  ...playOverlays.methods(req, uuid4),
  _routeState: normalizeRouteState(null),

  data: {
    ...orientLifecycle.data,
    arrivalGuard: null,
    guardAppealView: null,
    communityPostWrite: false,
    ...r5PlayerRuntime.data,
    ...themeBackpack.data,
    ...themeRecords.data,
    ...playerOps.data,
    ...playOverlays.data,
    tagSheetShow: false,
    tagSheetItems: [],
    privacyGateShow: false,
    loading: true,
    reducedMotion: false,
    statusBarHeight: 44,
    chrome: { actionTop: 54, actionRight: 12, contentTop: 100, sheetTop: 99 },
    emptyTip: '', emptyKind: '',    // 空态:'' 无 / missing 缺场次参数(重试无效) / error 加载失败 / signup 未报名 / empty 无打卡点
    timeBanner: '',                 // 非阻断横幅:活动未开始/已结束(仍可看足迹)
    isPreview: false,           // 预览态:fabu 创作器经 EventChannel 喂数据,屏蔽所有写接口/GPS
    isMock: false,              // 本机演示态:用假数据看玩家 UI,不显示创作器控件
    activityId: '',
    topicId: '',
    mode: 1, serverMode: 1, modeLabel: '城市定向',
    routeNotice: '', routeRefreshing: false, journeyAttributes: [],
    screen: '',                 // '' | intro | gameLaunch | gamePlay
    gpsOk: false, gpsLowAcc: false, gpsHint: '开始后可开启定位，也可扫码到达',
    // 底部运行栈三态(Figma 3957:12602 / 3962:14212 / 3962:14341)。
    // idle = 进了地图但还没按「开始」;running / paused 跟着 _sessionClock 走,不另存一份真值。
    runState: 'idle',
    runHoldLeft: 0,         // 长按结束时还没完成的站数(>0 才在按住时提示)
    runHoldPct: 0,          // 主键长按结束的进度环 0–100
    chapterCount: 0,   // 本主题章节数(开始前读数)
    searchPlaces: [],   // 顶部输入地址的候选,见 _searchPlaces
    // 自由探索选章弹窗:切换章节=先选章(cur=当前章下标),选完地点卡只出该章的点
    chapterPick: { show: false, cur: 0 },
    // 章节内容全屏态(Figma 3963:14970):一次一段、居中,滚动时按距屏心远近算 scale/opacity/blur
    chapterFull: false, chapterParas: [],
    // 相册(2026-09-24 用户):故事流里一条一直滚动的圆角照片带,点开是白底全屏(3D 弯折卡 + 彩光 + 倒影)
    album: { show: false, cur: 0, title: '', images: [] },
    // 开场页(原型 game-intro,2026-09-24):故事主题开局先看这一页,这一局只看一次
    storyIntro: { show: false },
    // 初始状态页(原型 statsIntro):建档交上去之后,「带着这些,开始这一生」
    startState: { show: false, rows: [] },
    // 故事流左上角罗盘上的距离读数(到下一站),复用 _distText。
    storyDistText: '',
    // 当前生效的氛围(mood 块给的);'' = 这一段没被标记过,沿用章节自己的配色。
    // 思维阁:玩家已经得到的念头与进度(右上角菜单里那一行)
    storyThoughts: [],
    chapterGameAnchor: '',
    storyMap: false,     // 故事流左上角罗盘点开的小地图(2026-09-23 裁决:罗盘 = 地图切换)
    storyMapCenter: null,
    oddFlick: -1,
    storyChat: false,    // 终章反转:读到反转块后整章翻成对话框(正文 = 「我」发出去的消息)        // 越往后越不正常:此刻正闪的那一段(chapterParas 的 i)
    soundOn: true,       // 故事流环境音 / 音效(2026-09-23);本机记住,右上角菜单开关
    chapterGate: { show: false, number: 1, total: 1, title: '', subtitle: '', faces: [], endInfo: null, info: {} }, finale: { show: false, faces: [], info: {} },
    // 导航态顶部条的第二行。⚠️ 全部是「预计」:没有导航 SDK,只能按直线距离 + 步行速度估。
    navMeta: { km: '0.0', dur: '—', eta: '—' },
    chapter: { idxLabel: '第一章', name: '', description: '', cover: '', mileage: '', timeText: '', atmospherePreset: 'DEFAULT', atmosphereClass: 'atmosphere--default' },
    previewEnding: { show: false, list: [], idx: 0 },
    bootStage: '',   // 开局「开机」动画的阶段(on/line/open/fade),空 = 不显示   // W-15 试玩末尾的结局卡(只在预览出现)
    chapterCards: [],             // 横滑章节卡(buildChapterCards)
    // mode2(自由探索)首屏:卡包 → 六宫格。动画标志位只驱动 WXSS class,不参与业务判定。
    // 四拍:② 蓄力(摇+压暗) → ③ 横撕(封口条扯走+爆炸) → ④ 发牌(逐张飞出) → 空袋下沉。
    // 奖励掉落卡:带服务端稳定 ID(badgeCode),可跳到徽章墙回读,不是一闪而过的 toast
    rewardDrop: { show: false, text: '', badges: [] },
    ringSegs: [],                 // 已核销环:等分间断条,一家一条,核销一家亮一条(见 _ringSegs)
    topicName: '',                // 卡包印刷用:一章一商家后 chapter.name 只是第一章,不能拿它当主题名
    topicCover: '',               // 卡包首屏底图:主题长图,虚化后铺底(没有就退回 chapter.cover)
    topicDesc: '',                // 通行证首屏那段简介:同理,chapters[0].description 讲的是第一家店的事
    seatTiles: [], packOpen: false, packShake: false, packBurst: false,
    // 点卡 hero 层:show 挂节点,open 驱动过渡(先 show 再下一帧 open,过渡才有起点)
    hero: { show: false, open: false, node: null, style: '', bodyTop: 0, ry: 0, spun: false, turned: false, sx: 0, ss: '1', faded: false, collapsed: false, scrollTop: 0 },
    // 店铺分身对话(自由探索卡片详情 → 点店铺卡)。started 一旦为真,居中的大字问候让位给消息流。
    shopNpc: { show: false, open: false, name: '', greeting: '', started: false, msgs: [], input: '', thinking: false, thinkPhase: 0, nodeId: 0, ry: 0, recording: false },
    // 章节剧情全屏故事流(自由探索卡片详情 → 点大卡片)。lines 逐行显形,不是整段一次淡入。
    chapStory: { show: false, open: false, title: '', lines: [], audio: '', audioDur: '', playing: false, barAway: false, frames: 0 },
    // 升级2/3:俱乐部角色卡(首入弹一次) + 同行者榜 sheet
    myRole: null,
    roleCard: { show: false, code: '', name: '', iconName: 'star', desc: '', skill: '', confirmed: false },
    gameModule: {
      enabled: false, loading: false, error: '', sheetShow: false,
      sessionId: 0, activityId: 0, status: '', revision: 0, snapshotAt: '',
      stale: false,
      canSubmitTask: false,
      role: { code: '', name: '' }, nodes: [], teamActions: [], story: { visibleVariables: [], ending: null },
      write: { status: 'idle', requestId: '', message: '', canRetry: false },
    },
    board: { show: false, loading: false, list: [], me: null },
    packDim: false, packTear: false, packSink: false, headIn: false,
    // 拖拽撕开:tearPct 是撕口进度(0-100),两个 style 串由 _applyTear 统一算,
    // 只在拖拽路径非空 —— tap 路径全程为 '',不会跟 fxRip/fxMouth 动画打架。
    tearPct: 0, packGrab: false, packSnap: false, packTorn: false,
    tearSealStyle: '', tearMouthStyle: '',
    // 爆炸的粒子/光条是数据驱动的(WXML 不能生成元素),开包那一刻才建,收场即清空。
    blastRays: [], blastShards: [], measuring: false,
    tileH: 0,                     // 瓦片行高:按剩余高度等分,4 家竖版大卡 / 6 家近方卡
    nodes: [], total: 0, merchantCount: 0, doneCount: 0, allDone: false, topicCompleted: false,
    remainingGameCount: 0,
    fmNodes: [], fmPolyline: [], fmPlayer: null, fmCenter: { lat: 31.2304, lng: 121.4737 },
    fmSubkey: MAP_DARK.subkey, fmLayerStyle: MAP_DARK.layerStyle, // S9 暗色底图预留,见上方 MAP_DARK 注释
    nextNode: null,
    sheet: { show: false, node: null, continueText: '前往下一站' },
    advancedPlay: { show: false, node: null },
    // 节点任务
    game: {}, answerInput: '', answerSel: '',
    preference: {
      loading: false, error: '', steps: [], stepIndex: 0, choices: {}, inheritedTags: [],
      answerHere: false, submitting: false, result: null, pendingTag: null,
      tagDisclosure: null, progress: null, availableTagValues: []
    },
    newLifeOS: {
      loading: false, show: false, tags: [], resultCards: [], mapAnchors: [], mapNodes: [],
      actions7Days: [], actions30Days: []
    },
    scanning: false, photoTaken: false, gWrong: false, gNetErr: '', gNetRetryable: false, gRetryType: '', gWriteUnknown: false, shownHints: [],
    canHint: false, canReveal: false, hintLabel: '1/2', revealed: false, wrongCount: 0, hintUnlocking: false,
    puzzleScoreCap: 100,
    // v5.1/v5.2 新玩法(Figma 组件库 v5.1 node 45:248):kit 由服务端在节点上下发,没下发就整块不存在
    playKit: { show: false, kit: null },
    // R14 旅程检定(入口在 encounter.allowedActions,不在 playKit):题面 + 回执 + 请求态
    journeyCheck: {
      show: false, loading: false, error: '', nodeId: 0,
      checkId: '', skill: '', tier: '', mods: [], advantage: false, disadvantage: false,
      receipt: null,
    },
    // 反馈
    flyScore: false, flyText: '', lockHint: false,
    medal: { show: false }, couponToast: false, couponText: '', couponBurst: [],
    // 前往中陪伴态(mode2 专属):方向箭头 + 底部陪伴条 + 50%/90% 路程各一句途中 NPC 气泡
    nav: { active: false, targetNodeId: null, targetName: '', remainM: 0, totalM: 0, hit50: false, hit90: false },
    navBubble: { show: false, text: '' },
    // P1 俱乐部带领(club 场次:集合屏→队长发车→章节放行;3~5s 轮询 team-progress)
    lead: { exists: false, status: 0, isLeader: false, arrived: 0, total: 0, meArrived: false, chapterName: '', broadcast: '', members: [] },
    leadLoadError: false, leadUnlockChecking: false,
    // 手记
    /* 半屏进场用的开关(原型 .sheet 的 @keyframes up 在这儿是 transition)。 */
    sceneIn: false,
    showJournal: false, journalDot: false, journalCards: [], sessionToolsOpen: false,
    /* 自由探索有两种看法(2026-09-10 用户裁决):这一页默认是卡包,
       点「N 家商户」旁边那枚地图钮换成地图模式(原型 x-run)。同一批商户,两种排布。 */
    // 2026-09-22 地图模式已删,本字段恒 false;它还留着只因为 .fx / .pbar 的 wx:if 仍引用,
    // 那两行归城市定向那条线收口(见 PR 说明),不在本次删除范围内。
    freeMap: false,
    sceneStack: [], sceneCurrent: null, sceneConfirm: { show: false, action: null, pending: null },
    // 剧情两段式的第二段(全屏叙事页)。内容由 openStory 从既有节点数据取,不新增接口。
    story: { show: false, title: '', step: '', cover: '', text: '' },
    focusAnchor: '', nextChapterText: '',
    // P0 碎片翻卡(mode2 完成节点即翻出该节点碎片)
    frag: { show: false, text: '', step: '' },
    // M2 夜间提示(自玩 22:00–06:00):后端回执带 nightWarning 时在完成结果时刻出一句
    nightHint: false,
    // 通关
    showFinish: false, celebrationEvent: '', finishScore: 0, finishPuzzleScore: 0, finishPuzzleCount: 0, finishPuzzleBest: 0,
    finishMedals: 0, finishMedalList: [], finishAchievements: [], finishDateLabel: '', medalScreen: { show: false, items: [], title: '', sub: '', stats: [], record: null }, finishSnap: '', finishSnapError: false, finishPhotos: [], canReplayJournal: false,
    branchPath: [],
    finishRouteRecommendation: null,
    voucher: { show: false },
    ticket: { show: false, url: '', code: '', loading: false, expiresAt: 0 },
    // B 通关轨迹回顾层(2D):只连已完成节点、按真实走法排序,渐进描线 + 成就气泡。
    // reviewEmpty=true 表示一个已完成节点都没有(或没坐标)⇒ 回顾层整块不渲染,不画空地图。
    reviewElapsed: '', reviewDistance: '', reviewEmpty: true,
    // C 结束分享:主视觉徽章 + 里程碑轴。两者都可能为 null,对应「没拿到徽章」「累计数取不到」,
    // 此时各自整块不渲染 —— 不摆假数据(任务书硬要求)。
    finishBadge: null, milestone: null, stageEnd: null,
    // 终章结局信(mode2)
    ending: { show: false, opener: '', fragments: [] }, endingError: false,
    // 音频播放
    audioPlaying: false, audioLoading: false, audioError: '', audioNodeId: null,
    // FE-19 入场码(玩家出动态码,商家/俱乐部扫核销);regId 由票夹带入,无则不显 fab
    regId: ''
  },

  // 隐私授权闸:app.js 优先调这里(真弹窗),没有这个方法的页面才回退到 /pages/privacy 路由页。
  showPrivacyGate() {
    this.setData({ privacyGateShow: true });
  },
  onPrivacyGateSettled() {
    this.setData({ privacyGateShow: false });
  },

  onLoad(options) {
    this._playOwner = this._playMember();
    this._playLoginContext = this._capturePlayLoginContext();
    this._playDisposed = false;
    options = options || {};
    this._entryTopicName = options.topicName ? decodeURIComponent(options.topicName) : '';
    this._routeState = normalizeRouteState(null);
    const sys = wx.getSystemInfoSync();
    this.setData({
      chrome: resolveMenuChrome(
        (wx.getWindowInfo && wx.getWindowInfo()) || sys,
        wx.getMenuButtonBoundingClientRect && wx.getMenuButtonBoundingClientRect()
      ),
    });
    this.rpx = 750 / sys.windowWidth;                 // px → rpx 系数
    this.screenW = sys.windowWidth * this.rpx;        // = 750
    this.screenH = sys.windowHeight * this.rpx; this._entryLayer = options.layer === 'scoring' ? String(options.sessionId || '*') : ''; // 我的申诉点一行:落到本局成绩全屏层
    // M2 自玩:onLoad 收 topicId||activityId(自玩会话走 topicId,场次会话走 activityId)
    this.setData({
      activityId: options.activityId || '',
      topicId: options.topicId || '',
      regId: options.registrationId || options.regId || '',
      reducedMotion: readReducedMotion(),
    });
    if (options.mock && app.isDevEnv && app.isDevEnv()) {
      this.setData({
        isMock: true,
        isPreview: false,
        statusBarHeight: getApp().globalData.statusBarHeight || 44
      });
      this.applyPreviewData(buildMockPlayData(options.mock));
      return;
    }
    // 预览模式:fabu 路线创作器经 EventChannel 喂数据,不走后端拉数
    if (options.preview) {
      const that = this;
      this.setData({
        isPreview: true,
        isMock: false,
        loading: false, emptyTip: '草稿未加载，请返回编辑器重新预览', emptyKind: 'preview',
        statusBarHeight: getApp().globalData.statusBarHeight || 44
      });
      const ec = this.getOpenerEventChannel && this.getOpenerEventChannel();
      ec && ec.on && ec.on('previewData', (data) => { that.applyPreviewData(data || {}); });
      return;
    }
    this._pendingInviteCode = String((options.inviteCode) || '').trim();
    if (this._pendingInviteCode) this._consumeInviteCode().then((current) => { if (current) this.loadData(true); });
    else this.loadData(true);
  },

  _consumeInviteCode() {
    if (!this._isPlayAccountCurrent()) return Promise.resolve(false);
    const code = this._pendingInviteCode;
    if (!code) return Promise.resolve(false);
    this._pendingInviteCode = '';
    const that = this;
    const activityId = String(this.data.activityId || '');
    const topicId = String(this.data.topicId || '');
    const sequence = this._inviteJoinSequence = (this._inviteJoinSequence || 0) + 1;
    return new Promise((resolve) => {
      let settled = false;
      const isCurrent = () => !settled && that._isPlayAccountCurrent()
        && sequence === that._inviteJoinSequence
        && activityId === String(that.data.activityId || '')
        && topicId === String(that.data.topicId || '');
      const finish = () => { const current = isCurrent(); settled = true; resolve(current); };
      app.sendRequest({
        hideLoading: true,
        url: '/api/circle-theme/session/join',
        method: 'POST',
        data: JSON.stringify({ inviteCode: code }),
        header: { 'Content-Type': 'application/json' },
        success(res) {
          if (!isCurrent()) { finish(); return; }
          if (res && (res.code == 200 || res.code == '200')) {
            const sessionId = res.data && res.data.id;
            if (sessionId && topicId) {
              const memberId = that._boundPlayOwner();
              const key = memberId === null || memberId === undefined || memberId === ''
                ? '' : 'circle_session_m' + memberId + '_t' + topicId;
              try {
                wx.removeStorageSync('circle_session_' + topicId);
                if (key) wx.setStorageSync(key, sessionId);
              } catch (e) {}
            }
          } else {
            cyToast((res && res.msg) || '无法加入同行探索');
          }
          finish();
        },
        successStatusAbnormal(res) { if (isCurrent()) cyToast((res && res.msg) || '无法加入同行探索'); finish(); },
        fail() { if (isCurrent()) cyToast('无法加入同行探索'); finish(); },
      });
    });
  },
  _playMember() { return String((app.getUserID && app.getUserID()) || ''); },
  _capturePlayLoginContext() {
    let issuedAt = '';
    try { issuedAt = wx.getStorageSync('authorization_issued_at') || ''; } catch (_) {}
    return { authorization: app.getAuthorization ? app.getAuthorization() : undefined,
      generation: String(app._featureFlagsEpoch || 0) + ':' + String(issuedAt) };
  },
  /** 高级玩法组件句柄;页面未挂 selectComponent(预渲染/桩)时按「无组件」处理。 */
  _advancedGameComponent() {
    return typeof this.selectComponent === 'function' ? this.selectComponent('#advancedGame') : null;
  },
  /** 本页归属账号:首次使用时绑定(onLoad 已绑;直接调用方法的场景在捕获上下文前补绑)。 */
  _boundPlayOwner() {
    if (this._playOwner === undefined) this._playOwner = this._playMember();
    if (!this._playLoginContext) this._playLoginContext = this._capturePlayLoginContext();
    return this._playOwner;
  },
  /** 页面未卸载(_playDisposed 为唯一卸载标记)且仍是进页时的账号。 */
  _isPlayAccountCurrent() {
    const owner = this._boundPlayOwner(), current = this._capturePlayLoginContext();
    return !this._playDisposed && owner === this._playMember()
      && this._playLoginContext.authorization === current.authorization && this._playLoginContext.generation === current.generation;
  },
  onShow() {
    // 只看账号是否已切换(卸载态由各回调自己的 _isPlayAccountCurrent 挡)
    if (this._playOwner !== undefined && this._playOwner !== this._playMember()) {
      this._stopLeadPoll(); this._stopTracking(); audioSession.pauseAll(); audioSession.releaseAwake();
      wx.reLaunch({ url: '/pages/index/index' });
      return;
    }
    this.restoreGuardAppeal();
    this._syncCommunityEntry();
    this._themeHidden = false; this._startThemePoll();
    this._startZeroHpTimer();
    if (this.data.chapterFull && !this.data.chapterGate.show) this.measureChapter();
    else if (this.data.chapStory.show) this._startStoryDust();
    if (this.data.chapterFull) audioSession.keepAwake('chapterFull', true);
    if (this.data.chapStory.show) audioSession.keepAwake('chapStory', true);
    this._resetPackFx();   // onHide 清掉定时器后,四拍/拖拽的中间态标志必须归零,否则包把自己挡死
    if (!this.data.isPreview && !this.data.isMock && (this.data.activityId || this.data.topicId) && (!this.data.loading || this._playReloadOnShow)) { this._playReloadOnShow = false; this.loadData(false); }
  },

  // M2 会话参数:活动会话带 activityId,自玩会话带 topicId(后端二选一,活动优先)
  _sessionParams() { return this.data.activityId ? { activityId: this.data.activityId } : { topicId: this.data.topicId }; },
  /** 会话归属比较键:id 统一按字符串比,/nodes 回包把 '31' 写回成 31 不算换了场次。 */
  _sessionKey(params) {
    const p = params || this._sessionParams();
    return p.activityId ? 'a:' + String(p.activityId) : 't:' + String(p.topicId == null ? '' : p.topicId);
  },
  _ensureRouteActionStore() {
    const owner = this._boundPlayOwner();
    const scope = [owner, this._sessionKey(), String((this._routeState || {}).sessionId || '')];
    const scopeKey = JSON.stringify(scope);
    if (this._routeActionsScope !== scopeKey) {
      this._routeActionsScope = scopeKey;
      // 非登录预渲染没有持久账号身份；真实写仍由服务端拒绝。
      this._routeActions = owner ? scopedRouteActionStore(scope, wx) : createRouteActionStore();
    }
    return this._routeActions;
  },
  _routeWritePayload(kind, nodeId, payload) {
    const owner = this._boundPlayOwner();
    this._ensureRouteActionStore();
    const basic = ['answer', 'arrive', 'checkin', 'photo', 'puzzleReveal', 'sensor', 'preference'].includes(kind);
    if (!basic && !this._otherRouteActions) this._otherRouteActions = createRouteActionStore();
    const store = basic ? this._routeActions : this._otherRouteActions;
    const body = store.payload(kind, nodeId, this._routeState, Object.assign({}, this._sessionParams(), { nodeId }, payload || {}));
    const sessionKey = this._sessionKey(), runtime = String((this._routeState || {}).sessionId || '');
    Object.defineProperty(body, '_intentOwner', { value: owner });
    Object.defineProperty(body, '_intentCurrent', { value: () => this._isPlayAccountCurrent()
      && sessionKey === this._sessionKey() && runtime === String((this._routeState || {}).sessionId || '') });
    Object.defineProperty(body, '_expectedSessionId', { value: String((this._routeState || {}).sessionId || '') });
    return body;
  },
  _withThoughtSync(write, isCurrent) {
    const current = () => typeof isCurrent !== 'function' || isCurrent();
    if (!current()) return Promise.resolve({ obsolete: true });
    if (this.data.isPreview || this.data.isMock
        || (!this._thoughtSyncPending && !(this._routeState && this._routeState.thoughtCount > 0) && !(this.data.themeBag || {}).stepEggs)) return write();
    const pendingClaim = this._thoughtSyncPending || Promise.resolve(true);
    const synced = pendingClaim.then(ok => current() && ok && this._syncThoughts([], isCurrent)).catch(() => false);
    // F-R2S-01:微信运动授权/登录/读数回调可能迟迟不回,步数同步最多等这么久,之后照常提交玩家动作。
    let timer = null;
    const bounded = new Promise((resolve) => {
      timer = setTimeout(() => resolve(false), THOUGHT_SYNC_WAIT_MS);
      synced.then(resolve, () => resolve(false));
    });
    return bounded.then(ok => {
      clearTimeout(timer);
      if (!current()) return { obsolete: true };
      if (!ok) cyToast('步数暂未同步，仍可继续游玩');
      return write();
    });
  },
  _resolveRouteAction(kind, nodeId) {
    const basic = ['answer', 'arrive', 'checkin', 'photo', 'puzzleReveal', 'sensor', 'preference'].includes(kind);
    const store = basic ? this._routeActions : this._otherRouteActions;
    if (store) store.resolve(kind, nodeId);
  },
  _sendBasicRouteIntent(url, payload) {
    if (!this._isPlayAccountCurrent()) return Promise.resolve({ obsolete: true });
    if (url === '/api/play/answer') return req('/api/play/answer', 'POST', payload);
    if (url === '/api/play/checkin') return req('/api/play/checkin', 'POST', payload);
    if (url === '/api/play/arrive') return req('/api/play/arrive', 'POST', payload);
    if (url === '/api/play/photo') return req('/api/play/photo', 'POST', payload);
    if (url === '/api/play/puzzle/reveal') return req('/api/play/puzzle/reveal', 'POST', payload);
    if (url === '/api/play/sensor-result') return req('/api/play/sensor-result', 'POST', payload);
    if (/^\/api\/play\/preference\/[1-9][0-9]*\/submit$/.test(url)) return req('/api/play/preference/' + payload.nodeId + '/submit', 'POST', payload, { 'content-type': 'application/json' });
    return Promise.resolve({ code: 500, msg: '无法确认本次操作，请重新进入' });
  },
  _readBasicRouteIntent(kind, nodeId) {
    if (!this._isPlayAccountCurrent()) return Promise.resolve({ obsolete: true });
    const body = this._routeWritePayload(kind, nodeId, { nodeId });
    return req('/api/play/action-receipt', 'GET', { activityId: body.activityId, topicId: body.topicId, nodeId: body.nodeId, routeActionId: body.routeActionId }, undefined, true);
  },
  _routeNoticeForState(routeState) {
    const state = normalizeRouteState(routeState);
    const status = state.status;
    const last = state.decisionLog[state.decisionLog.length - 1] || {};
    if (state.routeMode === 'BRANCH_GRAPH' && !Object.keys(state.nodeStates).length) return '路线状态暂未同步，请稍后重试';
    if (status === 'UNAVAILABLE' || status === 'WITHDRAWN' || status === 'CONFIG_WITHDRAWN') return '路线配置已调整，当前行程暂不可继续';
    if (status === 'ENDED' || status === 'CANCELLED' || status === 'EXPIRED') return '本次路线已结束';
    if (status === 'FALLBACK' || /FALLBACK/i.test(String(last.reason || ''))) return '原地点暂不可用，已切换到备用路线';
    return '';
  },
  _routeInteractionBlocked() {
    const routeState = normalizeRouteState(this._routeState);
    return routeState.routeMode === 'BRANCH_GRAPH'
      && (this.data.routeRefreshing || routeState.status !== 'ACTIVE'
        || !(this.data.nodes || []).some((node) => node && !node.done && node.playable === true));
  },
  _failClosedRouteState(message, refreshing) {
    if (this._routeState.routeMode !== 'BRANCH_GRAPH') {
      this.setData({ routeRefreshing: !!refreshing, routeNotice: message || '' });
      return;
    }
    const nodes = (this.data.nodes || []).map((node) => {
      if (node.done || node.routeNodeState === 'COMPLETED') return node;
      return Object.assign({}, node, {
        playable: false,
        locked: true,
        routeNodeState: 'DISCOVERED_LOCKED',
        lockReason: message || '路线状态暂未同步',
      });
    });
    this.setData({
      routeRefreshing: !!refreshing,
      routeNotice: message || '路线状态暂未同步，请稍后重试',
      nodes,
      nextNode: null,
      'sheet.show': false,
    });
  },
  _mergeRouteCatalogCurrent() {
    const current = {};
    (this.data.nodes || []).forEach((node) => { if (node && node.nodeId != null) current[String(node.nodeId)] = node; });
    const source = this._routeNodeCatalog || this.data.nodes || [];
    return source.map((node) => Object.assign({}, node, current[String(node.nodeId)] || {}));
  },
  _routeStateVersionKnown(input) {
    let source = input;
    if (typeof source === 'string') {
      try { source = JSON.parse(source); } catch (e) { source = null; }
    }
    const value = source && typeof source === 'object' && !Array.isArray(source) ? source.version : null;
    return (typeof value === 'number' || typeof value === 'string' && value.trim() !== '')
      && Number.isFinite(Number(value));
  },
  _acceptRouteStateProjection(input) {
    const next = normalizeRouteState(input), previous = this._routeState || {};
    const previousKnown = Number.isFinite(previous.version)
      && (previous.version > 0 || this._versionedRouteState === previous);
    return !(next.sessionId && next.sessionId === previous.sessionId && previousKnown
      && (!this._routeStateVersionKnown(input) || next.version < previous.version));
  },
  _applyRouteState(routeStateInput, callback) {
    if (!this._acceptRouteStateProjection(routeStateInput)) {
      if (callback) callback(false);
      return false;
    }
    const routeState = normalizeRouteState(routeStateInput);
    const versionKnown = this._routeStateVersionKnown(routeStateInput);
    if (this._routeState.routeMode === 'BRANCH_GRAPH' && routeState.routeMode !== 'BRANCH_GRAPH') {
      this._failClosedRouteState('路线状态暂未同步，请稍后重试', false);
      if (callback) callback(false);
      return false;
    }
    const catalog = this._mergeRouteCatalogCurrent();
    this._routeNodeCatalog = catalog;
    let nodes = applyRouteStateToNodes(catalog, routeState);
    if (routeState.routeMode === 'LINEAR' && this._hasGenericProofFlow()) nodes = nodes.map(node => {
      const state = routeState.nodeStates[String(node.nodeId)];
      return state ? Object.assign({}, node, { done: state === 'COMPLETED', playable: state === 'PLAYABLE', locked: state === 'DISCOVERED_LOCKED', routeNodeState: state }) : node;
    });
    if (routeState.routeMode === 'BRANCH_GRAPH' && routeState.status !== 'ACTIVE') {
      const message = this._routeNoticeForState(routeState) || '路线状态异常，当前不可继续';
      nodes = nodes.map((node) => node.done || node.routeNodeState === 'COMPLETED' ? node : Object.assign({}, node, {
        playable: false, locked: true, routeNodeState: 'DISCOVERED_LOCKED', lockReason: message,
      }));
    }
    this._routeState = routeState;
    this._versionedRouteState = versionKnown ? routeState : null;
    this.setData({
      nodes,
      total: nodes.length,
      doneCount: nodes.filter((node) => node.done).length,
      routeNotice: this._routeNoticeForState(routeState),
      journeyAttributes: routeState.attributes,
      routeRefreshing: false,
    }, () => {
      this.rebuild();
      this.refreshThemeBackpack();
      if (callback) callback(true);
    });
    return true;
  },
  refreshRouteState() {
    if (!this._isPlayAccountCurrent() || this._themeHidden || this.data.isPreview || this.data.isMock) return Promise.resolve(false);
    const params = this._sessionParams();
    const sessionKey = this._sessionKey(params);
    const loadEpoch = this._playLoadEpoch;
    const sequence = this._routeReadSequence = (this._routeReadSequence || 0) + 1;
    const isCurrent = () => this._isPlayAccountCurrent() && !this._themeHidden
      && sequence === this._routeReadSequence && loadEpoch === this._playLoadEpoch
      && sessionKey === this._sessionKey();
    this._failClosedRouteState('正在同步路线…', true);
    return req('/api/play/route-state', 'GET', params).then((response) => {
      if (!isCurrent()) return false;
      if (response.code == 200 || response.code == '200') {
        const data = response.data && response.data.routeState ? response.data.routeState : response.data;
        return this._applyRouteState(data || {});
      }
      this._failClosedRouteState(response.msg || '路线状态暂未同步，请稍后重试', false);
      return false;
    }).catch(() => {
      if (!isCurrent()) return false;
      this._failClosedRouteState('路线状态暂未同步，请稍后重试', false);
      return false;
    });
  },
  _handleRouteConflict(response, kind, nodeId) {
    if (response && (response.intentUnknown || Number(response.code) === 520)) return false;
    if (!isRouteConflict(response)) return false;
    const store = this._routeActions, sessionKey = this._sessionKey(), runtime = String((this._routeState || {}).sessionId || '');
    // 只有服务端确认原意图未执行，且用户确认后，才释放身份供下一次点击创建新意图。
    if (response.intentNotExecuted) cyModal.show({
      title: '原操作未执行', content: '路线状态已更新。确认结束原意图，再检查当前状态后重新操作？', confirmText: '确认',
      success: result => { if (result.confirm && this._isPlayAccountCurrent() && store === this._routeActions
        && sessionKey === this._sessionKey() && runtime === String((this._routeState || {}).sessionId || '')) this._resolveRouteAction(kind, nodeId); },
    });
    this.setData({ routeNotice: '路线状态有更新，正在同步…' });
    this.refreshRouteState();
    return true;
  },

  // M2 有效期"M/D"格式化(容错)
  _fmtMD(t) {
    if (!t) return '';
    try {
      const n = Number(t);
      if (Number.isFinite(n) && n > 0) {
        const d = new Date(n);
        return (d.getMonth() + 1) + '/' + d.getDate();
      }
      const s = String(t).replace('T', ' ');
      const m = s.match(/(\d{4})-(\d{1,2})-(\d{1,2})/);
      return m ? (Number(m[2]) + '/' + Number(m[3])) : '';
    } catch (e) { return ''; }
  },
  /** 本次行程的定位标识:一个主题一次行程 */
  _trackKey() { return 'play:' + (this.data && this.data.topicId != null ? this.data.topicId : 'cur'); },

  /** 行程结束的唯一收尾口(通关/退出/离开页面都走它),幂等 */
  _stopTracking() {
    this._playLocationEpoch = (this._playLocationEpoch || 0) + 1;
    this._locationRequesting = false;
    this._watching = false;
    if (this._playLocationHandler) {
      try { wx.offLocationChange(this._playLocationHandler); } catch (e) {}
      this._playLocationHandler = null;
    }
    const release = this._releasePlayTracking;
    this._releasePlayTracking = null;
    this._playTrackingKey = null;
    if (release) release();
  },

  onUnload() {
    this.suspendOrientLifecycle();
    this.setData({ arrivalGuard: null });
    this._playDisposed = true;
    if (this._leadVerificationWorkflow) { this._leadVerificationWorkflow.destroy(); this._leadVerificationWorkflow = null; }
    this._leadArrivalEpoch = (this._leadArrivalEpoch || 0) + 1; this._leadArriving = false;
    this._themeRecordsDisposed = true;
    this._themeRecordsEpoch = (this._themeRecordsEpoch || 0) + 1;
    this._playLoadEpoch = (this._playLoadEpoch || 0) + 1;
    this._cancelShopRecording();
    this._themeHidden = true; this._stopThemePoll();
    this._stopZeroHpTimer();
    this._themeEpoch = (this._themeEpoch || 0) + 1;
    if (this._gateTimer) clearTimeout(this._gateTimer);
    (this._fxTimers || []).forEach(clearTimeout);
    if (this._sound) { this._sound.dispose(); this._sound = null; }
    this._stopTracking();
    this._watching = false;
    this.destroyAudio();
    audioSession.releaseAwake();
    this._stopShopNpcVoice();
    this._stopLeadPoll();
    // R9-21:先落盘再销毁时钟 —— 暂停/返回页面不算主动离场,未结束的会话要能回来。
    this._persistRunSession();
    this._runRestoreEpoch = (this._runRestoreEpoch || 0) + 1;   // 页面已走,在途的服务端恢复不再回写
    this._destroySessionClock();
    // 开卡包的三个 timeout 都会在销毁后写 UI,必须跟着卸载清掉。
    // 用「有才清」而不是无条件 clearTimeout:未开过包时这个数组根本不存在。
    this._clearPackTimers();
    this._clearTransientUiTimers();
    if (this._routeDrawStop) this._routeDrawStop();
    // 玩法音频归页面持有(见 _togglePlayKitAudio),页面走了就得销毁,否则背景里一直在放
    if (this._kitAudio) { audioSession.unregister(this._kitAudio); this._kitAudio.destroy(); this._kitAudio = null; }
    // 章节旁白音频与故事流两个 timer 也归页面持有:若卸载时故事流还开着(没走 closeChapStory),
    // 旁白会留在后台继续放、timer 会在卸载后 setData。跟 _kitAudio 一样就地销毁。
    if (this._chapAudio) { audioSession.unregister(this._chapAudio); try { this._chapAudio.stop(); this._chapAudio.destroy(); } catch (e) {} this._chapAudio = null; }
    if (this._storyTimer) { clearTimeout(this._storyTimer); this._storyTimer = 0; }
    if (this._settleTimer) { clearTimeout(this._settleTimer); this._settleTimer = 0; }
    this._stopStoryDust();
    if (this._chMeasureTimer) clearTimeout(this._chMeasureTimer);
    // 长按结束的 40ms 进度 interval 只有 touchend 会清;卸载若正好压在按住那一拍上,
    // 不清就是卸载后每 40ms 一次 setData。
    this._cancelRunHold();
  },
  // ⚠️ onHide **不再停定位**:玩家锁屏/切走仍在走路,那正是后台定位的理由。
  //    定位只在 onUnload 与 _stopTracking(通关/退出)里停 —— 漏了就是长期采集。
  // 2026-08-20 并 master:开卡包的三个 timeout 与定位无关,该清照清;
  //    只把 master 那侧的 _watching=false / offLocationChange / stopLocationUpdate 三句拿掉。
  onHide() {
    this.suspendOrientLifecycle();
    this.setData({ arrivalGuard: null });
    this._leadArrivalEpoch = (this._leadArrivalEpoch || 0) + 1; this._leadArriving = false;
    this._playLoadEpoch = (this._playLoadEpoch || 0) + 1;
    this._playReloadOnShow = true;
    this._runRestoreEpoch = (this._runRestoreEpoch || 0) + 1;
    this._themeHidden = true; this._stopThemePoll();
    this._stopZeroHpTimer();
    if (this._sound) { this._sound.dispose(); this._sound = null; }   // 回来时按当前氛围重起
    this._stopStoryDust();
    if (this._chMeasureTimer) clearTimeout(this._chMeasureTimer);
    if (this._settleTimer) clearTimeout(this._settleTimer);
    this._storyAt = null;
    this._clearPackTimers(); this.pauseAudio(); audioSession.pauseAll(); audioSession.releaseAwake(); this._stopShopNpcVoice(); this._stopLeadPoll(); this._pauseSessionClock(); this._persistRunSession(); },

  _clearTransientUiTimers() {
    ['_navBubbleTimer', '_burstTimer', '_couponTimer',
     '_npcTimer', '_thinkTimer', '_spinTimer'].forEach((key) => {
      if (this[key] == null) return;
      clearTimeout(this[key]);
      this[key] = null;
    });
  },

  _ensureSessionClock() {
    if (!this._sessionClock) {
      this._sessionClock = createSessionClock();
      this._sessionClock.start();
    }
  },
  _pauseSessionClock() {
    if (!this._sessionClock || this._sessionClock.isPaused()) return;
    this._sessionClock.pause();
  },
  _destroySessionClock() {
    this._sessionClock = null;
  },
  // ===== R9-21 暂停会话恢复 =====
  /** 快照作用域:先按账号隔离，再按 activityId/topicId 隔离；身份未确定时不碰个人快照。 */
  _runSessionScope() {
    this._isPlayAccountCurrent();
    return { memberId: this._playOwner, activityId: this.data.activityId, topicId: this.data.topicId };
  },
  /** 暂停/离页时落盘。只记「已开始未结束」的行程;结束(通关/退出/服务端判结束)会 clear,
   *  所以重进读到的快照一定是可继续的暂停态,不是一个幽灵计时。 */
  _persistRunSession() {
    // 卸载时也要落盘(onUnload 先置 _playDisposed):这里只核账号归属,不看卸载标记。
    if (this._boundPlayOwner() !== this._playMember() || !this._runStarted || this.data.isPreview || this.data.isMock) return false;
    if (this._sessionClock && !this._sessionClock.isPaused()) this._sessionClock.pause();
    const elapsed = this._sessionClock ? this._sessionClock.elapsedSeconds() : 0;
    const now = Date.now();
    const scope = this._runSessionScope();
    // 第二轮拍板 22:同一份再写服务端,换手机登录同账号能续。写失败本机那份仍在,不影响本机恢复。
    this._queueRunSync(() => saveServerPausedRun(req, scope, elapsed, now, wx));
    return writePausedRun(wx, scope, elapsed, now);
  },
  /** 本机快照作废 + 服务端留结束墓碑:只清本机的话,换台设备又会把已结束的一局恢复回来。
   *  同时作废在途的服务端恢复读 —— 迟到的旧会话不能把刚结束的局拉回暂停态。 */
  _clearRunSessionEverywhere() {
    if (!this._isPlayAccountCurrent()) return false;
    const scope = this._runSessionScope();
    const now = Date.now();
    this._runRestoreEpoch = (this._runRestoreEpoch || 0) + 1;
    this._queueRunSync(() => clearServerRunSession(req, scope, now));
    return clearRunSession(wx, scope);
  },
  /** 同一页的服务端 save/clear 串行发出:先暂停再结束时,迟到的 save 不能把已结束的会话写回。 */
  _queueRunSync(send) {
    const member = this._playOwner;
    this._runSyncChain = (this._runSyncChain || Promise.resolve()).then(() => {
      if (member !== this._playMember()) return false;
      return send();
    });
    return this._runSyncChain;
  },
  /** 服务端权威态说这场已经不能继续(时间窗关闭 / 路线终态)时不恢复。 */
  _serverRunSessionEnded(d, routeState) {
    if (d && d.playable === false) return true;
    const status = String((routeState && routeState.status) || '').toUpperCase();
    // R1:COMPLETED/FINISHED 是 BRANCH_GRAPH 通关真实返回的终态(与 rebuild 的 branchFinished 同口径)。
    // 漏了它们,支线终局(还有没走的支线,本地 allDone 不成立)重进就会被当成暂停恢复。
    return ['COMPLETED', 'FINISHED', 'ENDED', 'CANCELLED', 'EXPIRED', 'UNAVAILABLE', 'WITHDRAWN', 'CONFIG_WITHDRAWN'].indexOf(status) >= 0;
  },
  /** 重进页面:读暂停快照,投影回 paused(不重开、不偷跑)。服务端说结束就不恢复并清快照。
   *  第二轮拍板 22:本机快照先同步恢复;再读服务端那份,本机没有(换了手机)或服务端更新时改用服务端的。 */
  _restoreRunSession(d, routeState, nodes) {
    if (!this._isPlayAccountCurrent() || this._runStarted || this.data.isPreview || this.data.isMock) return false;
    const scope = this._runSessionScope();
    const sessionKey = this._sessionKey();
    const openingPending = routeState && routeState.opening && !routeState.opening.complete;
    const allDone = !openingPending && (nodes || []).length > 0 && nodes.every((n) => n && n.done);
    if (allDone || this._serverRunSessionEnded(d, routeState)) {
      this._clearRunSessionEverywhere();
      return false;
    }
    const stored = readPausedRun(wx, scope);
    const restored = stored ? this._applyRestoredRun(stored) : false;
    const epoch = this._runRestoreEpoch = (this._runRestoreEpoch || 0) + 1;
    readServerPausedRun(req, scope).then((result) => {
      if (!this._isPlayAccountCurrent() || epoch !== this._runRestoreEpoch || !result.ok
          || sessionKey !== this._sessionKey()) return;
      // 等服务端期间玩家已经自己开了新局 / 点了继续:那一局归玩家,服务端这份不再覆盖或撤销它。
      const untouched = !this._runStarted || (this._restoredRun && this._sessionClock && this._sessionClock.isPaused());
      if (!untouched) return;
      // 另一台设备已经结束了这一局,且结束晚于本机快照:本机快照作废,不能还魂。
      if (stored && result.endedAt && result.endedAt >= stored.savedAt) {
        this._applyServerRunSessionEnd();
        return;
      }
      const next = newerPausedRun(stored, result.record);
      if (!next || next === stored) return;
      writePausedRun(wx, scope, next.elapsedSeconds, next.savedAt);
      this._applyRestoredRun(next, !!stored);
    });
    return restored;
  },
  _applyRestoredRun(record, fromOtherDevice) {
    this._runStarted = true;
    this._restoredRun = true;
    this._ensureSessionClock();
    if (this._sessionClock) this._sessionClock.restorePaused(record.elapsedSeconds);
    this._syncRunState();
    cyToast((fromOtherDevice ? '已同步另一台设备上的进度 · 用时 ' : '已恢复上次暂停的行程 · 用时 ') + formatElapsed(record.elapsedSeconds));
    return true;
  },
  /** 服务端会话已经结束(通关/取消)时,本地兜底的暂停快照作废;只撤「恢复出来的」那一局,
   *  不碰玩家自己按开始的新局。 */
  _applyServerRunSessionEnd() {
    this._clearRunSessionEverywhere();
    if (!this._restoredRun) return false;
    this._restoredRun = false;
    this._runStarted = false;
    this._pauseSessionClock();
    this._syncRunState();
    return true;
  },
  /** 一局结束的唯一收尾(长按结束 / 通关):停表 + 作废暂停快照 + 运行栈归 idle。
   *  R1:通关有多条入口(末站完成 / 勋章层关闭 / 继续),只清快照不够 —— 不清 _runStarted 的话,
   *  onHide/onUnload 的落盘又会把已结束的行程写回去,重进时被当成暂停恢复。 */
  _endRunSession() {
    this._stopTracking();
    this._pauseSessionClock();
    this._runStarted = false;
    this._restoredRun = false;
    this._clearRunSessionEverywhere();
    this._syncRunState();
  },
  onPlayHudToggle() {
    if (!this._sessionClock) return;
    if (this._sessionClock.isPaused()) {
      this._sessionClock.resume();
      // 玩家主动继续:这局不再是「服务端判结束就撤」的恢复态,归玩家自己了。
      this._restoredRun = false;
      if (!this.data.isPreview && !this.data.isMock && this.data.gpsOk) this.watchLocation();
    } else {
      this._sessionClock.pause();
      this._stopTracking();
    }
    if (this._sessionClock.isPaused()) this._persistRunSession();
  },

  // ===== 底部运行栈(Figma 3957:12602 / 3962:14212 / 3962:14341)=====
  /** runState 是 _sessionClock 的投影,不是第二份真值:时钟没起来 = idle,
   *  起来了再看 isPaused()。所有改动时钟的地方调一次这个即可,不必各自 setData。 */
  _syncRunState() {
    let runState = 'idle';
    if (this._runStarted && this._sessionClock) {
      runState = this._sessionClock.isPaused() ? 'paused' : 'running';
    }
    if (runState !== this.data.runState) this.setData({ runState });
  },
  /** 开始前的章节数。
   *  第三格「路程」随底部读数卡一起删了(2026-09-22),这里不再算 mileageText。 */
  _syncIdleStats(chapters, nodes) {
    this.setData({
      chapterCount: Array.isArray(chapters) ? chapters.length : 0,
    });
  },
  /** 中键:未开始→开始(起时钟+开定位),进行中→暂停,已暂停→继续。三态一个键,与设计稿一致。 */
  onRunToggle() {
    // 长按满 3 秒已经走了结束链路,松手时还会补一记 tap —— 不吞掉它，结束完会紧接着又开始一局
    if (this._runHoldTriggered) { this._runHoldTriggered = false; return; }
    if (this.data.runState === 'idle') {
      this._runStarted = true;
      this._restoredRun = false;
      // 新起一局必须作废旧暂停快照:否则「开了新的、还没落盘就杀进程」会把上一局的用时还魂。
      this._clearRunSessionEverywhere();
      this._ensureSessionClock();
      // 时钟在数据加载时就建好了,若不重置,「开始」那一刻的用时已经跑了十几秒
      if (this._sessionClock) this._sessionClock.start();
      // requestPlayLocation 自己带了预览/演示/已开启/请求中四道前置判断,这里不重复挡
      this.requestPlayLocation();
      this._syncRunState();
      return;
    }
    this.onPlayHudToggle();
    this._syncRunState();
  },
  /** 长按主键 3 秒 = 结束本次(9-09 裁决)。3 秒有意为之的按住本身就是确认,
   *  所以这里不再叠一层确认弹窗 —— 原来那枚方形「结束」键连同它的弹窗一起删了。
   *  还没开始(idle)时没有可结束的东西,不起计时。 */
  onRunHoldStart() {
    if (this.data.runState === 'idle') return;
    this._cancelRunHold();
    this._runHoldDelay = setTimeout(() => {
      this._runHoldDelay = null;
      const t0 = Date.now();
      this.setData({ runHoldPct: 0, runHoldLeft: (this.data.nodes || []).filter((n) => !n.done).length });
      this._runHoldTimer = setInterval(() => {
        const pct = Math.min(100, Math.round(((Date.now() - t0) / RUN_HOLD_MS) * 100));
        this.setData({ runHoldPct: pct });
        if (pct >= 100) {
          this._runHoldTriggered = true;
          this._cancelRunHold();
          wx.vibrateShort && wx.vibrateShort({ type: 'heavy' });
          this._finishRun();
        }
      }, 40);
    }, 180);   // 180ms 之前算点击,不算长按
  },
  onRunHoldEnd() {
    if (this.data.runHoldPct > 0 && this.data.runHoldPct < 100) this._cancelRunHold();
    else this._cancelRunHold();
  },
  _cancelRunHold() {
    if (this._runHoldDelay) { clearTimeout(this._runHoldDelay); this._runHoldDelay = null; }
    if (this._runHoldTimer) { clearInterval(this._runHoldTimer); this._runHoldTimer = null; }
    if (this.data.runHoldPct || this.data.runHoldLeft) this.setData({ runHoldPct: 0, runHoldLeft: 0 });
  },
  /** 结束:交给既有通关/结算路径,不在这里另写一套收尾 */
  _finishRun() {
    // R9-21:结束是主动收尾,暂停快照必须作废 —— 重进不能再拉回一局已经结束的计时。
    this._endRunSession();
    this.openFinish();
  },
  /** 左键「切换章节」(mode2):先弹章节选择;选完地点卡只出该章的商家/地点(用户裁决)。
   *  多章数据齐(chapterCards>1)才弹选择;单章直接进地点卡层。mode1 仍只给当前站。 */
  openStackOrChapters() {
    if (this.data.chapterCards.length > 1) { this.setData({ 'chapterPick.show': true }); return; }
    // 地点卡层已删:唯一的选点面就是卡包(用户裁决「完整地点列表=卡包」),别让入口点了没反应。
    this.setData({ packOpen: true });
  },
  /** 开卡包的定时器全挂在 _packTimers 上:onUnload 和 onHide 都要清干净 ——
   *  离开页面后它们会继续 vibrateShort、继续往已经不可见的页面 setData。 */
  _clearPackTimers() {
    if (this._packTimers) { this._packTimers.forEach(clearTimeout); this._packTimers = null; }
    if (this._heroTimer) { clearTimeout(this._heroTimer); this._heroTimer = null; }
  },
  /** onHide 清定时器后,四拍/拖拽会停在半路:packShake/packTear/packSnap/tearPct 这些
   *  中间态标志恰是 openPack / onPackTouchStart 的重入 guard ⇒ 不归零,回前台后
   *  点、拖全被自己挡死(与 tabBar「在途守卫 flag 卡死」同款,守卫必须 onShow 归零)。
   *  包已开完(packOpen)是终态,六宫格不动;否则一律回到待机重来。 */
  _resetPackFx() {
    const d = this.data;
    if (d.packOpen) return;
    if (!(d.packShake || d.packDim || d.packTear || d.packBurst || d.packSink
          || d.packGrab || d.packSnap || d.packTorn || d.tearPct > 0 || d.headIn)) return;
    const cleared = d.seatTiles.map((t) => {
      const rest = Object.assign({}, t);
      delete rest.fly;
      delete rest.land;
      return rest;
    });
    this.setData({
      packShake: false, packDim: false, packTear: false, packBurst: false,
      packSink: false, packGrab: false, packSnap: false, packTorn: false, headIn: false,
      tearPct: 0, tearSealStyle: '', tearMouthStyle: '',
      blastRays: [], blastShards: [], seatTiles: cleared,
    });
    this._tearActive = false;
    this._dragMoved = false;
    this._tearRawPct = 0;
  },
  /** mode2 开卡包,四拍:
   *    ② 蓄力 1000ms —— 摇 + 四周压暗(没有这层压暗,后面的闪光炸不出反差)
   *    ③ 横撕  640ms —— 顶部封口条整条向右扯走、撕口自左向右露出,同时爆炸
   *    ④ 发牌        —— 每张间隔 180ms 从卡包正中飞出,空袋下沉
   *  没有「开包」按钮是刻意的:一个居中的、明显是包装的东西,没人会不点。
   *  reducedMotion 直接落定不播 —— 动画永远不能是必须看完的。 */
  openPack() {
    if (this._dragMoved) return;   // 刚做完一次拖拽,吞掉紧跟着的 tap
    if (this.data.packOpen || this.data.packShake || this.data.packTear || this.data.tearPct > 0) return;   // 播放中再点不重入
    if (this.data.reducedMotion) { this.setData({ packOpen: true, headIn: true }); return; }
    this._packTimers = this._packTimers || [];

    this.setData({ packShake: true, packDim: true });
    wx.vibrateShort({ type: 'light' });

    this._packTimers.push(setTimeout(() => {
      this.setData({ packShake: false, packDim: false, packTear: true });
      this._finishTearAndDeal(PACK_TEAR);
    }, PACK_CHARGE));
  },

  /** 撕开完成后的爆炸+发牌收场,tap/拖拽两条路径共用。
   *  delay = 从爆炸到发牌的间隔:tap 路径等横撕动画播完(PACK_TEAR),
   *  拖拽路径撕口已被手指撕到位,只留一个短促的呼吸(90ms)。 */
  _finishTearAndDeal(delay) {
    this._packTimers = this._packTimers || [];
    this.setData({ packBurst: true });
    wx.vibrateShort({ type: 'heavy' });
    this._fireBlast();

    this._packTimers.push(setTimeout(() => {
      this._dealTiles();
      this.setData({ headIn: true });          // 通行证头跟第一张卡一起出来
      this.setData({ packSink: true });   // 爆完立刻自由落体坠出画面,把屏幕正中让给卡片
      // 最后一张落地之后才置 packOpen:它同时是「包已移除」和「兜底 is-dealt」,
      // 早置会把还在飞的卡强行拍到落点。
      const n = this.data.seatTiles.length || 1;
      const done = (n - 1) * DEAL_STAGGER + DEAL_FLIGHT;
      this._packTimers.push(setTimeout(() => this.setData({ packOpen: true }), done + 60));
      this._packTimers.push(setTimeout(
        () => this.setData({ packBurst: false, blastRays: [], blastShards: [] }), done + 400));
    }, delay));
  },

  /** ③′ 拖拽撕开:手指横拖,撕口实时跟随;过 TEAR_DONE_PCT 松手补完并接爆炸发牌,
   *  不足弹回待机。reducedMotion 不接管 —— tap 路径的「直接落定」已经覆盖。 */
  onPackTouchStart(e) {
    // 拖拽路径不走 packTear,所以 torn/snap/tearPct 必须自己挡:撕开完成到 packOpen
    // 之间有 ~2.4s 发牌窗,不挡的话第二根手指会再排一次爆炸+发牌(双开)。
    if (this.data.packOpen || this.data.packTear || this.data.packShake
        || this.data.packTorn || this.data.packSnap || this.data.tearPct > 0
        || this.data.reducedMotion) return;
    this._tearStartX = e.touches[0].clientX;
    this._tearActive = true;
    this._tearLastTs = 0;
    this._tearRawPct = 0;
  },
  onPackTouchMove(e) {
    if (!this._tearActive) return;
    const dx = e.touches[0].clientX - this._tearStartX;
    const pct = Math.max(0, Math.min(100, (dx / TEAR_MAX_PX) * 100));
    this._tearRawPct = pct;   // 节流前先记原始值:松手判阈值用它,不然快划的最后一帧被节流吃掉会误判弹回
    const now = Date.now();
    if (now - this._tearLastTs < 33 && pct < 100) return;   // ~30fps 节流,撕满那帧不丢
    this._tearLastTs = now;
    if (pct > 4 && !this.data.packGrab) {
      this.setData({ packGrab: true });
      wx.vibrateShort({ type: 'light' });    // 咬住封口的那一下
    }
    this._applyTear(pct);
  },
  onPackTouchEnd(e) {
    if (!this._tearActive) return;
    this._tearActive = false;
    const cancelled = !!(e && e.type === 'touchcancel');   // 系统打断(来电/弹窗)≠松手,一律弹回
    const pct = this._tearRawPct || 0;
    if (pct <= 0) {
      // 没拖出撕口 ⇒ 当没发生,交回给 tap;但 4% 抓握反馈可能已经亮了,得撤
      if (this.data.packGrab) this.setData({ packGrab: false });
      return;
    }
    this._dragMoved = true;                  // 同一根手指抬起会再触发 bindtap,吞掉它
    this._packTimers = this._packTimers || [];
    this._packTimers.push(setTimeout(() => { this._dragMoved = false; }, 350));
    if (!cancelled && pct >= TEAR_DONE_PCT) {
      this.setData({ packSnap: true, packTorn: true });   // is-snap 给补完过渡,is-torn 让袋口片翻出
      this._applyTear(100);
      this._packTimers.push(setTimeout(() => this._finishTearAndDeal(90), TEAR_SNAP));
    } else {
      this.setData({ packSnap: true, packGrab: false, tearSealStyle: '', tearMouthStyle: '' });
      this._packTimers.push(setTimeout(() => this.setData({ packSnap: false, tearPct: 0 }), TEAR_SNAP + 60));
    }
  },
  /** 撕口进度 → 两个内联 style。封口条的位移/旋转终点(500rpx,-18rpx,5deg)与 fxRip 的
   *  100% 帧一致 —— 拖到底和 tap 播到底必须停在同一个画面。 */
  _applyTear(pct) {
    const p = Math.round(pct * 10) / 10;
    const fade = p < 30 ? 1 : 1 - (p - 30) / 70;   // 前 30% 只位移不褪,和 fxRip 的节奏对齐
    this.setData({
      tearPct: p,
      tearMouthStyle: 'width:' + p + '%',
      tearSealStyle: 'transform:translate(' + (p * 5) + 'rpx,' + (p * -0.18) + 'rpx) rotate(' + (p * 0.05) + 'deg);opacity:' + fade.toFixed(2),
    });
  },

  /** 逐张发牌:每张两段 —— fly(弹到扇形弧顶) → land(落格)。
   *  分两段是因为一条 transition 画不出弧;弧顶时刻必须与 WXSS 的 DEAL_ARC 一致。
   *  返回真实排期(ms):节奏靠它断言 —— 靠 sleep 去采样「此刻飞了几张」会被
   *  自动化工具的往返延迟带偏,测出来的是延迟不是节奏。 */
  _dealTiles() {
    this._packTimers = this._packTimers || [];
    const arc = Math.round(DEAL_FLIGHT * DEAL_ARC);
    const schedule = this.data.seatTiles.map((_, i) => ({ i, fly: i * DEAL_STAGGER, land: i * DEAL_STAGGER + arc }));
    schedule.forEach(({ i, fly, land }) => {
      this._packTimers.push(setTimeout(() => this._markTile(i, 'fly'), fly));
      this._packTimers.push(setTimeout(() => {
        this._markTile(i, 'land');
        wx.vibrateShort({ type: 'light' });    // 每张落地一次轻震 = 逐张的那个点
      }, land));
    });
    return schedule;
  },

  /** 只翻第 i 张卡的某个动画标志位。
   *  ⚠️ 刻意整表重建、而不是 setData({['seatTiles[i].flag']: true}):
   *  动态 setData 键让 UI-GATE-0 的 U4 无法静态核对顶层字段消费,而那道棘轮
   *  (u4Dynamic ≤ 65)只减不增 —— 新增一条就是给别人留一笔还不掉的债。
   *  这里最多 6 个小对象,整表重建的开销可以忽略。 */
  _markTile(i, flag) {
    const nextTiles = this.data.seatTiles.map((t, idx) => (idx === i ? { ...t, [flag]: true } : t));
    this.setData({ seatTiles: nextTiles });
  },

  /** 已核销环:等分**间断条**,一家一条,核销一家亮一条。
   *  不用 conic-gradient —— 小程序渲染层不认,会渲成实心灰盘(2026-08-12 实拍);
   *  也不再用「两个半圆各转一半」画连续弧:那读起来是百分比进度,
   *  而这里要表达的是「四家里点亮了几家」,离散的条才对得上。
   *  按 doneCount 点亮前 N 条(不是按 seatTiles 各自的 done):环是计数展示,
   *  连续点亮才读得出进度;这几家本来也没有顺序。 */
  _ringSegs(done, total) {
    const n = Number(total) || 0;
    if (n <= 0) return [];   // 零家商户就零条 —— 抬成 1 会画出一根「0/0」的幽灵条
    const step = 360 / n;
    return Array.from({ length: n }, (_, i) => ({
      key: 'sg' + i,
      deg: (i * step).toFixed(2),
      on: i < done,
    }));
  },

  /** 爆炸六层里的两层需要真实元素:放射光条 + 碎片。WXML 生成不了,只能落成数据。
   *  另外四层(压暗/白闪/彩色光晕/冲击波)是固定节点,由 packBurst 开关。 */
  _fireBlast() {
    const rays = [];
    // 圆形光晕收成核心之后,「四射」全靠光条:条数和长度都得撑得住场面
    const RAYS = Math.round(16 * BLAST_POWER);
    for (let j = 0; j < RAYS; j++) {
      // ⚠️ 起步长度必须大于卡包的半对角线(424×741rpx ⇒ 约 222pt),否则光条整根
      //    埋在包后面(z-index 33 < 包 40),看不到「射出来」这件事。
      const len = 250 + Math.random() * 280;   // 长短拉开差距,齐刷刷等长会读成一个圆
      rays.push({
        id: 'r' + j,
        a: (j / RAYS * 360).toFixed(1),        // 爆心在包正中 ⇒ 全向等分,不偏上半圈
        c: HOLO[j % HOLO.length],
        len: Math.round(len),
        w: (3 + Math.random() * 5).toFixed(1),
        dur: Math.round((430 + Math.random() * 260) * 2),
      });
    }
    const shards = [];
    for (let i = 0; i < BLAST_SHARDS; i++) {
      const ang = (-90 + (Math.random() * 230 - 115)) * Math.PI / 180;
      const d = (90 + Math.random() * 210) * (0.6 + 0.6 * BLAST_POWER);
      const big = Math.random() < 0.3;        // 三成大块碎膜,七成细屑 —— 尺寸一致就读成彩纸
      shards.push({
        id: 's' + i,
        dx: Math.round(Math.cos(ang) * d),
        dy: Math.round(Math.sin(ang) * d + 55),
        rot: Math.round(Math.random() * 900 - 450),
        w: (big ? 7 + Math.random() * 9 : 2 + Math.random() * 4).toFixed(1),
        h: (big ? 11 + Math.random() * 18 : 4 + Math.random() * 8).toFixed(1),
        c: HOLO[i % HOLO.length],
        dur: Math.round((620 + Math.random() * 620) * 2),
      });
    }
    this.setData({ blastRays: rays, blastShards: shards });
  },

  /** 量两件事,两趟(第一趟改行高会挪动瓦片,必须落定后再量瓦片):
   *    ① 行高:瓦片按剩余高度等分 —— 写死 268rpx 时 4 家底下空一大块
   *    ② 飞行几何:每张卡从卡包正中弹出、经扇形弧顶、落格
   *  量不到就整个跳过:seatTiles 里 tx0/ty0 留空串,WXSS 的 var() 兜底 0px,
   *  卡片原地淡入 —— 降级成没有弧,不会消失。 */
  _measureDeal() {
    if (this.data.mode !== 2 || !this.data.seatTiles.length || this.data.packOpen) return;
    // 播放中不量:measuring 会给瓦片挂 transform:none,套在正在飞的卡上就是当场瞬移。
    // 只挡 packOpen 不够 —— 它是全程最后一步才置的。
    if (this.data.packShake || this.data.packTear || this.data.measuring) return;
    const that = this;
    const n = this.data.seatTiles.length;
    let rpx = 0.5;
    try { rpx = (wx.getWindowInfo().windowWidth || 375) / 750; } catch (e) {}
    const GAP = 22 * rpx;

    wx.createSelectorQuery()
      .select('.fx').boundingClientRect()
      .select('.fx-grid').boundingClientRect()
      .exec((r1) => {
        const sv = r1 && r1[0], gr = r1 && r1[1];
        if (!sv || !gr) return;
        // 卡片尺寸恒定:高 = 宽 × 1.38(4 章那一版量出来的比例,用户看过的就是它)。
        // ⚠️ 不要再按剩余屏高等分 —— 那样章数一变卡片就变形,同一个主题换个章数换一套观感。
        //    张数多了让它往下滚:.fx 本来就是 scroll-view。
        const PAD_X = 32 * rpx;
        const tileW = Math.max(80 * rpx, (gr.width - PAD_X * 2 - GAP) / 2);
        const tileH = Math.round(tileW * 1.38);
        // measuring 会给瓦片挂 transform:none(见 index.wxss),量到的就是**布局框**本身。
        // 比从变换后的框反推可靠:那个框同时吃了 scale 和 rotate,反推必然带误差。
        that.setData({ tileH: Math.round(tileH), measuring: true }, () => {
          wx.createSelectorQuery()
            .select('.fx-pack').boundingClientRect()
            .selectAll('.fx-tile').boundingClientRect()
            .select('.fx').boundingClientRect()
            .exec((r2) => {
              const pk = r2 && r2[0], tiles = r2 && r2[1], box = r2 && r2[2];
              if (!pk || !box || !tiles || tiles.length !== n) { that.setData({ measuring: false }); return; }
              const px = pk.left + pk.width / 2, py = pk.top + pk.height / 2;
              // 抛出半径对所有卡取同一个常数 —— 按各自落点距离算会让扇形左右不对称
              const R = pk.height * 0.55;
              const spread = DEAL_SPREAD;
              const geom = [];
              tiles.forEach((t, i) => {
                const lw = t.width, lh = t.height;                 // measuring 态 = 真实布局框
                const cx = t.left + t.width / 2, cy = t.top + t.height / 2;
                const k = n === 1 ? 0 : (i / (n - 1)) * 2 - 1;
                const ang = k * spread;
                const rad = (ang - 90) * Math.PI / 180;
                // 弧顶必须夹在画面内。不夹的话扇形一大、卡片一高,弧顶顶出上边框,
                // 看到的是「卡片从画面外掉进来」。夹坐标比整体下移好 —— 下移会把
                // 「卡片撑满屏幕、底下不留空档」又打回去。
                // 弧顶那一刻卡片是**转着**的(rotate(--rot * .4)),旋转后的外接框比原框大 ——
                // 按未旋转的尺寸留边,4 家会差 11px、6 家差 16px,卡角照样探出上边框。
                const rot2 = Math.abs(ang * 0.6 * 0.4) * Math.PI / 180;
                const ca = Math.abs(Math.cos(rot2)), sa = Math.abs(Math.sin(rot2));
                const w2 = lw * DEAL_MID_SCALE, h2 = lh * DEAL_MID_SCALE;
                const hw = (w2 * ca + h2 * sa) / 2, hh = (w2 * sa + h2 * ca) / 2, PAD = 6;
                let mx = px + Math.cos(rad) * R, my = py + Math.sin(rad) * R;
                mx = Math.min(Math.max(mx, box.left + hw + PAD), box.right - hw - PAD);
                my = Math.min(Math.max(my, box.top + hh + PAD), box.bottom - hh - PAD);
                geom[i] = {
                  tx0: Math.round(px - cx) + 'px', ty0: Math.round(py - cy) + 'px',
                  tx1: Math.round(mx - cx) + 'px', ty1: Math.round(my - cy) + 'px',
                  rot: (ang * 0.6).toFixed(1) + 'deg',
                };
              });
              // 同上:整表重建,不用动态 setData 键
              const nextTiles = that.data.seatTiles.map((t, i) => Object.assign({}, t, geom[i]));
              that.setData({ seatTiles: nextTiles, measuring: false });
            });
        });
      });
  },
  /** 点瓦片 = 进这家商家的**独立页**,不是半屏。
   *  这几家地理上离得很远、跨天逐个去,「打开某一家」是一次独立浏览:
   *  权益条款 / 到店体验 / 营业信息一屏塞不下,半屏也没法单独回跳。
   *  商家页零新增接口 —— 它从本页 nodes 里按 nodeId 取数据。 */
  onSeatTap(e) {
    const id = e.currentTarget.dataset.id;
    if (id == null) return;
    const node = (this.data.nodes || []).find((n) => String(n.nodeId) === String(id));
    if (this._lockedTip(node)) return;
    this._openHero(node, Number(e.currentTarget.dataset.idx));
  },
  /** 点大卡片 → 全屏故事流(这一章的剧情),不是进游戏 —— 游戏在底部那个按钮。
   *  自由探索的章节走的是「章节概述」那条路(不是城市定向的块化故事流),所以正文就是
   *  chapter.description 一个文本域:多了按空行分段、少了就一句,不硬凑。 */
  openChapStory() {
    const v = this.data.hero.node || {};
    const chapter = (this.data.chapterCards || []).find((c) => c.id != null && String(c.id) === String(v.chapterId));
    const thoughtKeys = [];
    for (const b of ((chapter && chapter.blocks) || [])) {
      if (!b) continue;
      if (b.type === 'node') {
        const node = (this.data.nodes || []).find((n) => String(n.nodeId) === String(b.nodeId));
        if (!node || !node.done) break;
      } else if (b.type === 'thought' && b.thoughtKey && thoughtKeys.indexOf(String(b.thoughtKey)) < 0) {
        thoughtKeys.push(String(b.thoughtKey));
      }
    }
    const text = String(v.chapDesc || '').trim();
    if (!text && !thoughtKeys.length) { cyToast('这一章还没写剧情'); return; }
    const paras = text.split(/\n\s*\n|\r?\n/).map((x) => x.trim()).filter(Boolean);
    // 图片块插在第一段之后(只有一段就排在它后面),和文字共用 .st-line ——
    // 用户 09-06 原话:「章节里有图片,也要和文字一样的样式」,所以入场动效必须是同一套。
    const at = paras.length > 1 ? 1 : paras.length;
    const lines = [];
    // 顶部那一大段留白里先立标题:样机靠 40% 的上内边距把第一段推到屏幕中段,
    // 空着不写东西就只是一片黑。标题也吃同一套显形动效。
    if (v.chapName) lines.push({ id: 'ttl', title: v.chapName, meta: v.chapNo || '', in: false, dir: 'up', d: 0 });
    paras.forEach((t, i) => {
      if (i === at && v.chapCover) lines.push({ id: 'img', img: v.chapCover, in: false, dir: 'up', d: 0 });
      lines.push({ id: 'p' + i, text: t, in: false, dir: 'up', d: 0 });
    });
    if (at >= paras.length && v.chapCover) lines.push({ id: 'img', img: v.chapCover, in: false, dir: 'up', d: 0 });
    this._storyTop = 0;
    audioSession.keepAwake('chapStory', true);
    this.setData({ chapStory: { show: true, open: false, title: v.chapName || '', lines, thoughtKeys,
      audio: v.audio || '', audioDur: v.audioDur || '', playing: false, barAway: false, frames: 0 } }, () => {
      this._storyTimer = setTimeout(() => {
        this.setData({ 'chapStory.open': true });
        this._settleStory();          // 首屏那几行不用等滚动,直接显形
        this._startStoryDust();
      }, 30);
    });
    this._refreshChapStoryThoughts();
    this._claimThoughts(thoughtKeys);
  },

  _refreshChapStoryThoughts() {
    if (!this.data.chapStory.show) return;
    const known = this.data.isPreview ? (this._previewThoughts || []) : ((this._routeState && this._routeState.thoughts) || []);
    const lines = this.data.chapStory.lines.filter((line) => !line.thoughtKey);
    (this.data.chapStory.thoughtKeys || []).forEach((key) => {
      const t = known.find((thought) => thought && String(thought.key) === key);
      if (!t) return;
      lines.push({ id: 'thought-' + key, thoughtKey: key, title: String(t.name || ''),
        meta: t.done ? '伏笔 · 已应验' : '伏笔 · 已获得', in: false, dir: 'up', d: 0 });
      if (t.desc) lines.push({ id: 'thought-desc-' + key, thoughtKey: key, text: String(t.desc), in: false, dir: 'up', d: 0 });
    });
    this.setData({ 'chapStory.lines': lines }, () => this._settleStory());
  },

  closeChapStory() {
    if (!this.data.chapStory.show) return;
    if (this._storyTimer) { clearTimeout(this._storyTimer); this._storyTimer = 0; }
    if (this._settleTimer) { clearTimeout(this._settleTimer); this._settleTimer = 0; }
    this._stopStoryDust();
    this._stopChapAudio();
    audioSession.keepAwake('chapStory', false);
    this.setData({ 'chapStory.open': false });
    this._storyTimer = setTimeout(() => this.setData({ 'chapStory.show': false, 'chapStory.lines': [] }), 260);
  },

  onStoryNativeBack() { this.closeChapStory(); },

  /** 这一章的音频:常驻在故事流右侧,点一下播、再点暂停。
   *  只有作者真配了 audioUrl 才渲染这颗按钮 —— 没有音频就不留一个点了没反应的壳。 */
  toggleChapAudio() {
    const st = this.data.chapStory;
    if (!st.audio) return;
    if (st.playing) { this._stopChapAudio(); return; }
    if (!this._chapAudio) {
      const ctx = audioSession.register(wx.createInnerAudioContext(), { kind: 'chapter' });
      ctx.src = st.audio;
      // 三个终态都要把按钮态收回去,否则暂停图标会一直亮着
      ctx.onPlay(() => audioSession.keepAwake('chapAudio', true));
      ctx.onPause(() => audioSession.keepAwake('chapAudio', false));
      ctx.onEnded(() => { audioSession.keepAwake('chapAudio', false); this.setData({ 'chapStory.playing': false }); });
      ctx.onStop(() => { audioSession.keepAwake('chapAudio', false); this.setData({ 'chapStory.playing': false }); });
      ctx.onError(() => { audioSession.keepAwake('chapAudio', false); this.setData({ 'chapStory.playing': false }); cyToast('这段音频放不出来'); });
      this._chapAudio = ctx;
    }
    this._chapAudio.play();
    this.setData({ 'chapStory.playing': true });
  },

  _stopChapAudio() {
    if (this._chapAudio) { audioSession.unregister(this._chapAudio); try { this._chapAudio.stop(); this._chapAudio.destroy(); } catch (e) {} this._chapAudio = null; }
    audioSession.keepAwake('chapAudio', false);
    if (this.data.chapStory.playing) this.setData({ 'chapStory.playing': false });
  },

  /** 滚动中不显形 —— tesseract 的手感就是「滑得快时屏幕上没有字」。
   *  只记方向和位移喂给粒子,显形一律等停下来那一刻(70ms 内没有新的滚动事件)。 */
  onStoryScroll(e) {
    if (!this.data.chapStory.show && !this.data.chapterFull) return;
    const now = Date.now();
    const top = e.detail.scrollTop || 0;
    if (this._storyAt != null && top === this._storyTop) return;
    const dt = Math.max(8, now - (this._storyAt || now));
    // 每帧位移(px):|Δ| / Δt × 16。样机的判据就是这个量,不是「每次事件挪了多少」——
    // 事件不保证一帧一次,直接拿差值当速度会随机器快慢漂。
    this._storyVel = Math.abs(top - (this._storyTop || 0)) / dt * 16;
    this._storyDir = top >= (this._storyTop || 0) ? 'up' : 'down';   // 往下滚 → 字从下方浮起
    this._storyTop = top;
    this._storyAt = now;
    if (!this.data.chapStory.barAway) this.setData({ 'chapStory.barAway': true });   // 滑动时把底栏收下去
    if (this._settleTimer) clearTimeout(this._settleTimer);
    // 慢滑就地显形(样机是每次 scroll 都量一遍;这里量一次要跨线程查询,
    // 每帧查会卡,所以压到下一拍再量,手感上仍是「跟着滑就浮出来」),
    // 快滑一律等停下 —— 滑得快时屏幕上没有字,这就是 tesseract 的手感。
    this._settleTimer = setTimeout(() => this._settleStory(), this._storyVel < STORY_FAST_PX ? 30 : 70);
  },

  /** 停下来这一刻,量所有行相对视口的位置:在屏内且还没显形的,让它上浮一行、由虚到实。
   *  出了屏的复位 —— 再滚回来要能重演一次,而不是「向下滚完再向上就没效果了」。 */
  _settleStory() {
    if (!this.data.chapStory.show && !this.data.chapterFull) return;
    if (this.data.chapStory.barAway) this.setData({ 'chapStory.barAway': false });   // 停下来,底栏弹回来
    const that = this;
    const chapterFull = this.data.chapterFull;
    const lines = this.data.chapterFull ? this.data.chapterParas : this.data.chapStory.lines;
    const dir = this._storyDir === 'down' ? 'down' : 'up';
    wx.createSelectorQuery().in(this)
      .select('.st-scroll').boundingClientRect()
      .selectAll('.st-line').boundingClientRect()
      .exec((r) => {
        const box = r && r[0], list = (r && r[1]) || [];
        if (!box || !list.length) return;
        if (chapterFull !== that.data.chapterFull
          || lines !== (chapterFull ? that.data.chapterParas : that.data.chapStory.lines)
          || (!chapterFull && !that.data.chapStory.show)) return;
        // 氛围跟着正文流走:取视口里最靠上那一行的基调。空值不切 —— 作者只在要换的地方
        // 插标记,没标记的段落沿用上一个基调(与原型 n.dataset.cos 的取法一致)。
        if (chapterFull) {
          const nextGame = lines.find((line, i) => line.kind === 'game' && line.auto && !line.done
            && list[i] && list[i].top < box.top + box.height * .7 && list[i].bottom > box.top + 80);
          if (nextGame && !that.data.chapterGate.show && !that.data.sessionToolsOpen) that._autoInline(nextGame.nodeId);
          for (let i = 0; i < lines.length; i += 1) {
            const it = list[i];
            if (!it || it.bottom <= box.top || it.top >= box.bottom) continue;
            that._saveReadingPos(lines[i]);
            that._syncStoryBed(lines[i]);
            break;
          }
        }
        // 终章反转:反转块一进视口,整章翻成对话框(只翻一次;重开本章复位)
        if (chapterFull && !that.data.storyChat && lines.some((ln, i) => ln.kind === 'reveal' && list[i]
          && list[i].top < box.bottom - 40 && list[i].bottom > box.top + 80)) {
          that.setData({ storyChat: true }, () => that.measureChapter());
          const s = that._storySound();
          if (s) s.sfx('page');
          if (!that.data.reducedMotion) wx.vibrateShort({ type: 'light' });
        }
        // 同一批补显形的行要**逐条错开** 50ms:一起亮就是整块淡入,不是样机那种一行一行浮起来
        let order = 0;
        const next = lines.map((ln, i) => {
          const it = list[i];
          if (!it) return ln;
          const cy = it.top + it.height / 2 - box.top;
          const inView = that.data.reducedMotion || (it.bottom > box.top + 80 && it.top < box.bottom - 40);
          if (ln.in && !inView) return { ...ln, in: false, dir: cy < 0 ? 'down' : 'up', d: 0 };
          if (!ln.in && inView) { const d = (order++) * 0.05; return { ...ln, in: true, dir, d }; }
          return ln;
        });
        // 只发显形状态中真正改变的路径,不把文字、图片与玩法配置整表重传。
        const patch = {};
        const key = chapterFull ? 'chapterParas' : 'chapStory.lines';
        next.forEach((ln, i) => {
          if (ln.in === lines[i].in) return;
          ['in', 'dir', 'd'].forEach(field => {
            if (ln[field] !== lines[i][field]) patch[key + '[' + i + '].' + field] = ln[field];
          });
        });
        if (!Object.keys(patch).length || chapterFull !== that.data.chapterFull
          || lines !== (chapterFull ? that.data.chapterParas : that.data.chapStory.lines)) return;
        that.setData(patch);
      });
  },

  /** 分层漂浮粒子。近景那层画在文字上方(第二张 canvas),会遮住字 —— 这是刻意的景深。
   *  视差幅度大于手指位移:k>1 的层跟手跑得比手快,近的才「近」。 */
  _startStoryDust() {
    this._stopStoryDust();
    if (this.data.chapterFull) return;
    if (this.data.reducedMotion) return;      // 降低动态:静止的星点即可,不跑 rAF
    // 越往后越不正常 · 第 1 档起「字偶尔闪」:隔几秒挑一段带档位的闪 90ms(不占 keyframes 额度)
    this._oddTimer = setInterval(() => {
      const odd = (this.data.chapterParas || []).filter((p) => p.odd >= 1 && p.in);
      if (!odd.length) return;
      this.setData({ oddFlick: odd[Math.floor(Math.random() * odd.length)].i });
      setTimeout(() => this.setData({ oddFlick: -1 }), 90);
    }, 4200);
    const that = this;
    const seed = [];
    // z 越大越近:更大、更实、视差更强。形状只有方/圆/三角,不画十字星、不加光晕。
    // 三层的**数量与视差系数照样机**:远景 190 颗 k=0.7、中景 20 颗 k=1.5、近景 12 颗 k=2.2。
    // 远景要足够密才是「星场」;之前 86 颗是随手写的,稀得像几粒灰。
    for (let i = 0; i < 222; i++) {
      const z = i < 190 ? 0.7 : (i < 210 ? 1.5 : 2.2);
      seed.push({ x: Math.random(), y: Math.random() * 2, z,
        s: (0.9 + Math.random() * 1.4) * z, a: (0.18 + Math.random() * 0.3) * (z > 1 ? 1 : 0.8),
        // vx/vy 的量纲是**像素每帧**,除以画布尺寸才归一化到 0..1 的坐标系。
        // 之前这里给的是 ±0.01 再除以宽度 ⇒ 每帧 0.01px,一秒挪不到一个像素,肉眼就是静止。
        shape: i % 3, vx: (Math.random() - 0.5) * 0.6, vy: (Math.random() - 0.5) * 0.4,
        k: Math.random() });   // k < 当前场景密度 n 才画:同一批星点,场景稀了就隐掉一部分
    }
    // 原型的散景:12 团大光斑,只在「城市」那一景出现
    this._bokeh = [];
    for (let i = 0; i < 12; i++) {
      this._bokeh.push({ x: Math.random(), y: Math.random(), r: 30 + Math.random() * 60, warm: Math.random() > 0.5, v: (Math.random() - 0.5) * 0.04 });
    }
    this._dust = seed;
    const paint = (id, near) => wx.createSelectorQuery().in(this).select('#' + id)
      .fields({ node: true, size: true }).exec((res) => {
        const item = res && res[0]; if (!item || !item.node || that._dust !== seed) return;
        const cv = item.node, ctx = cv.getContext('2d');
        that._dustCanvases = that._dustCanvases || {};
        that._dustCanvases[id] = cv;
        const dpr = (wx.getWindowInfo && wx.getWindowInfo().pixelRatio) || 2;
        cv.width = item.width * dpr; cv.height = item.height * dpr; ctx.scale(dpr, dpr);
        const W = item.width, H = item.height;
        const loop = () => {
          // show 落 false 比 _stopStoryDust 置 null 晚 260ms(关层动画),窗口内这帧还会跑到 —— 两个都要守
          if ((!that.data.chapStory.show && !that.data.chapterFull) || that._dust !== seed) return;
          ctx.clearRect(0, 0, W, H);
          const off = (that._storyTop || 0);
          // 换景:每帧往目标场景插一步(原型 k=0.025),只在远景那张画布上推进,近景读同一份
          if (!near) that._sceneCur = storyScene.step(that._sceneCur || storyScene.initial(that._sceneTarget || 'city'),
            that._sceneTarget || 'city', 0.025);
          const cu = that._sceneCur || storyScene.initial('city');
          if (!near) that._paintSceneLight(ctx, W, H, cu);
          const rgb = 'rgb(' + cu.col.map(Math.round).join(',') + ')';
          for (const p of that._dust) {
            if ((p.z > 1.2) !== near) continue;
            if (p.k > cu.n) continue;
            // 自己慢慢漂 + 场景的风向(原型 vx/vy × z × 1.2,量纲同为像素每帧)
            p.x += (p.vx * 0.4 + cu.vx * p.z * 1.2) / W; p.y += (p.vy * 0.4 + cu.vy * p.z * 1.2) / H;
            if (p.x < -0.05) p.x = 1.05; if (p.x > 1.05) p.x = -0.05;
            const y = ((p.y * H - off * p.z) % (H * 2) + H * 2) % (H * 2) - H * 0.5;
            ctx.globalAlpha = Math.min(1, p.a * cu.alpha / 0.5);
            ctx.fillStyle = rgb;                                /* 颜色随场景(原型 SC.col) */
            const x = p.x * W, s = p.s * cu.size;
            if (p.shape === 0) ctx.fillRect(x, y, s, s);
            else if (p.shape === 1) { ctx.beginPath(); ctx.arc(x, y, s / 2, 0, 6.2832); ctx.fill(); }
            else { ctx.beginPath(); ctx.moveTo(x, y - s / 2); ctx.lineTo(x + s / 2, y + s / 2);
              ctx.lineTo(x - s / 2, y + s / 2); ctx.closePath(); ctx.fill(); }
          }
          // ⚠️ 只在预览态回写帧计数:静态截图看不出「粒子有没有在动」,
          //    而这正是之前漏掉的 bug(漂移量纲写错,每帧只挪 0.01px,看着完全静止)。
          //    生产态不写 —— 一秒一次 setData 是白花的开销。
          if (that.data.isPreview) {
            that._dustFrames = (that._dustFrames || 0) + 1;
            if (that._dustFrames % 60 === 0) that.setData({ 'chapStory.frames': that._dustFrames });
          }
          that['_raf' + id] = cv.requestAnimationFrame(loop);
        };
        loop();
      });
    paint('stBack', false);
    paint('stFront', true);
  },

  /** 存档:读到的段落(同一段不重复写)。预览 / 缺身份时 scope 取不到 key,自然不存。 */
  _saveReadingPos(line) {
    if (!line || this.data.isPreview || this.data.isMock) return;
    const chapterId = String((this.data.chapter || {}).id || '');
    const mark = chapterId + ':' + line.i;
    if (!chapterId || this._lastReadingMark === mark) return;
    this._lastReadingMark = mark;
    storyResume.patch(this._runSessionScope(), { pos: { chapterId, i: line.i, nodeId: line.nodeId ? String(line.nodeId) : '' } });
  },
  /**
   * 中途退出重进(每次进页面只做一次):退出时开着玩法 → 直接重开它;否则滚回上次读到的那一段。
   * 那一段已经不在(作者改过、或本章还没解锁到那)就留在章首,不乱跳。
   */
  _resumeStory() {
    if (!this._isPlayAccountCurrent() || this._resumeTried || this.data.isPreview || this.data.isMock) return;
    this._resumeTried = true;
    const saved = storyResume.read(this._runSessionScope());
    const paras = this.data.chapterParas || [];
    const openId = String(saved.openNodeId || '');
    const open = openId && (this.data.nodes || []).find((n) => String(n.nodeId) === openId && !n.done);
    if (open && paras.some((p) => p.kind === 'game' && String(p.nodeId) === openId)) {
      this.startStoryGame({ currentTarget: { dataset: { nodeid: openId } } });
      return;
    }
    const pos = saved.pos;
    if (!pos || String(pos.chapterId) !== String((this.data.chapter || {}).id)) return;
    const hit = pos.nodeId ? paras.find((p) => String(p.nodeId) === pos.nodeId) : paras.find((p) => p.i === Number(pos.i));
    if (!hit || hit.i === 0) return;
    const anchor = hit.nodeId ? 'chapter-game-' + hit.nodeId : 'chapter-block-' + hit.i;
    const sessionKey = this._sessionKey();
    const chapterId = String((this.data.chapter || {}).id || '');
    const epoch = this._runRestoreEpoch;
    this.setData({ chapterGameAnchor: '' }, () => {
      if (!this._isPlayAccountCurrent() || epoch !== this._runRestoreEpoch
          || sessionKey !== this._sessionKey()
          || chapterId !== String((this.data.chapter || {}).id || '')) return;
      this.setData({ chapterGameAnchor: anchor });
    });
  },

  /** 场景的光:径向光晕 / 斜光束 / 散景(原型 cosmos 引擎的前三层,底色交给章节氛围的 CSS)。 */
  _paintSceneLight(ctx, W, H, cu) {
    ctx.globalAlpha = 1;
    if (cu.glow > 0.005) {
      const g = ctx.createRadialGradient(cu.gx * W, cu.gy * H, 0, cu.gx * W, cu.gy * H, Math.max(W, H) * 0.75);
      g.addColorStop(0, 'rgba(' + cu.gcol.map(Math.round).join(',') + ',' + cu.glow + ')');
      g.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    }
    if (cu.beam > 0.005) {
      const g = ctx.createLinearGradient(W, 0, 0, H);
      g.addColorStop(0, 'rgba(255,250,235,' + cu.beam + ')');
      g.addColorStop(0.6, 'rgba(255,250,235,' + cu.beam * 0.25 + ')');
      g.addColorStop(1, 'rgba(255,250,235,0)');
      ctx.fillStyle = g; ctx.beginPath(); ctx.moveTo(W * 0.55, 0); ctx.lineTo(W, 0); ctx.lineTo(W * 0.35, H); ctx.lineTo(-W * 0.15, H); ctx.closePath(); ctx.fill();
    }
    if (cu.bokeh > 0.01) (this._bokeh || []).forEach((b) => {
      b.x += b.v / 60; if (b.x < -0.1) b.x = 1.1; if (b.x > 1.1) b.x = -0.1;
      const g = ctx.createRadialGradient(b.x * W, b.y * H, 0, b.x * W, b.y * H, b.r);
      const col = b.warm ? '255,190,120' : '140,180,255';
      g.addColorStop(0, 'rgba(' + col + ',' + 0.13 * cu.bokeh + ')'); g.addColorStop(1, 'rgba(' + col + ',0)');
      ctx.fillStyle = g; ctx.fillRect(b.x * W - b.r, b.y * H - b.r, b.r * 2, b.r * 2);
    });
  },

  _stopStoryDust() {
    clearInterval(this._oddTimer);
    this._dust = null;
    Object.keys(this._dustCanvases || {}).forEach(id => {
      const cv = this._dustCanvases[id];
      if (this['_raf' + id]) cv.cancelAnimationFrame(this['_raf' + id]);
      this['_raf' + id] = 0;
    });
    this._dustCanvases = null;
  },

  /** 店铺卡 → 店铺分身对话。开场是一句居中大字问候,商家自己写了 greeting 就用他的,
   *  没写才回落到按时段拼的那句 —— 不要用模板顶掉作者写过的话。 */
  openShopNpc() {
    const v = this.data.hero.node || {};
    if (!v.nodeId) { cyToast('这家店还没有可对话的分身'); return; }
    if (!v.npcName) { cyToast('这家还没有店铺分身'); return; }
    const node = (this.data.nodes || []).find((n) => String(n.nodeId) === String(v.nodeId)) || {};
    const h = new Date().getHours();
    const hi = (h < 6 ? '凌晨好' : h < 11 ? '早上好' : h < 13 ? '中午好' : h < 18 ? '下午好' : '晚上好');
    const authored = node.npc && node.npc.greeting ? String(node.npc.greeting).trim() : '';
    this.setData({
      shopNpc: {
        show: true, open: false, name: v.npcName,
        greeting: authored || (hi + '，我是' + v.npcName + '，欢迎来到' + (v.name || '我们店铺')),
        started: false, msgs: [], input: '', thinking: false, thinkPhase: 0, nodeId: v.nodeId, ry: 0, recording: false,
      },
    }, () => { this._npcTimer = setTimeout(() => { this.setData({ 'shopNpc.open': true }); this._startNpcFigure(); }, 30); });
  },

  /** 画那个人形。几何由 utils/npc-figure 生成(纯函数,可单测),这里只负责上色与绘制 ——
   *  色值必须留在本文件:玩家端整页单色有契约扫描,扫的是这几个文件。 */
  _startNpcFigure() {
    const that = this;
    wx.createSelectorQuery().in(this).select('#npcFig').fields({ node: true, size: true }).exec((res) => {
      const item = res && res[0];
      if (!item || !item.node) return;
      const cv = item.node, ctx = cv.getContext('2d');
      let dpr = 2; try { dpr = wx.getWindowInfo().pixelRatio || 2; } catch (e) {}
      cv.width = item.width * dpr; cv.height = item.height * dpr; ctx.scale(dpr, dpr);
      const W = item.width, H = item.height;
      const model = npcFigure.buildFigure();
      const draw = (ry, bob) => {
        ctx.clearRect(0, 0, W, H);
        ctx.save(); ctx.translate(0, bob);
        const proj = npcFigure.project(model.verts, ry, W, H);
        const faces = npcFigure.shadeFaces(model.verts, model.faces, ry, proj);
        for (const f of faces) {
          const t = model.faces[f.idx];
          const a = proj.p[t[0]], b = proj.p[t[1]], c = proj.p[t[2]];
          // ⚠️ 必须用**不透明**灰阶。半透明填充下,补缝用的重叠区会把 alpha 叠一倍 ——
          //    实拍两轮都栽在这:先是描边描出线框人,再是微膨胀涨出亮网格。
          //    不透明之后重叠只是同色重画,缝彻底看不见了。整体轻重交给画布的 opacity。
          const g = Math.round(28 + f.shade * 86);   // 28..114:再暗就比下面的名字还弱,形象反而成了背景
          ctx.fillStyle = 'rgb(' + g + ',' + g + ',' + g + ')';  /* ds-ok 人形的体积明暗,中性灰阶不是色板色 */
          // 沿质心把三角微微涨 0.4px,盖住抗锯齿留下的发丝缝
          const mx = (a[0] + b[0] + c[0]) / 3, my = (a[1] + b[1] + c[1]) / 3;
          ctx.beginPath();
          for (const q of [a, b, c]) {
            const dx = q[0] - mx, dy = q[1] - my, L = Math.sqrt(dx * dx + dy * dy) || 1;
            const px = q[0] + dx / L * 0.4, py = q[1] + dy / L * 0.4;
            if (q === a) ctx.moveTo(px, py); else ctx.lineTo(px, py);
          }
          ctx.closePath(); ctx.fill();
        }
        ctx.restore();
      };
      that._drawNpcFig = draw;
      that._npcCanvas = cv;          // 收起时要拿它 cancelAnimationFrame,不能只把句柄置 0
      if (that.data.reducedMotion) { draw(that.data.shopNpc.ry || 0, 0); return; }
      const t0 = Date.now();
      const loop = () => {
        if (!that.data.shopNpc.show) return;
        draw(that.data.shopNpc.ry || 0, Math.sin((Date.now() - t0) / 1400) * 5);   // 呼吸式浮动
        that._rafNpcFig = cv.requestAnimationFrame(loop);
      };
      loop();
    });
  },

  /** 真取消,不是只把句柄置 0:loop 里那句 `if (!show) return` 只在**下一帧**才生效,
   *  置 0 期间画布仍在跑一帧。canvas 的 rAF 要用同一个 canvas 节点去 cancel。 */
  _stopNpcFigure() {
    if (this._npcCanvas && this._rafNpcFig) {
      try { this._npcCanvas.cancelAnimationFrame(this._rafNpcFig); } catch (e) {}
    }
    this._drawNpcFig = null; this._rafNpcFig = 0; this._npcCanvas = null;
  },

  /** 形象跟手左右转。几何是真的绕 Y 轴转,不是把一张平面剪影压扁 ——
   *  ±38° 是样机定的幅度,再大就看见后脑勺,分身「在跟你说话」的感觉就没了。 */
  onNpcTouchStart(e) { this._npcX = e.touches[0].pageX; this._npcRy0 = this.data.shopNpc.ry || 0; },
  onNpcTouchMove(e) {
    if (this._npcX == null) return;
    const dx = e.touches[0].pageX - this._npcX;
    const ry = Math.max(-38, Math.min(38, (this._npcRy0 || 0) + dx * 0.35));
    if (Math.abs(ry - (this.data.shopNpc.ry || 0)) >= 1) this.setData({ 'shopNpc.ry': Math.round(ry) });
  },
  onNpcTouchEnd() { this._npcX = null; },

  closeShopNpc() {
    this._cancelShopRecording();
    if (!this.data.shopNpc.show) return;
    this._shopNpcConversationEpoch = (this._shopNpcConversationEpoch || 0) + 1;
    if (this._npcTimer) { clearTimeout(this._npcTimer); this._npcTimer = 0; }
    this._stopThinkBeat();
    this._stopShopNpcVoice();
    this._stopNpcFigure();
    this.setData({ 'shopNpc.open': false });
    this._npcTimer = setTimeout(() => this.setData({ 'shopNpc.show': false }), 260);
  },

  /** 点输入框问候语就让位 —— 和样机一致:大字只在还没开口时占着中间那块地方。 */
  onShopNpcFocus() { if (!this.data.shopNpc.started) this.setData({ 'shopNpc.started': true }); },
  onShopNpcInput(e) { this.setData({ 'shopNpc.input': e.detail.value }); },

  /** 按住说话。松开就上传转写,转写出来的文字**走和打字完全相同**的那条链路 ——
   *  所以「点了语音就回不了打字」在结构上不成立:输入框一直在,语音只是它旁边的另一个入口。
   *  ⚠️ 这里直连 wx.uploadFile 而不走 app.getUploadClient():那条通道的契约要求返回
   *  文件 URL(它是给要落 OSS 的资产用的),而语音片段是临时的、刻意不持久化。 */
  _isShopRecordingCurrent(intent) {
    const conversation = this.data.shopNpc;
    return !!intent && this._shopRecordingIntent === intent && !intent.cancelled
      && this._isPlayAccountCurrent() && intent.owner === this._playOwner
      && intent.sessionKey === this._sessionKey()
      && intent.epoch === this._shopNpcConversationEpoch && conversation === intent.conversation
      && conversation.show && conversation.open !== false && conversation.nodeId === intent.nodeId;
  },
  _releaseShopRecording(intent) {
    if (intent.rec) {
      try { if (intent.rec.offStop) intent.rec.offStop(intent.onStop); } catch (e) {}
      try { if (intent.rec.offError) intent.rec.offError(intent.onError); } catch (e) {}
    }
    if (this._shopRecordingIntent === intent) this._shopRecordingIntent = null;
  },
  _cancelShopRecording() {
    const intent = this._shopRecordingIntent;
    if (!intent) return;
    const current = this._isShopRecordingCurrent(intent);
    intent.cancelled = true;
    if (current) this.setData({ 'shopNpc.recording': false });
    if (!intent.rec) { this._releaseShopRecording(intent); return; }
    // Keep its terminal listener until stop settles; a new press must not consume this stop event.
    if (intent.stopping) return;
    intent.stopping = true;
    try { intent.rec.stop(); } catch (e) { this._releaseShopRecording(intent); }
  },
  onVoiceStart() {
    const s = this.data.shopNpc;
    if (!this._isPlayAccountCurrent() || !s.show || s.open === false || s.thinking || this._shopRecordingIntent) return;
    const intent = { owner: this._boundPlayOwner(), conversation: s, nodeId: s.nodeId,
      epoch: this._shopNpcConversationEpoch, sessionKey: this._sessionKey() };
    this._shopRecordingIntent = intent;
    wx.authorize({ scope: 'scope.record' })
      .then(() => {
        if (this._isShopRecordingCurrent(intent)) this._beginRecord(intent);
        else this._releaseShopRecording(intent);
      })
      .catch(() => {
        const current = this._isShopRecordingCurrent(intent);
        this._releaseShopRecording(intent);
        if (current) cyToast('没有录音权限，先在设置里打开');
      });
  },

  _beginRecord(intent) {
    if (!this._isShopRecordingCurrent(intent)) return;
    const rec = wx.getRecorderManager();
    this._rec = rec; intent.rec = rec;
    intent.onStop = (res) => {
      const current = this._isShopRecordingCurrent(intent);
      this._releaseShopRecording(intent);
      if (!current) return;
      this.setData({ 'shopNpc.recording': false });
      if (!res || !res.tempFilePath || (res.duration || 0) < 500) return;
      this._sendVoice(res.tempFilePath);
    };
    intent.onError = () => {
      const current = this._isShopRecordingCurrent(intent);
      this._releaseShopRecording(intent);
      if (!current) return;
      this.setData({ 'shopNpc.recording': false }); cyToast('录音出错了');
    };
    rec.onStop(intent.onStop); rec.onError(intent.onError);
    try {
      rec.start({ duration: 60000, format: 'mp3', sampleRate: 16000, numberOfChannels: 1 });
      this.setData({ 'shopNpc.recording': true, 'shopNpc.started': true });
    } catch (e) { intent.onError(); }
  },

  onVoiceEnd() {
    const intent = this._shopRecordingIntent;
    if (!intent) return;
    if (!intent.rec) { intent.cancelled = true; this._releaseShopRecording(intent); return; }
    if (intent.stopping) return;
    intent.stopping = true;
    try { intent.rec.stop(); } catch (e) { intent.onError(); }
  },

  /** 语音走**统一上传通道的 uploadOne**,不裸调 wx.uploadFile ——
   *  裸调会漏掉认证头构造,而这条链路是要花钱的写操作。
   *  这里用 uploadOne 而不是 uploadAll:后者强制结果里带文件地址,而语音刻意不落盘,
   *  返回的是分身的回答。 */
  _shopNpcRequestContext() {
    const conversation = this.data.shopNpc;
    if (!this._isPlayAccountCurrent() || !conversation || !conversation.show || conversation.open === false) return null;
    const sequence = this._shopNpcRequestSequence = (this._shopNpcRequestSequence || 0) + 1;
    return { conversation, nodeId: conversation.nodeId, sequence, epoch: this._shopNpcConversationEpoch,
      sessionKey: this._sessionKey(), settled: false };
  },

  _isShopNpcRequestCurrent(context) {
    const conversation = this.data.shopNpc;
    return !!context && !context.settled && this._isPlayAccountCurrent()
      && context.sequence === this._shopNpcRequestSequence && context.epoch === this._shopNpcConversationEpoch
      && context.sessionKey === this._sessionKey() && conversation === context.conversation
      && conversation.show && conversation.open !== false && conversation.nodeId === context.nodeId;
  },

  _sendVoice(filePath) {
    const context = this._shopNpcRequestContext();
    if (!context) return;
    const s = context.conversation;
    const that = this;
    this.setData({ 'shopNpc.thinking': true });
    this._startThinkBeat();
    app.getUploadClient().uploadOne(filePath, {
      path: '/api/ai/npc/voice-chat',
      formData: { nodeId: s.nodeId, requestId: uuid4() },
      onDone(r) {
        if (!that._isShopNpcRequestCurrent(context)) return;
        context.settled = true;
        if (!r.ok) { that._pushShopNpcReply(r.msg || '没听清，再说一次'); return; }
        const d = r.data;
        // 先把识别出的原话作为我方消息补上 —— 玩家要能看见自己被听成了什么
        const asr = d.asr || '（语音）';
        const msgs = that.data.shopNpc.msgs.concat([{ id: that.data.shopNpc.msgs.length + 1, role: 'me', text: asr }]);
        that.setData({ 'shopNpc.msgs': msgs });
        const reply = (d.data && (d.data.safeText || d.data.text)) || '';
        that._pushShopNpcReply(reply || '这个我说不好，你到店里当面问我一次。', undefined,
          d.data && d.data.audioUrl);
      },
    });
  },

  /** 物理返回:对话层是页内层,不能让返回键直接退出整页。 */
  onShopNpcNativeBack() { this.closeShopNpc(); },

  /** 问一句。requestId 由本端生成,后端按 (user, requestId) 幂等 ——
   *  网络抖动重发不会二次调用模型、不会二次计费。 */
  sendShopNpc() {
    const s = this.data.shopNpc;
    const text = String(s.input || '').trim();
    if (!text || s.thinking) return;
    const msgs = s.msgs.concat([{ id: s.msgs.length + 1, role: 'me', text }]);
    this.setData({ 'shopNpc.msgs': msgs, 'shopNpc.input': '', 'shopNpc.thinking': true, 'shopNpc.started': true });
    this._startThinkBeat();
    this._askShopNpc(text);
  },

  /** 真正发问那一段。打字与「重答」共用 —— 兜底文案的口径只能有一份。
   *  @param replaceIdx 传了就改写这一条(重答),不传则追加一条(正常问答)。 */
  _askShopNpc(text, replaceIdx) {
    const context = this._shopNpcRequestContext();
    if (!context) return;
    const that = this;
    const requestId = uuid4();
    req('/api/ai/npc/shop-chat', 'POST', { requestId, nodeId: context.nodeId, message: text }, { 'content-type': 'application/json' }).then((r) => {
      if (!that._isShopNpcRequestCurrent(context)) return;
      context.settled = true;
      const d = (r && r.data) || {};
      // safeText 是后端在拒绝/失败时给的安全兜底文案;正常回答也走同一个字段。
      const reply = d.safeText || d.text || '';
      // ⚠️ 重答失败时**不能覆盖**原来那条:玩家点「重答」是想要个更好的答案,
      //    结果把已经拿到的好答案换成一句「网络异常」,等于为了再问一次把答案弄丢了。
      //    失败就保住原文,错误走轻提示。(2026-09-08 实拍抓到)
      if (!reply && replaceIdx != null) {
        that._stopThinkBeat();
        that.setData({ 'shopNpc.thinking': false, 'shopNpc.thinkPhase': 0 });
        cyToast(r && r.msg ? String(r.msg) : '没问成，原来那条还在');
        return;
      }
      // ⚠️ 接口被闸掉(店铺分身对话默认关)或没配人设时,不要编一句「我有点忙」——
      //    那是假话,玩家会一直重试。把服务端说的原话端出来。
      that._pushShopNpcReply(reply || (r && r.msg ? String(r.msg) : '这个我说不好，你到店里当面问我一次。'),
        replaceIdx, d.audioUrl);
    });
  },

  /** @param replaceIdx 传了就改写这一条(重答),不传则追加。 */
  _pushShopNpcReply(text, replaceIdx, audioUrl) {
    this._stopThinkBeat();
    if (!this.data.shopNpc.show) return;   // 已经收起来了就别再往里塞
    const old = this.data.shopNpc.msgs;
    const target = old[replaceIdx];
    const msgs = target && target.role === 'ai'
      // id 保持不变:换了 id 会让 wx:key 判成新节点,整条重新入场闪一下
      ? old.map((m, i) => (i === replaceIdx ? { ...m, text } : m))
      : old.concat([{ id: old.length + 1, role: 'ai', text }]);
    this.setData({ 'shopNpc.msgs': msgs, 'shopNpc.thinking': false, 'shopNpc.thinkPhase': 0 });
    this._playShopNpcVoice(audioUrl);
  },

  _playShopNpcVoice(audioUrl) {
    if (!audioUrl) return;
    this._stopShopNpcVoice();
    const audio = audioSession.register(wx.createInnerAudioContext(), { kind: 'npc' });
    this._shopNpcVoiceAudio = audio;
    audio.src = audioUrl;
    audio.onEnded(() => this._stopShopNpcVoice());
    audio.onError(() => this._stopShopNpcVoice());
    audio.play();
  },

  _stopShopNpcVoice() {
    if (!this._shopNpcVoiceAudio) return;
    audioSession.unregister(this._shopNpcVoiceAudio);
    try { this._shopNpcVoiceAudio.stop(); this._shopNpcVoiceAudio.destroy(); } catch (e) {}
    this._shopNpcVoiceAudio = null;
  },

  /** 等待名条的第二拍:「{名} 正在输入…」→「正在回答」。
   *  一句话不动地挂着,超过一秒就像卡住了;换一拍是在说「还在,只是慢」。 */
  _startThinkBeat() {
    this._stopThinkBeat();
    this.setData({ 'shopNpc.thinkPhase': 0 });
    this._thinkTimer = setTimeout(() => {
      if (this.data.shopNpc.thinking) this.setData({ 'shopNpc.thinkPhase': 1 });
    }, 1200);
  },

  _stopThinkBeat() {
    if (this._thinkTimer) { clearTimeout(this._thinkTimer); this._thinkTimer = 0; }
  },

  /** 复制这一条回答。分身说的话经常是地址、年份、暗号 —— 记不住就得能带走。 */
  copyNpcMsg(e) {
    const text = String(e.currentTarget.dataset.text || '');
    if (!text) return;
    wx.setClipboardData({ data: text });
  },

  /** 重答:把这条回答**之前**最近的那句我方提问原样再问一次,新答案**改写原来那一条**。
   *  两条都不做:不写回输入框(会覆盖玩家正在打的字)、不追加一条新回答
   *  (同一个问题挂两个答案,读起来像分身自言自语说了两遍)。 */
  retryNpcMsg(e) {
    const s = this.data.shopNpc;
    if (s.thinking) return;
    const idx = Number(e.currentTarget.dataset.idx);
    let ask = '';
    for (let i = idx - 1; i >= 0; i--) {
      if (s.msgs[i] && s.msgs[i].role === 'me') { ask = s.msgs[i].text; break; }
    }
    if (!ask) return;
    this.setData({ 'shopNpc.thinking': true });
    this._startThinkBeat();
    this._askShopNpc(ask, idx);
  },

  /** 卡片飞到位之后自转一整圈:一圈之内正面、右脊、背面、左脊各露一次,
   *  「这张卡是有厚度的、背面写着章节」这件事不用文案说,转一圈就看见了。
   *  ⚠️ 用定时器逐帧推 --ry 而不是 CSS animation:转完要**停在 0°**并接上呼吸浮动,
   *  animation 结束回到初值会闪一下,而且中途被手指接管时没法平滑接续。 */
  _spinHeroCard() {
    if (this.data.reducedMotion) { this.setData({ 'hero.spun': true }); return; }
    const that = this;
    if (this._spinTimer) clearTimeout(this._spinTimer);
    this._spinTimer = setTimeout(() => {
      const t0 = Date.now(), DUR = 1100;
      const step = () => {
        if (!that.data.hero.show) return;
        if (that._cardDrag) { that.setData({ 'hero.spun': true }); return; }   // 手指接管:角度交给拖动
        const p = Math.min(1, (Date.now() - t0) / DUR);
        // easeInOutCubic:起步慢、中段快、收尾稳,跟样机同一条
        const e = p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
        const ry = Math.round(360 * e) % 360;
        that.setData({ 'hero.ry': ry, 'hero.turned': turnedAt(ry) });
        if (p < 1) { that._spinTimer = setTimeout(step, 16); }   // 复用同一个句柄:延时与逐帧不会同时在跑,收起时才真能取消
        else { that.setData({ 'hero.ry': 0, 'hero.spun': true, 'hero.turned': false }); }   // 收在正面,再交给呼吸浮动
      };
      step();
    }, 350);
  },

  /** 跟手左右转。转的是卡片自己,±180° 之内随便转 —— 背面本来就是给人看的。 */
  onCardTouchStart(e) {
    this._cardDrag = true; this._cardAxis = '';
    this._cardX = e.touches[0].pageX; this._cardY = e.touches[0].pageY;
    this._cardRy0 = this.data.hero.ry || 0; this._cardTop0 = this._heroScrollTop || 0;
  },
  /** 卡片压在正文之上,所以竖向滑动落不到 scroll-view 上 —— 这里按首个明显位移判轴:
   *  横向转卡片,竖向代它把正文滚起来(scroll-top 受控)。不这么做的话,
   *  手指落在卡片上就滚不动页面,而卡片又占了上半屏。 */
  onCardTouchMove(e) {
    if (!this._cardDrag) return;
    const dx = e.touches[0].pageX - this._cardX, dy = e.touches[0].pageY - this._cardY;
    if (!this._cardAxis) {
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
      this._cardAxis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
    }
    if (this._cardAxis === 'y') {
      const top = Math.max(0, (this._cardTop0 || 0) - dy);
      this.setData({ 'hero.scrollTop': Math.round(top) });
      return;
    }
    const ry = (this._cardRy0 || 0) + dx * 0.8;
    if (Math.abs(ry - (this.data.hero.ry || 0)) >= 1) this.setData({ 'hero.ry': Math.round(ry), 'hero.turned': turnedAt(ry) });
  },
  /** 松手吸附到最近的一面。停在 40° 这类角度上只能看见一条厚度,既不是正面也不是背面 ——
   *  样机松手也是吸附到正/背面的。 */
  onCardTouchEnd() {
    this._cardDrag = false;
    if (this._cardAxis === 'y') { this._cardAxis = ''; return; }   // 竖向那次是在滚页面,别去动转角
    this._cardAxis = '';
    const ry = this.data.hero.ry || 0;
    const snap = Math.round(ry / 180) * 180;
    this.setData({ 'hero.ry': snap, 'hero.spun': true, 'hero.turned': turnedAt(snap) });
  },

  /** 正文往上滚时,卡片缩小、左移、淡出,顶栏换成一条「缩略图 + 店名」的紧凑条。
   *  与样机同一条曲线:0→220px 映射到 p 0→1,缩到 0.12,越过 0.85 顶栏接管。
   *  ⚠️ 正文的滚动区从顶栏下面就开始(内容自己顶一段占位),卡片才可能被内容盖过去;
   *  把滚动区压到卡片下方的话,卡片缩走会在上面留下一块空背景。 */
  onHeroScroll(e) {
    const top = e.detail.scrollTop || 0;
    this._heroScrollTop = top;
    const p = Math.min(1, Math.max(0, top / 220));
    // 只缩不移:transform-origin 是 0 0,收缩方向本来就朝卡片左上角(顶栏缩略图那一侧),
    // 再叠一个左移会把它推出屏幕外。
    const next = { sx: 0, ss: (1 - 0.88 * p).toFixed(3), faded: p > 0.92, collapsed: p > 0.85 };
    const cur = this.data.hero;
    if (cur.sx === next.sx && cur.ss === next.ss && cur.collapsed === next.collapsed) return;
    this.setData({ 'hero.sx': next.sx, 'hero.ss': next.ss, 'hero.faded': next.faded, 'hero.collapsed': next.collapsed });
  },

  /** 导航行:整卡是进分身对话,这一行单独接管,catchtap 在 wxml 那侧靠独立 view 分开。
   *  没有坐标就不假装能导航 —— 提示一句比打开一张空白地图诚实。 */
  openHeroNav() {
    const node = (this.data.nodes || []).find((n) => String(n.nodeId) === String(this.data.hero.node.nodeId));
    const lng = node && Number(node.longitude), lat = node && Number(node.latitude);
    if (!lng || !lat) { cyToast('这家还没标坐标'); return; }
    wx.openLocation({ longitude: lng, latitude: lat, name: node.name || '', address: node.address || '', scale: 18 });
  },

  /** 卡片详情的正文投影。三件事分明:章节是章节的、店铺是店铺的、游戏是游戏的 ——
   *  混着写玩家分不清「这段讲的是这一章」还是「这段讲的是这个玩法」。
   *  ⚠️ 只读已下发字段,不在这里补默认文案:没有的段直接不渲染,比编一句占位诚实。 */
  _heroView(node) {
    const chap = ((this.data.chapterCards || []).filter((c) => c.id != null
      && String(c.id) === String(node.chapterId))[0]) || null;
    // 「怎么玩」= 商家/主办方写的规则说明,按换行拆条;拆不出多条就当一段正文。
    const howto = String(node.rule || '').split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
    const perk = node.perk || null;
    const meta = [];
    if (node.duration) meta.push({ icon: 'clock', text: node.duration });
    if (node.players) meta.push({ icon: 'mtab-customers', text: node.players });
    if (perk && perk.name) meta.push({ icon: 'gift', text: perk.name });
    return {
      nodeId: node.nodeId,
      chapterId: node.chapterId, isMerchant: Number(node.merchantId) > 0,
      name: node.name, imgUrl: String(node.imgUrl || '').split(',')[0], done: !!node.done,
      // ① 章节
      chapMeta: chap ? ('本章 · ' + (chap.meta || '')) : '',
      chapNo: chap ? (chap.meta || '') : '',      // 卡面眉标用的是不带「本章」前缀的那一版
      chapName: chap ? (chap.title || '') : '',
      chapDesc: chap ? (chap.description || chap.sub || '') : '',
      audio: node.audio || '', audioDur: node.audioDur || '',
      chapCover: (chap && chap.cover) || String(node.imgUrl || '').split(',')[0] || '',
      playName: node.gameTitle || '',
      // ⚠️ 这里**不再**把整章正文分段下发到详情:章节正文只出现在故事流里。
      // 详情重复念一遍会让「点进故事流」失去理由(用户 9-07 口径:「章节详情里不写章节内容」)。
      // 卡片背面只承载开头那一段(卡面装不下整章,长章节会溢出去)
      chapLead: (chap ? String(chap.description || chap.sub || '') : '')
        .split(/\n\s*\n|\r?\n/).map((x) => x.trim()).filter(Boolean)[0] || '',
      hook: node.hookText || '',
      // ② 店铺
      address: node.address || '', businessTime: node.businessTime || '',
      npcName: node.npc && node.npc.name ? node.npc.name : '',
      // ③ 游戏
      gameKind: node.vm === 2 ? '拍照任务' : (node.vm === 3 || node.vm === 1 ? '答题' : (node.vm === 4 ? '现场打卡' : '')),
      gameTitle: node.gameTitle || '', gameMeta: meta,
      howto, materials: node.materials || '',
      // ④ 到店三步:第一步到店、第二步凭证、第三步商家核销
      stepArrive: !!node.arrived, stepProof: !!node.gameDone, stepRedeem: !!node.done,
      // ⑤ 本店权益
      perkName: perk ? (perk.name || '') : '',
      perkRule: perk ? (perk.redeemRule || '到店出示核销') : '',
      perkValid: (function () {
        const t = perk && perk.validEnd ? chinaParts(perk.validEnd) : null;
        return t ? ('有效期至 ' + t.month + '/' + t.day) : '';
      })(),
    };
  },

  /** 点卡 → hero:量到被点瓦片的视口矩形,克隆一张放在原位,下一帧过渡到目标位。
   *  目标位 = 页边距内居中、顶在 contentTop 之下、等比放大(非等比会把封面拉变形),
   *  高度不超过半屏,给下面的正文留地方。量不到矩形(布局没稳)就退回直接跳商家页 —— 动效永远不能挡住办事。 */
  _openHero(node, idx) {
    if (!node || this.data.hero.show) return;
    const tile = (this.data.seatTiles || [])[idx];
    // 发牌播放中不开:此刻量到的是半空中的矩形
    if (tile && tile.fly && !tile.land && !this.data.packOpen) return;
    const that = this;
    wx.createSelectorQuery().selectAll('.fx-tile').boundingClientRect().exec((r) => {
      const from = r && r[0] && r[0][idx];
      if (!from || !from.width) { that.heroEnter({ currentTarget: { dataset: { id: node.nodeId } } }); return; }
      let win = { windowWidth: 375, windowHeight: 812 };
      try { win = wx.getWindowInfo(); } catch (e2) {}
      const rpx = (win.windowWidth || 375) / 750;
      const top0 = that.data.chrome.contentTop + 12 * rpx;
      const s = Math.min((win.windowWidth - 64 * rpx) / from.width, (win.windowHeight * 0.46) / from.height);
      const tw = from.width * s, th = from.height * s;
      const hx = Math.round((win.windowWidth - tw) / 2 - from.left), hy = Math.round(top0 - from.top);
      const style = 'left:' + Math.round(from.left) + 'px;top:' + Math.round(from.top) + 'px;'
        + 'width:' + Math.round(from.width) + 'px;height:' + Math.round(from.height) + 'px;'
        + '--hx:' + hx + 'px;--hy:' + hy + 'px;--hs:' + s.toFixed(4);
      const view = Object.assign({}, tile || {}, that._heroView(node));
      that.setData({ hero: { show: true, open: false, node: view, style, bodyTop: Math.round(top0 + th + 28 * rpx), ry: 0, spun: false, turned: false, sx: 0, ss: '1', faded: false, collapsed: false, scrollTop: 0 } }, () => {
        // 起点那一帧必须先画出来,否则没有过渡直接出现在终点
        that._heroTimer = setTimeout(() => { that.setData({ 'hero.open': true }); that._spinHeroCard(); }, 30);
      });
    });
  },
  closeHero() {
    if (!this.data.hero.show) return;
    if (this._heroTimer) clearTimeout(this._heroTimer);
    this.setData({ 'hero.open': false });
    const wait = this.data.reducedMotion ? 0 : HERO_DUR + 40;
    if (this._spinTimer) { clearTimeout(this._spinTimer); this._spinTimer = 0; }
    this._heroTimer = setTimeout(() => this.setData({ hero: { show: false, open: false, node: null, style: '', bodyTop: 0, ry: 0, spun: false, turned: false, sx: 0, ss: '1', faded: false, collapsed: false, scrollTop: 0 } }), wait);
  },
  /** 「进店」:hero 是看,商家页是办(扫码/凭证/核销)。跳之前直接收掉 hero,
   *  回来看到的是格子 —— 商家页里状态可能已变,留一张陈旧的 hero 比没有更糟。 */
  heroEnter(e) {
    // 故事流底栏的「开始」也走这里:进游戏前把故事流关掉(顺带停旁白音频),
    // 否则游戏全程叠着章节旁白,退出游戏后故事流还带着旧内容重弹。
    // 必须放在下面两个 early-return 之前:hero.node 为 null(id 取不到)时按钮不能死着不动,
    // 至少把故事流收掉回到卡片层(closeChapStory 自带 show 守卫,没开时是空操作)
    this.closeChapStory();
    const id = (e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id != null)
      ? e.currentTarget.dataset.id : (this.data.hero.node && this.data.hero.node.nodeId);
    if (id == null) return;
    const node = (this.data.nodes || []).find((n) => String(n.nodeId) === String(id));
    if (node && node.done) return;      // 已核销:按钮是禁用态,点不动
    if (this._heroTimer) clearTimeout(this._heroTimer);
    this.setData({ hero: { show: false, open: false, node: null, style: '', bodyTop: 0, ry: 0, spun: false, turned: false, sx: 0, ss: '1', faded: false, collapsed: false, scrollTop: 0 } });
    // 「开始互动 获得奖励!」直接进玩法面板,不再经商家页中转 ——
    // 中间那一页除了再点一次「开始」之外没有别的事可做(用户 2026-09-08 定)。
    if (node) { this.startGame(node); return; }
    wx.navigateTo({ url: '/pages/play/merchant/index?nodeId=' + id });   // 拿不到节点时的兜底
  },
  /** 物理返回 / iOS 侧滑被 page-container 守卫吃掉后落到这里:关的是 hero,不是 play 页。
   *  按钮关闭时 hero.show 已先置 false,守卫随之 leave 再进来一次 —— closeHero 的 guard 让它成为空操作。 */
  onHeroNativeBack() {
    this.closeHero();
  },
  closeChapterPick() { this.setData({ 'chapterPick.show': false }); },
  onPickChapter(e) {
    const idx = Number(e.currentTarget.dataset.idx) || 0;
    this.setData({ 'chapterPick.show': false, 'chapterPick.cur': idx, packOpen: true });
  },
  /** 顶部输入地址选中一条 = 把地图挪过去并打开它的详情半屏。不跳页。 */
  onSearchPick(e) {
    const p = e.detail || {};
    const lat = Number(p.lat); const lng = Number(p.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
    this.setData({ fmCenter: { lat, lng } });
    const node = this._nodeById(p.nodeId);
    if (node) this.openSheet(node);
  },

  /** 节点级前置解锁:locked 只提示不放行。服务端四个完成入口另有硬闸,这里是 UX 层(文案与服务端一致:说「先完成什么」,不说「不可用」)。 */
  _lockedTip(node) {
    if (node && node.paused) {
      cyToast('本站已暂停，请改去其他节点');
      return true;
    }
    if (!node || !node.locked) return false;
    if (this._routeState.routeMode === 'BRANCH_GRAPH') {
      cyToast(node.lockReason || '前路未知 · 完成当前路线后解锁');
      return true;
    }
    const prev = (this.data.nodes || []).find((n) => String(n.nodeId) === String(node.unlockAfterNodeId));
    cyToast(prev ? ('先完成「' + prev.name + '」才会解锁') : '前路未知 · 先完成前置关卡');
    return true;
  },

  _playerProjectionContext() {
    const module = this.data.gameModule || {};
    return { sessionKey: this._sessionKey(), activityId: module.activityId,
      sessionId: module.sessionId, revision: module.revision };
  },

  _isPlayerProjectionContextCurrent(context) {
    const module = this.data.gameModule || {};
    return !!context && this._isPlayAccountCurrent() && module.enabled === true
      && context.sessionKey === this._sessionKey()
      && context.activityId === module.activityId && context.sessionId === module.sessionId
      && context.revision === module.revision;
  },

  /** 角色卡只展示服务端投影；客户端不再按 roleCode 自带角色秘密或技能正文。 */
  _maybeShowRoleCard(myRole) {
    const game = this.data.gameModule || {};
    if (!game.enabled || !myRole || !myRole.roleCode || !game.role || game.role.code !== myRole.roleCode) return;
    // 角色图标只是展示层映射,不含线索/技能正文;desc/skill 仍只取服务端投影。
    const meta = {
      NAVIGATOR:  { iconName: 'tab-explore' },
      OBSERVER:   { iconName: 'search' },
      RECORDER:   { iconName: 'camera' },
      NEGOTIATOR: { iconName: 'tab-feed' },
      DECODER:    { iconName: 'scan' }
    }[myRole.roleCode] || { iconName: 'star' };
    const roleScope = this.data.activityId || this.data.topicId || '';
    const key = this._roleSeenKey(roleScope);
    let seen = false; try { seen = key ? !!wx.getStorageSync(key) : false; } catch (e) {}
    try { wx.removeStorageSync('role_seen_' + roleScope); } catch (e) {}
    if (seen && myRole.confirmed !== false) return;
    this._roleCardContext = Object.assign(this._playerProjectionContext(), { roleCode: myRole.roleCode });
    this.setData({ roleCard: {
      show: true,
      code: myRole.roleCode,
      name: myRole.roleName || '',
      iconName: meta.iconName,
      desc: myRole.summary || '这是你今晚的身份。留意角色线索，和队友一起推进本局。',
      skill: myRole.skill || '',
      confirmed: myRole.confirmed === true,
    } });
    if (myRole.confirmed === true) {
      try { if (key) wx.setStorageSync(key, 1); } catch (e) {}
    }
  },
  closeRoleCard() { this._roleCardContext = null; this.setData({ 'roleCard.show': false }); },

  confirmPlayerRole() {
    if (!this._isPlayAccountCurrent()) return;
    if (this.data.roleCard.confirmed) { this.closeRoleCard(); return; }
    if (this.data.gameModule.write.status === 'unknown') { this.reconcilePlayerChoice(); return; }
    if (this.data.gameModule.write.status === 'submitting') return;
    const gameModule = this.data.gameModule || {};
    if (!this._isPlayerProjectionContextCurrent(this._roleCardContext)
        || !gameModule.role || this._roleCardContext.roleCode !== gameModule.role.code) {
      cyToast('身份状态暂未同步');
      return;
    }
    const requestId = 'pg-role-' + gameModule.activityId + '-' + Date.now();
    let input;
    try {
      input = buildPlayerRoleConfirmInput(gameModule, requestId);
    } catch (error) {
      cyToast('身份状态暂未同步');
      return;
    }
    this.setData({ 'gameModule.write': { status: 'submitting', requestId, message: '正在确认身份…' } });
    if (!this._savePlayerPending(input)) {
      this._failPlayerPendingSave(requestId, false);
      return false;
    }
    const context = this._playerPendingContext();
    gameSessionClient.submitAction('player', input).then((result) => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      if (this._handlePlayerAuthorityConflict(result)) return;
      if (result && result.reasonCode === 'GAME_STATE_CONFLICT') this._loadPlayerGameModule();
      const pending = context.pending;
      const applied = result && result.status === 'success'
        && matchingPlayerTerminalReceipt(result, pending, 'APPLIED');
      const failed = result && result.status === 'business-error'
        && matchingPlayerTerminalReceipt(result, pending, 'FAILED');
      if (applied) {
        try {
          this._clearPlayerPending();
          const key = this._roleSeenKey(gameModule.activityId);
          if (key) wx.setStorageSync(key, 1);
        } catch (error) {}
        cyToast.success('身份已确认');
        this.setData({ 'roleCard.show': false, 'gameModule.write': { status: 'confirmed', requestId, message: '身份已确认' } }, () => this._loadPlayerGameModule());
        return;
      }
      if (failed) {
        this._loadPlayerGameModule();
        this._clearPlayerPending();
        this.setData({ 'gameModule.write': { status: 'error', requestId, message: result.message || '身份确认未被接受' } });
        return;
      }
      this.setData({ 'gameModule.write': {
        status: 'unknown', requestId,
        canRetry: true,
        message: (result && result.message) || '身份确认中，请勿重复提交',
      } });
    }).catch(() => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      this.setData({ 'gameModule.write': { status: 'unknown', requestId, message: '身份确认中，请勿重复提交', canRetry: true } });
    });
  },

  _roleSeenKey(scope) {
    if (!this._isPlayAccountCurrent()) return '';
    const memberId = this._playOwner;
    return memberId === null || memberId === undefined || memberId === '' || !scope
      ? '' : 'role_seen_m' + memberId + '_s' + scope;
  },

  _playerPendingKey() {
    if (!this._isPlayAccountCurrent()) return '';
    const memberId = this._playOwner;
    return memberId === null || memberId === undefined || memberId === '' || !this.data.activityId
      ? '' : 'game_player_pending_m' + memberId + '_a' + this.data.activityId;
  },

  _playerPendingContext() {
    const module = this.data.gameModule || {};
    return { pending: this._playerPendingWrite, activityId: Number(this.data.activityId),
      moduleActivityId: module.activityId, sessionId: module.sessionId };
  },

  _isPlayerPendingContextCurrent(context) {
    const module = this.data.gameModule || {};
    return this._isPlayAccountCurrent() && !!context.pending
      && Number(this.data.activityId) === context.activityId
      && module.activityId === context.moduleActivityId && module.sessionId === context.sessionId
      && this._playerPendingWrite === context.pending
      && module.write && module.write.requestId === context.pending.requestId;
  },

  _savePlayerPending(input, preserveMemoryCommand) {
    const pending = normalizePlayerPendingWrite({
      requestId: input && input.requestId,
      action: input && input.action,
      command: input,
    }, this.data.activityId);
    if (!pending) return false;
    const receiptIndex = normalizePlayerPendingReceiptIndex({
      activityId: pending.command.activityId,
      nodeId: pending.command.nodeId,
      requestId: pending.command.requestId,
      expectedRevision: pending.command.expectedRevision,
      action: pending.command.action,
    }, this.data.activityId);
    if (!receiptIndex) return false;
    const key = this._playerPendingKey();
    if (!key) return false;
    try {
      wx.setStorageSync(key, receiptIndex);
    } catch (error) {
      return false;
    }
    if (!preserveMemoryCommand) this._playerPendingWrite = pending;
    return true;
  },

  _failPlayerPendingSave(requestId, keepUnknown) {
    if (!keepUnknown) this._playerPendingWrite = null;
    const message = keepUnknown
      ? '无法保存重试状态，上次操作仍待确认'
      : '无法安全保存本次操作，请检查小程序存储后重试';
    this.setData({ 'gameModule.write': {
      status: keepUnknown ? 'unknown' : 'error',
      requestId: keepUnknown ? requestId : '',
      message,
      canRetry: keepUnknown,
    } });
    cyToast(keepUnknown ? '无法安全保存，未发送重试' : '无法安全保存，请稍后重试');
    return false;
  },

  _clearPlayerPending() {
    if (!this._isPlayAccountCurrent()) return;
    this._playerPendingWrite = null;
    try {
      const key = this._playerPendingKey();
      if (key) wx.removeStorageSync(key);
      const legacyKey = 'game_player_pending_' + (this.data.activityId || '');
      if (wx.getStorageSync(legacyKey)) wx.removeStorageSync(legacyKey);
    } catch (error) {}
  },

  _rememberPlayerReveal(receipt, pending) {
    const command = pending && (pending.command || pending);
    const revealText = normalizePlayerRevealText(receipt && receipt.result && receipt.result.revealText);
    if (!command || pending.action !== 'PLAYER_REVEAL' || receipt.action !== 'PLAYER_REVEAL' || !revealText) return '';
    const nodeId = Number(command.nodeId);
    if (!Number.isSafeInteger(nodeId) || nodeId <= 0) return '';
    const nodes = ((this.data.gameModule && this.data.gameModule.nodes) || []).map((node) => (
      node && node.nodeId === nodeId ? Object.assign({}, node, { revealedAnswer: revealText }) : node
    ));
    this.setData({ 'gameModule.nodes': nodes });
    try {
      cyModal.show({
        title: '答案已揭示',
        content: revealText,
        showCancel: false,
        confirmText: '我知道了',
      });
    } catch (error) {}
    return revealText;
  },

  _loadPlayerGameModule() {
    const activityId = Number(this.data.activityId) || 0;
    if (this._playDisposed || !activityId || this.data.isPreview || this.data.isMock) return;
    const requestEpoch = (this._playerProjectionEpoch || 0) + 1;
    this._playerProjectionEpoch = requestEpoch;
    const memberId = app.getUserID && app.getUserID();
    const sessionId = (this.data.gameModule || {}).sessionId;
    const currentRequest = () => !this._playDisposed
      && requestEpoch === this._playerProjectionEpoch && activityId === Number(this.data.activityId)
      && memberId === (app.getUserID && app.getUserID())
      && (this.data.gameModule || {}).sessionId === sessionId;
    this.setData({ 'gameModule.loading': true, 'gameModule.error': '' });
    return gameSessionClient.loadProjection('player', activityId).then((result) => {
      if (!currentRequest()) return;
      if (!result || result.status !== 'ready') {
        const absent = result && ['GAME_SESSION_NOT_FOUND', 'GAME_SESSION_NOT_PREPARED', 'GAME_MODULE_DISABLED'].includes(result.reasonCode);
        const current = this.data.gameModule || {};
        const retainedSnapshot = !absent && current.enabled === true
          && typeof current.snapshotAt === 'string' && !!current.snapshotAt;
        this.setData({
          'gameModule.loading': false,
          'gameModule.enabled': retainedSnapshot ? true : false,
          'gameModule.stale': retainedSnapshot,
          'gameModule.error': absent ? '' : ((result && result.message) || '本局状态暂未同步'),
        });
        return;
      }
      if (Number(result.data && result.data.activityId) !== activityId
          || (sessionId && Number(result.data && result.data.sessionId) !== Number(sessionId))) return;
      if (result.data && result.data.player && result.data.player.playMode === 'ROUTE') {
        if (['FINISHED', 'CANCELLED'].includes(result.data.status)) this._applyServerRunSessionEnd();
        if (result.data.player.scope === 'TEAM') this._applyThemeTeamView(result.data.player, result.data);
        this.setData({ 'gameModule.loading': false, 'gameModule.enabled': false,
          'gameModule.stale': false, 'gameModule.error': '' });
        return;
      }
      const projection = normalizePlayerProjection(result.data);
      const previous = this.data.gameModule || {};
      if (previous.sessionId === projection.sessionId && Number(previous.revision) > Number(projection.revision)) {
        this.setData({ 'gameModule.loading': false });
        return;
      }
      if (!projection.enabled) {
        this.setData({
          'gameModule.loading': false, 'gameModule.enabled': false,
          'gameModule.stale': false, 'gameModule.error': '',
        });
        return;
      }
      const current = this.data.gameModule || {};
      // R9-21:服务端会话已经结束(通关/取消)时,本地兜底的暂停快照不能把玩家拉回一局已结束的计时。
      if (projection.status === 'FINISHED' || projection.status === 'CANCELLED') {
        this._applyServerRunSessionEnd();
      }
      const taskEvidenceByNode = {};
      const previousRuntimeByNode = {};
      ((this.data.gameModule && this.data.gameModule.nodes) || []).forEach((node) => {
        if (!node || !node.nodeId) return;
        previousRuntimeByNode[String(node.nodeId)] = node;
        if (node.taskEvidence && node.playerTask) taskEvidenceByNode[String(node.nodeId)] = {
          task: this._playerTaskSignature(node.playerTask), evidence: node.taskEvidence,
        };
      });
      projection.nodes = projection.nodes.map((node) => Object.assign({}, node, {
        taskEvidence: node.playerTask && taskEvidenceByNode[String(node.nodeId)]
          && taskEvidenceByNode[String(node.nodeId)].task === this._playerTaskSignature(node.playerTask)
          ? taskEvidenceByNode[String(node.nodeId)].evidence : {
          type: node.playerTask ? node.playerTask.inputType : '',
          ready: false,
          text: '',
          evidenceUrls: [],
          statusText: '',
        },
        revealedAnswer: node.status === 'FALLBACK_COMPLETED' && node.completionSource === 'PLAYER_REVEAL'
          ? normalizePlayerRevealText(previousRuntimeByNode[String(node.nodeId)]
            && previousRuntimeByNode[String(node.nodeId)].revealedAnswer)
          : '',
      }));
      let savedPending = null;
      try {
        const legacyKey = 'game_player_pending_' + activityId;
        if (wx.getStorageSync(legacyKey)) wx.removeStorageSync(legacyKey);
        const pendingKey = this._playerPendingKey();
        savedPending = pendingKey ? (wx.getStorageSync(pendingKey) || null) : null;
      } catch (e) { savedPending = null; }
      const restoredPending = normalizePlayerPendingReceiptIndex(savedPending, activityId);
      const memory = this._playerPendingWrite;
      const memoryIndex = memory && normalizePlayerPendingReceiptIndex(memory.command || memory, activityId);
      // Same receipt identity keeps its original command in memory; storage stays a redacted index.
      const pending = restoredPending && memoryIndex
        && ['activityId', 'nodeId', 'requestId', 'expectedRevision', 'action']
          .every(key => restoredPending[key] === memoryIndex[key]) ? memory : restoredPending;
      this._playerPendingWrite = pending;
      if (savedPending && !pending) this._clearPlayerPending();
      if (pending) {
        projection.write = pending === memory && current.write
          && current.write.requestId === pending.requestId && current.write.status === 'submitting'
          ? current.write : {
          status: 'unknown', requestId: pending.requestId,
          message: pending.command ? '正在确认上次操作' : '正在确认上次操作；不会自动重发现场内容',
          canRetry: !!pending.command,
        };
      }
      const projectedByNode = {};
      projection.nodes.forEach((node) => { projectedByNode[String(node.nodeId)] = node; });
      const newlyCompleted = projection.nodes.find((node) => {
        const before = previousRuntimeByNode[String(node.nodeId)];
        return before && before.status !== 'COMPLETED' && node.status === 'COMPLETED'
          && parseGameCompletedAt(node.completedAt) > 0;
      });
      const nodes = (this.data.nodes || []).map((node) => {
        const projected = projectedByNode[String(node.nodeId)];
        if (!projected) return node;
        const paused = projected.status === 'PAUSED' || projected.stationStatus === 'PAUSED';
        const completed = projected.status === 'COMPLETED' || projected.status === 'FALLBACK_COMPLETED';
        const completedAt = parseGameCompletedAt(projected.completedAt);
        return Object.assign({}, node, {
          paused,
          runtimeStatus: projected.status,
          done: completed,
          doneAt: completedAt || (completed ? Number(node.doneAt) || 0 : 0),
          locked: paused || projected.status === 'LOCKED',
        });
      });
      const myRole = projection.role.code ? {
        roleCode: projection.role.code,
        roleName: projection.role.name,
        summary: projection.role.publicBrief,
        confirmed: projection.role.confirmed,
      } : null;
      this.setData({
        gameModule: Object.assign({}, projection, {
          loading: false, error: '', stale: false, sheetShow: this.data.gameModule.sheetShow,
          write: previous.write && previous.write.status === 'error' ? previous.write : projection.write,
        }),
        nodes,
        myRole,
      }, () => {
        this.rebuild();
        if (newlyCompleted) this._animateCompletedSegment(this.data.nodes, newlyCompleted.nodeId);
        if (myRole) this._maybeShowRoleCard(myRole);
        if (pending) this.reconcilePlayerChoice();
      });
    }).catch(() => {
      if (!currentRequest()) return;
      const current = this.data.gameModule || {};
      const retainedSnapshot = current.enabled === true
        && typeof current.snapshotAt === 'string' && !!current.snapshotAt;
      this.setData({
        'gameModule.loading': false,
        'gameModule.enabled': retainedSnapshot ? true : false,
        'gameModule.stale': retainedSnapshot,
        'gameModule.error': '本局状态暂未同步',
      });
    });
  },

  openGameModule() {
    this.setData({ 'gameModule.sheetShow': true });
  },
  closeGameModule() { this.setData({ 'gameModule.sheetShow': false }); },
  retryGameModule() { this._loadPlayerGameModule(); },

  submitPlayerChoice(e) {
    if (!this._isPlayAccountCurrent()) return;
    const gameModule = this.data.gameModule || {};
    if (!gameModule.enabled || gameModule.write.status === 'submitting' || gameModule.write.status === 'unknown') return;
    const nodeId = Number(e.currentTarget.dataset.node) || 0;
    const choiceId = String(e.currentTarget.dataset.choice || '');
    const requestId = 'pg-' + gameModule.activityId + '-' + nodeId + '-' + Date.now();
    let input;
    try {
      input = buildPlayerChoiceInput({ activityId: gameModule.activityId, nodeId, revision: gameModule.revision, authorityEpoch: gameModule.authorityEpoch }, choiceId, requestId);
    } catch (error) {
      cyToast('这个选择暂时不能提交');
      return;
    }
    this.setData({ 'gameModule.write': { status: 'submitting', requestId, message: '正在记录选择…' } });
    if (!this._savePlayerPending(input)) {
      this._failPlayerPendingSave(requestId, false);
      return false;
    }
    const context = this._playerPendingContext();
    gameSessionClient.submitAction('player', input).then((result) => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      if (this._handlePlayerAuthorityConflict(result)) return;
      if (result && result.reasonCode === 'GAME_STATE_CONFLICT') this._loadPlayerGameModule();
      const pending = context.pending;
      const applied = result && result.status === 'success'
        && matchingPlayerTerminalReceipt(result, pending, 'APPLIED');
      const failed = result && result.status === 'business-error'
        && matchingPlayerTerminalReceipt(result, pending, 'FAILED');
      if (applied) {
        this._clearPlayerPending();
        cyToast.success('选择已记录');
        this.setData({ 'gameModule.write': { status: 'confirmed', requestId, message: '选择已记录' } }, () => this._loadPlayerGameModule());
        return;
      }
      if (failed) {
        this._loadPlayerGameModule();
        this._clearPlayerPending();
        this.setData({ 'gameModule.write': { status: 'error', requestId, message: result.message || '选择未被接受' } });
        return;
      }
      this.setData({ 'gameModule.write': {
        status: 'unknown', requestId, message: (result && result.message) || '结果待确认，请勿重复提交', canRetry: true,
      } });
    }).catch(() => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      this.setData({ 'gameModule.write': { status: 'unknown', requestId, message: '结果待确认，请勿重复提交', canRetry: true } });
    });
  },

  submitPlayerTask(e) {
    if (!this._isPlayAccountCurrent()) return;
    const gameModule = this.data.gameModule || {};
    if (!gameModule.enabled || gameModule.write.status === 'submitting' || gameModule.write.status === 'unknown') return;
    if (!(gameModule.availableActions || []).includes('PLAYER_SUBMIT')) return;
    const nodeId = Number(e.currentTarget.dataset.node) || 0;
    const taskCode = String(e.currentTarget.dataset.task || '');
    const node = (gameModule.nodes || []).find((item) => item.nodeId === nodeId);
    if (!node || !node.playerTask || node.playerTask.taskCode !== taskCode
      || (node.submission && node.submission.status !== 'REJECTED')) return;
    const evidence = node.taskEvidence || {};
    if (!evidence.ready || !Array.isArray(evidence.evidenceUrls) || !evidence.evidenceUrls.length) {
      cyToast('请先完成本站任务凭证');
      return;
    }
    const requestId = 'pg-submit-' + gameModule.activityId + '-' + nodeId + '-' + Date.now();
    let input;
    try {
      input = buildPlayerSubmissionInput(
        { activityId: gameModule.activityId, nodeId, revision: gameModule.revision, authorityEpoch: gameModule.authorityEpoch },
        taskCode,
        requestId,
        evidence.evidenceUrls
      );
    } catch (error) {
      cyToast('本站任务暂时不能提交');
      return;
    }
    this.setData({ 'gameModule.write': { status: 'submitting', requestId, message: '正在提交本站任务…' } });
    if (!this._savePlayerPending(input)) {
      this._failPlayerPendingSave(requestId, false);
      return false;
    }
    const context = this._playerPendingContext();
    gameSessionClient.submitAction('player', input).then((result) => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      if (this._handlePlayerAuthorityConflict(result)) return;
      if (result && result.reasonCode === 'GAME_STATE_CONFLICT') this._loadPlayerGameModule();
      const pending = context.pending;
      const applied = result && result.status === 'success'
        && matchingPlayerTerminalReceipt(result, pending, 'APPLIED');
      const failed = result && result.status === 'business-error'
        && matchingPlayerTerminalReceipt(result, pending, 'FAILED');
      if (applied) {
        this._clearPlayerPending();
        this._setPlayerTaskEvidence(nodeId, {
          type: node.playerTask.inputType, ready: false, text: '', evidenceUrls: [], statusText: '',
        });
        const evidenceOnly = node.playerTask.inputType === 'PHOTO'
          && node.playerTask.completionPolicy === 'EVIDENCE_ONLY';
        const submissionId = result.receipt.result && result.receipt.result.submissionId;
        cyToast(evidenceOnly ? '证据已记录' : (submissionId ? ('核验编号 ' + submissionId) : '任务已提交'), { icon: evidenceOnly ? 'none' : 'success' });
        this.setData({ 'gameModule.write': {
          status: 'confirmed', requestId,
          message: evidenceOnly ? '证据已记录' : '任务已记录',
        } }, () => this._loadPlayerGameModule());
        return;
      }
      if (failed) {
        this._loadPlayerGameModule();
        this._clearPlayerPending();
        this.setData({ 'gameModule.write': { status: 'error', requestId, message: result.message || '任务未被接受' } });
        return;
      }
      this.setData({ 'gameModule.write': {
        status: 'unknown', requestId, message: (result && result.message) || '提交结果待确认，请勿重复提交', canRetry: true,
      } });
    }).catch(() => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      this.setData({ 'gameModule.write': { status: 'unknown', requestId, message: '提交结果待确认，请勿重复提交', canRetry: true } });
    });
  },

  _submitPlayerAssist(input, labels) {
    if (!this._isPlayAccountCurrent()) return;
    const gameModule = this.data.gameModule || {};
    if (!gameModule.enabled || !gameModule.write || gameModule.write.status === 'submitting'
        || gameModule.write.status === 'unknown' || input.activityId !== gameModule.activityId
        || input.expectedRevision !== gameModule.revision
        || !(gameModule.availableActions || []).includes(input.action)) return false;
    const requestId = input.requestId;
    this.setData({ 'gameModule.write': { status: 'submitting', requestId, message: labels.submitting } });
    if (!this._savePlayerPending(input)) {
      this._failPlayerPendingSave(requestId, false);
      return false;
    }
    const context = this._playerPendingContext();
    gameSessionClient.submitAction('player', input).then((result) => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      if (this._handlePlayerAuthorityConflict(result)) return;
      if (result && result.reasonCode === 'GAME_STATE_CONFLICT') this._loadPlayerGameModule();
      const pending = context.pending;
      const applied = result && result.status === 'success'
        && matchingPlayerTerminalReceipt(result, pending, 'APPLIED');
      const failed = result && result.status === 'business-error'
        && matchingPlayerTerminalReceipt(result, pending, 'FAILED');
      if (applied) {
        this._rememberPlayerReveal(result.receipt, pending);
        this._clearPlayerPending();
        cyToast(labels.success);
        this.setData({
          'gameModule.write': { status: 'confirmed', requestId, message: labels.confirmed },
        }, () => this._loadPlayerGameModule());
        return;
      }
      if (failed) {
        this._loadPlayerGameModule();
        this._clearPlayerPending();
        this.setData({
          'gameModule.write': { status: 'error', requestId, message: result.message || labels.rejected },
        });
        return;
      }
      this.setData({ 'gameModule.write': {
        status: 'unknown', requestId,
        canRetry: true,
        message: (result && result.message) || labels.unknown,
      } });
    }).catch(() => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      this.setData({ 'gameModule.write': { status: 'unknown', requestId, message: labels.unknown, canRetry: true } });
    });
  },

  requestPlayerHint(e) {
    if (!this._isPlayAccountCurrent()) return;
    const gameModule = this.data.gameModule || {};
    if (!gameModule.enabled || gameModule.write.status === 'submitting' || gameModule.write.status === 'unknown') return;
    if (!(gameModule.availableActions || []).includes('PLAYER_HINT')) return;
    const nodeId = Number(e.currentTarget && e.currentTarget.dataset.node) || 0;
    const level = Number(e.currentTarget && e.currentTarget.dataset.level);
    const node = (gameModule.nodes || []).find((item) => item.nodeId === nodeId);
    const hint = node && node.hint;
    if (!hint || level !== hint.nextLevel || (level !== 1 && level !== 2) || !hint.nextImpactLabel) return;
    const context = this._playerProjectionContext();
    const hintState = JSON.stringify(hint);
    const nodeStatus = node.status;
    cyModal.show({
      title: '查看第 ' + level + ' 级提示',
      content: hint.nextImpactLabel,
      confirmText: '查看提示',
      cancelText: '继续想想',
      success: (modal) => {
        if (!modal.confirm || !this._isPlayerProjectionContextCurrent(context)) return;
        const current = this.data.gameModule;
        const currentNode = (current.nodes || []).find(item => item.nodeId === nodeId);
        if (!currentNode || currentNode.status !== nodeStatus || JSON.stringify(currentNode.hint) !== hintState
            || !current.write || current.write.status === 'submitting' || current.write.status === 'unknown'
            || !(current.availableActions || []).includes('PLAYER_HINT')) return;
        const requestId = 'pg-hint-' + gameModule.activityId + '-' + nodeId + '-' + level + '-' + Date.now();
        let input;
        try {
          input = buildPlayerHintInput(
            { activityId: gameModule.activityId, nodeId, revision: gameModule.revision, authorityEpoch: gameModule.authorityEpoch },
            level,
            requestId
          );
        } catch (error) {
          cyToast('提示状态暂未同步');
          return;
        }
        this._submitPlayerAssist(input, {
          submitting: '正在获取提示…',
          success: '提示已揭示',
          confirmed: '提示已确认揭示',
          rejected: '提示暂时不可用',
          unknown: '提示结果待确认，请勿重复请求',
        });
      },
    });
  },

  requestPlayerReveal(e) {
    if (!this._isPlayAccountCurrent()) return;
    const gameModule = this.data.gameModule || {};
    if (!gameModule.enabled || gameModule.write.status === 'submitting' || gameModule.write.status === 'unknown') return;
    if (!(gameModule.availableActions || []).includes('PLAYER_REVEAL')) return;
    const nodeId = Number(e.currentTarget && e.currentTarget.dataset.node) || 0;
    const node = (gameModule.nodes || []).find((item) => item.nodeId === nodeId);
    const hint = node && node.hint;
    if (!hint || hint.revealAvailable !== true || !hint.revealImpactLabel) return;
    const context = this._playerProjectionContext();
    const hintState = JSON.stringify(hint);
    const nodeStatus = node.status;
    cyModal.show({
      title: '揭示答案',
      content: hint.revealImpactLabel,
      confirmText: '查看答案',
      cancelText: '继续想想',
      success: (modal) => {
        if (!modal.confirm || !this._isPlayerProjectionContextCurrent(context)) return;
        const current = this.data.gameModule;
        const currentNode = (current.nodes || []).find(item => item.nodeId === nodeId);
        if (!currentNode || currentNode.status !== nodeStatus || JSON.stringify(currentNode.hint) !== hintState
            || !current.write || current.write.status === 'submitting' || current.write.status === 'unknown'
            || !(current.availableActions || []).includes('PLAYER_REVEAL')) return;
        const requestId = 'pg-reveal-' + gameModule.activityId + '-' + nodeId + '-' + Date.now();
        let input;
        try {
          input = buildPlayerRevealInput(
            { activityId: gameModule.activityId, nodeId, revision: gameModule.revision, authorityEpoch: gameModule.authorityEpoch },
            requestId
          );
        } catch (error) {
          cyToast('答案状态暂未同步');
          return;
        }
        this._submitPlayerAssist(input, {
          submitting: '正在揭示答案…',
          success: '已按保底完成',
          confirmed: '答案已揭示，记为保底完成',
          rejected: '暂时不能揭示答案',
          unknown: '揭示结果待确认，请勿重复请求',
        });
      },
    });
  },

  _playerTaskSignature(task) {
    return task ? JSON.stringify([task.taskCode, task.inputType, task.prompt,
      task.verificationRequired === true, task.completionPolicy || '']) : '';
  },

  _playerTaskEvidenceContext(node) {
    const module = this.data.gameModule || {};
    const requests = this._playerEvidenceRequests = this._playerEvidenceRequests || Object.create(null);
    const sequence = requests[node.nodeId] = (requests[node.nodeId] || 0) + 1;
    return { nodeId: node.nodeId, sequence, sessionKey: this._sessionKey(),
      sessionId: module.sessionId, activityId: module.activityId, status: node.status,
      task: this._playerTaskSignature(node.playerTask),
      submission: JSON.stringify(node.submission || null) };
  },

  _isPlayerTaskEvidenceCurrent(context) {
    if (!this._isPlayAccountCurrent()) return false;
    const module = this.data.gameModule || {};
    const node = (module.nodes || []).find(item => item.nodeId === context.nodeId);
    return module.enabled === true && module.activityId === context.activityId
      && module.sessionId === context.sessionId
      && context.sessionKey === this._sessionKey()
      && this._playerEvidenceRequests[context.nodeId] === context.sequence
      && !!node && node.status === context.status
      && this._playerTaskSignature(node.playerTask) === context.task
      && JSON.stringify(node.submission || null) === context.submission;
  },

  _setPlayerTaskEvidence(nodeId, evidence) {
    const nodes = ((this.data.gameModule && this.data.gameModule.nodes) || []).map((node) => (
      node && node.nodeId === nodeId ? Object.assign({}, node, { taskEvidence: evidence }) : node
    ));
    this.setData({ 'gameModule.nodes': nodes });
  },

  onPlayerTaskTextInput(e) {
    const nodeId = Number(e.currentTarget && e.currentTarget.dataset.node) || 0;
    const node = ((this.data.gameModule && this.data.gameModule.nodes) || []).find((item) => item.nodeId === nodeId);
    if (!node || !node.playerTask || node.playerTask.inputType !== 'TEXT') return;
    const text = String((e.detail && e.detail.value) || '').slice(0, 300);
    const trimmed = text.trim();
    this._setPlayerTaskEvidence(nodeId, {
      type: 'TEXT',
      ready: !!trimmed,
      text,
      evidenceUrls: trimmed ? ['text:' + encodeURIComponent(trimmed)] : [],
      statusText: trimmed ? '文字凭证已填写' : '',
    });
  },

  scanPlayerTaskEvidence(e) {
    const nodeId = Number(e.currentTarget && e.currentTarget.dataset.node) || 0;
    const node = ((this.data.gameModule && this.data.gameModule.nodes) || []).find((item) => item.nodeId === nodeId);
    if (!node || !node.playerTask || node.playerTask.inputType !== 'SCAN') return;
    const context = this._playerTaskEvidenceContext(node);
    wx.scanCode({
      onlyFromCamera: true,
      success: (result) => {
        if (!this._isPlayerTaskEvidenceCurrent(context)) return;
        const value = String((result && result.result) || '').trim().slice(0, 400);
        if (!value) {
          cyToast('没有识别到任务码');
          return;
        }
        this._setPlayerTaskEvidence(nodeId, {
          type: 'SCAN', ready: true, text: '',
          evidenceUrls: ['scan:' + encodeURIComponent(value)],
          statusText: '已识别本站任务码',
        });
      },
      fail: (error) => {
        if (!this._isPlayerTaskEvidenceCurrent(context)) return;
        if (!isScanCancelled(error)) cyToast('未能扫码，请检查相机权限后重试');
      },
    });
  },

  photoPlayerTaskEvidence(e) {
    const nodeId = Number(e.currentTarget && e.currentTarget.dataset.node) || 0;
    const node = ((this.data.gameModule && this.data.gameModule.nodes) || []).find((item) => item.nodeId === nodeId);
    if (!node || !node.playerTask || node.playerTask.inputType !== 'PHOTO') return;
    const context = this._playerTaskEvidenceContext(node);
    app.chooseImage((urls) => {
      if (!this._isPlayerTaskEvidenceCurrent(context)) return;
      const evidenceUrls = (Array.isArray(urls) ? urls : [])
        .map((url) => String(url || '').trim())
        .filter((url) => url.toLowerCase().startsWith('https://'))
        .slice(0, 3);
      if (!evidenceUrls.length) return;
      this._setPlayerTaskEvidence(nodeId, {
        type: 'PHOTO', ready: true, text: '', evidenceUrls,
        statusText: '已上传 ' + evidenceUrls.length + ' 张现场照片',
      });
    }, 3);
  },

  reconcilePlayerChoice() {
    if (!this._isPlayAccountCurrent()) return;
    const gameModule = this.data.gameModule || {};
    const requestId = gameModule.write && gameModule.write.requestId;
    const pendingWrite = this._playerPendingWrite;
    if (!requestId || !pendingWrite || pendingWrite.requestId !== requestId
        || gameModule.write.status !== 'unknown') return;
    const context = this._playerPendingContext();
    const isCurrent = () => this._isPlayerPendingContextCurrent(context)
      && this.data.gameModule.write.status === 'unknown';
    this.setData({ 'gameModule.write.message': '正在确认操作结果…' });
    gameSessionClient.readReceipt(gameModule.activityId, requestId).then((result) => {
      if (!isCurrent()) return;
      const applied = result && result.status === 'matched'
        && matchingPlayerTerminalReceipt(result, pendingWrite, 'APPLIED');
      const failed = result && result.status === 'business-error'
        && matchingPlayerTerminalReceipt(result, pendingWrite, 'FAILED');
      if (applied) {
        const current = this.data.gameModule;
        const merged = mergeConfirmedReceipt(current, result.receipt);
        if (merged !== current) {
          try {
            this._rememberPlayerReveal(result.receipt, pendingWrite);
            this._clearPlayerPending();
            if (result.receipt.action === 'CONFIRM_ROLE') {
              const key = this._roleSeenKey(gameModule.activityId);
              if (key) wx.setStorageSync(key, 1);
            }
          } catch (error) {}
          this.setData({
            gameModule: Object.assign({}, merged, { nodes: this.data.gameModule.nodes, sheetShow: current.sheetShow, loading: false, error: '' }),
            'roleCard.show': result.receipt.action === 'CONFIRM_ROLE' ? false : this.data.roleCard.show,
          }, () => this._loadPlayerGameModule());
          return;
        }
      }
      if (failed) {
        this._loadPlayerGameModule();
        this._clearPlayerPending();
        this.setData({ 'gameModule.write': {
          status: 'error', requestId: pendingWrite.requestId,
          message: result.message || '原操作未被接受',
        } });
        return;
      }
      const pending = result && result.status === 'pending';
      const restored = !pendingWrite.command;
      this.setData({ 'gameModule.write.message': pending
        ? (restored ? '结果仍在确认；不会自动重发现场内容' : '结果仍在确认，请稍后再试')
        : (restored ? '暂时无法确认；不会自动重发现场内容' : '暂时无法确认，请保留本页稍后重试') });
    }).catch(() => {
      if (!isCurrent()) return;
      this.setData({ 'gameModule.write.message': pendingWrite && !pendingWrite.command
        ? '暂时无法确认；不会自动重发现场内容'
        : '暂时无法确认，请保留本页稍后重试' });
    });
  },

  _handlePlayerAuthorityConflict(result) {
    if (!result || result.status !== 'business-error' || result.reasonCode !== 'GAME_AUTHORITY_CONFLICT') return false;
    // 服务端在创建回执之前拒绝旧纪元；只重新读取，不换纪元重发。
    this._clearPlayerPending();
    this.setData({ 'gameModule.write': { status: 'error', requestId: result.requestId || '', message: result.message } });
    cyToast(result.message || '本局权威版本已更新，请刷新');
    this._loadPlayerGameModule();
    return true;
  },

  retryPlayerUnknownWrite() {
    if (!this._isPlayAccountCurrent()) return;
    const gameModule = this.data.gameModule || {};
    const pending = this._playerPendingWrite;
    if (!pending || !pending.command || !gameModule.write || gameModule.write.status !== 'unknown') return false;
    if (!this._savePlayerPending(pending.command, true)) {
      return this._failPlayerPendingSave(pending.requestId, true);
    }
    this.setData({ 'gameModule.write': {
      status: 'submitting', requestId: pending.requestId, message: '正在重试…',
    } });
    const context = this._playerPendingContext();
    gameSessionClient.submitAction('player', pending.command).then((result) => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      if (this._handlePlayerAuthorityConflict(result)) return;
      if (result && result.reasonCode === 'GAME_STATE_CONFLICT') this._loadPlayerGameModule();
      const applied = result && result.status === 'success'
        && matchingPlayerTerminalReceipt(result, pending, 'APPLIED');
      const failed = result && result.status === 'business-error'
        && matchingPlayerTerminalReceipt(result, pending, 'FAILED');
      if (applied) {
        this._rememberPlayerReveal(result.receipt, pending);
        this._clearPlayerPending();
        if (pending.action === 'CONFIRM_ROLE') {
          try {
            const key = this._roleSeenKey(gameModule.activityId);
            if (key) wx.setStorageSync(key, 1);
          } catch (error) {}
        }
        cyToast.success('结果已确认');
        this.setData({
          'roleCard.show': pending.action === 'CONFIRM_ROLE' ? false : this.data.roleCard.show,
          'gameModule.write': { status: 'confirmed', requestId: pending.requestId, message: '操作已确认' },
        }, () => this._loadPlayerGameModule());
        return;
      }
      if (failed) {
        this._loadPlayerGameModule();
        this._clearPlayerPending();
        this.setData({ 'gameModule.write': {
          status: 'error', requestId: pending.requestId, message: result.message || '原操作未被接受',
        } });
        return;
      }
      this.setData({ 'gameModule.write': {
        status: 'unknown', requestId: pending.requestId, message: '重试结果仍待确认，请勿另起请求', canRetry: true,
      } });
    }).catch(() => {
      if (!this._isPlayerPendingContextCurrent(context)) return;
      this.setData({ 'gameModule.write': {
        status: 'unknown', requestId: pending.requestId, message: '重试结果仍待确认，请勿另起请求', canRetry: true,
      } });
    });
    return true;
  },

  /** 升级3:同行者榜(GET /api/play/leaderboard,score=完成关数)。入口在通关 sheet。 */
  openBoard() {
    if (!this._isPlayAccountCurrent()) return;
    const owner = this._boundPlayOwner(), params = this._sessionParams(), sessionKey = this._sessionKey(params);
    const sequence = this._boardReadSequence = (this._boardReadSequence || 0) + 1;
    const current = () => this._isPlayAccountCurrent() && owner === this._playOwner
      && sequence === this._boardReadSequence && this.data.board.show
      && sessionKey === this._sessionKey()
      && originalSessionId === precisePlayId((this._routeState || {}).sessionId);
    this.setData({ board: { show: true, loading: true, list: [], me: null, error: '' } });
    const originalSessionId = precisePlayId((this._routeState || {}).sessionId);
    const orientBoard = this.data.mode === 1 && !!originalSessionId;
    let usingOrientBoard = orientBoard;
    const loadBoard = orientBoard
      ? req('/api/orient/scoring/session/' + originalSessionId + '/leaderboard', 'GET', {}, undefined, true).then(res => {
        // Only an explicit feature/applicability refusal allows the legacy board. Network/permission failures do not.
        if (current() && ['ORIENT_SCORING_DISABLED', 'ORIENT_SCORING_NOT_APPLICABLE'].includes(res.errorCode || res.err)) {
          usingOrientBoard = false;
          return req('/api/play/leaderboard', 'GET', params);
        }
        return res;
      }) : req('/api/play/leaderboard', 'GET', params);
    loadBoard.then((res) => {
      if (!current()) return;
      if (res.code == 200 || res.code == '200') {
        const d = res.data || {};
        const view = playBoardView.project(d);
        if (!view || usingOrientBoard && (!['SCORE', 'ROUTE'].includes(d.mode) || precisePlayId(d.sessionId) !== originalSessionId)) {
          this.setData({ 'board.loading': false, 'board.error': '榜单结果不完整，请重新读取本局榜单' }); return;
        }
        this.setData({ board: { show: true, loading: false, ...view, error: '' } });
      } else {
        // 失败 ≠ 空:不落 error 的话,WXML 会拿空 list 显示「还没人上榜」——把网络失败说成事实。
        this.setData({ 'board.loading': false, 'board.error': (res && res.msg) || '榜单加载失败' });
      }
    }).catch(() => {
      if (!current()) return;
      this.setData({ 'board.loading': false, 'board.error': '网络不太好，榜单没拉到' });
    });
  },
  closeBoard() {
    this._boardReadSequence = (this._boardReadSequence || 0) + 1;
    this.setData({ 'board.show': false });
  },
  /** 核销入场:动态码收进场景栈(#572 单宿主收口),票面+放大码都在 scene-qr-ticket 组件里 */
  openEntryQr() {
    if (this.data.isPreview || this.data.isMock) return;
    const regId = this.data.regId;
    if (!regId) { this._explainMissingEntryQr(); return; }
    this.openScene('qr-ticket', { registrationId: regId });
  },
  /**
   * 从帖文/俱乐部等入口进来时页面只有 activityId/topicId,没有票夹带的 registrationId,
   * 入场码注定出不来。原来只弹一句「从票夹进入才能出示入场码」就完了 —— 用户被晾在原地,
   * 不知道票夹在哪、怎么去。这里给一条真实出路:直接把他送到票夹(那里才是带 regId 的入口)。
   */
  _explainMissingEntryQr() {
    cyModal.show({
      title: '从票夹进入才能出示入场码',
      content: '入场码要绑定你那张票，所以只有从票夹点进来才带得到。现在去票夹?',
      confirmText: '去票夹',
      cancelText: '先不去',
      success: (r) => { if (r.confirm) wx.reLaunch({ url: '/subpackageMember/signup/index' }); },
    });
  },
  noop() {},
  /* 原型 sheet(inner, closeTo) 的 closeTo:能返回上一层就返回,否则整个收掉
     (脏态确认仍归 requestSceneClose)。漫游页同名同逻辑。 */
  onProtoSheetClose() {
    const cur = this.data.sceneCurrent;
    if (cur && cur.canBack) this.backScene();
    else this.requestSceneClose();
  },
  openBadge3d() {
    const m = this.data.medal || {};
    wx.navigateTo({ url: '/subpackageP3/pages/badge-3d/index?name=' + encodeURIComponent(m.name || '徽章')
      + '&style=' + (m.style || 'glow')
      + (m.img ? '&img=' + encodeURIComponent(m.img) : '') });
  },
  /** 券页显示层:券数据取「被点开的那个节点」(_voucherNode),不锁死 nextNode ——
   *  mode2 点非下一站的带券节点,票面商家名/地址必须是所点那家(review 抓出)。 */
  _syncVoucherView() {
    const node = this._voucherNode || this.data.nextNode || {};
    const reward = this._themeReward || {};
    const c = node.coupon || reward.finishCoupon || null;
    if (!c) return;   // 券页只在有券时打开(openTicket 已挡),不再造「入场」假票面
    const amt = c.amount != null ? String(c.amount).replace(/\.0+$/, '') : '';
    // 「满X可用」门槛已拍板删除展示(2026-09-17):线下到店券平台校验不了金额,只留券名。
    // 有效期展示只到日(全站统一 'YYYY.MM.DD');服务端判定仍用完整时间。
    const endDay = formatDayDots(c.endTime);
    this.setData({
      voucherView: {
        big: amt ? (amt + '元') : '优惠',
        small: c.name || '优惠券',
        expires: endDay ? ('截止日期：' + endDay) : '有效期见券包',
        merchantName: node.name || '合作商家',
        merchantAddress: node.address || '地址待补充',
      },
    });
  },
  /** 章节内容:正文按段拆开,一次只让一段处在「近焦」。
   *  文本来源是当前章节描述 + 已解锁节点的 story,与旅程手记同源,不另编内容。 */
  /* ===== 伏笔(2026-09-22 用户裁决) =====
     ① 微信运动授权在开局时弹(这局配了伏笔才弹,一局只问一次;拒了不再追问)。
     ② 读到正文里的伏笔块就认领 —— 不依赖微信运动:没授权也照样得到,只是步数不涨。
     ③ 到站同步一次步数。步数只交密文,解密在服务端。 */
  _askWeRunOnce() {
    if (!this._isPlayAccountCurrent() || this.data.isPreview || this.data.isMock || this._weRunAsked) return;
    const owner = this._boundPlayOwner(), sessionKey = this._sessionKey();
    const routeSessionId = String((this._routeState || {}).sessionId || '');
    const current = () => this._isPlayAccountCurrent() && owner === this._playOwner
      && !this.data.isPreview && !this.data.isMock && sessionKey === this._sessionKey()
      && routeSessionId === String((this._routeState || {}).sessionId || '');
    this._weRunAsked = true;
    let settled = false;
    wx.getSetting({
      success: (res) => {
        if (settled) return;
        settled = true;
        if (!current()) return;
        const granted = res && res.authSetting ? res.authSetting['scope.werun'] : undefined;
        this.setData({ weRunDenied: granted === false });
        if (granted !== undefined) return;
        wx.authorize({ scope: 'scope.werun', success: () => this.setData({ weRunDenied: false }), fail: () => this.setData({ weRunDenied: true }) });   // 拒了 = 伏笔只靠选项与剧情,不影响游玩
      },
    });
  },
  openWeRunSettings() {
    wx.openSetting({ success: (res) => {
      this.setData({ weRunDenied: !(res && res.authSetting && res.authSetting['scope.werun']) });
      if (!this.data.weRunDenied) this._syncThoughts([]);
    } });
  },
  _weRunCipher(isCurrent) {
    const current = () => typeof isCurrent !== 'function' || isCurrent();
    return new Promise((resolve) => {
      wx.getSetting({
        success: (res) => {
          if (!current()) { resolve(false); return; }
          if (!(res && res.authSetting && res.authSetting['scope.werun'])) { resolve(null); return; }
          // 必须先 wx.login 再 getWeRunData(session_key 要新鲜,否则服务端解不开)
          wx.login({
            success: (login) => {
              if (!current()) { resolve(false); return; }
              wx.getWeRunData({
              success: (run) => resolve(current() && login && login.code ? { code: login.code, encryptedData: run.encryptedData, iv: run.iv } : false),
              fail: () => resolve(false),
            });
            },
            fail: () => resolve(false),
          });
        },
        fail: () => resolve(false),
      });
    });
  },
  _claimThoughts(keys) {
    if (this.data.isPreview || this.data.isMock) return;
    if (!this._isPlayAccountCurrent()) return;
    const scope = JSON.stringify([this._boundPlayOwner(), this._sessionKey(), (this._routeState || {}).sessionId || '']);
    if (scope !== this._thoughtClaimScope) { this._thoughtClaimScope = scope; this._thoughtClaimed = {}; }
    this._thoughtClaimed = this._thoughtClaimed || {};
    const fresh = keys.filter((k) => !this._thoughtClaimed[k]);
    if (!fresh.length) return;
    fresh.forEach((k) => { this._thoughtClaimed[k] = true; });
    this._syncThoughts(fresh);
  },
  _syncThoughts(claim, isCurrent) {
    if (this.data.isPreview || this.data.isMock) return Promise.resolve(true);
    const owner = this._boundPlayOwner(), params = Object.assign({}, this._sessionParams());
    const topicId = this.data.topicId, sessionKey = this._sessionKey(params);
    const routeSessionId = String((this._routeState || {}).sessionId || '');
    const claimed = this._thoughtClaimed;
    const current = () => this._isPlayAccountCurrent() && owner === this._playOwner
      && sessionKey === this._sessionKey()
      && routeSessionId === String((this._routeState || {}).sessionId || '')
      && (typeof isCurrent !== 'function' || isCurrent());
    const releaseClaims = () => { if (current()) claim.forEach((k) => { if (claimed && this._thoughtClaimed === claimed) delete claimed[k]; }); };
    const sync = () => {
      if (!current()) return Promise.resolve(false);
      return this._weRunCipher(current).then((cipher) => {
      if (!current()) return false;
      if (cipher === false) { releaseClaims(); return false; }
      if (!claim.length && !cipher) return true;       // 未授权运动:继续正常游玩,使用未应验结局
      return req('/api/play/journey/thought/sync', 'POST', Object.assign({ topicId },
        params, { claim }, cipher || {}), { 'content-type': 'application/json' }).then((res) => {
        if (!current()) return false;
        if ((res.code == 200 || res.code == '200') && res.data) {
          this._applyRouteState(res.data, () => {
            if (!current()) return;
            if (this.data.chapterFull) this.openChapterFull();
            if (this.data.chapStory.show) this._refreshChapStoryThoughts();
          });
          return true;
        }
        // 失败:放开这几个的认领标记,下次读到再认领;不弹错 —— 伏笔晚一点出现不影响游玩
        releaseClaims();
        return false;
      });
    }).catch(() => { releaseClaims(); return false; });
    };
    // 认领与到站同步串行:先拿到伏笔,再同步步数,最后才提交可能结束本局的动作。
    const pending = (this._thoughtSyncPending || Promise.resolve()).then(sync);
    this._thoughtSyncPending = pending;
    pending.then(() => { if (this._thoughtSyncPending === pending) this._thoughtSyncPending = null; });
    return pending;
  },
  /* 故事流声音(2026-09-23 用户裁决「优化顺序第 2 项」):照原型 WebAudio 合成,不用素材。
     环境音跟章节氛围 / 氛围块走,读到梦块换梦的和弦;开场门、进梦、玩法完成各有一声。 */
  _storySound() {
    if (!this._sound) {
      this._sound = storyAudio.create({ createContext: () => (wx.createWebAudioContext ? wx.createWebAudioContext() : null) });
      let off = false;
      try { off = !!wx.getStorageSync(STORY_SOUND_KEY); } catch (e) { off = false; }
      if (off) { this._sound.setOn(false); if (this.data.soundOn) this.setData({ soundOn: false }); }
    }
    return this._sound;
  },
  /** 视口最上面那一行决定环境音:跟当前氛围(相册是通用的多照片块,不再按「梦」另起和弦与场景)。 */
  _syncStoryBed(line) {
    const chapterNow = this.data.chapter || {};
    // 背景场景与环境音同一张表:换景 = 换声(优化顺序第 3 项)
    this._sceneTarget = storyScene.sceneFor(this.data.storyMoodClass || chapterNow.atmosphereClass || chapterNow.atmospherePreset, false);
    const s = this._storySound();
    if (!s) return;
    const chapter = this.data.chapter || {};
    s.bed(storyAudio.bedForMood(this.data.storyMoodClass || chapter.atmosphereClass || chapter.atmospherePreset));
  },
  toggleStorySound() {
    const soundOn = !this.data.soundOn;
    try { wx.setStorageSync(STORY_SOUND_KEY, soundOn ? '' : '1'); } catch (e) { /* 存不下就只管这一次 */ }
    this.setData({ soundOn });
    const s = this._storySound();
    if (s) s.setOn(soundOn);
  },
  /* 罗盘 = 地图切换(2026-09-23 用户裁决,推翻 #1140 的「地图不要了」只针对故事流这一层):
     点开一张真实小地图(这一站 / 下一站 / 我),再点收回故事流。没有带坐标的地点就别开空地图,照旧回卡包。 */
  openStoryMap() {
    if (!(this.data.fmNodes || []).some((n) => n && n.lat && n.lng)) { this.closeChapterFull(); return; }
    const n = this.data.nextNode;
    // 以下一站为中心;没有下一站(已走完)就用整条路线的中心
    const center = n && n.lat && n.lng ? { lat: Number(n.lat), lng: Number(n.lng) } : this.data.fmCenter;
    this.setData({ storyMap: true, storyMapCenter: center, sessionToolsOpen: false });
    if (!this.data.gpsOk) this.requestPlayLocation();
  },
  closeStoryMap() { this.setData({ storyMap: false }); },
  /** 导航交给微信地图:站内不接管导航(#1140 的口径,退出小程序用外部地图零损失)。 */
  openNextStopNav() {
    const n = this.data.nextNode;
    if (!n || !n.lat || !n.lng) return;
    wx.openLocation({ latitude: Number(n.lat), longitude: Number(n.lng), name: n.name || '', address: n.address || '', scale: 17 });
  },
  openChapterFull() {
    const wasOpen = this.data.chapterFull;
    const items = [];
    const claims = [];     // 读到了、但服务端还没下发的伏笔 —— 渲染完再认领
    const chap0 = this.data.chapter || {};
    this._sceneTarget = storyScene.sceneFor(chap0.atmosphereClass || chap0.atmospherePreset, false);   // 开章先按章节氛围定景
    /* 故事变量(契约 §3.1):文本推入前先做 {名字} / {名字|兜底词} 替换。
       值是服务端会话视图下发的 vars map;没值且没兜底时**原样留花括号**(作者看得见),
       不渲染成空串 —— 空串会让漏掉的键永远没人发现。 */
    const vars = this._storyVars || null;
    // 氛围跟着正文流走:遇到 mood 块就换,之后的段落都带这个基调(mood 块自己不占一段)。
    let curMood = '';
    let curOdd = 0;   // 越往后越不正常:odd 块起的档位,之后每段都带着(同 mood)
    // cond:试玩里条件正文的「只给谁看」标签(真玩家拿到的条件块已由服务端按本局标签筛过,不带这个)
    const push = (t, cond) => { String(t || '').split(/\n+/).forEach((x, i) => { const v = x.trim(); if (v) items.push({ kind: 'text', text: applyStoryVars(v, vars), mood: curMood, odd: curOdd, cond: i === 0 && cond ? cond : '' }); }); };
    const condOf = (b) => (this.data.isPreview && b.when ? String(b.condLabel || '有条件才看到') : '');
    // 真实章节封面是叙事的第一屏，也必须进入 chapterParas，才能和正文共用近焦/渐隐测量。
    if (this.data.chapter && this.data.chapter.cover) {
      items.push({ kind: 'cover', text: '', url: String(this.data.chapter.cover) });
    }
    // 有块流就以块流为准:作者在故事流编辑器里怎么排,这里就怎么铺(文字/图片/音频)。
    //
    // ★ 故事流在【第一个未完成的节点块】处截断 —— 设计文档 §7.1「其后的块:完全不渲染」。
    //   这条不是可选的收敛:节点之后的文字是「走到了才解锁」的剧情,一次铺完就是剧透。
    //   ⚠️ master 上这屏只读 chapter.description,而后端的 description 投影本来就在首个
    //      node 块处停(ChapterFlowCompiler:beforeFirstNode)—— 所以旧代码是安全的,
    //      是「开始消费完整块流」这件事把潜在泄漏变成真泄漏。改块流渲染必须同时补这道闸。
    //   ⚠️ 后端目前仍全量下发 blocks(ApiPlayProgressController 未做 projectPlayerFlow),
    //      所以这只是客户端截断,抓包仍能看到全文。服务端裁剪另开 PR,别把这里当已收口。
    // ⚠️ node 块自身不展开成一段 —— 站点有自己的卡片,再摆一遍会把剧情读成目录;
    //    已完成的节点就地插入它的手记,顺序才跟作者编排的一致。
    // ⚠️ 也不能再叠一次 chapter.description:它就是块流里首个节点之前那些文字块的投影。
    const blocks = (this.data.chapter && this.data.chapter.blocks) || null;
    const nodes = this.data.nodes || [];
    /* 内嵌玩法(契约 §1.5):作者把它配成「长在故事流里」的 kit,铺成一个 kind:'kit' 段。
       ⚠️ 它必须仍是**一个 .chfull__para** —— measureChapter 用 selectAll('.chfull__para')
       按下标对齐 chapterParas,多一个少一个都让整屏焦点错位(见 WXML 的循环注释)。
       位置选在第一个未完成节点处:玩家读到「我当前站在这」,玩法就在这一块里等着。 */
    const inlineKit = this._inlineKit();
    const check = this.data.journeyCheck || {};
    const checkNode = nodes.find((n) => String(n.nodeId) === String(check.nodeId));
    const inlineCheck = check.show && !check.blocked && check.present === 'inline'
      && !(checkNode && checkNode.done);
    let kitPlaced = false;
    let autoNodeId = null;   // 读到的第一个内嵌玩法:不出「进入」,直接铺开(2026-09-24 对照原型)
    // 这一站玩法里发生过的内心声音(咔哒等)接在玩法后面;重铺整章也不能丢
    const pushVoices = (nodeId) => ((this._storyVoices || {})[String(nodeId)] || [])
      .forEach((v) => items.push({ kind: 'voice', who: v.who, color: v.color, text: v.text, mood: curMood, odd: curOdd }));
    if (blocks && blocks.length) {
      for (let i = 0; i < blocks.length; i += 1) {
        const b = blocks[i];
        if (!b) continue;
        if (b.type === 'node') {
          const n = nodes.find((x) => String(x.nodeId) === String(b.nodeId));
          if (!n || !n.done) {               // 未完成 ⇒ 故事流到此为止
            if (inlineCheck && String(b.nodeId) === String(check.nodeId)) {
              items.push({ kind: 'journeyCheck', nodeId: b.nodeId, mood: curMood });
              break;
            }
            if (inlineKit && (!this._playKitNodeId || String(this._playKitNodeId) === String(b.nodeId))) {
              items.push({ kind: 'kit', nodeId: b.nodeId, mood: curMood }); kitPlaced = true;
              pushVoices(b.nodeId);
            } else if (n && this._isStoryGame(n)) {
              const entry = Object.assign(storyGameEntry(n, curMood), { auto: true });
              if (presentOf(n) === 'inline') autoNodeId = n.nodeId;
              items.push(entry);
            }
            break;
          }
          const kitHere = inlineKit && String(this._playKitNodeId) === String(b.nodeId);
          if (kitHere) {
            items.push({ kind: 'kit', nodeId: b.nodeId, mood: curMood }); kitPlaced = true;
          }
          pushVoices(b.nodeId);
          if (n.story) push(n.story);        // 已完成 ⇒ 手记就地接上
          continue;
        }
        if (b.type === 'text') {
          const previousOdd = curOdd;
          if (b.level != null) curOdd = Math.max(0, Math.min(3, Number(b.level) || 0));
          push(b.who ? String(b.who) + '：' + String(b.content || '') : b.content, condOf(b));
          curOdd = previousOdd;
        }
        else if ((b.type === 'image' || b.type === 'audio') && b.url) {
          items.push({ kind: b.type, text: '', url: String(b.url), altText: typeof b.altText === 'string' ? b.altText.trim() : '', mood: curMood, odd: curOdd }); // altText(#18):图片 aria-label / 音频文字稿
        } else if (b.type === 'dream') {
          /* 梦块(契约 §3.2):一组图 + 每张一句,一张张「穿过去」。
             ⚠️ 整块仍是**一个** .chfull__para —— measureChapter 用 selectAll('.chfull__para')
             按下标对齐 chapterParas,块内多出的元素不进这个选择器,但少一个外层就整屏错位。 */
          const images = (b.images || [])
            .filter((im) => im && im.url)
            .map((im) => ({ url: String(im.url), line: applyStoryVars(im.line || '', vars) }));
          // 故事流里那条一直滚动的照片带:张数太少时补齐到至少 4 张,循环滚动才接得上
          const strip = [];
          while (images.length && strip.length < Math.max(4, images.length)) strip.push({ url: images[strip.length % images.length].url, k: strip.length });
          if (images.length) items.push({ kind: 'dream', title: b.title || '相册', images, strip, mood: curMood, odd: curOdd });
        } else if (b.type === 'mood') {
          // 标记块:换基调,不产出 item —— items 必须与 .chfull__para 一一对应。
          if (CHAPTER_ATMOSPHERE_VALUES.indexOf(b.mood) >= 0) curMood = b.mood;
        } else if (b.type === 'reveal' && b.content) {
          const lines = String(b.content).split(/\n+/).map((x) => applyStoryVars(x.trim(), vars)).filter(Boolean);
          if (lines.length) items.push({ kind: 'reveal', who: String(b.who || ''), lines, mood: curMood, odd: curOdd });
        } else if (b.type === 'odd') {
          curOdd = Math.max(0, Math.min(3, Number(b.level) || 0));   // 标记块,同 mood 不产出 item
        } else if (b.type === 'voice' && STORY_VOICE_COLORS[b.who] && b.content) {
          items.push({ kind: 'voice', who: (curOdd >= 3 && STORY_VOICE_ALT[b.who]) || b.who, color: STORY_VOICE_COLORS[b.who],
            text: applyStoryVars(String(b.content), vars), mood: curMood, odd: curOdd, cond: condOf(b) });
        } else if (b.type === 'thought') {
          // 原型 gainThought「得到一个念头」。真玩家只认服务端已下发的念头(没得到的不露名字 = 防剧透);
          // 试玩认草稿里配的。查不到就不出卡,不画空壳。
          const known = this.data.isPreview ? (this._previewThoughts || [])
            : ((this._routeState && this._routeState.thoughts) || []);
          const t = known.find((x) => x && String(x.key) === String(b.thoughtKey));
          if (t) items.push({ kind: 'thought', name: String(t.name || ''), desc: String(t.desc || ''), need: Number(t.need) || 0, mood: curMood });
          else if (!this.data.isPreview && b.thoughtKey) claims.push(String(b.thoughtKey));   // 读到了 ⇒ 向服务端认领
        }
      }
    } else {
      // 无块流的存量章节:沿用旧口径(描述 + 已完成节点的手记),行为逐字不变
      push(this.data.chapter.description);
      nodes.forEach((n) => { if (n.done && n.story) push(n.story); });
      /* 没有块流就没有「节点在哪一块」可对:正开着的内嵌玩法只能接在末尾。下面那条兜底要求
         _playKitNodeId 为空,而开站必然记下它 ⇒ 无块流章节的内嵌玩法以前一律不铺,
         玩家只看见手记和「继续探索」,玩法永远打不开(2026-10-27 生产实测:南京路附录盲盒)。 */
      if (inlineKit && this._playKitNodeId) {
        items.push({ kind: 'kit', nodeId: this._playKitNodeId, mood: curMood }); kitPlaced = true;
        pushVoices(this._playKitNodeId);
      }
    }
    // 没有未完成节点(线性走完 / 没有节点块)时,内嵌玩法接在故事流末尾
    if (inlineKit && !kitPlaced && !this._playKitNodeId) items.push({ kind: 'kit', mood: curMood });
    // 存量章节没有块流:检定仍可内嵌;完整块流只在其所属的未完成节点处铺。
    if (inlineCheck && !(blocks && blocks.length)) items.push({ kind: 'journeyCheck', nodeId: check.nodeId, mood: curMood });
    if (!items.length) push('这一章还没有写下的内容。');
    if (claims.length) this._claimThoughts(claims);
    audioSession.keepAwake('chapterFull', true);
    this.setData({
      chapterFull: true,
      chapterParas: items.map((item, i) => Object.assign({
        i, text: '', url: '', audioKey: 'ch' + i,
        in: !!this.data.reducedMotion, dir: 'up', d: 0,
      }, item)),
      storyChat: false,
    }, () => {
      if (!wasOpen) this._storyTop = 0;
      this.showChapterGate();
      if (!wasOpen) this._resumeStory();
      if (autoNodeId != null) this._autoInline(autoNodeId);
      this.measureChapter();
      if (!this.data.chapterGate.show) this.playChapterAudio();
      this._enterThemeChapter();
    });
  },
  /** 内嵌玩法读到就铺开(原型:登记表、选择题、「在现场」都直接嵌在故事流里),每站只自动开一次 */
  _autoInline(nodeId) {
    this._autoInlined = this._autoInlined || {};
    const key = String(nodeId);
    if (this._autoInlined[key] || this.data.playKit.show || String(this._playKitNodeId) === key) return;
    this._autoInlined[key] = true;
    this.startStoryGame({ currentTarget: { dataset: { nodeid: nodeId } } });
  },
  /** 开局第一屏:故事主题先看开场页(原型 game-intro),「开始游戏」之后才进故事流;
   *  这一局看过就不再出现(试玩每次都看)。不是故事主题直接进故事流。 */
  _openStory() {
    const chapter = this.data.chapter || {};
    const seen = !this.data.isPreview && !!storyResume.read(this._runSessionScope()).intro;
    if (!Array.isArray(chapter.blocks) || seen || this.data.storyIntro.show) { this.openChapterFull(); return; }
    this.setData({ storyIntro: { show: true, out: false, title: this.data.topicName || chapter.name || '', line: introLine(this.data.topicDesc) } });
  },
  startStoryIntro() {
    if (!this.data.storyIntro.show || this.data.storyIntro.out) return;
    storyResume.patch(this._runSessionScope(), { intro: true });
    const s = this._storySound();
    if (s) s.sfx('page');
    const go = () => { this.setData({ 'storyIntro.show': false }); this.openChapterFull(); };
    if (this.data.reducedMotion) { go(); return; }
    this.setData({ 'storyIntro.out': true });
    this._fxTimers = (this._fxTimers || []).concat(setTimeout(go, 400));
  },
  /** 初始状态页:这一局只看一次;没有可列的数值就不出 */
  _showStartState(rows) {
    if (!rows || !rows.length || this.data.startState.show) return;
    const scope = this._runSessionScope();
    if (this.data.isPreview ? this._startStateShown : storyResume.read(scope).stats) return;
    this._startStateShown = true;
    storyResume.patch(scope, { stats: true });
    this.setData({ startState: { show: true, out: false, rows } });
  },
  closeStartState() {
    if (!this.data.startState.show || this.data.startState.out) return;
    const hide = () => this.setData({ 'startState.show': false });
    if (this.data.reducedMotion) { hide(); return; }
    this.setData({ 'startState.out': true });
    this._fxTimers = (this._fxTimers || []).concat(setTimeout(hide, 400));
  },
  /** 相册(块类型仍是 dream,面上叫「相册」):点故事流里那条照片带,打开白底全屏 */
  openAlbum(e) {
    const para = (this.data.chapterParas || [])[Number(e.currentTarget.dataset.i)];
    if (!para || para.kind !== 'dream' || !(para.images || []).length) return;
    this.setData({ album: { show: true, cur: 0, title: para.title || '相册', images: para.images } });
    this._albumStatusBar(true);
  },
  closeAlbum() {
    if (!this.data.album || !this.data.album.show) return;
    this.setData({ 'album.show': false });
    this._albumStatusBar(false);
  },
  /** 白底全屏时状态栏字换黑,回故事流换回白(页面自绘导航,只动前景色) */
  _albumStatusBar(light) {
    if (typeof wx.setNavigationBarColor !== 'function') return;
    wx.setNavigationBarColor({ frontColor: light ? '#000000' : '#ffffff', backgroundColor: light ? '#ffffff' : '#000000' });   /* ds-ok 微信 API 只收这两个字面色 */
  },
  closeChapterFull() {
    if (this._sound) this._sound.bed('void');
    // 收起就停章节音频:它属于这一屏,留在后台继续念会跟节点语音导览抢同一个播放器。
    // 整章旁白('chapter')和故事流音频块('ch<i>')都属于这一屏,收起时一并停。
    this.closeAlbum();
    const k = String(this.data.audioNodeId || '');
    if (k === 'chapter' || k.indexOf('ch') === 0) this.stopAudio();
    this._stopStoryDust();
    if (this._settleTimer) clearTimeout(this._settleTimer);
    if (this._chMeasureTimer) clearTimeout(this._chMeasureTimer);
    audioSession.keepAwake('chapterFull', false);
    this.setData({ chapterFull: false, 'chapterGate.show': false });
  },

  continueChapterStory() {
    const opening = this._routeState && this._routeState.opening;
    // 结局章读完:收起故事流,出结局卡(真实游玩与试玩同一条)
    if (this._afterEndingChapter) {
      const after = this._afterEndingChapter;
      this._afterEndingChapter = null;
      this.closeChapterFull();
      after();
      return Promise.resolve();
    }
    // 试玩:本章读完 → 下一章 / 看结局 / 回编辑(顶条的「跳过 / 下一章」已删,这里是唯一入口)
    if (this.data.isPreview) { this.advancePreview(); return Promise.resolve(); }
    if (this.data.isMock || !opening || opening.complete || !this.data.chapter.opening) {
      const next = this.data.nextNode;
      const node = next && (this.data.nodes || []).find(n => String(n.nodeId) === String(next.nodeId));
      if (!this._isPlayAccountCurrent()) return Promise.resolve();
      const context = { owner: this._boundPlayOwner(), sessionKey: this._sessionKey(),
        routeSessionId: String((this._routeState || {}).sessionId || ''), chapter: this.data.chapter };
      this._chapterContinueIntent = context;
      return this._endThemeChapter().then(ok => {
        if (!ok || !node || this._chapterContinueIntent !== context || !this._isPlayAccountCurrent()
            || context.owner !== this._playOwner || context.sessionKey !== this._sessionKey()
            || context.routeSessionId !== String((this._routeState || {}).sessionId || '')
            || this.data.chapter !== context.chapter) return;
        const currentNext = this.data.nextNode;
        const currentNode = (this.data.nodes || []).find(n => String(n.nodeId) === String(node.nodeId));
        if (!currentNext || String(currentNext.nodeId) !== String(node.nodeId) || currentNode !== node || currentNode.done) return;
        this._chapterContinueIntent = null;
        this.openSheet(node);
      });
    }
    if (!opening.ready) {
      const nodeId = this._routeState.currentNodeId;
      this.setData({ chapterGameAnchor: 'chapter-game-' + nodeId });
      cyToast('请先完成开局玩法');
      return Promise.resolve();
    }
    if (!this._isPlayAccountCurrent() || this._openingSubmitting) return Promise.resolve();
    const context = this._beginRouteWriteContext('_openingWrite');
    const chapter = this.data.chapter;
    const current = () => this._isRouteWriteContextCurrent(context)
      && String((this.data.chapter || {}).id) === String((chapter || {}).id) && (this._routeState || {}).opening
      && String(this._routeState.opening.chapterId) === String(opening.chapterId);
    const payload = Object.assign({}, this._sessionParams(), { topicId: this.data.topicId, chapterId: opening.chapterId });
    this._openingSubmitting = true;
    return this._withThoughtSync(() => req('/api/play/journey/opening/complete', 'POST', Object.assign({}, payload, {
      expectedVersion: this._routeState.version,
    }), { 'content-type': 'application/json' }), current).then(res => {
      if (!current() || res && res.obsolete) return;
      if (Number(res.code) !== 200) {
        cyToast(res.msg || '保存失败，请重试');
        if (isRouteConflict(res)) this.loadData(false);
        return;
      }
      this.closeChapterFull();
      this.loadData(false);
    }).catch(() => { if (current()) cyToast('保存失败，请重试'); }).finally(() => {
      if (this._openingWriteIntent !== context) return;
      this._finishRouteWriteContext(context);
      this._openingSubmitting = false;
    });
  },

  /** 章节背景旁白。2026-09-04 起音频是章节属性(chapter.audioUrl)、一章一段,
   *  不再是故事流里某个位置上的可点块 —— 打开本章就播,玩家不用点。
   *  与节点语音导览共用同一个 InnerAudioContext —— 两段同时出声是灾难,
   *  共用播放器天然保证「后播的把前面那段顶掉」。
   *  audioNodeId 在这里存字符串 'ch',节点侧存的是数字 nodeId,=== 比较不会撞。 */
  /** 故事流右上角的声音钮:整章旁白开/关。只有作者配了 audioUrl 时 wxml 才渲染它。 */
  toggleStoryAudio() {
    if (this.data.audioNodeId === 'chapter' && this.data.audioPlaying) { this._stopChapAudio(); return; }
    this.playChapterAudio();
  },

  playChapterAudio() {
    const url = this.data.chapter && this.data.chapter.audioUrl;
    if (!url) return;
    // 已经在放这一章的旁白就不要重来 —— 滚动/重测都会再进这里,重播会把它剁成开头一秒。
    if (this.data.audioNodeId === 'chapter' && this.data.audioPlaying) return;
    this.initAudio();
    const audio = this._audio;
    audio.stop();
    audio.src = String(url);
    audio.play();
    this.setData({ audioNodeId: 'chapter' });
  },

  /** 故事流里带位置的音频块(2026-09-06 恢复,与上面的整章旁白并存)。
   *  三者共用同一个 InnerAudioContext —— 章节旁白 / 音频块 / 节点语音导览同时出声是灾难,
   *  共用播放器天然保证「后播的把前面那段顶掉」。
   *  key 分三套且互不相等:整章旁白 = 'chapter',块 = 'ch<i>',节点侧 = 数字 nodeId。 */
  toggleChapterAudio(e) {
    const i = Number(e.currentTarget.dataset.i);
    const item = (this.data.chapterParas || [])[i];
    if (!item || item.kind !== 'audio' || !item.url) return;
    this.initAudio();
    const audio = this._audio;
    const key = 'ch' + i;
    const same = this.data.audioNodeId === key;
    if (same && this.data.audioPlaying) { audio.pause(); return; }
    if (same && !this.data.audioError) { audio.play(); return; }
    audio.stop();
    audio.src = item.url;
    audio.play();
    this.setData({ audioNodeId: key });
  },
  /** 正文布局落定后量真实段落;滚动仍共用故事流的去抖与显形路径。 */
  measureChapter() {
    if (this._chMeasureTimer) clearTimeout(this._chMeasureTimer);
    this._chMeasureTimer = setTimeout(() => {
      if (!this.data.chapterFull || this.data.chapterGate.show) return;
      // 这是延时回调:页面有可能已经走掉。那时 selector query 拿不到,量不了也画不了 ——
      // 城市定向改成打开即故事流后,这条路径每次进页面都会走到,不守住就会在卸载后抛。
      if (typeof wx.createSelectorQuery !== 'function') return;
      this._settleStory();
      this._stopStoryDust();
      this._startStoryDust();
    }, 120);
  },
  onChapterScroll(e) { this.onStoryScroll(e); },
  showChapterGate() {
    const chapter = this.data.chapter || {};
    if (!chapter.id || chapter.ending) return;   // 结局章不弹开场门:它不是路线的第 N 章
    const key = String(this.data.topicId) + ':' + String(chapter.id);
    if (this._seenChapterGates && this._seenChapterGates.has(key)) return;
    this._seenChapterGates = this._seenChapterGates || new Set();
    // 存档:看过的开场门记在本地,中途退出重进不再重播
    const savedGates = storyResume.read(this._runSessionScope()).gates || [];
    if (savedGates.indexOf(String(chapter.id)) >= 0) { this._seenChapterGates.add(key); return; }
    // 这一局的第一道门先「开机」(原型 flashl + warm):只在真正开局时放一次
    const boot = !this.data.reducedMotion && !this._seenChapterGates.size && !savedGates.length && !this._booted;
    if (boot) this._playBoot();
    const cards = (this.data.chapterCards || []).filter(card => !card.opening);
    const index = cards.findIndex(card => String(card.id) === String(chapter.id));
    this._pendingChapterGateKey = key;
    const sound = this._storySound();
    if (sound) sound.sfx('boot');
    const flip = chapterFlip.buildGateFlip({ cards, chapterId: chapter.id, nodes: this.data.nodes, topicCover: this.data.topicCover, topicName: this.data.topicName, desc: introLine(chapter.description) }); if (chapter.opening) Object.assign(flip, { endInfo: null, info: { kicker: chapter.name === '序章' ? '' : '序章', title: chapter.name || '', desc: introLine(chapter.description), stats: [] } });
    this.setData({ chapterGate: Object.assign({ show: true, number: chapter.opening ? 0 : (index < 0 ? 1 : index + 1), // 眉标已写「序章 / 第 N 章 / 共 M 章」,副标题再放序数标签就是同一句印两遍(2026-09-22 走查)。
      total: Math.max(cards.length, 1), title: chapter.name || '', subtitle: '' }, flip) });
    // 2026-09-24 用户:章节门每章都要点一次太打断 —— 停 2.8 秒自己进正文,点一下仍可提前
    clearTimeout(this._gateTimer);
    this._gateTimer = setTimeout(() => {
      if (this.data.chapterGate.show && this._pendingChapterGateKey === key) this.continueChapterGate();
    }, (boot ? 1800 : 0) + (this.data.chapterGate.endInfo ? 7800 : 4200)); // 过场自己会在播完时发 continue;这里只是兜底
  },
  /** 开局「开机」:黑场里一条白线横向展开再收细,暖光亮起后整层淡出,露出第一道开场门。 */
  _playBoot() {
    this._booted = true;
    this._fxTimers = this._fxTimers || [];
    this.setData({ bootStage: 'on' });
    [['line', 60], ['open', 520], ['fade', 1100], ['', 2000]].forEach(([stage, at]) => {
      this._fxTimers.push(setTimeout(() => this.setData({ bootStage: stage }), at));
    });
  },
  /** 结局卡入场:先挂上(透明),下一拍再加 in,让各段依次浮上来;减弱动态直接落定。 */
  _showEnding(ending) {
    const reduced = !!this.data.reducedMotion;
    this.setData({ previewEnding: Object.assign(ending, { in: reduced, off: '' }) }, () => {
      if (!reduced) this._fxTimers = (this._fxTimers || []).concat(setTimeout(() => this.setData({ 'previewEnding.in': true }), 30));
    });
  },
  continueChapterGate() {
    clearTimeout(this._gateTimer);
    if (this._pendingChapterGateKey) {
      this._seenChapterGates.add(this._pendingChapterGateKey);
      const scope = this._runSessionScope();
      const gates = storyResume.read(scope).gates || [];
      const chapterId = String((this.data.chapter || {}).id || '');
      if (chapterId && gates.indexOf(chapterId) < 0) storyResume.patch(scope, { gates: gates.concat(chapterId).slice(-40) });
    }
    this._pendingChapterGateKey = '';
    this.setData({ 'chapterGate.show': false }, () => {
      this.measureChapter();
      this.playChapterAudio();
    });
  },
  /** 滑动核销确认:真正的核销是商家扫玩家的码,这里只是玩家侧把码亮出来并标记本次已出示。
   *  ⚠️ 不在客户端写「已核销」到后端 —— 那是商家侧的动作,客户端说了不算。 */
  confirmRedeem() {
    cyToast('请让商家扫这个码');
  },
  /** 顶部条第二行:直线距离 + 按步行 5km/h 的预计用时与到达时刻。
   *  这三个都是估算,文案里已经写了「预计」;真逐向导航要接 SDK,不在这一版。 */
  _syncNavMeta() {
    const m = Number(this.data.nav.remainM) || 0;
    if (!this.data.nav.active) return;
    const minutes = Math.max(1, Math.round(m / (5000 / 60)));
    const eta = new Date(Date.now() + minutes * 60000);
    const pad = (v) => String(v).padStart(2, '0');
    this.setData({
      navMeta: {
        km: (m / 1000).toFixed(1),
        dur: minutes >= 60 ? Math.floor(minutes / 60) + '小时' + (minutes % 60) + '分' : minutes + '分钟',
        eta: pad(eta.getHours()) + ':' + pad(eta.getMinutes()),
      },
    });
  },

  // 空态动作
  goSignup() { this.goBackDetail(); },                                           // 回场次详情去报名
  // C-26:自玩没有(或过期)通行证 → 出路是去主题详情获取通行证(自玩没有「报名」这个动作)。
  // 之前这里会落到 signup 空态文案「你还没有报名这个场次」+「去报名」,把用户指向票务报名。
  goGetPass() {
    const topicId = this.data.topicId;
    if (topicId) { wx.navigateTo({ url: '/pages/topic/index/index?id=' + topicId }); return; }
    this.goBackDetail();
  },
  doRelogin() {
    var that = this;
    app.reLogin(function (ok) {
      if (ok) { that.reloadPlay(); }
      else { cyToast('登录失败，请重新进入'); }
    });
  },
  reloadPlay() { this.setData({ loading: true, emptyTip: '', emptyKind: '' }, () => this.loadData(true)); },

  // 错误态次级退出路径(DS Error §):返回活动详情。play 由详情/票夹压入,优先 navigateBack;无栈则回票夹。
  goBackDetail() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: '/subpackageMember/signup/index' });
  },

  // ---------- 数据 ----------
  loadData(first) {
    if (!this._isPlayAccountCurrent() || this._themeHidden) return;
    const loadEpoch = this._playLoadEpoch = (this._playLoadEpoch || 0) + 1;
    const sessionKey = this._sessionKey();
    const isCurrent = () => this._isPlayAccountCurrent() && loadEpoch === this._playLoadEpoch
      && sessionKey === this._sessionKey();
    const that = this;
    if (this.data.isPreview || this.data.isMock) { this.setData({ loading: false }); return; }
    this._loadCommitted = false;   // 每轮加载重新武装:上一轮的成功不能替这一轮的失败背书
    // 缺场次参数 ≠ 加载失败:reloadPlay 会再次命中这一行 return,给「重新加载」等于给一个
    // 物理上永远回到同一状态的假按钮。单独落 missing,只留真的走得通的出口(回活动详情)。
    if (!this.data.activityId && !this.data.topicId) { this.setData({ loading: false, emptyTip: '链接已失效，请返回重新进入', emptyKind: 'missing' }); return; }
    req('/api/play/nodes', 'GET', this._sessionParams()).then((res) => {
      if (!isCurrent()) return;
      if (res.code == 402 || res.code == '402') {
        // C-26:未购/过期自玩通行证 → needPass 空态 + 「获取通行证」按钮(goGetPass 跳主题详情)。
        // 不再落 registered=false 的「你还没有报名这个场次」——自玩没有报名这个动作,那句指引是错的。
        that.setData({ loading: false, emptyTip: app.getRequestErrorMessage(res, '请先购买自玩通行证后再开始'), emptyKind: 'needPass' });
        return;
      }
      if (res.code != 200 && res.code != '200') {
        var isAuth = (res.msg && (res.msg.indexOf('登录') >= 0 || res.msg.indexOf('认证') >= 0));
        that.setData({ loading: false, emptyTip: isAuth ? '登录已过期，请重新登录' : (res.msg || '加载失败，请重试'), emptyKind: isAuth ? 'auth' : 'error' });
        return;
      }
      const d = res.data;
      if (!isRecord(d) || !isRecordList(d.nodes)) {
        that.setData({ loading: false, emptyTip: '加载失败，请重试', emptyKind: 'error' });
        return;
      }
      // 领队(leadSpectator)是运营身份,本来就不报名本场,不该被未报名空态挡住;
      // 它只解锁渲染,玩家权益仍由 registered 管。判据见 play-lead-spectator-gate.test.js。
      if (d.registered === false && !d.leadSpectator) {
        that.setData({ loading: false, emptyTip: '你还没有报名这个场次', emptyKind: 'signup' });
        return;
      }
      that._playable = d.playable !== false;
      const raw = d.nodes;
      if (!raw.length && !(d.routeState && d.routeState.opening)) {
        that.setData({ loading: false, emptyTip: '路线配置中', emptyKind: 'empty' });
        return;
      }
      const mode = Number(d.mode) || that.data.mode || 1;
      /* 故事变量的两个来源之一(另一个是 advanced 会话视图):/api/play/nodes 回包 data.vars。
         章节文本与节点 story 大多数时候在这个点的会话之前就被读到 —— 只从会话视图取的话
         {name} 会一直原样留着,所以这条必须在首屏成功分支里落。 */
      that._applyStoryVars(d);
      that._puzzlePersonalBest = Number(d.puzzlePersonalBest) || 0;
      // 规则16 历史履约:已普通下架的本人已完成站只作只读回看,不并入 nodes/total/完成率/解锁/推荐/地图。
      const historyNodes = Array.isArray(d.historyNodes)
        ? d.historyNodes.map((h) => Object.assign({}, h, { history: true, done: true }))
        : [];
      // 只喂 buildJournal / _historyNodeById 的内部状态,不渲染、不进 setData(UI-GATE-0 死数据字段口径)。
      that._historyNodes = historyNodes;
      const catalog = raw.map((n, i) => that.normNode(n, i, mode));
      const routeInput = d.routeState || { routeMode: d.routeMode };
      const acceptProjection = that._acceptRouteStateProjection(routeInput);
      const routeState = acceptProjection ? normalizeRouteState(routeInput) : that._routeState;
      const nodes = applyRouteStateToNodes(catalog, routeState);
      that._routeState = routeState;
      if (acceptProjection) that._versionedRouteState = that._routeStateVersionKnown(routeInput) ? routeState : null;
      that._routeNodeCatalog = catalog;
      if (routeState.thoughtCount > 0) that._askWeRunOnce();
      // 加载/轮询不是原命令终态，不能清除待确认身份。
      const chapter = that.buildChapter(d, nodes);
      // 成功已落地:此后回调里的后处理(rebuild / 会话时钟 / 角色卡)再抛错,不该冒充「加载失败」
      // 把已经渲染出来的首屏盖成错误页 —— 重试只会重跑同一段计算,用户永远出不去。异常仍打 console。
      that._loadCommitted = true;
      // 通关奖励摘要:唯一渲染点(剧本 tab 的奖励卡)随起始页删除,这里只留 js 侧消费
      // (完成页判「有没有券」与奖励文案),所以退回实例字段,不再占一个没人渲染的 data 位。
      that._themeReward = d.reward || null;
      const topicName = d.topicName || that._entryTopicName || '';
      that._vitalsOn = d.vitalsOn || null;
      // 2026-09-22 拆除:此处原先按主题名正则 /^预制人生/ 把整局 redirectTo 到硬编码分包
      // subpackagePrefab。那条换轨让「用玩法模板 + 主题配置做出《预制人生》」永远无法验收 ——
      // 作者配得再对,一取这个名字就被顶掉,看到的仍是写死在 JS 里的那份。
      that.setData({
        loading: false, emptyTip: '', emptyKind: '', topicId: d.topicId || '', topicName, topicDesc: d.topicDesc || '', topicCover: d.topicCover || '',
        mode, serverMode: mode, playContact: playOverlays.contact(d.contact),
        modeLabel: mode == 2 ? '自由探索' : '城市定向',
        routeNotice: that._routeNoticeForState(routeState),
        journeyAttributes: routeState.attributes,
        timeBanner: d.playable === false ? (d.timeNote || '') : '',
        // M2 自玩态:顶部状态条"自由探索中 · 有效期至 M/D"
        selfPlay: !!d.selfPlay,
        selfPlayBanner: d.selfPlay ? ('自由探索中 · 有效期至 ' + that._fmtMD(d.expiresAt)) : '',
        chapter, nodes, total: nodes.length,
        chapterCards: that.buildChapterCards(d, chapter).map((c) => ({
          ...c,
          nodeCount: c.id != null ? nodes.filter((n) => String(n.chapterId) === String(c.id)).length || nodes.length : nodes.length,
        })),
        doneCount: nodes.filter((n) => n.done).length,
        myRole: null, 'roleCard.show': false,
        // 首屏成功态与数据同一次 setData 落地:原来放在 setData 回调里,回调里任一处理抛错
        // (rebuild / 会话时钟 / 角色卡)都会把页面永久留在 screen='' 且无提示的地图态 —— 
        // 2026-09-16 截图冒烟拍到的「整屏纯黑、无任何内容与提示」就是这一类无出口态。
        // 2026-09-22 删起始页后首屏直接落卡包,first 不再切屏,保持当前 screen。
        screen: that.data.screen
      }, () => {
        if (!isCurrent()) return;
        that.rebuild();
        that._ensureSessionClock();
        that._syncIdleStats(d.chapters, nodes);
        that._syncRunState();
        if (routeState.opening && !routeState.opening.complete) {
          that.setData({ screen: '' });
          that._openStory();
        } else if (String(that.data.mode) !== '2') {
          // 城市定向:打开就是故事流(2026-09-22 裁决「按预制人生来」)。
          // 地图没有删 —— 左上角罗盘随时切回去,只是它不再是第一屏。
          that._openStory();
        } else if (!first && that.data.chapterFull) that.openChapterFull();
        // R9-21:服务端态允许继续时,把上次暂停的会话恢复成 paused(不重开、不丢用时)
        that._restoreRunSession(d, routeState, nodes);
        that.loadTeamProgress();   // P1 带领场探测(club 场次才 exists,内部自管轮询)
        that._loadPlayerGameModule();
        that.loadHudCharacter(); that.loadSessionStats();   // 文档二 L5:状态栏第二行 / 「…」用时与得分 if (that._entryLayer && ['*', String(that._orientSessionId())].includes(that._entryLayer)) that.openScoreOverlay(); that._entryLayer = '';
      });
    }).catch(() => {
      if (!isCurrent()) return;
      // 网络层 reject(超时/断网/req 抛错):落加载失败态,复用「重新加载」(reloadPlay),避免 loading 死转。
      // 已经落地的成功首屏不在这里被覆盖(见上面的 _loadCommitted)。
      if (that._loadCommitted) {
        // 只打固定标签:生产 console 门禁禁止把异常/响应/身份动态值写进开发者日志。
        console.error('[play] 首屏数据已落地，后处理异常');
        return;
      }
      that.setData({ loading: false, emptyTip: '加载失败，请重试', emptyKind: 'error' });
    });
  },

  // 预览态:把 fabu 产出的 previewData 经现有渲染链归一并落到 data(契约字段已对齐 normNode)
  applyPreviewData(data) {
    const that = this;
    const d = data || {};
    const raw = d.nodes || [];
    this._vitalsOn = d.vitalsOn || null;
    this._previewStartRows = d.startState || [];
    if (this.data.isPreview) { this._previewChapters = d.chapters || []; this._previewChapterIndex = 0; this._previewThoughts = d.thoughts || []; this._previewEndings = d.endings || []; }
    const mode = Number(d.mode) || 1;
    const catalog = raw.map((n, i) => that.normNode(n, i, mode));
    const routeState = normalizeRouteState(d.routeState || { routeMode: d.routeMode });
    const nodes = applyRouteStateToNodes(catalog, routeState);
    that._routeState = routeState;
    that._versionedRouteState = that._routeStateVersionKnown(d.routeState) ? routeState : null;
    that._routeNodeCatalog = catalog;
    const chapter = that.buildChapter(d, nodes);
    this._historyNodes = [];
    this.setData({
      loading: false, emptyTip: '', emptyKind: '', topicId: d.topicId || '', topicName: d.topicName || '', topicDesc: d.topicDesc || '', topicCover: d.topicCover || '', mode, serverMode: mode,
      modeLabel: mode == 2 ? '自由探索' : '城市定向',
      routeNotice: that._routeNoticeForState(routeState),
        journeyAttributes: routeState.attributes,
      screen: d.startScreen || '',
      selfPlay: !!d.selfPlay,
      selfPlayBanner: d.selfPlay ? ('自由探索中 · 有效期至 ' + that._fmtMD(d.expiresAt)) : '',
      chapter, nodes, total: nodes.length,
      chapterCards: that.buildChapterCards(d, chapter),
      doneCount: nodes.filter((n) => n.done).length
    }, () => {
      that.rebuild();
      that._ensureSessionClock();
      that._syncIdleStats(d.chapters, nodes);
      that._syncRunState();
      if (that.data.isPreview && d.startStory) that._openStory();
      // 城市定向打开即故事流(2026-09-22 裁决)。loadData 那条路径已经这么做了,预览/mock
      // 走的是本方法并直接 return,不补这一句的话作者试玩看到的仍是地图 —— 与玩家不是同一个首屏。
      else if (String(that.data.mode) !== '2') that._openStory();
    });
  },

  normNode(n, i, mode) {
    // 后端 validationMethod:0/null 到点即完成 · 1 文字 · 2 拍照 · 3 选项 · 4 扫码 · 5 GPS到达 · 6偏好题组
    const vm = Number(n.validationMethod) || 0;
    let advancedConfig = null;
    try { advancedConfig = n.advancedConfigJson ? JSON.parse(n.advancedConfigJson) : null; } catch (e) { advancedConfig = null; }
    const hasAdvanced = !!(advancedConfig && Object.keys(advancedConfig).some(function (key) {
      if (key === 'schemaVersion') return false;
      const seg = advancedConfig[key];
      return seg && typeof seg === 'object' && seg.enabled;
    }));
    const isGame = hasAdvanced || (vm === 1 || vm === 2 || vm === 3 || vm === 4 || vm === 6), shopDay = Number(mode || this.data.mode) === 2; // 5/0 走到达,不开节点任务
    return {
      nodeId: n.nodeId != null ? n.nodeId : i,
      num: n.sortId != null ? n.sortId : (i + 1),
      sortId: n.sortId != null ? n.sortId : (i + 1),
      name: n.name || ('第 ' + (i + 1) + ' 站'),
      address: n.address || '',
      description: n.description || n.question || NODE_DESC_FALLBACK,
      imgUrl: n.imgUrl || '',
      businessTime: n.businessTime || '',
      openStatus: n.openStatus || '',                       // S4 服务端营业态三态(营业中/即将打烊/已打烊);空则大卡回退客户端 inBizWindow
      lng: Number(n.longitude) || 0, lat: Number(n.latitude) || 0,
      done: !!n.done,
      doneAt: parseGameCompletedAt(n.doneAt),   // 手记按服务端真实完成时间排序
      picUrl: n.picUrl || '',          // 当前玩家本人上传的完赛照片；节点封面仍只走 imgUrl
      arrived: !!n.arrived,
      arrivedAt: n.arrivedAt || null,
      gameDone: !!n.gameDone,   // 到店三步第二步:凭证已记录(后端只在 mode=2 下发)
      selfReported: !!n.selfReported,
      hasGame: !!n.hasGame,   // B5 收窄:该店配了答题模板(题面按进店态另行下发)
      gameKind: gameKindOf(vm, advancedConfig, ''),          // 故事流入口卡的图标与动作词
      crowded: n.crowded === true ? true : (n.crowded === false ? false : null),
      inStoreLoad: n.inStoreLoad,
      serviceCapacity: n.serviceCapacity || null,
      expectedWaitMinutes: n.expectedWaitMinutes,
      quantityUnavailable: !!n.quantityUnavailable,
      chapterId: n.chapterId != null ? n.chapterId : null,   // S1 卡堆行章节色条
      hookText: n.hookText || '',                            // S1 卡堆行钩子一句(空则行内回退 description)
      merchantId: n.merchantId || 0,                         // S1 卡堆行「合作商户」标注(后端未下发时恒为0,标注不显示)
      // 节点级前置解锁(服务端 /nodes 下发;硬闸在服务端,这里只做置灰与提示)
      locked: !!n.locked,
      paused: String(n.runtimeStatus || n.stationStatus || '').toUpperCase() === 'PAUSED',
      runtimeStatus: String(n.runtimeStatus || '').toUpperCase(),
      unlockAfterNodeId: n.unlockAfterNodeId != null ? Number(n.unlockAfterNodeId) : 0,
      hasTemplate: isGame,
      hasAdvanced, advancedConfig,
      ...(this.data.isPreview ? { previewKit: n.previewKit || null } : {}),
      vm,
      question: n.question || '', options: n.options || null,
      // 题面媒体(看图答题 / 听音答题)。与 audio(整节点语音导览)不是一回事:
      // 那是到达就能听的导览,这是这道题的题面,只在答题卡里出现。
      questionImg: n.questionImg || '', questionAudio: n.questionAudio || '',
      optionMedia: n.optionMedia || null,
      points: n.xp || 12,
      // 模板呈现字段(/api/play/nodes 已下发)
      gameTitle: n.gameTitle || (vm === 6 ? '生活偏好校准' : (vm === 4 ? '现场打卡' : (vm === 2 ? '拍照任务' : (vm ? '节点任务' : '')))),
      duration: n.duration ? (n.duration + ' 分钟') : '', difficulty: n.difficulty || '', players: n.players || '',
      materials: n.requiredMaterials || '', rule: n.ruleInstructions || '',
      story: n.storyText || '', cover: n.storyImg || n.imgUrl || '',
      cardHook: n.cardHookLong || '',
      fragmentText: n.fragmentText || '',
      hint1: n.hint1 || '', hint2: n.hint2 || '', reveal: n.answerReveal || '',
      hintLocked: !!n.hintLocked, hintCost: n.hintCost || 0, hintCount: n.hintCount || 0,
      puzzleScoring: !!n.puzzleScoring,
      puzzleHintLevel: Number(n.puzzleHintLevel) || 0,
      puzzleScoreCap: n.puzzleScoreCap == null ? 100 : Number(n.puzzleScoreCap),
      usedHints: Array.isArray(n.usedHints) ? n.usedHints.filter(Boolean) : [],
      puzzleScore: n.puzzleScore == null ? null : Number(n.puzzleScore),
      completionMode: n.completionMode || '',
      feedback: n.feedbackText || '答案还没咬合,再看看四周……',
      photoDesc: n.photoRequireDesc || '', audio: n.audioUrl || '', audioDur: (n.audioDuration ? n.audioDuration + '″' : ''),
      medalName: shopDay ? '' : (n.medalName || ''), medalImg: shopDay ? '' : (n.medalImg || ''), medalStyle: shopDay ? '' : (n.medalStyle || 'glow'), couponId: shopDay ? 0 : (n.couponId || 0),
      // 后端 /nodes 新下发的券公开投影(name/amount/endTime;无 code):券页据此显示真面额
      coupon: shopDay ? null : (n.coupon || null),
      // 探店日卡片详情三段各自的料:章节钩子 / 店铺分身 / 本店权益。
      // 三个都只在 mode=2 下发,经典定向恒空 —— 那边的详情页不长这样。
      hookText: n.hookText || '',
      npc: n.npc || null,
      perk: n.perk || null
    };
  },

  /** 起始页(Figma 3956:12602)章节横滑卡。
   *  后端 chapters 是数组,本页此前只取 [0](见 buildChapter)——卡片行要把多章都摆出来。
   *  拿不到数组就回落到归一后的那一章,不凭空造第二张卡。 */
  buildChapterCards(d, chapter) {
    const list = (d && Array.isArray(d.chapters) && d.chapters.length) ? d.chapters : null;
    if (!list) {
      return [{ key: 'c0', meta: chapter.idxLabel, title: chapter.name, sub: chapter.description,
        cover: chapter.cover, mileage: chapter.mileage, timeText: chapter.timeText,
        description: chapter.description, blocks: chapter.blocks,
        atmospherePreset: chapter.atmospherePreset, atmosphereClass: chapter.atmosphereClass }];
    }
    this._blocksParseSeen = this._blocksParseSeen || {};
    list.forEach((c) => reportBlocksParseFailure(analytics, this._blocksParseSeen,
      c && (c.chapterId != null ? c.chapterId : c.id), c && c.blocks));
    return list.map((c, i) => ({
      key: 'c' + i,
      opening: c.opening === true,
      id: c.chapterId != null ? c.chapterId : (c.id != null ? c.id : null),   // 后端键=chapterId(id 是我一开始猜错的)
      category: c.category || '',
      // 占位/空名时标题本身就是「第 N 章」,上面再挂一个同样的序数标签是同一件事说两遍。
      meta: (isChapterPlaceholderName(c.name) || !String(c.name || '').trim())
        ? '' : (c.idxLabel || ('第 ' + (i + 1) + ' 章')),
      title: displayChapterName(c.name, i, '第 ' + (i + 1) + ' 章'),
      sub: c.description || '',
      cover: c.imgArr ? String(c.imgArr).split(/[,;]/)[0] : (c.cover || ''),
      mileage: c.totalMileage ? (c.totalMileage + 'km') : '',
      timeText: c.totalTime ? Math.round(c.totalTime / 60) + ' 分钟' : '',
      description: c.description || '',
      blocks: Array.isArray(c.blocks) ? c.blocks : null,
      atmospherePreset: normalizeAtmosphere(c.atmospherePreset),
      atmosphereClass: atmosphereClass(c.atmospherePreset),
      nodeCount: 0,   // 回填于 loadData:按 chapterId 数节点
    }));
  },

  /** 背景封面:章节图优先,没有就退到第一个有图的商家。
   *  没有兜底时整屏是纯黑 —— 「主题封面 + 玻璃蒙版」这层就静默不存在了。 */
  _coverOf(chapterCover, nodes) {
    if (chapterCover) return chapterCover;
    const withImg = (nodes || []).find((n) => n && n.imgUrl);
    return withImg ? String(withImg.imgUrl).split(',')[0] : '';
  },

  buildChapter(d, nodes) {
    const c = (d.chapters && d.chapters[0]) || {};
    this._blocksParseSeen = this._blocksParseSeen || {};
    reportBlocksParseFailure(analytics, this._blocksParseSeen, c.id || c.chapterId, c.blocks);
    const placeholderName = isChapterPlaceholderName(c.name);
    return {
      id: c.id || c.chapterId || '',
      opening: c.opening === true,
      // 标题已经是「第 N 章」时,眉标不再重复同一个序数。
      // 序章的眉标就叫「序章」,与标题同名时顶栏会印成「序章 / 序章」。
      idxLabel: (placeholderName || (c.idxLabel && c.idxLabel === c.name)) ? '' : (c.idxLabel || '第一章'),
      // 2026-09-18 UI-20 返修:publish/simple、fabu 建主题时给章节写的默认名是英文占位「Chapter 1」,
      // 不再原样上屏(也不落回主题名把章节标题变成主题名),统一显示「第 N 章」。
      name: displayChapterName(c.name, 0, d.topicName || '城市探索'),
      description: c.description || '跟着脚步走完这几处坐标，把散落的线索串成一段属于你的城市故事。',
      cover: this._coverOf(c.imgArr ? String(c.imgArr).split(/[,;]/)[0] : (c.cover || ''), nodes),
      mileage: c.totalMileage ? (c.totalMileage + 'km') : '',
      timeText: c.totalTime ? Math.round(c.totalTime / 60) + ' 分钟' : '',
      atmospherePreset: normalizeAtmosphere(c.atmospherePreset),
      atmosphereClass: atmosphereClass(c.atmospherePreset),
      routeGeometry: c.routeGeometry || null,
      // 章节背景旁白:进本章自动播放(2026-09-04 拍板)。
      // ⚠️ buildChapter 是**白名单**映射 —— 后端下发了、这里不列,页面就永远读不到。
      //    E1 第一版就漏在这:playChapterAudio 读 this.data.chapter.audioUrl 恒为 undefined,
      //    整条自动播放是死链,而契约测试直接给 page.data.chapter 赋值、绕过了本函数,所以是假绿。
      audioUrl: c.audioUrl || '',
      // /api/play/nodes 一直在下发章节 blocks(文字/节点/图片/音频),此前本页没人读它。
      // 章节剧情屏要按作者编排的顺序铺开,所以块流必须带到这一层。
      blocks: Array.isArray(c.blocks) ? c.blocks : null
    };
  },

  rebuild() {
    const mode = this.data.mode;
    const nodes = this.data.nodes;
    const rawNextNode = this.computeNext(nodes, null); // 推荐算法不变(内部 pts 仅 mode1 用 sortId,mode2 用 GPS/doneAt,可传 null)
    const visibleNodes = nodes;
    // 陪伴态的目标就是 nav.targetNodeId(原来另存一份 fmTarget 专喂地图箭头,地图删了就没有第二份真值了)
    const activeTargetId = this.data.nav.active && this.data.nav.targetNodeId != null
      ? this.data.nav.targetNodeId
      : (rawNextNode && rawNextNode.nodeId);
    const pinState = (x) => {
      if (this._routeState.routeMode !== 'BRANCH_GRAPH') return getPlayRouteNodeState(x, mode, activeTargetId);
      if (x.done || x.routeNodeState === 'COMPLETED') return 'completed';
      if (activeTargetId != null && String(x.nodeId) === String(activeTargetId)) return 'target';
      return x.routeNodeState === 'PLAYABLE' ? 'actionable' : 'restricted';
    };
    // 自由探索不编号:没有解锁顺序,「第 3 站」这个数字读不出任何含义(2026-09-09 裁决)。
    // num 给空串,free-map 那边就不挂序号胶囊了。
    // 地图上的序号按「地点」数,故事玩法不占号(它们不在地图上)—— 否则两个地点会标成 3 和 8。
    const placeNum = {};
    visibleNodes.filter((x) => x.lat && x.lng).forEach((x, i) => { placeNum[String(x.nodeId)] = i + 1; });
    const fmNodes = visibleNodes.map((x) => ({
      nodeId: x.nodeId, num: mode === 2 ? '' : (placeNum[String(x.nodeId)] || x.num), state: pinState(x), lat: x.lat, lng: x.lng,
      imgUrl: String(x.imgUrl || '').split(',')[0],
    }));
    const routePathNodes = this._routeState.routeMode === 'BRANCH_GRAPH'
      ? buildVisibleRoutePath(visibleNodes, this._routeState)
      : nodes;
    const fmPolyline = buildPlayRoute(routePathNodes, mode === 2 ? 'free' : 'classic');
    const fmPlayer = this._loc ? { lat: this._loc.latitude, lng: this._loc.longitude } : null;
    const first = visibleNodes.find((n) => n.lat && n.lng) || nodes.find((n) => n.lat && n.lng) || {};
    const target = activeTargetId != null && this._nodeById(activeTargetId);
    const targetState = getPlayMapState(activeTargetId != null ? 'target' : 'candidate');
    /* computeNext 给的是精简卡片(没有坐标),「导航过去」要坐标:从整节点补上。
       以前 openNextStopNav 拿不到 lat/lng 一律静默 return —— 按钮一直在,点了什么都不发生(2026-10-27 生产实测)。 */
    const nextFull = rawNextNode && this._nodeById(rawNextNode.nodeId);
    const nextNode = rawNextNode && Object.assign({}, rawNextNode, {
      lat: nextFull ? nextFull.lat : rawNextNode.lat,
      lng: nextFull ? nextFull.lng : rawNextNode.lng,
      address: (nextFull && nextFull.address) || rawNextNode.address || '',
      mapStateLabel: targetState.cardLabel,
      mapA11yLabel: targetState.readerLabel,
    });
    let activeChapter = this.data.chapter;
    if (mode === 1 && rawNextNode && !this.data.isPreview
        && !(this._routeState.opening && !this._routeState.opening.complete)) {
      const activeNode = this._nodeById(rawNextNode.nodeId);
      const card = (this.data.chapterCards || []).find((item) => activeNode && item.id != null
        && String(item.id) === String(activeNode.chapterId));
      if (card) {
        activeChapter = {
          id: card.id,
          opening: card.opening === true,
          idxLabel: card.meta,
          name: card.title,
          description: card.description || card.sub || '',
          cover: card.cover || '',
          mileage: card.mileage || '',
          timeText: card.timeText || '',
          blocks: card.blocks || null,
          atmospherePreset: normalizeAtmosphere(card.atmospherePreset),
          atmosphereClass: atmosphereClass(card.atmospherePreset),
        };
      }
    }
    const fmCenter = (target && target.lat) ? { lat: target.lat, lng: target.lng }
      : (fmPlayer || { lat: first.lat || 31.2304, lng: first.lng || 121.4737 });
    const branchFinished = ['COMPLETED', 'FINISHED'].includes(this._routeState.status);
    const openingPending = this._routeState.opening && !this._routeState.opening.complete;
    const allDone = !openingPending && (this._routeState.routeMode === 'BRANCH_GRAPH'
      ? branchFinished
      : nodes.length > 0 && nodes.every((x) => x.done));
    const doneCount = nodes.filter((x) => x.done).length;
    // 凭证三态文案的唯一实现:剧本 tab 方卡与 mode2 六宫格共用 —— 别各写一份,会漂移。
    // 现场状态未知(crowded=null)给空串:WXML 里角标按「有内容才出」渲染。原来的「现场状态待同步」
    // 是占位不是信息,2026-09-05 用户拍板删掉。
    // 已核销**不挂标签**:卡片整张阴掉(.fx-tile.is-done 的 grayscale)已经说清楚了,
    // 再贴一枚「已核销」是同一件事说两遍(用户 2026-09-05 当面定)。
    const proofStatus = (x) => x.done ? '' : (x.crowded === null ? ''
      : (x.selfReported ? '照片已记录' : (x.arrived ? '已到店' : '未到店')));
    // mode2 六宫格瓦片:字段一律取后端有什么显示什么,拿不到就不显示、不编(与商家页 buildPlay 同口径)。
    // ★ 严格保持 nodes 原序 —— 展示顺序由后端决定(商家曝光轮转),前端不许自己 shuffle,
    //   否则商家看板的「谁排第一」对不上账。发牌动画只是把这个已定的顺序演出来。
    // 已核销环改成了等分间断条(见 _ringSegs / index.wxss),
    // 原来那圈点阵的 ringX/ringY 和它们用的 segStep 随之作废,已删。
    const chapById = {};
    (this.data.chapterCards || []).forEach((c) => { if (c.id != null) chapById[String(c.id)] = c; });
    // ⚠️ rebuild() 在发牌那 2.5s 内是可达的(定位成功回调 / 前往 / 结束前往 / 完成任务
    //    四条路径都会调),整表重建会把 fly/land 抹回 false ⇒ 正在飞的卡瞬间消失。
    //    按 nodeId 继承上一轮的动画态和已量好的几何,重建就不再打断动画。
    const prevTile = {};
    (this.data.seatTiles || []).forEach((t) => { prevTile[String(t.nodeId)] = t; });
    // 卡片排布:恒定两列、**卡片尺寸恒定**,张数多了往下滚。
    //   3 章 = 和 4 章一样的排法,右下角空一格;5/6 章卡片一样大,第三行滚下去看。
    // 不按剩余屏高等分:那样 6 章会把卡压扁、3 章又撑得过高,同一个主题换个章数就换一套观感。
    const seatTiles = mode !== 2 ? [] : nodes.map((x, i) => ({
      nodeId: x.nodeId,
      name: x.name || '未命名商家',
      chapterLabel: (() => {
        const c = chapById[String(x.chapterId)];
        return c ? [c.meta, c.title].filter(Boolean).join(' · ') : '';
      })(),
      imgUrl: String(x.imgUrl || '').split(',')[0],
      playName: x.gameTitle || '',
      locked: !!x.locked,
      // ⚠️ normNode() 产的是 businessTime/openStatus,**从来不产 openText** ——
      //    这里原来写 x.openText || '' 导致瓦片营业态永久不显示(2026-08-14 自审发现)。
      openText: resolveOpenState(x).openText,
      openDot: resolveOpenState(x).openDot,
      done: !!x.done,
      // ⚠️ 三态/拥挤字段必须在 seatTiles 上 —— fx-tile__st(index.wxml)读的是这里,
      //    d798cac9c 曾只加进剧本 tab 的那份副本,导致主屏三态与「排队中」提示恒空白。
      crowded: x.crowded,
      statusText: proofStatus(x),
      waitText: (x.crowded && Number(x.expectedWaitMinutes) > 0)
        ? ('排队约 ' + x.expectedWaitMinutes + ' 分钟，建议先去别家') : '',
      // 发牌节奏由 _dealTiles() 的定时器给(每张两段,一条 transition 画不出弧),
      // 不再走 transition-delay ⇒ 原来的 dealDelay 字段已无消费方,删掉。
      // 飞行几何(从卡包正中弹出 → 扇形弧顶 → 落格),由 _measureDeal() 量完回填。
      // 量不到时留空:WXSS 的 var() 兜底 0px 让卡片原地淡入(高度另有 268rpx 兜底)。
      ...(({ fly = false, land = false, tx0 = '', ty0 = '', tx1 = '', ty1 = '', rot = '' }) =>
        ({ fly, land, tx0, ty0, tx1, ty1, rot }))(prevTile[String(x.nodeId)] || {}),
    }));
    const ringSegs = this._ringSegs(doneCount, nodes.length);
    // 顶部输入地址只搜这一局里的地点(§6.1「只显示这一个主题里的地点」);
    // 针的颜色按玩法给 —— 城市定向蓝 / 自由探索绿。
    const searchPlaces = visibleNodes.filter((x) => x.lat && x.lng).map((x) => ({
      id: String(x.nodeId), name: x.name || '未命名地点', address: x.address || '',
      kind: mode === 2 ? 'free' : 'city', lat: x.lat, lng: x.lng, nodeId: x.nodeId,
    }));
    const storyDistText = nextNode ? this._distText(nextNode) : '';
    // 伏笔:服务端只下发已得到的念头,这里原样带到菜单上(见右上角「伏笔」那一行)
    const storyThoughts = (this._routeState && this._routeState.thoughts) || [];
    const chapterChanged = this.data.chapterFull && String(activeChapter.id) !== String(this.data.chapter.id);
    const merchantCount = new Set(visibleNodes.filter((node) => Number(node.merchantId) > 0)
      .map((node) => String(node.merchantId))).size;
    this.setData({ fmNodes, fmPolyline, fmPlayer, fmCenter, nextNode, storyDistText, storyThoughts, chapter: activeChapter, allDone, doneCount,
      remainingGameCount: nodes.filter((node) => node.hasTemplate && !node.done).length,
      total: visibleNodes.length, merchantCount, seatTiles, ringSegs, searchPlaces }, () => {
      if (chapterChanged) this.openChapterFull();
      wx.nextTick(() => this._measureDeal());   // 布局落定后才量得到瓦片/卡包的真实矩形
      // 探店日:核销完最后一家回到本页(onShow 重拉)时自动弹结算 —— 只认 allDone 上升沿,
      // 重进一个早已完成的主题不重放仪式。经典定向有自己的 completed→openFinish 链,不走这里。
      if (mode === 2 && !this.data.isPreview) {
        if (!allDone) { this._sawIncomplete = true; }
        else if (this._sawIncomplete && !this._finCelebrated) {
          this._finCelebrated = true;
          setTimeout(() => this.openFinish(), 400);
        }
      }
    });
    this.buildJournal();
  },

  _nodeById(id) { return this.data.nodes.find((n) => n.nodeId === id); },
  // 历史回看专用查找:只在手记/剧情全文里用,不并入可玩 nodes(不给任何操作入口)。
  _historyNodeById(id) {
    return (this._historyNodes || []).find((n) => String(n.nodeId) === String(id));
  },
  computeNext(nodes, pts) {
    pts = pts || [];
    const mode = this.data.mode;
    const undone = nodes.map((x, i) => ({ x, i })).filter((o) => !o.x.done && !o.x.paused);
    if (!undone.length) return null;
    let pick = undone[0], reason = '';
    if (mode === 1) {
      const next = resolveNextRouteNode(nodes, this._routeState);
      if (!next) return null;
      pick = { x: next, i: nodes.findIndex((node) => node && String(node.nodeId) === String(next.nodeId)) };
    } else {
      // P0 推荐条(纯前端加权):距离 × 营业时间窗 × 顺路;正常行走态始终只给第一推荐。
      const scored = this.scoreCandidates(nodes, pts, undone);
      pick = scored[0]; reason = pick.reason;
    }
    const x = pick.x, pt = pts[pick.i] || null;
    return {
      nodeId: x.nodeId, num: x.num, name: x.name, imgUrl: x.imgUrl,
      metaText: mode === 2 && reason ? reason
        : (x.address ? (x.address + (this.data.gpsOk ? '' : ' · 到现场打卡')) : '点开看看这一站'),
      _pt: pt
    };
  },

  // P0 推荐评分收口到 utils/play-recommend(已单测);此处只喂 GPS 与当前分钟数
  scoreCandidates(nodes, pts, undone) {
    const now = new Date();
    return recommend.scoreCandidates(nodes, pts, undone, this._loc, now.getHours() * 60 + now.getMinutes());
  },

  // 营业时间窗:解析 "HH:mm-HH:mm";解析不出返回 null(不加不减)。逻辑收口到 utils/geo(已单测),此处只喂当前分钟数。
  inBizWindow(bt) {
    const now = new Date();
    return geo.bizWindowState(bt, now.getHours() * 60 + now.getMinutes());
  },

  // ---------- 卡堆半屏(mode2 决策层):居中标题 + 圆角候选行 + 推荐状态 ----------
  // 距离展示文本(卡堆行大字用,如 "320m"/"1.2km");与 scoreCandidates 内部的中文 reason 文案互不影响、各自独立格式化
  _distText(node) {
    if (!node.lat || !node.lng) return '';
    let dm = null;
    if (this._loc) {
      dm = this.haversine(this._loc.latitude, this._loc.longitude, node.lat, node.lng);
    } else {
      const doneSorted = this.data.nodes.filter((n) => n.done).sort((a, b) => (b.doneAt || 0) - (a.doneAt || 0));
      const last = doneSorted[0];
      if (last && last.lat && last.lng) dm = this.haversine(last.lat, last.lng, node.lat, node.lng);
    }
    if (dm == null) return '';
    return dm >= 1000 ? (dm / 1000).toFixed(1) + 'km' : Math.round(dm) + 'm';
  },
  // 章节色:现有 buildJournal 里没有独立的按 chapterId 取色表(labelCol/bodyCol 走的是完成度沉降色阶),
  // 故卡堆左条统一按 chapterId 哈希一个稳定色;chapterId 缺失时同一套哈希兜底,保证同章节颜色一致。
  _chapterColor(chapterId) {
    return routeColorForKey(chapterId == null || chapterId === '' ? 'default' : chapterId);
  },
  /** 「看看别家」:完整地点列表就是卡包(seatTiles 覆盖全部 nodes),关掉半屏就回到它。
   *  2026-09-22 地点卡层删除后两个模式合成同一条路 —— 两个选点面并存只会让人问「这两个有什么区别」。 */
  openPoiFromSheet() {
    this.setData({ 'sheet.show': false, packOpen: true });
  },
  /** ③ 的门店核销码必须经过 merchant 页的单店授权写入+回读闸。 */
  openMerchantFromSheet() {
    const node = this.data.sheet && this.data.sheet.node;
    if (!node || node.nodeId == null) return;
    this.setData({ 'sheet.show': false }, () => {
      wx.navigateTo({ url: '/pages/play/merchant/index?nodeId=' + node.nodeId });
    });
  },
  // 结局层关闭(删卡堆时被误伤,门禁抓回):看完故事要能回到原页
  closeEnding() {
    this._endingEpoch = (this._endingEpoch || 0) + 1;
    this.setData({ 'ending.show': false });
  },
  // ---------- 定位 ----------
  // 仅由「开始游玩」或「前往此站」触发。进入页面时不预先请求，拒绝后仍可扫码完成。
  requestPlayLocation() {
    if (!this._isPlayAccountCurrent() || this.data.isPreview || this.data.isMock || this.data.gpsOk || this._locationRequesting) return;
    const that = this;
    // 与加载代际分开：锁屏或切后台不撤回已明确同意的游玩定位。
    const epoch = this._playLocationEpoch = (this._playLocationEpoch || 0) + 1;
    const sessionKey = this._sessionKey();
    const current = () => that._isPlayAccountCurrent() && epoch === that._playLocationEpoch
      && sessionKey === that._sessionKey();
    const unavailable = (hint) => {
      if (!current()) return;
      that._locationRequesting = false;
      that.setData({ gpsOk: false, gpsHint: hint });
    };
    this._locationRequesting = true;
    this.setData({ gpsHint: '正在请求定位…' });
    cyModal.show({
      title: '开启定位',
      content: '仅在你主动开始游玩后，持续获取位置，用于显示当前位置和自动判断到点。锁屏或切到后台仍会获取；暂停、结束本次或离开游玩页后停止。未开启时仍可扫码到达。',
      confirmText: '开启定位',
      cancelText: '先用扫码',
      success(result) {
        if (!current()) return;
        if (!result.confirm) { unavailable('定位未开启 · 可扫码完成到达'); return; }
        wx.getLocation({
          type: 'gcj02', isHighAccuracy: true,
          success(loc) {
            if (!current()) return;
            that._loc = { longitude: loc.longitude, latitude: loc.latitude, accuracy: loc.accuracy };
            that._locationRequesting = false;
            that.setData({ gpsOk: true, gpsHint: '定位已开启 · 靠近后会自动点亮' }, () => {
              if (!current()) return;
              that._noteLocAccuracy(loc.accuracy);
              that.rebuild();
              if (current()) that.watchLocation();
            });
          },
          fail() { unavailable('定位未开启 · 可扫码完成到达'); }
        });
      },
      fail() { unavailable('定位暂不可用 · 可扫码完成到达'); }
    });
  },
  /** 定位精度提示:只在「不足/恢复」翻转时 setData,不随每次定位回调刷新 */
  _noteLocAccuracy(accuracy) {
    const low = playAccuracy.isLowAccuracy(accuracy);
    if (low === !!this.data.gpsLowAcc) return;
    if (low) this.setData({ gpsLowAcc: true, gpsHint: playAccuracy.LOW_ACCURACY_HINT });
    else this.setData({ gpsLowAcc: false, gpsHint: '定位已开启 · 靠近后会自动点亮' });
  },
  watchLocation() {
    const that = this;
    if (!this._isPlayAccountCurrent()) { this._stopTracking(); return; }
    if (this._watching) return; // 同一持有者不重复注册监听。
    this._watching = true;
    const epoch = this._playLocationEpoch || 0;
    const sessionKey = this._sessionKey();
    const trackingKey = this._trackKey() + ':' + (++playLocationOwnerSequence);
    let released = false;
    const release = () => {
      if (released) return;
      released = true;
      if (that._releasePlayTracking === release) {
        that._releasePlayTracking = null;
        that._playTrackingKey = null;
        that._watching = false;
        if (that._playLocationHandler) {
          try { wx.offLocationChange(that._playLocationHandler); } catch (e) {}
          that._playLocationHandler = null;
        }
      }
      bgTracker.release(trackingKey);
    };
    this._playTrackingKey = trackingKey;
    this._releasePlayTracking = release;
    const current = () => that._releasePlayTracking === release && that._isPlayAccountCurrent()
      && epoch === (that._playLocationEpoch || 0) && sessionKey === that._sessionKey();
    if (this._playLocationHandler) {
      try { wx.offLocationChange(this._playLocationHandler); } catch (e) {}
      this._playLocationHandler = null;
    }
    // 后台定位:玩家锁屏塞兜里走路是本场景的常态(在架路线 2–4.5 小时、7.8–10.5 公里),
    // 前台版一锁屏就断,轨迹断段、走到点位也判不出到达。拿不到后台权限时自动降级前台。
    // ⚠️ 停的时机见 onUnload / _stopTracking —— **不能**在 onHide 停。
    bgTracker.acquire(trackingKey, {
      fail() {
        if (!current()) { release(); return; }
        release();
        that.setData({ gpsOk: false, gpsHint: '定位未开启，可扫码到达' });
      },
      success() {
        if (!current() || !that._watching) { release(); return; }
        that._playLocationHandler = (loc) => {
          if (!current() || !that._watching) { release(); return; }
          that._loc = { longitude: loc.longitude, latitude: loc.latitude, accuracy: loc.accuracy };
          that._noteLocAccuracy(loc.accuracy);
          // S2 陪伴态:仅 nav.active 时才把玩家位置回灌地图(箭头需要跟着走)+ 刷新剩余距离/途中气泡阈值
          // 性能:回灌地图会触发 free-map 全量 _rebuild(重画节点+circles+箭头),GPS 每 1-3s 一次太频繁,
          // 节流成"移动 ≥5 米才回灌";nav.remainM/气泡阈值仍每 tick 刷(便宜,不受影响)。
          if (that.data.nav.active || that.data.chapterFull || that.data.storyMap) {
            const moved = (that._lastNavPushLat == null) ? Infinity
              : that.haversine(that._lastNavPushLat, that._lastNavPushLng, that._loc.latitude, that._loc.longitude);
            if (moved >= 5) {
              that.setData({ fmPlayer: { lat: that._loc.latitude, lng: that._loc.longitude } });
              that._lastNavPushLat = that._loc.latitude;
              that._lastNavPushLng = that._loc.longitude;
            }
            if (that.data.nav.active) that._updateNav(that._loc);
          }
          // nav 陪伴态下自动到达也要盯 nav.targetNodeId(玩家可能在导航非推荐站的节点),
          // 非 nav 时(含 mode1)仍走原 nextNode 逻辑,行为不变。
          const navTargetNode = that.data.nav.active ? that._nodeById(that.data.nav.targetNodeId) : null;
          const nx = that.data.nextNode;
          const node = navTargetNode || (nx && that.data.nodes.find((n) => n.nodeId === nx.nodeId));
          if (node && node.lng && node.lat) {
            const d = that.haversine(that._loc.latitude, that._loc.longitude, node.lat, node.lng);
            // 仅"到点即完成(vm0)/GPS到达(vm5)"节点自动到达;答题/拍照/选项/扫码(1/2/3/4)须玩家手动完成,
            // 否则后端 arrive 闸会拒绝并在每次定位回调刷 toast。
            // 精度 > 80m 后端必拒(定位精度不足),不发,免得每次定位回调都弹一次拒绝
            /* 8/9/10 是「玩法过了再到达收尾」(与 onAdvancedReady 同一白名单):玩法已通过就和 0/5 一样靠近即点亮,
               否则做完玩法人就站在门口,卡片写着「靠近后会自动点亮」却永远不亮(2026-10-27 生产实测)。 */
            const arrivesHere = node.vm === 0 || node.vm === 5
              || ([8, 9, 10].includes(node.vm) && that._advancedReadyNodeId === node.nodeId);
            if (d <= 50 && !node.done && arrivesHere && playAccuracy.canAutoArrive(loc.accuracy)) that.arrive(node.nodeId, 'gps');
          }
        };
        wx.onLocationChange(that._playLocationHandler);
      }
    });
  },
  // ---------- P1 俱乐部带领(轮询 + 队长控) ----------
  loadTeamProgress() {
    const that = this;
    const progressContext = this._leadOperationContext();
    const options = arguments[0];
    // 返回 promise:true=本次成功(或被更新的响应顶替,不算失败),false=失败 —— 轮询器据此退避
    if (this._playDisposed || !this._isPlayAccountCurrent() || this._themeHidden || !this.data.activityId || this.data.isPreview || this.data.isMock) return Promise.resolve(true);
    const memberId = app.getUserID && app.getUserID();
    const activityId = this.data.activityId;
    const lifecycleEpoch = this._leadLifecycleEpoch || 0;
    const requestEpoch = (this._leadProgressEpoch || 0) + 1;
    this._leadProgressEpoch = requestEpoch;
    const explicitAuditToken = options && options.unlockAuditToken;
    const unlockAuditToken = explicitAuditToken != null
      ? explicitAuditToken
      : (this._leadUnlockChecking ? this._leadUnlockAuditToken : null);
    const claimResponse = () => {
      if (that._playDisposed || that._themeHidden || memberId !== (app.getUserID && app.getUserID()) || activityId !== that.data.activityId) return false;
      // 只拒绝比「已落地」结果更旧的响应，不能以「最新已发起」为准。
      // 否则当 GET 持续慢于 4s 轮询间隔时，每个响应都会被下一请求提前作废而永不刷新。
      if (!that._isLeadOperationContextCurrent(progressContext, false) || that._themeHidden || lifecycleEpoch !== (that._leadLifecycleEpoch || 0)) return false;
      if (requestEpoch < (that._leadProgressAppliedEpoch || 0)) return false;
      that._leadProgressAppliedEpoch = requestEpoch;
      return true;
    };
    const markLeadLoadFailure = () => {
      if (claimResponse()) that.setData({ leadLoadError: true });
      return false;
    };
    // F-W1-10:4 秒轮询的读取失败只落到 leadLoadError,不再每轮弹后端 toast(如换号后的「无权查看本场队伍进度」)。
    return req('/api/club/lead/team-progress', 'GET', { activityId: this.data.activityId }, undefined, true).then((res) => {
      // 轮询、手动重试和放行后对账可能并发；旧响应不得覆盖较新的章节真相。
      if (!claimResponse()) return true;
      if (!(res.code == 200 || res.code == '200') || !res.data) {
        that.setData({ leadLoadError: true });
        return false;
      }
      const d = res.data;
      const auditIntent = that._leadUnlockIntent;
      const unlockAuditDone = unlockAuditToken != null && unlockAuditToken === that._leadUnlockAuditToken
        && auditIntent && auditIntent.auditToken === unlockAuditToken
        && that._isLeadOperationContextCurrent(auditIntent.context, false);
      if (unlockAuditDone && that._leadUnlockIntents[auditIntent.key] === auditIntent) {
        delete that._leadUnlockIntents[auditIntent.key];
        that._leadUnlockIntent = null;
      }
      if (!d.exists) {
        if (unlockAuditDone) that._leadUnlockChecking = false;
        that.setData({
          'lead.exists': false,
          leadLoadError: false,
          leadUnlockChecking: unlockAuditDone ? false : !!that._leadUnlockChecking,
        });
        that._stopLeadPoll();
        return true;
      }
      // 广播变化时轻提示(一次)
      if (d.broadcast && d.broadcast !== that._lastBroadcast) {
        that._lastBroadcast = d.broadcast;
        that.diegetic('队长:' + d.broadcast);
      }
      const openedWhileWaiting = Number(d.status) === 2
        && Number(that.data.lead.status) !== 2 && that._playable === false;
      that.setData({
        lead: {
          exists: true, status: d.status || 1, isLeader: !!d.isLeader,
          arrived: d.arrived || 0, total: d.total || 0, meArrived: !!d.meArrived,
          chapterName: d.chapterName || '', currentChapterId: d.currentChapterId, nextChapterId: d.nextChapterId, broadcast: d.broadcast || '',
          members: (d.members || []).slice(0, 8)
        },
        leadLoadError: false,
        leadUnlockChecking: unlockAuditDone ? false : !!that._leadUnlockChecking,
      });
      if (unlockAuditDone) that._leadUnlockChecking = false;
      if (openedWhileWaiting) that.loadData(false);
      that._startLeadPoll();
      return true;
    }).catch(markLeadLoadFailure);
  },
  // 带领场进度轮询:setTimeout 链(./utils/poller.js),上一发没回来不叠发;连续失败 4s→8s→10s 退避,成功复位。
  _startLeadPoll() {
    if (this._playDisposed || !this._isPlayAccountCurrent() || this._themeHidden) return;
    if (!this._leadPoller) {
      this._leadPoller = createPoller({ run: () => this.loadTeamProgress(), interval: 4000, maxInterval: 10000 });
    }
    this._leadPoller.start();
  },
  _stopLeadPoll() {
    this._leadLifecycleEpoch = (this._leadLifecycleEpoch || 0) + 1;
    if (this._leadPoller) this._leadPoller.stop();
  },
  retryLeadProgress() {
    this.setData({ leadLoadError: false }, () => this.loadTeamProgress());
  },
  leadArrive() {
    const that = this;
    if (!this._isPlayAccountCurrent() || this._themeHidden || this._leadArriving || this.data.isPreview || this.data.isMock) return;
    const activityId = this.data.activityId;
    const memberId = app.getUserID && app.getUserID();
    const authorization = app.getAuthorization && app.getAuthorization();
    if (!(Number(activityId) > 0) || !(Number(memberId) > 0) || !authorization || authorization === app.DEV_TOKEN) {
      this.diegetic('请先登录并进入本场'); return;
    }
    const epoch = (this._leadArrivalEpoch || 0) + 1;
    this._leadArrivalEpoch = epoch; this._leadArriving = true;
    const lifecycleEpoch = this._leadLifecycleEpoch || 0;   // 带领轮询生命周期换代(停轮询)后旧签到不再回写
    const live = () => !that._playDisposed && !that._themeHidden && that._leadArrivalEpoch === epoch
      && lifecycleEpoch === (that._leadLifecycleEpoch || 0)
      && activityId === that.data.activityId && memberId === (app.getUserID && app.getUserID())
      && authorization === (app.getAuthorization && app.getAuthorization());
    const finish = (message) => {
      if (that._leadArrivalEpoch === epoch) that._leadArriving = false;
      if (message && live()) that.diegetic(message);
    };
    cyModal.show({
      title: '集合签到定位',
      content: '仅本次签到获取当前位置，用于确认你是否在集合点500米内，不开启持续或后台定位。集合前60分钟起可签到。',
      confirmText: '定位并签到', cancelText: '取消',
      success(result) {
        if (!live()) { finish(); return; }
        if (!result.confirm) { finish(); return; }
        if (typeof wx.getLocation !== 'function') { finish('定位暂不可用'); return; }
        wx.getLocation({
          type: 'gcj02', isHighAccuracy: true,
          success(loc) {
            if (!live()) { finish(); return; }
            if (!loc || !Number.isFinite(loc.latitude) || !Number.isFinite(loc.longitude)
              || Math.abs(loc.latitude) > 90 || Math.abs(loc.longitude) > 180) {
              finish('当前位置无效，请重新定位'); return;
            }
            req('/api/club/lead/arrive', 'POST', { activityId, latitude: loc.latitude, longitude: loc.longitude }).then((r) => {
              if (!live()) { finish(); return; }
              const ok = r.code == 200;
              finish(r.msg || (ok ? '已到集合点' : '签到未完成，请重试'));
              if (ok) that.loadTeamProgress();
            });
          },
          fail() { finish('定位未开启，未提交签到'); }
        });
      },
      fail() { finish('签到确认未完成'); }
    });
  },
  leadStart() {
    if (!this._isPlayAccountCurrent() || !this.data.activityId) return;
    const that = this;
    const context = this._leadOperationContext();
    const lifecycleEpoch = this._leadLifecycleEpoch || 0;
    const sequence = this._leadStartSequence = (this._leadStartSequence || 0) + 1;
    req('/api/club/lead/start', 'POST', { activityId: context.activityId }).then((r) => {
      if (!that._isLeadOperationContextCurrent(context, false)
          || lifecycleEpoch !== (that._leadLifecycleEpoch || 0) || sequence !== that._leadStartSequence) return;
      that.diegetic(r.msg || '出发!');
      that.loadTeamProgress();
    });
  },
  _canUseLeadTools() {
    const lead = this.data.lead;
    return !!(lead && lead.exists && lead.isLeader);
  },
  _leadOperationContext() {
    return { owner: this._boundPlayOwner(), activityId: this.data.activityId,
      sessionKey: this._sessionKey() };
  },
  _isLeadOperationContextCurrent(context, requireLeader) {
    return !!context && this._isPlayAccountCurrent() && context.owner === this._playOwner
      && context.activityId === this.data.activityId && context.sessionKey === this._sessionKey()
      && (!requireLeader || this._canUseLeadTools());
  },
  // 规则 1：带队主题的玩家票必须从当前 activity 入口核销。不能复用商家首页的通用扫码，
  // 否则请求没有场次身份，后端会 fail closed，结算也无法区分同主题的不同团。
  leadVerifyMemberTicket() {
    const that = this;
    if (!this._isPlayAccountCurrent() || !this._canUseLeadTools() || !this.data.activityId) return;
    const context = this._leadOperationContext();
    const seatLogin = captureLogin(app, wx);
    const sequence = this._leadTicketScanSequence = (this._leadTicketScanSequence || 0) + 1;
    const isCurrent = () => that._isLeadOperationContextCurrent(context, true)
      && sequence === that._leadTicketScanSequence;
    let scanSettled = false, loadingShown = false;
    const finishLoading = () => { if (loadingShown) { loadingShown = false; cyLoading.hide(); } };
    wx.scanCode({
      onlyFromCamera: true,
      success(scan) {
        if (!isCurrent() || scanSettled) return;
        scanSettled = true;
        const ticket = resolveLeadMemberTicket(scan.result);
        if (ticket.error) {
          that.diegetic(ticket.error);
          return;
        }
        const request = { url: '/api/registration/scan_group_member_ticket', method: 'POST',
          data: { code: ticket.code, activityId: context.activityId } };
        const target = seatTarget(request);
        if (target) {
          const seatCurrent = () => isCurrent() && loginCurrent(app, wx, seatLogin);
          if (!seatCurrent()) return;
          if (!that._leadVerificationWorkflow) that._leadVerificationWorkflow = createVerificationWorkflow({
            deadlineMs: 15000, storage: wx, getOwner: () => app.getUserID && app.getUserID() });
          const key = seatKey(context.owner, target);
          that._leadVerificationWorkflow.run(key, done => app.sendRequest(Object.assign({}, request, {
            hideLoading: true, silentError: true, autoErrorToast: false,
            success: r => done(classifyVerificationOutcome(r, 'success', undefined, request)),
            fail: (r, status) => done(classifyVerificationOutcome(r, 'fail', status, request)),
            successStatusAbnormal: (r, status) => done(classifyVerificationOutcome(r, 'abnormal', status, request))
          })), result => {
            if (!seatCurrent()) return;
            if (result.status === 'success') motion.haptic({ type: 'medium', reducedMotion: that.data.reducedMotion });
            that.diegetic(result.status === 'unknown'
              ? '原席位结果待确认；再次扫描同一席位只查询结果，请勿改选席位重发'
              : result.response && result.response.msg || '核销未提交');
          }, seatRecovery(app, target, seatCurrent));
          return;
        }
        loadingShown = true; cyLoading.show('正在核销');
        req('/api/registration/scan_group_member_ticket', 'POST', {
          code: ticket.code, activityId: context.activityId
        }).then((r) => {
          finishLoading();
          if (!isCurrent()) return;
          const verified = r.code == 200 || r.code == '200';
          if (verified) motion.haptic({ type: 'medium', reducedMotion: that.data.reducedMotion });
          that.diegetic(r.msg || (verified ? '核销成功' : '核销失败'));
        }).catch(() => { finishLoading(); if (isCurrent()) that.diegetic('核销失败'); });
      },
      fail(error) {
        if (!isCurrent() || scanSettled) return;
        scanSettled = true;
        that.diegetic(isScanCancelled(error) ? '已取消扫码' : '扫码未完成，请重试');
      }
    });
  },
  leadShowGroupCode() {
    if (!this._canUseLeadTools() || !this.data.activityId) return;
    this.openScene('qr-group-code', { activityId: this.data.activityId, name: '本场次' });
  },
  leadUnlock() {
    if (!this._isPlayAccountCurrent() || !this._canUseLeadTools() || !this.data.activityId) return;
    if (this._leadUnlockIntent && this._leadUnlockIntent.inFlight
      && this._isLeadOperationContextCurrent(this._leadUnlockIntent.context, true)) return;
    const context = this._leadOperationContext();
    const store = scopedRouteActionStore([context.owner, context.sessionKey, 'lead-chapter'], wx);
    const lead = this.data.lead || {};
    const original = { activityId: context.activityId, expectedChapterId: lead.currentChapterId == null ? 0 : lead.currentChapterId,
      targetChapterId: lead.nextChapterId };
    let body;
    try { body = store.payload('unlock', context.activityId, {}, original); }
    catch (error) { this.diegetic(error.message); return; }
    const intent = { context, body };
    this._leadUnlockIntent = intent;
    const current = () => this._leadUnlockIntent === intent && this._isLeadOperationContextCurrent(context, true);
    const submit = () => {
      if (!current()) return;
      intent.inFlight = true;
      this._leadUnlockInFlight = true;
      this.setData({ leadUnlockChecking: true });
      const input = Object.assign({}, body, { commandId: body.routeActionId });
      const request = body.receiptOnly
        ? req('/api/club/lead/unlock-chapter-receipt', 'GET', { activityId: body.activityId, commandId: body.routeActionId }, undefined, true)
        : req('/api/club/lead/unlock-chapter', 'POST', input);
      request.then(response => {
        const validReceipt = Number(response.code) === 200 && response.data
          && response.data.commandId === body.routeActionId
          && Number(response.data.expectedChapterId) === Number(body.expectedChapterId)
          && Number(response.data.targetChapterId) === Number(body.targetChapterId);
        const unknown = response.netFail || response.httpFail || Number(response.code) === 520 || Number(response.code) === 200 && !validReceipt;
        // 原请求的未知事实属于原账号/场次，即使页面已换场也必须保留；不写当前页状态。
        if (unknown && typeof body._markIntentUnknown === 'function') body._markIntentUnknown();
        if (!current()) return;
        if (unknown) {
          // 队伍当前章发生变化不能证明“本命令”执行过；只保留原身份查回执。
          body.receiptOnly = true;
          this._leadUnlockUnknown = true;
          if (typeof body._markIntentUnknown === 'function') body._markIntentUnknown();
          this.diegetic('章节放行结果待确认，再点放行只查询原命令');
          return;
        }
        this._leadUnlockUnknown = false;
        if (response.intentNotExecuted) {
          cyModal.show({ title: '原操作未执行', content: '期望章节已变化。确认结束原意图，再检查当前章节后操作？', confirmText: '确认',
            success: result => { if (result.confirm && current()) store.resolve('unlock', context.activityId); } });
        } else if (Number(response.code) === 200 || Number(response.code) !== 409 && !body.receiptOnly) store.resolve('unlock', context.activityId);
        this.diegetic(response.msg || '请核对本场章节');
        this.loadTeamProgress();
      }).catch(() => { if (current()) this.diegetic('章节放行结果待确认，再点放行只查询原命令'); })
        .finally(() => {
          intent.inFlight = false;
          if (this._leadUnlockIntent !== intent) return;
          this._leadUnlockInFlight = false;
          if (current()) this.setData({ leadUnlockChecking: !!this._leadUnlockUnknown });
        });
    };
    if (body.receiptOnly) { submit(); return; }
    if (!body.targetChapterId) { store.resolve('unlock', context.activityId); this.diegetic('本场没有可放行的下一章'); return; }
    cyModal.show({ title: '解锁下一章节', content: '确认放行选定的下一章节？', confirmText: '确认解锁',
      success: result => { if (!current()) return; if (result.confirm) submit(); else store.resolve('unlock', context.activityId); } });
  },
  leadBroadcast() {
    if (!this._isPlayAccountCurrent()) return;
    const context = this._leadOperationContext();
    const intent = {};
    this._leadBroadcastIntent = intent;
    const current = () => this._leadBroadcastIntent === intent && this._isLeadOperationContextCurrent(context);
    let settled = false;
    cyModal.show({
      title: '队长广播',
      editable: true,
      placeholderText: '对全队说一句…',
      success: (res) => {
        if (settled || !current()) return;
        settled = true;
        if (res.confirm && res.content) {
          req('/api/club/lead/broadcast', 'POST', { activityId: context.activityId, text: res.content })
            .then((r) => {
              if (!current()) return;
              this.diegetic(r.msg || '已广播'); this.loadTeamProgress();
            });
        }
      }
    });
  },

  // D-9 队长全团结算:一次核销=全团入结算(到场∩已付)。资金动作,二次确认。
  leadSettle() {
    if (!this._isPlayAccountCurrent() || !this._canUseLeadTools() || !this.data.activityId) return;
    const context = this._leadOperationContext();
    if (this._leadSettleIntent && this._isLeadOperationContextCurrent(this._leadSettleIntent.context)) return;
    const intent = { context };
    this._leadSettleIntent = intent;
    const current = () => this._leadSettleIntent === intent && this._isLeadOperationContextCurrent(context, true);
    const release = () => { if (this._leadSettleIntent === intent) this._leadSettleIntent = null; };
    let settled = false, loading = false;
    const hideLoading = () => { if (loading) { loading = false; cyLoading.hide(); } };
    cyModal.show({
      title: '全团结算',
      content: '将为到场已购票的全体队员核销并入结算，确认结算?',
      confirmText: '结算全团',
      success: (res) => {
        if (settled) return;
        settled = true;
        if (!current()) { release(); return; }
        if (!res.confirm) { release(); this.diegetic('已取消结算'); return; }
        cyLoading.show('正在结算'); loading = true;
        req('/api/club/lead/settle', 'POST', { activityId: context.activityId }).then((r) => {
          hideLoading();
          const owned = current(); release();
          if (!owned) return;
          const ok = r.code == 200 || r.code == '200';
          this.diegetic(r.msg || (ok ? '全团结算完成' : '整团结算失败，请重试'));
          if (ok) this.loadTeamProgress();
        }).catch(() => {
          hideLoading(); const owned = current(); release();
          if (owned) this.diegetic('整团结算失败，请重试');
        });
      },
      fail: () => {
        if (settled) return;
        settled = true; const owned = current(); release();
        if (owned) this.diegetic('结算确认未打开，请重试');
      }
    });
  },

  // ---------- P0 途中彩蛋 ----------
  // GPS 回调里判定:进 radius 且未读 且频控(≥180s/条)→ 弹气泡

  // ---------- S2 前往中陪伴态(FF-32:方向箭头 + 底部陪伴条,mode2 专属) ----------
  // 原来这里还有一个 onGoTap,和下面的 onSheetGoTap 逐行同义。它注释里写的入口(底部推荐条
  // 主 CTA)早已不存在,真实的唯一绑定是工具抽屉里一行标着「到点提醒」的行 —— 标签和动作
  // 对不上。前往 / 我到了只留节点半屏这一个入口(9-09 裁决:点不开或点错的功能一律收口)。
  // 入口:节点详情卡「⊡ 我已到达此站」(无模板节点)。仅 mode2 且有坐标才截胡进 nav。
  _isStoryGame(node) {
    return !!node && !node.lat && !node.lng && node.vm !== 4 && node.vm !== 5
      && ((this.data.chapter && this.data.chapter.blocks) || []).some((block) =>
        block.type === 'node' && block.locationRequired === false
        && String(block.nodeId) === String(node.nodeId));
  },
  startStoryGame(e) {
    const id = e.currentTarget.dataset.nodeid;
    const node = (this.data.nodes || []).find((n) => String(n.nodeId) === String(id));
    const entry = (this.data.chapterParas || []).find((p) => p.kind === 'game' && String(p.nodeId) === String(id));
    if (!entry || !this._isStoryGame(node) || node.done) return;
    // 存档:记下正开着的玩法,中途退出重进时直接回到它;首个会话视图若显示早已做完(退出前没点继续),直接接着走
    storyResume.patch(this._runSessionScope(), { openNodeId: String(node.nodeId) });
    this._freshStoryGame = String(node.nodeId);
    this._storyOpenNode = true;
    this.startGame(node);
  },
  onSheetGoTap() {
    const node = this.data.sheet.node;
    if (this.data.isPreview && node) { this.arrive(node.nodeId, 'preview'); return; }
    const navHere = this.data.nav.active && node && this.data.nav.targetNodeId === node.nodeId;
    if (this.data.mode === 2 && node && !node.done && node.lat && node.lng && !navHere) { this.startNav(node); return; }
    this.onArriveTap();
  },
  // 进入陪伴态:记录目标 + 路程基准(totalM=当前直线距离),隐藏 peek/卡堆。
  // 到达判定仍由既有 watchLocation(vm0/5 自动)/手动「我到了」/扫码(陪伴条上的入口,调既有 onArriveTap)负责,这里只管展示。
  startNav(node) {
    // 原来这里挡了 mode1,但设计稿 3963:15608(城市定向)同样有导航态 ⇒ 只挡缺坐标
    if (!node || !node.lat || !node.lng) return;
    const loc = this._loc;
    const totalM = loc ? this.haversine(loc.latitude, loc.longitude, node.lat, node.lng) : 0;
    this._lastNavPushLat = null; this._lastNavPushLng = null; // 重置节流基准,进入陪伴态首个 tick 必回灌一次
    this.setData({
      nav: { active: true, targetNodeId: node.nodeId, targetName: node.name || '', remainM: Math.round(totalM), totalM: totalM || 1, hit50: false, hit90: false },
      'sheet.show': false, 'stack.show': false
    }, () => {
      this.rebuild();
      this._syncNavMeta();
      this.requestPlayLocation();
    });
  },
  // 退出陪伴态:到达(onComplete 里加一行调用)或用户点「结束前往」X。nav 本就未激活时是安全 no-op。
  stopNav() {
    if (this._navBubbleTimer) { clearTimeout(this._navBubbleTimer); this._navBubbleTimer = null; }
    this._lastNavPushLat = null; this._lastNavPushLng = null; // 重置节流基准
    this.setData({
      nav: { active: false, targetNodeId: null, targetName: '', remainM: 0, totalM: 0, hit50: false, hit90: false },
      navBubble: { show: false, text: '' }
    }, () => this.rebuild());
  },
  // GPS 回调里驱动:刷新剩余距离,过 50%/90% 路程阈值各触发一次途中 NPC 气泡(S3 companionLine 端点)
  _updateNav(loc) {
    const node = this._nodeById(this.data.nav.targetNodeId);
    if (!node || !node.lat || !node.lng) return;
    const remainM = this.haversine(loc.latitude, loc.longitude, node.lat, node.lng);
    this.setData({ 'nav.remainM': Math.round(remainM) }, () => this._syncNavMeta());
    const totalM = this.data.nav.totalM || 1;
    const ratio = 1 - remainM / totalM;
    // hit90 优先判、互斥 else if:稀疏 GPS 一跳跨过 50%/90% 两个阈值时只触发一次气泡,
    // 避免两次 companionLine 请求共用一个 _navBubbleTimer、后一次悄悄覆盖前一次。正常步行仍分两个 tick 各触发一次。
    if (ratio >= 0.9 && !this.data.nav.hit90) { this.setData({ 'nav.hit90': true }); this._fetchCompanionLine(); }
    else if (ratio >= 0.5 && !this.data.nav.hit50) { this.setData({ 'nav.hit50': true }); this._fetchCompanionLine(); }
  },
  _fetchCompanionLine() {
    if (!this._isPlayAccountCurrent() || this.data.isPreview || this.data.isMock) return;
    const that = this;
    const nav = this.data.nav;
    if (!nav || !nav.active) return;
    const targetNodeId = nav.targetNodeId;
    const params = this._sessionParams();
    const sessionKey = this._sessionKey(params);
    const sequence = this._companionReadSequence = (this._companionReadSequence || 0) + 1;
    req('/api/play/companionLine', 'GET', params).then((r) => {
      if (!that._isPlayAccountCurrent() || sequence !== that._companionReadSequence
          || sessionKey !== that._sessionKey() || that.data.nav !== nav
          || !nav.active || nav.targetNodeId !== targetNodeId) return;
      if (!(r.code == 200 || r.code == '200') || !r.data || !r.data.line) return;
      that.showNavBubble(r.data.line);
    });
  },
  showNavBubble(text) {
    this.setData({ navBubble: { show: true, text } });
    if (this._navBubbleTimer) clearTimeout(this._navBubbleTimer);
    this._navBubbleTimer = setTimeout(() => this.setData({ 'navBubble.show': false }), 3500);
  },

  haversine(la1, lo1, la2, lo2) { return geo.haversine(la1, lo1, la2, lo2); }, // 收口到 utils/geo(已单测)

  // ---------- 模式 ----------
  setMode(e) {
    const m = Number(e.currentTarget.dataset.m);
    if (m === this.data.mode) return;
    // 模式由后端按票种锁定(serverMode):城市定向(1)顺序探索、自由探索(2)全开。客户端不可切换,
    // 否则把锁定关卡显示成可点、逐个被后端"请先完成上一站"打回(误导)。
    cyToast(this.data.serverMode == 2 ? '本场为自由探索，可任意先后' : '本场为城市定向，需按顺序探索');
  },

  // ---------- 节点详情卡 ----------
  openSheet(node) {
    // 升级4:节点级前置解锁的**唯一收口**。onSeatTap 之外,还有 goToNode(手记行)也经这里
    // 进详情卡 —— 逐个入口加守卫必漏一个,
    // 所以闸放在这一层。(服务端四个完成入口另有硬闸,这里是 UX 层:先说清「还差什么」。)
    if (!node.done && this._routeInteractionBlocked()) { cyToast(this.data.routeNotice || '路线状态同步中'); return; }
    if (this._lockedTip(node)) return;
    const biz = node.businessTime;
    // S4 营业态换算已抽到 utils/play-open-state.js:大卡、六宫格瓦片、商家权益页共用一份,
    // 免得各自抄一份再各自漂。规则未变(openStatus 优先 → businessTime 兜底 → 都无则空)。
    const { openText, openCol, openDot } = resolveOpenState(node);
    const sheetNode = Object.assign({}, node, {
      metaLine: (node.address || '此处') + (biz ? (' · ' + biz) : ''),
      bizText: biz, openText, openCol, openDot,
      hasGame: node.hasTemplate,
      gameLede: node.story ? String(node.story).slice(0, 24) : '到达后开启本站任务',
      cardHook: node.cardHook || ''
    });
    this.setData({
      'sheet.show': true, sheetOut: false, 'sheet.node': sheetNode,
      'sheet.continueText': this.data.allDone ? '查看城市足迹卡' : '前往下一站'
    });
    // M6:开卡时相机平移,让选中节点落到上 1/3 视野(半屏大卡遮住下半屏时仍可见);无坐标节点优雅跳过
    if (node && node.lat && node.lng) {
      const fm = this.selectComponent('#fmMap');
      if (fm && typeof fm.focusNode === 'function') fm.focusNode(node.lat, node.lng);
    }
    // R14 旅程检定入口(契约:allowedActions)。到店前服务端不会给 'check',这里就什么都不弹。
    this._probeJourneyCheck(node);
  },
  // P0 导航逃生门:微信内置地图看真实街道(写法同 topic/index goLocation)
  sheetNav() {
    const n = this.data.sheet && this.data.sheet.node;
    if (!n || !n.lat || !n.lng) return;
    wx.openLocation({
      latitude: parseFloat(n.lat), longitude: parseFloat(n.lng),
      name: n.name || '目的地', address: n.address || '', scale: 18
    });
  },
  // 线索图片：全屏预览同一段的全部图片
  previewThemeClueImage(e) {
    const urls = e.currentTarget.dataset.urls || [];
    if (urls.length) wx.previewImage({ current: e.currentTarget.dataset.url || urls[0], urls: urls });
  },
  // 节点封面点击:多图时全屏预览(单图/无图不弹)
  previewNodeImages(e) {
    const raw = e.currentTarget.dataset.imgs;
    const urls = raw ? String(raw).split(',').filter(Boolean) : [];
    if (urls.length < 2) return;
    wx.previewImage({ current: urls[0], urls: urls });
  },
  closeSheet() {
    // M9 埋点:节点卡打开后未完成即离开 → node_abandon(可玩性 Lint 原料)
    const n = this.data.sheet && this.data.sheet.node;
    if (n && !n.done && !this.data.isPreview && !this.data.isMock) {
      analytics.track('node_abandon', { bizType: 'topic', bizId: this.data.topicId, properties: { nodeId: n.nodeId, via: 'sheet' } });
    }
    this._outThen('sheet');
  },
  pickPreviewEnding(e) {
    const idx = Number(e.currentTarget.dataset.idx) || 0;
    const ending = this.data.previewEnding;
    const picked = (ending.list || [])[idx];
    // 试玩里点哪个结局就读哪一章(结局章);老数据没有章节就只换卡上的标题
    if (picked && picked.chapter) {
      const card = Object.assign({}, ending, { idx });
      this.setData({ 'previewEnding.show': false }, () => this._playEndingChapter(picked.chapter, () => this._showEnding(card)));
      return;
    }
    this.setData({ 'previewEnding.idx': idx });
  },
  previewGoBack() {
    wx.navigateBack({
      delta: 1,
      fail: () => wx.switchTab({ url: '/pages/template/index' })
    });
  },
  _completePreviewNode(nodeId) {
    const nodes = this.data.nodes.map(node => String(node.nodeId) === String(nodeId) && !node.done
      ? Object.assign({}, node, { done: true, doneAt: Date.now(),
        story: [node.previewKit && node.previewKit.result && node.previewKit.result.text, node.story].filter(Boolean).join('\n') }) : node);
    this._routeNodeCatalog = nodes;
    this._advancedReadyNodeId = null;
    this.setData({ nodes, screen: '', advancedPlay: { show: false, node: null },
      playKit: { show: false, kit: null }, 'sheet.show': false }, () => {
      this.rebuild();
      this.openChapterFull();
    });
  },
  advancePreview() {
    if (!this.data.isPreview) return;
    const node = this.data.nodes.find(item => !item.done && String(item.chapterId) === String(this.data.chapter.id));
    if (node) { this._completePreviewNode(node.nodeId); return; }
    const index = this._previewChapterIndex + 1;
    const chapter = (this._previewChapters || [])[index];
    if (!chapter) {
      // W-15:最后一章之后给作者看结局(默认兜底那条,可切换看每一个);没配结局才回编辑器
      const list = this._previewEndings || [];
      if (!list.length || this.data.previewEnding.show) { this.previewGoBack(); return; }
      const fallback = list.findIndex(e => e.fallback);
      const idx = fallback < 0 ? 0 : fallback;
      const archive = lifeArchive.fromPreview(this.data.nodes);
      const card = () => this._showEnding({ show: true, list, idx, archive, hasArchive: !lifeArchive.isEmpty(archive) });
      // 结局章(2026-09-24):先像玩家那样把兜底那章当故事流读完,再出结局卡(卡上切换看每一章)
      if (list[idx].chapter) { this._playEndingChapter(list[idx].chapter, card); return; }
      card();
      return;
    }
    this._previewChapterIndex = index;
    this.stopAudio();
    this.setData({ chapter: this.buildChapter({ chapters: [chapter] }, this.data.nodes), chapterGameAnchor: '' }, () => {
      this.openChapterFull();
    });
  },
  // 预览态:从节点详情卡跳回 fabu 创作器并定位该节点编辑
  previewEditNode() {
    const node = this.data.sheet.node;
    const ec = this.getOpenerEventChannel && this.getOpenerEventChannel();
    ec && ec.emit && ec.emit('editNode', { nodeId: node && node.nodeId });
    this.previewGoBack();
  },
  flashLock() { this.setData({ lockHint: true }); setTimeout(() => this.setData({ lockHint: false }), 2200); },
  // ---------- 到达(无模板 / GPS / 手动) ----------
  _arriveTarget() {
    // nav 陪伴态优先按 nav.targetNodeId 解目标(玩家可能在导航别的点,sheet.node/nextNode 都可能是别的站),
    // 否则才落回原有 sheet.node/nextNode 链(非 nav 场景行为不变)。
    const nav = this.data.nav;
    return (nav.active && nav.targetNodeId != null && this._nodeById(nav.targetNodeId))
      || this.data.sheet.node || (this.data.nextNode && this.data.nodes.find((n) => n.nodeId === this.data.nextNode.nodeId));
  },
  onArriveTap() {
    const node = this._arriveTarget();
    if (!node) return;
    if (this._arrivalWriteUnknown) return;
    // 高级玩法已通过且本站没有坐标时直接收尾；真实地点和GPS任务仍要求定位。
    const needsLocation = node.lat || node.lng || node.vm === 5 || this._advancedReadyNodeId !== node.nodeId;
    if (!this.data.gpsOk && !this._isStoryGame(node) && needsLocation) { this.requestPlayLocation(); return; }
    this.arrive(node.nodeId, 'arrive');
  },
  onScanArrive() {
    const node = this._arriveTarget();
    if (this._arrivalWriteUnknown) return;
    if (node) this.scanArrive(node);
  },
  scanArrive(node) {
    const that = this;
    wx.scanCode({
      onlyFromCamera: true,
      success(r) { that.checkin(node ? node.nodeId : null, r.result); },
      fail(error) {
        if (isScanCancelled(error)) return;
        that.recordArrivalNetworkFailure('未能打开扫码，请检查相机权限后重试', { type: 'scan', nodeId: node && node.nodeId });
      }
    });
  },
  _beginRouteWriteContext(channel) {
    // 先建好路线动作存储:否则首写时 _routeWritePayload 才懒建,刚捕获的 routeActions 立刻失配。
    this._ensureRouteActionStore();
    const context = { channel, owner: this._boundPlayOwner(), sessionKey: this._sessionKey(),
      routeSessionId: String((this._routeState || {}).sessionId || ''), routeActions: this._routeActions };
    this[channel + 'Intent'] = context;
    return context;
  },
  _isRouteWriteContextCurrent(context) {
    return this._isPlayAccountCurrent() && context.owner === this._playOwner
      && this[context.channel + 'Intent'] === context
      && context.sessionKey === this._sessionKey() && context.routeActions === this._routeActions
      && (!context.routeSessionId || context.routeSessionId === String((this._routeState || {}).sessionId || ''));
  },
  _finishRouteWriteContext(context) {
    if (this[context.channel + 'Intent'] !== context) return;
    this[context.channel + 'Intent'] = null;
    this[context.channel + 'InFlight'] = false;
  },

  _ensureGuardAppeal() {
    const owner = this._boundPlayOwner(), session = this._sessionKey();
    const scope = owner + ':' + session;
    if (!this._guardAppeal || this._guardAppealScope !== scope) {
      this._guardAppealScope = scope;
      this._guardAppeal = guardAppeal.create({ storage: wx, owner, session,
        current: () => this._isPlayAccountCurrent() && this._sessionKey() === session && this._guardAppealScope === scope,
        // 守卫申诉只用三条固定接口；按字面量分派，U1 可逐条核对后端映射。
        send: (url, method, body) => {
          const header = { 'content-type': 'application/json' };
          switch (url) {
            case '/api/audit/guard/reference': return req('/api/audit/guard/reference', method, body, header, true);
            case '/api/audit/guard/appeal/receipt': return req('/api/audit/guard/appeal/receipt', method, body, header, true);
            case '/api/audit/guard/appeal': return req('/api/audit/guard/appeal', method, body, header, true);
          }
          throw new Error('Unknown guard appeal endpoint');
        },
        changed: view => this.setData({ guardAppealView: view }), keyFactory: uuid4 });
    }
    return this._guardAppeal;
  },
  restoreGuardAppeal() {
    if (!this._isPlayAccountCurrent() || this.data.isPreview || this.data.isMock || !(this.data.activityId || this.data.topicId)) return;
    try {
      const flow = this._ensureGuardAppeal(); this.setData({ guardAppealView: flow.view() });
      if (flow.view()) return flow.read();
    } catch (error) { this.diegetic(error.message); }
  },
  readGuardAppeal() { return this.restoreGuardAppeal(); },
  async submitGuardAppeal() {
    if (!this._isPlayAccountCurrent()) return;
    const flow = this._ensureGuardAppeal(); await flow.read();
    const view = flow.view();
    if (!this._isPlayAccountCurrent() || !view || view.command || !view.eligibility || !view.eligibility.canSubmit) return;
    const session = this._sessionKey();
    cyModal.show({ title: '原局守卫申诉', editable: true, multiline: true, maxlength: 500, placeholderText: '请填写申诉理由（最多500字）',
      content: '成立后仍需重新现场核验，不直接放行或补发奖励。',
      success: async result => {
        if (!result.confirm || !this._isPlayAccountCurrent() || session !== this._sessionKey()) return;
        try { await flow.submit(result.content); } catch (error) { if (this._isPlayAccountCurrent()) this.diegetic(error.message); }
      } });
  },
  async reverifyGuardArrival() {
    if (!this._isPlayAccountCurrent() || this._themeHidden) return;
    const flow = this._ensureGuardAppeal(); await flow.read();
    if (!this._isPlayAccountCurrent() || this._themeHidden) return;
    const view = flow.view(), session = this._sessionKey();
    const run = String((this._routeState || {}).sessionId || '');
    if (!view || !view.result || !view.result.canReverify || run !== view.reference.routeSessionId) {
      this.diegetic('原局当前不可重新核验，可继续查询申诉结果'); return;
    }
    const live = () => this._isPlayAccountCurrent() && !this._themeHidden && this._sessionKey() === session
      && String((this._routeState || {}).sessionId || '') === run;
    cyModal.show({ title: '重新现场核验', content: '仅本次获取当前位置；仍执行原现场条件和速度距离守卫，不补发奖励。',
      success: result => {
        if (!result.confirm || !live()) return;
        wx.getLocation({ type: 'gcj02', isHighAccuracy: true,
          success: loc => {
            if (!live()) return;
            if (!Number.isFinite(loc.latitude) || !Number.isFinite(loc.longitude)) { this.diegetic('定位无效，未提交'); return; }
            this._loc = { latitude: loc.latitude, longitude: loc.longitude, accuracy: loc.accuracy };
            this.arrive(view.reference.nodeId, 'guard-reverify');
          }, fail: () => { if (live()) this.diegetic('未取得现场定位，未提交'); } });
      } });
  },

  arrive(nodeId, via) {
    if (!this._isPlayAccountCurrent()) return;
    if (this.data.isPreview || this.data.isMock) { this.onComplete(nodeId, {}); return; }
    if (!this.beginArrivalWrite()) return;
    const that = this;
    const loc = this._loc || {};
    const context = this._beginRouteWriteContext('_arrivalWrite');
    let arrivalRequestId = null;
    const isCurrent = () => this._isRouteWriteContextCurrent(context);
    // 完成请求在伏笔/步数同步之后才生成,带上同步后的路线版本。
    this._withThoughtSync(() => {
      if (!isCurrent()) return Promise.resolve({ obsolete: true });
      const payload = this._routeWritePayload('arrive', nodeId, { nodeId, longitude: loc.longitude, latitude: loc.latitude, accuracy: playAccuracy.accuracyOf(loc) });
      arrivalRequestId = payload.routeActionId;
      return req('/api/play/arrive', 'POST', payload);
    }, isCurrent)
      .then((r) => {
        const current = isCurrent(); that._finishRouteWriteContext(context);
        if (!current || (r && r.obsolete)) return;
        if (r.code == 200 || r.code == '200') {
          that._resolveRouteAction('arrive', nodeId);
          that.setData({ arrivalGuard: null });
          if (that.data.mode === 2) { that.onShopDayArrived(nodeId, r.data); return; }
          that.onComplete(nodeId, r.data);
        }
        else if (that._handleRouteConflict(r, 'arrive', nodeId)) return;
        else if (r.httpFail) that.recordArrivalHttpUnknown(r.msg, nodeId);
        else if (r.netFail) that.recordArrivalNetworkFailure('打卡请求没有送达', { type: 'arrive', nodeId, via });
        else {
          that._resolveRouteAction('arrive', nodeId);
          const guard = arrivalGuardView.project(r, nodeId, arrivalRequestId);
          that.setData({ arrivalGuard: guard });
          if (guard && guard.appealable && guard.appealBizType === 'play_arrive_guard') {
            try { that._ensureGuardAppeal().reference(guard); } catch (error) { that.diegetic(error.message); }
          }
          if (playAccuracy.isLowAccuracyMsg(r.msg)) that.setData({ gpsLowAcc: true, gpsHint: playAccuracy.LOW_ACCURACY_HINT });
          that.diegetic(r.msg || '还没到这一站附近');
          if (that._hasGenericProofFlow()) return that._readGenericProof(nodeId);
        }
      }).catch(() => {
        const current = isCurrent(); that._finishRouteWriteContext(context);
        if (!current) return;
        that.recordArrivalNetworkFailure('打卡请求没有送达', { type: 'arrive', nodeId, via });
      });
  },
  checkin(nodeId, code, source) {
    if (!this._isPlayAccountCurrent()) return;
    if (this.data.isPreview || this.data.isMock) { this.onComplete(nodeId, {}); return; }
    const that = this;
    const isGame = source === 'game';
    if (isGame ? !this.beginGameWrite() : !this.beginArrivalWrite()) return;
    const attempt = isGame ? { type: 'gameCheckin', nodeId, code, viewContext: this._legacyGameContext() } : null;
    const retryAttempt = this._retryGameAttempt || null;
    const extra = { nodeId, code, registrationId: this.data.regId || undefined };   // 点击时的输入,同步期间不变
    const context = this._beginRouteWriteContext(isGame ? '_gameWrite' : '_arrivalWrite');
    const isCurrent = () => this._isRouteWriteContextCurrent(context) && (!isGame || this._isLegacyGameCurrent(attempt.viewContext));
    this._withThoughtSync(() => {
      if (!isCurrent()) return Promise.resolve({ obsolete: true });
      // 完成请求在伏笔/步数同步之后才生成,带上同步后的路线版本。
      const payload = isGame ? this._legacyGameWritePayload('gameCheckin', 'checkin', nodeId, extra, retryAttempt)
        : this._routeWritePayload('checkin', nodeId, extra);
      if (attempt) attempt.payload = payload;
      return req('/api/play/checkin', 'POST', this._retriedRouteWriteBody(payload, isGame && retryAttempt));
    }, isCurrent)
      .then((r) => {
        const current = isCurrent(); that._finishRouteWriteContext(context);
        if (!current || (r && r.obsolete)) return;
        // 扫码以"码→节点"为准:用后端返回的 nodeId 点亮(自由定向扫错码不会点亮错节点)
        if (r.code == 200 || r.code == '200') {
          that._resolveRouteAction('checkin', nodeId);
          if (isGame) that.clearGameNetworkError();
          const litId = (r.data && r.data.nodeId != null) ? r.data.nodeId : nodeId;
          // 探店日(mode2):静态码只记「进店」,后端不置完成 —— 本地也绝不能走 onComplete,
          // 它会置 done+发庆祝,把「进店」演成「已核销」。改走凭证态合并 + 进商家页三步条。
          if (that.data.mode === 2 && !isGame) { that.onShopDayArrived(litId, r.data); return; }
          that.onComplete(litId, r.data);
        } else if (that._handleRouteConflict(r, 'checkin', nodeId)) return;
        else if (r.httpFail) {
          if (isGame) that.recordGameHttpUnknown(r.msg);
          else that.recordArrivalHttpUnknown(r.msg, nodeId);
        } else if (r.netFail) {
          if (isGame) that.recordGameNetworkFailure('扫码结果没有送达', attempt);
          else that.recordArrivalNetworkFailure('扫码结果没有送达', { type: 'checkin', nodeId, code });
        } else if (isGame) {
          that._resolveRouteAction('checkin', nodeId);
          that._lastGameAttempt = null;
          that.setData({ gNetErr: r.msg || '这枚码对不上这一站', gNetRetryable: false });
          if (that._hasGenericProofFlow()) return that._readGenericProof(nodeId);
        } else {
          that._resolveRouteAction('checkin', nodeId);
          that.diegetic(r.msg || '这枚码对不上这一站');
          if (that._hasGenericProofFlow()) return that._readGenericProof(nodeId);
        }
      }).catch(() => {
        const current = isCurrent(); that._finishRouteWriteContext(context);
        if (!current) return;
        if (isGame) that.recordGameNetworkFailure('扫码结果没有送达', attempt);
        else that.recordArrivalNetworkFailure('扫码结果没有送达', { type: 'checkin', nodeId, code });
      });
  },
  // 2026-09-22 原来这条失败画在底部读数卡的错误条上(带一枚「重试」)。卡随地图层删除后改走
  // 全局 toast:写请求失败必须留下可见提示,重试就是用户再点一次那个动作 —— 静默是最糟的一种。
  recordArrivalNetworkFailure(message, attempt) {
    if (this._hasGenericProofFlow() && attempt && ['arrive', 'checkin'].includes(attempt.type)) {
      this._arrivalWriteUnknown = true;
      return this._readGenericProof(attempt && attempt.nodeId, '打卡结果待确认，请勿重复提交', 'arrive');
    }
    cyToast(message);
  },
  beginArrivalWrite() {
    if (this._arrivalWriteUnknown || this._arrivalWriteInFlight) return false;
    this._arrivalWriteInFlight = true;
    return true;
  },
  recordArrivalHttpUnknown(message, nodeId) {
    this._arrivalWriteUnknown = true;
    if (this._hasGenericProofFlow()) return this._readGenericProof(nodeId, '打卡结果待确认，请勿重复提交', 'arrive');
    // 结果未知这条尤其不能静默:看不见它的人只会反复点,而 beginArrivalWrite 已经把后续写拦住了。
    cyToast((message ? message + '；' : '') + '打卡结果待确认，请重新进入后查看，暂请勿重复提交');
  },
  recordGameNetworkFailure(message, attempt) {
    if (this._gameWriteUnknown) return;
    if (!attempt) return;
    if (!attempt.viewContext) attempt.viewContext = this._legacyGameContext();
    if (!this._isLegacyGameCurrent(attempt.viewContext)) return;
    if (this._hasGenericProofFlow() && ['photo', 'gameCheckin', 'gameScan', 'answer'].includes(attempt.type)) return this.recordGameHttpUnknown(message, attempt.nodeId);
    this._lastGameAttempt = attempt;
    this.setData({ gNetErr: message, gNetRetryable: true, gRetryType: attempt && attempt.type || '', gWriteUnknown: false });
  },
  beginGameWrite() {
    if (this._gameWriteUnknown || this._gameWriteInFlight) return false;
    this._gameWriteInFlight = true;
    return true;
  },
  recordGameHttpUnknown(message, nodeId) {
    this._gameWriteUnknown = true;
    this._gameWriteUnknownMessage = (message ? message + '；' : '') + '提交结果待确认，请重新进入后查看，暂请勿重复提交';
    // UNKNOWN 保留原局、动作和输入，供原号恢复使用；写入口继续冻结。
    this.setData({ gWrong: false, gNetErr: this._gameWriteUnknownMessage, gNetRetryable: false, gWriteUnknown: true });
    if (this._hasGenericProofFlow()) return this._readGenericProof(nodeId || this.data.game.nodeId, '', 'game');
  },
  clearGameNetworkError() {
    const unknown = !!this._gameWriteUnknown;
    if (!unknown) this._lastGameAttempt = null;
    if (this.data.gNetErr || this.data.gNetRetryable || this.data.gWriteUnknown !== unknown) {
      this.setData({
        gNetErr: unknown ? this._gameWriteUnknownMessage : '',
        gNetRetryable: false,
        gRetryType: '',
        gWriteUnknown: unknown,
      });
    }
  },
  /** 同号恢复保持完整原请求体，包括原版本；冲突后的新意图必须重新确认。 */
  _retriedRouteWriteBody(payload, retried) {
    return payload;
  },
  _legacyGameWritePayload(type, kind, nodeId, extra, retryAttempt) {
    // 显式重试在同步等待期间就会结束;调用方须在发起时捕获重试意图并传进来。
    const retry = retryAttempt === undefined ? this._retryGameAttempt : retryAttempt;
    if (retry && retry.type === type && retry.payload) return retry.payload;
    return this._routeWritePayload(kind, nodeId, extra);
  },
  retryGameSubmission() {
    if (this._gameWriteUnknown || this._gameWriteInFlight) return;
    const attempt = this._lastGameAttempt;
    this.clearGameNetworkError();
    if (!attempt || !this._isLegacyGameCurrent(attempt.viewContext)) return;
    this._retryGameAttempt = attempt;
    try {
      if (attempt.type === 'photo') this.submitPhoto(attempt.nodeId, attempt.pic);
      else if (attempt.type === 'gameCheckin') this.checkin(attempt.nodeId, attempt.code, 'game');
      else if (attempt.type === 'gameScan') this.scanGame();
      else if (attempt.type === 'puzzleReveal') this.revealAnswer();
      else if (attempt.type === 'answer') this.submitGame();
    } finally { if (this._retryGameAttempt === attempt) this._retryGameAttempt = null; }
  },

  // ---------- 节点玩法 ----------
  startGame(nodeOverride) {
    if (!this.data.isPreview && !this.data.isMock && this._playable === false) {
      cyToast(this.data.timeBanner);
      return;
    }
    if (nodeOverride && nodeOverride.nodeId != null) {
      this.setData({ 'sheet.node': nodeOverride, 'sheet.show': true, sheetOut: false }, () => this.startGame());
      return;
    }
    this.stopAudio();
    const node = this.data.sheet.node;
    if (!node) return;
    if (Number(this.data.mode) === 2 && !this.data.isPreview && !this.data.isMock
        && node.merchantId && !node.arrived) {
      this.openMerchantFromSheet();
      return;
    }
    if (String(this._playKitNodeId) !== String(node.nodeId)) {
      this._playKitNodeId = node.nodeId;
      this.setData({ playKit: { show: false, kit: null } });
    }
    if (node.hasAdvanced && this._advancedReadyNodeId !== node.nodeId) {
      /* 详情卡「开始任务」进来也认「首个会话视图已做完 → 直接接着走」,与故事里打开同一口径:
         退出前做完没点继续、重进时以前会把第一个玩法空白重开(估数显示未答、找东西 0/3),
         再点交就是「这一题已经答过了 / 已经全部找到了」的死循环(2026-10-27 生产实测)。 */
      if (!node.done) this._freshStoryGame = String(node.nodeId);
      this.setData({ 'sheet.show': false, chapterGameAnchor: '', advancedPlay: { show: true, node } });
      return;
    }
    /* 玩法已通过、本站靠到达收尾(vm 0/5/8/9/10,与 onAdvancedReady 同一白名单):详情卡的「开始任务」
       走到达,不进老任务页 —— 老任务页只写了 vm 1/2/3/4/6,这几个进去只剩标题与录音、零按钮。 */
    if (node.hasAdvanced && [0, 5, 8, 9, 10].includes(Number(node.vm))) { this.onSheetGoTap(); return; }
    const chips = [];
    if (node.duration) chips.push({ ic: '⏱', v: node.duration });
    if (node.difficulty) chips.push({ ic: '◇', v: node.difficulty });
    if (node.players) chips.push({ ic: '人', v: node.players });
    const opts = [];
    const optMedia = node.optionMedia || {};
    if (node.options) ['A', 'B', 'C', 'D'].forEach((k) => {
      if (!node.options[k]) return;
      const media = optMedia[k] || {};
      opts.push({ k, v: node.options[k], img: media.img || '', audio: media.audio || '' });
    });
    this.setData({
      hintUnlocking: this._isLegacyHintPending(node.nodeId), scanning: false,
      screen: 'gamePlay', 'sheet.show': false,   // 启动页(p02)已删:详情卡直接进任务
      game: {
        nodeId: node.nodeId, num: node.num, vm: node.vm, title: node.gameTitle || '本站任务',
        cover: node.cover, chips, story: node.story, rule: node.rule, materials: node.materials,
        qname: node.question || '依据现场线索作答', opts,
        qImg: node.questionImg, qAudio: node.questionAudio,
        audio: node.audio, audioDur: node.audioDur, photoDesc: node.photoDesc,
        feedback: node.feedback, reveal: node.reveal, hint1: node.hint1, hint2: node.hint2,
        hintLocked: !!node.hintLocked, hintCost: node.hintCost || 0, hintCount: node.hintCount || 0,
        puzzleScoring: !!node.puzzleScoring,
        medalName: node.medalName, medalImg: node.medalImg, couponId: node.couponId
      },
      answerInput: '', answerSel: '', gWrong: false,
      gNetErr: this._gameWriteUnknown ? this._gameWriteUnknownMessage : '',
      gNetRetryable: false, gWriteUnknown: !!this._gameWriteUnknown,
      shownHints: node.usedHints || [], revealed: false, wrongCount: 0,
      puzzleScoreCap: node.puzzleScoreCap == null ? 100 : node.puzzleScoreCap,
      // 锁态:按钮仍显示(文案带积分价),点击走解锁确认;明文态与原逻辑一致(hint2-only 也可看)
      canHint: node.puzzleScoring ? (node.usedHints || []).length < node.hintCount : (!!(node.hint1 || node.hint2) || !!node.hintLocked),
      canReveal: node.puzzleScoring && node.hintCount > 0 && (node.usedHints || []).length >= node.hintCount,
      hintLabel: node.hintLocked ? (node.hintCost + '积分')
        : (((node.usedHints || []).length || 1) + '/' + (node.hintCount || ((node.hint1 ? 1 : 0) + (node.hint2 ? 1 : 0)) || 1))
    }, () => { if (node.vm === 6) this.loadPreference(node); });
  },
  async onAdvancedReady(e) {
    if (!this._isPlayAccountCurrent()) return;
    const active = this.data.advancedPlay, node = active.node;
    if (!active.show || !node || (e.detail && e.detail.nodeId != null && e.detail.nodeId !== node.nodeId)) return;
    if (this._themeChallengeNodeId === Number(node.nodeId)) {
      this._themeChallengeNodeId = null;
      this.setData({ advancedPlay: { show: false, node: null }, playKit: { show: false, kit: null } });
      this.refreshThemeBackpack();
      return;
    }
    const context = { owner: this._boundPlayOwner(), sessionKey: this._sessionKey(),
      epoch: this._playLoadEpoch, active, chapter: this.data.chapter,
      component: this._advancedGameComponent() };
    this._advancedReadyIntent = context;
    const scopeCurrent = () => this._isPlayAccountCurrent() && this._advancedReadyIntent === context
      && context.owner === this._playOwner && context.epoch === this._playLoadEpoch
      && context.sessionKey === this._sessionKey() && String((this.data.chapter || {}).id) === String((context.chapter || {}).id);
    const current = () => scopeCurrent() && this.data.advancedPlay === active
      && active.show && active.node === node && this._advancedGameComponent() === context.component;
    // 玩法作答可能更新路线变量和版本，收尾写入前先读取新的权威版本。
    if (!this.data.isPreview && !this.data.isMock
        && this._routeState
        && (this._routeState.routeMode === 'BRANCH_GRAPH' || this._routeState.sessionId)) {
      if (!(await this.refreshRouteState())) return;
      if (!current()) return;
    }
    if (!current()) return;
    this._advancedReadyNodeId = node.nodeId;
    const closed = { show: false, node: null };
    this.setData({ advancedPlay: closed, playKit: { show: false, kit: null }, 'sheet.node': node }, () => {
      if (!scopeCurrent() || this.data.advancedPlay !== closed) return;
      this._advancedReadyIntent = null;
      /* vm 0/5 靠到达收尾;8/9/10(猜数字/猜图/找东西)也是 —— 后端
         ValidationMethod.completesOnArrive 白名单就把这三个算作「arrive 收尾」。
       以前只放 0/5 过,其余全被塞进 startGame:守卫刚被 _advancedReadyNodeId
       放行,于是落到老任务页 —— 那屏只写了 vm 1/2/3/4/6,这三个进来只剩一屏
       标题文字、零按钮的死路。 */
      if (node.vm === 0 || node.vm === 5 || node.vm === 8 || node.vm === 9 || node.vm === 10) {
        this.setData({ 'sheet.show': true, sheetOut: false });
        this.onSheetGoTap();
      } else {
        this.startGame();
      }
    });
  },
  onAdvancedClose() {
    this._advancedReadyIntent = null;
    const node = this.data.advancedPlay.node;
    if (node && this._themeChallengeNodeId === Number(node.nodeId)) {
      this._themeChallengeNodeId = null;
      this.setData({ advancedPlay: { show: false, node: null }, playKit: { show: false, kit: null } });
      this.refreshThemeBackpack();
      return;
    }
    this.setData({ advancedPlay: { show: false, node: null },
      playKit: { show: false, kit: null },
      'sheet.show': !this.data.chapterFull && !!node, 'sheet.node': node || null });
    if (this.data.chapterFull) {
      this.openChapterFull();
    }
  },
  closeGame() {
    // M9 埋点:任务界面未完成即退出 → node_abandon
    const g = this.data.game;
    if (g && g.nodeId && !this.data.isPreview && !this.data.isMock) {
      const n = this.data.nodes.find((x) => x.nodeId === g.nodeId);
      if (n && !n.done) analytics.track('node_abandon', { bizType: 'topic', bizId: this.data.topicId, properties: { nodeId: g.nodeId, via: 'game' } });
    }
    this._preferenceEpoch = (this._preferenceEpoch || 0) + 1;
    this.stopAudio(); this.clearGameNetworkError(); this.setData({ screen: '', scanning: false, hintUnlocking: false, gNetRetryable: false });
  },
  onInput(e) { this.setData({ answerInput: e.detail.value }); },
  selOpt(e) { this.setData({ answerSel: e.currentTarget.dataset.k }); },
  _isPreferenceContextCurrent(context) {
    return !!context && this._preferenceContext === context && context.epoch === this._preferenceEpoch
      && this._isLegacyGameCurrent(context.viewContext);
  },
  loadPreference(node) {
    if (this.data.isPreview) {
      this.setData({ preference: { loading: false, error: '偏好题暂不支持预览，可跳过玩法继续查看故事', steps: [], submitting: false, result: null } });
      return;
    }
    if (!this._isPlayAccountCurrent() || !node || node.nodeId !== this.data.game.nodeId) return;
    const that = this;
    const nodeId = node.nodeId;
    const epoch = (this._preferenceEpoch || 0) + 1;
    this._preferenceEpoch = epoch;
    const context = { epoch, nodeId, viewContext: this._legacyGameContext() };
    this._preferenceContext = context;
    this._preferenceResultContext = null;
    this.setData({ preference: {
      loading: true, error: '', steps: [], stepIndex: 0, choices: {}, inheritedTags: [],
      answerHere: false, submitting: false, result: null, pendingTag: null,
      tagDisclosure: null, progress: null, availableTagValues: []
    } });
    req('/api/play/preference/' + nodeId, 'GET', this._sessionParams()).then((r) => {
      if (!that._isPreferenceContextCurrent(context)) return;
      if (r.code == 200 || r.code == '200') {
        const d = r.data || {};
        that.setData({
          'preference.loading': false,
          'preference.steps': Array.isArray(d.steps) ? d.steps : [],
          'preference.inheritedTags': Array.isArray(d.inheritedTags) ? d.inheritedTags : []
        });
      } else {
        that.setData({ 'preference.loading': false, 'preference.error': r.msg || '偏好题加载失败' });
      }
    });
  },
  retryPreference() {
    const node = (this.data.nodes || []).find((n) => n.nodeId === this.data.game.nodeId);
    if (node) this.loadPreference(node);
  },
  answerPreferenceHere() { this.setData({ 'preference.answerHere': true }); },
  selectPreferenceOption(e) {
    const stepKey = e.currentTarget.dataset.step;
    const optionKey = e.currentTarget.dataset.option;
    this.setData({ ['preference.choices.' + stepKey]: optionKey });
  },
  nextPreferenceStep() {
    const p = this.data.preference;
    const step = p.steps[p.stepIndex];
    if (!step || !p.choices[step.key]) return cyToast('先选一个更像你的答案');
    if (p.stepIndex < p.steps.length - 1) {
      this.setData({ 'preference.stepIndex': p.stepIndex + 1 });
      return;
    }
    this.submitPreference({ choices: p.choices });
  },
  useInheritedPreference(e) {
    this.submitPreference({ choices: {}, reuseTagCode: e.currentTarget.dataset.code });
  },
  submitPreference(payload) {
    if (this.data.isPreview) { cyToast('预览中可跳过此玩法'); return; }
    const context = this._preferenceContext;
    if (!this._isPreferenceContextCurrent(context) || this.data.preference.submitting) return;
    const that = this;
    const nodeId = this.data.game.nodeId;
    const intent = { context };
    this._preferenceWriteIntent = intent;
    const current = () => this._preferenceWriteIntent === intent && this._isPreferenceContextCurrent(context);
    const original = Object.assign({}, payload, { choices: Object.assign({}, payload.choices || {}) });
    this.setData({ 'preference.submitting': true, 'preference.error': '' });
    this._withThoughtSync(() => req('/api/play/preference/' + nodeId + '/submit', 'POST',
      this._routeWritePayload('preference', nodeId, original), { 'content-type': 'application/json' }), current)
      .then((r) => {
        if (!current() || r && r.obsolete) return;
        if (r.code == 200 || r.code == '200') {
          that._resolveRouteAction('preference', nodeId);
          const d = r.data || {};
          if (d.needsTiebreak && d.tiebreak) {
            const steps = that.data.preference.steps.slice();
            if (!steps.some((s) => s.key === d.tiebreak.key)) steps.push(d.tiebreak);
            that.setData({ 'preference.submitting': false, 'preference.steps': steps, 'preference.stepIndex': steps.length - 1 });
            return;
          }
          that._preferenceResultContext = { context, nodeId, progress: d.progress || {} };
          that.setData({
            'preference.submitting': false,
            'preference.result': d.evaluation || null,
            'preference.pendingTag': d.pendingTag || null,
            'preference.tagDisclosure': d.tagDisclosure || null,
            'preference.progress': d.progress || null,
            'preference.availableTagValues': Array.isArray(d.availableTagValues) ? d.availableTagValues : []
          });
        } else if (that._handleRouteConflict(r, 'preference', nodeId)) {
          that.setData({ 'preference.submitting': false, 'preference.error': '路线状态已更新，请重新确认当前选择' });
        } else {
          if (!r.netFail && !r.httpFail) that._resolveRouteAction('preference', nodeId);
          that.setData({ 'preference.submitting': false, 'preference.error': r.msg || '结果没有生成，请重试' });
        }
      }).catch(() => {
        if (current()) this.setData({ 'preference.submitting': false, 'preference.error': '结果没有生成，请重试' });
      }).finally(() => {
        if (this._preferenceWriteIntent === intent) this._preferenceWriteIntent = null;
      });
  },
  _preferenceTagContext(tag) {
    return { preference: this._preferenceContext, tag };
  },
  _isPreferenceTagContextCurrent(context) {
    return !!context && this._isPreferenceContextCurrent(context.preference)
      && this.data.preference.pendingTag === context.tag;
  },
  _hasPreferenceTagWrite() {
    return !!this._preferenceTagIntent && this._isPreferenceTagContextCurrent(this._preferenceTagIntent.context);
  },
  _finishPreferenceTagWrite(intent) {
    if (this._preferenceTagIntent !== intent) return;
    this._preferenceTagIntent = null; this._preferenceTagWrite = false;
  },
  confirmPreferenceTag() {
    if (this.data.isPreview) { cyToast('预览中可跳过此玩法'); return; }
    const tag = this.data.preference.pendingTag;
    const context = this._preferenceTagContext(tag);
    if (!tag || !tag.id || !this._isPreferenceTagContextCurrent(context) || this._hasPreferenceTagWrite()) return;
    const intent = { context };
    this._preferenceTagIntent = intent; this._preferenceTagWrite = true;
    req('/api/play/tag/' + tag.id + '/confirm', 'POST', {}).then((r) => {
      const current = this._preferenceTagIntent === intent && this._isPreferenceTagContextCurrent(context);
      this._finishPreferenceTagWrite(intent);
      if (!current) return;
      if (r.code == 200 || r.code == '200') this.setData({ 'preference.pendingTag': r.data });
      else cyToast(r.msg || '标签没有确认成功');
    }).catch(() => {
      const current = this._preferenceTagIntent === intent && this._isPreferenceTagContextCurrent(context);
      this._finishPreferenceTagWrite(intent);
      if (current) cyToast('标签没有确认成功');
    });
  },
  correctPreferenceTag() {
    const tag = this.data.preference.pendingTag;
    const values = this.data.preference.availableTagValues || [];
    const context = this._preferenceTagContext(tag);
    if (!tag || !tag.id || !values.length || !this._isPreferenceTagContextCurrent(context) || this._hasPreferenceTagWrite()) return;
    this._preferenceTagSheet = { context, values: values.slice() };
    this.setData({ tagSheetShow: true, tagSheetItems: values });
  },
  onTagSheetCancel() { this._preferenceTagSheet = null; this.setData({ tagSheetShow: false }); },
  onTagSheetSelect(e) {
    if (this.data.isPreview) { cyToast('预览中可跳过此玩法'); return; }
    const sheet = this._preferenceTagSheet;
    this._preferenceTagSheet = null;
    this.setData({ tagSheetShow: false });
    if (!sheet || !this._isPreferenceTagContextCurrent(sheet.context)) return;
    const tag = sheet.context.tag, value = sheet.values[e.detail.index];
    if (!tag.id || !value || value === tag.tagValue || this._hasPreferenceTagWrite()) return;
    const intent = { context: sheet.context };
    this._preferenceTagIntent = intent; this._preferenceTagWrite = true;
    req('/api/play/tag/' + tag.id + '/correct', 'POST', { tagValue: value }, { 'content-type': 'application/json' }).then((r) => {
      const current = this._preferenceTagIntent === intent && this._isPreferenceTagContextCurrent(sheet.context);
      this._finishPreferenceTagWrite(intent);
      if (!current) return;
      if (r.code == 200 || r.code == '200') {
        this.setData({ 'preference.pendingTag': r.data }); cyToast('已改为 ' + value);
      } else cyToast(r.msg || '结果没有改成功');
    }).catch(() => {
      const current = this._preferenceTagIntent === intent && this._isPreferenceTagContextCurrent(sheet.context);
      this._finishPreferenceTagWrite(intent);
      if (current) cyToast('结果没有改成功');
    });
  },
  skipPreferenceTag() { this.setData({ 'preference.pendingTag': null }); },
  finishPreferenceResult() {
    const result = this._preferenceResultContext;
    if (!result || !this._isPreferenceContextCurrent(result.context)) return;
    this._preferenceResultContext = null;
    this.onComplete(result.nodeId, result.progress);
  },
  _legacyGameContext() {
    return { owner: this._boundPlayOwner(), sessionKey: this._sessionKey(),
      epoch: this._playLoadEpoch, game: this.data.game };
  },
  _isLegacyGameCurrent(context) {
    return this._isPlayAccountCurrent() && context.owner === this._playOwner
      && context.sessionKey === this._sessionKey()
      && context.epoch === this._playLoadEpoch && context.game === this.data.game;
  },
  scanGame() {
    if (this._gameWriteUnknown) return;
    const that = this, context = this._legacyGameContext();
    if (!this._isLegacyGameCurrent(context)) return;
    let settled = false;
    this.setData({ scanning: true });
    wx.scanCode({
      onlyFromCamera: true,
      success(r) {
        if (settled || !that._isLegacyGameCurrent(context)) return;
        settled = true; that.setData({ scanning: false }); that.checkin(context.game.nodeId, r.result, 'game');
      },
      fail(error) {
        if (settled || !that._isLegacyGameCurrent(context)) return;
        settled = true;
        that.setData({ scanning: false });
        if (!isScanCancelled(error)) that.recordGameNetworkFailure('未能打开扫码，请检查相机权限后重试', { type: 'gameScan', viewContext: context });
      }
    });
  },
  photoGame() {
    if (this._gameWriteUnknown) return;
    const that = this, g = this.data.game, context = this._legacyGameContext();
    if (!this._isLegacyGameCurrent(context)) return;
    if (this.data.isPreview || this.data.isMock) { this.onComplete(g.nodeId, {}); return; }
    let picked = false;
    app.chooseImage(function (urls) {
      if (picked || !that._isLegacyGameCurrent(context)) return;
      picked = true;
      const pic = (urls && urls[0]) || '';
      if (!pic) return;
      that.setData({ photoTaken: true, gNetErr: '', gNetRetryable: false });
      that.submitPhoto(g.nodeId, pic);
    }, 1);
  },
  submitPhoto(nodeId, pic) {
    const viewContext = this._legacyGameContext();
    if (!this._isLegacyGameCurrent(viewContext)) return;
    if (!this.beginGameWrite()) return;
    const retryAttempt = this._retryGameAttempt || null;
    const context = this._beginRouteWriteContext('_gameWrite');
    const isCurrent = () => this._isRouteWriteContextCurrent(context) && this._isLegacyGameCurrent(viewContext);
    const that = this;
    const attempt = { type: 'photo', nodeId, pic, viewContext };
    this._lastGameAttempt = attempt;
    this._withThoughtSync(() => {
      attempt.payload = this._legacyGameWritePayload('photo', 'photo', nodeId, { nodeId, picUrl: pic }, retryAttempt);
      return req('/api/play/photo', 'POST', this._retriedRouteWriteBody(attempt.payload, retryAttempt));
    }, isCurrent)
      .then((r) => {
        const current = isCurrent();
        that._finishRouteWriteContext(context);
        if (!current) return;
        if (r.code == 200 || r.code == '200') {
          that._resolveRouteAction('photo', nodeId);
          that.clearGameNetworkError();
          that.onComplete(nodeId, r.data);
          that._applyAiScore(nodeId, r.data);
        } else if (that._handleRouteConflict(r, 'photo', nodeId)) return;
        else if (r.httpFail) {
          that.recordGameHttpUnknown(r.msg);
        } else if (r.netFail) {
          that.recordGameNetworkFailure('照片上传没有送达', attempt);
        } else {
          that._resolveRouteAction('photo', nodeId);
          that._lastGameAttempt = null;
          that.setData({ gNetErr: r.msg || '照片未上传成功，请重新选择', gNetRetryable: false });
          if (that._hasGenericProofFlow()) return that._readGenericProof(nodeId);
        }
      }).catch(() => {
        const current = isCurrent();
        that._finishRouteWriteContext(context);
        if (!current) return;
        that.recordGameNetworkFailure('照片上传没有送达', attempt);
      });
  },
  submitGame() {
    if (this._gameWriteUnknown) return;
    const g = this.data.game;
    const retry = this._retryGameAttempt;
    const answer = retry && retry.type === 'answer' ? retry.answer : (g.vm == 3 ? this.data.answerSel : this.data.answerInput);
    if (!answer) return this.setData({ gWrong: true, 'game.feedback': '先把答案填上再提交。' });
    if (this.data.isPreview || this.data.isMock) { this.onComplete(g.nodeId, {}); return; }   // 路线级预览只跳过服务端判题,不跳过空值校验
    const viewContext = this._legacyGameContext();
    if (!this._isLegacyGameCurrent(viewContext)) return;
    if (!this.beginGameWrite()) return;
    const retryAttempt = this._retryGameAttempt || null;
    const context = this._beginRouteWriteContext('_gameWrite');
    const isCurrent = () => this._isRouteWriteContextCurrent(context) && this._isLegacyGameCurrent(viewContext);
    const attempt = { type: 'answer', nodeId: g.nodeId, answer, viewContext };
    const that = this;
    this._withThoughtSync(() => {
      attempt.payload = this._legacyGameWritePayload('answer', 'answer', g.nodeId, { nodeId: g.nodeId, answer }, retryAttempt);
      return req('/api/play/answer', 'POST', this._retriedRouteWriteBody(attempt.payload, retryAttempt));
    }, isCurrent)
      .then((r) => {
        const current = isCurrent();
        that._finishRouteWriteContext(context);
        if (!current) return;
        if (r.code == 200 || r.code == '200') { that._resolveRouteAction('answer', g.nodeId); that.clearGameNetworkError(); that.onComplete(g.nodeId, r.data); }
        else if (that._handleRouteConflict(r, 'answer', g.nodeId)) return;
        // 请求没送到 ≠ 服务端判你答错。原码把 code!=200 一律记 wrongCount++,断网提交两次
        // 就满足 canReveal(wc>=2) —— 系统替你答错两次、直接把答案揭示了。
        else if (r.httpFail) that.recordGameHttpUnknown(r.msg);
        else if (r.netFail) that.recordGameNetworkFailure('提交没送出去，检查网络后重试', attempt);
        else {
          that._resolveRouteAction('answer', g.nodeId);
          that._lastGameAttempt = null;
          // ★ 只有后端标了「答案错」(data.answerWrong)才计一次错。业务拒绝(活动已结束/未报名/
          //   先扫码进店/本章未放行…)原来也被计成答错,连碰两次业务错误就解锁「揭示答案」——
          //   等于系统替玩家答错两次。业务拒绝只提示,不计数、不解锁。
          const answerWrong = !!(r.data && r.data.answerWrong);
          const wc = answerWrong ? that.data.wrongCount + 1 : that.data.wrongCount;
          that.setData({ gWrong: true, gNetErr: '', gNetRetryable: false, wrongCount: wc, 'game.feedback': r.msg || g.feedback, canReveal: wc >= 2 && (!!g.reveal || !!g.puzzleScoring) });
          if (that._hasGenericProofFlow()) return that._readGenericProof(g.nodeId);
        }
      }).catch(() => {
        const current = isCurrent();
        that._finishRouteWriteContext(context);
        if (!current) return;
        that.recordGameNetworkFailure('提交没送出去，检查网络后重试', attempt);
      });
  },
  _legacyHintBusinessKey(nodeId) {
    return JSON.stringify([this._boundPlayOwner(), this._sessionKey(), (this._routeState || {}).sessionId || '', String(nodeId)]);
  },
  _isLegacyHintPending(nodeId) {
    return !!(this._legacyHintPending && this._legacyHintPending[this._legacyHintBusinessKey(nodeId)]);
  },
  _beginLegacyHint() {
    const context = this._legacyGameContext(), key = this._legacyHintBusinessKey(context.game.nodeId);
    const pending = this._legacyHintPending = this._legacyHintPending || {};
    if (pending[key]) return null;
    const intent = { context, key, pending };
    pending[key] = intent; this._legacyHintIntent = intent;
    return intent;
  },
  _isLegacyHintCurrent(intent) {
    return !!intent && this._legacyHintIntent === intent && this._isLegacyGameCurrent(intent.context);
  },
  _finishLegacyHint(intent) {
    if (!intent) return;
    if (intent.pending[intent.key] === intent) delete intent.pending[intent.key];
    if (this._legacyHintIntent === intent) this._legacyHintIntent = null;
    const game = this.data.game;
    if (this._isPlayAccountCurrent() && game && this._legacyHintBusinessKey(game.nodeId) === intent.key
        && !this._isLegacyHintPending(game.nodeId)) this.setData({ hintUnlocking: false });
  },
  useHint() {
    const g = this.data.game;
    if (!this._isPlayAccountCurrent()) return;
    if (!this.data.isPreview && g.puzzleScoring && this.data.mode === 1) {
      if (this.data.hintUnlocking) return;
      const level = this.data.shownHints.length + 1;
      const intent = this._beginLegacyHint();
      if (!intent) return;
      this.setData({ hintUnlocking: true });
      req('/api/play/puzzle/hint', 'POST', Object.assign(this._sessionParams(), { nodeId: g.nodeId, level }))
        .then((r) => {
          if (!this._isLegacyHintCurrent(intent)) { this._finishLegacyHint(intent); return; }
          if (r.code == 200 || r.code == '200') {
            const hints = Array.isArray(r.data.hints) ? r.data.hints.filter(Boolean)
              : this.data.shownHints.concat([r.data.hint]).filter(Boolean);
            const hintCount = Number(r.data.hintCount || g.hintCount || hints.length);
            this.setData({
              shownHints: hints,
              puzzleScoreCap: Number(r.data.scoreCap) || 40,
              canHint: hints.length < hintCount,
              canReveal: hints.length >= hintCount,
              hintLabel: hints.length + '/' + hintCount,
            });
            const nodeIndex = this.data.nodes.findIndex((n) => n.nodeId === g.nodeId);
            if (nodeIndex >= 0) {
              const nodes = this.data.nodes.slice();
              nodes[nodeIndex] = Object.assign({}, nodes[nodeIndex], {
                usedHints: hints,
                puzzleHintLevel: Number(r.data.level) || 0,
                puzzleScoreCap: Number(r.data.scoreCap) || 40,
              });
              this.setData({ nodes });
            }
          } else {
            cyToast(r.msg || '提示暂时不可用');
          }
          this._finishLegacyHint(intent);
        })
        .catch(() => {
          const current = this._isLegacyHintCurrent(intent);
          this._finishLegacyHint(intent);
          if (!current) return;
          cyToast('提示加载失败，请重试');
        });
      return;
    }
    if (!this.data.isPreview && g.hintLocked) { this.unlockHint(); return; }
    // 按实际配置的提示列表渐进揭示(兼容只配 hint2 的模板,别让人付了分看不到东西)
    const all = [g.hint1, g.hint2].filter(function (t) { return !!t; });
    const hints = this.data.shownHints.slice();
    if (hints.length < all.length) hints.push(all[hints.length]);
    this.setData({ shownHints: hints, canHint: hints.length < all.length, hintLabel: hints.length + '/' + (all.length || 1) });
  },
  // 积分解锁本节点提示:确认弹窗 → 后端扣分(幂等,重复调不重复扣) → 回填明文继续渐进揭示
  unlockHint() {
    if (this.data.isPreview) { this.useHint(); return; }
    const that = this, g = this.data.game;
    if (!this._isPlayAccountCurrent()) return;
    if (this.data.hintUnlocking) return;
    const intent = this._beginLegacyHint();
    if (!intent) return;
    const payload = Object.assign({ nodeId: g.nodeId }, this._sessionParams());
    let confirmed = false;
    this.setData({ hintUnlocking: true }); // 弹窗期间即占位:连点不再叠出第二个弹窗
    cyModal.show({
      title: '解锁提示',
      content: '解锁本节点提示需 ' + (g.hintCost || 0) + ' 积分，确认解锁?',
      confirmText: '解锁',
      cancelText: '再想想',
      success(res) {
        if (confirmed) return;
        if (!that._isLegacyHintCurrent(intent)) { that._finishLegacyHint(intent); return; }
        confirmed = true;
        if (!res.confirm) { that._finishLegacyHint(intent); return; }
        app.sendRequest({
          url: '/api/play/hint/unlock', method: 'POST',
          // 必须带会话上下文:后端按它验「真的在这场/这个主题里」(与解谜提示同口径),
          // 只发 nodeId 会被拒 —— 否则任何登录用户拿到节点 id 就能读走提示正文。
          data: payload,
          success(resp) {
            if (!that._isLegacyHintCurrent(intent)) return;
            if (resp.code == '200' && resp.data) {
              that.setData({
                'game.hint1': resp.data.hint1 || '',
                'game.hint2': resp.data.hint2 || '',
                'game.hintLocked': false
              });
              that.useHint(); // 解锁后立即揭示第一条
            } else {
              cyToast(app.getRequestErrorMessage(resp, '解锁失败，请重试'));
            }
          },
          fail(resp) {
            if (!that._isLegacyHintCurrent(intent)) return;
            cyToast(app.getRequestErrorMessage(resp, '网络异常，请重试'));
          },
          complete() { that._finishLegacyHint(intent); }
        });
      },
      fail() { if (!confirmed) that._finishLegacyHint(intent); }
    });
  },
  revealAnswer() {
    const g = this.data.game;
    if (this.data.isPreview || !g.puzzleScoring || this.data.mode !== 1) {
      this.setData({ revealed: true, canReveal: false });
      return;
    }
    const viewContext = this._legacyGameContext();
    if (!this._isLegacyGameCurrent(viewContext)) return;
    if (!this.beginGameWrite()) return;
    const retryAttempt = this._retryGameAttempt || null;
    const context = this._beginRouteWriteContext('_gameWrite');
    const isCurrent = () => this._isRouteWriteContextCurrent(context) && this._isLegacyGameCurrent(viewContext);
    const attempt = { type: 'puzzleReveal', nodeId: g.nodeId, viewContext };
    this._lastGameAttempt = attempt;
    this._withThoughtSync(() => {
      attempt.payload = this._legacyGameWritePayload('puzzleReveal', 'puzzleReveal', g.nodeId, { nodeId: g.nodeId }, retryAttempt);
      return req('/api/play/puzzle/reveal', 'POST', this._retriedRouteWriteBody(attempt.payload, retryAttempt));
    }, isCurrent)
      .then((r) => {
        const current = isCurrent();
        this._finishRouteWriteContext(context);
        if (!current) return;
        if (r.code == 200 || r.code == '200') {
          this._resolveRouteAction('puzzleReveal', g.nodeId);
          this.clearGameNetworkError();
          const answer = (r.data && r.data.answerReveal) || '答案已揭示';
          this.setData({ revealed: true, canReveal: false, puzzleScoreCap: 0, 'game.reveal': answer });
          cyModal.show({
            title: '答案', content: answer, showCancel: false, confirmText: '继续',
            success: () => { if (this._isLegacyGameCurrent(viewContext)) this.onComplete(g.nodeId, r.data || {}); },
          });
        } else if (this._handleRouteConflict(r, 'puzzleReveal', g.nodeId)) return;
        else if (r.httpFail) this.recordGameHttpUnknown(r.msg);
        else if (r.netFail) this.recordGameNetworkFailure('查看答案请求没有送达', attempt);
        else {
          this._resolveRouteAction('puzzleReveal', g.nodeId);
          this._lastGameAttempt = null;
          cyToast(r.msg || '暂时不能查看答案');
        }
      })
      .catch(() => {
        const current = isCurrent();
        this._finishRouteWriteContext(context);
        if (!current) return;
        this.recordGameNetworkFailure('查看答案请求没有送达', attempt);
      });
  },

  // ---------- 完成:盖章 + 飞分 + 徽章/券 + 推进 ----------
  /** 探店日进店回执:只合并后端凭证态(arrived/arrivedAt/…),不置 done、不庆祝,
   *  然后带进商家详情页,由三步条接管「拍照 → 出示核销码」。 */
  onShopDayArrived(nodeId, data) {
    const patch = data || {};
    const nodes = this.data.nodes.map((n) => n.nodeId === nodeId ? Object.assign({}, n, patch) : n);
    this.setData({ nodes, 'sheet.show': false }, () => this.rebuild());
    wx.navigateTo({ url: '/pages/play/merchant/index?nodeId=' + nodeId + '&justScanned=1' });
  },
  _animateCompletedSegment(nodes, nodeId) {
    if (this._routeDrawStop) this._routeDrawStop();
    const segment = completedRouteSegment(nodes, nodeId);
    if (segment.length < 2) return;
    const base = (this.data.fmPolyline || []).filter((line) => !line.gameProgressSegment);
    this._routeDrawStop = motion.routeDraw(segment, (points) => {
      const line = {
        gameProgressSegment: true,
        points: points.map((point) => ({ latitude: point.lat, longitude: point.lng })),
        color: routeColorForKey('confirmed-progress'),
        width: 8,
        borderColor: ROUTE_BORDER_COLOR,
        borderWidth: 2,
        dottedLine: false,
        arrowLine: false,
      };
      this.setData({ fmPolyline: base.concat(line) });
    }, { reducedMotion: this.data.reducedMotion });
  },
  _hasGenericProofFlow() {
    const bag = this.data.themeBag || {};
    return !this.data.isPreview && !this.data.isMock && !!bag.sessionId
      && ((bag.quests || []).length > 0 || bag.activeQuest || bag.gameplay && bag.gameplay.activeQuest || bag.activeOperation && bag.activeOperation.operationKey);
  },
  _readGenericProof(nodeId, message, domain) {
    const epoch = this._themeEpoch || 0;
    return Promise.allSettled([this.refreshRouteState(), this.refreshThemeBackpack()]).then(reads => {
      if (epoch !== (this._themeEpoch || 0)) return false;
      const node = this._nodeById(nodeId);
      const bag = this.data.themeBag || {};
      const quest = (bag.quests || []).find(row => String(row.nodeId) === String(nodeId));
      const operation = bag.activeOperation || {};
      const confirmed = nodeId != null && (reads[0].status === 'fulfilled' && reads[0].value === true && !!(node && node.done)
        || reads[1].status === 'fulfilled' && reads[1].value === true && (!!(quest && quest.trustedPass === true)
          || String(operation.nodeId) === String(nodeId) && operation.verified === true));
      if (confirmed && domain === 'arrive') this._arrivalWriteUnknown = false;
      if (confirmed && domain === 'game') { this._gameWriteUnknown = false; this._gameWriteUnknownMessage = ''; this.clearGameNetworkError(); }
      if (!confirmed && message) this.diegetic(message);
      return confirmed;
    });
  },
  _captureCompletionEffects() {
    const context = { owner: this._boundPlayOwner(), sessionKey: this._sessionKey(),
      routeSessionId: String((this._routeState || {}).sessionId || '') };
    this._completionEffects = context;
    return context;
  },
  _isCompletionEffectCurrent(context) {
    return !!context && this._completionEffects === context && this._isPlayAccountCurrent()
      && context.owner === this._playOwner && context.sessionKey === this._sessionKey()
      && context.routeSessionId === String((this._routeState || {}).sessionId || '');
  },
  onComplete(nodeId, data) {
    const afterProfile = !!this._profileNodeId && String(nodeId) === this._profileNodeId;
    if (this.data.isPreview) {
      this._completePreviewNode(nodeId);
      if (afterProfile) this._showStartState(this._previewStartRows || []);
      return;
    }
    // 基础到达/凭证不等于正式结算；照片机审仍可能返回 advanceProgress 的完整回执。
    const returnedNodeState = data && data.routeState && data.routeState.nodeStates && data.routeState.nodeStates[String(nodeId)];
    const completedReceipt = data && typeof data.firstTime === 'boolean' && Number.isInteger(data.done) && String(data.nodeId) === String(nodeId);
    if (this._hasGenericProofFlow() && !completedReceipt || returnedNodeState && returnedNodeState !== 'COMPLETED'
      || data && (data.done === false || data.themeRuntime || data.sharedRuntime || data.disabledReason === 'PROOF_PENDING')) {
      data = data || {};
      const patch = {}; for (const field of ['arrived', 'arrivedAt', 'selfReported', 'picUrl', 'gameDone']) if (data[field] !== undefined) patch[field] = data[field];
      const merge = node => String(node.nodeId) === String(nodeId) ? Object.assign({}, node, patch) : node;
      if (this._routeNodeCatalog) this._routeNodeCatalog = this._routeNodeCatalog.map(merge);
      this.stopNav(); this.clearGameNetworkError();
      this.setData({ nodes: this.data.nodes.map(merge), screen: '', 'sheet.show': false, flyScore: false, themeBackpackOpen: true, themeBackpackTab: 'game' }, () => this.rebuild());
      if (data.themeRuntime && data.themeRuntime.sessionId) this._applyThemeView(data.themeRuntime);
      const message = data.disabledReason === 'PROOF_PENDING' ? '待审核 / 凭证未通过，请确认原任务' : '凭证已记录，请继续当前任务';
      this.diegetic(message);
      return data.sharedRuntime ? Promise.resolve(this._loadPlayerGameModule()).then(() => this._readGenericProof(nodeId)) : this._readGenericProof(nodeId);
    }
    if (this._sound) this._sound.sfx('ok');
    const effectContext = this._captureCompletionEffects();
    const effectsCurrent = () => this._isCompletionEffectCurrent(effectContext);
    const storyOpen = this.data.chapterFull && this._isStoryGame(
      (this.data.nodes || []).find((node) => String(node.nodeId) === String(nodeId)));
    // arrival/game 是可并发的两个写域；任一节点完成都不能解除另一写域的在途/待核对锁。
    // 各请求只在自己的 then/catch 中收口 in-flight，unknown 只能由该动作的权威读回或重进页面清除。
    this.clearGameNetworkError();
    this.stopNav();   // S2 陪伴态:到达即收起陪伴条(nav 未激活时是安全 no-op)
    // P0 推荐采纳埋点:完成的正是当前推荐节点(mode=2)
    if (this.data.mode === 2 && this.data.nextNode && this.data.nextNode.nodeId === nodeId && !this.data.isMock) {
      analytics.track('play_rec_accept', { bizType: 'topic', bizId: this.data.topicId, properties: { nodeId } });
    }
    // 完成事实来自本次服务端成功回执；BRANCH_GRAPH 的下一站仍只认随回执返回或只读刷新得到的 routeState。
    const patchCompleted = (n) => n.nodeId === nodeId ? Object.assign({}, n, {
      done: true,
      doneAt: parseGameCompletedAt(data && data.completedAt) || n.doneAt || 0,
      earnedXp: (data && (data.xp != null || data.score != null))
        ? Number(data.xp != null ? data.xp : data.score) : n.earnedXp,
      routeNodeState: this._routeState.routeMode === 'BRANCH_GRAPH' ? 'COMPLETED' : n.routeNodeState,
      playable: false,
      puzzleScore: data && data.puzzleScore != null ? Number(data.puzzleScore) : n.puzzleScore,
      completionMode: data && data.completionMode ? data.completionMode : n.completionMode,
    }) : n;
    const catalog = (this._routeNodeCatalog || this.data.nodes).map(patchCompleted);
    this._routeNodeCatalog = catalog;
    const hasRouteProjection = !!(data && data.routeState);
    const acceptedProjection = hasRouteProjection && this._acceptRouteStateProjection(data.routeState);
    const returnedRouteState = acceptedProjection ? normalizeRouteState(data.routeState) : null;
    const routeState = returnedRouteState || this._routeState;
    effectContext.routeSessionId = String((routeState || {}).sessionId || '');
    const nodes = hasRouteProjection
      ? applyRouteStateToNodes(catalog, routeState)
      : this.data.nodes.map(patchCompleted);
    if (data && data.puzzlePersonalBest != null) {
      this._puzzlePersonalBest = Number(data.puzzlePersonalBest) || 0;
    }
    const node = nodes.find((n) => n.nodeId === nodeId) || {};
    const score = Number(data && (data.xp != null ? data.xp : data.score));
    // M8 到达双震:两次短震间隔 ~120ms,只强化到达这一处;彩蛋(light)/券(heavy)分级不动
    wx.vibrateShort({ type: 'medium' });
    setTimeout(() => { if (effectsCurrent()) wx.vibrateShort({ type: 'medium' }); }, 120);
    // 飞字(双轨文案:探索值升等级,区别于可抵现积分)
    if (Number.isFinite(score) && score > 0 && data.firstTime !== false) {
      this.setData({ flyScore: true, flyText: '+' + score + ' 探索值' });
      setTimeout(() => { if (effectsCurrent()) this.setData({ flyScore: false }); }, 1100);
    }
    // 标记 just-wrote(手记写入)
    const justId = nodeId;
    this._routeState = routeState;
    if (acceptedProjection) this._versionedRouteState = this._routeStateVersionKnown(data.routeState) ? routeState : null;
    this.setData({
      nodes,
      routeNotice: returnedRouteState ? this._routeNoticeForState(returnedRouteState) : this.data.routeNotice,
      journeyAttributes: routeState.attributes,
      screen: '',
      'sheet.show': false
    }, () => {
      if (!effectsCurrent()) return;
      this.rebuild();
      if (afterProfile) this._showStartState(startStateRows(routeState, this._vitalsOn));
      setTimeout(() => { if (effectsCurrent()) this._animateCompletedSegment(nodes, nodeId); }, 0);
      this._triggerCelebration('task-complete');
      // M2 夜间提示(自玩 22:00–06:00 后端下发 nightWarning):结果时刻一句,非阻断、自动收
      if (data && data.nightWarning === true) this._flashNightHint();
      // mode2 碎片翻卡:完成即翻出该节点碎片(空则不弹)
      if (this.data.mode === 2) {
        const done = nodes.filter((n) => n.done);
        const frText = node.fragmentText || '';
        // 剩余点位半屏已删(用户裁决):碎片翻卡恢复常弹,剩余点看地点卡层
        if (frText) this.setData({ frag: { show: true, text: frText, step: '我的第 ' + done.length + ' 步 · ' + (node.name || '') } });
      }
      const completed = data && data.completed;
      /* 服务端这次判了通关(主题规则可以是「主线 N 站」,选做站没走完也算):通关卡按「通关」讲,
         别因为本地还有没走的选做站就把标题写成章节名、把里程碑少算这一个(2026-10-27 生产实测)。 */
      if (completed && !this.data.topicCompleted) this.setData({ topicCompleted: true });
      // 后端本次新得成长徽章(FIRST_STEP/STREAK/TOPIC_CLEAR…),与节点模板勋章不同源,逐一轻提示
      const newBadges = (data && data.newBadges) || [];
      if (newBadges.length) {
        // 原来这里是一句 toast:徽章名一闪就没,badgeCode(服务端给的稳定 ID)被直接丢掉,
        // 玩家既回不到收藏位、也没法核实这枚徽章到底有没有真落进墙里。
        // 改成常驻掉落卡:留住 code,给「查看收藏」和「继续」两条明路。
        this.setData({
          rewardDrop: {
            show: true,
            text: '解锁徽章:' + newBadges.map((b) => b.name).join('、'),
            badges: newBadges.map((b) => ({ code: b.code || '', name: b.name || '徽章' })),
          },
        });
      }
      // 名次奖牌只发给全平台头三个完成该节点的人(node_medal_claim)。原来只要节点配了
      // medalName/medalImg 就弹「专属徽章」——第 4 名起弹的是假奖,勋章墙里根本没有。
      // data.medalRank 才是「这次真夺到了」的事实;没中签就不弹勋章,给一句人话。
      const medalRank = data && Number(data.medalRank) > 0 ? Number(data.medalRank) : 0;
      const hasMedalArt = !!(node.medalName || node.medalImg);
      const previewMedal = !!(this.data.isPreview || this.data.isMock);
      if (hasMedalArt && (medalRank || previewMedal)) {
        // 模板勋章弹层;关闭时若已通关由 closeMedal 触发通关卡
        this._medalCompletionContext = effectContext;
        this.setData({ medal: { show: true, name: node.medalName || '专属徽章', shortName: (node.medalName || '徽章').slice(0, 2), img: node.medalImg, style: node.medalStyle || 'glow', coupon: node.couponId ? '到店出示核销' : '' } }); if (medalRank) (this._wonMedals = this._wonMedals || []).push({ nodeId: node.nodeId, name: node.medalName || '专属徽章', img: node.medalImg || '', rank: medalRank }); // 完成页勋章堆只收真夺到的
      } else {
        if (hasMedalArt) this.diegetic('这个节点的名次奖牌已经发完，完成记录已保存');
        if (node.couponId) this.showCoupon('到店出示核销');
        if (completed) setTimeout(() => { if (effectsCurrent()) this.openFinish(); }, 600);  // 末站即便配券也要弹通关卡(原 else-if 漏掉)
      }
      if (newBadges.length || (hasMedalArt && (medalRank || previewMedal))) this._triggerCelebration('medal-earned');
      this.setData({ journalDot: !this.data.showJournal });
      if (storyOpen && (this.data.isPreview || this.data.isMock)) this.openChapterFull();
      if ((this._routeState.routeMode === 'BRANCH_GRAPH' || storyOpen) && !this.data.isPreview && !this.data.isMock) {
        // routeState 只描述节点状态，未包含本次刚从 HIDDEN 解锁节点的地点/玩法资料。
        // 必须同页重读 /nodes，不能等玩家重新进入页面才看见下一站。
        this.loadData(false);
      }
    });
  },
  closeMedal() {
    const context = this._medalCompletionContext;
    this._medalCompletionContext = null;
    this.setData({ 'medal.show': false });
    if (this.data.allDone && this._isCompletionEffectCurrent(context)) {
      setTimeout(() => { if (this._isCompletionEffectCurrent(context)) this.openFinish(); }, 300);
    }
  },
  // P0 券断链修复:落袋可见(文案+重震动)+ 可点击一跳到卡券包;停留 5s 给足点击窗口
  // M11 掉落分级·重:券=独立 couponBurst 彩带+重震动,不并入完成庆祝语义
  showCoupon(text) {
    wx.vibrateShort({ type: 'heavy' });   // 动效分级:券=重
    const colors = ['#FFFFFF', '#FFFFFF', '#FFFFFF', '#EEEEEE'];
    const couponBurst = [];
    for (let i = 0; i < 22; i++) couponBurst.push({ i, left: Math.round(Math.random() * 100), color: colors[i % colors.length], dur: (1.4 + Math.random() * 1.2).toFixed(2), delay: (Math.random() * 0.4).toFixed(2) });
    this.setData({ couponToast: true, couponText: text, couponBurst });
    if (this._couponTimer) clearTimeout(this._couponTimer);
    if (this._burstTimer) clearTimeout(this._burstTimer);
    this._burstTimer = setTimeout(() => this.setData({ couponBurst: [] }), 2600);
    this._couponTimer = setTimeout(() => this.setData({ couponToast: false }), 5000);
  },
  // 去优惠券列表:券 toast / 勋章弹层券行 共用(2026-07-31:票夹已删优惠券 tab，改跳独立优惠券页)
  goCouponWallet() {
    this.setData({ couponToast: false, 'medal.show': false });
    this.openScene('game-coupon-wallet');
  },
  continueNext() {
    this.setData({ 'sheet.show': false });
    const nx = this.data.nextNode;
    if (nx) { const node = this._nodeById(nx.nodeId); if (node && node.lat && node.lng) this.setData({ fmCenter: { lat: node.lat, lng: node.lng } }); }
  },
  diegetic(msg) { cyToast(msg); },
  // M2 夜间提示(自玩 22:00–06:00):完成结果时刻出,4.6s 自动收;纯展示层,不吃点击不弹窗。
  _flashNightHint() {
    if (this._nightHintTimer) clearTimeout(this._nightHintTimer);
    this.setData({ nightHint: true });
    this._nightHintTimer = setTimeout(() => {
      this._nightHintTimer = null;
      this.setData({ nightHint: false });
    }, 4600);
  },

  /** 「查看收藏」:带着 badgeCode 跳徽章墙——奖励必须能在它自己的详情位被回读到。 */
  openRewardCollection() {
    const first = (this.data.rewardDrop.badges || [])[0] || {};
    this.setData({ 'rewardDrop.show': false });
    wx.navigateTo({
      url: '/subpackageP3/pages/badge-wall/index/index'
        + (first.code ? '?badgeCode=' + encodeURIComponent(first.code) : ''),
      fail: () => this.diegetic('徽章墙无法打开，可以从成长中心进入'),
    });
  },
  closeRewardDrop() { this.setData({ 'rewardDrop.show': false }); },

  // ---------- 章节过场 ----------
  openScene(id, params = {}) {
    const next = getScene(id, params);
    if (exitDecision(this.data.sceneStack, 'close') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'replace', pending: next } });
      return false;
    }
    const sceneStack = pushScene(this.data.sceneStack, next);
    this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneIn: false, sceneConfirm: { show: false, action: null, pending: null } });
    /* sceneIn 先 false 再置 true:原型 sheet 是 @keyframes up 进场,这里换成同参数的
       transition(仓库 keyframes 只减不增),必须留一帧当起点。 */
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
  // 任务列表的行是官方活动:收场景后进官方活动详情(3-18)
  openOfficialEvent(e) {
    this.closeScene();
    wx.navigateTo({ url: '/pages/activity/official-detail/index?id=' + e.detail.id });
  },
  // 「投一张」:scene-roam-poi-detail 只发事件,由宿主收场景再进分包城市签(3-24)
  openCityStamp(e) {
    const place = (e && e.detail && e.detail.place) || '这一站';
    this.closeScene();
    wx.navigateTo({ url: '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent(place) });
  },
  closeScene() { if (this._sceneInTimer) { clearTimeout(this._sceneInTimer); this._sceneInTimer = null; }
    this.setData({ sceneStack: [], sceneCurrent: null, sceneIn: false, sceneConfirm: { show: false, action: null, pending: null } }); },
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
  onSceneSessionShare() {
    if (wx.showShareMenu) wx.showShareMenu({ withShareTicket: true });
  },
  openStampCameraFromScene() {
    this.closeScene();
    wx.navigateTo({ url: '/subpackageP3/pages/stamp-camera/index/index' });
  },
  openCityStamp(event) {
    const place = (event && event.detail && event.detail.place) || '这一站';
    this.closeScene();
    wx.navigateTo({ url: '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent(place) });
  },
  blockSceneTouch() {},
  openSessionTools() { this.setData({ sessionToolsOpen: true, toolsOut: false }); },
  // 收进菜单的动作:先关菜单再执行,与其它 *FromTools 同一条规矩。
  onScanArriveFromTools() { this.setData({ sessionToolsOpen: false }, () => this.onScanArrive()); },
  onRunToggleFromTools() { this.setData({ sessionToolsOpen: false }, () => this.onRunToggle()); },
  openStackOrChaptersFromTools() { this.setData({ sessionToolsOpen: false }, () => this.openStackOrChapters()); },
  toggleSessionTools() { if (this.data.sessionToolsOpen) this._toolsClose(); else { this.setData({ sessionToolsOpen: true, toolsOut: false }); this.loadSessionStats(); this.loadPlayContact(); } },
  closeSessionTools() { this._toolsClose(); },
  /* 定位驱动的两行(自动记录足迹 / 到点提醒)在这一页翻不动:它们跟着系统定位权限走。
   * 没开就去要权限,已经开着就说清楚它由谁决定 —— 不留一枚按下去毫无反应的开关。 */
  onLocationRowTap() {
    if (!this.data.gpsOk) { this.requestPlayLocation(); return; }
    cyToast('跟着定位权限走，去微信设置里改');
  },

  toggleReducedMotion() {
    const reducedMotion = writeReducedMotion(null, !this.data.reducedMotion); if (reducedMotion === null) { cyToast('设置未保存，请重试'); return; }
    this.setData({ reducedMotion });
    cyToast(reducedMotion ? '已减少动态效果' : '已恢复动态效果');
  },
  _triggerCelebration(moment) {
    if (!moment) return;
    this._celebrationSeq = (this._celebrationSeq || 0) + 1;
    this.setData({ celebrationEvent: moment + ':' + this._celebrationSeq });
  },
  leadUnlockFromTools() { this.setData({ sessionToolsOpen: false }, () => this.leadUnlock()); },
  leadVerifyMemberTicketFromTools() { this.setData({ sessionToolsOpen: false }, () => this.leadVerifyMemberTicket()); },
  leadShowGroupCodeFromTools() { this.setData({ sessionToolsOpen: false }, () => this.leadShowGroupCode()); },
  leadBroadcastFromTools() { this.setData({ sessionToolsOpen: false }, () => this.leadBroadcast()); },
  leadSettleFromTools() { this.setData({ sessionToolsOpen: false }, () => this.leadSettle()); },
  // ---------- 剧情两段式:摘要卡 → 全屏叙事页 ----------
  // 只做外壳:文案与顺序都来自 buildJournal 已经算好的 journalCards / 既有节点数据,
  // 这里不参与排序,更不碰防剧透那套(未解锁段落在 wxml 层就没有展开入口)。
  openStory(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const node = this._nodeById(ds.id) || this._historyNodeById(ds.id);
    if (!node) return;
    this.setData({
      story: {
        show: true,
        title: node.name || '',
        step: ds.step || '',
        cover: node.imgUrl ? String(node.imgUrl).split(',')[0] : '',
        // 节点手记与章节正文同一条规矩:{名字} 在这也要替换(契约 §3.1 的「节点 story」)
        text: applyStoryVars(String(node.description == null ? '' : node.description), this._storyVars),
      },
    });
  },
  closeStory() { this.setData({ 'story.show': false }); },

  // 起飞过场已删除(用户裁决):过场层与其播放/关闭函数族一并退场

  // ---------- 旅程手记(原型 journalScreen:半屏 + 一行行 .kv) ----------
  openJournal() {
    this._endingEpoch = (this._endingEpoch || 0) + 1;
    this.setData({ showJournal: true, journalOut: false, journalDot: false, 'ending.show': false }, () => {
      this.buildJournal();
    });
  },
  openJournalFromTools() { this.setData({ sessionToolsOpen: false }, () => this.openJournal()); },
  /* 自由探索抽屉两行(原型 x-tools):我的权益 = 这趟已领到的券;
     这个主题是什么 = 主题正文那一屏(mode2 没有章节,openChapterFull 铺的就是主题介绍)。 */
  openRightsFromTools() { this.setData({ sessionToolsOpen: false }, () => this.goCouponWallet()); },
  openThemeFromTools() { this.setData({ sessionToolsOpen: false }, () => this.openChapterFull()); },

  /* 自由探索的地图模式(原型 x-run):把卡包让开,露出本页本来就有的地图台、读数卡与底栏 ——
     它们原先被 mode!=2 一律关掉。地图那套是按城市定向写的,自由探索只借它的「看」,
     不借「一场会话」:所以底栏没有中键(原型 freeBar 的原话)。 */
  closeJournal() { this._outThen('journal'); },

  // FE-19 入场码:动态核销码,5 分钟有效,过期重开即重签发。
  // 拆两层(用户裁决:核销≠优惠券页):ensureTicket 只取码;展示层各自决定开哪个面。
  ensureTicket() {
    const that = this;
    if (!this._isPlayAccountCurrent() || this.data.isPreview || this.data.isMock) return;             // 预览/mock 不出码
    const regId = this.data.regId;
    if (!regId) { this._explainMissingEntryQr(); return; }
    const owner = this._boundPlayOwner(), sessionKey = this._sessionKey();
    const login = captureLogin(app, wx);
    if (!loginCurrent(app, wx, login)) return;
    const sequence = this._ticketIssueSequence = (this._ticketIssueSequence || 0) + 1;
    const current = () => loginCurrent(app, wx, login) && this._isPlayAccountCurrent() && owner === this._playOwner
      && !this.data.isPreview && !this.data.isMock && this.data.regId === regId
      && sequence === this._ticketIssueSequence && sessionKey === this._sessionKey();
    this.setData({ ticket: { show: this.data.ticket.show, url: '', code: '', loading: true, expiresAt: 0 } });
    req('/api/verify/dyncode/issue', 'POST', { registrationId: regId }).then((res) => {
      if (!current()) return;
      if (String(res.code) === '200' && res.data) {
        if (seatChoice(res.data, regId)) {
          // 旧优惠券出码调用也走同一明确选席组件；不能把整单默认成第一席。
          that.setData({ 'ticket.loading': false, 'ticket.url': '', 'ticket.code': '' });
          that.openScene('qr-ticket', { registrationId: regId });
          return;
        }
        that.setData({ 'ticket.url': res.data.qrcodeUrl || '', 'ticket.code': res.data.code || '', 'ticket.loading': false, 'ticket.expiresAt': res.data.expiresAt || 0 });
      } else {
        that.setData({ 'ticket.loading': false });
        const msg = (res && res.msg) ? res.msg : '入场码生成失败';
        app.tips ? app.tips(msg) : cyToast(msg);
      }
    });
  },
  /** 优惠券页:只属于优惠券 —— 目标节点没配券就不开这页(没有「入场」兜底票)。
   *  入口在详情卡券行:目标节点 = 详情卡正开着的那个。 */
  openTicketFromSheet() {
    this._voucherNode = (this.data.sheet && this.data.sheet.node) || null;
    this.openVoucher();
  },

  // ================= v5.1/v5.2 新玩法(Figma 组件库 v5.1 node 45:248 / v5.2 node 64:304)=================
  // 玩法数据整块由服务端在节点上下发(node.playKit)。没下发时入口行和弹窗都不渲染,
  // 所以这套 UI 对既有节点是纯增量,不需要额外开关,也不会把没有玩法的站点变成有玩法。
  /** cy-advanced-game 每次拿到权威视图都会抛上来;v5.1 新玩法的 sheet 由本页渲染
   *  (那些壳是独立 sheet,套不进面板自己那层 sheet)。 */
  onAdvancedSession(e) {
    if (!this._isPlayAccountCurrent()) return;
    const view = e.detail || {};
    const active = this.data.advancedPlay;
    if (!active.show || !active.node
        || (view.nodeId != null && String(view.nodeId) !== String(active.node.nodeId))) return;
    const component = this._advancedGameComponent();
    const state = component && component.data && component.data.state;
    if (state && view.sessionId != null && state.sessionId != null && String(view.sessionId) !== String(state.sessionId)) return;
    if (state && view.version != null && state.version != null && String(view.version) !== String(state.version)) return;
    const previousKit = this._kit() || {};
    if (view.sessionId != null && previousKit.sessionId != null && String(view.sessionId) === String(previousKit.sessionId)
        && view.version != null && previousKit.version != null
        && Number.isFinite(Number(view.version)) && Number.isFinite(Number(previousKit.version))
        && Number(view.version) < Number(previousKit.version)) return;
    const context = { owner: this._boundPlayOwner(), active, component, epoch: this._playLoadEpoch,
      chapter: this.data.chapter, sessionKey: this._sessionKey() };
    this._advancedSessionContext = context;
    // scopeCurrent 不要求本回调仍是最新视图:同一会话的重复视图不应吞掉已排好的故事衔接。
    const scopeCurrent = () => this._isPlayAccountCurrent()
      && context.owner === this._playOwner && context.epoch === this._playLoadEpoch
      && context.sessionKey === this._sessionKey() && String((this.data.chapter || {}).id) === String((context.chapter || {}).id)
      && this.data.advancedPlay === active && active.show && this._advancedGameComponent() === component;
    const current = () => this._advancedSessionContext === context && scopeCurrent();
    if (this._applyR5AdvancedView(view)) return;
    if (this._themeChallengeNodeId === Number(active.node.nodeId)) {
      const kit = pickPlayKit(view, new Date());
      this.setData({ playKit: { show: !!kit, kit: kit && Object.assign(kit, { challenge: view.challenge || null, challengeShared: view.ownerType === 2 }), readyForBase: false } });
      this.refreshThemeBackpack();
      return; // 展示挑战玩法，但不接普通章节的继续/完成路径。
    }
    // 故事变量随权威会话视图一起下来(契约 §3.1):{名字} 的取值 = profile 答案 + 已完成 kit 的结果,
    // 由服务端拼成 map。与 /api/play/nodes 那份走同一套合并口径(见 _mergeStoryVars)。
    this._mergeStoryVars(view.vars);
    if (this._freshStoryGame && String(active.node.nodeId) === this._freshStoryGame) {
      this._freshStoryGame = '';
      // 退出前已经做完、只差「继续故事」:重进打开它时直接接上,不让玩家再点一次
      if (!this.data.isPreview && !this.data.isMock && canContinue(view)) setTimeout(() => { if (current()) this.continueStoryGame(); }, 400);
    }
    /* profile 提交成功后必须重拉一次 nodes:页面级 nodes 是读一次的快照,刚填的名字不会自己
       回到 _storyVars 里,章节里就还是 {name}。只认这一条上升沿,后续每个会话视图不重拉。 */
    const profileDone = !!(view.playKit && view.playKit.profile && view.playKit.profile.done);
    this._onKaccha(view, active.node.nodeId);
    if (profileDone && !this._profileStoryVarsRefreshed) {
      this._profileStoryVarsRefreshed = true;
      this._refreshStoryVarsFromNodes();
    }
    const previous = this._kit();
    /* 拍物成卡那一屏的「地点」:会话视图里只有 nodeId,主题名与节点名在本页手里 */
    const placeNode = this.data.advancedPlay && this.data.advancedPlay.node;
    const placeName = [this.data.topicName, placeNode && placeNode.name].filter(Boolean).join(' · ');
    const picked = this.data.isPreview && view.previewKit ? view.previewKit : pickPlayKit(Object.assign({}, view, { placeName }), new Date());
    const kit = dailySignRevealState(previous, picked);
    if (kit && kit.type === 'album' && !kit.done && kit.sessionId === previous.sessionId && kit.version === previous.version) kit.uploaded = !!previous.uploaded;
    if (!kit) { this.setData({ playKit: { show: false, kit: null } }); return; }
    /* 内嵌(契约 §1.5):不弹整屏那一层 —— 它已经长在故事流里了(见 openChapterFull 的
       kind:'kit' 段)。show 关掉、kit 留着,那一块绑的就是它。整屏 / 缺字段照旧。 */
    if (this.data.isPreview) {
      const remember = node => String(node.nodeId) === String(active.node.nodeId)
        ? Object.assign({}, node, { previewKit: kit, previewReadyForBase: view.readyForBase === true }) : node;
      this._routeNodeCatalog = (this._routeNodeCatalog || this.data.nodes).map(remember);
      this.setData({ nodes: this.data.nodes.map(remember) });
    }
    const inline = kit.present === 'inline' && this.data.chapterFull;
    const ready = canContinue(view);
    /* 分支走到结局时不自动接着走:结局那段话就是这一站的全部回报,0.9 秒就被翻走等于没看到
       (2026-10-27 生产实测:南京路结案三选一,选完直接跳进附录章节卡)。玩家读完点下面的「继续」。 */
    const advanceStory = this.data.chapterFull && this._storyOpenNode && ready && !this.data.playKit.readyForBase
      && kit.type !== 'branch';
    // 填了一半的草稿按「账号 + 局 + 站」存本地(建档 / 留言组件自取);试玩不存
    if (kit.type === 'profile') this._profileNodeId = String(active.node.nodeId);
    if ((kit.type === 'profile' || kit.type === 'note') && !this.data.isPreview && !this.data.isMock) kit.draftKey = storyResume.draftKey(this._runSessionScope(), active.node.nodeId);
    this.setData({ playKit: { show: !inline, kit, readyForBase: ready } });
    if (advanceStory) {
      const nodeId = String(active.node.nodeId);
      setTimeout(() => {
        const latest = this.data.advancedPlay;
        if (scopeCurrent() && latest.show && latest.node && String(latest.node.nodeId) === nodeId
            && this.data.playKit.readyForBase) this.continueStoryGame();
      }, this.data.reducedMotion ? 0 : 900);
    }
    /* 故事流正开着:就地补一块,别让玩家退出重进才看见。只认「还没铺过」这一条上升沿,
       否则每次会话视图(每个动作都来一次)都会重铺整章、把滚到一半的位置弹回开头。 */
    if (inline && this.data.chapterFull
        && !(this.data.chapterParas || []).some((p) => p.kind === 'kit'
          && String(p.nodeId) === String(this._playKitNodeId))) {
      this.openChapterFull();
      // 先清空再设:入口卡用的就是同一个 id,同值 setData 不会让 scroll-view 再对准一次,
      // 内嵌玩法的题干会停在入口卡当时的位置、被顶到顶栏底下(2026-09-23 整本试玩)。
      const anchor = 'chapter-game-' + this._playKitNodeId;
      const aim = () => {
        if (!current()) return;
        this.setData({ chapterGameAnchor: '' }, () => { if (current()) this.setData({ chapterGameAnchor: anchor }); });
      };
      aim();
      // 第一次是带动画的长距离滚动,途中上方段落还在重排(入场标记),实测会偏 95px 停在顶栏底下;
      // 动画结束后再对准一次(短距离,不会再偏)。ponytail: 固定 1200ms(实测 600ms 仍在重排),改动画要跟着改。
      clearTimeout(this._kitAnchorTimer);
      this._kitAnchorTimer = setTimeout(() => {
        if (current() && this.data.chapterFull && this.data.chapterGameAnchor === anchor) aim();
      }, 1200);
    }
  },

  /**
   * 两个来源的合并口径(契约 §3.1 的 vars 只有一个真源,但有两处回包):
   *   · 只认非空对象 —— 服务端取不到时整块不下发,空值/缺失**绝不能**把已有值清掉;
   *   · 逐键合并,新回包的键覆盖旧值(每份都是服务端当时的完整快照,新的更接近现在);
   *   · 两份各有的键都留着 —— 会话视图带 kit 结果,nodes 带 profile 答案,谁都不是全集。
   */
  _mergeStoryVars(vars) {
    if (!isRecord(vars) || !Object.keys(vars).length) return false;
    this._storyVars = Object.assign({}, this._storyVars || {}, vars);
    return true;
  },

  /** loadData/刷新回包 → vars。只做提取与合并,不重渲染(重渲染由调用点决定)。 */
  _applyStoryVars(payload) {
    // 咔哒那几句按站随 /nodes 回来(存档修复):重新进页面后故事流里照样在
    const voices = payload && payload.storyVoices;
    if (isRecord(voices)) {
      const next = Object.assign({}, this._storyVoices);
      Object.keys(voices).forEach((nodeId) => { next[nodeId] = kacchaLines(voices[nodeId]); });
      this._storyVoices = next;
    }
    return this._mergeStoryVars(payload && payload.vars);
  },

  /**
   * 咔哒:服务端把玩家的选项改写成了作者定的那一项(overrides 只在发生后下发)。
   * 那句话按站存下来,故事流重铺时接在玩法后面;声音 / 震动 / 提示只在「刚提交」那一下响 ——
   * 再进页面拿到同一份视图不重放。
   */
  _onKaccha(view, nodeId) {
    const profile = (view.playKit && view.playKit.profile)
      || (view.previewKit && view.previewKit.type === 'profile' ? view.previewKit : null);
    const hits = profile && Array.isArray(profile.overrides) ? profile.overrides : [];
    if (!hits.length) return;
    const key = String(nodeId);
    const fresh = !(this._storyVoices && this._storyVoices[key]);
    this._storyVoices = Object.assign({}, this._storyVoices, { [key]: kacchaLines(hits) });
    const prev = this._kit();
    if (!fresh || !prev || prev.type !== 'profile' || prev.done) return;
    const s = this._storySound();
    if (s) s.sfx('kaccha');
    if (!this.data.reducedMotion) wx.vibrateShort({ type: 'heavy' });
    cyToast('——咔哒。');
  },

  /** profile 提交成功后的补拉:只取 vars,不重跑 loadData(那会把首屏/章节/角色卡整套重演一遍)。 */
  _refreshStoryVarsFromNodes() {
    if (!this._isPlayAccountCurrent() || this.data.isPreview || this.data.isMock) return;
    if (!this.data.activityId && !this.data.topicId) return;
    const params = this._sessionParams();
    const sessionKey = this._sessionKey(params);
    const chapterId = String((this.data.chapter || {}).id || '');
    const loadEpoch = this._playLoadEpoch;
    const sequence = this._storyVarsReadSequence = (this._storyVarsReadSequence || 0) + 1;
    req('/api/play/nodes', 'GET', params).then((res) => { // 补拉
      if (!this._isPlayAccountCurrent() || sequence !== this._storyVarsReadSequence
          || loadEpoch !== this._playLoadEpoch || sessionKey !== this._sessionKey()
          || chapterId !== String((this.data.chapter || {}).id || '')) return;
      if (!(res.code == 200 || res.code == '200') || !res.data) return;
      if (!this._applyStoryVars(res.data)) return;
      // 章节屏正开着就地重铺一次,让刚填的名字立刻显形(measureChapter 会重新量几何)
      if (this.data.chapterFull) this.openChapterFull();
    }).catch(() => {});
  },

  continueStoryGame() {
    const game = this.selectComponent('#advancedGame');
    if (game && this.data.playKit.readyForBase) game.continueBase();
  },
  closePlayKit() {
    this._stopPlayKitAudio();
    if (this._storyOpenNode) {   // 玩家主动退出故事里的玩法:重进不再自动打开
      this._storyOpenNode = false;
      storyResume.patch(this._runSessionScope(), { openNodeId: '' });
    }
    this.setData({ 'playKit.show': false });
    if (this.data.chapterFull && this.data.advancedPlay.node) {
      if (this.data.playKit.readyForBase) this.continueStoryGame();
      else this.setData({ advancedPlay: { show: false, node: null }, playKit: { show: false, kit: null } });
      this.openChapterFull();
    }
  },

  /** 当前打开的玩法数据;没开玩法时给空对象,让调用方少写一遍空值判断 */
  _kit() { return (this.data.playKit && this.data.playKit.kit) || {}; },

  /** 该铺进故事流的那个内嵌 kit(契约 §1.5);不是内嵌 / 没有玩法 → null。
   *  呈现方式逐字读服务端会话视图的 present(pickPlayKit 搬进来):默认表只在服务端一份,
   *  客户端不重推 —— 字段缺失时按 fullscreen,存量模板行为逐字不变。 */
  _inlineKit() {
    const kit = this._kit();
    return kit.present === 'inline' ? kit : null;
  },

  /** 服务端在照片回执里给了 AI 参考分,就把它挂到这个节点上。
   *  稿写明「最终以商家审核为准」——所以它不参与完成判定,只是回看这一站时的参考。
   *  没给分就没有这张卡,不造一个占位分。 */
  _applyAiScore(nodeId, data) {
    const ai = data && data.aiScore;
    if (!ai || !(Number(ai.score) > 0)) return;
    const card = {
      score: Number(ai.score),
      comment: ai.comment || '',
      note: ai.note || '最终以商家审核为准',
    };
    const patch = (n) => (n.nodeId === nodeId ? Object.assign({}, n, { aiScore: card }) : n);
    if (this._routeNodeCatalog) this._routeNodeCatalog = this._routeNodeCatalog.map(patch);
    // 两次字面量 setData 而不是拼一个动态对象:U4 门禁静态核对顶层字段消费,
    // 动态键名它判不了,只能进基线债 —— 这里本来就只有两个固定字段,不值当欠那笔。
    this.setData({ nodes: this.data.nodes.map(patch) });
    // 卡正开着这一站时同步一份,否则要等下次打开才看得到
    if (this.data.sheet && this.data.sheet.node && this.data.sheet.node.nodeId === nodeId) {
      this.setData({ 'sheet.node.aiScore': card });
    }
  },

  onPlayKitAction(e) {
    const evt = e.detail || {};
    const detail = evt.detail || {};
    if (this.data.isPreview && !['close', 'requestclose', 'namechange', 'toggle', 'audio'].includes(evt.action)) {
      const game = this.selectComponent('#advancedGame');
      if (!game || !game.previewKitAction(evt)) cyToast('预览中可跳过此玩法');
      return;
    }
    switch (evt.action) {
      case 'retry': {
        const game = this.selectComponent('#advancedGame');
        const state = game && game.data && game.data.state;
        const kit = this._kit();
        if (!state || !kit.terminal || kit.canRetry !== true || state.canRetry !== true
            || String(state.sessionId) !== String(kit.sessionId) || state.version !== kit.version) return;
        return game.retry();
      }
      case 'continue':
        if (evt.type === 'qa' && this._kit().type === 'qa' && this.data.playKit.readyForBase) {
          this.continueStoryGame();
        }
        return;
      // 纯本地:关闭 / 改草稿 / 切筛选 / 播放器 —— 这些没有服务端可裁决的东西
      case 'close':
      case 'requestclose':
        this.closePlayKit();
        return;
      case 'namechange':
        this.setData({ 'playKit.kit.value': detail.value || '' });
        return;
      case 'toggle':
      case 'audio':
        // 听音答题(qa)与扫码语音回复(scan)的播放按钮抛的是 'audio',
        // 音乐角抛 'toggle' —— 两个名字同一件事,以前只有 toggle 有人接。
        this._togglePlayKitAudio(!!detail.playing);
        return;
      case 'refresh':
        if (evt.type !== 'typein' || !this._kit().r5) { this._submitPlayKitAction(evt.action, detail); return; }
      case 'open':
        // 时段限定到点:组件不自己开门(本地时钟不是权威),回 /state 问一次,
        // 权威视图回来后重新 pickPlayKit —— 门真开了就会弹该节点真正的玩法。
        { const adv = this.selectComponent('#advancedGame');
          if (adv) adv.refreshState(); }
        return;
      case 'giveup':
        // 沉默点单裁决为「不判定通关」(mechanicsReady 注释),认输没有服务端可记,
        // 收尾这一屏就是它的全部语义 —— 以前它掉进 default 被静默吞掉。
        this.closePlayKit();
        return;
      case 'subscribe':
        this._subscribePlayKit();
        return;
      case 'slowstart':
      case 'slowclaim':
        // 跨日任务的开始与领取都要服务端裁决(日期在服务端算),走同一条委托通道
        this._submitPlayKitAction(evt.action, detail);
        return;
      default:
        // 其余都是要服务端裁决的(作答/提交/认输/刷新/收签/CTA)
        this._submitPlayKitAction(evt.action, detail);
    }
  },

  /**
   * 需要服务端裁决的动作:统一委托 cy-advanced-game 发。
   * 不在本页自己拼请求 —— 幂等键、CAS 版本、unknown 回读那一整套都在组件里,
   * 页面再发一份就会和组件的 version 漂移,漂移之后每次提交都撞"状态已更新"。
   */
  _isTypeInStartCurrent(pending) {
    const context = pending && pending.context, kit = this._kit();
    return !!context && this._typeInStart === pending && this._isPlayAccountCurrent()
      && context.owner === this._playOwner && context.epoch === this._playLoadEpoch
      && context.sessionKey === this._sessionKey() && this.data.advancedPlay === context.active
      && context.active.show && this._advancedGameComponent() === context.component
      && kit.type === 'typein' && pending.sessionId === kit.sessionId;
  },
  _submitPlayKitAction(action, detail) {
    const kit = this._kit();
    if (kit.type === 'album' && ['shoot', 'submit'].includes(action)) {
      submitAlbum(this, action, detail, cyToast); return;
    }
    if (kit.type === 'branch' && action === 'choose' && kit.feedback) {
      const game = this.selectComponent('#advancedGame');
      const visible = this.data.advancedPlay.show && (this.data.playKit.show || this.data.chapterFull && kit.present === 'inline');
      if (!visible || !game || !feedbackWritable(game.data.state, kit)) { cyToast('这段反馈仅可阅读'); return; }
    }
    /* 拍照问答(qa:shoot)不在服务端动作表里(playkit-view 明确注释:放进去会被当成一步直发,
       而组件手里只有出了手机就不存在的临时路径)。所以必须在 serverAction 门禁**之前**分派,
       否则被 `if (!name) return` 拦成死分支 —— 玩家拍完照既不报错也永远不提交。
       ⚠️ 同时必须限定 qa:shoot 只有 qa 组件是合法来源(scan 等其它 kit 不发 shoot),
       否则伪造一个 shoot 就能绕过类型门禁直接走照片上传/提交。
       《预制人生》的 photoCheck 与建档头像是同一条路:拍/选之后先上传,再提交或写回 kit。 */
    if (action === 'shoot') {
      if (kit.type === 'qa') this._submitKitPhoto(detail, {});
      else if (kit.type === 'photocheck') {
        // 判定全在服务端(契约 §2.2):只报图片地址。客户端本地算分那套已删,别把 score 塞回来
        this._submitKitPhoto(detail, {
          name: 'SUBMIT_PHOTO_CHECK',
          payload: (url) => ({ imageUrl: url }),
        });
      }
      return;
    }
    if (action === 'avatar') {
      if (kit.type === 'profile') this._submitKitPhoto(detail, { avatar: true });
      return;
    }
    const name = serverAction(kit.type, action);
    if (!name) return;                       // 不在册的动作一律不发
    /* 计步:walk 屏发的是 sync(同步微信运动)与 claim(走够了落章),都要走
       wx.login → getWeRunData 拿密文这条路,不能直接 action 空手发。
       ⚠️ 早先只认 'refresh'(旧 steps 屏的事件名)—— 换屏之后那两个动作会掉进
       下面的通用分支,发出去一个没有 encryptedData 的 SUBMIT_STEPS,服务端解不开。 */
    if (kit.type === 'walk' || action === 'refresh') { this._submitSteps(); return; }
    /* 拍照问答:照片得先上传拿到地址,再连地址一起提交。
       组件手里只有临时路径 —— 那个路径出了这台手机就不存在,发给服务端等于发了个空。 */
    if (action === 'shoot') { this._submitQaPhoto(detail); return; }
    const game = this.selectComponent('#advancedGame');
    if (!game) { cyToast('玩法未就绪，请退出后重进'); return; }
    /* payload 要过一次翻译:组件按玩家看到的东西命名,服务端按判定要用的东西命名。
       直接把 detail 发过去,服务端读不到字段会按 0 判 —— 不报错,只是永远不通过。 */
    const payload = serverPayload(kit.type, action, detail);
    if (kit.type === 'typein' && action === 'start') {
      if (!this._isPlayAccountCurrent() || !this.data.advancedPlay.show) return;
      const context = { owner: this._boundPlayOwner(), epoch: this._playLoadEpoch,
        sessionKey: this._sessionKey(), active: this.data.advancedPlay, component: game };
      this.setData({ 'playKit.kit.startConfirmed': false });
      this._typeInStart = { sessionId: kit.sessionId, context, promise: Promise.resolve(game.action(name, payload)) };
      const pending = this._typeInStart;
      pending.promise.then(ok => {
        if (!this._isTypeInStartCurrent(pending)) return;
        if (ok === true) this.setData({ 'playKit.kit.startConfirmed': true });
        else if (!this._kit().startConfirmed && !(game.data || {}).unknown && !(game.data || {}).acting) {
          this.onAdvancedActionFail({ detail: { action: name, sessionId: pending.sessionId } });
        }
      });
      return;
    }
    if (kit.type === 'typein' && action === 'submit' && !kit.startConfirmed) {
      const pending = this._typeInStart;
      if (this._isTypeInStartCurrent(pending) && !pending.submitQueued && !(game.data || {}).unknown) {
        pending.submitQueued = true;
        pending.promise.then(() => {
          if (!this._isTypeInStartCurrent(pending)
              || !this.data.advancedPlay.show || !this._kit().startConfirmed || (game.data || {}).unknown || (game.data || {}).acting) return;
          game.action(name, payload);
        });
        return;
      }
      cyToast('开始计时还未确认，请先确认结果');
      return;
    }
    game.action(name, payload);
  },

  /**
   * 拍照族的两步走:先上传,再提交地址(qa 拍照题 / photoCheck / 建档头像共用这一条)。
   *
   * <p>两步都可能失败,而且要分开说:上传失败是「照片没传上去」,提交失败是「传上去了但没记上」。
   * 合成一句「提交失败」的话,玩家不知道要不要重拍。
   *
   * <p>cfg.name 给服务端动作名与 payload 组装;cfg.avatar 为真时不发动作,只把地址写回 kit ——
   * 头像在「提交登记」那一步才随 answers 一起走。
   */
  /** 拍物成卡那一屏在等结果:告诉它这一张没交上去,回到取景(页面计数单调递增,组件只认比见过的大)。 */
  _notifyPhotoFail() {
    const kit = this._kit();
    if (!kit || kit.type !== 'photocheck' || kit.mode !== 'CARD') return;
    this._photoFailSeq = (this._photoFailSeq || 0) + 1;
    this.setData({ 'playKit.kit.photoFailSeq': this._photoFailSeq });
  },

  /** 玩法组件说「这一步没提交成功」(被拒 / 状态已更新 / 回读说没写进去 / 回读也失败)。
   *  只有拍照提交会让那一屏在转圈等,别的动作失败由玩法组件自己的错误提示收尾。 */
  onAdvancedActionFail(e) {
    const detail = (e && e.detail) || {};
    const action = detail.action;
    const kit = this._kit();
    if (action === 'SUBMIT_QA' && kit.type === 'qa' && kit.judge === false
        && !kit.finished && !kit.passed
        && (detail.sessionId == null || String(detail.sessionId) === String(kit.sessionId))) {
      const game = this.selectComponent('#advancedGame');
      if (game && !game.data.unknown) this.setData({ 'playKit.kit.failureSeq': (kit.failureSeq || 0) + 1 });
    }
    if (action === 'CHOOSE' && kit.type === 'branch' && kit.feedback
        && (detail.sessionId == null || String(detail.sessionId) === String(kit.sessionId))) {
      const game = this.selectComponent('#advancedGame');
      if (game && !game.data.unknown) this.setData({ 'playKit.kit.failureSeq': (kit.failureSeq || 0) + 1 });
    }
    if (!this._isPlayAccountCurrent() || !this.data.advancedPlay || !this.data.advancedPlay.show) return;
    if (action !== 'START_CHALLENGE' && action !== 'SUBMIT_PHOTO_CHECK') return;
    const currentGame = this._advancedGameComponent();
    const state = currentGame && currentGame.data && currentGame.data.state;
    // Existing producer emits sessionId for both action failure and unknown-result readback failure.
    if (detail.sessionId == null || kit.sessionId == null || !state || state.sessionId == null
        || String(detail.sessionId) !== String(kit.sessionId) || String(detail.sessionId) !== String(state.sessionId)) return;
    if (action === 'START_CHALLENGE' && kit.type === 'typein') {
      if (detail.sessionId != null && String(detail.sessionId) !== String(kit.sessionId)) return;
      const game = this.selectComponent('#advancedGame');
      // unknown 没有确定结果，保留核对入口，不允许再次开表。
      if (game && game.data.unknown) return;
      if (kit.startConfirmed) return;
      this._typeInStart = null;
      this._typeInFailSeq = (this._typeInFailSeq || 0) + 1;
      this.setData({ 'playKit.kit.startFailureSeq': this._typeInFailSeq });
    }
    if (action === 'SUBMIT_PHOTO_CHECK') this._notifyPhotoFail();
  },

  _submitKitPhoto(detail, cfg) {
    if (!this._isPlayAccountCurrent()) return;
    const path = (detail && detail.tempFilePath) || '';
    if (!path) return;
    const option = cfg || {};
    const game = this.selectComponent('#advancedGame');
    const kitNow = this._kit();
    /* 拍物成卡那一屏自己有「处理中」,不再盖全屏 loading;失败时那一屏收不到回包,
       要靠 kit.photoFailSeq 通知它回到取景(页面计数单调递增,组件只认比见过的大)。 */
    const cardMode = !!(kitNow && kitNow.type === 'photocheck' && kitNow.mode === 'CARD');
    const fail = (msg) => {
      cyToast(msg);
      if (cardMode) this._notifyPhotoFail();
    };
    if (!game) { fail('玩法未就绪，请退出后重进'); return; }
    // 双击锁:同一次拍照上传在途时再收到 shoot 直接忽略,避免起第二个必被 advanced-game 静默丢掉的请求。
    if (this._kitPhotoUploading) return;
    const kit = this._kit();
    const state = (game.data && game.data.state) || null;
    const sameVersion = (a, b) => a != null && b != null && String(a) === String(b);
    if (!state || !state.sessionId || !kit.sessionId
        || state.sessionId !== kit.sessionId || !sameVersion(state.version, kit.version)) {
      fail('玩法状态不完整，请重进节点');
      return;
    }
    /* ★ 业务身份 + 题目版本隔离:照片是异步上传的,回来时必须仍是**同一 session、同一版本**
       (页面 kit 与 advanced-game 权威 state 都要对得上)。否则玩家在同一页面切到别的节点/会话后,
       旧回包会经 game.action 把上一题的照片提交到下一题/下一个会话。 */
    const nodeIdentity = () => [((game.data || {}).node || {}).nodeId, ((game.data || {}).state || {}).nodeId,
      (this._kit() || {}).nodeId, ((this.data.advancedPlay || {}).node || {}).nodeId]
      .map(id => id == null ? '' : String(id)).join(':');
    const question = { sessionId: state.sessionId, version: state.version, nodeId: nodeIdentity() };
    const intent = { owner: this._boundPlayOwner(), login: this._capturePlayLoginContext(), sessionKey: this._sessionKey(),
      epoch: this._playLoadEpoch, active: this.data.advancedPlay, game, settled: false, loading: false };
    this._kitPhotoIntent = intent;
    const contextCurrent = () => {
      const login = this._capturePlayLoginContext();
      return this._isPlayAccountCurrent() && intent.owner === this._playOwner
        && intent.login.authorization === login.authorization && intent.login.generation === login.generation
        && intent.sessionKey === this._sessionKey() && intent.epoch === this._playLoadEpoch
        && this.data.advancedPlay === intent.active && this._advancedGameComponent() === game;
    };
    const sameQuestion = () => {
      const currentState = (game.data || {}).state, currentKit = this._kit();
      return currentState && currentState.sessionId === question.sessionId
        && sameVersion(currentState.version, question.version) && currentKit.sessionId === question.sessionId
        && sameVersion(currentKit.version, question.version) && nodeIdentity() === question.nodeId;
    };
    const settle = () => {
      if (intent.settled) return false;
      intent.settled = true;
      if (this._kitPhotoIntent === intent) { this._kitPhotoIntent = null; this._kitPhotoUploading = false; }
      if (intent.loading) { intent.loading = false; cyLoading.hide(); }
      return true;
    };
    this._kitPhotoUploading = true;
    /* ★ 绑页面生命周期:上传中途玩家返回的话,不绑会留下三样东西 ——
       转不掉的遮罩、取消不了的请求、对着已销毁组件调的 game.action。
       写法与 app.js 里既有的两处上传一致(attach + isAborted 双守卫)。 */
    const operation = app.createPageBoundOperation();
    operation.onAbort(() => { settle(); });
    if (!cardMode) cyLoading.show('正在上传');
    intent.loading = !cardMode;
    const upload = () => operation.attach(app.getUploadClient().uploadAll([path], {
      // 组件拍照回调带了真实 size 就交给共享入口按生产 10MB 预检:超限不发起 wxUploadFile,
      // 直接给「压缩/换文件」可操作文案;size 未知(旧组件/拿不到)按 undefined 放行,由服务端拦截。
      fileSizes: [detail && detail.size],
      mapResult: option.name === 'SUBMIT_PHOTO_CHECK' ? data => ({ url: data.url,
        photoReference: (typeof data.assetId !== 'number' || Number.isSafeInteger(data.assetId))
          && /^[1-9][0-9]{0,18}$/.test(String(data.assetId || '')) ? 'media-asset:' + data.assetId : '' }) : undefined,
      onDone: (r) => {
        const owned = this._kitPhotoIntent === intent && contextCurrent();
        if (!settle()) return;
        if (operation.isAborted()) return;
        operation.finish();
        if (!owned) return;
        const uploaded = r && r.ok && r.results && r.results[0];
        const url = option.name === 'SUBMIT_PHOTO_CHECK' ? uploaded && uploaded.photoReference : uploaded;
        const currentGame = this.selectComponent('#advancedGame');
        if (!sameQuestion()) {
          fail('题目已变化，上一张照片未提交');
          return;
        }
        if (!url) {
          fail((r && r.failures && r.failures[0] && r.failures[0].msg) || '照片没传上去');
          return void (option.onUploadFailed && option.onUploadFailed((r && r.failures && r.failures[0]) || {}));
        }
        // advanced-game 正在 acting/unknown 时会静默 no-op:这里不装成功,给一句可恢复的实话。
        if (currentGame.data.acting || currentGame.data.unknown) {
          fail('确认中，照片未提交，请稍后再试');
          return;
        }
        if (option.avatar) { this.setData({ 'playKit.kit.avatarUrl': url }); return; }
        // cfg 缺省 = qa 拍照题那条老路(不带 name/payload 时按它提交),历史行为逐字不变
        const actionName = option.name || 'SUBMIT_QA';
        const payload = option.payload ? option.payload(url) : { imageUrl: url };
        if (option.onUploaded) option.onUploaded(url);
        currentGame.action(actionName, payload);
      },
    }));
    if (option.name === 'SUBMIT_PHOTO_CHECK') {
      const consentCurrent = () => !operation.isAborted() && this._kitPhotoIntent === intent && contextCurrent() && sameQuestion();
      app.requestSeparateConsent('photo', consentCurrent).then(() => {
        if (!consentCurrent()) { settle(); operation.finish(); return; }
        upload();
      }).catch(error => {
        const owned = consentCurrent();
        settle();
        if (operation.isAborted()) return;
        operation.finish(); if (owned) fail(error.message);
      });
    } else upload();
  },

  // ================= R14 旅程检定(入口在 encounter.allowedActions,不在 playKit) =================
  /**
   * 节点卡打开时问一次 encounter:服务端只在「已到店 && 未锁定 && 该节点旅程块 check.enabled」
   * 时才把 'check' 放进 allowedActions —— 未到店时这里拿到的是空数组,不弹任何东西。
   * ⚠️ 探测失败一律静默:检定不是通关闸,探测不成功不该打断任何已有路径(打卡/任务/导航都不受影响)。
   */
  _probeJourneyCheck(node) {
    if (!this._isPlayAccountCurrent() || this.data.isPreview || this.data.isMock || !node || node.done) return;
    if (!this.data.topicId) return;
    const current = this.data.journeyCheck;
    if (current.show && current.nodeId === node.nodeId) return;
    const epoch = (this._checkProbeEpoch || 0) + 1;
    this._checkProbeEpoch = epoch;
    const params = { topicId: this.data.topicId, activityId: this.data.activityId || 0, nodeId: node.nodeId };
    const loadEpoch = this._playLoadEpoch;
    req('/api/play/encounter', 'GET', params, undefined, true).then((r) => {
      const sheet = this.data.sheet || {};
      const currentNode = (this.data.nodes || []).find(item => String(item.nodeId) === String(node.nodeId));
      if (!this._isPlayAccountCurrent() || epoch !== this._checkProbeEpoch || loadEpoch !== this._playLoadEpoch
          || params.topicId !== this.data.topicId || params.activityId !== (this.data.activityId || 0)
          || !sheet.show || !sheet.node || String(sheet.node.nodeId) !== String(node.nodeId)
          || !currentNode || currentNode.done) return;
      if (!(r.code == 200 || r.code == '200') || !r.data) return;
      if (r5PlayerRuntime.isR5(r.data)) {
        if ((r.data.allowedActions || []).includes('check')) this.setData({
          'sheet.show': false, journeyCheck: { show: false }, advancedPlay: { show: true, node },
        });
        return;
      }
      const kit = pickJourneyCheck(r.data);
      if (!kit) return;
      this.setData({ journeyCheck: Object.assign({ show: true, nodeId: node.nodeId, receipt: null }, kit) });
      this._restoreJourneyCheck();
      if (kit.present === 'inline' && !kit.blocked) {
        this.setData({ 'sheet.show': false });
        this.openChapterFull();
      }
    }).catch(() => {});
  },

  _isJourneyCheckIntentCurrent(intent) {
    const kit = this.data.journeyCheck || {};
    return !!intent && this._isPlayAccountCurrent() && this._checkWriteIntent === intent
      && intent.loadEpoch === this._playLoadEpoch && kit.show
      && String(intent.payload.topicId) === String(this.data.topicId)
      && String(intent.payload.activityId) === String(this.data.activityId || 0)
      && String(intent.payload.nodeId) === String(kit.nodeId)
      && intent.payload.checkId === kit.checkId
      && String(intent.payload.sessionId) === String((this._routeState || {}).sessionId);
  },

  onJourneyCheckAction(e) {
    if (!this._isPlayAccountCurrent()) return;
    const action = (e.detail && e.detail.action) || '';
    if (this.data.journeyCheck.r5) return this._r5CheckAction(action);
    return this._legacyCheckAction(action);
  },

  /** 检定屏退出。结算前后都能关 —— 失败也推进是产品口径,这里不设任何通关闸。 */
  closeJourneyCheck() {
    this._checkWriteIntent = null; this._checkActing = false;
    const inline = this.data.journeyCheck.present === 'inline';
    if (this.data.journeyCheck.r5) {
      const pending = this.data.journeyCheck.receipt && this.data.journeyCheck.receipt.pending;
      this._r5CheckView = null;
      this.setData({ advancedPlay: { show: false, node: null }, themeBackpackOpen: !!pending, themeBackpackTab: 'game' });
    }
    this.setData({ 'journeyCheck.show': false });
    if (inline && this.data.chapterFull) {
      const chapterParas = (this.data.chapterParas || []).filter((p) => p.kind !== 'journeyCheck')
        .map((p, i) => Object.assign({}, p, { i }));
      this.setData({ chapterParas }, () => this.measureChapter());
    }
  },

  /**
   * 计步提交。微信运动只给密文,而且**必须先 wx.login 再 getWeRunData**
   * (session_key 要新鲜,否则服务端解不开)—— 这个顺序不能调换。
   * 前端不解密也不上报步数本身,只把密文原样转交。
   */
  _submitSteps() {
    if (!this._isPlayAccountCurrent()) return;
    if (this.data.zeroHpView) {
      this.setData({ themeBackpackOpen: true, themeBackpackTab: 'game' });
      return this.syncZeroHpSteps();
    }
    const game = this.selectComponent('#advancedGame');
    if (!game) { cyToast('玩法未就绪，请退出后重进'); return; }
    const sessionKey = this._sessionKey();
    const kit = this._kit();
    const nodeKey = () => String(this._playKitNodeId || (this.data.advancedPlay && this.data.advancedPlay.node
      && this.data.advancedPlay.node.nodeId) || '');
    const nodeId = nodeKey();
    const sequence = this._stepsSubmitSequence = (this._stepsSubmitSequence || 0) + 1;
    const isCurrent = () => this._isPlayAccountCurrent() && sequence === this._stepsSubmitSequence
      && sessionKey === this._sessionKey() && nodeId === nodeKey()
      && this._kit() === kit && this._advancedGameComponent() === game;
    wx.login({
      success: (login) => {
        if (!isCurrent()) return;
        if (!login || !login.code) { cyToast('微信登录态获取失败'); return; }
        wx.getWeRunData({
          success: (run) => {
            if (!isCurrent()) return;
            game.action('SUBMIT_STEPS', { code: login.code, encryptedData: run.encryptedData, iv: run.iv });
          },
          fail: () => { if (isCurrent()) cyToast('需要授权微信运动才能计步'); },
        });
      },
      fail: () => { if (isCurrent()) cyToast('微信登录态获取失败'); },
    });
  },
  /** 治愈音乐角:音频归页面持有,弹窗收起后还能继续放(这一站的玩法就是"听完") */
  _togglePlayKitAudio(shouldPlay) {
    const kit = this._kit();
    if (!kit.audioUrl) { cyToast('这一站还没有配曲目'); return; }
    if (!this._kitAudio) {
      this._kitAudio = audioSession.register(wx.createInnerAudioContext(), { kind: 'kit' });
      this._kitAudio.onPlay(() => audioSession.keepAwake('kitAudio', true));
      this._kitAudio.onPause(() => audioSession.keepAwake('kitAudio', false));
      this._kitAudio.onStop(() => audioSession.keepAwake('kitAudio', false));
      this._kitAudio.onTimeUpdate(() => {
        this.setData({
          'playKit.kit.position': Math.floor(this._kitAudio.currentTime || 0),
          'playKit.kit.duration': Math.floor(this._kitAudio.duration || 0),
        });
      });
      this._kitAudio.onEnded(() => {
        // 只收播放态:音乐角明确**不判定通关**(2026-08-26 拍板,utils/playkit-view.js:318)。
        // 这里原来还跟一句 _submitPlayKitAction('finished'),但 ACTION_OF 没有 musiccorner:finished,
        // 组件也只发 toggle/close —— 那是一次谁也收不到的空提交(审查 #24)。
        audioSession.keepAwake('kitAudio', false);
        this.setData({ 'playKit.kit.playing': false });
      });
      this._kitAudio.onError(() => {
        audioSession.keepAwake('kitAudio', false);
        this.setData({ 'playKit.kit.playing': false });
        cyToast('曲目播放失败');
      });
    }
    if (shouldPlay) {
      if (this._kitAudio.src !== kit.audioUrl) this._kitAudio.src = kit.audioUrl;
      this._kitAudio.play();
    } else {
      this._kitAudio.pause();
    }
    this.setData({ 'playKit.kit.playing': shouldPlay });
  },

  _stopPlayKitAudio() {
    if (!this._kitAudio) return;
    this._kitAudio.stop();
    this.setData({ 'playKit.kit.playing': false });
  },


  /** 时段限定玩法的开播提醒:模板 id 由服务端下发,前端不硬编码模板 */
  _subscribePlayKit() {
    const kit = this._kit();
    const tmplId = kit.subscribeTmplId;
    if (!tmplId) { cyToast('提醒暂不可用'); return; }
    wx.requestSubscribeMessage({
      tmplIds: [tmplId],
      success: (res) => {
        if (res[tmplId] === 'accept') {
          this.setData({ 'playKit.kit.subscribed': true });
          this._submitPlayKitAction('subscribe', { tmplId });
          return;
        }
        cyToast('没有开启提醒');
      },
      fail: () => cyToast('提醒开启失败'),
    });
  },

  openVoucher() {
    const node = this._voucherNode || this.data.nextNode;
    const hasCoupon = (node && node.coupon) || (this._themeReward && this._themeReward.finishCoupon);
    if (!hasCoupon) { cyToast('本站没有优惠券'); return; }
    this._syncVoucherView();
    this.ensureTicket();
    this.setData({ 'voucher.show': true });
  },
  openTicketFromTools() { this.setData({ sessionToolsOpen: false }, () => this.openEntryQr()); },
  closeVoucher() { this._voucherNode = null; this.setData({ 'voucher.show': false }); },
  closeFragment() { this.setData({ frag: { show: false, text: '', step: '' } }); },
  buildJournal() {
    const mode = this.data.mode, nodes = this.data.nodes;
    let order = nodes.slice();
    const branch = this._routeState.routeMode === 'BRANCH_GRAPH';
    if (branch) order = orderedJourneyNodes(nodes, this._routeState);
    else if (mode === 1) order.sort((a, b) => a.sortId - b.sortId);
    else {
      // P0 剧情成书:已完成按"我的完成顺序"(doneAt 后端 checkinTime,本地完成即时补),未完成殿后保持防剧透
      const doneArr = nodes.filter((n) => n.done).sort((a, b) => (a.doneAt || 0) - (b.doneAt || 0));
      order = doneArr.concat(nodes.filter((n) => !n.done));
    }
    const focus = branch ? resolveNextRouteNode(order, this._routeState)
      : (mode === 1 ? order.find((n) => !n.done) : nodes.filter((n) => !n.done)[0]);
    const fpos = focus ? order.findIndex((n) => n.nodeId === focus.nodeId) : order.length;
    const cards = order.map((n, i) => {
      /* 只留 .kv 行真会用到的:key(wx:key)/nodeId(点进全文)/locked(不剧透)/kvK|kvT|kvS。
         gap/scale/ty/blur/opacity/justWrote/canGo 是已删的文字流留下的,一起清掉,
         免得成为「写了没人读」的字段(U4 对计算属性名是盲的,靠它抓不出来)。 */
      const seg = { key: n.nodeId, nodeId: n.nodeId };
      const paras = String(n.description).split('\n');
      if (n.done) {
        // mode=2:标签用"我的第 K 步"(K=完成顺序),路线变成自己的故事线;mode=1 保持原站序
        seg.label = mode === 1 && !branch ? ('第 ' + n.num + ' 站 · 已抵达') : ('我的第 ' + (i + 1) + ' 步 · ' + n.name);
        seg.paras = paras;
      } else if (focus && n.nodeId === focus.nodeId) {
        seg.label = '此刻'; seg.paras = paras;
      } else if (mode === 1) {
        const fr = i - fpos - 1;
        seg.label = '前路未知'; seg.locked = true;
        seg.paras = fr <= 0 ? paras : ['░░░░░░░░░ ░░░░░ ░░░░░░░░ ░░░░'];
      } else {
        seg.label = n.name; seg.paras = ['这一页尚未书写。你的脚步还没抵达这里。'];
      }
      /* 原型 .kv 一行三格:左边一格标签、右边标题与一句。
         label 形如「第 3 站 · 已抵达」/「我的第 2 步 · 昼夜咖啡」—— 按「 · 」劈开正好，
         劈不开(「此刻」「前路未知」)就整句当左格、标题回落到节点名。
         正文只取第一段并夹一行:全文仍在点进去那件半屏里,这儿是目录不是正文。 */
      const parts = String(seg.label || '').split(' · ');
      seg.kvK = parts[0] || '';
      seg.kvT = parts.length > 1 ? parts.slice(1).join(' · ') : (n.name || '');
      seg.kvS = String((seg.paras && seg.paras[0]) || '').slice(0, 40);
      return seg;
    });
    /* 规则16 历史履约:普通下架的已完成站以只读卡片续在手记末尾(完成时间序);
       不参与上面的主线 order/focus/locked 计算,也没有任何挑战/发奖入口。 */
    const historyCards = (this._historyNodes || []).slice()
      .sort((a, b) => (a.doneAt || 0) - (b.doneAt || 0))
      .map((n) => {
        const paras = String(n.description == null ? '' : n.description).split('\n');
        const label = '已下架 · ' + (n.name || '');
        const parts = label.split(' · ');
        return {
          key: 'history-' + n.nodeId,
          nodeId: n.nodeId,
          history: true,
          label,
          paras,
          kvK: parts[0] || '',
          kvT: parts.length > 1 ? parts.slice(1).join(' · ') : (n.name || ''),
          kvS: String(paras[0] || '').slice(0, 40),
        };
      });
    this.setData({
      journalCards: cards.concat(historyCards),
      nextChapterText: this.data.allDone ? '你已写满本章。翻过这一页，新的街巷正在等你。' : '走完本章坐标，下一章的扉页才会在流的尽头显形。'
    });
  },
  /* ⚠️ onJournalTouchMove / onJournalScroll(滚动沉降:越靠屏幕中央越清晰)
     随文字流一起退场 —— 原型的手记是一张静态列表,没有对焦/模糊/缩放这一层。
     它们留下的 _segGeom / _jscroll0 / _jbusy 也一并删掉,免得成为读不到的死状态。
     goToNode(手记行里的「前往此站」)同批退场:同一个动作在节点半屏里还有
     (「去这家 / 我已到达此站」),不是丢了入口。 */

  // ---------- 通关足迹卡 ----------
  loadEnding() {
    if (!this._isPlayAccountCurrent() || this.data.mode !== 2 || this.data.isPreview || this.data.isMock) return;
    const that = this;
    const epoch = this._endingEpoch = (this._endingEpoch || 0) + 1;
    const params = this._sessionParams();
    const sessionKey = this._sessionKey(params);
    const routeSessionId = String((this._routeState || {}).sessionId || '');
    const isCurrent = () => that._isPlayAccountCurrent() && epoch === that._endingEpoch && that.data.mode === 2
      && sessionKey === that._sessionKey()
      && routeSessionId === String((that._routeState || {}).sessionId || '');
    this.setData({ endingError: false });
    req('/api/play/ending', 'GET', params).then((r) => {
      if (!isCurrent()) return;
      if ((r.code == 200 || r.code == '200') && r.data) {
        const opener = typeof r.data.opener === 'string' ? r.data.opener : '';
        const fragments = Array.isArray(r.data.fragments) ? r.data.fragments : [];
        const hasVisibleContent = opener.trim() !== '' || fragments.some((fragment) => fragment
          && (String(fragment.name || '').trim() !== '' || String(fragment.text || '').trim() !== ''));
        that.setData({ ending: { show: hasVisibleContent, opener, fragments }, endingError: false });
      } else {
        that.setData({ endingError: true });
      }
    }).catch(() => {
      if (!isCurrent()) return;
      that.setData({ endingError: true });
    });
  },
  retryEnding() { this.loadEnding(); },
  /**
   * 真玩家的结局 + 「这一生的档案」(2026-09-24):本局完成后先问服务端走到了哪个结局,
   * 盖在完成页之前;点「继续」才进完成页。主题没配结局 / 拿不到 → 直接进完成页,不挡路。
   */
  _loadLifeEnding(next) {
    if (!this._isPlayAccountCurrent()) return;
    const params = { topicId: this.data.topicId, activityId: this.data.activityId || 0 };
    const routeSessionId = String((this._routeState || {}).sessionId || '');
    const sequence = this._lifeEndingReadSequence = (this._lifeEndingReadSequence || 0) + 1;
    const isCurrent = () => this._isPlayAccountCurrent() && sequence === this._lifeEndingReadSequence
      && params.topicId === this.data.topicId && params.activityId === (this.data.activityId || 0)
      && routeSessionId === String((this._routeState || {}).sessionId || '');
    const finish = () => { if (isCurrent()) next(); };
    req('/api/play/journey/ending', 'GET', params).then((res) => {
      if (!isCurrent()) return;
      const d = res && (res.code == 200 || res.code == '200') ? res.data : null;
      if (!d || !d.title) { finish(); return; }
      const archive = lifeArchive.fromServer(d.archive, this.data.nodes);
      const card = () => { if (isCurrent()) this._showEnding({ show: true, real: true, idx: 0, list: [{ code: d.code, title: d.title, summary: d.summary || '' }],
        archive, hasArchive: !lifeArchive.isEmpty(archive) }); };
      // 结局章(2026-09-24):服务端挑中的那一章先当故事流读,读完才出结局卡与这一生的档案
      const ch = d.chapter;
      if (ch && Array.isArray(ch.blocks) && ch.blocks.length) {
        this._playEndingChapter({ id: ch.id, chapterId: ch.id, name: ch.name, blocks: ch.blocks,
          atmospherePreset: ch.atmospherePreset, audioUrl: ch.audioUrl }, card, isCurrent);
        return;
      }
      card();
    }).catch(finish);
  },
  /** 把一章结局章当故事流播;章末「继续」时收起它并走 after(出结局卡) */
  _playEndingChapter(chapter, after, isCurrent) {
    const current = () => typeof isCurrent !== 'function' || isCurrent();
    if (!current()) return;
    this._afterEndingChapter = () => { if (current()) after(); };
    this.stopAudio();
    const built = Object.assign(this.buildChapter({ chapters: [chapter] }, this.data.nodes), { ending: true, idxLabel: '结局' });
    this.setData({ chapter: built, chapterGameAnchor: '' }, () => { if (current()) this.openChapterFull(); });
  },
  continueLifeEnding() {
    const done = () => this.setData({ 'previewEnding.show': false }, () => this.openFinish());
    if (this.data.reducedMotion) { done(); return; }
    if (this.data.previewEnding.off) return;   // 关机动画进行中,别重复点
    // 结束「关机」(原型 bootout):整屏压成一条亮线,再收掉,然后进完成页
    this.setData({ 'previewEnding.off': 'off' });
    this._fxTimers = (this._fxTimers || []).concat(
      setTimeout(() => this.setData({ 'previewEnding.off': 'gone' }), 480),
      setTimeout(done, 820));
  },
  _finishViewContext() {
    return { owner: this._boundPlayOwner(), sessionKey: this._sessionKey(),
      routeSessionId: String((this._routeState || {}).sessionId || ''), epoch: this._finishArtworkEpoch };
  },
  _isFinishViewCurrent(context) {
    return !!context && this._isPlayAccountCurrent() && context.owner === this._playOwner
      && context.sessionKey === this._sessionKey() && context.epoch === this._finishArtworkEpoch
      && context.routeSessionId === String((this._routeState || {}).sessionId || '');
  },
  openFinish() {
    // R9-21 + R1:通关是会话终点,不管从哪条入口进来都要走统一收尾 —— 只清快照不置 _runStarted,
    // 离页时会被写回;正在跑的那局也在这一刻停表,不能再被当成「暂停中」恢复。
    // (先于下面的结局页:结局页也是通关之后,这一刻就得停表)
    this._endRunSession();
    // 故事主题先看结局与档案(只看一次);探店日与试玩不走这条
    // 只有故事流主题(本章有块流)才配得出结局;经典路线照旧直接进完成页
    const storyTopic = Array.isArray(this.data.chapter && this.data.chapter.blocks);
    const lifeEndingScope = JSON.stringify([this._boundPlayOwner(), this._sessionKey(), String((this._routeState || {}).sessionId || '')]);
    if (storyTopic && !this.data.isPreview && !this.data.isMock && this.data.mode !== 2
        && (!this._lifeEndingShown || this._lifeEndingShownScope !== lifeEndingScope)) {
      this._lifeEndingShown = true; this._lifeEndingShownScope = lifeEndingScope;
      this._loadLifeEnding(() => this.openFinish());
      return;
    }
    this.loadEnding();
    this.loadNewLifeOS();
    const isShopDay = this.data.mode === 2;
    const recap = buildPlayRecap(this.data.nodes);
    // 探店日:探索值(total×12 写死)与模板勋章数都是经典定向的读数,对 mode2 显示会撒谎;
    // 真读数 = 已核销商户的集章列表 + 主题通关奖励(grantOnce 在最后一次核销时已发)。
    const finishStamps = isShopDay
      ? this.data.nodes.filter((n) => n.done).map((n) => ({ nodeId: n.nodeId, name: n.name })) : [];
    const puzzleSummary = summarizePuzzleScores(this.data.nodes);
    this._finishMedals = this.data.nodes.filter((n) => n.medalName || n.medalImg).length; this.setData({ finishMedalList: buildFinishMedals({ allDone: !!this.data.allDone && !isShopDay, reward: this._themeReward, won: this._wonMedals, nodes: this.data.nodes }) });
    // ⚠️ 别在这里加「通关奖励已发放」行:后端对 FREE_COLLECT 不下发 themeReward(B5 剥离),
    //    前端拿不到真数据,写了就是恒空的死 UI。奖励到账明细归修C后端(grantOnce 结果上凭证面)。
    const earnedXp = (this.data.nodes || []).reduce(function (sum, node) {
      return sum + (node && node.done ? (Number(node.earnedXp) || 0) : 0);
    }, 0);
    this._finishArtworkEpoch = (this._finishArtworkEpoch || 0) + 1;
    const finishView = this._finishViewContext();
    this.setData({
      showFinish: true,
      finishScore: earnedXp,
      finishPuzzleScore: puzzleSummary.score,
      finishPuzzleCount: puzzleSummary.count,
      finishPuzzleBest: Number(this._puzzlePersonalBest) || 0,
      finishStamps, finishDateLabel: finishDateLabel(new Date()),
      finishSnap: '', finishSnapError: false,
      finishPhotos: recap.photos,
      canReplayJournal: recap.canReplayJournal,
      // 站末分支图(契约 §3.3):只读本次会话已有的走向记录
      branchPath: this._buildBranchPath(),
      // 主视觉:本次拿到的徽章(有图用图,无图用名字首字);一枚都没有时不硬凑
      finishBadge: this._pickFinishBadge(),
    }, () => {
      if (!this._isFinishViewCurrent(finishView) || !this.data.showFinish) return;
      this._triggerCelebration(this.data.mode === 2 ? 'free-finish' : 'classic-finish');
      this.paintRouteThumb();   // finish-map 盒内=路线缩略图(全卡仅保存时生成,避免和 HTML 数字行重复)
      this.buildReview(); this._openMedalScreen(); this._openFinale(isShopDay); // B:轨迹回顾层(渐进描线 + 成就气泡 + 用时/距离);v13 整个主题走完时盖上通关勋章页
    });
    this.loadMilestone(); this.loadStageEnd(); // BL-186 本章结算与里程碑并列读,不挡完成页
    if (!this.data.allDone) { this.loadSessionStats(); this.loadHudCharacter(); } // 文档二 L5:章节结算的本局成绩名次与 RPG 小结
    this.loadFinishRouteRecommendation();
  },
  closeFinish() {
    this._hideFinish(() => {
      if (this.data.allDone && !this.data.isPreview && !this.data.isMock) this.goBackDetail();
    });
  },
  _hideFinish(callback) {
    this._finishArtworkEpoch = (this._finishArtworkEpoch || 0) + 1;
    this._milestoneReadSequence = (this._milestoneReadSequence || 0) + 1;
    if (this._reviewTimer) { clearTimeout(this._reviewTimer); this._reviewTimer = null; }
    this._finishRecommendationEpoch = (this._finishRecommendationEpoch || 0) + 1;
    this._endingEpoch = (this._endingEpoch || 0) + 1;
    const finishView = this._finishViewContext();
    this.setData({ showFinish: false, 'ending.show': false }, () => {
      if (this._isFinishViewCurrent(finishView) && !this.data.showFinish && callback) callback();
    });
  },
  openFinishJournal() {
    this._hideFinish(() => this.openJournal());
  },

  loadNewLifeOS() {
    if (!this._isPlayAccountCurrent() || this.data.mode !== 2 || this.data.isPreview || this.data.isMock || !this.data.topicId) return;
    const that = this;
    const topicId = String(this.data.topicId);
    const sessionKey = this._sessionKey();
    const loadEpoch = this._playLoadEpoch;
    const sequence = this._osReadSequence = (this._osReadSequence || 0) + 1;
    this.setData({ 'newLifeOS.loading': true });
    req('/api/play/os/' + topicId, 'GET', {}, undefined, true).then((r) => {
      if (!that._isPlayAccountCurrent() || sequence !== that._osReadSequence
          || topicId !== String(that.data.topicId) || sessionKey !== that._sessionKey()
          || loadEpoch !== that._playLoadEpoch || that.data.mode !== 2) return;
      if (!(r.code == 200 || r.code == '200') || !r.data) {
        that.setData({ 'newLifeOS.loading': false, 'newLifeOS.show': false });
        return;
      }
      const d = r.data;
      const anchors = Array.isArray(d.mapAnchors) ? d.mapAnchors : [];
      const cards = Array.isArray(d.resultCards) ? d.resultCards : [];
      const tags = Array.isArray(d.tags) ? d.tags : [];
      that.setData({ newLifeOS: {
        loading: false,
        show: anchors.length > 0 || cards.length > 0 || tags.length > 0,
        tags,
        resultCards: cards,
        mapAnchors: anchors,
        mapNodes: anchors.map((a) => ({ nodeId: a.nodeId, name: a.name, lat: Number(a.latitude), lng: Number(a.longitude), done: true })),
        actions7Days: Array.isArray(d.actions7Days) ? d.actions7Days : [],
        actions30Days: Array.isArray(d.actions30Days) ? d.actions30Days : []
      } });
    });
  },
  revokeOsTag(e) {
    if (!this._isPlayAccountCurrent()) return;
    const tagId = e.currentTarget.dataset.id;
    if (!tagId) return;
    const topicId = String(this.data.topicId || '');
    const sessionKey = this._sessionKey();
    const loadEpoch = this._playLoadEpoch;
    const requests = this._osTagWrites = this._osTagWrites || Object.create(null);
    const requestKey = JSON.stringify([this._boundPlayOwner(), topicId, sessionKey, String(tagId)]);
    if (requests[requestKey]) return;
    const intent = requests[requestKey] = {};
    const unlock = () => { if (requests[requestKey] === intent) delete requests[requestKey]; };
    const isCurrent = () => this._isPlayAccountCurrent()
      && topicId === String(this.data.topicId || '') && loadEpoch === this._playLoadEpoch
      && sessionKey === this._sessionKey();
    req('/api/play/tag/' + tagId + '/revoke', 'POST', {}).then((r) => {
      unlock();
      if (!isCurrent()) return;
      if (r.code == 200 || r.code == '200') {
        this.setData({ 'newLifeOS.tags': this.data.newLifeOS.tags.filter((tag) => String(tag.id) !== String(tagId)) });
      } else cyToast(r.msg || '撤回没有成功');
    }).catch(() => {
      unlock();
      if (isCurrent()) cyToast('撤回没有成功');
    });
  },

  // ---------- B 通关轨迹回顾层(2D;3D 旋转版已拍板不做)----------
  // 顺序真源:与 _drawRoute 同一套 —— mode2 按我的真实完成顺序(doneAt),mode1 按 sortId。
  // 只连**已完成**节点:回顾的是"我真的走过的路",没走到的点不该出现在轨迹里
  // (页面平时那条 fmPolyline 是全量节点的路线预览,两者语义不同,故另建一条,不复用)。
  _reviewOrderedNodes() {
    const branch = this._routeState.routeMode === 'BRANCH_GRAPH';
    const free = this.data.mode === 2;
    const src = (this.data.nodes || []).filter((n) => n.done && n.lat && n.lng);
    return branch
      ? orderedJourneyNodes(src, this._routeState)
      : free
      ? src.slice().sort((a, b) => (a.doneAt || 9e15) - (b.doneAt || 9e15))
      : src.slice().sort((a, b) => (a.sortId || 0) - (b.sortId || 0));
  },

  // 成就气泡:每一条都必须由**真实存在的字段**推出来,字段没有就不生成这条气泡
  // (任务书硬要求:别摆假数据)。
  // ⚠️ 一个 marker 只能挂一条 callout,所以必须定优先级 + 让位:
  //    徽章/券是"这个点真发生过的事",优先级高于"最快一段"这种统计结论;
  //    最快一段被占了就顺延到下一段最快的**未占用**节点,而不是被静默盖掉
  //    (2026-08-01 实测踩到:最快的那段正好落在有徽章的点上,气泡被覆盖,整条信息消失)。
  _reviewAchievements(ordered) {
    const tag = {};
    if (!ordered.length) return tag;
    // ① 节点自身事实:徽章 > 券
    ordered.forEach((n) => {
      if (n.medalName) tag[n.nodeId] = '徽章 · ' + n.medalName;
      else if (n.couponId) tag[n.nodeId] = '到店券 · 已领';
    });
    // ② 最快一段:两端 doneAt 都是真值才算(否则 doneAt=0 会造出"0 秒最快段");
    //    按间隔升序找第一个还没被占用的节点
    const segs = [];
    for (let i = 1; i < ordered.length; i++) {
      const prev = ordered[i - 1], cur = ordered[i];
      if (!prev.doneAt || !cur.doneAt) continue;
      const gap = cur.doneAt - prev.doneAt;
      if (gap > 0) segs.push({ gap, node: cur });
    }
    segs.sort((a, b) => a.gap - b.gap);
    const seg = segs.find((x) => !tag[x.node.nodeId]);
    if (seg) {
      const mm = Math.floor(seg.gap / 60000), ss = Math.round((seg.gap % 60000) / 1000);
      tag[seg.node.nodeId] = '最快一段 · ' + (mm ? mm + '分' : '') + ss + '秒';
    }
    // ③ 首达:最后补,同样只在该点还空着时给 —— 徽章/最快段都比"第一个到"更具体
    if (!tag[ordered[0].nodeId]) tag[ordered[0].nodeId] = '首达 · ' + (ordered[0].name || '第一站');
    return tag;
  },

  // 总距离:相邻已完成点的球面距离累加。少于 2 个点算不出来 ⇒ 返回 ''(那一格整格不渲染)
  _reviewDistanceKm(ordered) {
    if (ordered.length < 2) return '';
    const R = 6371, rad = (d) => d * Math.PI / 180;
    let km = 0;
    for (let i = 1; i < ordered.length; i++) {
      const a = ordered[i - 1], b = ordered[i];
      const dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng);
      const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
      km += 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
    }
    return km >= 10 ? km.toFixed(0) : km.toFixed(1);
  },

  // 渐进描线:把"我真正走过的那条"一段一段叠到 fmPolyline 上,做出轨迹逐渐展开的感觉。
  // ⚠️ 不动 fmPolyline[0](全量路线)—— play-ui-contract 的既有契约要求完成卡展示**完整路径**,
  //   回顾是在它之上加一层高亮,不是把它换掉。关掉动效偏好时一次画完,不做假动画。
  _startReviewDraw(ordered) {
    if (this._reviewTimer) { clearTimeout(this._reviewTimer); this._reviewTimer = null; }
    const base = (this._reviewBase || []).map((seg) => Object.assign({}, seg, {
      color: '#FFFFFF3A', width: 4, borderWidth: 0, arrowLine: false, dottedLine: true,
    }));
    const pts = ordered.map((n) => ({ latitude: n.lat, longitude: n.lng }));
    if (pts.length < 2) { this.setData({ fmPolyline: base.length ? base : this._reviewBase }); return; }
    const layers = (upto) => base.concat([
      // 原生 map 的 polyline 没有 shadow/glow,发光只能用"加粗半透明垫一层"叠出来
      { points: pts.slice(0, upto), color: '#FFFFFF33', width: 14, borderWidth: 0 },
      { points: pts.slice(0, upto), color: '#FFFFFFEE', width: 5, borderColor: '#00000055', borderWidth: 1, arrowLine: this.data.mode !== 2 },
    ]);
    if (this.data.reducedMotion) { this.setData({ fmPolyline: layers(pts.length) }); return; }
    let i = 2;
    const step = () => {
      this.setData({ fmPolyline: layers(i) });
      i += 1;
      if (i <= pts.length) this._reviewTimer = setTimeout(step, 260);
      else this._reviewTimer = null;
    };
    step();
  },

  /** 站末分支图(契约 §3.3):只读本次会话已有的选择历史,画「你走过的路」。
   *  数据 = _routeState.decisionLog(后端路线引擎按走向写的那份)+ 页面已有的节点名。
   *  ⚠️ 不自己造后端字段:没有记录(不是分支模式 / 没走过)就返回空数组,整块不渲染 ——
   *     不用「当前节点」硬凑一条假路线,那是拿猜的东西冒充走过的事实。
   *  ⚠️ 别人的比例契约里已后置,这里不做。 */
  _buildBranchPath() {
    const state = normalizeRouteState(this._routeState);
    if (state.routeMode !== 'BRANCH_GRAPH') return [];
    const nameOf = (id) => {
      const node = (this.data.nodes || []).find((n) => n && String(n.nodeId) === String(id));
      return node ? (node.name || '') : '';
    };
    const rows = [];
    state.decisionLog.forEach((entry) => {
      if (!entry || entry.fromNodeId == null || entry.toNodeId == null) return;
      rows.push({
        key: String(entry.edgeId || (rows.length + ':' + entry.toNodeId)),
        from: nameOf(entry.fromNodeId),
        to: nameOf(entry.toNodeId),
        at: entry.at ? formatDayDots(entry.at) : '',
      });
    });
    return rows;
  },

  buildReview() {
    const ordered = this._reviewOrderedNodes();
    const all = this._reviewAchievements(ordered);
    // 完成卡小地图上只留一条成就气泡(2026-10-27 用户:站点挨得近,三条气泡互相盖、被裁掉)。
    // 优先级与 _reviewAchievements 一致:徽章 > 券 > 最快一段 > 首达。
    const rank = (t) => ['徽章', '到店券', '最快一段', '首达'].findIndex((p) => t.indexOf(p) === 0);
    const topId = Object.keys(all).sort((a, b) => rank(all[a]) - rank(all[b]))[0];
    const tags = topId != null ? { [topId]: all[topId] } : {};
    // 成就气泡挂到既有 fmNodes 上(节点 pin 全部保留),没有成就的节点 reviewLabel 为空 = 原样显示序号
    const fmNodes = (this.data.fmNodes || []).map((n) => Object.assign({}, n, { reviewLabel: tags[n.nodeId] || '' }));
    const sec = this._sessionClock ? this._sessionClock.elapsedSeconds() : 0;
    if (!this._reviewBase) this._reviewBase = (this.data.fmPolyline || []).slice();
    this.setData({
      fmNodes,
      reviewElapsed: sec > 0 ? formatElapsed(sec) : '',
      reviewDistance: this._reviewDistanceKm(ordered),
      reviewEmpty: ordered.length === 0, finishAchievements: achievementRows(all, ordered),
    });
    this._startReviewDraw(ordered);
  },

  // 主视觉徽章:优先本次真正拿到的那一枚(有图优先),一枚都没有时返回 null ⇒ 回落到路线缩略图,
  // 不造一个假徽章占位。
  _pickFinishBadge() {
    const got = (this.data.nodes || []).filter((n) => n.medalName || n.medalImg);
    if (!got.length) return null;
    const withImg = got.find((n) => n.medalImg) || got[0];
    const name = withImg.medalName || '专属徽章';
    return { img: withImg.medalImg || '', name: name, initial: name.slice(0, 2), count: got.length };
  },

  // ---------- C 结束分享:里程碑轴 ----------
  // 档位数字必须是真的:累计完成主题数取 /api/play/my-completed(成长中心同一条只读接口),
  // 按 topicId 去重 —— 与 utils/growth-overview.js 的 completedTopicCount 同口径。
  // ⚠️ 拿不到就整条轴不渲染(milestone=null),不拿本章数据冒充累计、也不填 0 凑一个「第 1 档」。
  //   这条与成长中心「失败不把数据伪装为零」的既有约定一致。
  loadFinishRouteRecommendation() {
    if (!this._isPlayAccountCurrent() || !this.data.showFinish) return Promise.resolve(null);
    const requestEpoch = (this._finishRecommendationEpoch || 0) + 1;
    this._finishRecommendationEpoch = requestEpoch;
    const sessionKey = this._sessionKey();
    const activityId = this.data.activityId, topicId = this.data.topicId, mode = this.data.mode;
    const loadEpoch = this._playLoadEpoch;
    const isCurrent = () => this._isPlayAccountCurrent() && this.data.showFinish
      && requestEpoch === this._finishRecommendationEpoch && loadEpoch === this._playLoadEpoch
      && sessionKey === this._sessionKey()
      && activityId === this.data.activityId && topicId === this.data.topicId && mode === this.data.mode;
    this.setData({ finishRouteRecommendation: null });
    if (!finishRouteRecommendation.isEnabled(app.globalData && app.globalData.features)
        || this.data.isPreview || this.data.isMock) return Promise.resolve(null);
    const origin = finishRouteRecommendation.resolveOrigin(this._loc, this.data.nodes);
    if (!origin) return Promise.resolve(null);
    return req('/api/activity/list', 'POST', {
      is_my: 0,
      sort_type: 1,
      pageNum: 1,
      pageSize: 5,
      longitude: origin.longitude,
      latitude: origin.latitude
    }).then((response) => {
      if (!isCurrent()) return null;
      const rows = response && response.code == 200 && response.data && response.data.rows;
      const card = finishRouteRecommendation.pickCandidate(
        Array.isArray(rows) ? rows : [], activityId, topicId
      );
      this.setData({ finishRouteRecommendation: card }, () => {
        if (!card || !isCurrent()) return;
        analytics.track('finish_route_recommendation_shown', {
          bizType: 'activity', bizId: card.id,
          properties: { mode, placement: 'play_finish' }
        });
      });
      return card;
    });
  },

  goFinishRouteRecommendation() {
    const card = this.data.finishRouteRecommendation;
    finishRouteRecommendation.openCandidate(card, {
      mode: this.data.mode,
      track: analytics.track,
      openScene: (id, params) => this.openScene(id, params)
    });
  },

  loadMilestone() {
    if (!this._isPlayAccountCurrent() || !this.data.showFinish) return;
    const sequence = this._milestoneReadSequence = (this._milestoneReadSequence || 0) + 1;
    const sessionKey = this._sessionKey();
    const loadEpoch = this._playLoadEpoch;
    this.setData({ milestone: null });
    app.sendRequest({
      url: '/api/play/my-completed', method: 'POST', hideLoading: true, silentError: true,
      success: (res) => {
        if (!this._isPlayAccountCurrent() || !this.data.showFinish
            || sequence !== this._milestoneReadSequence || loadEpoch !== this._playLoadEpoch
            || sessionKey !== this._sessionKey()) return;
        const ok = res && (res.code === 200 || res.code === '200');
        if (!ok || !Array.isArray(res.data)) return;      // 保持 null,轴不出现
        const ids = {};
        res.data.forEach((it) => {
          const id = it && it.topicId;
          if (id != null && id !== '') ids[String(id)] = true;
        });
        // 完成事实列表按路线局状态算,线性局要所有站(含选做)都走完才落「完成」;这一局刚由服务端判过通关,算上它
        if (this.data.topicCompleted && this.data.topicId) ids[String(this.data.topicId)] = true;
        this.setData({ milestone: buildMilestone(Object.keys(ids).length) });
      },
    });
  },

  /**
   * BL-186 章节阶段结算:本章(未通关)的结算半屏补上作者写的「这一章改变了什么 / 接下来打开什么」。
   * 取数与降级照 _loadLifeEnding 的 /journey/ending:只给故事流真玩家;没配阶段 / 拿不到 → 整块不渲染,不挡路。
   */
  loadStageEnd() {
    const sequence = this._stageEndReadSequence = (this._stageEndReadSequence || 0) + 1;
    if (this.data.stageEnd) this.setData({ stageEnd: null });
    const chapter = this.data.chapter || {};
    const chapterId = Number(chapter.chapterId || chapter.id);
    if (!this._isPlayAccountCurrent() || this.data.allDone || this.data.isPreview || this.data.isMock
        || this.data.mode === 2 || chapter.ending || !Array.isArray(chapter.blocks)
        || !Number.isSafeInteger(chapterId) || chapterId <= 0) return;
    const params = { topicId: this.data.topicId, activityId: this.data.activityId || 0, chapterId };
    const routeSessionId = String((this._routeState || {}).sessionId || '');
    const isCurrent = () => this._isPlayAccountCurrent() && sequence === this._stageEndReadSequence
      && this.data.showFinish && params.topicId === this.data.topicId
      && routeSessionId === String((this._routeState || {}).sessionId || '');
    req('/api/play/journey/stage-end', 'GET', params, undefined, true).then((res) => {
      if (!isCurrent()) return;
      const d = res && (res.code == 200 || res.code == '200') ? res.data : null;
      const changed = d && Array.isArray(d.changed)
        ? d.changed.filter((text) => typeof text === 'string' && text.trim() !== '') : [];
      const open = d && typeof d.open === 'string' ? d.open.trim() : '';
      if (!changed.length && !open) return;
      this.setData({ stageEnd: { changed, open } });
    });
  },

  // ---------- P1 足迹卡:盒内路线缩略图 + 保存用全卡(AD 风格) ----------
  // 共享的路线绘制:把节点(lat/lng)归一化画进 (bx,by,bw,bh) 框(线 + done 实心/未完成描边)
  _drawRoute(g, nodes, bx, by, bw, bh, artwork) {
    // P1 自由定向分享卡:按"我的完成顺序"连线+标序号(每个人路径不同=个性化传播点);mode1 保持 sortId 序
    const mode = artwork ? artwork.mode : this.data.mode;
    const routeState = artwork ? artwork.routeState : this._routeState;
    const free = mode === 2;
    const src = (nodes || []).filter((n) => n.lat && n.lng);
    const branch = routeState.routeMode === 'BRANCH_GRAPH';
    const ordered = branch
      ? orderedJourneyNodes(src, routeState)
      : free
      ? src.slice().sort((a, b) => (a.doneAt || 9e15) - (b.doneAt || 9e15))
      : src.slice().sort((a, b) => (a.sortId || 0) - (b.sortId || 0));
    const pts = ordered.map((n) => ({ x: n.lng, y: -n.lat, done: n.done })); // 纬度取负,越大越靠上
    if (!pts.length) return;
    let minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
    pts.forEach((p) => { minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y); });
    const pad = 40;
    const s = Math.min((bw - pad * 2) / Math.max(1e-9, maxX - minX), (bh - pad * 2) / Math.max(1e-9, maxY - minY));
    const ox = bx + pad + (bw - pad * 2 - (maxX - minX) * s) / 2, oy = by + pad + (bh - pad * 2 - (maxY - minY) * s) / 2;
    const P = (p) => ({ x: ox + (p.x - minX) * s, y: oy + (p.y - minY) * s });
    const linePts = free ? pts.filter((p) => p.done) : pts; // 自由定向只连走过的路
    g.strokeStyle = PLAY_STORY_CARD.accent; g.lineWidth = 5; g.lineJoin = 'round'; g.beginPath();
    linePts.forEach((p, i) => { const q = P(p); i ? g.lineTo(q.x, q.y) : g.moveTo(q.x, q.y); });
    g.stroke();
    let seq = 0;
    pts.forEach((p) => {
      const q = P(p); g.beginPath(); g.arc(q.x, q.y, free && p.done ? 13 : 9, 0, 7);
      if (p.done) {
        g.fillStyle = PLAY_STORY_CARD.accent; g.fill();
        if (free) {
          seq += 1;
          g.fillStyle = PLAY_STORY_CARD.ring; g.font = '600 13px Menlo, monospace';
          g.textAlign = 'center'; g.textBaseline = 'middle';
          g.fillText(String(seq), q.x, q.y);
        }
      }
      else { g.fillStyle = PLAY_STORY_CARD.surface; g.fill(); g.strokeStyle = PLAY_STORY_CARD.line; g.lineWidth = 2; g.stroke(); }
    });
    g.textBaseline = 'alphabetic'; g.textAlign = 'left'; // 还原,别影响后续 fillText
  },
  _rr(g, x, y, w, h, r) {
    g.beginPath();
    g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath();
  },
  _captureFinishArtwork(channel) {
    const sequenceKey = channel + 'ArtworkSequence';
    const sequence = this[sequenceKey] = (this[sequenceKey] || 0) + 1;
    return { owner: this._boundPlayOwner(), sessionKey: this._sessionKey(),
      routeSessionId: String((this._routeState || {}).sessionId || ''), epoch: this._finishArtworkEpoch,
      sequenceKey, sequence, mode: this.data.mode, chapter: Object.assign({}, this.data.chapter),
      nodes: (this.data.nodes || []).map(node => Object.assign({}, node)),
      routeState: JSON.parse(JSON.stringify(this._routeState || {})), doneCount: this.data.doneCount,
      finishScore: this.data.finishScore, medals: this._finishMedals,
      lead: Object.assign({}, this.data.lead), milestone: this.data.milestone ? JSON.parse(JSON.stringify(this.data.milestone)) : null };
  },
  _isFinishArtworkCurrent(artwork) {
    return !!artwork && this._isPlayAccountCurrent() && this.data.showFinish
      && artwork.owner === this._playOwner && artwork.epoch === this._finishArtworkEpoch
      && artwork.sequence === this[artwork.sequenceKey] && artwork.sessionKey === this._sessionKey()
      && artwork.routeSessionId === String((this._routeState || {}).sessionId || '');
  },
  _queueFinishCanvas(current, draw, onError) {
    this._finishCanvasQueue = this._finishCanvasQueue || [];
    this._finishCanvasQueue.push({ current, draw, onError });
    this._drainFinishCanvas();
  },
  _drainFinishCanvas() {
    if (this._finishCanvasBusy) return;
    const queue = this._finishCanvasQueue || [];
    let task;
    while (queue.length) { const next = queue.shift(); if (next.current()) { task = next; break; } }
    if (!task) return;
    this._finishCanvasBusy = true;
    let released = false;
    const release = () => {
      if (released) return;
      released = true; this._finishCanvasBusy = false; this._drainFinishCanvas();
    };
    try { task.draw(release); } catch (e) { release(); if (task.current() && task.onError) task.onError(); }
  },
  // 盒内路线缩略图 → finishSnap(仅路线,数字由 HTML finish-stat 负责,不重复)
  paintRouteThumb() {
    const artwork = this._captureFinishArtwork('_thumb');
    const current = () => this._isFinishArtworkCurrent(artwork);
    if (!current()) return;
    this._queueFinishCanvas(current, release => {
      wx.createSelectorQuery().select('#snapcv').fields({ node: true }).exec((res) => {
        if (!current()) { release(); return; }
        if (!res || !res[0] || !res[0].node) { release(); this.setData({ finishSnapError: true }); return; }
        const cv = res[0].node;
        const W = 690, H = 320, dpr = 2;
        cv.width = W * dpr; cv.height = H * dpr;
        const g = cv.getContext('2d'); g.scale(dpr, dpr);
        g.fillStyle = PLAY_STORY_CARD.surface; this._rr(g, 0, 0, W, H, 24); g.fill();
        this._drawRoute(g, artwork.nodes, 0, 0, W, H, artwork);
        wx.canvasToTempFilePath({
          canvas: cv, destWidth: W * dpr, destHeight: H * dpr,
          success: r => { release(); if (current()) this.setData({ finishSnap: r.tempFilePath, finishSnapError: false }); },
          fail: () => { release(); if (current()) this.setData({ finishSnapError: true }); }
        });
      });
    }, () => { if (current()) this.setData({ finishSnapError: true }); });
  },
  retryRouteThumb() {
    this.setData({ finishSnapError: false }, () => this.paintRouteThumb());
  },
  // 保存/分享用全卡(眉标+衬线题+路线+三列大数字+带领行+品牌脚);回调拿临时路径,不占 finishSnap
  paintFinishCard(cb) {
    const that = this, artwork = this._captureFinishArtwork('_card');
    const current = () => this._isFinishArtworkCurrent(artwork);
    if (!current()) return;
    this._queueFinishCanvas(current, release => {
    let delivered = false;
    const deliver = path => { if (delivered) return; delivered = true; release(); if (current() && cb) cb(path); };
    wx.createSelectorQuery().select('#snapcv').fields({ node: true }).exec((res) => {
      if (!current()) { release(); return; }
      if (!res || !res[0] || !res[0].node) { deliver(''); return; }
      const cv = res[0].node;
      const W = 750, H = 1000, dpr = 2;
      cv.width = W * dpr; cv.height = H * dpr;
      const g = cv.getContext('2d'); g.scale(dpr, dpr);
      g.fillStyle = PLAY_STORY_CARD.page; g.fillRect(0, 0, W, H);
      g.fillStyle = PLAY_STORY_CARD.muted; g.font = '600 22px Menlo, monospace'; g.textAlign = 'left';
      g.fillText(artwork.mode === 2 ? 'C H E N G Y I N · 我 的 探 索 顺 序' : 'C H E N G Y I N · 主 题 通 关', 56, 88);
      g.fillStyle = PLAY_STORY_CARD.text; g.font = '500 52px Georgia, serif';
      g.fillText(String(artwork.chapter.name || '城市探索').slice(0, 12), 56, 160);
      const bx = 56, by = 210, bw = W - 112, bh = 380;
      g.fillStyle = PLAY_STORY_CARD.surface; that._rr(g, bx, by, bw, bh, 24); g.fill();
      that._drawRoute(g, artwork.nodes || [], bx, by, bw, bh, artwork);
      const stats = [
        // 与通关卡同一处修正:标签是"点亮坐标"就得给已点亮数,不是总数。
        // 这份是**分享卡**,错数字会被烤进图片传播出去,比页面上错得更久。
        [String(artwork.doneCount || 0), '点亮坐标'],
        [String(artwork.finishScore || 0), '探索值'],
        [String(artwork.medals || 0), '获得徽章']
      ];
      stats.forEach((st, i) => {
        const cx = 56 + i * ((W - 112) / 3) + ((W - 112) / 6);
        g.textAlign = 'center';
        g.fillStyle = PLAY_STORY_CARD.text; g.font = '300 84px Menlo, monospace';
        g.fillText(st[0], cx, by + bh + 130);
        g.fillStyle = PLAY_STORY_CARD.muted; g.font = '500 22px -apple-system, sans-serif';
        g.fillText(st[1], cx, by + bh + 170);
      });
      g.textAlign = 'center'; g.fillStyle = PLAY_STORY_CARD.muted; g.font = '400 24px -apple-system, sans-serif';
      if (artwork.lead && artwork.lead.exists) {
        g.fillText('全队 ' + artwork.lead.total + ' 人同行', W / 2, by + bh + 230);
      }
      const footerY = H - 24;
      // 里程碑轴:海报要和屏幕上那张卡是同一件东西,所以这里也画一条。
      // milestone 为 null(累计数没取到)时整条不画 —— 与页面同一条口径,不在海报上补假数字。
      const ms = artwork.milestone;
      if (ms && ms.tiers && ms.tiers.length) {
        const my = by + bh + 290;
        const r = 30, gap = (W - 112 - r * 2) / (ms.tiers.length - 1);
        g.strokeStyle = PLAY_STORY_CARD.line; g.lineWidth = 2;
        g.beginPath(); g.moveTo(56 + r, my); g.lineTo(W - 56 - r, my); g.stroke();
        ms.tiers.forEach((t, i) => {
          const cx = 56 + r + i * gap;
          g.beginPath(); g.arc(cx, my, r, 0, Math.PI * 2);
          if (t.state === 'current') { g.fillStyle = PLAY_STORY_CARD.accent; g.fill(); g.fillStyle = PLAY_STORY_CARD.ring; }
          else if (t.state === 'past') { g.fillStyle = PLAY_STORY_CARD.accent; g.fill(); g.fillStyle = PLAY_STORY_CARD.ring; }
          else { g.fillStyle = PLAY_STORY_CARD.surface; g.fill(); g.strokeStyle = PLAY_STORY_CARD.line; g.stroke(); g.fillStyle = PLAY_STORY_CARD.fade; }
          g.textAlign = 'center'; g.font = '700 22px Menlo, monospace';
          g.fillText(String(t.value), cx, my + 8);
        });
        g.textAlign = 'center'; g.fillStyle = PLAY_STORY_CARD.muted; g.font = '400 24px -apple-system, sans-serif';
        const milestoneTitleY = Math.min(my + 62, footerY - 40);
        g.fillText(ms.title, W / 2, milestoneTitleY);
      }
      g.fillStyle = PLAY_STORY_CARD.footer; g.font = '600 22px Menlo, monospace';
      g.fillText('城 瘾 · 走 进 城 市 的 瘾', W / 2, footerY);
      wx.canvasToTempFilePath({
        canvas: cv, destWidth: W * dpr, destHeight: H * dpr,
        success(r) { deliver(r.tempFilePath); }, fail() { deliver(''); }
      });
    });
    }, () => { if (current() && cb) cb(''); });
  },
  saveFinishCard() {
    const that = this, intent = this._captureFinishArtwork('_album');
    const current = () => this._isFinishArtworkCurrent(intent);
    if (!current()) return;
    that.diegetic('生成足迹卡…');
    that.paintFinishCard((path) => {
      if (!current()) return;
      if (!path) { that.diegetic('足迹卡生成失败，再试一次'); return; }
      wx.saveImageToPhotosAlbum({
        filePath: path,
        success() { if (current()) that.diegetic('已保存到相册'); },
        fail(e) {
          if (!current()) return;
          if (e.errMsg && e.errMsg.indexOf('auth') >= 0) { wx.openSetting({}); }
          else if (e.errMsg && e.errMsg.indexOf('cancel') >= 0) { /* 用户取消 */ }
          else { that.diegetic('保存失败，再试一次'); }
        }
      });
    });
  },
  _syncCommunityEntry() {
    const owner = app.getUserID && app.getUserID();
    const sync = () => {
      if (this._playDisposed || owner !== (app.getUserID && app.getUserID())) return;
      this.setData({ communityPostWrite: typeof app.canUseCommunity === 'function' && app.canUseCommunity(true) });
    };
    const pending = typeof app.loadFeatureFlags === 'function' ? app.loadFeatureFlags() : null;
    sync();
    if (pending && typeof pending.then === 'function') pending.then(sync);
  },
  shareFinish() {
    if (typeof app.canUseCommunity !== 'function' || !app.canUseCommunity(true)) { cyToast('当前账号未开放广场发布'); return; }
    // 通关→广场发布:发帖时活动选择器列出已通关活动(本场已在 /api/play/my-completed 中),选中即关联
    cyToast('去广场发布你的城市足迹');
    setTimeout(() => { wx.navigateTo({ url: '/pages/square/list/index' }); }, 800);
  },

  // ---------- 音频播放 ----------
  initAudio() {
    if (this._audio) return;
    const that = this;
    const audio = audioSession.register(wx.createInnerAudioContext(), { kind: 'guide' });
    audio.obeyMuteSwitch = false;       // 静音开关下也出声（语音导览属于内容消费）
    audio.onPlay(() => {
      audioSession.keepAwake('guideAudio', true);
      that.setData({ audioPlaying: true, audioLoading: false, audioError: '' });
    });
    audio.onPause(() => {
      audioSession.keepAwake('guideAudio', false);
      that.setData({ audioPlaying: false });
    });
    audio.onStop(() => {
      audioSession.keepAwake('guideAudio', false);
      that.setData({ audioPlaying: false, audioLoading: false });
    });
    audio.onEnded(() => {
      audioSession.keepAwake('guideAudio', false);
      that.setData({ audioPlaying: false, audioLoading: false });
    });
    audio.onError((err) => {
      const msg = (err && err.errMsg) ? err.errMsg : '音频加载失败';
      audioSession.keepAwake('guideAudio', false);
      that.setData({ audioPlaying: false, audioLoading: false, audioError: msg });
      cyToast(msg);
    });
    audio.onWaiting(() => {
      that.setData({ audioLoading: true, audioError: '' });
    });
    audio.onCanplay(() => {
      that.setData({ audioLoading: false, audioError: '' });
    });
    this._audio = audio;
  },

  toggleAudio() {
    const g = this.data.game;
    if (!g.audio) return;
    this.initAudio();
    const audio = this._audio;
    const curNodeId = this.data.audioNodeId;
    const sameNode = curNodeId === g.nodeId;

    if (sameNode && this.data.audioPlaying) {
      // 同一节点正在播 → 暂停
      audio.pause();
    } else if (sameNode && !this.data.audioPlaying && !this.data.audioError) {
      // 同一节点暂停中 → 继续
      audio.play();
    } else {
      // 不同节点或错误重试 → 换源
      audio.stop();
      audio.src = g.audio;
      audio.play();
      this.setData({ audioNodeId: g.nodeId });
    }
  },

  /** 题面音频(题干配音 / 选项配音)。与节点语音导览共用同一个 InnerAudioContext ——
   *  三处同时出声是灾难,共用播放器天然保证「后播的把前面那段顶掉」。
   *  audioNodeId 在这里存字符串键('q' / 'optA'),节点侧存的是数字 nodeId,=== 比较不会撞。 */
  _toggleMediaAudio(key, url) {
    if (!url) return;
    if (typeof url === 'string' && url.startsWith('/audio/merchant-games/')) {
      url = bundledMerchantGameAudioPath(url);
      if (!url) { cyToast('音频素材不存在'); return; }
    }
    this.initAudio();
    const audio = this._audio;
    const same = this.data.audioNodeId === key;
    if (same && this.data.audioPlaying) { audio.pause(); return; }
    if (same && !this.data.audioError) { audio.play(); return; }
    audio.stop();
    audio.src = url;
    audio.play();
    this.setData({ audioNodeId: key });
  },

  toggleQuestionAudio() {
    this._toggleMediaAudio('q', this.data.game && this.data.game.qAudio);
  },

  toggleOptionAudio(e) {
    const k = e.currentTarget.dataset.k;
    const opt = ((this.data.game && this.data.game.opts) || []).find((o) => o.k === k);
    this._toggleMediaAudio('opt' + k, opt && opt.audio);
  },

  previewQuestionImg() {
    const url = this.data.game && this.data.game.qImg;
    if (url) wx.previewImage({ urls: [url], current: url });
  },

  stopAudio() {
    if (!this._audio) return;
    try { this._audio.stop(); } catch (e) {}
    this.setData({ audioPlaying: false, audioLoading: false, audioError: '', audioNodeId: null });
  },

  pauseAudio() {
    // 走统一音频会话:登记过的 ctx(导览/旁白/玩法音乐/NPC 语音)一并暂停,并记下谁在播
    audioSession.pauseAll();
    if (!this._audio) return;
    try { this._audio.pause(); } catch (e) {}
    // 不清理 audioNodeId——回前台可继续播同一段
  },

  destroyAudio() {
    if (!this._audio) return;
    audioSession.unregister(this._audio);
    try { this._audio.destroy(); } catch (e) {}
    this._audio = null;
    this.setData({ audioPlaying: false, audioLoading: false, audioError: '', audioNodeId: null });
  },

  onShareAppMessage(event) {
    return activityShareFromEvent(event)
      || { title: '来城瘾，走一段城市探索', path: '/pages/play/index?activityId=' + this.data.activityId };
  },
  // FE-17 朋友圈分享(游玩页高传播)
  onShareTimeline() { return { title: '来城瘾，走一段城市探索', query: 'activityId=' + this.data.activityId }; },

  /* v13 通关勋章页:只在整个主题走完时出。勋章取本次真拿到的;一枚没有就用这个主题本身(通关是真的)。
     四格统计只写拿得到的数(用时 / 步行 / 站点或商家 / 邮票或解谜分);新纪录只在解谜分超过个人最好时出。 */
  _openMedalScreen() {
    if (!this.data.allDone) return;
    const shop = this.data.mode === 2;
    const mics = ['env', 'leaf', 'moon', 'key'];
    let items = (this.data.finishMedalList || []).map((m, i) => ({ key: m.key || 'm' + i, name: m.name, sub: m.sub, img: m.img || '', mic: mics[i % 4] }));
    if (!items.length) items = [{ key: 'theme', name: this.data.topicName || '主题通关', sub: shop ? '全部去完' : '主题通关', img: '', mic: shop ? 'stamp' : 'env' }];
    const sec = this._sessionClock ? this._sessionClock.elapsedSeconds() : 0;
    const hms = sec > 0 ? Math.floor(sec / 3600) + ':' + String(Math.floor(sec / 60) % 60).padStart(2, '0') + ':' + String(Math.floor(sec) % 60).padStart(2, '0') : '';
    const km = this._reviewDistanceKm(this._reviewOrderedNodes());
    const stats = [];
    if (hms) stats.push({ k: '用时', v: hms, glyph: 'clock' });
    if (km) stats.push({ k: '步行 · 公里', v: km, glyph: 'walk' });
    if (this.data.total) stats.push({ k: shop ? '去过 · 家' : '节点', v: this.data.doneCount + '/' + this.data.total, glyph: shop ? 'shop' : 'book' });
    if (shop && this.data.finishStamps.length) stats.push({ k: '邮票 · 枚', v: String(this.data.finishStamps.length), glyph: 'stamp' });
    else if (!shop && this.data.finishPuzzleCount) stats.push({ k: '解谜分', v: String(this.data.finishPuzzleScore), glyph: 'stamp' });
    const best = Number(this.data.finishPuzzleBest) || 0, score = Number(this.data.finishPuzzleScore) || 0;
    const record = !shop && best > 0 && score > best ? { label: '解谜分 · 个人最好', text: '比上次最好多 ' + (score - best) + ' 分', tag: 'BEST' } : null;
    this.setData({ medalScreen: { show: true, items, title: shop ? '探店完成' : '主题通关', sub: (this.data.topicName ? this.data.topicName + ' · ' : '') + '获得 ' + items.length + ' 枚勋章' + (items.length > 1 ? '，点或拖动切换' : ''), stats: stats.slice(0, 4), record } });
  },
  onMedalRecords() { this.setData({ 'medalScreen.show': false }); },
  // 2026-10-23 弹层动效:手动关闭的面板先挂 is-out 播退场(full 档 280ms = --cy-motion-sheet-full-out),
  // 播完再卸载;减动效直接卸载。程序里一关一开的切换不走这里(那是直接换下一层,不叠)。
  _outThen(which) {
    const journal = which === 'journal';
    const still = () => (journal ? this.data.journalOut : this.data.sheetOut);
    const unmount = () => {
      if (journal) this.setData({ journalOut: false, showJournal: false });
      else this.setData({ sheetOut: false, 'sheet.show': false });
    };
    if (this.data.reducedMotion) { unmount(); return; }
    if (still()) return;
    if (journal) this.setData({ journalOut: true });
    else this.setData({ sheetOut: true });
    // 退场期间又被打开(打开处会把标记清回 false):不再卸载新开的那一层
    setTimeout(() => { if (still()) unmount(); }, 280);
  },

  // 2026-10-23 弹层动效:右上角工具菜单原来一帧收起;点遮罩 / 再点按钮先淡出(--cy-motion-fast 140ms)再卸载。
  // 菜单里点某一项去别处(扫码、打开章节…)仍是直接收起,马上接下一层,不会两层同时动。
  _toolsClose() {
    if (!this.data.sessionToolsOpen) return;
    if (this.data.reducedMotion) { this.setData({ sessionToolsOpen: false, toolsOut: false }); return; }
    if (this.data.toolsOut) return;
    this.setData({ toolsOut: true });
    setTimeout(() => { if (this.data.toolsOut) this.setData({ sessionToolsOpen: false, toolsOut: false }); }, 140);
  },

  /** 2026-10-28 通关过场:整个主题走完时先放「三棱锥炸开 → 银色城瘾标志」,播完再露出通关勋章页。探店日与试玩不放。 */
  _openFinale(isShopDay) {
    if (!this.data.allDone || isShopDay || this.data.isPreview || this.data.isMock) return;
    const sec = this._sessionClock ? this._sessionClock.elapsedSeconds() : 0;
    const flip = chapterFlip.buildFinale({
      cards: this.data.chapterCards, nodes: this.data.nodes, topicCover: this.data.topicCover, topicName: this.data.topicName,
      doneCount: this.data.doneCount, elapsed: sec > 0 ? formatElapsed(sec) : '', score: this.data.finishScore,
      puzzle: { score: this.data.finishPuzzleScore, count: this.data.finishPuzzleCount },
    });
    this.setData({ finale: Object.assign({ show: true }, flip) });
  },
  closeFinale() { this.setData({ 'finale.show': false }); },
  saveFinale() { this.setData({ 'finale.show': false }, () => this.saveFinishCard()); },
});

/** 完成页眉标:「10月28日 周二」 */
function finishDateLabel(d) { return (d.getMonth() + 1) + '月' + d.getDate() + '日 周' + '日一二三四五六'[d.getDay()]; }
/** 完成页「成就」行:_reviewAchievements 的「标题 · 值」拆开,值放右侧,站名做副行(首达的值本身就是站名) */
function achievementRows(tags, ordered) {
  const glyph = { 徽章: '徽', 到店券: '券', 最快一段: '速', 首达: '首' };
  return ordered.filter((n) => tags[n.nodeId]).map((n) => {
    const [title, value] = String(tags[n.nodeId]).split(' · ');
    const first = title === '首达';
    return { key: String(n.nodeId), title, value: first ? '' : (value || ''), sub: first ? (value || '') : (n.name || ''), glyph: glyph[title] || title.slice(0, 1), kind: first ? 'ink' : 'metal' };
  });
}

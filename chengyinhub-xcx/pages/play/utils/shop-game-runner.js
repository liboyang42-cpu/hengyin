// W7 x-shop-game-runner:店铺小游戏在据点(和城市大富翁落格后)真正玩一局。纯函数,单测友好。
//
// 步骤:发局 POST /api/shop-games/launch → 开这局自己的会话 POST /api/shop-games/plays/{playId}/session
// → 用现有高级玩法组件(cy-advanced-game)玩 → 记结果 POST /api/shop-games/result → 看结果。
// 组件原本只认旅程的三条路由(start/state/action);shopPlayId 有值时由 mapRequest 换成这局自己的会话路由,
// 其他路由(回执、排行榜)一律不发。服务端判定每一步;不在店里也能玩,只记成绩,不发积分。不涉及钱。

const LAUNCH_PATH = '/api/shop-games/launch';
const RESULT_PATH = '/api/shop-games/result';
const SESSION_STATE_PATH = '/api/shop-games/sessions/state';
const SESSION_ACTION_PATH = '/api/shop-games/sessions/action';

function positiveInt(value) {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : 0;
}

/**
 * 把组件发出的旅程路由换成这局店铺小游戏的会话路由。返回 null = 这条路由店铺小游戏没有,不发。
 * 开局不带任何旅程 id:组件只在路径 /api/shop-games/plays/{playId}/session 里带 playId,body 为空。
 */
function mapRequest(url, method, data, playId) {
  const id = positiveInt(playId);
  if (!id) return null;
  const m = String(method || 'GET').toUpperCase();
  if (url === '/api/play/advanced/start' && m === 'POST') return { route: 'start', playId: id, method: 'POST', data: {} };
  if (url === '/api/play/advanced/state' && m === 'GET') {
    return { route: 'state', url: SESSION_STATE_PATH, method: 'GET', data: { sessionId: (data || {}).sessionId } };
  }
  if (url === '/api/play/advanced/action' && m === 'POST') return { route: 'action', url: SESSION_ACTION_PATH, method: 'POST', data: data || {} };
  return null;
}

/**
 * 组件按 (activityId, topicId, nodeId) 核对回包是不是这一局的;店铺会话没有旅程 id,
 * 这里只在本机给回包补上这局的本地身份(activity 0,topic 与 node 都是 playId),服务端从不收到。
 */
function identify(res, playId) {
  const id = positiveInt(playId);
  if (!id || !res || typeof res !== 'object') return res;
  const code = Number(res.code);
  if (code !== 200 || !res.data || typeof res.data !== 'object') return res;
  return Object.assign({}, res, { data: Object.assign({}, res.data, { activityId: 0, topicId: id, nodeId: id }) });
}

/** 一条路由店铺小游戏没有时组件收到的确定拒绝(回包形状同 app.sendRequest 的 body),不是网络错误。 */
function refusal() {
  return { code: 409, msg: '店铺小游戏不支持这一步', errorCode: 'SHOP_GAME_ROUTE' };
}

/** 服务端的每个原因都给一句人话。 */
const REASON_TEXT = {
  NOT_OPEN: '店铺小游戏还没开放，开放后就在这里玩。',
  SHOP_GAME_NOT_OPEN: '店铺小游戏还没开放，开放后就在这里玩。',
  NOT_A_SHOP: '这里不是店铺，没有小游戏。',
  NOT_READY: '这家店的小游戏还在准备，换个时间再来。',
  TOO_FAR: '要在店里或店门口才能开始这局小游戏。',
  LOCATION_NOT_TRUSTED: '这次定位不够准，到店后再试一次。',
  LOCATION_REQUIRED: '需要定位才能开始这局小游戏。',
  SHOP_GAME_NOT_PLAYABLE: '这家店的小游戏现在只能在旅程里玩。',
  PLAY_NOT_FINISHED: '这局还没玩完，玩完再看结果。',
  PLAY_NOT_JUDGED: '这局还没有结果，玩完再看。',
  PLAY_EXPIRED: '这局时间到了，明天再来。',
  PLAY_ALREADY_FINISHED: '今天已经玩过这家店的小游戏了，明天再来。',
  PLAY_NOT_FOUND: '没有找到这局小游戏，回到地点页再开一局。',
  SESSION_NOT_FOUND: '没有找到这局小游戏，回到地点页再开一局。',
  SESSION_CLOSED: '这局小游戏已经结束了。',
  VERSION_CONFLICT: '这局有更新，刷新后再试。',
  IDEMPOTENCY_CONFLICT: '这一步已经提交过了，刷新后再试。',
  INVALID_ACTION: '这个小游戏不支持这一步。',
  UNAUTHORIZED: '登录后才能玩店铺小游戏。',
  UNAVAILABLE: '没能开始这局小游戏，请稍后再试。',
};
const FALLBACK_TEXT = '没能开始这局小游戏，请稍后再试。';

function reasonText(code) {
  return REASON_TEXT[String(code || '')] || FALLBACK_TEXT;
}

/** 回包(含 409)里的原因码:拒绝在 errorCode,发局不可用在 data.reason。 */
function reasonOf(res) {
  if (!res || typeof res !== 'object') return '';
  if (res.errorCode) return String(res.errorCode);
  const data = res.data && typeof res.data === 'object' ? res.data : {};
  return data.reason ? String(data.reason) : '';
}

function launchBody(poiId, source, fix) {
  const body = { poiId: positiveInt(poiId), source: source === 'CITY_LANDING' ? 'CITY_LANDING' : 'PLACE_PAGE' };
  if (fix && isFinite(fix.lat) && isFinite(fix.lng)) {
    body.lat = Number(fix.lat); body.lng = Number(fix.lng);
    if (fix.accuracy != null && isFinite(fix.accuracy)) body.accuracy = Number(fix.accuracy);
  }
  return body;
}

function finishBody(playId, fix) {
  const body = { playId: positiveInt(playId) };
  if (fix && isFinite(fix.lat) && isFinite(fix.lng)) {
    body.lat = Number(fix.lat); body.lng = Number(fix.lng);
    if (fix.accuracy != null && isFinite(fix.accuracy)) body.accuracy = Number(fix.accuracy);
  }
  return body;
}

/** 发局回包 → 页面状态:能玩 / 今天玩过(直接看结果) / 不能玩(一句人话)。 */
function launchView(data) {
  const d = data || {};
  if (!d.available) return { kind: 'unavailable', text: reasonText(d.reason) };
  const playId = positiveInt(d.playId);
  if (!playId) return { kind: 'unavailable', text: FALLBACK_TEXT };
  const game = d.game || {};
  if (d.playedToday || (d.status && d.status !== 'ISSUED')) return { kind: 'played', playId, gameName: game.nameZh || '' };
  return { kind: 'play', playId, gameName: game.nameZh || '' };
}

const CEILING_TEXT = {
  REMOTE: '你不在店里，这局只记成绩，不发积分。在店里玩赢了才有积分。',
  DEVICE_REPORTED: '这个游戏的成绩由手机记录，只记成绩，不发积分。',
  UNJUDGED_EVIDENCE: '这局的结果没法核对，只记成绩，不发积分。',
};

/** 结果回包 → 看结果页:赢没赢、得了几分、为什么没发积分(rewardCeilingReason 的人话)。 */
function verdictView(data) {
  const d = data || {};
  const status = String(d.status || '');
  const won = d.won === true || status === 'WON';
  const points = Math.max(0, Number(d.points) || 0);
  let headline = won ? '赢了！' : '这局没赢，明天再来。';
  if (status === 'EXPIRED') headline = '这局时间到了，明天再来。';
  let note = '';
  if (won && d.rewardCeilingReason) note = CEILING_TEXT[d.rewardCeilingReason] || '这局只记成绩，不发积分。';
  else if (won && d.capped) note = '今天的积分已经领到上限了，明天再来。';
  return { headline, won, points, pointsText: points > 0 ? ('得到 ' + points + ' 积分。') : '', note };
}

/** 会话结束了(赢、输或超时)才能看结果。 */
function sessionOver(status) {
  return !!status && String(status) !== 'RUNNING';
}

module.exports = {
  LAUNCH_PATH, RESULT_PATH, SESSION_STATE_PATH, SESSION_ACTION_PATH,
  mapRequest, identify, refusal, reasonText, reasonOf, launchBody, finishBody,
  launchView, verdictView, sessionOver, REASON_TEXT,
};

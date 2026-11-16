// W7 x-shop-game-runner:店铺小游戏在据点页真正玩一局 —— 路由映射、本地身份、每个 409 的人话、结果页。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const runner = require('../../pages/play/utils/shop-game-runner.js');

const root = path.join(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

test('只把组件的 start/state/action 换成这局自己的会话路由,开局不带旅程 id', () => {
  assert.deepEqual(runner.mapRequest('/api/play/advanced/start', 'POST', { activityId: 0, topicId: 9, nodeId: 9 }, 9),
    { url: '/api/shop-games/plays/9/session', method: 'POST', data: {} });
  assert.deepEqual(runner.mapRequest('/api/play/advanced/state', 'GET', { sessionId: 31 }, 9),
    { url: '/api/shop-games/sessions/state', method: 'GET', data: { sessionId: 31 } });
  const action = { sessionId: 31, version: 2, idempotencyKey: 'k', action: 'SUBMIT_TRUE_FALSE', payload: { answer: false } };
  assert.deepEqual(runner.mapRequest('/api/play/advanced/action', 'POST', action, 9),
    { url: '/api/shop-games/sessions/action', method: 'POST', data: action });
  for (const url of ['/api/play/advanced/receipt', '/api/play/advanced/leaderboard', '/api/play/nodes']) {
    assert.equal(runner.mapRequest(url, 'GET', {}, 9), null, url);
  }
  assert.equal(runner.mapRequest('/api/play/advanced/start', 'GET', {}, 9), null, 'method bound');
  assert.equal(runner.mapRequest('/api/play/advanced/start', 'POST', {}, 0), null, 'no play, nothing sent');
  assert.equal(runner.refusal().errorCode, 'SHOP_GAME_ROUTE');
});

test('只在本机给成功回包补上这局的本地身份,拒绝原样返回', () => {
  const ok = runner.identify({ code: 200, data: { sessionId: 31, status: 'RUNNING', topicId: null } }, 9);
  assert.equal(ok.data.activityId, 0);
  assert.equal(ok.data.topicId, 9);
  assert.equal(ok.data.nodeId, 9);
  assert.equal(ok.data.sessionId, 31);
  const refused = { code: 409, msg: 'x', errorCode: 'SESSION_CLOSED' };
  assert.equal(runner.identify(refused, 9), refused);
});

test('每个 409 和不可用原因都有一句人话,没有占位词', () => {
  const codes = ['NOT_OPEN', 'SHOP_GAME_NOT_OPEN', 'NOT_A_SHOP', 'NOT_READY', 'TOO_FAR', 'LOCATION_NOT_TRUSTED', 'LOCATION_REQUIRED',
    'SHOP_GAME_NOT_PLAYABLE', 'PLAY_NOT_FINISHED', 'PLAY_NOT_JUDGED', 'PLAY_EXPIRED', 'PLAY_ALREADY_FINISHED', 'PLAY_NOT_FOUND',
    'SESSION_NOT_FOUND', 'SESSION_CLOSED', 'VERSION_CONFLICT', 'IDEMPOTENCY_CONFLICT', 'INVALID_ACTION', 'UNAUTHORIZED'];
  for (const code of codes) {
    const text = runner.reasonText(code);
    assert.ok(text && text === runner.REASON_TEXT[code], code);
    for (const banned of ['即将开放', '敬请期待', '原回执', '回读', 'lease', 'gate']) assert.ok(!text.includes(banned), code);
  }
  assert.ok(runner.reasonText('SOMETHING_NEW'));
  assert.equal(runner.reasonOf({ code: 409, errorCode: 'PLAY_NOT_FINISHED' }), 'PLAY_NOT_FINISHED');
  assert.equal(runner.reasonOf({ code: 200, data: { available: false, reason: 'TOO_FAR' } }), 'TOO_FAR');
});

test('发局:能玩、今天玩过、不能玩', () => {
  assert.deepEqual(runner.launchView({ available: true, playId: 9, status: 'ISSUED', game: { nameZh: '对还是错' } }),
    { kind: 'play', playId: 9, gameName: '对还是错' });
  assert.equal(runner.launchView({ available: true, playId: 9, status: 'WON', playedToday: true }).kind, 'played');
  assert.equal(runner.launchView({ available: false, reason: 'NOT_OPEN' }).text, '店铺小游戏还没开放，开放后就在这里玩。');
  assert.equal(runner.launchView(null).kind, 'unavailable');
  assert.deepEqual(runner.launchBody(900, 'CITY_LANDING', { lat: 30.2, lng: 120.1, accuracy: 12 }),
    { poiId: 900, source: 'CITY_LANDING', lat: 30.2, lng: 120.1, accuracy: 12 });
  assert.equal(runner.launchBody(900, 'ANYTHING', null).source, 'PLACE_PAGE');
  assert.deepEqual(runner.finishBody(9, null), { playId: 9 });
});

test('结果页:积分、不在店里只记成绩、手机自报、封顶', () => {
  assert.deepEqual(runner.verdictView({ status: 'WON', won: true, points: 10 }),
    { headline: '赢了！', won: true, points: 10, pointsText: '得到 10 积分。', note: '' });
  assert.match(runner.verdictView({ status: 'WON', won: true, points: 0, rewardCeilingReason: 'REMOTE' }).note, /不在店里/);
  assert.match(runner.verdictView({ status: 'WON', won: true, rewardCeilingReason: 'DEVICE_REPORTED' }).note, /手机/);
  assert.match(runner.verdictView({ status: 'WON', won: true, rewardCeilingReason: 'UNJUDGED_EVIDENCE' }).note, /核对/);
  assert.match(runner.verdictView({ status: 'WON', won: true, capped: true }).note, /上限/);
  assert.equal(runner.verdictView({ status: 'LOST' }).headline, '这局没赢，明天再来。');
  assert.equal(runner.verdictView({ status: 'EXPIRED' }).headline, '这局时间到了，明天再来。');
  assert.equal(runner.sessionOver('RUNNING'), false);
  assert.equal(runner.sessionOver('COMPLETED'), true);
  assert.equal(runner.sessionOver(''), false);
});

test('页面接线:组件走 shop-play-id,据点页开放时才给入口,页面已注册', () => {
  const component = read('pages/play/components/advanced-game/index.js');
  assert.match(component, /shopPlayId: \{ type: null, value: '' \}/);
  assert.match(component, /shopGameRunner\.mapRequest\(url, method, data, shopPlayId\)/);
  assert.match(component, /return Promise\.resolve\(shopGameRunner\.refusal\(\)\)/);
  const page = read('pages/play/shop-game/index.wxml');
  assert.match(page, /shop-play-id="\{\{playId\}\}"/);
  const js = read('pages/play/shop-game/index.js');
  assert.ok(js.indexOf('runner.LAUNCH_PATH') < js.indexOf('runner.RESULT_PATH'));
  const app = JSON.parse(read('app.json'));
  const play = app.subPackages.find(s => s.root === 'pages/play');
  assert.ok(play.pages.includes('shop-game/index'));
  const detail = read('subpackageMember/components/cy/scene-roam-poi-detail/index.wxml');
  assert.match(detail, /wx:if="\{\{shopGame\.open\}\}"/);
  assert.match(detail, /店铺小游戏还没开放，开放后就在这里玩。/);
  for (const banned of ['即将开放', '敬请期待']) assert.ok(!read('pages/play/shop-game/index.wxml').includes(banned));
});

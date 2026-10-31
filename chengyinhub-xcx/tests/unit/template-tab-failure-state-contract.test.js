'use strict';
// The old page was retired on master. Preserve its failure/pagination/adoption contracts
// on the actual shelf Component and topic-preview caller; do not resurrect the old page API.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '../..');
const TOPIC_URL = '/api/template/topic-template/list';
const GAME_URL = '/api/template/list';
const SHELF = path.join(ROOT, 'pages/templatedetail/shelf/index.js');
const WXML = path.join(ROOT, 'pages/templatedetail/shelf/index.wxml');
const TWO_TOPICS = [{ id: 11, name: '主题A' }, { id: 12, name: '主题B' }];
const game = (id, title) => ({ id, title: title || '今晚的暗号', packType: 0 });
function mountTemplatePage() {
  let definition, owner = '9004';
  const requests = [], navigations = [];
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => owner, ensureLoginFor: () => true,
    getRequestErrorMessage: (res, fallback) => res && res.msg || fallback,
    sendRequest: options => { requests.push(options); } });
  global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ windowWidth: 375 }),
    navigateTo: options => navigations.push(options.url), showToast() {} };
  global.Component = value => { definition = value; };
  delete require.cache[SHELF]; require(SHELF);
  const page = Object.assign({}, definition.methods);
  page.data = JSON.parse(JSON.stringify(definition.data));
  for (const [key, prop] of Object.entries(definition.properties)) page.data[key] = prop.value;
  page.setData = patch => Object.assign(page.data, patch);
  page.triggerEvent = () => {};
  definition.lifetimes.attached.call(page);
  return { page, requests, navigations, definition, owner: value => { owner = value; } };
}
const lastRequest = (h, url) => h.requests.filter(r => r.url === url).at(-1);
function loadTopics(h, rows) { lastRequest(h, TOPIC_URL).success({ code: 200, data: rows }); }
function switchTab(h, key) { h.page.switchTab({ detail: { key } }); }
function tabBlocks(source) {
  // Master renders topic with wx:if and game with the adjacent wx:else.
  // Bound the regions at the actual outer block transition; a missing marker must fail.
  const match = source.match(/<block wx:if="\{\{ tab === 'topic' \}\}">([\s\S]*?)<\/block>\s*<!-- =+ 玩法 =+ -->\s*<block wx:else>([\s\S]*?)<\/block>\s*<!-- 「全部分类」/);
  assert.ok(match, '必须找到互斥的主题/玩法真实外层分支，不能把整页当成某一 tab');
  return { topic: match[1], game: match[2] };
}
function topicBlock(source) { return tabBlocks(source).topic; }

test('主题 tab 成功后切游戏 tab 请求失败:错误态必须可见,主题内容不得冒充游戏列表', () => {
  const h = mountTemplatePage(); loadTopics(h, TWO_TOPICS); switchTab(h, 'game');
  lastRequest(h, GAME_URL).fail({ msg: '网络断开' });
  assert.equal(h.page.data.topicLoaded, true); assert.equal(h.page.data.gameLoaded, false);
  assert.match(h.page.data.gameError, /网络断开/);
  assert.deepEqual(h.page.data.featGames, []); assert.deepEqual(h.page._games, []);
  const wxml = fs.readFileSync(WXML, 'utf8');
  assert.match(tabBlocks(wxml).game, /gameError && !gameLoaded/);
  assert.doesNotMatch(tabBlocks(wxml).game, /topicError|featTopics/, '玩法失败分支不能呈现主题缓存');
  assert.equal(h.page.data.tab, 'game');
});

test('失败后切回主题 tab:缓存内容即刻恢复,错误清空', () => {
  const h = mountTemplatePage(); loadTopics(h, TWO_TOPICS); const cached = h.page.data.featTopics;
  switchTab(h, 'game'); lastRequest(h, GAME_URL).fail({ msg: '游戏失败' }); switchTab(h, 'topic');
  assert.equal(h.page.data.topicLoaded, true); assert.equal(h.page.data.topicError, '');
  assert.equal(h.page.data.featTopics, cached); assert.equal(cached[0].id, 11);
  assert.doesNotMatch(topicBlock(fs.readFileSync(WXML, 'utf8')), /gameError/);
});

test('迟到的游戏响应落在主题 tab 上:只进缓存,不得把未加载的主题 tab 标成已加载', () => {
  const h = mountTemplatePage(); switchTab(h, 'game'); const req = lastRequest(h, GAME_URL); switchTab(h, 'topic');
  req.success({ code: 200, data: { rows: [game(21)], total: 1 } });
  assert.equal(h.page.data.gameLoaded, true); assert.equal(h.page._games[0].id, 21);
  assert.equal(h.page.data.topicLoaded, false); assert.deepEqual(h.page.data.featTopics, []);
  assert.equal(h.page.data.topicError, '');
});

test('迟到的游戏失败落在主题 tab 上:错误文案不得画到主题的错误卡,骨架屏不得被掐掉', () => {
  const h = mountTemplatePage(); switchTab(h, 'game'); const req = lastRequest(h, GAME_URL); switchTab(h, 'topic');
  req.fail({ msg: '游戏失败' });
  assert.equal(h.page.data.topicLoaded, false); assert.equal(h.page.data.topicError, '');
  assert.equal(h.page.data.gameLoaded, false); assert.equal(h.page.data.gameError, '游戏失败');
  assert.match(topicBlock(fs.readFileSync(WXML, 'utf8')), /!topicLoaded && !topicError/);
});

test('请求在途时切去已加载 tab:loading 由当前 tab 独立判定,不留游离的 true', () => {
  const h = mountTemplatePage(); loadTopics(h, TWO_TOPICS); switchTab(h, 'game'); switchTab(h, 'topic');
  assert.equal(h.page.data.topicLoaded, true); assert.equal(h.page.data.topicError, '');
  assert.equal(h.page.data.gameLoaded, false); assert.equal(h.page.data.featTopics.length, 2);
  assert.match(topicBlock(fs.readFileSync(WXML, 'utf8')), /!topicLoaded && !topicError/);
});

function assertSingleTopicEmptyContract(source) {
  const block = topicBlock(source);
  assert.match(block, /wx:elif="\{\{ !featTopics.length \}\}"/);
  assert.match(block, /wx:for="\{\{ featTopics \}\}"/);
}
test('单主题 banner 不能与空态同屏，真实错误条件变体必须被负控抓住', () => {
  const h = mountTemplatePage(); loadTopics(h, [TWO_TOPICS[0]]);
  assert.equal(h.page.data.featTopics.length, 1); assert.equal(h.page.data.featTopics[0].id, 11);
  const source = fs.readFileSync(WXML, 'utf8'); assertSingleTopicEmptyContract(source);
  const mutant = source.replace('!featTopics.length', '!topicShelves.length');
  assert.notEqual(mutant, source); assert.throws(() => assertSingleTopicEmptyContract(mutant));
});

test('开发环境真实空或错误响应不注入假主题或玩法', () => {
  const h = mountTemplatePage(); loadTopics(h, []);
  lastRequest(h, GAME_URL).success({ code: 500, msg: '拒绝' });
  assert.equal(h.page.data.topicLoaded, true); assert.deepEqual(h.page._topics, []);
  assert.deepEqual(h.page.data.featTopics, []); assert.equal(h.page.data.gameLoaded, false);
  assert.deepEqual(h.page._games, []); assert.match(h.page.data.gameError, /拒绝/);
});

test('游戏库按total完整顺序翻页，未收齐前不发布缓存或成功态', () => {
  const h = mountTemplatePage();
  const rows = Array.from({ length: 50 }, (_, i) => game(1000 + i, '填充玩法' + i));
  const first = lastRequest(h, GAME_URL); first.success({ code: 200, data: { rows, total: 51 } });
  assert.equal(h.page.data.gameLoaded, false); assert.deepEqual(h.page._games, []);
  const second = lastRequest(h, GAME_URL); assert.notEqual(second, first);
  assert.equal(first.data.pageSize, 50); assert.equal(second.data.pageNum, 2);
  second.success({ code: 200, data: { rows: [game(21)], total: 51 } });
  assert.equal(h.page.data.gameLoaded, true); assert.equal(h.page.data.gameError, '');
  assert.equal(h.page._games.length, 1, '保留 master 的25玩法策展规则，不把填充数据上架');
  assert.equal(h.page._games[0].id, 21, '最后一页原玩法必须进入完整结果');
});

test('游戏库分页中断或提前空页不能把已收集子集伪装成成功', () => {
  for (const stop of ['network', 'empty']) {
    const h = mountTemplatePage();
    lastRequest(h, GAME_URL).success({ code: 200, data: { rows: [game(21)], total: 2 } });
    const second = lastRequest(h, GAME_URL);
    if (stop === 'network') second.fail({ msg: '第二页失败' });
    else second.success({ code: 200, data: { rows: [], total: 2 } });
    assert.equal(h.page.data.gameLoaded, false); assert.deepEqual(h.page._games, []);
    assert.ok(h.page.data.gameError); assert.deepEqual(h.page.data.featGames, []);
  }
});

test('货架离页或账号/作用域代次改变后旧分页不能继续、覆盖缓存或结束新loading', () => {
  for (const mutate of [h => h.owner('other'), h => { h.page.data.scope = 'MERCHANT'; },
    h => h.definition.pageLifetimes.hide.call(h.page), h => h.definition.lifetimes.detached.call(h.page)]) {
    const h = mountTemplatePage(); const first = lastRequest(h, GAME_URL), count = h.requests.length;
    mutate(h); first.success({ code: 200, data: { rows: [game(21)], total: 2 } });
    assert.equal(h.requests.length, count); assert.deepEqual(h.page._games, []);
    assert.equal(h.page.data.gameLoaded, false); assert.equal(h.page.data.gameError, '');
  }
});

test('整套主题货架沿原整包采用进入新主题草稿，不混用小游戏库ID或adopt-draft', () => {
  const h = mountTemplatePage(); loadTopics(h, [{ id: 31, name: '完整路线游戏', templateStatus: 'VERIFIED' }]);
  h.page.openTopic({ currentTarget: { dataset: { item: h.page.data.featTopics[0] } } });
  assert.equal(h.navigations[0], '/pages/templatedetail/topic-preview/index?id=31');
  let definition; global.Page = value => { definition = value; };
  // 2026-10-30 V-24:预览页「查看详情」进主题模板详情,「应用」在详情页,复制链路不变
  const file = path.join(ROOT, 'pages/templatedetail/topic-preview/index.js'); delete require.cache[file]; require(file);
  const preview = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  preview.setData = patch => Object.assign(preview.data, patch); preview.onLoad({ id: '31' }); preview.goDetail();
  assert.equal(h.navigations[1], '/pages/templatedetail/topic-detail/index?id=31');
  const detailFile = path.join(ROOT, 'pages/templatedetail/topic-detail/index.js'); delete require.cache[detailFile]; require(detailFile);
  const detail = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  detail.setData = patch => Object.assign(detail.data, patch); detail.onLoad({ id: '31' }); detail.useTemplate();
  const use = lastRequest(h, '/api/template/topic-template/use');
  assert.deepEqual(use.data, { id: '31', scope: '' });
  assert.equal(h.navigations.length, 2); use.success({ code: 200, data: { topicId: 8031 } });
  assert.equal(h.navigations[2], '/pages/publish/fabu/index?id=8031');
  assert.equal(h.requests.some(r => r.url === '/api/template/adopt-draft'), false);
});

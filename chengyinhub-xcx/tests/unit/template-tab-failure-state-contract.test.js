// 模板页两 tab 失败态契约(2026-08-27)。
//
// 病灶:listLoaded 是两 tab 共用的布尔,switchTab 又不清 banner/topList/tailList ——
// 主题 tab 成功后切游戏 tab、游戏请求失败时,wxml 的错误态(errorMsg && !listLoaded)
// 永远不渲染,页面继续拿主题内容冒充游戏 tab,连重试入口都没有。
// 本契约钉住:listLoaded 按当前 tab 判定、切到未加载 tab 先清屏、失败必须能看到错误态;
// 另钉一条:空态判定必须把 banner 计入(单条数据时 banner 有值、两个列表全空,
// cy-empty 不得与 banner 大卡同屏)。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_JS = path.join(ROOT, 'pages/template/index.js');
const PAGE_WXML = path.join(ROOT, 'pages/template/index.wxml');

const TOPIC_URL = '/api/template/topic-template/list';
const GAME_URL = '/api/template/list';

function mountTemplatePage() {
  const requests = [];
  let definition;
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44, menuButtonInfo: {} },
    getUserRole: () => 'player',
    getUserType: () => 0,
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(options) { requests.push(options); },
  };
  global.getApp = () => app;
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showToast() {},
    navigateTo() {},
  };
  global.Page = (config) => { definition = config; };
  delete require.cache[require.resolve(PAGE_JS)];
  require(PAGE_JS);
  const page = Object.assign({}, definition);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = function (patch) { Object.assign(this.data, patch); };
  return { page, requests };
}

function lastRequest(h, url) {
  return h.requests.filter((r) => r.url === url).at(-1);
}

function loadTopics(h, rows) {
  h.page.getTopicTemplates();
  const req = lastRequest(h, TOPIC_URL);
  req.success({ code: '200', data: rows });
  req.complete();
}

const TWO_TOPICS = [
  { id: 11, name: '主题A', templateStatus: 'VERIFIED' },
  { id: 12, name: '主题B', templateStatus: 'VERIFIED' },
];

test('主题 tab 成功后切游戏 tab 请求失败:错误态必须可见,主题内容不得冒充游戏列表', () => {
  const h = mountTemplatePage();
  loadTopics(h, TWO_TOPICS);
  assert.equal(h.page.data.listLoaded, true);
  assert.equal(h.page.data.banner.id, 11);

  h.page.switchTab({ detail: { key: 'game' } });
  const gameReq = lastRequest(h, GAME_URL);
  assert.ok(gameReq, '切到未加载的游戏 tab 必须发起请求');
  gameReq.fail({ msg: '网络断开' });
  gameReq.complete();

  assert.match(h.page.data.errorMsg, /网络断开/);
  // wxml 的错误态条件是 errorMsg && !listLoaded:listLoaded 若沿用主题 tab 的 true,
  // 错误卡和重试按钮都不会渲染 —— 这就是「旧数据冒充新数据」的闸门位。
  assert.equal(h.page.data.listLoaded, false,
    'listLoaded 必须按当前 tab 判定:游戏 tab 从未拿到过数据');
  assert.equal(h.page.data.topList.length, 0, '主题列表不得留在屏上冒充游戏 tab');
  assert.equal(h.page.data.tailList.length, 0, '主题列表不得留在屏上冒充游戏 tab');
  assert.ok(!h.page.data.banner.id, '主题 banner 不得留在屏上冒充游戏 tab');
});

test('失败后切回主题 tab:缓存内容即刻恢复,错误清空', () => {
  const h = mountTemplatePage();
  loadTopics(h, TWO_TOPICS);
  h.page.switchTab({ detail: { key: 'game' } });
  const gameReq = lastRequest(h, GAME_URL);
  gameReq.fail({ msg: '网络断开' });
  gameReq.complete();

  h.page.switchTab({ detail: { key: 'topic' } });
  assert.equal(h.page.data.errorMsg, '');
  assert.equal(h.page.data.listLoaded, true);
  assert.equal(h.page.data.banner.id, 11, '已加载 tab 的缓存必须原样回来');
});

test('迟到的游戏响应落在主题 tab 上:只进缓存,不得把未加载的主题 tab 标成已加载', () => {
  const h = mountTemplatePage();
  // 用户先切到游戏(请求在途),又切回主题(主题请求也在途)
  h.page.switchTab({ detail: { key: 'game' } });
  h.page.switchTab({ detail: { key: 'topic' } });
  const gameReq = lastRequest(h, GAME_URL);
  gameReq.success({ code: '200', data: { rows: [{ id: 21, title: '游戏A' }] } });
  gameReq.complete();

  assert.equal(h.page._gameLoaded, true, '数据照常进缓存，回到游戏 tab 时直接可用');
  assert.equal(h.page.data.listLoaded, false,
    '主题还没回来:游戏响应把 listLoaded 置 true 会让主题 tab 渲染成假空态');
  assert.equal(h.page.data.topList.length, 0);
});

test('迟到的游戏失败落在主题 tab 上:错误文案不得画到主题的错误卡,骨架屏不得被掐掉', () => {
  const h = mountTemplatePage();
  h.page.switchTab({ detail: { key: 'game' } });
  h.page.switchTab({ detail: { key: 'topic' } });
  const gameReq = lastRequest(h, GAME_URL);
  // 游戏请求迟到失败:此刻用户在主题 tab、主题请求仍在途(loading:true)
  gameReq.fail({ errMsg: 'request:fail timeout' });
  gameReq.complete();

  assert.equal(h.page.data.errorMsg, '',
    '游戏的失败文案画在主题的错误卡上 —— 失败半边也必须按当前 tab 闸');
  assert.equal(h.page.data.loading, true,
    '游戏的 complete 把主题在途的骨架屏掐成了空白 —— complete 也必须按当前 tab 闸');
  assert.equal(h.page._gameLoaded, false, '失败不得进缓存，切回游戏 tab 要重新加载');
});

test('请求在途时切去已加载 tab:loading 由 switchTab 显式清掉,不留游离的 true', () => {
  const h = mountTemplatePage();
  loadTopics(h, [{ id: 41, name: '主题A', templateStatus: 'VERIFIED' }, { id: 42, name: '主题B' }]);
  h.page.switchTab({ detail: { key: 'game' } });   // 游戏未加载,请求在途 loading:true
  assert.equal(h.page.data.loading, true);
  h.page.switchTab({ detail: { key: 'topic' } });  // 切回已加载的主题:不发请求
  assert.equal(h.page.data.loading, false,
    '游戏的 complete 已按 tab 闸不再保底,switchTab 不显式清就永远 true');
});

// ===== P2:空态判定必须把 banner 计入 =====
function assertEmptyStateCountsBanner(wxml) {
  const empty = wxml.match(/<cy-empty[^>]*wx:elif="\{\{([^}]*)\}\}"/);
  assert.ok(empty, 'cy-empty 的 wx:elif 条件不存在，锚点失效');
  assert.match(empty[1], /!\s*banner\.id/,
    '空态条件必须包含 !banner.id —— 单条数据时 banner 占用了唯一内容、两个列表全空，只看列表会让 cy-empty 与 banner 大卡同屏');
}

test('单条数据时 banner 有值、两个列表全空:空态判定必须把 banner 计入', () => {
  const h = mountTemplatePage();
  loadTopics(h, [{ id: 31, name: '唯一主题', templateStatus: 'VERIFIED' }]);
  // 数据形态:banner 吃掉唯一一条,topList/tailList 全空 —— 同屏风险的前提成立
  assert.equal(h.page.data.banner.id, 31);
  assert.equal(h.page.data.topList.length, 0);
  assert.equal(h.page.data.tailList.length, 0);
  assert.equal(h.page.data.listLoaded, true);

  const wxml = fs.readFileSync(PAGE_WXML, 'utf8');
  assertEmptyStateCountsBanner(wxml);

  // 负控:把 !banner.id 摘掉,检查器必须真的判红
  const mutant = wxml.replace(/!banner\.id\s*&&\s*/, '');
  if (mutant !== wxml) {
    assert.throws(() => assertEmptyStateCountsBanner(mutant),
      '摘掉 !banner.id 后检查器仍然放行 —— 这条断言是摆设');
  }
});

test('开发版主题模板真实空结果和故障不注入演示主题', () => {
  for(const response of [{code:200,data:[]},{code:500,msg:'真实故障'}]){
    const h=mountTemplatePage();global.wx.getAccountInfoSync=()=>({miniProgram:{envVersion:'develop'}});
    h.page.getTopicTemplates();lastRequest(h,TOPIC_URL).success(response);lastRequest(h,TOPIC_URL).complete();
    assert.equal(h.page.data.topList.length,0);assert.equal(h.page.data.tailList.length,0);assert.ok(!h.page.data.banner.id);
    if(response.code===200)assert.equal(h.page.data.listLoaded,true);else assert.equal(h.page.data.errorMsg,'真实故障');
  }
});

test('游戏库按原total完整分页，第一页complete不提前结束loading或缓存截断结果', () => {
  const h = mountTemplatePage(); h.page.data.tab = 'game';
  h.page.getGameRows();
  const first = lastRequest(h, GAME_URL);
  assert.deepEqual(first.data, { pageNum: 1, pageSize: 200, keyword: '' });
  first.success({ code: 200, data: { rows: Array.from({ length: 200 }, (_, i) => ({ id: i + 1, title: '内容' + i })), total: 201 } });
  first.complete();
  assert.equal(h.page.data.loading, true);
  assert.equal(h.page._gameLoaded, false);
  const second = lastRequest(h, GAME_URL);
  assert.deepEqual(second.data, { pageNum: 2, pageSize: 200, keyword: '' });
  second.success({ code: 200, data: { rows: [{ id: 501, title: '未入首屏的新内容' }], total: 201 } });
  second.complete();
  assert.equal(h.page.data.loading, false);
  assert.equal(h.page._gameLoaded, true);
  assert.equal(h.page._gameRows.length, 201);
  assert.equal(h.page._gameRows.at(-1).id, 501);
});

test('完整游戏库翻页失败或总数前空页不得缓存部分库为成功', () => {
  for (const stop of ['network', 'empty']) {
    const h = mountTemplatePage(); h.page.data.tab = 'game';
    h.page.getGameRows(); const first = lastRequest(h, GAME_URL);
    first.success({ code: 200, data: { rows: [{ id: 21, title: '一条内容' }], total: 2 } });
    first.complete(); const second = lastRequest(h, GAME_URL);
    if (stop === 'network') second.fail({ msg: '第二页失败' });
    else second.success({ code: 200, data: { rows: [], total: 2 } });
    second.complete();
    assert.equal(h.page._gameLoaded, false);
    assert.equal(h.page._gameRows.length, 0);
    assert.ok(h.page.data.errorMsg);
    assert.equal(h.page.data.loading, false);
  }
});

test('游戏库搜索词或页面代次改变后旧分页不能继续、覆盖缓存或结束新loading', () => {
  for (const mutate of [
    p => { p.data.searchKeyword = '另一主题'; },
    p => { p._loginContinuationEpoch = (p._loginContinuationEpoch || 0) + 1; },
  ]) {
    const h = mountTemplatePage(); h.page.data.tab = 'game'; h.page.getGameRows();
    const first = lastRequest(h, GAME_URL), count = h.requests.length;
    mutate(h.page);
    first.success({ code: 200, data: { rows: [{ id: 21, title: '旧搜索内容' }], total: 2 } });
    first.complete();
    assert.equal(h.requests.length, count, '旧上下文不得继续取下一页');
    assert.equal(h.page._gameRows.length, 0);
    assert.equal(h.page._gameLoaded, false);
    assert.equal(h.page.data.loading, true);
  }
});

test('整套主题货架沿原整包采用进入新主题草稿，不混用小游戏库ID或adopt-draft', () => {
  const h = mountTemplatePage(); let destination = '';
  wx.navigateTo = options => { destination = options.url; };
  loadTopics(h, [{ id: 31, name: '完整路线游戏', templateStatus: 'VERIFIED' }]);
  h.page.goTopicConfig({ currentTarget: { dataset: { item: h.page.data.banner } } });
  const use = lastRequest(h, '/api/template/topic-template/use');
  assert.deepEqual(use.data, { id: 31, scope: '' });
  assert.equal(destination, '');
  use.success({ code: 200, data: { topicId: 8031 } });
  assert.equal(destination, '/pages/publish/fabu/index?id=8031');
  assert.equal(h.requests.some(r => r.url === '/api/template/adopt-draft'), false);
});

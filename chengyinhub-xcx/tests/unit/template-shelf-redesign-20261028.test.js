// 2026-10-28 业主定稿原型 v21:模板页货架(tpl-shelf)+ 主题卡堆预览(topic-preview)。
// 旧模板页列表的契约(切 tab 失败态、配置入口、封面失败态、复制在途闸……)在新位置逐条重钉。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const SHELF_JS = path.join(ROOT, 'pages/templatedetail/shelf/index.js');
const PREVIEW_JS = path.join(ROOT, 'pages/templatedetail/topic-preview/index.js');
// 2026-10-30 V-24:「应用」(整包复制)从卡堆预览搬到主题模板详情
const DETAIL_JS = path.join(ROOT, 'pages/templatedetail/topic-detail/index.js');
const art = require('../../pages/templatedetail/shelf/art.js');

function stubApp(extra) {
  const requests = [];
  const app = Object.assign({
    globalData: { statusBarHeight: 20, navBarHeight: 44, menuButtonInfo: {} },
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(options) { requests.push(options); }
  }, extra || {});
  global.getApp = () => app;
  return { app, requests };
}

function stubWx() {
  const navigations = [];
  const toasts = [];
  global.wx = {
    navigateTo(o) { navigations.push(o.url); },
    showToast(o) { toasts.push(o.title); },
    getStorageSync: () => '',
    getWindowInfo: () => ({ windowWidth: 375 }),
    setNavigationBarColor() {}
  };
  global.getCurrentPages = () => [];
  return { navigations, toasts };
}

function mountShelf(props) {
  const env = stubApp();
  const wxEnv = stubWx();
  let def;
  global.Component = (d) => { def = d; };
  delete require.cache[SHELF_JS];
  require(SHELF_JS);
  const vm = Object.assign({}, def.methods);
  vm.data = JSON.parse(JSON.stringify(def.data));
  Object.keys(def.properties).forEach((k) => { vm.data[k] = def.properties[k].value; });
  Object.assign(vm.data, props || {});
  vm.setData = function (patch) { Object.assign(this.data, patch); };
  vm.events = [];
  vm.triggerEvent = function (name, detail) { this.events.push([name, detail]); };
  Object.keys(def.methods).forEach((k) => { vm[k] = def.methods[k].bind(vm); });
  def.lifetimes.attached.call(vm);
  return Object.assign({ vm, def }, env, wxEnv);
}

function mountPreview(query, appExtra, wxExtra, file) {
  const env = stubApp(appExtra);
  const wxEnv = stubWx();
  Object.assign(global.wx, wxExtra || {});
  let def;
  global.Page = (d) => { def = d; };
  delete require.cache[file || PREVIEW_JS];
  require(file || PREVIEW_JS);
  const page = Object.assign({}, def);
  page.data = JSON.parse(JSON.stringify(def.data));
  page.setData = function (patch) { Object.assign(this.data, patch); };
  page.onLoad(query || { id: '7' });
  return Object.assign({ page }, env, wxEnv);
}

const ok = (data) => ({ code: '200', data });
const flush = () => new Promise((r) => setImmediate(r));
const respond = async (req, res) => { req.success(res); await flush(); await flush(); };

function gameRow(id, title, extra) {
  return Object.assign({ id, title, players: '单人/双人', duration: 5, packType: 0 }, extra || {});
}

// ---------------- 玩法：翻页、保留清单、去重 ----------------

test('玩法列表按后端单页 50 条翻页取全，38–49 这批不再被截掉', async () => {
  const { vm, requests } = mountShelf();
  const gameReq = () => requests.filter((r) => r.url === '/api/template/list');
  assert.equal(gameReq().length, 1);
  assert.deepEqual(gameReq()[0].data, { pageNum: 1, pageSize: 50, pack_type: 0 });
  const page1 = Array.from({ length: 50 }, (_, i) => gameRow(1000 + i, '填充玩法' + i));
  page1[0] = gameRow(89, '闭眼味觉师');
  await respond(gameReq()[0], ok({ rows: page1, total: 52 }));
  assert.equal(gameReq().length, 2, '满 50 条且没到 total 就要取下一页');
  assert.equal(gameReq()[1].data.pageNum, 2);
  await respond(gameReq()[1], ok({ rows: [gameRow(38, '今晚的暗号'), gameRow(12, '闭眼味觉师')], total: 52 }));
  assert.equal(vm.data.gameLoaded, true);
  const titles = vm._games.map((g) => g.title);
  assert.ok(titles.includes('今晚的暗号'), '第二页的玩法要进货架');
  assert.equal(titles.filter((t) => t === '闭眼味觉师').length, 1, '同名种子只留一条');
  assert.equal(vm._games.find((g) => g.title === '闭眼味觉师').id, 89, '同名留 id 最大的那条');
  assert.ok(!titles.some((t) => /^填充玩法/.test(t)), '保留清单之外的玩法不上货架');
});

test('只上保留的 25 个单节点玩法：剧情包 / IP 包与主题自带的 G 段游戏不进来', async () => {
  const { vm, requests } = mountShelf();
  const req = requests.find((r) => r.url === '/api/template/list');
  await respond(req, ok({ rows: [
    gameRow(81, '一桌任务宾果', { players: '2-4人同桌', duration: 8 }),
    gameRow(16, 'G0｜30秒生活掉线测试'),
    gameRow(55, '分支故事接力', { packType: 1 }),
    gameRow(9, '很时尚')
  ], total: 4 }));
  assert.deepEqual(vm._games.map((g) => g.title), ['一桌任务宾果']);
  assert.equal(Object.keys(art.GAMES).length, 25);
});

test('玩法精选 = App Store 卡：分组色眉标 + 两行推荐语 + 包内封面 + 渐变取色', async () => {
  const { vm, requests } = mountShelf();
  const rows = art.FEATURED_GAMES.map((f, i) => gameRow(200 + i, f[0]));
  await respond(requests.find((r) => r.url === '/api/template/list'), ok({ rows, total: rows.length }));
  assert.deepEqual(vm.data.featGames.map((g) => g.title), art.FEATURED_GAMES.map((f) => f[0]));
  const first = vm.data.featGames[0];
  assert.equal(first.groupName, '小队协作');
  assert.equal(first.lead, '三项任务，一桌人。');
  assert.match(first.cover, /^\/pages\/templatedetail\/art\/covers\/game\/.+\.jpg$/);
  assert.match(first.tintEnd, /^rgba\(\d+,\d+,\d+,0\.88\)$/);
  const wxml = read('pages/templatedetail/shelf/index.wxml');
  assert.match(wxml, /class="ap__eyebrow"[\s\S]*class="ap__blurb"[\s\S]*class="ap__lead"/);
  assert.match(wxml, /class="ap__pill">使用</);
  assert.match(read('pages/templatedetail/shelf/index.wxss'), /\.ap__blurb \{[^}]*-webkit-line-clamp: 2/, '推荐语最多两行');
});

test('玩法标签：分类页 = 两列方块，选中标签只换标签下方，精选与 tab 不动', async () => {
  const { vm, requests } = mountShelf();
  const rows = ['一桌任务宾果', '今晚的暗号', '低碳行动'].map((t, i) => gameRow(300 + i, t, { players: t === '一桌任务宾果' ? '2-4人同桌' : '单人/不限' }));
  await respond(requests.find((r) => r.url === '/api/template/list'), ok({ rows, total: 3 }));
  vm.setData({ tab: 'game' });
  const feat = JSON.stringify(vm.data.featGames);
  vm.pickTag({ currentTarget: { dataset: { key: 'shop' } } });
  assert.equal(vm.data.gf, 'shop');
  assert.deepEqual(vm.data.gameCat.items.map((g) => g.title), ['今晚的暗号']);
  assert.equal(JSON.stringify(vm.data.featGames), feat);
  assert.equal(vm.data.tab, 'game');
  assert.match(read('pages/templatedetail/shelf/index.wxss'), /\.gg \{[^}]*grid-template-columns: 1fr 1fr/);
  vm.pickTag({ currentTarget: { dataset: { key: 'all' } } });
  assert.equal(vm.data.gameCat, null);
});

// ---------------- 主题：时长、状态、封面、标签 ----------------

function topic(id, name, extra) {
  return Object.assign({ id, name, subtitle: '副标题', totalTime: 5400, templateCount: 5, chapterCount: 1, productType: 2, templateStatus: 'EXPERIMENTAL' }, extra || {});
}

test('主题时长按秒换算(不再把 14400 秒写成「14400分钟」),未验证的主题标「实验模板」', async () => {
  const { vm, requests } = mountShelf();
  await respond(requests.find((r) => r.url === '/api/template/topic-template/list'), ok([
    topic(1, '风味巡游', { totalTime: 14400 }),
    topic(2, '不惑之年', { totalTime: 7800, templateStatus: 'VERIFIED' })
  ]));
  const [a, b] = vm._topics;
  assert.equal(a.meta, '实验模板 · 5 个玩法 · 4 小时');
  assert.equal(b.meta, '5 个玩法 · 2 小时10 分');
  assert.equal(a.cover, '/pages/templatedetail/art/covers/topic/flavor-tour.jpg', '已知主题用包内封面');
});

test('不在包内的主题用接口封面，拉不动时按稳定 id 换成既有失败态', async () => {
  const { vm, requests } = mountShelf();
  await respond(requests.find((r) => r.url === '/api/template/topic-template/list'), ok([
    topic(5, '某个新主题', { imgUrl: 'https://cdn.example/x.jpg' }), topic(6, '风味巡游')
  ]));
  assert.equal(vm._topics[0].cover, 'https://cdn.example/x.jpg');
  vm.onCoverError({ currentTarget: { dataset: { id: 999 } } });
  assert.equal(vm._topics[0].cover, 'https://cdn.example/x.jpg', '对不上 id 的迟到 error 不标错卡');
  vm.onCoverError({ currentTarget: { dataset: { id: 5 } } });
  assert.equal(vm._topics[0].cover, '');
  assert.equal(vm.data.coverErrorSrc, '/images/no_data.svg');
  const wxml = read('pages/templatedetail/shelf/index.wxml');
  ['lc__img', 'r5__pic', 'mc__pic', 'tci__pic'].forEach((cls) => {
    assert.match(wxml, new RegExp('<image wx:if="\\{\\{ item\\.cover \\}\\}" class="' + cls + '"[^>]*binderror="onCoverError"'));
    assert.match(wxml, new RegExp('class="' + cls + ' cv-err"[\\s\\S]*?封面暂不可用'));
  });
});

test('主题标签只换标签下方(分类页带描述),精选大卡、tab 名与光晕都不动', async () => {
  const { vm, requests } = mountShelf();
  await respond(requests.find((r) => r.url === '/api/template/topic-template/list'), ok([
    topic(1, '风味巡游'), topic(2, '高温末日逃生'), topic(3, '生活不掉线', { totalTime: 360 })
  ]));
  const feat = JSON.stringify(vm.data.featTopics);
  const glow = vm.data.glow;
  vm.pickTag({ currentTarget: { dataset: { key: 'survival' } } });
  assert.equal(vm.data.tf, 'survival');
  assert.deepEqual(vm.data.topicCat.items.map((t) => t.name), ['高温末日逃生']);
  assert.equal(JSON.stringify(vm.data.featTopics), feat);
  assert.equal(vm.data.glow, glow);
  assert.ok(vm.data.topicTags.every((t) => t.key !== 'scifi'), '没有内容的标签不出现');
  assert.match(read('pages/templatedetail/shelf/index.wxml'), /class="tci__desc">\{\{ item\.desc \}\}/);
});

test('标签图标与文字之间约 4 个空格(18px = 36rpx)', () => {
  assert.match(read('pages/templatedetail/shelf/index.wxss'), /\.tg__ic \{[^}]*margin: -8rpx 36rpx -8rpx -16rpx/);
});

test('主题模板没有场地：货架与预览不出现公里、站点、地点或路线', () => {
  const src = ['pages/templatedetail/shelf/index.wxml', 'pages/templatedetail/shelf/index.js',
    'pages/templatedetail/topic-preview/index.wxml', 'pages/templatedetail/topic-preview/index.js'].map(read).join('\n');
  assert.doesNotMatch(src, /totalMileage|locationCount|公里|km\b|个地点|路线/);
});

// ---------------- 两个 tab 各管各的加载 / 失败 ----------------

test('玩法 tab 失败只画玩法错误卡，主题已加载的内容不受影响；迟到响应不覆盖新一轮', async () => {
  const { vm, requests } = mountShelf();
  await respond(requests.find((r) => r.url === '/api/template/topic-template/list'), ok([topic(1, '风味巡游')]));
  await respond(requests.find((r) => r.url === '/api/template/list'), { code: '500', msg: '服务忙' });
  assert.equal(vm.data.gameError, '服务忙');
  assert.equal(vm.data.topicError, '');
  assert.equal(vm.data.topicLoaded, true);
  const stale = requests.filter((r) => r.url === '/api/template/topic-template/list');
  vm.loadTopics();
  const fresh = requests.filter((r) => r.url === '/api/template/topic-template/list').pop();
  await respond(fresh, ok([topic(2, '不惑之年')]));
  await respond(stale[0], ok([topic(9, '预制人生')]));
  assert.deepEqual(vm._topics.map((t) => t.name), ['不惑之年'], '旧一轮的回包不得覆盖');
  const wxml = read('pages/templatedetail/shelf/index.wxml');
  assert.match(wxml, /<cy-error wx:elif="\{\{ gameError && !gameLoaded \}\}"[^>]*bindretry="retry"/);
  assert.match(wxml, /<cy-empty wx:elif="\{\{ !featTopics\.length \}\}"[^>]*kind="empty"/);
});

test('刷新失败保留已在屏的货架(不整页换成错误)', async () => {
  const { vm, requests } = mountShelf();
  await respond(requests.find((r) => r.url === '/api/template/topic-template/list'), ok([topic(1, '风味巡游')]));
  vm.loadTopics();
  await respond(requests.filter((r) => r.url === '/api/template/topic-template/list').pop(), { code: '500' });
  assert.equal(vm.data.topicError, '');
  assert.equal(vm.data.featTopics.length, 1);
});

test('货架拒绝含空元素的畸形列表', async () => {
  const { vm, requests } = mountShelf();
  await respond(requests.find((r) => r.url === '/api/template/topic-template/list'), ok([topic(1, '风味巡游'), null]));
  assert.equal(vm.data.topicLoaded, false);
  assert.ok(vm.data.topicError);
  await respond(requests.find((r) => r.url === '/api/template/list'), ok({ rows: [gameRow(81, '一桌任务宾果'), null], total: 2 }));
  assert.equal(vm.data.gameLoaded, false);
  assert.ok(vm.data.gameError);
});

// ---------------- 入口 ----------------

test('主题点开进卡堆预览(带商家作用域与只读标记),玩法点开进既有详情页；实验预览不跳', () => {
  const { vm, navigations } = mountShelf({ scope: 'MERCHANT', readonly: true });
  vm.openTopic({ currentTarget: { dataset: { item: { id: 7 } } } });
  vm.openGame({ currentTarget: { dataset: { item: { id: 81 } } } });
  assert.deepEqual(navigations, [
    '/pages/templatedetail/topic-preview/index?id=7&scope=MERCHANT&readonly=1',
    '/pages/templatedetail/templatedetail?id=81&operationScope=MERCHANT'
  ]);
  vm.openTopic({ currentTarget: { dataset: { item: { id: 8, previewOnly: true } } } });
  assert.equal(navigations.length, 2);
  assert.deepEqual(vm.events.pop(), ['toast', { text: '这是预览模板，保存后才能查看' }]);
});

test('模板页只留顶栏、发布入口与底栏；货架走分包异步组件，搜索进统一搜索页', () => {
  const json = JSON.parse(read('pages/template/index.json'));
  assert.equal(json.usingComponents['tpl-shelf'], '/pages/templatedetail/shelf/index');
  assert.equal(json.componentPlaceholder['tpl-shelf'], 'view', '主包页面引用分包组件必须给占位');
  const js = read('pages/template/index.js');
  assert.doesNotMatch(js, /\/api\/template\/(list|topic-template)/, '列表请求只在货架组件里发');
  assert.match(js, /goSearch\(\) \{ wx\.navigateTo\(\{ url: '\/pages\/search2\/index' \}\); \}/);
  assert.match(read('pages/template/index.wxml'), /<tpl-shelf [^>]*bind:search="goSearch"/);
  assert.match(read('pages/templatedetail/shelf/index.wxss'), /\.sh-search \{[^}]*height: 88rpx/, '搜索入口保留 88rpx 整行触控高度');
  assert.match(read('pages/templatedetail/shelf/index.wxml'), /<cy-tabs class="sh-tabs" variant="icon"/, 'tab 走 cy-tabs');
});

// ---------------- 包内图 ----------------

test('包内图齐全：25 个玩法封面与道具、全部主题封面、标签道具都真实存在，分包不超 2MB', () => {
  const files = [];
  Object.keys(art.GAMES).forEach((t) => { const a = art.gameArt(t); files.push(a.cover, a.prop); });
  Object.keys(art.THEMES).forEach((n) => files.push(art.topicArt(n).cover));
  art.TOPIC_TAGS.concat(art.GAME_TAGS).forEach((t) => files.push(art.propSrc(t.icon)));
  files.push('/images/tpl-tab-topic.svg', '/images/tpl-tab-game.svg');
  files.forEach((f) => assert.ok(fs.existsSync(path.join(ROOT, f)), f + ' 不存在'));
  let bytes = 0;
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p); else bytes += fs.statSync(p).size;
  });
  walk(path.join(ROOT, 'pages/templatedetail'));
  assert.ok(bytes < 2 * 1024 * 1024, '分包 ' + bytes + ' 字节，超过微信单分包 2MB');
});

test('玩法详情页对货架上的玩法用包内封面(线上 imgUrl 为空也有图)', () => {
  const src = read('pages/templatedetail/templatedetail.js');
  assert.match(src, /const shelfArt = require\('\.\/shelf\/art\.js'\);/);
  assert.equal((src.match(/coverUrl: detailCover\(normalized\)|target\.coverUrl = detailCover\(normalized\)/g) || []).length, 2);
  assert.equal(art.gameArt('拍下这一刻').cover, '/pages/templatedetail/art/covers/game/photo-moment.jpg');
  assert.equal(art.gameArt('不存在的玩法'), null);
});

// ---------------- 卡堆预览 ----------------

function info(extra) {
  return Object.assign({ id: 7, name: '异常事务局：不存在的坐标', productType: 1, totalTime: 5400, finishMedalName: 'A-00 临时调查员',
    games: ['档案一', '档案二', '档案三', '档案四', '终局'].map((t) => ({ title: t, duration: 10 })) }, extra || {});
}

test('城市定向：封面 + 每关一张卡，末关是三选一结局；✓ 清单只有关数、结局、时长、勋章', async () => {
  const { page, requests } = mountPreview({ id: '7' });
  assert.deepEqual(requests[0].data, { id: '7' });
  await respond(requests[0], ok(info()));
  const cards = page.data.cards;
  assert.equal(cards.length, 6);
  assert.equal(cards[0].cover, true);
  assert.deepEqual(cards[0].steps, ['档案一', '档案二', '档案三', '档案四', '终局']);
  assert.deepEqual(cards[1].steps, ['读档案', '做任务，拿证据', '解锁下一关']);
  assert.deepEqual(cards[5].steps, ['汇总证据', '三选一结局', '领取勋章']);
  assert.equal(cards[1].foot, '10 分钟');
  assert.deepEqual(page.data.checks, ['5 关剧情，每关一个玩法', '最后一关三选一结局', '约 1 小时30 分', '勋章「A-00 临时调查员」']);
  assert.equal(page.data.cover, '/pages/templatedetail/art/covers/topic/anomaly-coordinate.jpg');
});

test('自由探索 1 个玩法 / 生活不掉线多章：卡背与 ✓ 清单各按自己的形态', async () => {
  const solo = mountPreview({ id: '1' });
  await respond(solo.requests[0], ok(info({ name: '风味巡游', productType: 2, totalTime: 14400, games: [{ title: '单人探索', duration: 0 }] })));
  assert.deepEqual(solo.page.data.cards[1].steps, ['随时开始', '任选一个轻动作', '玩满 3 次通关']);
  assert.equal(solo.page.data.cards[1].foot, '4 小时');
  assert.deepEqual(solo.page.data.checks, ['1 个玩法，一个人就能玩', '随时开始，可以跨天', '约 4 小时']);
  const life = mountPreview({ id: '3' });
  const games = Array.from({ length: 7 }, (_, i) => ({ title: 'G' + i, duration: 2 }));
  await respond(life.requests[0], ok(info({ name: '生活不掉线', productType: 2, totalTime: 360, games,
    chapters: [{ name: '吃饭 EAT' }, { name: '衣物 WEAR' }, { name: '居住 HOME' }, { name: '出行 MOVE' }] })));
  assert.deepEqual(life.page.data.checks, ['吃饭 / 衣物 / 居住 / 出行', '7 个小玩法', '首局约 6 分钟']);
  assert.deepEqual(life.page.data.cards[0].steps.slice(-1), ['…'], '封面卡背最多列 5 个再省略');
  assert.deepEqual(life.page.data.cards[3].steps, ['答几道小题', '拿到备用答案']);
});

test('卡堆：左右滑切换，点最前面那张翻面并放大 1.25,两侧邻卡仍露出；点侧卡先转到中间', async () => {
  const { page, requests } = mountPreview();
  await respond(requests[0], ok(info()));
  page.onTouchStart({ touches: [{ clientX: 200 }] });
  page.onTouchEnd({ changedTouches: [{ clientX: 120 }] });
  assert.equal(page.data.sel, 1);
  page.tapCard({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(page.data.sel, 1, '滑动后紧跟的 tap 不算点击');
  page.tapCard({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(page.data.flip, true);
  assert.match(page.data.cards[1].style, /scale\(1\.25\)/);
  assert.match(page.data.cards[2].style, /translateX\(124px\)[^;]*rotate\(8deg\)/);
  assert.match(page.data.cards[0].style, /translateX\(-124px\)/);
  assert.match(page.data.cards[2].style, /opacity: 1;/, '翻面后邻卡仍露出，提示可以横滑');
  page.tapCard({ currentTarget: { dataset: { index: 3 } } });
  assert.equal(page.data.sel, 3);
  assert.equal(page.data.flip, false);
});

test('每次打开都从中间扇开(减少动态效果时不播)', async () => {
  const { page, requests } = mountPreview();
  await respond(requests[0], ok(info()));
  assert.equal(page.data.entered, false);
  assert.match(read('pages/templatedetail/topic-preview/index.wxss'), /\.stk--pre \.kc \{[^}]*scale\(\.8\)[^}]*opacity: 0/);
  const quiet = mountPreview({ id: '7' }, null, { getStorageSync: (k) => k === 'play_reduced_motion' });
  await respond(quiet.requests[0], ok(info()));
  assert.equal(quiet.page.data.entered, true);
});

const mountDetail = (query, appExtra, wxExtra) => mountPreview(query, appExtra, wxExtra, DETAIL_JS);

test('卡堆预览「查看详情」带作用域进主题模板详情(V-24)', () => {
  const m = mountPreview({ id: '7', scope: 'MERCHANT', readonly: '1' });
  m.page.goDetail();
  assert.deepEqual(m.navigations, ['/pages/templatedetail/topic-detail/index?id=7&scope=MERCHANT&readonly=1']);
});

test('用这个模板：先过写前登录闸，只读岗位拦下，在途不重复复制，带 MERCHANT 作用域进专业编辑器', async () => {
  // 2026-10-30 V-24:这条链路搬到主题模板详情的「应用」
  let gated = 0;
  const loggedOut = mountDetail({ id: '7' }, { ensureLoginFor: () => { gated += 1; return false; } });
  loggedOut.page.useTemplate();
  assert.equal(gated, 1);
  assert.equal(loggedOut.requests.length, 1, '没登录不发复制请求');

  const ro = mountDetail({ id: '7', scope: 'MERCHANT', readonly: '1' });
  ro.page.useTemplate();
  assert.equal(ro.requests.length, 1);
  assert.deepEqual(ro.toasts, ['当前岗位仅可浏览模板']);

  const m = mountDetail({ id: '7', scope: 'MERCHANT' });
  m.page.useTemplate();
  m.page.useTemplate();
  const uses = m.requests.filter((r) => r.url === '/api/template/topic-template/use');
  assert.equal(uses.length, 1, '在途时连点不重复复制');
  assert.deepEqual(uses[0].data, { id: '7', scope: 'MERCHANT' });
  uses[0].success(ok({ topicId: 901 }));
  assert.deepEqual(m.navigations, ['/pages/publish/fabu/index?id=901&scope=MERCHANT']);

  const failed = mountDetail({ id: '7' });
  failed.page.useTemplate();
  failed.requests.pop().fail();
  failed.page.useTemplate();
  assert.equal(failed.requests.filter((r) => r.url === '/api/template/topic-template/use').length, 1, '失败后放行下一次');
});

test('复制回包晚于离开预览页：不再把用户拉去编辑器', () => {
  const { page, requests, navigations } = mountDetail({ id: '7' });
  page.useTemplate();
  page.onUnload();
  requests.pop().success(ok({ topicId: 901 }));
  assert.deepEqual(navigations, []);
});

test('业主暂缓的主题(野生邻居观察局)接口回来也不上货架', async () => {
  const { vm, requests } = mountShelf();
  await respond(requests.find((r) => r.url === '/api/template/topic-template/list'), ok([topic(1, '野生邻居观察局'), topic(2, '风味巡游')]));
  assert.deepEqual(vm._topics.map((t) => t.name), ['风味巡游']);
  const tagged = art.TOPIC_TAGS.some((t) => t.test({ key: '野生邻居观察局', seconds: 0 }) && t.key !== 'solo');
  assert.equal(tagged, false);
});

test('标签按多个维度分组(没有「场景」维度);横滑行只放常用几个，其余进「全部分类」', async () => {
  assert.deepEqual(art.TOPIC_TAG_GROUPS.map((g) => g.name), ['类型', '风格', '适合谁', '时长']);
  assert.deepEqual(art.GAME_TAG_GROUPS.map((g) => g.name), ['用途', '玩法', '题材', '适合的店', '人数', '时间', '分区']);
  assert.ok(!art.TOPIC_TAGS.some((t) => /雨天|滨江|公园|美食探店|拍照出片/.test(t.name)), '业主去掉了场景维度');
  assert.ok(!art.TOPIC_TAGS.some((t) => /黄浦|徐汇|浦东|虹口/.test(t.name)), '业主去掉了区域维度');
  const { vm, requests } = mountShelf();
  await respond(requests.find((r) => r.url === '/api/template/topic-template/list'), ok([
    topic(1, '风味巡游', { totalTime: 14400 }), topic(2, '高温末日逃生', { totalTime: 21600 }), topic(3, '异常事务局：不存在的坐标', { productType: 1 })
  ]));
  assert.ok(vm.data.topicTags.length <= art.TOPIC_ROW.length + 1);
  assert.ok(!vm.data.topicTagGroups.some((g) => g.tags.some((t) => t.key === 'versus')), '没有内容的「竞技对抗」不出现');
  vm.openAllTags();
  assert.equal(vm.data.allTagsShow, true);
  vm.pickTag({ currentTarget: { dataset: { key: 'halfday' } } });
  assert.equal(vm.data.allTagsShow, false, '在半屏里选完就收起');
  assert.equal(vm.data.topicTags[0].key, 'halfday', '选中的标签不在常用行里时临时放到最前');
  assert.deepEqual(vm.data.topicCat.items.map((t) => t.name).sort(), ['风味巡游', '高温末日逃生'].sort());
  const wxml = read('pages/templatedetail/shelf/index.wxml');
  assert.equal((wxml.match(/class="tg tg--more" bindtap="openAllTags"/g) || []).length, 2, '两个 tab 的标签行末尾都有「全部分类」');
  assert.match(wxml, /<cy-sheet show="\{\{ allTagsShow \}\}" title="全部分类"/);
});

test('玩法标签：营销引流 / 插件玩法 / 解谜 / 运动等按标题归类，人数与时长按字段现算', async () => {
  const { vm, requests } = mountShelf();
  const rows = [gameRow(38, '今晚的暗号', { players: '单人/双人/不限', duration: 5 }), gameRow(94, '低碳行动', { players: '单人/不限', duration: 30 }),
    gameRow(81, '一桌任务宾果', { players: '2-4人同桌', duration: 8 })];
  await respond(requests.find((r) => r.url === '/api/template/list'), ok({ rows, total: 3 }));
  vm.setData({ tab: 'game' });
  const pick = (key) => { vm.pickTag({ currentTarget: { dataset: { key } } }); return vm.data.gameCat ? vm.data.gameCat.items.map((g) => g.title) : []; };
  assert.deepEqual(pick('promo'), ['今晚的暗号']);
  assert.deepEqual(pick('riddle'), ['今晚的暗号']);
  assert.deepEqual(pick('sport'), ['低碳行动']);
  assert.deepEqual(pick('p3'), ['一桌任务宾果']);
  assert.deepEqual(pick('quick'), ['今晚的暗号']);
});

test('每个标签都有自己的 3D 图标:同一 tab 内不重复，也不借玩法卡的图标', () => {
  const gameSlugs = Object.keys(art.GAMES).map((title) => art.GAMES[title][0]);
  [art.TOPIC_TAG_GROUPS, art.GAME_TAG_GROUPS].forEach((groups) => {
    const tags = groups.reduce((all, g) => all.concat(g.tags), []);
    const icons = tags.map((t) => t.icon);
    assert.equal(new Set(icons).size, icons.length, '同一 tab 内两个标签用了同一个图标');
    tags.forEach((t) => {
      assert.ok(gameSlugs.indexOf(t.icon) < 0, t.name + ' 借用了玩法卡图标 ' + t.icon);
      assert.ok(fs.existsSync(path.join(ROOT, 'pages/templatedetail/art/props', t.icon + '.png')), t.name + ' 的图标文件不存在');
    });
  });
});

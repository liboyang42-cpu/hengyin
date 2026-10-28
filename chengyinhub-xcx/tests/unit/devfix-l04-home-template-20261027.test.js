// 2026-10-27 真机走查修复 L04(首页 + 模板 tab):docs/delivery/device-walkthrough-20261027/实施文档.md
// P-01 / P-02 / P-03 / P-04 / P-05 / P-06 / P-07 / P-08 / P-10 / P-11 / P-12 / M-02 的源码契约。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const strip = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '');
const rule = (css, selector) => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = strip(css).match(new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`));
  assert.ok(m, `缺少样式 ${selector}`);
  return m[1];
};

const homeWxml = () => read('pages/index/index.wxml');
const homeWxss = () => read('pages/index/index.wxss');

function assertGreetRow(wxml, wxss) {
  const start = wxml.indexOf('class="v3-hero-greet-row"');
  assert.ok(start > 0, '缺少招呼语行');
  const block = wxml.slice(start, wxml.indexOf('class="v3-hero-dots"', start));
  const hi = block.indexOf('class="v3-hero-hi"');
  const mem = block.indexOf('class="v3-hero-mem');
  assert.ok(hi > 0 && mem > hi, 'P-01:名字在上，「✓ 玩家 · 城市」在下');
  assert.match(block.slice(mem), /v3-hero-mem-txt">玩家<\/text>[\s\S]*?· \{\{city\}\}/);
  const row = rule(wxss, '.v3-hero-greet-row');
  assert.match(row, /flex-direction:\s*column/, 'P-01:名字必须单独占一行');
}

test('P-01:名字单独一行，下一行「✓ 玩家 · 城市」', () => {
  assertGreetRow(homeWxml(), homeWxss());
});

test('P-01 负控:招呼语行改回横排会判红', () => {
  const broken = homeWxss().replace(/(\.v3-hero-greet-row\s*\{[\s\S]*?)flex-direction:\s*column;/, '$1');
  assert.notEqual(broken, homeWxss());
  assert.throws(() => assertGreetRow(homeWxml(), broken), /单独占一行/);
});

test('P-02 / P-08:首页不再有「为什么看到」「继续上次漫游」「继续探索」', () => {
  const wxml = homeWxml();
  const json = read('pages/index/index.json');
  const js = read('pages/index/index.js');
  assert.doesNotMatch(wxml, /为什么看到|showRecommendationSource/);
  assert.doesNotMatch(wxml, /cy-roam-resume-bar|v3-continue|continueExplore/);
  assert.doesNotMatch(json, /scene-roam-resume-bar/);
  assert.doesNotMatch(js, /loadContinueExplore|goContinueExplore|continue-explore\.js|\/api\/play\/run-session\/list/);
  // P-08 只撤首页的挂载，组件本身归漫游线
  assert.ok(fs.existsSync(path.join(ROOT, 'subpackageRoam/components/scene-roam-resume-bar/index.js')));
});

test('P-03:「主题 / 活动」tab 与第一张卡之间留 24rpx', () => {
  assert.match(rule(homeWxss(), '.v3-feed .page-tab'), /margin-bottom:\s*var\(--cy-space-3\)/);
  assert.match(read('style/tokens.wxss'), /--cy-space-3:\s*24rpx/);
});

// 2026-10-28 p104/p70 业主:主题都必须有封面 ⇒ 推荐卡改成整张封面卡(高同 cy-feed-play-card),
// 取代 P-04 的「无封面插画占位 + 左文右宫格」。封面缺失只剩兜底图,卡下不再露白。
test('P-04:推荐卡整张铺封面，缺封面用兜底图，不再出宫格与占位插画', () => {
  const wxml = homeWxml();
  const covers = wxml.match(/<image class="v2-fcover" src="\{\{item\.cover \|\| '\/images\/route_city_cover\.png'\}\}"/g) || [];
  assert.equal(covers.length, 2, '主题和活动两个分支都铺封面');
  assert.doesNotMatch(wxml, /v2-fgrid|v2-fcell/);
  assert.match(rule(homeWxss(), '.v2-fcover'), /width:\s*100%[\s\S]*height:\s*100%/);
});

test('P-05:主题没有固定时间显示「长期开放」', () => {
  const wxml = homeWxml();
  const topicFeed = wxml.slice(wxml.indexOf("activeTab === '0'"), wxml.indexOf("activeTab === '1'"));
  assert.match(topicFeed, /\{\{item\.formattedDateTime \|\| '长期开放'\}\}/);
});

test('P-06:首页原生背景(含底部安全区)统一深色', () => {
  const json = JSON.parse(read('pages/index/index.json'));
  assert.equal(json.backgroundColor, '#000000');
  assert.equal(json.backgroundColorBottom, '#000000');
  assert.match(rule(homeWxss(), '.index'), /background:\s*var\(--cy-color-bg-page\)/);
});

test('P-07:列表底部留出底栏真实高度，价格行不压底栏', () => {
  assert.match(rule(homeWxss(), '.v3-scroll-pad'), /height:\s*calc\(var\(--cy-tabbar-h\) \+ var\(--cy-space-6\)\)/);
});

test('P-10:模板空态用 #602 Figma 插画(kind="empty"),没有「刷新试试」按钮', () => {
  const wxml = read('pages/template/index.wxml');
  const empty = (wxml.match(/<cy-empty[^>]*title="\{\{ emptyTitle \}\}"[^>]*\/>/) || [])[0];
  assert.ok(empty, '缺少模板空态');
  assert.match(empty, /kind="empty"/);
  assert.doesNotMatch(empty, /\bcta=|icon=/);
  assert.doesNotMatch(wxml + read('pages/template/index.js'), /刷新试试/);
});

function loadTemplatePage() {
  let definition;
  const requests = [];
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44, menuButtonInfo: {} },
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(options) { requests.push(options); },
  };
  vm.runInNewContext(read('pages/template/index.js'), {
    module: { exports: {} },
    console,
    getApp: () => app,
    Page(value) { definition = value; },
    setTimeout: () => 1,
    clearTimeout() {},
    require(request) {
      if (request === '../../utils/motion-preference.js') return { readReducedMotion: () => false };
      if (request === '../../utils/modal.js') return { show() {} };
      if (request === '../../utils/page-motion.js') return { enter() {} };
      if (request === '../../utils/toast.js') return () => {};
      if (request === '../../utils/font-scale.js') return { readPageStyle: () => '' };
      if (request === '../../utils/identity/identity-policy.js') return { isMerchantView: () => false };
      if (request === '../../utils/merchant-theme.js') return { merchantPageRestore() {}, merchantPageShow() {} };
      if (request === '../../utils/merchant-access-policy.js') return {};
      if (request === '../../utils/template-display.js') return { formatDurationMinutes: () => '', gameTemplateCover: (item) => item.imgUrl || '' };
      if (request === '../../utils/response-shape.js') return { isRecord: (v) => !!v && typeof v === 'object', isRecordList: Array.isArray };
      if (request === '../../utils/analytics.js') return { track() {} };
      throw new Error(`unexpected require: ${request}`);
    },
    wx: {},
  }, { filename: 'pages/template/index.js' });
  const page = Object.assign({}, definition);
  page.data = Object.assign(JSON.parse(JSON.stringify(definition.data)), { tab: 'game' });
  page.setData = function (patch) { Object.assign(this.data, patch); };
  return { page, requests };
}

test('P-11:游戏 tab 只列单节点地点玩法，主题自带的分段游戏与剧情包 / IP 包不进来', () => {
  const { page, requests } = loadTemplatePage();
  page.getGameRows();
  const req = requests.find((r) => r.url === '/api/template/list');
  assert.ok(req, '游戏 tab 要请求 /api/template/list');
  assert.equal(req.data.pack_type, 0, '服务端先按 pack_type=0 过滤');
  req.success({
    code: '200',
    data: {
      rows: [
        { id: 1, title: '今晚的暗号', packType: 0 },
        { id: 2, title: '城市接力棒' },
        { id: 3, title: 'G0｜30秒生活掉线测试', packType: 0 },
        { id: 4, title: 'G7｜60秒共同落款' },
        { id: 5, title: '整套剧情包', packType: 1 },
        { id: 6, title: '店铺 IP 包', packType: '2' },
      ],
    },
  });
  const shown = [page.data.banner].concat(page.data.topList, page.data.tailList)
    .filter((item) => item && item.id).map((item) => item.id);
  assert.deepEqual(shown.sort(), [1, 2]);
});

test('P-11:主题 tab 的「配置」直进专业编辑器 publish/fabu', () => {
  const js = read('pages/template/index.js');
  assert.match(js, /if \(item\._kind === 'topic'\) \{ this\.goTopicConfig\(e\); return; \}/);
  assert.match(js, /wx\.navigateTo\(\{ url: '\/pages\/publish\/fabu\/index\?' \+ qs\.join\('&'\) \}\)/);
});

function assertBackdrop(wxml, wxss, js) {
  assert.doesNotMatch(wxml, /class="ps__mask"[^>]*style="[^"]*top:/, 'P-12:遮罩必须盖住状态栏与导航栏');
  assert.match(rule(wxss, '.ps__mask'), /inset:\s*0/);
  assert.match(rule(wxss, '.ps--quick .ps__mask'), /transition:\s*opacity var\(--cy-motion-fade-swap\)/, 'P-12:遮罩淡入淡出 200ms');
  assert.match(rule(wxss, '.ps--quick.ps--closing .ps__mask'), /opacity:\s*0/, 'P-12:退场要淡出');
  assert.doesNotMatch(js, /wx\.nextTick\(/, 'P-12:nextTick 会和挂载并进同一帧，淡入没有起点');
  assert.match(js, /setTimeout\(\(\) => \{[\s\S]*?setData\(\{ quickOpen: true \}\)/);
}

test('P-12:「+」菜单遮罩铺满整屏(含状态栏与导航栏),淡入淡出 200ms', () => {
  assert.match(read('style/tokens.wxss'), /--cy-motion-fade-swap:\s*200ms/);
  assertBackdrop(read('components/cy/publish-sheet/index.wxml'), read('components/cy/publish-sheet/index.wxss'), read('components/cy/publish-sheet/index.js'));
});

test('P-12 负控:遮罩过渡改回瞬时会判红', () => {
  const wxss = read('components/cy/publish-sheet/index.wxss');
  const broken = wxss.replace('transition: opacity var(--cy-motion-fade-swap) var(--cy-ease-out);', 'transition: none;');
  assert.notEqual(broken, wxss);
  assert.throws(() => assertBackdrop(read('components/cy/publish-sheet/index.wxml'), broken, read('components/cy/publish-sheet/index.js')), /200ms/);
});

test('M-02:搜索框与「主题 / 游戏」tab 同盒底对齐，不再被底部外边距抬高', () => {
  const r = rule(read('pages/template/index.wxss'), '.tpl-search');
  assert.match(r, /align-self:\s*flex-end/);
  assert.doesNotMatch(r, /margin-bottom/);
});

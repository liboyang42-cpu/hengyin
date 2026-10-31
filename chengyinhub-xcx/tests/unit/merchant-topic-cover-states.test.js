// 商家招募主题首屏封面四态契约：成功、慢加载、加载失败、空 URL。
// 每态都必须留下主题名、类型/来源、价值说明和明确下一步；封面只提供氛围。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js'); // node:vm + 沙箱里装真 utils/toast.js·loading.js
const { loadMediaPreview } = require('../helpers/media-preview-sandbox.js');

const ROOT = path.resolve(__dirname, '../..');
const WXML = fs.readFileSync(path.join(ROOT, 'pages/topic/merchantinfo/merchantinfo.wxml'), 'utf8');
const WXSS = fs.readFileSync(path.join(ROOT, 'pages/topic/merchantinfo/merchantinfo.wxss'), 'utf8');
const COOP_CENTER_WXML = fs.readFileSync(path.join(ROOT, 'pages/merchant/coop-center/index.wxml'), 'utf8');
const COOP_CENTER_JS = fs.readFileSync(path.join(ROOT, 'pages/merchant/coop-center/index.js'), 'utf8');
let coopCenterPageConfig = null;
global.getApp = () => ({ globalData: {}, getRequestErrorMessage: () => '' });
global.wx = {};
global.Page = (config) => { coopCenterPageConfig = config; };
require('../../pages/merchant/coop-center/index.js');

function merchantInfoPageFrom(source) {
  let config = null;
  const sandbox = {
    getApp: () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 } }),
    wx: {},
    Page: (page) => { config = page; },
    require: (request) => {
      if (request === '../../../utils/merchant-theme.js') {
        return { merchantPageShow() {}, merchantPageRestore() {} };
      }
      if (request === '../../../utils/datetime') {
        return require('../../utils/datetime');
      }
      if (request === '../../../utils/session/login-reload.js') return require('../../utils/session/login-reload.js');
      if (request === '../../../utils/response-shape.js') {
        return require('../../utils/response-shape.js');
      }
      if (request === '../../../utils/motion-preference.js') {
        return require('../../utils/motion-preference.js');
      }
      if (request === '../../../utils/validation-method-labels.js') {
        return require('../../utils/validation-method-labels.js');
      }
      if (request === '../utils/topic-detail-facts.js') {
        return require('../../pages/topic/utils/topic-detail-facts.js');
      }
      if (request === '../../../utils/nav-safe-area.js') {
        return require('../../utils/nav-safe-area.js');
      }
      if (request === '../../../utils/media-preview.js') return loadMediaPreview({ previewImage() {} });
      if (request === '../utils/code-text.js') return require('../../pages/topic/utils/code-text.js');
      // 2026-10-30 走查 v2 R09:分润明细口径
      if (request === '../../../utils/profit-split.js') return require('../../utils/profit-split.js');
      throw new Error('unexpected require: ' + request);
    },
  };
  vm.runInNewContext(source, sandbox, { filename: 'merchantinfo.js' });
  return config;
}

function assertCoverErrorSetsFallback(page) {
  assert.equal(typeof page.onCoverError, 'function', 'binderror 必须指向可调用 handler');
  page.data = Object.assign({}, page.data, { coverFailed: false });
  page.setDataCalls = [];
  page.setData = function (patch) {
    this.setDataCalls.push(patch);
    Object.assign(this.data, patch);
  };
  page.onCoverError({ type: 'error' });
  assert.equal(page.setDataCalls.length, 1, 'binderror 必须真的写入 coverFailed');
  assert.equal(page.setDataCalls[0].coverFailed, true, 'setData patch 必须把 coverFailed 写为 true');
  assert.equal(page.data.coverFailed, true, '失败后 WXML 才会切到 wx:else 保底');
}

/* 2026-09-08 稿 181:576 改了这一屏的口径:封面正常出图时上面不压任何字,
   信息锚点只在**封面没出来**的两态(空 URL / binderror)渲染。
   所以这条测试从「四态都要有锚点」收窄成「封面没出来的两态必须有锚点」——
   出图的那两态屏幕上有图,不存在「一个字都没有」的问题,那才是当初 P0-UI-1 要防的。 */
test('招募主题封面缺失时保留信息锚点', () => {
  const hero = /<view class="slide1[^"\n]*"[^>]*>([\s\S]*?)<view class="slide2"[^>]*>/.exec(WXML);
  assert.ok(hero, '找不到招募主题首屏');
  const text = hero[1];

  const brief = /<view class="cover-brief">/.exec(text);
  assert.ok(brief, '保底态的信息锚点缺失');
  assert.match(text, /info\.name \|\| '主题详情'/, '必须保留主题名');
  assert.match(text, /info\.productType/, '必须保留类型或来源');
  assert.match(text, /info\.subtitle \|\| info\.description/, '必须保留价值一句话');
  assert.match(text, /class="slide-txt slide-txt--cover-fallback" wx:if="\{\{ !info\.imgUrl \|\| coverFailed \}\}"/,
    '锚点必须挂在「封面没出来」这个条件上，不能又变回无条件渲染压在图上');

  assert.match(text, /wx:if="\{\{ info\.imgUrl && !coverFailed \}\}"/, '有 URL 且未失败时才渲染封面');
  assert.match(text, /binderror="onCoverError"/, '404 必须进入保底');
  assert.match(text, /<view class="slide-cover-fallback" wx:else>/, '空 URL 与加载失败必须有保底');
  assert.match(WXSS, /\.topic \.slide1 \.slide-cover-fallback\s*\{[^}]*background: var\(--cy-bg-card-2\)/,
    '保底不能是全白/全黑空腔');
});

function assertFallbackContrast(wxml, wxss) {
  const hero = /<view class="slide1[^"\n]*"[^>]*>([\s\S]*?)<view class="slide2"[^>]*>/.exec(wxml);
  assert.ok(hero, '找不到招募主题首屏');
  const text = hero[1];

  assert.match(text, /class="slide-scrim \{\{ !info\.imgUrl \|\| coverFailed \? 'slide-scrim--cover-fallback' : '' \}\}"/,
    '浅色保底必须同步移除图片态的压暗遮罩');
  /* 2026-09-08 稿 181:576:封面正常渲染时上面不压任何字(整页是一条长滚动),
     所以信息锚点整块改成「只有封面没出来才渲染」,类名不再是三元切换。
     契约要守的仍是同一件事 —— 兜底屏必须有那块深色信息锚点,不能整屏空白。 */
  assert.match(text, /class="slide-txt slide-txt--cover-fallback" wx:if="\{\{ !info\.imgUrl \|\| coverFailed \}\}"/,
    '封面缺失时必须渲染浅底信息锚点，且用 fallback 深色样式');
  assert.doesNotMatch(text, /class="slide-hint"/,
    '稿上封面不压提示语,「下滑查看…」不能回来');
  assert.match(wxss, /\.topic \.slide1 \.slide-txt--cover-fallback\s*\{[^}]*color: var\(--cy-text-title\)/,
    '浅色保底的正文不能继续使用 inverse/白字');
  assert.match(wxss, /\.topic \.slide1 \.slide-txt--cover-fallback \.cover-brief-title\s*\{[^}]*color: var\(--cy-text-title\)/,
    '主题名在浅色保底上必须使用深色标题 token');
  assert.match(wxss, /\.topic \.slide1 \.slide-txt--cover-fallback \.cover-brief-value\s*\{[^}]*color: var\(--cy-text-body\)/,
    '价值说明在浅色保底上必须使用深色正文 token');
  assert.match(wxss, /\.topic \.slide1 \.slide-txt--cover-fallback \.jt\s*\{[^}]*background: var\(--cy-color-overlay\)/,
    '浅色保底的白色下滑箭头必须有深色 token 承载底');
}

test('封面失败或空 URL 时，浅色兜底必须切换为深色信息锚点', () => {
  assertFallbackContrast(WXML, WXSS);
});

test('负控：浅色兜底重新使用 inverse/白字会判红', () => {
  const broken = WXSS.replace(
    '.topic .slide1 .slide-txt--cover-fallback {\n  color: var(--cy-text-title);\n}',
    '.topic .slide1 .slide-txt--cover-fallback {\n  color: var(--cy-text-inverse);\n}'
  );
  assert.notEqual(broken, WXSS, '变异夹具必须真的把保底正文改回 inverse/白字');
  assert.throws(() => assertFallbackContrast(WXML, broken), /正文不能继续使用 inverse/);
});

test('主题详情 binderror 触发后真的切换 coverFailed 兜底', () => {
  const source = fs.readFileSync(path.join(ROOT, 'pages/topic/merchantinfo/merchantinfo.js'), 'utf8');
  assertCoverErrorSetsFallback(merchantInfoPageFrom(source));
});

test('负控：删除或破坏 onCoverError 会被四态测试判红', () => {
  const source = fs.readFileSync(path.join(ROOT, 'pages/topic/merchantinfo/merchantinfo.js'), 'utf8');
  const broken = source.replace(
    /onCoverError\(\) \{\s*this\.setData\(\{ coverFailed: true \}\);\s*\},/,
    'onCoverError() {},'
  );
  assert.notEqual(broken, source, '变异夹具必须真的破坏 handler');
  assert.throws(() => assertCoverErrorSetsFallback(merchantInfoPageFrom(broken)), /coverFailed/);
});

// 2026-09-15 契约改写(总控裁决「给过稿的页面听稿」,稿 234:276):合作中心广场卡按稿重做,
//   封面兜底是纯色占位,主题名/模式/档期/看详情留在卡上;旧断言盯的 cc-cover-fallback、
//   prepareApplication 与 typeText 是稿上已删的旧结构,改写为对稿后四态「不丢可理解内容」的断言。
test('合作中心路线卡片的封面四态不丢失可理解内容', () => {
  assert.match(COOP_CENTER_WXML, /wx:if="\{\{item\.cover && !item\._coverFailed\}\}"/, '只在 URL 可用时渲染封面');
  assert.match(COOP_CENTER_WXML, /binderror="onRouteCoverError"/, '图片失败必须切换保底');
  // 2026-09-15 集成总线:占位从 wx:else 空分支改为常驻纯色底、封面 image 叠在其内(U6 空白状态分支门禁),兜底语义不变。
  // 2026-10-22 owner 重做(参考图):整卡就是封面、文字全写在图上;兜底是按模式取色的深色渐变底常驻包住封面,
  //   整张卡点进详情,「看详情」黑胶囊是卡上的明确动作标识;截止信息压成「截止 M月D日」放在图上底行。
  const COVER_WITH_FALLBACK = /<view class="cc-route-cover[^"]*">\s*<image class="cc-route-cover__img" wx:if=/;
  assert.match(COOP_CENTER_WXML, COVER_WITH_FALLBACK, '空 URL/图片失败必须有兜底:纯色占位常驻包住封面');
  assert.match(COOP_CENTER_WXML, /class="cc-route-title">\{\{item\.title\}\}/, '必须保留主题名');
  assert.match(COOP_CENTER_WXML, /item\.modeText/, '必须保留模式标签(城市定向/自由探索)');
  assert.match(COOP_CENTER_WXML, /item\.deadlineShort/, '必须保留截止信息');
  assert.match(COOP_CENTER_WXML, /class="cc-route[^"]*"[^>]*bindtap="openTopic"/, '整张卡必须能点进详情');
  assert.match(COOP_CENTER_WXML, /class="cc-detail"[^>]*>看详情</, '必须保留明确的看详情标识');
  assert.match(COOP_CENTER_JS, /onRouteCoverError\(e\)/, '必须处理封面加载失败');
  // 负控:封面不再有兜底分支时必须判红
  const broken = COOP_CENTER_WXML.replace(/<view class="cc-route-cover[^"]*">(\s*)<image/, '<view>$1<image');
  assert.notEqual(broken, COOP_CENTER_WXML, '变异夹具必须真的删掉兜底');
  assert.throws(() => assert.match(broken, COVER_WITH_FALLBACK), assert.AssertionError);
});

test('合作中心封面失败只标记同一张卡，迟到事件不误伤刷新后的新卡', () => {
  const page = Object.assign({}, coopCenterPageConfig, {
    data: { routes: [{ id: 7 }, { id: 8 }] },
    setData(patch) { this.patch = patch; },
  });
  page.onRouteCoverError({ currentTarget: { dataset: { index: 1, id: 8 } } });
  assert.deepEqual(page.patch, { 'routes[1]._coverFailed': true });

  page.patch = null;
  page.onRouteCoverError({ currentTarget: { dataset: { index: 1, id: 99 } } });
  assert.equal(page.patch, null, '列表已换时不能把失败写到同下标的新卡');

  page.data.routes = [{ id: '' }];
  page.onRouteCoverError({ currentTarget: { dataset: { index: 0, id: '' } } });
  assert.equal(page.patch, null, '空身份不能被误当作同一张卡');
});

// 商家承接页已收口为任务向导，节点按真实作者内容顺序展示；玩家侧仍保留接待商家。
test('任务向导展示真实节点名称，不恢复承接阵容或待定占位；玩家接待信息仍在', () => {
  const block = WXML.slice(WXML.indexOf('class="task-guide"'), WXML.indexOf('暂无任务向导'));
  assert.match(block, /part.type === 'node'/);
  assert.match(block, /part.title/);
  assert.match(block, /part.description/);
  assert.doesNotMatch(block, /registrationMerchantList|待定|未定/);
  const page = merchantInfoPageFrom(fs.readFileSync(path.join(ROOT, 'pages/topic/merchantinfo/merchantinfo.js'), 'utf8'));
  const parts = page.buildGuideBlocks({ id: 1, nodes: [{ id: 8, name: '自写地点', description: '到店任务', registrationMerchantList: [] }], blocks: [{type:'node', nodeId:999}, {type:'node', nodeId:8}] });
  assert.equal(parts.length, 1);
  assert.equal(parts[0].title, '自写地点');
  assert.equal(parts[0].description, '到店任务');
  // 玩家详情仍显示真实商家名；无商家时显示作者地点名，保留候选已有分支。
  const player = fs.readFileSync(path.join(ROOT, 'pages/topic/index/index.wxml'), 'utf8');
  const pblock = player.slice(player.indexOf('class="node-card-foot"'), player.indexOf('class="node-card-foot"') + 900);
  assert.ok(player.indexOf('class="node-card-foot"') > 0, '玩家侧的站点卡缺商家行');
  assert.match(pblock, /wx:if="\{\{node\.registrationMerchantList\[0\]\.mmsMerchant\.name\}\}"/);
  assert.match(pblock, /wx:elif="\{\{node\.name\}\}"/);
  assert.doesNotMatch(pblock, /待定|未定|暂无/, '公共站可显示真实名称，不能编造商家占位文案');
  assert.match(pblock, /node\.registrationMerchantList\[0\]\.mmsMerchant\.name/);
  assert.doesNotMatch(pblock, /待定|未定|暂无/);
})

'use strict';
// qx-u2(2026-11-10):ref 1 首屏样式只落在 P1 落地 / P4 模式卡 / P7 战报卡 / W2-16 首次介绍;只用页面已有事实。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const v = require('../../subpackageJourney/utils/journey-view.js');

function loadPage(rel, extraWx) {
  let def;
  global.getApp = () => ({ globalData: {}, getUserID: () => '9009', requireLogin: () => Promise.resolve(false) });
  global.Page = (d) => { def = d; };
  global.wx = Object.assign({ getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {}, getSystemInfoSync: () => ({}) }, extraWx || {});
  const file = require.resolve(path.join(root, rel));
  delete require.cache[file];
  require(file);
  return Object.assign({ data: JSON.parse(JSON.stringify(def.data)), setData(p) { Object.assign(this.data, p); } }, def);
}

test('弱化行只拼服务端事实,没有值的事实不出;没有事实就没有这一行', () => {
  assert.equal(v.factLine([{ k: '邀请', v: '2026.12.10 09:37 前有效' }, { k: '报名', v: '已关闭' }]), '邀请 2026.12.10 09:37 前有效 · 报名 已关闭');
  assert.equal(v.factLine([{ k: '领取', v: '' }]), '');
  assert.equal(v.factLine(null), '');
});

test('P1 落地:叠卡 hero + 共享页标题 + 白药丸主按钮 + 文字次要动作;没有封面字段就不画封面', () => {
  const view = read('subpackageJourney/pages/landing/index.wxml');
  // 2026-11-10 qx-s2 staging 合并:落地页正文与 r2-join b3-p1-5(后一轮业主点名「同行」结构 + 怎么玩模式卡)改的是同一块,
  // 取 r2-join 的 jl-duo / jl-tiles;u2 在本页保留的是共享页标题、白药丸主按钮(ob-footer)与「先逛逛」文字次要动作。
  assert.match(view, /class="jl-duo"/);
  assert.match(view, /class="jn-footer jn-footer--stack ob-footer"/);
  assert.doesNotMatch(view, /<image[^>]*cover/i, '落地读口没有封面字段,不拿别处的图冒充');
  assert.match(view, /<cy-page-title align="center" title="\{\{headline\}\}" \/>/, '标题合同:页标题走共享 cy-page-title,正文不再重复大标题');
  assert.doesNotMatch(view, /ob-title/);
  assert.match(view, /<cy-btn class="jn-grow" wx:if="\{\{primary\}\}" variant="primary"/);
  assert.match(view, /bindtap="onBrowse"[^>]*>先逛逛</);
  const css = read('subpackageJourney/style/journey.wxss');
  assert.match(css, /\.ob-footer \{ --cy-comp-btn-radius: var\(--cy-radius-pill\); \}/);
  assert.match(css, /prefers-reduced-motion: reduce\) \{ \.ob-card, \.ob-mode, \.ob-progress__seg \{ transition: none; \}/);
  assert.doesNotMatch(css.slice(css.indexOf('qx-u2')), /@keyframes/, '只用 transition');
});

test('P1 落地:领取后标题与事实行同步更新;次要动作只回首页,不领取不建单', () => {
  let tabbed = '';
  const page = loadPage('subpackageJourney/pages/landing/index.js', { switchTab: (o) => { tabbed = o.url; } });
  page.onBrowse();
  assert.equal(tabbed, '/pages/index/index');
  page._kind = 'seat';
  page._renderSeat(1);
  assert.equal(page.data.headline, '朋友邀请你同行');
  assert.deepEqual(page.data.facts, []);
  assert.equal(page.data.primary, '领取席位');
});

test('P4 模式:只读展示,切换不提交模式', () => {
  // 2026-11-10 qx-s2 staging 合并:u2 的横向分页 swiper 由 r2-join 的「怎么玩」模式卡(onMode)取代;保留「切换不发请求」这条业务断言。
  const view = read('subpackageJourney/pages/landing/index.wxml');
  assert.doesNotMatch(view, /<swiper/);
  assert.match(view, /class="jl-tiles"/);
  const js = read('subpackageJourney/pages/landing/index.js');
  const body = js.slice(js.indexOf('onMode(e)'), js.indexOf('onPrimary()'));
  assert.ok(body.length > 0);
  assert.doesNotMatch(body, /api\.|request/, '切换模式不发请求');
});

test('P7 战报:叠层卡;只有计分时出 NRC 粗斜体大数字,不计分/未知弱化,不画成 0', () => {
  const view = read('subpackageJourney/pages/report/index.wxml');
  // 2026-11-10 qx-s2 staging 合并:战报三格与 r2-finish b3-p7(后一轮业主点名「三格收进一张分组卡」)改的是同一块,取 r2-finish 的
  // jn-grid--card;u2 叠卡在本页退场。保留本条的业务断言:不计分 / 用时未知弱化显示,不画成 0;分享仍是 ob-footer 白色胶囊。
  assert.match(view, /<view class="jn-grid jn-grid--card">/);
  assert.match(view, /jn-stat__v \{\{report\.scored \? '' : 'is-muted'\}\}">\{\{report\.scoreText\}\}/);
  assert.match(view, /jn-stat__v \{\{report\.elapsedText === '未知' \? 'is-muted' : ''\}\}/);
  assert.match(view, /<view class="jn-footer ob-footer" wx:if="\{\{report\.finished\}\}">[\s\S]*open-type="share"/);
  const css = read('subpackageJourney/style/journey.wxss');
  assert.match(css, /\.rp-score__n \{[^}]*font-weight: 900; font-style: italic;/);
  const r = v.reportView({ scopeKind: 'RUN', runState: 'IN_PROGRESS', completionKind: 'IN_PROGRESS', completedNodes: 0, totalNodes: 2,
    elapsedSeconds: null, score: { state: 'NOT_SCORED', score: null } });
  assert.equal(r.scored, false);
  assert.equal(r.scoreText, '本局不计分');
});

test('W2-16 首次介绍:顶栏 返回/分段进度/跳过;左右滑翻页;跳过也记为看过', () => {
  const view = read('pages/merchant/decor/ai-npc/index.wxml');
  assert.match(view, /class="ni-bar"[\s\S]*bindtap="prevIntro"[\s\S]*class="ni-dots"[\s\S]*bindtap="dismissIntro"[^>]*>跳过</);
  assert.match(view, /bindtouchstart="onIntroTouchStart" bindtouchend="onIntroTouchEnd"/);
  let saved = null;
  const page = loadPage('pages/merchant/decor/ai-npc/index.js', { setStorageSync: (k, val) => { saved = [k, val]; } });
  const swipe = (dx, dy) => { page.onIntroTouchStart({ touches: [{ clientX: 200, clientY: 300 }] }); page.onIntroTouchEnd({ changedTouches: [{ clientX: 200 + dx, clientY: 300 + (dy || 0) }] }); };
  swipe(-120); assert.equal(page.data.introStep, 1, '左滑下一步');
  swipe(-30); assert.equal(page.data.introStep, 1, '位移不足不翻');
  swipe(-90, 120); assert.equal(page.data.introStep, 1, '偏竖直不翻');
  swipe(120); assert.equal(page.data.introStep, 0, '右滑上一步');
  swipe(120); assert.equal(page.data.introStep, 0, '第一步不再后退');
  for (let i = 0; i < 6; i++) swipe(-120);
  assert.equal(page.data.introStep, page.data.introSteps.length - 1, '最后一步左滑不关闭');
  page.prevIntro(); assert.equal(page.data.introStep, page.data.introSteps.length - 2);
  page.setData({ introVisible: true });
  page.dismissIntro();
  assert.equal(page.data.introVisible, false);
  assert.deepEqual(saved, ['cy_ai_npc_intro_seen', 1]);
});

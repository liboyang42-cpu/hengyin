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

test('hero 标题只换行不改字;未登记的标题原样一行;空标题不出行', () => {
  assert.deepEqual(v.heroLines('朋友邀请你一起玩'), ['朋友邀请你', '一起玩']);
  assert.deepEqual(v.heroLines('朋友送你一局'), ['朋友送你', '一局']);
  assert.deepEqual(v.heroLines('已领取同行席位'), ['已领取', '同行席位']);
  assert.deepEqual(v.heroLines('本局战报'), ['本局', '战报']);
  for (const t of ['朋友邀请你一起玩', '朋友送你一局', '朋友邀请你同行', '已领取同行席位', '礼物已领取', '本局战报', '主题记录']) {
    assert.equal(v.heroLines(t).join(''), t, t + ' 拼回原文');
  }
  assert.deepEqual(v.heroLines('服务端新文案'), ['服务端新文案']);
  assert.deepEqual(v.heroLines(''), []);
});

test('弱化行只拼服务端事实,没有值的事实不出;没有事实就没有这一行', () => {
  assert.equal(v.factLine([{ k: '邀请', v: '2026.12.10 09:37 前有效' }, { k: '报名', v: '已关闭' }]), '邀请 2026.12.10 09:37 前有效 · 报名 已关闭');
  assert.equal(v.factLine([{ k: '领取', v: '' }]), '');
  assert.equal(v.factLine(null), '');
});

test('P1 落地:叠卡 hero + 两行标题(品牌紫句点)+ 白药丸主按钮 + 文字次要动作;没有封面字段就不画封面', () => {
  const view = read('subpackageJourney/pages/landing/index.wxml');
  assert.match(view, /class="ob-hero"[\s\S]*ob-card--back2[\s\S]*ob-card--back1[\s\S]*ob-card--front/);
  assert.doesNotMatch(view, /<image[^>]*cover/i, '落地读口没有封面字段,不拿别处的图冒充');
  assert.match(view, /wx:for="\{\{headLines\}\}"[\s\S]*class="ob-dot">\.<\/text>/);
  assert.match(view, /<cy-btn class="jn-grow" wx:if="\{\{primary\}\}" variant="primary"/);
  assert.match(view, /bindtap="onBrowse"[^>]*>先逛逛</);
  const css = read('subpackageJourney/style/journey.wxss');
  assert.match(css, /\.ob-dot \{ color: var\(--cy-ref-violet-500\); \}/);
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
  assert.deepEqual(page.data.headLines, ['朋友邀请你', '同行']);
  assert.equal(page.data.factLine, '');
  assert.equal(page.data.primary, '领取席位');
});

test('P4 模式:两种以上才出横向分页与分段进度;只跟随滑动,不提交模式', () => {
  const view = read('subpackageJourney/pages/landing/index.wxml');
  assert.match(view, /<swiper wx:if="\{\{modes\.length > 1\}\}"[^>]*bindchange="onModeChange"/);
  assert.match(view, /class="ob-progress" wx:if="\{\{modes\.length > 1\}\}"/);
  assert.match(view, /wx:else class="ob-single"/);
  const page = loadPage('subpackageJourney/pages/landing/index.js');
  page.onModeChange({ detail: { current: 1 } });
  assert.equal(page.data.modeIndex, 1);
  page.onModeChange({ detail: { current: 'x' } });
  assert.equal(page.data.modeIndex, 1);
  const js = read('subpackageJourney/pages/landing/index.js');
  assert.doesNotMatch(js.slice(js.indexOf('onModeChange'), js.indexOf('onBrowse')), /api\.|request/, '翻页不发请求');
});

test('P7 战报:叠层卡;只有计分时出 NRC 粗斜体大数字,不计分/未知弱化,不画成 0', () => {
  const view = read('subpackageJourney/pages/report/index.wxml');
  assert.match(view, /<text class="rp-score__n" wx:if="\{\{report\.scored\}\}">\{\{report\.scoreText\}\}<\/text>/);
  assert.match(view, /<text class="rp-score__muted" wx:else>\{\{report\.scoreText\}\}<\/text>/);
  assert.match(view, /rp-stat__v \{\{report\.elapsedText === '未知' \? 'is-muted' : ''\}\}/);
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

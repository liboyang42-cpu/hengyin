'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const source = read('pages/play/index.js');

function routeCardHarness(snapshot) {
  let current = true, drawn = 0;
  const canvas = { getContext: () => ({ scale() {} }), createImage() {
    return { set src(value) { this.onload(); } };
  } };
  const wx = { createSelectorQuery: () => ({ select() { return this; }, fields() { return this; }, exec(done) { done([{ node: canvas }]); } }) };
  const map = { includePoints: r => r, styleFor: () => ({}), polyline: () => [], takeSnapshot: () => snapshot() };
  const card = { W: 600, H: 800, drawCard() { drawn++; } };
  const begin = source.indexOf('  _startRouteCard() {');
  const end = source.indexOf('  onRouteMapUpdated()', begin);
  const run = new Function('routeCardMap', 'routeCard', 'wx', 'setTimeout', 'return ({' + source.slice(begin, end) + '})._startRouteCard;')(
    map, card, wx, fn => { queueMicrotask(fn); return 1; });
  const page = { data: { finishSnap: 'line.png', finishSnapError: false },
    _captureFinishArtwork: () => ({}), _isFinishArtworkCurrent: () => current,
    _routeCardModel: () => ({ route: [{ lat: 31, lng: 121 }, { lat: 32, lng: 122 }] }),
    _routeCardExtras: () => Promise.resolve([null, '']), selectComponent: () => ({ getMapContext: () => ({}) }),
    setData(patch, next) {
      for (const [key, value] of Object.entries(patch)) {
        if (key.startsWith('routeCard.')) this.data.routeCard[key.slice(10)] = value;
        else this.data[key] = value;
      }
      if (next) next();
    } };
  return { page, run: () => run.call(page), stale: () => { current = false; }, drawn: () => drawn };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

for (const [name, snapshot] of [['reject', () => Promise.reject(new Error('SDK unavailable'))], ['empty', () => Promise.resolve(null)]]) {
  test('地图快照失败保留可显示的线稿，不伪报主图就绪：' + name, async () => {
    const h = routeCardHarness(snapshot); h.run(); await flush();
    assert.deepEqual(h.page.data.routeCard, { on: true, ready: false, error: true });
    assert.equal(h.page.data.finishSnap, 'line.png'); assert.equal(h.page.data.finishSnapError, false);
    assert.equal(h.drawn(), 0);
  });
}

test('真实地图快照成功时继续绘制原路线卡', async () => {
  const h = routeCardHarness(() => Promise.resolve('map.png')); h.run(); await flush();
  assert.equal(h.drawn(), 1); assert.equal(h.page.data.routeCard.ready, true);
  assert.notEqual(h.page.data.routeCard.error, true);
});

test('旧结算页的迟到快照失败不能改写新页面', async () => {
  let reject;
  const h = routeCardHarness(() => new Promise((_, fail) => { reject = fail; }));
  h.run(); await flush(); h.stale(); reject(new Error('old failure')); await flush();
  assert.notEqual(h.page.data.routeCard.error, true);
});

test('结算使用可滚动内容区，保留真实成就和勋章，离屏导出画布不露出', () => {
  const wxml = read('pages/play/index.wxml');
  assert.match(wxml, /<scroll-view scroll-y enhanced[^>]*class="finsheet /);
  assert.match(wxml, /routeCard.error && finishSnap && !routeCard.ready/);
  assert.match(wxml, /wx:if="\{\{finishAchievements.length \|\| finishMedalList.length\}\}"/);
  assert.match(wxml, /cy-finish-medals items="\{\{finishMedalList\}\}"/);
  assert.match(wxml, /id="snapcv"[^>]*aria-hidden="true"[^>]*opacity:0/);
});

test('题卡直接按 imageUrl 渲染，图片后到也不依赖旧的 hasImage 缓存', () => {
  const wxml = read('pages/play/components/playkit-qa/index.wxml');
  assert.match(wxml, /qa__media" wx:if="\{\{imageUrl\}\}"/);
  assert.match(wxml, /class="qa__img"[^>]*mode="widthFix"/);
});

test('零共享免费次数不否认作者配置的免费提示，提示面板使用实底', () => {
  const help = require('../../pages/play/utils/play-help-panel.js');
  const result = help.project({ enabled: true, remainingFreeUses: 0, layers: [{ level: 1, type: 'TEXT', usable: true, costPoints: 0 }] });
  assert.equal(result.rows[0].action, '免费');
  assert.match(read('pages/play/play-help.wxml'), /helpPanel.freeLeft > 0/);
  assert.match(read('pages/play/play-help.wxss'), /\.phl-pop__card\{[^}]*background:var\(--cy-comp-sheet-player-bg\)/);
});

test('票种半屏和自玩结账共享两位小数金额格式', () => {
  const { buyOptions } = require('../../pages/topic/utils/topic-motion.js');
  const { checkoutView } = require('../../pages/topic/utils/checkout-view.js');
  for (const price of [0, 29, 29.9, 29.95]) {
    const ticket = buyOptions({ selfPlay: 1, selfPlayPrice: price })[0].priceText;
    const checkout = checkoutView({ info: { selfPlay: 1, selfPlayPrice: price }, quoteState: 'ready', quote: { payAmount: price } }).totalText;
    assert.equal(ticket, '¥' + price.toFixed(2)); assert.equal(ticket, checkout);
  }
});

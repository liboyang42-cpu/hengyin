'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const file = path.resolve(__dirname, '../../subpackageP3/pages/badge-3d/index.js');
const viewFile = file.replace(/index\.js$/, 'index.wxml');
const DEFAULT = '/subpackageP3/assets/badge-demo.png';
const actual = 'https://cdn.example.com/badge.png';
function mount(style, reduced = false, staticCapable = true) {
  let definition, preference = reduced, engineOptions, createFails = false;
  const timers = [], queries = [], calls = { start: 0, stop: 0, destroy: 0, detail: 0, static: 0, create: 0 };
  const engine = { setInsets() {}, setBadges() {}, start() { calls.start++; }, stop() { calls.stop++; },
    destroy() { calls.destroy++; }, openDetail() { calls.detail++; } };
  if (staticCapable) engine.renderStatic = () => { calls.static++; };
  const context = {
    Page: d => { definition = d; },
    require: name => name.includes('engine.js') ? { createEngine(opts) { engineOptions = opts; calls.create++; if (createFails) throw new Error('create'); return engine; } }
      : { readReducedMotion: () => preference },
    setTimeout(fn, ms) { const item = { fn, ms, cleared: false }; timers.push(item); return timers.length; },
    clearTimeout(id) { if (timers[id - 1]) timers[id - 1].cleared = true; },
    wx: { getMenuButtonBoundingClientRect: () => ({ top: 54 }), getWindowInfo: () => ({}),
      createSelectorQuery() { const q = { in: () => q, select: () => q, fields: () => q,
        exec(callback) { queries.push(callback); } }; return q; } },
  };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), context, { filename: file });
  let writes = 0;
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { writes++; Object.assign(this.data, patch); } };
  page.onLoad({ style, img: encodeURIComponent(actual), name: encodeURIComponent('城市徽章'), sub: encodeURIComponent('来自城市探索') });
  page.onShow();
  return { page, timers, queries, calls, preference(value) { preference = value; }, writes: () => writes,
    deadline: () => timers.find(t => t.ms === 3000),
    init() { const timer = timers.find(t => t.ms === 200 && !t.cleared); if (timer) { timer.fired = true; timer.fn(); } },
    resolve() { queries.at(-1)([{ node: {}, width: 300, height: 400 }]); },
    failCreate() { createFails = true; },
    fault(method) { engine[method] = () => { throw new Error(method); }; },
    error() { engineOptions.onError(new Error('renderer')); } };
}
for (const style of ['enamel', 'glow']) {
  test(style + ' 加载3秒超时降级；ready后的排队超时不得回退', () => {
    const h = mount(style); const timeout = h.deadline();
    assert.ok(timeout, '两种样式都需要3秒加载期限');
    timeout.fn(); assert.equal(h.page.data.renderState, 'fallback');
    const ready = mount(style); const late = ready.deadline();
    if (style === 'enamel') ready.page.onLoaded(); else { ready.init(); ready.resolve(); }
    assert.equal(ready.page.data.renderState, 'ready');
    late.fn(); assert.equal(ready.page.data.renderState, 'ready');
  });
  test(style + ' 超时后迟到loaded/画布不能复活渲染器', () => {
    const h = mount(style); if (style === 'glow') h.init();
    assert.ok(h.deadline()); h.deadline().fn();
    if (style === 'enamel') h.page.onLoaded(); else h.resolve();
    assert.equal(h.page.data.renderState, 'fallback');
    assert.equal(h.calls.create, 0);
  });
  for (const phase of ['onHide', 'onUnload']) test(style + ' ' + phase + ' 清理timer，迟到回调不得setData', () => {
    const h = mount(style); if (style === 'glow') h.init();
    h.page[phase](); const writes = h.writes();
    h.timers.slice().forEach(t => t.fn());
    if (style === 'glow') h.resolve();
    h.page.onLoaded(); h.page.onXrReady(); h.page.onFallbackError(); h.page.onUnsupported({ detail: { reason: 'asset' } });
    assert.equal(h.writes(), writes);
    assert.equal(h.calls.create, 0);
    assert.ok(h.timers.every(t => t.cleared || t.fired));
  });
}

test('glow已就绪后离页清engine和特写timer，旧onError和特写回调不再操作', () => {
  for (const phase of ['onHide', 'onUnload']) {
    const h = mount('glow'); h.init(); h.resolve();
    assert.equal(h.calls.start, 1); h.page[phase]();
    const writes = h.writes(), details = h.calls.detail;
    h.timers.slice().forEach(t => t.fn()); h.error();
    assert.equal(h.writes(), writes); assert.equal(h.calls.detail, details);
    assert.ok(h.calls.stop > 0); assert.equal(h.calls.destroy, 1);
  }
});

test('glow隐藏再显示可重新加载，前一代画布响应失效', () => {
  const h = mount('glow'); h.init(); const oldQuery = h.queries[0];
  h.page.onHide(); h.page.onShow(); oldQuery([{ node: {}, width: 300, height: 400 }]);
  assert.equal(h.calls.create, 0);
  const next = h.timers.filter(t => t.ms === 200 && !t.cleared).at(-1);
  assert.ok(next); next.fn(); h.resolve(); assert.equal(h.page.data.renderState, 'ready');
});

test('备用图片坏链只切换一次正式默认素材，默认素材再失败不循环', () => {
  const h = mount('enamel'); h.page.onUnsupported({ detail: { reason: 'renderer' } });
  const before = h.writes(); h.page.onFallbackError();
  assert.equal(h.page.data.fallbackSrc, DEFAULT); assert.equal(h.writes(), before + 1);
  h.page.onFallbackError(); assert.equal(h.writes(), before + 1);
  assert.equal(h.page.data.fallbackSrc, DEFAULT);
  const view = fs.readFileSync(viewFile, 'utf8');
  assert.match(view, /<image[^>]*binderror="onFallbackError"/);
  assert.match(view, /aria-label="[^"\n]*\{\{name\}\}[^"\n]*\{\{sub\}\}/);
});

test('减少动态只在实际稳定帧生效时提示，切回动态后撤下说明', () => {
  const h = mount('glow', true); h.init(); h.resolve();
  assert.equal(h.calls.start, 0); assert.equal(h.calls.static, 1); assert.equal(h.page.data.motionReduced, true);
  h.preference(false); h.page.onShow();
  assert.equal(h.page.data.motionReduced, false); assert.equal(h.calls.start, 1);
  h.preference(true); h.page.onShow();
  assert.equal(h.page.data.motionReduced, true); assert.equal(h.calls.static, 2);
  const unsupportedStatic = mount('glow', true, false); unsupportedStatic.init(); unsupportedStatic.resolve();
  assert.equal(unsupportedStatic.page.data.motionReduced, false);
  assert.equal(unsupportedStatic.calls.start, 0);
  assert.equal(unsupportedStatic.page.data.renderState, 'fallback');
  const enamel = mount('enamel', true); enamel.page.onLoaded(); assert.equal(enamel.page.data.motionReduced, true);
  enamel.page.onUnsupported({ detail: { reason: 'asset' } }); assert.equal(enamel.page.data.motionReduced, false);
  const view = fs.readFileSync(viewFile, 'utf8');
  assert.match(view, /wx:if="\{\{motionReduced && renderState === 'ready'\}\}"[^>]*>已减少动态效果/);
});


test('glow画布缺失、引擎创建异常和初始化方法抛错均退静态预览', () => {
  const empty = mount('glow'); empty.init(); empty.queries.at(-1)([]);
  assert.equal(empty.page.data.renderState, 'fallback');
  const create = mount('glow'); create.failCreate(); create.init(); create.resolve();
  assert.equal(create.page.data.renderState, 'fallback');
  for (const method of ['setBadges', 'start', 'renderStatic']) {
    const h = mount('glow', method === 'renderStatic'); h.fault(method); h.init();
    assert.doesNotThrow(() => h.resolve());
    assert.equal(h.page.data.renderState, 'fallback'); assert.equal(h.calls.destroy, 1);
  }
});

test('glow ready后真实engine报错可降级；加载中坏备用图不会被超时恢复为坏链接', () => {
  const h = mount('glow'); h.init(); h.resolve(); h.error();
  assert.equal(h.page.data.renderState, 'fallback'); assert.equal(h.calls.destroy, 1);
  const image = mount('glow'); image.page.onFallbackError(); image.deadline().fn();
  assert.equal(image.page.data.fallbackSrc, DEFAULT);
  const writes = image.writes(); image.page.onFallbackError(); assert.equal(image.writes(), writes);
});

test('enamel渲染器已ready但贴图未loaded时超时仍按asset降级', () => {
  const h = mount('enamel'); h.page.onXrReady(); h.deadline().fn();
  assert.equal(h.page.data.renderState, 'fallback'); assert.equal(h.page.data.fallbackSrc, DEFAULT);
  assert.ok(fs.existsSync(path.resolve(__dirname, '../..', DEFAULT.slice(1))));
});

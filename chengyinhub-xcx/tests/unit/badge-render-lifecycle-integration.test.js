'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const PAGE = path.join(ROOT, 'subpackageP3/pages/badge-3d/index.js');
const XR = path.join(ROOT, 'subpackageP3/components/xr-badge/index.js');
const ENGINE = path.join(ROOT, 'subpackageP3/pages/badge-wall/index/engine.js');
const ACTUAL = 'https://cdn.example.com/original-badge.png';

// Only platform drawing/IO are faked. The page, xr-badge._boot and wall engine below are real modules.
function platform() {
  const images = [], frames = [], counts = { draws: 0, uploads: 0, cancels: 0, schedules: 0, pixels: 0, cpuReads: 0, cpuDraws: 0 };
  const constants = { ACTIVE_UNIFORMS: 1, LINK_STATUS: 2 };
  const gl = new Proxy({}, { get(target, key) {
    if (key in target) return target[key];
    if (/^[A-Z_0-9]+$/.test(key)) return constants[key] || 100;
    if (key === 'getProgramParameter') return (_p, param) => param === constants.ACTIVE_UNIFORMS ? 0 : true;
    if (key === 'getShaderParameter') return () => true;
    if (key === 'getAttribLocation') return () => 0;
    if (key === 'getUniformLocation') return (_p, name) => name;
    if (key.startsWith('create')) return () => ({});
    if (key === 'drawArrays' || key === 'drawElements') return () => { counts.draws++; };
    if (key === 'texImage2D') return () => { counts.uploads++; };
    if (key === 'readPixels') return () => { counts.pixels++; };
    return () => {};
  } });
  function offscreen(w, h) {
    const ctx = new Proxy({}, { get(target, key) {
      if (key in target) return target[key];
      if (key === 'getImageData') return (_x, _y, width, height) => { counts.cpuReads++; return { data: new Uint8ClampedArray(width * height * 4) }; };
      if (key === 'drawImage') return () => { counts.cpuDraws++; };
      if (key === 'createLinearGradient' || key === 'createRadialGradient') return () => ({ addColorStop() {} });
      if (key === 'measureText') return () => ({ width: 1 });
      return () => {};
    } });
    return { width: w, height: h, getContext: () => ctx,
      createImage() { const image = { width: 32, height: 32 }; images.push(image); return image; } };
  }
  const canvas = { width: 300, height: 400, getContext: () => gl,
    requestAnimationFrame(fn) { frames.push(fn); counts.schedules++; return frames.length; },
    cancelAnimationFrame() { counts.cancels++; } };
  const wx = { getWindowInfo: () => ({ pixelRatio: 1 }), getMenuButtonBoundingClientRect: () => ({ top: 54 }),
    createOffscreenCanvas: options => offscreen(options.width, options.height) };
  return { images, frames, counts, canvas, wx, offscreen };
}
function realComponent(io, emit, delayed = false) {
  let definition; const queries = [];
  vm.runInNewContext(fs.readFileSync(XR, 'utf8'), { Component: d => { definition = d; }, wx: io.wx });
  const instance = { ...definition.methods, data: { src: ACTUAL, reduced: false },
    triggerEvent: emit,
    createSelectorQuery() { const q = { select: () => q, fields: () => q, exec(fn) {
      queries.push(fn); if (!delayed) fn([{ node: io.canvas, width: 300, height: 400 }]);
    } }; return q; } };
  return { instance, definition, queries,
    attach() { definition.lifetimes.attached.call(instance); },
    detach() { definition.lifetimes.detached.call(instance); } };
}
function linkedPage(coalesced = false) {
  const io = platform(), timers = [], children = [];
  let definition, child;
  vm.runInNewContext(fs.readFileSync(PAGE, 'utf8'), {
    Page: d => { definition = d; }, wx: io.wx,
    require: name => name.includes('engine.js') ? require(ENGINE) : { readReducedMotion: () => false },
    setTimeout(fn, ms) { timers.push({ fn, ms }); return timers.length; }, clearTimeout() {},
  });
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    selectComponent: () => child && child.instance,
    setData(patch, done) {
      Object.assign(this.data, patch);
      const mounted = this.data.style === 'enamel' && this.data.rendererMounted !== false && !this.data.unsupported;
      if (!mounted && child && !coalesced) { child.detach(); child = null; }
      if (mounted && !child) {
        child = realComponent(io, (name, detail) => {
          const handler = { loaded: 'onLoaded', xready: 'onXrReady', unsupported: 'onUnsupported' }[name];
          page[handler]({ detail });
        });
        children.push(child); child.attach();
      }
      if (done) done();
    } };
  page.onLoad({ style: 'enamel', img: encodeURIComponent(ACTUAL) }); page.onShow();
  return { io, page, timers, children, badgeImages: () => io.images.filter(image => image.src === ACTUAL) };
}

test('真实enamel hide→图片loaded→show重建：保留正常原图并恢复ready，旧回包不复活', () => {
  const h = linkedPage(); const oldImage = h.badgeImages()[0], oldFrame = h.io.frames[0];
  h.page.onHide(); assert.ok(h.io.counts.cancels > 0, 'hide必须真实取消子组件RAF');
  oldImage.onload(); h.page.onShow();
  assert.equal(h.children.length, 2, 'show必须重新挂载真实xr-badge');
  assert.equal(h.page.data.fallbackSrc, ACTUAL);
  const newImage = h.badgeImages()[1]; newImage.onload();
  assert.equal(h.page.data.renderState, 'ready');
  h.io.frames.at(-1)(16); assert.ok(h.io.counts.draws > 0, '重建后的真实组件必须仍能绘制');
  const before = { ...h.io.counts };
  oldImage.onload(); oldImage.onerror(); oldFrame(16);
  assert.deepEqual(h.io.counts, before, '旧贴图与排队RAF不再上传/绘制/调度');
  h.timers.filter(t => t.ms === 3000).forEach(t => t.fn());
  assert.equal(h.page.data.renderState, 'ready'); assert.equal(h.page.data.fallbackSrc, ACTUAL);
});

test('真实enamel已ready后hide/show仍重建，旧错误不污染新图；unload终止真实RAF', () => {
  const h = linkedPage(); const old = h.badgeImages()[0]; old.onload();
  assert.equal(h.page.data.renderState, 'ready'); h.page.onHide(); h.page.onShow();
  assert.equal(h.page.data.renderState, 'loading'); old.onerror();
  assert.equal(h.page.data.unsupported, false);
  h.badgeImages()[1].onload(); assert.equal(h.page.data.renderState, 'ready');
  h.page.onUnload();
  const counts = { ...h.io.counts };
  h.badgeImages()[1].onload(); h.io.frames.forEach(fn => fn(32));
  assert.deepEqual(h.io.counts, counts);
});

test('真实xr-badge已detached时迟到selector不能_boot；重新attached后旧代次也失效', () => {
  const io = platform(), events = [];
  const c = realComponent(io, name => events.push(name), true);
  c.attach(); c.detach(); const counts = { ...io.counts };
  c.queries[0]([{ node: io.canvas, width: 300, height: 400 }]);
  assert.deepEqual(io.counts, counts); assert.equal(events.length, 0);
  c.attach(); const before = io.images.length;
  c.queries[0]([{ node: io.canvas, width: 300, height: 400 }]); assert.equal(io.images.length, before);
  c.queries[1]([{ node: io.canvas, width: 300, height: 400 }]); assert.ok(io.images.length > before);
});

function engineFixture() {
  const io = platform();
  const engine = require(ENGINE).createEngine({ canvas: io.canvas, width: 300, height: 400, pixelRatio: 1,
    createOffscreen: io.offscreen });
  engine.setBadges([{ name: '原徽章', rarity: 0, iconUrl: ACTUAL }]);
  return { io, engine, image: io.images.find(image => image.src === ACTUAL) };
}

test('真实glow静态帧destroy后迟到img.onload不再CPU上传、drawArrays或调度RAF', () => {
  const h = engineFixture(); h.engine.renderStatic();
  assert.ok(h.io.counts.draws >= 2, '正控必须实际绘制过背景和徽章');
  h.engine.stop(); h.engine.destroy(); const before = { ...h.io.counts }, imageCount = h.io.images.length;
  h.image.onload(); h.image.onerror();
  h.engine.renderStatic(); h.engine.start(); h.engine.setBadges([{ name: '新', iconUrl: ACTUAL }]);
  assert.equal(h.engine.captureFrame(), null);
  assert.equal(h.io.images.length, imageCount, 'destroy后不能重启新贴图请求');
  assert.deepEqual(h.io.counts, before);
});

test('真实glow正常晚到图片会更新稳定帧；仅stop期间不重绘，resume可正常继续', () => {
  const h = engineFixture(); h.engine.renderStatic(); const before = h.io.counts.draws;
  h.image.onload(); assert.ok(h.io.counts.draws > before, '未销毁时真实晚到图片必须可显示');
  h.engine.stop(); const paused = h.io.counts.draws; h.image.onload(); assert.equal(h.io.counts.draws, paused);
  h.engine.start(); assert.ok(h.io.counts.schedules > 0); h.engine.destroy();
  const final = { ...h.io.counts }; h.io.frames.forEach(fn => fn(48)); assert.deepEqual(h.io.counts, final);
});


test('真实enamel视图hide/show被合批时，同一已停实例按新代次_boot而非误降demo', () => {
  const h = linkedPage(true); const oldImage = h.badgeImages()[0], oldFrame = h.io.frames[0];
  h.page.onHide(); assert.ok(h.io.counts.cancels > 0);
  h.page.onShow(); assert.equal(h.children.length, 1, '模拟视图合批保留原实例');
  assert.equal(h.badgeImages().length, 2, '必须真实重新执行_boot获取新贴图');
  h.badgeImages()[1].onload(); assert.equal(h.page.data.renderState, 'ready');
  const counts = { ...h.io.counts }; oldImage.onload(); oldImage.onerror(); oldFrame(64);
  assert.deepEqual(h.io.counts, counts);
  h.timers.filter(t => t.ms === 3000).forEach(t => t.fn());
  assert.equal(h.page.data.renderState, 'ready'); assert.equal(h.page.data.fallbackSrc, ACTUAL);
});

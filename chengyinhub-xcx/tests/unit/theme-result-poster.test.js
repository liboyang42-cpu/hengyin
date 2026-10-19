'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const poster = require('../../pages/play/utils/theme-result-poster.js');
const base = extra => Object.assign({ success: false, endedAtText: '2026-10-09 02:30', title: '公开结果', scene: '第一幕', summary: '结束说明' }, extra);
function native(options = {}) {
  const calls = [], removed = [], drawn = [], images = [], canvases = [];
  const context = { measureText: text => ({ width: Array.from(text).length * 22 }), scale() {}, fillRect() {},
    fillText: text => drawn.push(text), drawImage: (...args) => images.push(args) };
  const api = {
    createOffscreenCanvas(args) {
      calls.push({ kind: 'canvas', args });
      const canvas = { width: args.width, height: args.height, getContext: () => context,
        createImage() { return { set src(path) { if (options.imageFail) this.onerror({ errMsg: 'decode failed' }); else this.onload(); } }; } };
      if (options.enforcePhysicalBounds) {
        let width = args.width, height = args.height;
        const check = (w, h) => { assert.ok(w <= 2400 && h <= 2400, 'physical canvas side limit'); assert.ok(w * h <= 1800000, 'physical canvas area limit'); };
        Object.defineProperties(canvas, {
          width: { get: () => width, set(value) { check(value, height); width = value; } },
          height: { get: () => height, set(value) { check(width, value); height = value; } },
        });
      }
      canvases.push(canvas); return canvas;
    },
    canvasToTempFilePath(args) { calls.push({ kind: 'export', args }); if (options.export) options.export(args); else args.success({ tempFilePath: 'wxfile://tmp_poster.png' }); },
    getImageInfo(args) { calls.push({ kind: 'cover', args }); if (options.cover) options.cover(args); else args.success({ type: 'png', width: 640, height: 360, path: 'wxfile://tmp_cover.png' }); },
    getFileSystemManager: () => ({ unlink: ({ filePath }) => removed.push(filePath) }),
  };
  return { api, calls, removed, drawn, images, canvases, context };
}
test('public snapshot allowlists display fields and never copies identity track rules or private content', () => {
  const card = base({ ownerId: 'SECRET_OWNER', sessionId: 'SECRET_RUN', route: 'SECRET_ROUTE', track: ['SECRET_TRACK'],
    title: '已公开标题', inventory: ['SECRET_INVENTORY'], events: ['SECRET_EVENT'], nodeAnswers: ['SECRET_ANSWER'],
    stats: [{ key: 'hp', label: '体温', value: 0 }, { key: 'private', label: 'SECRET_STAT', value: 20 }],
    medals: [{ name: '徽章', quantity: 0, image: 'SECRET_IMAGE', id: 'SECRET_ID' }],
    epilogues: [{ name: '角色', line: '公开尾声', npcId: 'SECRET_NPC' }], unlocks: { total: 3, known: [{ title: '已知结局', id: 'SECRET_ENDING' }] },
    nextScene: { name: '第二幕', asOfText: '2026-10-09 02:30', private: 'SECRET_NEXT' } });
  const before = JSON.stringify(card), snap = poster.publicSnapshot(card);
  assert.doesNotMatch(JSON.stringify(snap), /SECRET/); assert.equal(JSON.stringify(card), before);
  assert.deepEqual(snap.stats, ['体温：0']); assert.deepEqual(snap.medals, ['徽章 × 0']);
  assert.match(snap.next.join(' '), /不代表本局已发生内容/); assert.equal(snap.success, false);
});
test('public snapshot rejects missing result/time and does not invent enabled stats', () => {
  for (const card of [null, {}, base({ success: 1 }), base({ endedAtText: '' })]) assert.throws(() => poster.publicSnapshot(card), { code: 'PUBLIC_CARD' });
  const snap = poster.publicSnapshot(base({ stats: [{ key: 'hp', value: '9' }, { key: 'luck', value: Infinity }] }));
  assert.deepEqual(snap.stats, []); assert.deepEqual(snap.items, []);
});
test('snapshot text and collection bounds handle huge Unicode input with explicit clipping', () => {
  const snap = poster.publicSnapshot(base({ title: '😀'.repeat(100000), summary: '汉'.repeat(100000),
    items: Array.from({ length: 1000 }, () => ({ name: '物'.repeat(1000), quantity: 2 })),
    epilogues: Array.from({ length: 1000 }, () => ({ name: '人'.repeat(1000), line: '尾'.repeat(1000) })) }));
  assert.equal(Array.from(snap.title).length, 100); assert.ok(Array.from(snap.summary).length <= 1800);
  assert.ok(snap.items.length <= 12); assert.ok(snap.story.length <= 8); assert.equal(snap.clipped, true);
  assert.ok(JSON.stringify(snap).length < poster.MAX_TEXT * 2 + 1000);
  assert.doesNotMatch(snap.title, /[\uD800-\uDBFF]$/);
});
test('text-only selection omits cover and never copies image arrays', () => {
  const snap = poster.publicSnapshot(base({ cover: 'https://images.example/cover.png', items: [{ name: '物品', image: 'https://images.example/item.png' }] }), true);
  assert.equal(snap.cover, ''); assert.equal(snap.textOnly, true); assert.doesNotMatch(JSON.stringify(snap), /https/);
  assert.throws(() => poster.publicSnapshot(base({ cover: 'x'.repeat(2049) })), { code: 'COVER' });
});
test('layout bounds logical pixels and rows and honestly labels truncation', () => {
  const f = native(), snap = poster.publicSnapshot(base({ summary: '很长的结果。'.repeat(400), items: Array.from({ length: 12 }, () => ({ name: '很长的物品'.repeat(30), quantity: 99 })) }));
  const layout = poster.buildLayout(snap, f.context, true);
  assert.equal(layout.width, poster.WIDTH); assert.ok(layout.height <= poster.MAX_HEIGHT); assert.ok(layout.commands.length <= poster.MAX_ROWS);
  assert.equal(layout.clipped, true); assert.match(layout.footer, /更多公开详情/);
  assert.ok(layout.commands.every(row => row.x >= 0 && row.y < layout.height - 100));
  assert.deepEqual(poster.wrapText('甲乙\n丙丁', 2, text => text.length), ['甲乙', '丙丁']);
  assert.throws(() => poster.wrapText('a', 1, () => NaN), { code: 'CANVAS' });
});
test('explicit render uses isolated offscreen 2d bounded export and releases bitmap', async () => {
  const f = native(), lease = await poster.render(f.api, poster.publicSnapshot(base()), () => true);
  assert.deepEqual(f.calls[0], { kind: 'canvas', args: { type: '2d', width: 1, height: 1 } });
  const exported = f.calls.find(x => x.kind === 'export').args;
  assert.equal(exported.destWidth, poster.WIDTH * poster.SCALE); assert.ok(exported.destHeight <= poster.MAX_HEIGHT * poster.SCALE);
  assert.ok(exported.destWidth <= poster.MAX_CANVAS_SIDE); assert.ok(exported.destHeight <= poster.MAX_CANVAS_SIDE);
  assert.ok(exported.destWidth * exported.destHeight <= poster.MAX_CANVAS_PIXELS);
  assert.equal(exported.fileType, 'png'); assert.equal(f.canvases[0].width, 1); assert.equal(f.canvases[0].height, 1);
  assert.match(f.drawn.join(' '), /本局已结束/); assert.equal(lease.path, 'wxfile://tmp_poster.png');
  assert.deepEqual(f.removed, []); lease.dispose(); lease.dispose(); assert.deepEqual(f.removed, [lease.path]);
});
test('maximum-content rendering never exceeds 2400 physical side or 1.8 million pixels at allocation or export', async () => {
  const f = native({ enforcePhysicalBounds: true, export(args) {
    assert.ok(args.destWidth <= 2400 && args.destHeight <= 2400); assert.ok(args.destWidth * args.destHeight <= 1800000);
    args.success({ tempFilePath: 'wxfile://tmp_bounded.png' });
  } });
  const lease = await poster.render(f.api, poster.publicSnapshot(base({ title: '大'.repeat(100), scene: '场'.repeat(80),
    cover: 'https://images.example/cover.png', summary: '长'.repeat(1800),
    medals: Array.from({ length: 12 }, () => ({ name: '章'.repeat(80), quantity: 1 })),
    items: Array.from({ length: 12 }, () => ({ name: '物'.repeat(80), quantity: 1 })),
    epilogues: Array.from({ length: 8 }, () => ({ name: '人'.repeat(40), line: '文'.repeat(300) })) })), () => true);
  assert.equal(lease.width, 750); assert.ok(lease.height <= 2400); assert.equal(lease.clipped, true); lease.dispose();
});
test('cover render validates media dimensions, uses bounded contain fit and preserves shared native cache', async () => {
  const f = native(), lease = await poster.render(f.api, poster.publicSnapshot(base({ cover: 'https://images.example/cover.png' })), () => true);
  assert.equal(f.calls.filter(x => x.kind === 'cover').length, 1); assert.equal(f.images.length, 1);
  assert.ok(f.images[0][3] <= poster.WIDTH - 112); assert.ok(f.images[0][4] <= 300);
  assert.deepEqual(f.removed, []); lease.dispose(); assert.deepEqual(f.removed, ['wxfile://tmp_poster.png']);
});
test('unsupported or oversized covers fail closed with explicit text-only retry, never silent substitution', async () => {
  for (const info of [{ type: 'gif', width: 10, height: 10 }, { type: 'png', width: 9000, height: 2 }, { type: 'jpeg', width: 4000, height: 4000 }, { type: 'png', width: 0, height: 20 }]) {
    const f = native({ cover: args => args.success(Object.assign({ path: 'wxfile://tmp_cover.png' }, info)) });
    await assert.rejects(poster.render(f.api, poster.publicSnapshot(base({ cover: 'https://images.example/cover.png' })), () => true), { code: 'COVER' });
    assert.equal(f.calls.some(x => x.kind === 'export'), false); assert.equal(f.canvases[0].width, 1);
    assert.deepEqual(f.removed, []);
  }
  const f = native(); await assert.rejects(poster.render(f.api, poster.publicSnapshot(base({ cover: 'http://images.example/cover.png' })), () => true), { code: 'COVER' });
  const lease = await poster.render(f.api, poster.publicSnapshot(base({ cover: 'http://images.example/cover.png' }), true), () => true);
  assert.equal(f.calls.some(x => x.kind === 'cover'), false); lease.dispose();
});
test('cover decoding errors preserve shared native cache and do not export a misleading success image', async () => {
  const f = native({ imageFail: true });
  await assert.rejects(poster.render(f.api, poster.publicSnapshot(base({ cover: 'https://images.example/c.png' })), () => true), { code: 'COVER' });
  assert.deepEqual(f.removed, []); assert.equal(f.calls.some(x => x.kind === 'export'), false);
});
test('unsupported canvas never triggers another native export route', async () => {
  const f = native(); delete f.api.createOffscreenCanvas;
  await assert.rejects(poster.render(f.api, poster.publicSnapshot(base()), () => true), { code: 'CANVAS' });
  assert.equal(f.calls.length, 0);
});
test('stale selection before rendering and after export cannot retain output', async () => {
  const f = native(); await assert.rejects(poster.render(f.api, poster.publicSnapshot(base()), () => false), { code: 'CANCELLED' }); assert.equal(f.calls.length, 0);
  let live = true; const late = native({ export: args => { live = false; args.success({ tempFilePath: 'wxfile://tmp_old.png' }); } });
  await assert.rejects(poster.render(late.api, poster.publicSnapshot(base()), () => live), { code: 'CANCELLED' });
  assert.deepEqual(late.removed, ['wxfile://tmp_old.png']); assert.equal(late.canvases[0].height, 1);
});
test('stale cover callback does not proceed to decode/export and never deletes shared native cache', async () => {
  let live = true; const f = native({ cover: args => { live = false; args.success({ type: 'png', width: 200, height: 200, path: 'wxfile://tmp_old_cover.png' }); } });
  await assert.rejects(poster.render(f.api, poster.publicSnapshot(base({ cover: 'https://images.example/cover.png' })), () => live), { code: 'CANCELLED' });
  assert.equal(f.calls.some(x => x.kind === 'export'), false); assert.deepEqual(f.removed, []);
});
test('late cover info after timeout never unlinks a platform-owned cached file', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] }); let callback;
  const f = native({ cover: args => { callback = args.success; } });
  const pending = poster.render(f.api, poster.publicSnapshot(base({ cover: 'https://images.example/cover.png' })), () => true);
  const rejected = assert.rejects(pending, { code: 'COVER' }); t.mock.timers.tick(8001); await rejected;
  callback({ type: 'png', width: 640, height: 360, path: 'wxfile://shared_cached_cover.png' });
  assert.deepEqual(f.removed, []); assert.equal(f.calls.some(x => x.kind === 'export'), false); assert.equal(f.canvases[0].height, 1);
});
test('export timeout releases canvas and late native success deletes the unclaimed file', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] }); let callback;
  const f = native({ export: args => { callback = args.success; } });
  const pending = poster.render(f.api, poster.publicSnapshot(base()), () => true);
  const rejected = assert.rejects(pending, { code: 'TIMEOUT' }); t.mock.timers.tick(poster.RENDER_TIMEOUT + 1); await rejected;
  assert.equal(f.canvases[0].height, 1); callback({ tempFilePath: 'wxfile://tmp_late.png' }); assert.deepEqual(f.removed, ['wxfile://tmp_late.png']);
});
test('callback timeouts and sync throws settle once; duplicate success never removes live output', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] }); let success; const late = [];
  const pending = poster.callbackOperation(ok => { success = ok; }, 20, value => late.push(value));
  const rejected = assert.rejects(pending, { code: 'TIMEOUT' }); t.mock.timers.tick(21); await rejected; success('late'); assert.deepEqual(late, ['late']);
  await poster.callbackOperation(ok => { ok('first'); ok('duplicate'); }, 20, value => late.push(value)); assert.deepEqual(late, ['late']);
  await assert.rejects(poster.callbackOperation(() => { throw new Error('native failure'); }, 20), /native failure/);
});
test('native lease pin survives retirement until callback and cannot be pinned again', () => {
  const f = native(), lease = poster.exportLease(f.api, 'wxfile://tmp_pin.png', {}), release = lease.pin();
  lease.dispose(); assert.deepEqual(f.removed, []); assert.throws(() => lease.pin(), { code: 'CANCELLED' });
  release(); release(); assert.deepEqual(f.removed, [lease.path]);
});
test('abandoned native lease pin has a bounded retirement timeout', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = native(), lease = poster.exportLease(f.api, 'wxfile://tmp_pin.png', {}), release = lease.pin();
  lease.dispose(); t.mock.timers.tick(poster.NATIVE_TIMEOUT + 1); assert.deepEqual(f.removed, [lease.path]); release(); assert.equal(f.removed.length, 1);
});

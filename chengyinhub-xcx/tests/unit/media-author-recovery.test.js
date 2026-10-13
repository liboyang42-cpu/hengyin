const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '../..');
const FILE = path.join(ROOT, 'pages/privacy/index.js');
function load() {
  const state = { member: 9, token: 'fixture-token-a', pending: false, calls: [], clipboard: [] };
  global.getApp = () => ({ getUserID: () => state.member, getAuthorization: () => state.token,
    hasPendingPrivacyAuthorization: () => state.pending, sendRequest: options => state.calls.push(options),
    reLogin: () => Promise.resolve({ ok: true }), firstLogin: () => Promise.resolve({ ok: true }),
    declineUsageConsent() {}, resolvePrivacyAuthorization() {} });
  global.wx = { setClipboardData: data => state.clipboard.push(data.data), showToast() {} };
  global.Page = definition => { state.definition = definition; };
  delete require.cache[require.resolve(FILE)]; require(FILE);
  const page = Object.assign({}, state.definition, { setData(patch, done) { Object.assign(this.data, patch); if (done) done(); } });
  page.data = JSON.parse(JSON.stringify(state.definition.data)); page.onLoad(); page.onShow();
  return { state, page };
}
const rows = status => [{ assetId: 12, mediaType: 'AUDIO', reviewState: status, reviewReason: status === 'REJECTED' ? '录音需要复核' : '' }];
const event = { currentTarget: { dataset: { id: '12' } } };
test('normal author entry rereads persisted media after restart and HOLD is never copyable', () => {
  const { state, page } = load(); assert.equal(state.calls[0].url, '/api/media/asset/list');
  state.calls[0].success({ code: 200, data: rows('HOLD') }); assert.equal(page.data.mediaRows[0].stateLabel, '待审核');
  page.copyApprovedMedia(event); assert.equal(state.calls.length, 1); assert.deepEqual(state.clipboard, []);
  page.onUnload(); assert.deepEqual(page.data.mediaRows, []);
  const restarted = load(); assert.equal(restarted.state.calls[0].url, '/api/media/asset/list');
  restarted.state.calls[0].success({ code: 200, data: rows('HOLD') }); assert.equal(restarted.page.data.mediaRows[0].assetId, '12');
});
test('rejected author retries original asset and independently refreshes persisted HOLD state', () => {
  const { state, page } = load(); state.calls[0].success({ code: 200, data: rows('REJECTED') });
  assert.equal(page.data.mediaRows[0].reviewReason, '录音需要复核'); page.retryOwnMedia(event);
  assert.equal(state.calls[1].url, '/api/media/asset/12/retry'); assert.equal(state.calls[1].method, 'POST');
  state.calls[1].success({ code: 200, data: { assetId: 12, reviewState: 'HOLD' } }); assert.equal(state.calls[2].url, '/api/media/asset/list');
  state.calls[2].success({ code: 200, data: rows('HOLD') }); assert.equal(page.data.mediaRows[0].reviewState, 'HOLD');
});
test('actual preview endpoint yields ephemeral author URL; hide and unload clear it', () => {
  const { state, page } = load(); state.calls[0].success({ code: 200, data: rows('HOLD') }); page.previewOwnMedia(event);
  assert.equal(state.calls[1].url, '/api/media/asset/12/preview');
  state.calls[1].success({ code: 200, data: { authorPreviewUrl: 'https://media.invalid/private?expires=300' } });
  assert.equal(page.data.mediaPreviewType, 'AUDIO'); page.onHide(); assert.equal(page.data.mediaPreviewUrl, '');
  page.onShow(); state.calls[2].success({ code: 200, data: rows('HOLD') }); page.previewOwnMedia(event); page.onUnload();
  state.calls[3].success({ code: 200, data: { authorPreviewUrl: 'https://media.invalid/stale' } }); assert.equal(page.data.mediaPreviewUrl, '');
});
test('stale callback cannot repopulate another account or rotated token', () => {
  const { state, page } = load(); const old = state.calls[0]; state.member = 10; state.token = 'fixture-token-b'; page.onShow();
  state.calls[1].success({ code: 200, data: [] }); old.success({ code: 200, data: rows('REJECTED') }); assert.deepEqual(page.data.mediaRows, []);
  page.loadOwnMedia(); state.calls[2].success({ code: 200, data: rows('HOLD') }); page.previewOwnMedia(event); state.token = 'fixture-token-c';
  state.calls[3].success({ code: 200, data: { authorPreviewUrl: 'https://media.invalid/stale' } });
  assert.equal(page.data.mediaPreviewUrl, ''); assert.deepEqual(page.data.mediaRows, []);
});
test('401 and 403 clear private state with distinct login and permission recovery', () => {
  for (const code of [401, 403]) {
    const { state, page } = load(); state.calls[0].success({ code: 200, data: rows('HOLD') }); page.previewOwnMedia(event);
    state.calls[1].fail({ code }, code); assert.deepEqual(page.data.mediaRows, []); assert.equal(page.data.mediaPreviewUrl, '');
    assert.equal(page.data.mediaAuthRequired, code === 401); assert.equal(page.data.mediaAccessDenied, code === 403);
  }
});
test('copy independently requires real approved receipt and refuses a premature URL', () => {
  const { state, page } = load(); state.calls[0].success({ code: 200, data: rows('APPROVED') }); page.copyApprovedMedia(event);
  state.calls[1].success({ code: 200, data: { reviewState: 'HOLD', url: 'https://media.invalid/unsafe' } }); assert.deepEqual(state.clipboard, []);
  page.copyApprovedMedia(event); state.calls[2].success({ code: 200, data: { reviewState: 'APPROVED', url: 'https://media.invalid/approved' } });
  assert.deepEqual(state.clipboard, ['https://media.invalid/approved']);
});
test('guest and unsettled privacy authorization never request private author media', () => {
  const { state, page } = load(); page.onHide(); state.member = 0; state.token = ''; page.onShow();
  assert.equal(state.calls.length, 1); assert.equal(page.data.mediaAuthRequired, true);
  state.pending = true; page.data.pendingAuthorization = true; page.onShow(); assert.equal(state.calls.length, 1);
});
test('normal settings reaches real author route and model preview loads actual GLTF without camera', () => {
  assert.match(fs.readFileSync(path.join(ROOT, 'pages/shezhi/components/privacy-sheet/index.js'), 'utf8'), /url: '\/pages\/privacy\/index'/);
  assert.match(fs.readFileSync(path.join(ROOT, 'pages/shezhi/components/privacy-sheet/index.wxml'), 'utf8'), /bindtap="openOwnMedia"/);
  const xr = fs.readFileSync(path.join(ROOT, 'components/cy/media-model-preview/index.wxml'), 'utf8');
  assert.match(xr, /type="gltf"[^>]*src="\{\{src\}\}"/); assert.doesNotMatch(xr, /ar-system|is-ar-camera/);
});
test('private XR preview uses existing fit math, rotates and cancels its bounded loader on detach', () => {
  const vm = require('node:vm'); let definition, timer, cleared = 0; const events = [], scale = [], position = [];
  const transform = { scale: { setValue: (...value) => scale.push(value) }, position: { setValue: (...value) => position.push(value) } };
  const model = { calcTotalBoundBox: () => ({ center: { x: 2, y: 1, z: 0 }, size: { x: 4, y: 2, z: 1 } }) };
  const sys = { GLTF: 'gltf', Transform: 'transform' };
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'components/cy/media-model-preview/index.js'), 'utf8'), {
    require: () => require(path.join(ROOT, 'pages/play/components/playkit-scan/ar/ar-math.js')),
    Component: value => { definition = value; }, wx: { getXrFrameSystem: () => sys },
    setTimeout: callback => { timer = callback; return 1; }, clearTimeout: () => { cleared++; },
  });
  const component = { data: { angle: 0 }, setData(patch) { Object.assign(this.data, patch); }, triggerEvent: event => events.push(event) };
  Object.assign(component, definition.methods); definition.lifetimes.attached.call(component);
  component.handleReady({ detail: { value: { getNodeById: () => ({ getComponent: type => type === 'gltf' ? model : transform }) } } });
  component.handleLoaded(); assert.deepEqual(scale, [[0.375, 0.375, 0.375]]); assert.deepEqual(position, [[-0.75, 0, 0]]);
  component.rotate(); assert.equal(component.data.angle, 45); assert.ok(cleared > 0);
  definition.lifetimes.detached.call(component); timer(); assert.deepEqual(events, []);
});
test('private XR refuses malformed geometry and emits recoverable error instead of displaying NaN', () => {
  const vm = require('node:vm'); let definition; const events = [];
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'components/cy/media-model-preview/index.js'), 'utf8'), {
    require: () => require(path.join(ROOT, 'pages/play/components/playkit-scan/ar/ar-math.js')),
    Component: value => { definition = value; }, wx: { getXrFrameSystem: () => ({ GLTF: 'gltf' }) },
    setTimeout: () => 1, clearTimeout() {},
  });
  const component = { triggerEvent: event => events.push(event) }; Object.assign(component, definition.methods);
  definition.lifetimes.attached.call(component);
  component.handleReady({ detail: { value: { getNodeById: () => ({ getComponent: () => ({ calcTotalBoundBox: () => ({ center: { x: 0, y: 0, z: 0 }, size: { x: Infinity, y: 1, z: 1 } }) }) }) } } });
  component.handleLoaded(); assert.deepEqual(events, ['error']);
});

const fullPage = top => Array.from({ length: 50 }, (_, index) => ({ assetId: String(BigInt(top) - BigInt(index)), mediaType: 'AUDIO', reviewState: 'HOLD' }));
test('author cursor finds old rejected media after fifty new assets without offset drift', () => {
  const { state, page } = load(); state.calls[0].success({ code: 200, data: fullPage('70') });
  assert.equal(page.data.mediaBeforeId, '21'); assert.equal(page.data.mediaHasMore, true); page.loadOlderMedia();
  assert.equal(state.calls[1].url, '/api/media/asset/list?beforeId=21'); page.loadOlderMedia(); assert.equal(state.calls.length, 2);
  const older = Array.from({ length: 20 }, (_, index) => ({ assetId: String(20 - index), mediaType: 'AUDIO', reviewState: index === 19 ? 'REJECTED' : 'HOLD' }));
  state.calls[1].success({ code: 200, data: older }); assert.equal(page.data.mediaRows.length, 70); assert.equal(page.data.mediaRows[69].stateLabel, '未通过');
  assert.equal(page.data.mediaHasMore, false); page.loadOlderMedia(); assert.equal(state.calls.length, 2);
  page.retryOwnMedia({ currentTarget: { dataset: { id: '1' } } }); assert.equal(state.calls[2].url, '/api/media/asset/1/retry');
});
test('author pagination preserves exact long cursors and rejects duplicates and unsafe numeric IDs', () => {
  const { state, page } = load(); state.calls[0].success({ code: 200, data: fullPage('9223372036854775807') });
  const cursor = page.data.mediaBeforeId; assert.equal(cursor, '9223372036854775758'); page.loadOlderMedia();
  assert.equal(state.calls[1].url, '/api/media/asset/list?beforeId=' + cursor);
  state.calls[1].success({ code: 200, data: [{ assetId: cursor, mediaType: 'AUDIO', reviewState: 'HOLD' }] });
  assert.equal(page.data.mediaRows.length, 50); assert.match(page.data.mediaError, /加载失败/);
  page.loadOlderMedia(); state.calls[2].success({ code: 200, data: [{ assetId: 9007199254740993, reviewState: 'HOLD' }] }); assert.equal(page.data.mediaRows.length, 50);
  page.loadOwnMedia(); state.calls[3].success({ code: 200, data: [{ assetId: '9223372036854775808', reviewState: 'HOLD' }] }); assert.deepEqual(page.data.mediaRows, []);
});
test('older media requests clear on hide account switch and authentication failures', () => {
  for (const failure of ['hide', 'account', 401, 403]) {
    const { state, page } = load(); state.calls[0].success({ code: 200, data: fullPage('70') }); page.loadOlderMedia();
    if (failure === 'hide') { page.onHide(); state.calls[1].success({ code: 200, data: rows('REJECTED') }); }
    else if (failure === 'account') { state.member = 10; state.token = 'other-token'; state.calls[1].success({ code: 200, data: rows('REJECTED') }); }
    else state.calls[1].fail({ code: failure }, failure);
    assert.deepEqual(page.data.mediaRows, []); assert.equal(page.data.mediaHasMore, false); assert.equal(page.data.mediaBeforeId, '');
  }
});
test('older media failed request stays recoverable and refresh invalidates its late result', () => {
  const { state, page } = load(); state.calls[0].success({ code: 200, data: fullPage('70') }); page.loadOlderMedia();
  state.calls[1].fail({ code: 500 }); assert.match(page.data.mediaError, /失败/); assert.equal(page.data.mediaRows.length, 50);
  page.loadOlderMedia(); page.loadOwnMedia(); state.calls[3].success({ code: 200, data: rows('HOLD') });
  state.calls[2].success({ code: 200, data: [{ assetId: '1', reviewState: 'REJECTED', mediaType: 'AUDIO' }] });
  assert.deepEqual(page.data.mediaRows.map(row => row.assetId), ['12']);
  assert.match(fs.readFileSync(path.join(ROOT, 'pages/privacy/index.wxml'), 'utf8'), /bindtap="loadOlderMedia"/);
});

test('account change before pagination immediately wipes the previous loaded owner instead of mixing lists', () => {
  const { state, page } = load(); state.calls[0].success({ code: 200, data: fullPage('70') });
  state.member = 10; state.token = 'new-owner-token'; page.loadOlderMedia();
  assert.deepEqual(page.data.mediaRows, []); assert.equal(state.calls[1].url, '/api/media/asset/list');
  state.calls[1].success({ code: 200, data: [{ assetId: '80', mediaType: 'AUDIO', reviewState: 'HOLD' }] });
  assert.deepEqual(page.data.mediaRows.map(row => row.assetId), ['80']);
});

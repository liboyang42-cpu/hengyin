'use strict';
// FE-STORY 6.1:自由探索只读已到访地点的章节。
// 锁卡不渲染正文;openChapStory 锁定时不打开;到访成功后卡片翻转并打开本章一次(触感一次);
// 结果未知只回读 /nodes 与原命令回执,不自动重发;没送达才允许再发。城市定向不进任何分支。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const visit = require('../../pages/play/utils/fe-story-visit.js');

const modals = [];
const toasts = [];
const calls = [];
let modalAnswer = { confirm: true, cancel: false };
global.getCurrentPages = () => [{ selectComponent(id) {
  if (id === '#cy-modal-host') return { open(opts) { modals.push(opts); if (opts.success) opts.success(modalAnswer); } };
  if (id === '#cy-toast') return { show(o) { toasts.push(o && o.title); } };
  return null;
} }];
let locationResult = { latitude: 31.2, longitude: 121.4, accuracy: 30 };
let scanResult = { result: 'CODE-ABC' };
global.wx = {
  getLocation(o) { calls.push(['getLocation', o.type, o.isHighAccuracy]); if (locationResult.errMsg) o.fail(locationResult); else o.success(locationResult); },
  scanCode(o) { calls.push(['scanCode']); o.success(scanResult); },
  vibrateShort() { calls.push(['vibrate']); },
  openSetting() { calls.push(['openSetting']); },
};
const gate = require('../../pages/play/utils/fe-story-gate.js');

function lockedNode(extra) {
  return Object.assign({ nodeId: 7, chapterId: 3, name: '示例站点', storyLocked: true, visited: false, visitCheck: 'GPS', hookText: '门口有一棵树' }, extra);
}

function harness(nodeExtra, responses) {
  const queue = (responses || []).slice();
  const page = Object.assign({}, gate.methods(), {
    data: { mode: 2, storyGate: true, nodes: [lockedNode(nodeExtra)], seatTiles: [{ nodeId: 7 }], hero: { show: false, node: null }, regId: 5 },
    sent: [], receipts: [], resolved: [], loads: 0, opened: 0,
    setData(patch, cb) {
      Object.keys(patch).forEach((k) => {
        if (k.indexOf('.') > 0) { const [a, b] = k.split('.'); this.data[a] = Object.assign({}, this.data[a], { [b]: patch[k] }); }
        else this.data[k] = patch[k];
      });
      if (cb) cb();
    },
    _isPlayAccountCurrent: () => true,
    _sessionKey: () => 't:1',
    _routeWritePayload(kind, nodeId, input) { return Object.assign({ kind }, input); },
    _sendBasicRouteIntent(url, body) { this.sent.push([url, body]); return Promise.resolve(queue.shift() || { code: 200, data: {} }); },
    _readBasicRouteIntent(kind, nodeId) { this.receipts.push([kind, nodeId]); return Promise.resolve(this.receipt || { intentTerminal: false }); },
    _resolveRouteAction(kind, nodeId) { this.resolved.push([kind, nodeId]); },
    _handleRouteConflict: () => false,
    rebuild() {},
    loadData() { this.loads++; if (this.afterLoad) this.afterLoad(); this._consumeVisitOpen(); },
    _heroView(node) { return { nodeId: node.nodeId, storyLocked: node.storyLocked === true, chapDesc: node.storyLocked ? '' : '正文' }; },
    openChapStory() { this.opened++; },
    _openHero(node, idx) { this.heroOpened = [node.nodeId, idx]; },
  });
  return page;
}
const tick = () => new Promise((r) => setImmediate(r));
function reset() { modals.length = 0; toasts.length = 0; calls.length = 0; modalAnswer = { confirm: true, cancel: false };
  locationResult = { latitude: 31.2, longitude: 121.4, accuracy: 30 }; scanResult = { result: 'CODE-ABC' }; }

test('纯函数:按钮文案、到访日期、进度、碎片标题', () => {
  assert.equal(visit.visitButtonText('SCAN'), '扫码解锁');
  assert.equal(visit.visitButtonText('GPS'), '我已到达');
  assert.equal(visit.visitButtonText(undefined), '我已到达');
  assert.equal(visit.visitDateText('2026-11-17T02:00:00Z'), '11月17日到访');
  assert.equal(visit.visitDateText(null), '');
  assert.equal(visit.progressText(2, 5), '已解锁 2 / 5 章');
  assert.equal(visit.fragmentTitle(3), '第 3 个到访');
  const locked = visit.tileView({ storyLocked: true, visitCheck: 'scan', hookText: '一句钩子', visitTime: null }, '320m');
  assert.deepEqual(locked, { storyLocked: true, visitCheck: 'SCAN', visitBtn: '扫码解锁', visitText: '', distText: '320m', hookText: '一句钩子' });
  const open = visit.tileView({ storyLocked: false, hookText: 'x', visitTime: '2026-11-17T02:00:00Z' }, '320m');
  assert.equal(open.visitText, '11月17日到访');
  assert.equal(open.hookText, '', '已解锁的卡不再出钩子');
});

test('回包分类:没送达才可重试;结果未知只回读', () => {
  assert.equal(visit.outcome({ code: 200 }), 'ok');
  assert.equal(visit.outcome({ code: 'fail', netFail: true, intentNotExecuted: true }), 'notSent');
  assert.equal(visit.outcome({ code: 'fail', netFail: true }), 'unknown');
  assert.equal(visit.outcome({ code: 520, httpFail: true }), 'unknown');
  assert.equal(visit.outcome({ code: 500, errorCode: 'ARRIVE_TOO_FAR' }), 'rejected');
  assert.equal(visit.outcome({ obsolete: true }), 'obsolete');
  assert.equal(visit.rejection({ errorCode: 'ARRIVE_TOO_FAR', data: { distanceMeters: 184 } }).title, '离这里还有约 180 米');
  assert.equal(visit.rejection({ errorCode: 'ARRIVE_TOO_FAR', distanceMeters: 1236 }).title, '离这里还有约 1240 米');
  assert.equal(visit.rejection({ errorCode: 'ARRIVE_TOO_FAR' }).title, '还没到这里');
  assert.equal(visit.rejection({ errorCode: 'ARRIVE_ACCURACY_LOW' }).title, '定位不准，再试一次');
  assert.equal(visit.rejection({ errorCode: 'VISIT_SCAN_REQUIRED' }).retry, '扫码解锁');
});

test('GPS 到访:先说明用途,再前台取一次 gcj02 高精度定位,带坐标与精度调 /api/play/arrive;成功后翻卡并打开本章一次', async () => {
  reset();
  const page = harness();
  page.afterLoad = () => { page.data.nodes = [lockedNode({ storyLocked: false, visited: true })]; };
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(modals[0].title, gate.PURPOSE_TITLE, '取位置前先说明用途');
  assert.deepEqual(calls[0], ['getLocation', 'gcj02', true]);
  assert.equal(page.sent.length, 1);
  assert.equal(page.sent[0][0], '/api/play/arrive');
  assert.deepEqual([page.sent[0][1].latitude, page.sent[0][1].longitude, page.sent[0][1].accuracy], [31.2, 121.4, 30]);
  assert.deepEqual(page.resolved, [['arrive', 7]]);
  assert.equal(page.loads, 1, '用回读拿到本章正文');
  assert.equal(page.opened, 1, '打开本章一次');
  assert.equal(calls.filter((c) => c[0] === 'vibrate').length, 1, '触感一次');
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(page.sent.length, 1, '已解锁的卡再点不再发');
  // 第二次点别的锁卡不再重复说明用途
  page.data.nodes = [lockedNode({ nodeId: 8 })];
  page.onVisitTap({ currentTarget: { dataset: { id: 8 } } });
  await tick();
  assert.equal(modals.filter((m) => m.title === gate.PURPOSE_TITLE).length, 1);
});

test('结果未知:不自动重发,只回读 /nodes;再点读原命令回执,仍不重发', async () => {
  reset();
  const page = harness(null, [{ code: 'fail', netFail: true }]);
  page._visitPurposeShown = true;
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(page.sent.length, 1);
  assert.equal(page.loads, 1, '结果未知 → 回读 /api/play/nodes');
  assert.equal(page.opened, 0, '仍锁着就不打开');
  assert.deepEqual(page.resolved, [], '未知的意图不能释放');
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(page.sent.length, 1, '再点不重发');
  assert.deepEqual(page.receipts, [['arrive', 7]], '再点只读原命令回执');
  assert.equal(page.loads, 2);
  // 回执确认已到访 → 释放意图、翻卡、打开一次
  page.receipt = { intentTerminal: true, code: 200, data: {} };
  page.afterLoad = () => { page.data.nodes = [lockedNode({ storyLocked: false, visited: true })]; };
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.deepEqual(page.resolved, [['arrive', 7]]);
  assert.equal(page.sent.length, 1);
  assert.equal(page.opened, 1);
});

test('结果未知后回读到已到访:打开一次并释放意图', async () => {
  reset();
  const page = harness(null, [{ code: 520, httpFail: true }]);
  page._visitPurposeShown = true;
  page.afterLoad = () => { page.data.nodes = [lockedNode({ storyLocked: false, visited: true })]; };
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(page.opened, 1);
  assert.deepEqual(page.resolved, [['arrive', 7]]);
  assert.equal(page._visitUnknown, null);
});

test('没送达(intentNotExecuted):释放原意图,下一次点击用新定位重新发', async () => {
  reset();
  const page = harness(null, [{ code: 'fail', netFail: true, intentNotExecuted: true }, { code: 200, data: {} }]);
  page._visitPurposeShown = true;
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.deepEqual(page.resolved, [['arrive', 7]]);
  assert.equal(page.loads, 0);
  assert.ok(toasts.includes('网络异常，请重试'));
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(page.sent.length, 2, '证明没送达的才允许再发');
});

test('太远:「离这里还有约 X 米」+「重新定位」;需扫码:「扫码解锁」走 /api/play/checkin', async () => {
  reset();
  const page = harness(null, [{ code: 500, errorCode: 'ARRIVE_TOO_FAR', distanceMeters: 418 }, { code: 500, errorCode: 'VISIT_SCAN_REQUIRED' }, { code: 200, data: {} }]);
  page._visitPurposeShown = true;
  modalAnswer = { confirm: false, cancel: true };
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(modals[0].title, '离这里还有约 420 米');
  assert.equal(modals[0].confirmText, '重新定位');
  modalAnswer = { confirm: true, cancel: false };
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick(); await tick();
  assert.equal(modals[1].title, '这里需要扫码');
  assert.equal(modals[1].confirmText, '扫码解锁');
  assert.equal(page.sent[2][0], '/api/play/checkin');
  assert.equal(page.sent[2][1].code, 'CODE-ABC');
});

test('扫码地点:锁卡按钮直接扫码,不取定位;定位被拒时引到设置或改用扫码', async () => {
  reset();
  const page = harness({ visitCheck: 'SCAN' });
  page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.deepEqual(calls[0], ['scanCode']);
  assert.equal(calls.some((c) => c[0] === 'getLocation'), false);
  assert.equal(page.sent[0][0], '/api/play/checkin');
  reset();
  const denied = harness();
  denied._visitPurposeShown = true;
  locationResult = { errMsg: 'getLocation:fail auth deny' };
  denied.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(modals[0].title, '定位没有开启');
  assert.equal(modals[0].cancelText, '改用扫码');
  assert.ok(calls.some((c) => c[0] === 'openSetting'));
});

test('城市定向 / 未开启到访闸 / 已解锁的卡:onVisitTap 一律不动', async () => {
  reset();
  for (const patch of [{ mode: 1 }, { storyGate: false }]) {
    const page = harness();
    Object.assign(page.data, patch);
    page.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
    await tick();
    assert.equal(page.sent.length, 0);
  }
  const open = harness({ storyLocked: false });
  open.onVisitTap({ currentTarget: { dataset: { id: 7 } } });
  await tick();
  assert.equal(open.sent.length + calls.length + modals.length, 0);
});

test('选章面板:锁定的章打开该章锁卡', () => {
  const page = harness();
  page._openChapterLockedCard({ id: 3 });
  assert.deepEqual(page.heroOpened, [7, 0]);
  const pops = read('pages/play/utils/play-stage-pops.js');
  assert.match(pops, /gate && c && c\.chapterLocked \? 'visit'/);
  const js = read('pages/play/index.js');
  assert.match(js, /if \(card && this\.data\.storyGate && card\.chapterLocked\) \{ this\._openChapterLockedCard\(card\); return true; \}/);
  const wxml = read('pages/play/index.wxml');
  assert.match(wxml, /<text class="chpick__sub" wx:if="\{\{chapterPick\.states\[index\] === 'visit'\}\}">\{\{storyExpired \? '已过期，不能再解锁' : '到访后解锁'\}\}<\/text>/);
});

test('锁卡不渲染正文;锁定的章打不开;碎片卡标题按到访数', () => {
  const wxml = read('pages/play/index.wxml');
  const lockedTile = wxml.slice(wxml.indexOf('<view class="fx-tile__c" wx:if="{{item.storyLocked}}">'), wxml.indexOf('<view class="fx-tile__c" wx:elif="{{storyGate}}">'));
  assert.ok(lockedTile.length > 50);
  assert.doesNotMatch(lockedTile, /description|cardHook|fragmentText|storyText|chapLead|playName/);
  const js = read('pages/play/index.js');
  assert.match(js, /openChapStory\(\) \{\n    const v = this\.data\.hero\.node \|\| \{\};\n    if \(v\.storyLocked === true\) return;/);
  assert.match(js, /this\.data\.storyGate \? feVisit\.fragmentTitle\(nodes\.filter\(\(n\) => n\.visited\)\.length\) : '我的第 ' \+ done\.length \+ ' 步'/);
  // normNode 只在 mode 2 映射到访字段:城市定向的节点形状不变
  assert.match(js, /\.\.\.\(shopDay \? \{ visited: n\.visited === true, visitTime: n\.visitTime \|\| null, visitCheck: feVisit\.normCheck\(n\.visitCheck\),/);
  assert.match(js, /storyGate: mode === 2 && d\.storyTotal != null/);
});

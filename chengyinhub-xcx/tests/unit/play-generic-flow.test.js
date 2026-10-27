const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const gameplay = require('../../pages/play/utils/theme-gameplay.js');
const backpack = require('../../pages/play/utils/theme-backpack.js');

// Java: GameplayToolState.availability / routeActionSummary / personalToolSummary.
const pending = require('./fixtures/generic-proof-view.json');
const passed = { ...pending, quests: pending.quests.map(q => ({ ...q, enabled: true, trustedPass: true, disabledReason: null,
  actionSummary: { operation: 'QUEST_RESOLVE', enabled: true, trustedPass: true, disabledReason: null } })) };
// 基础写入先只读原局身份再发写；这里只让写请求本身丢失响应。
const ORIGINAL_CONTEXT = { code: 200, data: { routeSessionId: '77', expectedRouteVersion: 0 } };
function setData(patch, cb) { for (const [key, value] of Object.entries(patch)) { const parts = key.split('.'); let at = this.data; for (const part of parts.slice(0, -1)) at = at[part] || (at[part] = {}); at[parts.at(-1)] = value; } if (cb) cb(); }
function loadPage() {
  let definition; const calls = [];
  global.wx = { showToast() {}, vibrateShort() {}, nextTick(cb) { cb(); } };
  const app = { sendRequest(options) { calls.push(options); options.success({ code: 200, data: {} }); } };
  global.getApp = () => app; global.Page = options => { definition = options; };
  const file = require.resolve('../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { ...JSON.parse(JSON.stringify(definition.data)), nodes: [{ nodeId: 41, vm: 5, done: false, playable: true, points: 12 }], mode: 1 }, setData,
    _routeState: { routeMode: 'LINEAR' }, _calls: calls, _sound: { sfx() {} }, rebuild() {}, stopNav() {},
    refreshRouteState() { this.reads = (this.reads || 0) + 1; return Promise.resolve(true); }, refreshThemeBackpack() { this.bagReads = (this.bagReads || 0) + 1; return Promise.resolve(true); },
    _triggerCelebration() { this.celebrated = true; }, _animateCompletedSegment() {}, diegetic(text) { this.message = text; } });
  return { page, calls, app };
}

test('共享 PROOF_PENDING 不受粗粒度 availableActions 放行，不提供结算写按钮', () => {
  const row = gameplay.actionRows(pending, [])[0];
  assert.equal(row.enabled, false); assert.match(row.disabledText, /待审核/); assert.notEqual(row.actionText, '完成任务');
});
test('trustedPass 只证明凭证通过，QUEST_RESOLVE 是否可结算仍遵从 enabled', () => {
  const row = gameplay.actionRows(passed, [])[0];
  assert.equal(row.enabled, true); assert.equal(row.operation, 'QUEST_RESOLVE'); assert.match(row.actionText, /结算/);
  const denied = { ...passed, quests: passed.quests.map(q => ({ ...q, enabled: false, disabledReason: 'LIMIT_REACHED', actionSummary: { ...q.actionSummary, enabled: false, disabledReason: 'LIMIT_REACHED' } })) };
  assert.equal(gameplay.actionRows(denied, [])[0].enabled, false);
  const allowedByServer = { ...passed, awaitingChallenge: true, gameplay: {}, quests: passed.quests.map(q => ({ ...q, actionSummary: { ...q.actionSummary, costs: { hpCost: 2 } } })) };
  assert.equal(gameplay.actionRows(allowedByServer, [])[0].enabled, true, '现代刷新显式 enabled 不由本端费用/恢复规则覆盖');
});
test('服务器未完成的共享/个人到达凭证不置 done、不发默认 XP，改读任务状态', async () => {
  for (const runtime of [{ sharedRuntime: { teamId: 8, receiptId: 19, runtimeInstance: 'game:7', authorityEpoch: 4, basicVerified: true } }, { themeRuntime: { sessionId: 7, version: 9, activeQuest: 'q', quests: [], allowedActions: [] } }]) {
    const { page } = loadPage();
    await page.onComplete(41, { nodeId: 41, arrived: true, done: false, ...runtime });
    assert.equal(page.data.nodes[0].done, false); assert.equal(page.data.nodes[0].playable, true); assert.equal(page.data.flyScore, false); assert.equal(page.celebrated, undefined);
    assert.ok(page.bagReads || page.reads);
  }
});
test('待审核照片成功回包不是正式结算，不飞 XP、不庆祝', async () => {
  const { page } = loadPage();
  await page.onComplete(41, { nodeId: 41, done: false, selfReported: true, disabledReason: 'PROOF_PENDING' });
  assert.equal(page.data.nodes[0].done, false); assert.equal(page.data.flyScore, false); assert.equal(page.celebrated, undefined); assert.match(page.message || page.data.gNetErr, /待审核/);
});
test('正式完成零 XP 或无 XP 不用节点 points 或默认 12 补奖励', () => {
  for (const data of [{ firstTime: true, xp: 0 }, { firstTime: true }, { firstTime: false, xp: 12 }]) {
    const { page } = loadPage(); page.onComplete(41, data); assert.equal(page.data.flyScore, false);
  }
});
test('个人 OPEN_QUEST 用 ValidationMethod 的 1/3/4/5 既有校验入口，无空高级配置', async () => {
  const java = fs.readFileSync(path.resolve(__dirname, '../../../chengyinhub-common/src/main/java/com/chengyinhub/common/constant/ValidationMethod.java'), 'utf8');
  for (const [vm, label] of [[1, '文字验证'], [3, '选项验证'], [4, '扫码打卡'], [5, 'GPS到达']]) {
    assert.match(java, new RegExp('case '+vm+':\\s+return "'+label+'"'));
    const { page } = loadPage(); let opened, arrived;
    page.data.nodes = [{ nodeId: 41, vm, done: false, hasAdvanced: false }];
    page.data.themeBag = backpack.present({ sessionId: 7, version: 9, status: 'ACTIVE', activeQuest: 'q', quests: [{ questKey: 'q', nodeId: 41 }], allowedActions: [{ operation: 'QUEST_CANCEL', contentId: 'q' }] }, []);
    page.startGame = node => { opened = node; }; page.onArriveTap = () => { arrived = true; }; page.onSheetGoTap = () => { arrived = true; };
    await page.onThemeGameplayAction({ currentTarget: { dataset: { index: 0 } } });
    assert.equal(page.data.advancedPlay.show, false);
    if (vm === 5) assert.equal(arrived, true); else assert.equal(opened.nodeId, 41);
    opened = null; arrived = false;
    page.data.themeBag = backpack.present({ ...pending, scope: 'PLAYER', version: 9, activeQuest: 'q', allowedActions: [{ operation: 'QUEST_CANCEL', contentId: 'q' }] }, []);
    await page.onThemeGameplayAction({ currentTarget: { dataset: { index: 0, open: true } } });
    if (vm === 5) assert.equal(arrived, true); else assert.equal(opened.nodeId, 41);
  }
});

test('共享结算未知或失败回读原请求 receipt，待确认期间再次点击不重写', async () => {
  for (const first of [{ code: 'fail', netFail: true }, { code: 500, msg: '本局已更新' }, { code: 200, data: { activityId: 5, requestId: 'wrong', action: 'QUEST_RESOLVE', receiptId: 4, revision: 12, outcome: 'PENDING' } }]) {
    const { page } = loadPage(); const calls = [];
    page.data.themeBag = backpack.present(passed, []); page._loadPlayerGameModule = () => Promise.resolve(true);
    Object.assign(page, backpack.methods(async (url, method, data) => { calls.push({ url, method, data }); if (method === 'POST') return first; return { code: 200, data: null }; }));
    await page._themeGameplayCommand({ operation: 'QUEST_RESOLVE', contentId: 'q' });
    assert.equal(calls.filter(c => c.method === 'POST').length, 1);
    if (first.msg) assert.ok(page.data.themeBagError.includes(first.msg));
    const read = calls.find(c => c.url === '/api/game/session/receipt'); assert.ok(read);
    assert.equal(read.data.requestId, calls.find(c => c.method === 'POST').data.requestId);
    await page.retryThemeBagAction(); await page._themeGameplayCommand({ operation: 'QUEST_RESOLVE', contentId: 'q' }); assert.equal(calls.filter(c => c.method === 'POST').length, 1);
  }
});
test('共享未知结算读到匹配 APPLIED 才解锁，个人没有 receipt GET 只读状态而不重写', async () => {
  const { page } = loadPage(); const calls = [];
  page.data.themeBag = backpack.present(passed, []); page._loadPlayerGameModule = () => Promise.resolve(true);
  let command;
  Object.assign(page, backpack.methods(async (url, method, data) => {
    calls.push({ url, method, data });
    if (method === 'POST') { command = data; return { code: 'fail', netFail: true }; }
    return { code: 200, data: { activityId: command.activityId, requestId: command.requestId, action: command.action, receiptId: 5, revision: 13, outcome: 'APPLIED', result: { questKey: 'q' } } };
  }));
  assert.equal(await page._themeGameplayCommand({ operation: 'QUEST_RESOLVE', contentId: 'q' }), true);
  assert.equal(page._gameplayPending, null); assert.equal(page.data.themeBagUnknown, false); assert.equal(calls.filter(c => c.method === 'POST').length, 1);
  const personal = loadPage().page; const writes = [];
  personal.data.topicId = 9; personal._sessionParams = () => ({ topicId: 9 });
  personal.data.themeBag = backpack.present({ ...passed, scope: 'PLAYER', version: 12, activeQuest: 'q', allowedActions: [] }, []);
  Object.assign(personal, backpack.methods(async (url, method, data) => { writes.push({ url, method, data }); return method === 'POST' ? { code: 'fail', netFail: true } : { code: 200, data: { ...personal.data.themeBag, version: 13 } }; }));
  await personal._themeGameplayCommand({ operation: 'QUEST_RESOLVE', contentId: 'q' }); await personal.retryThemeBagAction();
  assert.equal(writes.filter(c => c.method === 'POST').length, 1); assert.ok(writes.some(c => c.url === '/api/play/theme/state')); assert.equal(writes.some(c => c.url === '/api/game/session/receipt'), false);
});
test('通用到达请求响应丢失立刻权威读回，不留可重写的网络重试', async () => {
  const { page, app, calls } = loadPage(); page.data.themeBag = backpack.present(pending, []); page._withThoughtSync = job => Promise.resolve().then(job);
  page._sessionParams = () => ({ activityId: 5 });
  app.sendRequest = options => { calls.push(options); if (options.url === '/api/play/action-context') options.success(ORIGINAL_CONTEXT); else options.fail(); };
  await page.arrive(41, 'arrive'); await new Promise(resolve => setImmediate(resolve));
  assert.ok(page.reads || page.bagReads); assert.equal(page._arrivalWriteUnknown, true);
  await page.arrive(41, 'arrive'); assert.equal(calls.filter(c => c.method === 'POST').length, 1);
});

test('待审核禁结算但仍可打开原拍照校验，trustedPass 通过后不再提供重做入口', async () => {
  const { page } = loadPage(); const row = gameplay.actionRows(pending, [])[0];
  assert.equal(row.openable, true); assert.equal(gameplay.actionRows(passed, [])[0].openable, false);
  page.data.nodes = [{ nodeId: 41, vm: 2, hasAdvanced: false }]; page.data.themeBag = backpack.present(pending, []);
  let opened; page.startGame = node => { opened = node; };
  await page.onThemeGameplayAction({ currentTarget: { dataset: { index: 0, open: true } } });
  assert.equal(opened.nodeId, 41); assert.equal(page.data.advancedPlay.show, false);
});
test('结算回读拿到其他 action 的 receipt 不解锁或显示奖励', async () => {
  const { page } = loadPage(); page.data.themeBag = backpack.present(passed, []); page._loadPlayerGameModule = () => Promise.resolve(true);
  let command; let writes = 0;
  Object.assign(page, backpack.methods(async (url, method, data) => {
    if (method === 'POST') { writes++; command = data; return { code: 'fail', netFail: true }; }
    return { code: 200, data: { activityId: 5, requestId: command.requestId, action: 'ITEM_USE', receiptId: 6, revision: 13, outcome: 'APPLIED', result: { xp: 999 } } };
  }));
  assert.equal(await page._themeGameplayCommand({ operation: 'QUEST_RESOLVE', contentId: 'q' }), false);
  assert.equal(page.data.themeBagUnknown, true); assert.ok(page._gameplayPending); assert.equal(page.data.flyScore, false);
  await page.retryThemeBagAction(); assert.equal(writes, 1);
});

test('通用拍照未知只回读，保留照片与输入，不显示重新上传且不重写', async () => {
  const { page, app, calls } = loadPage(); page.data.themeBag = backpack.present(pending, []); page.data.game = { nodeId: 41, vm: 2 }; page.data.photoTaken = true;
  page._sessionParams = () => ({ activityId: 5 }); page._withThoughtSync = job => Promise.resolve().then(job);
  app.sendRequest = options => { calls.push(options); if (options.url === '/api/play/action-context') options.success(ORIGINAL_CONTEXT); else options.fail(); };
  await page.submitPhoto(41, 'uploaded/photo.jpg'); await new Promise(resolve => setImmediate(resolve));
  assert.ok(page.reads || page.bagReads); assert.equal(page._gameWriteUnknown, true); assert.equal(page.data.gNetRetryable, false); assert.equal(page.data.photoTaken, true);
  await page.submitPhoto(41, 'uploaded/photo.jpg'); assert.equal(calls.filter(c => c.method === 'POST').length, 1);
});
test('正式结算只显示本次服务端正数 XP，不回退配置 points', () => {
  const { page } = loadPage(); page.onComplete(41, { firstTime: true, xp: 7 });
  assert.equal(page.data.nodes[0].done, true); assert.equal(page.data.flyText, '+7 探索值');
  const formal = loadPage().page; formal.data.themeBag = backpack.present(passed, []);
  formal.onComplete(41, { nodeId: 41, firstTime: true, done: 1, total: 2, completed: false, xp: 7, routeState: { routeMode: 'LINEAR', nodeStates: { 41: 'COMPLETED' } } });
  assert.equal(formal.data.nodes[0].done, true); assert.equal(formal.data.flyText, '+7 探索值');
  const notDone = loadPage().page; notDone.data.themeBag = backpack.present(pending, []);
  notDone.onComplete(41, { nodeId: 41, firstTime: true, done: 1, xp: 7, routeState: { routeMode: 'LINEAR', nodeStates: { 41: 'PLAYABLE' } } });
  assert.equal(notDone.data.nodes[0].done, false); assert.equal(notDone.data.flyScore, false);
});
test('有高级配置仍用既有 startGame 高级分支，基础文字与选项保留原题面', async () => {
  for (const [vm, advanced] of [[1,false],[3,false],[4,false],[3,true]]) {
    const { page } = loadPage(); page.stopAudio = () => {}; page.data.nodes = [{ nodeId: 41, vm, hasAdvanced: advanced, question: '现场选择', options: { A: '第一项', B: '第二项' } }];
    page.data.themeBag = backpack.present({ sessionId: 7, version: 9, status: 'ACTIVE', activeQuest: 'q', quests: [{ questKey: 'q', nodeId: 41 }], allowedActions: [{ operation: 'QUEST_CANCEL', contentId: 'q' }] }, []);
    await page.onThemeGameplayAction({ currentTarget: { dataset: { index: 0 } } });
    if (advanced) assert.equal(page.data.advancedPlay.show, true);
    else { assert.equal(page.data.screen, 'gamePlay'); assert.equal(page.data.game.vm, vm); if (vm === 3) assert.deepEqual(page.data.game.opts.map(o => o.v), ['第一项', '第二项']); }
  }
});

test('通用线性路线完成只消费服务器 nodeStates，trustedPass 不会点亮节点', () => {
  const { page } = loadPage(); page.data.themeBag = backpack.present(passed, []);
  for (const state of ['PLAYABLE', 'COMPLETED', 'DISCOVERED_LOCKED']) {
    page._applyRouteState({ routeMode: 'LINEAR', status: 'ACTIVE', nodeStates: { 41: state } });
    assert.equal(page.data.nodes[0].done, state === 'COMPLETED'); assert.equal(page.data.nodes[0].playable, state === 'PLAYABLE');
  }
  assert.equal(page.data.flyScore, false);
});
test('通用凭证成功空回包不作完成；明确业务拒绝仍回读并保留输入', async () => {
  const { page, app } = loadPage(); page.data.themeBag = backpack.present(pending, []);
  await page.onComplete(41, undefined); assert.equal(page.data.nodes[0].done, false); assert.equal(page.data.flyScore, false);
  page._withThoughtSync = job => Promise.resolve().then(job); page._sessionParams = () => ({ topicId: 9 });
  app.sendRequest = options => options.success({ code: 400, msg: '凭证未通过' });
  page.data.game = { nodeId: 41, vm: 1 }; page.data.answerInput = '保留答案';
  const reads = page.reads; await page.submitGame(); await new Promise(resolve => setImmediate(resolve));
  assert.ok(page.reads > reads); assert.equal(page.data.answerInput, '保留答案'); assert.equal(page.data.nodes[0].done, false);
});

test('相机取消或权限失败尚未发出到达请求，不锁定通用写域', () => {
  const { page } = loadPage(); page.data.themeBag = backpack.present(pending, []);
  page.recordArrivalNetworkFailure('请检查相机权限', { type: 'scan', nodeId: 41 });
  assert.notEqual(page._arrivalWriteUnknown, true); assert.equal(page.reads, undefined);
});
test('凭证状态回读失败不能用旧 trustedPass 解锁未知写入', async () => {
  const { page } = loadPage(); page.data.themeBag = backpack.present(passed, []); page._gameWriteUnknown = true;
  page.refreshRouteState = () => Promise.resolve(false); page.refreshThemeBackpack = () => Promise.reject(new Error('offline'));
  assert.equal(await page._readGenericProof(41, '', 'game'), false); assert.equal(page._gameWriteUnknown, true);
  page.data.themeBag = backpack.present({ ...pending, quests: [], gameplay: {}, activeOperation: { operationKind: 'FACILITY', operationKey: 'f', nodeId: 41, verified: true } }, []);
  page.refreshThemeBackpack = () => Promise.resolve(true);
  assert.equal(await page._readGenericProof(41, '', 'game'), true); assert.equal(page._gameWriteUnknown, false); assert.equal(page.data.nodes[0].done, false); assert.equal(page.data.flyScore, false);
  page._gameWriteUnknown = true; assert.equal(await page._readGenericProof(null, '', 'game'), false); assert.equal(page._gameWriteUnknown, true);
});

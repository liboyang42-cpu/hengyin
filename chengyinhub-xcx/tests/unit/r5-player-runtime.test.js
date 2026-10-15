const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');
const runtime = require('../../pages/play/utils/r5-player-runtime.js');

global.Behavior = value => value;
const sent = [];
let response = { code: 200, data: {} };
const app = { getUserID: () => 99, globalData: { features: {} }, isDevEnv: () => false,
  sendRequest(options) { sent.push(options); options.success(response); } };
global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({ statusBarHeight: 20 }) };
global.getApp = () => app;
let pageSpec;
global.Page = value => { pageSpec = value; };
require('../../pages/play/index.js');

function setData(patch, cb) {
  this.patches.push(patch);
  for (const [key, value] of Object.entries(patch)) {
    const keys = key.split('.'); let target = this.data;
    for (const part of keys.slice(0, -1)) target = target[part] || (target[part] = {});
    target[keys.at(-1)] = value;
  }
  if (cb) cb();
}
function component(name, props = {}) {
  let spec;
  const filename = require.resolve('../../pages/play/components/' + name + '/index.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    Component: value => { spec = value; }, getApp: () => app, wx: global.wx,
    require: p => p.includes('play-action-key') || p.includes('playkit-view') ? require(path.resolve(path.dirname(filename), p)) : { haptic() {}, safeUserMessage: (_, fallback) => fallback },
    Date, setTimeout, clearTimeout, setInterval, clearInterval,
  });
  const data = { ...spec.data };
  for (const [key, property] of Object.entries(spec.properties)) data[key] = property.value;
  const events = [];
  const c = { ...spec.methods, data: { ...data, ...props }, patches: [], setData,
    triggerEvent(name, detail) { events.push({ name, detail }); }, selectComponent: () => null };
  return { c, spec, events };
}
function page(props = {}) {
  return { ...pageSpec, data: { ...JSON.parse(JSON.stringify(pageSpec.data)), topicId: 19, activityId: 9, ...props },
    patches: [], setData, refreshThemeBackpack: () => Promise.resolve(true) };
}
// AdvancedGameRuntimeServiceImpl.toView + PlayCheckReceipt, with current actor projection.
function checkDto(check = {}, ownerType = 1) {
  return { sessionId: 51, nodeId: 29, version: 0, ownerType, status: 'RUNNING', present: 'fullscreen',
    config: { schemaVersion: 1, outcomeRulesVersion: 1, check: { enabled: true, tier: 'medium' } },
    check: { attemptId: 'advanced:51:3', allowedActions: ['ROLL_CHECK'], disabledReason: '', rerollRemaining: 0, ...check } };
}
function rollDto(check = {}) {
  return checkDto({ dice: [12], kept: 12, total: 12, dc: 13, tier: 'medium', success: false,
    luck: 2, rerolled: false, settled: false, allowedActions: ['SUBMIT_CHECK', 'REROLL_CHECK'], rerollRemaining: 1, ...check });
}
function typeDto(r5, deadlineAt = null) {
  return { sessionId: 51, version: 2, deadlineAt, config: r5 ? { outcomeRulesVersion: 1 } : { schemaVersion: 1 },
    playKit: { typeIn: { title: '输入', target: 'hi', seconds: 3, tries: 0, attempts: 0, started: true, passed: false } } };
}

test('Page R5 check uses Advanced actions; legacy keeps existing endpoint', async () => {
  const p = page({ advancedPlay: { show: true, node: { nodeId: 29 } } });
  const actions = [];
  p.selectComponent = () => ({ data: { state: { sessionId: 51 } }, action: (name, payload) => { actions.push({ name, payload }); return true; } });
  p.onAdvancedSession({ detail: checkDto() });
  assert.equal(p.data.journeyCheck.tier, 'medium');
  await p.onJourneyCheckAction({ detail: { action: 'roll' } });
  p.onAdvancedSession({ detail: rollDto() });
  await p.onJourneyCheckAction({ detail: { action: 'reroll' } });
  await p.onJourneyCheckAction({ detail: { action: 'settle' } });
  assert.deepEqual(actions.map(row => row.name), ['ROLL_CHECK', 'REROLL_CHECK', 'SUBMIT_CHECK']);
  assert.deepEqual(actions[0].payload, {}, '客户端不提供骰点或成败');
  p.onAdvancedSession({ detail: { ...typeDto(true), nodeId: 29 } });
  assert.equal(p.data.journeyCheck.show, false, '切换R5玩法清理旧检定界面');
  sent.length = 0;
  p.data.journeyCheck = { show: true, checkId: 'legacy', nodeId: 29 };
  p.onJourneyCheckAction({ detail: { action: 'roll' } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(sent[0].url, '/api/play/check/roll');
});

test('Frozen R5 encounter opens Advanced session rather than legacy check, and check close returns to pending task', async () => {
  const p = page();
  response = { code: 200, data: { nodeId: 29, config: { outcomeRulesVersion: 1 }, allowedActions: ['check'], check: { checkId: 'c' } } };
  p._probeJourneyCheck({ nodeId: 29 });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.data.advancedPlay.show, true);
  assert.equal(p.data.journeyCheck.show, false);
  p.data.journeyCheck = runtime.checkView(rollDto({ submitted: true, allowedActions: [] }), {});
  p.closeJourneyCheck();
  assert.equal(p.data.advancedPlay.show, false);
  assert.equal(p.data.themeBackpackOpen, true);
  response = { code: 200, data: {} };
});

test('R5 real component buttons obey server permissions and exhaustion', () => {
  const view = runtime.checkView(rollDto({ allowedActions: ['SUBMIT_CHECK'], rerollRemaining: 0, rerolled: true }), {});
  const { c, spec, events } = component('playkit-journey-check', { ...view });
  spec.observers['show, tier, receipt'].call(c, true, view.tier, view.receipt);
  spec.observers['r5, allowedActions'].call(c, true, ['SUBMIT_CHECK']);
  c.onReroll();
  assert.equal(events.length, 0);
  assert.equal(c.data.rerollVisible, false);
  c.onSettle();
  assert.equal(events[0].detail.action, 'settle');
  c.data.receipt.pending = true;
  c.onSettle();
  assert.equal(events.length, 1);
});

test('Advanced refusal never replaces receipt with client success', async () => {
  const { c, events } = component('advanced-game', { state: checkDto(), node: { nodeId: 29 } });
  c.request = async () => ({ code: 409, msg: '幸运值不足' });
  await c.action('REROLL_CHECK', {});
  assert.equal(c.data.state.check.dice, undefined);
  assert.equal(c.data.acting, false);
  assert.ok(events.some(event => event.name === 'actionfail'));
});

test('TEAM network unknown replays identical actionId and receipt through real Advanced handler', async () => {
  const { c } = component('advanced-game', { state: checkDto({}, 2), node: { nodeId: 29 } });
  const requests = []; const receipt = rollDto().check;
  c.request = async (url, method, command) => {
    requests.push({ url, command: JSON.parse(JSON.stringify(command)) });
    if (requests.length === 1) throw new Error('response lost');
    return { code: 200, data: { ...checkDto(receipt, 2), version: 1 } };
  };
  c.syncTimer = () => {};
  await c.action('ROLL_CHECK', {});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.length, 2);
  assert.deepEqual(requests[0], requests[1]);
  assert.ok(requests[0].command.idempotencyKey);
  assert.deepEqual(c.data.state.check.dice, [12]);
  assert.equal(c.data.unknown, false);
});

test('R5 and legacy typeIn route timer props from server DTO, never old seconds for R5', () => {
  const unlimited = pickPlayKit(typeDto(true));
  assert.equal(unlimited.r5, true); assert.equal(unlimited.deadlineAt, 0);
  const deadline = Date.now() + 20000;
  const limited = pickPlayKit(typeDto(true, deadline));
  assert.equal(limited.deadlineAt, deadline);
  assert.equal(pickPlayKit(typeDto(false, deadline)).r5, false);
  assert.equal(pickPlayKit(typeDto(false)).seconds, 3);
});

test('R5 timeout never auto-submits; legacy timeout still submits input', () => {
  for (const r5 of [true, false]) {
    const { c, events } = component('playkit-typein', { r5, phase: 'run', typed: 'h', canSubmit: true });
    c.onVerdict({ detail: { timedOut: true } });
    assert.equal(events.filter(row => row.name === 'submit').length, r5 ? 0 : 1);
    c.onDeadline();
    assert.equal(events.filter(row => row.name === 'refresh').length, r5 ? 1 : 0);
  }
});

test('R5 typeIn reentry keeps server deadline, started input never resends START_CHALLENGE', () => {
  const deadline = Date.now() + 10000;
  const { c, spec, events } = component('play-stage', { show: true, deadlineAt: deadline, limitSeconds: 0 });
  spec.observers['show, deadlineAt'].call(c, true, deadline);
  assert.ok(c.data.limitLeft <= 10);
  spec.pageLifetimes.hide.call(c);
  spec.pageLifetimes.show.call(c);
  assert.equal(c.data.deadlineAt, deadline);
  assert.ok(c.data.limitLeft <= 10);
  c.data.deadlineAt = Date.now() - 1;
  spec.observers['show, deadlineAt'].call(c, true, c.data.deadlineAt);
  assert.equal(c.data.verdict, '');
  assert.equal(events.at(-1).name, 'deadline');
  const input = component('playkit-typein', { r5: true, startConfirmed: true });
  input.c.onStart();
  assert.equal(input.c.data.phase, 'run');
  assert.equal(input.events.length, 0);
  const p = page({ playKit: { kit: { r5: true, type: 'typein' } } });
  let reads = 0; p.selectComponent = () => ({ refreshState() { reads++; } });
  p.onPlayKitAction({ detail: { type: 'typein', action: 'refresh' } });
  assert.equal(reads, 1);
});

test('PASS/FAIL and option-local replies only come from SETTLED receipts, one stable slot across polling/reentry', () => {
  for (const outcome of ['PASS', 'FAIL']) {
    const receipt = { attemptId: 'advanced:51:3', outcome, settled: true, status: 'SETTLED', reply: '选项对应的回复' };
    const p = page();
    p._applyR5OutcomeView({ sessionId: 99, lastOutcome: receipt });
    const count = p.patches.length;
    p._applyR5OutcomeView({ sessionId: 99, lastOutcome: { ...receipt } });
    assert.equal(p.patches.length, count);
    assert.equal(p.data.r5OutcomeReply.reply, receipt.reply);
    assert.equal(p.data.r5OutcomeReply.outcome, outcome);
    const reentry = page(); reentry._applyR5OutcomeView({ sessionId: 99, lastOutcome: receipt });
    assert.deepEqual(reentry.data.r5OutcomeReply, p.data.r5OutcomeReply);
  }
});

test('PENDING/BLOCKED_COST and wrong-attempt settled receipts cannot settle current check', () => {
  const view = rollDto({ submitted: true, allowedActions: [] });
  for (const status of ['PENDING', 'BLOCKED_COST']) {
    const outcome = { attemptId: 'advanced:51:3', outcome: 'PASS', settled: false, status, reply: '未结算不显示' };
    assert.equal(runtime.outcomeReply({ lastOutcome: outcome }).reply, '');
    assert.equal(runtime.checkView(view, { lastOutcome: outcome }).receipt.pending, true);
  }
  const receipt = { attemptId: 'advanced:51:2', outcome: 'PASS', settled: true, status: 'SETTLED', reply: '上一局' };
  assert.equal(runtime.checkView(view, { lastOutcome: receipt }).receipt.settled, false);
  receipt.attemptId = 'advanced:51:3';
  assert.equal(runtime.checkView(view, { lastOutcome: receipt }).receipt.text, '上一局');
  const untrusted = runtime.checkView(rollDto({ settled: true, text: '骰点不是结算回复' }), {});
  assert.equal(untrusted.receipt.settled, false);
  assert.equal(untrusted.receipt.text, '');
  receipt.outcome = 'FAIL';
  assert.equal(runtime.checkView(rollDto({ success: true }), { lastOutcome: receipt }).receipt.success, false, '结算后的成败只认最终回执');
  for (const active of [
    { activeQuest: 'quest:29', quests: [{ questKey: 'quest:29', trustedPass: true }] },
    { activeOperation: { operationKey: 'tool:29', verified: true } },
  ]) {
    const pending = runtime.outcomeReply({ sessionId: 99, ...active, lastOutcome: receipt });
    assert.equal(pending.settled, false); assert.equal(pending.reply, ''); assert.equal(pending.statusText, '待确认结算');
  }
});

test('R5 expired Advanced readback does not keep polling or recursively refreshing a fixed deadline', () => {
  const { c } = component('advanced-game', { state: { ...typeDto(true, Date.now() - 1), status: 'EXPIRED' } });
  let reads = 0; c.refreshState = () => { reads++; };
  c.syncTimer();
  assert.equal(reads, 0); assert.equal(c._timer, undefined);
});

test('TEAM resultCard fixture stays on existing card path, no reply fabricated from a terminal card', () => {
  const fixture = require('../../../docs/delivery/gg-r5-zeroview-fixtures/team-hp-zero-result-card.json');
  assert.ok(fixture.resultCard);
  assert.equal(runtime.outcomeReply(fixture), null);
  const p = page(); p._applyThemeTeamView = pageSpec._applyThemeTeamView;
  p._applyThemeView = view => { assert.deepEqual(view.resultCard, fixture.resultCard); };
  p._applyThemeTeamView(fixture, { sessionId: 50, activityId: 70, revision: 1, status: 'RUNNING', availableActions: [] });
});


test('零血失败与未完成恢复次数的检定不遮住明确挑战入口，也不自动start', () => {
  for (const status of ['FAILED', 'EXPIRED', 'COMPLETED']) {
    const p = page({ advancedPlay: { show: true, node: { nodeId: 29 } }, journeyCheck: { show: true, r5: true } });
    p._themeChallengeNodeId = 29;
    let starts = 0;
    p.selectComponent = () => ({ start() { starts++; } });
    p.onAdvancedSession({ detail: { sessionId: 42, nodeId: 29, version: 3, status, ownerType: 2, canRetry: true,
      challenge: { instanceId: 'old', retry: true }, config: { outcomeRulesVersion: 1, check: { enabled: true } },
      playKit: { reaction: {} } } });
    assert.equal(starts, 0);
    assert.equal(p.data.journeyCheck.show, false);
    assert.equal(p.data.playKit.kit.terminal, true);
    assert.equal(p.data.playKit.kit.canRetry, true);
    assert.equal(p.data.playKit.readyForBase, false);
  }
});

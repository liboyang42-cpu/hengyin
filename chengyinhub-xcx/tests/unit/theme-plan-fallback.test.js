const test = require('node:test');
const assert = require('node:assert/strict');
const fallback = require('../../pages/club/topic-detail/plan-fallback.js');
const { DIRECTOR_METHODS } = require('../../pages/club/topic-detail/director.js');
const event = value => ({ detail: { value } });
const control = { revision: 12, availableActions: ['PLAN_FALLBACK'], world: { stage: 'TIED', round: 2, fallbackAvailable: true, fallbackDisabledReason: null } };
function host(read) {
  const calls = [];
  const page = { _activityId: 5, data: { ...fallback.data, loadState: 'ready', revision: 12, authorityEpoch: 4, writeLocked: false },
    setData(patch) { Object.assign(this.data, patch); }, executeAction(action, payload) { calls.push({ action, payload }); } };
  Object.assign(page, fallback.methods(read));
  return { page, calls };
}

test('W2 shared/control WORLD_SHARED 回读可用且可写；旧服无字段不显示入口', () => {
  assert.equal(fallback.present(control, 12).planFallbackAvailable, true);
  assert.equal(fallback.present({}, 12).planFallbackKnown, false);
  assert.equal(fallback.present({ ...control, availableActions: [] }, 12).planFallbackAvailable, false);
  assert.match(fallback.present(control, 11).planFallbackReason, /重新加载/);
  for (const reason of ['FALLBACK_NOT_CONFIGURED', 'NO_EFFECTIVE_TEAMS', 'PLAN_AVAILABLE', 'BALLOT_NOT_FINISHED', 'BALLOT_CHANGED', 'SESSION_ENDED', 'SESSION_PAUSED', 'RULE_BLOCKED', 'NOT_STAFF']) {
    const view = fallback.present({ ...control, world: { fallbackAvailable: false, fallbackDisabledReason: reason } }, 12);
    assert.equal(view.planFallbackAvailable, false); assert.ok(view.planFallbackReason && view.planFallbackReason !== reason);
  }
});
test('原因必填 1–500 字；必须二次确认后才走原导演命令', () => {
  assert.throws(() => fallback.reasonPayload(' '), /1–500/);
  assert.throws(() => fallback.reasonPayload('长'.repeat(501)), /1–500/);
  const { page, calls } = host(); Object.assign(page.data, fallback.present(control, 12));
  let confirm; global.wx = { showModal(options) { confirm = options.success; } };
  page.openPlanFallback(); assert.equal(page.data.planFallbackSheet, true);
  assert.equal(page.confirmPlanFallback(), false); assert.match(page.data.planFallbackError, /原因/);
  page.inputPlanFallbackReason(event('  所有队伍已明确拒绝剩余方案  '));
  assert.equal(page.confirmPlanFallback(), true); assert.equal(calls.length, 0);
  confirm({ confirm: false }); assert.equal(calls.length, 0);
  page.confirmPlanFallback(); confirm({ confirm: true });
  assert.deepEqual(calls, [{ action: 'PLAN_FALLBACK', payload: { reason: '所有队伍已明确拒绝剩余方案' } }]);
});
test('确认期间纪元变化不写，锁定与不可用状态不允许打开', () => {
  const { page, calls } = host(); Object.assign(page.data, fallback.present(control, 12));
  let confirm; global.wx = { showModal(options) { confirm = options.success; } };
  page.inputPlanFallbackReason(event('本轮没有可执行方案')); page.confirmPlanFallback(); page.data.authorityEpoch = 5; confirm({ confirm: true });
  assert.equal(calls.length, 0); assert.match(page.data.planFallbackError, /已变化/);
  page.data.writeLocked = true; page.openPlanFallback(); assert.equal(page.data.planFallbackSheet, false);
});
test('读取真实 shared/control，切活动或 revision 的迟到响应不污染当前页', async () => {
  const pending = []; const { page } = host(options => pending.push(options));
  const first = page.loadPlanFallback(); assert.equal(pending[0].url, '/api/game/session/shared/control');
  assert.deepEqual(pending[0].data, { activityId: 5 });
  page.data.revision = 13; pending[0].success({ code: 200, data: control }); await first; assert.equal(page.data.planFallbackKnown, false);
  page.data.revision = 12; const second = page.loadPlanFallback(); pending[1].success({ code: 200, data: control }); await second;
  assert.equal(page.data.planFallbackAvailable, true);
});

test('默认只读适配固定 shared/control 路径并保留回调与 app 接收者', async () => {
  const previous = global.getApp;
  const pending = [];
  const app = { sendRequest(options) { assert.equal(this, app); pending.push(options); } };
  global.getApp = () => app;
  try {
    const { page } = host(); const read = page.loadPlanFallback();
    assert.equal(pending[0].url, '/api/game/session/shared/control');
    assert.equal(pending[0].method, 'GET');
    assert.deepEqual(pending[0].data, { activityId: 5 });
    pending[0].success({ code: 200, data: control }); await read;
    assert.equal(page.data.planFallbackAvailable, true);
    const gate = require('../lint/ui-integrity.js');
    assert.deepEqual(gate.scanU1([require.resolve('../../pages/club/topic-detail/plan-fallback.js')],
      new Set(['/api/game/session/shared/control'])).violations, []);
  } finally { global.getApp = previous; }
});
test('兜底未知结果只能核对原回执，不重发 PLAN_FALLBACK', () => {
  let checks = 0;
  const page = { ...DIRECTOR_METHODS, _activityId: 5, _pendingCommand: { action: 'PLAN_FALLBACK' }, onReconcileUnknownWrite() { checks++; return 'read'; } };
  global.getApp = () => ({ getUserID: () => '7', getAuthorization: () => 'unit-session' });
  global.getCurrentPages = () => [page];
  assert.equal(DIRECTOR_METHODS.retryUnknownWrite.call(page), 'read'); assert.equal(checks, 1);
});

test('兜底回执只接受匹配的 APPLIED fallback 结局，收尾原因保留', () => {
  const { page } = host(); page.data.planFallbackSheet = true; page.data.planFallbackDraft = '本轮无可执行方案';
  const raw = { receipt: { action: 'PLAN_FALLBACK', outcome: 'APPLIED', result: { result: { endingKey: 'stop', title: '停在这里', fallback: true, terminal: true, reason: '全部队长拒绝' } } } };
  page.applyPlanFallbackReceipt({ raw: { receipt: { ...raw.receipt, outcome: 'FAILED' } } }); assert.equal(page.data.planFallbackResult, null);
  page.applyPlanFallbackReceipt({ raw }); assert.equal(page.data.planFallbackSheet, false); assert.deepEqual(page.data.planFallbackResult, { endingKey: 'stop', title: '停在这里', reason: '全部队长拒绝' });
  let reads = 0;
  const restored = { ...DIRECTOR_METHODS, _activityId: 5, _pendingReceiptIndex: { action: 'PLAN_FALLBACK' }, onReconcileUnknownWrite() { reads++; } };
  global.getApp = () => ({ getUserID: () => '7', getAuthorization: () => 'unit-session' });
  global.getCurrentPages = () => [restored];
  DIRECTOR_METHODS.retryUnknownWrite.call(restored); assert.equal(reads, 1);
});

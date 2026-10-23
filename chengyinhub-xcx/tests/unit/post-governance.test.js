const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const DIR = 'pages/shezhi/components/post-governance';
const { enforcementRows } = require('../../' + DIR + '/view-model.js');

// PRIVATE-GROUP-post-governance-F0521:设置页本人帖子处置 + 纯文字申诉。
test('UPHELD 显示维持处置,REVERSED 显示已撤销处置,ACTIVE/申诉中不写成最终结论', () => {
  const rows = enforcementRows([
    { id: 4, post_id: 9, enforcement_type: 'REMOVE', status: 'ACTIVE', appeal_id: null, create_time: '2026-10-20 10:00:00' },
    { id: 3, post_id: 9, enforcement_type: 'LABEL', status: 'ACTIVE', appeal_id: 31, appeal_status: 'UPHELD', decision_reason: '证据充分' },
    { id: 2, post_id: null, enforcement_type: 'COMMENT_TIMEOUT', status: 'REVERSED', appeal_id: 21, appeal_status: 'REVERSED' },
    { id: 1, post_id: 8, enforcement_type: 'DOWNRANK', status: 'ACTIVE', appeal_id: 11, appeal_status: 'REVIEWING' },
  ]);
  assert.deepEqual(rows.map((r) => [r.id, r.stateLabel, r.canAppeal]), [
    [4, '处置生效中', true], [3, '维持处置', false], [2, '已撤销处置', false], [1, '申诉处理中', false],
  ]);
  assert.match(rows[0].stateNote, /不是最终结论/);
  assert.match(rows[3].stateNote, /不是最终结论/);
  assert.equal(rows[1].decisionReason, '证据充分');
  assert.equal(rows[0].decisionReason, '');
  assert.equal(rows[0].typeText, '移除内容');
  assert.equal(rows[2].targetText, '账号处置');
  assert.equal(rows[0].timeText, '2026-10-20 10:00');
});

test('同一处置多条申诉取最新一条;坏响应不伪造列表', () => {
  const rows = enforcementRows([
    { id: 5, status: 'ACTIVE', appeal_id: 50, appeal_status: 'UPHELD' },
    { id: 5, status: 'ACTIVE', appeal_id: 51, appeal_status: 'SUBMITTED' },
    { id: 5, status: 'ACTIVE', appeal_id: null },
  ]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].stateLabel, '申诉处理中');
  assert.equal(enforcementRows(null), null);
  assert.equal(enforcementRows([{ id: 'x' }]), null);
  assert.equal(enforcementRows([null]), null);
  assert.deepEqual(enforcementRows([]), []);
  assert.equal(enforcementRows([{ id: 6, status: 'WHATEVER' }])[0].stateLabel, '状态待确认');
});

function mount({ uid = '7', storage = {}, responses }) {
  let def;
  const calls = [];
  const prev = { getApp: global.getApp, Component: global.Component, wx: global.wx };
  const app = {
    getUserID: () => uid,
    sendRequest(opts) { calls.push(opts); responses.shift()(opts); },
  };
  global.getApp = () => app;
  global.Component = (c) => { def = c; };
  global.wx = {
    getStorageSync: (k) => storage[k],
    setStorageSync: (k, v) => { storage[k] = JSON.parse(JSON.stringify(v)); },
    removeStorageSync: (k) => { delete storage[k]; },
    showToast() {},
  };
  try {
    for (const mod of ['../../' + DIR + '/index.js', '../../utils/toast.js']) delete require.cache[require.resolve(mod)];
    require('../../' + DIR + '/index.js');
  } finally { global.getApp = prev.getApp; global.Component = prev.Component; }
  const vm = Object.assign({ data: JSON.parse(JSON.stringify(def.data)) }, def.methods);
  vm.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this); };
  return { vm, calls, storage, app, restore: () => { global.wx = prev.wx; } };
}

const active = { code: 200, data: { items: [{ id: 4, post_id: 9, enforcement_type: 'REMOVE', status: 'ACTIVE' }], nextCursor: 4 } };
const appealed = { code: 200, data: { items: [{ id: 4, post_id: 9, enforcement_type: 'REMOVE', status: 'ACTIVE', appeal_id: 40, appeal_status: 'SUBMITTED' }], nextCursor: 4 } };

test('申诉提交纯文字:enforcementId + reason + 持久化 requestId,不带证据附件', async () => {
  const t = mount({ responses: [(o) => o.success(active), (o) => o.success({ code: 200 }), (o) => o.success(appealed)] });
  try {
    await t.vm.load();
    assert.equal(t.calls[0].url, '/api/v1/community/me/enforcements');
    assert.equal(t.calls[0].method, 'GET');
    t.vm.startAppeal({ currentTarget: { dataset: { id: 4 } } });
    t.vm.onReasonInput({ detail: { value: '  误判  ' } });
    t.vm.submitAppeal();
    const body = JSON.parse(t.calls[1].data);
    assert.equal(t.calls[1].url, '/api/v1/community/appeals');
    assert.deepEqual(Object.keys(body).sort(), ['enforcementId', 'reason', 'requestId']);
    assert.equal(body.reason, '误判');
    assert.ok(body.requestId.length >= 8 && body.requestId.length <= 96);
    assert.equal(t.vm.data.editingId, null);
    assert.deepEqual(Object.keys(t.storage).filter((k) => !k.endsWith(':previous')), []);
  } finally { t.restore(); }
});

test('断网/响应不明:先回读处置列表,读到申诉才算已提交;读不到保留同一 requestId 供重试', async () => {
  const t = mount({ responses: [
    (o) => o.success(active),
    (o) => o.fail({ errMsg: 'request:fail' }),
    (o) => o.success(active),
    (o) => o.successStatusAbnormal({}, 502),
    (o) => o.success(appealed),
  ] });
  try {
    await t.vm.load();
    t.vm.startAppeal({ currentTarget: { dataset: { id: 4 } } });
    t.vm.onReasonInput({ detail: { value: '误判' } });
    t.vm.submitAppeal();
    await new Promise((r) => setImmediate(r));
    assert.equal(t.calls[2].method, 'GET', '结果不明后必须刷新');
    assert.equal(t.vm.data.submitting, false);
    assert.equal(t.vm.data.editingId, 4, '没读到申诉时留在表单内允许重试');
    assert.match(t.vm.data.notice, /不会重复提交/);
    assert.doesNotMatch(t.vm.data.notice, /未提交/);
    assert.equal(t.vm.data.rows[0].pendingUnknown, true);
    const firstKey = JSON.parse(t.calls[1].data).requestId;

    t.vm.submitAppeal();
    assert.equal(JSON.parse(t.calls[3].data).requestId, firstKey, '同一内容重试复用 requestId');
    await new Promise((r) => setImmediate(r));
    assert.equal(t.vm.data.editingId, null, '刷新到申诉后关闭表单');
    assert.equal(t.vm.data.rows[0].stateLabel, '申诉处理中');
    assert.equal(t.vm.data.rows[0].canAppeal, false);
  } finally { t.restore(); }
});

test('明确的业务拒绝如实提示,不进入未知态', async () => {
  const t = mount({ responses: [(o) => o.success(active), (o) => o.success({ code: 403, msg: '只能申诉自己的处置' })] });
  t.app.getRequestErrorMessage = (res, fb) => (res && res.msg) || fb;
  try {
    await t.vm.load();
    t.vm.startAppeal({ currentTarget: { dataset: { id: 4 } } });
    t.vm.onReasonInput({ detail: { value: '误判' } });
    t.vm.submitAppeal();
    assert.equal(t.calls.length, 2);
    assert.equal(t.vm.data.notice, '只能申诉自己的处置');
  } finally { t.restore(); }
});

test('换号后的旧回包不写进当前页面;未登录不发请求', async () => {
  const t = mount({ uid: '0', responses: [] });
  try {
    await t.vm.load();
    assert.equal(t.calls.length, 0);
    assert.equal(t.vm.data.state, 'login');
  } finally { t.restore(); }
  let uid = '7';
  const t2 = mount({ responses: [(o) => { uid = '8'; o.success(active); }] });
  t2.app.getUserID = () => uid;
  try {
    await t2.vm.load();
    assert.deepEqual(t2.vm.data.rows, []);
  } finally { t2.restore(); }
});

test('设置页有入口并挂载组件;文案不把 ACTIVE 说成最终结论', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/shezhi/shezhi.wxml'), 'utf8');
  assert.match(wxml, /id="settings-post-governance"[\s\S]*?bind:tap="openPostGovernance"/);
  assert.match(wxml, /<cy-post-governance show="\{\{showPostGovernance\}\}" \/>/);
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/shezhi/shezhi.json'), 'utf8'));
  assert.equal(json.usingComponents['cy-post-governance'], '/' + DIR + '/index');
  const comp = fs.readFileSync(path.join(ROOT, DIR, 'index.wxml'), 'utf8');
  assert.doesNotMatch(comp, /最终处置|已定案|永久有效/);
  assert.doesNotMatch(fs.readFileSync(path.join(ROOT, DIR, 'index.js'), 'utf8'), /evidenceAssetIds/);
});

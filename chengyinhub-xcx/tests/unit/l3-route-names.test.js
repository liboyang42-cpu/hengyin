// 文档二 L3：下一站名单读注册表、添加层「新建「…」」走 L8 契约 §3.1，键名只在底下流转。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

function load(responses) {
  const calls = [];
  global.getApp = () => ({ sendRequest(opts) { calls.push(opts); const r = responses.shift(); if (r === 'fail') opts.fail(); else opts.success(r); } });
  const toastPath = require.resolve('../../utils/toast.js');
  const toasts = [];
  require.cache[toastPath] = { id: toastPath, filename: toastPath, loaded: true, exports: (msg) => toasts.push(msg) };
  const file = path.join(__dirname, '../../pages/publish/fabu/theme-route-names.js');
  delete require.cache[require.resolve(file)];
  const { methods } = require(file);
  const created = [];
  const page = Object.assign({}, methods, {
    data: { editingTopicId: '42', operationScope: '', routeMappingShow: true,
      routeRuleNames: { clue: [], tag: [], item: [], trait: [], attr: [], clock: [], value: [], node: [] },
      routeMappingRows: [{ formIdentity: 'a:1:edge:x' }] },
    _routeMappingSourceNodeId: '7',
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb(); },
    selectComponent(id) { return id === '#route-rules-0' ? { created: key => created.push(key) } : null; },
  });
  return { page, calls, toasts, created };
}

test('打开下一站读注册表补名字，数值补进可用变量', () => {
  const { page, calls } = load([{ code: 200, data: [{ stateKey: 'clue.k1', label: '半张货单' }, { stateKey: 'counter.v1', label: '线人好感', initialValue: 1 }] }]);
  page._loadRouteRuleRegistry({ 'sys.luck': 3 });
  assert.equal(calls[0].url, '/api/topic/state-keys');
  assert.deepEqual(calls[0].data, { topicId: '42', scope: '' });
  assert.deepEqual(page.data.routeRuleNames.clue, [{ key: 'clue.k1', label: '半张货单' }]);
  assert.equal(page.data.routeRuleVariables['counter.v1'], 1);
  assert.equal(page.data.routeRuleVariables['sys.luck'], 3);
});

test('换了节点或关掉以后，迟到的注册表回包不写', () => {
  const { page } = load([]);
  let pending; global.getApp = () => ({ sendRequest(opts) { pending = opts; } });
  delete require.cache[require.resolve('../../pages/publish/fabu/theme-route-names.js')];
  Object.assign(page, require('../../pages/publish/fabu/theme-route-names.js').methods);
  page._loadRouteRuleRegistry({});
  page._routeMappingSourceNodeId = '8';
  pending.success({ code: 200, data: [{ stateKey: 'clue.k1', label: '半张货单' }] });
  assert.deepEqual(page.data.routeRuleNames.clue, []);
});

test('新建线索：POST state-keys/named，成功把服务端的键交回组件；重名原话照显', () => {
  const ok = load([{ code: 200, data: { key: 'clue.k3', kind: 'clue', label: '暗号' } }]);
  ok.page.onRouteRuleCreate({ detail: { identity: 'a:1:edge:x', kind: 'clue', label: '暗号' } });
  assert.equal(ok.calls[0].url, '/api/topic/state-keys/named');
  assert.equal(ok.calls[0].method, 'POST');
  assert.deepEqual(JSON.parse(ok.calls[0].data), { topicId: 42, scope: '', kind: 'clue', label: '暗号' });
  assert.deepEqual(ok.created, ['clue.k3']);
  assert.deepEqual(ok.page.data.routeRuleNames.clue, [{ key: 'clue.k3', label: '暗号' }]);

  const dup = load([{ code: 400, msg: '已有同名的线索。', errorCode: 'STATE_KEY_INVALID' }]);
  dup.page.onRouteRuleCreate({ detail: { identity: 'a:1:edge:x', kind: 'clue', label: '暗号' } });
  assert.deepEqual(dup.toasts, ['已有同名的线索。']);
  assert.deepEqual(dup.created, ['']);

  const net = load(['fail']);
  net.page.onRouteRuleCreate({ detail: { identity: 'a:1:edge:x', kind: 'tag', label: '夜行' } });
  assert.deepEqual(net.toasts, ['没有保存成功，请再试一次。']);
  assert.deepEqual(net.created, ['']);
});

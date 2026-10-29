const test = require('node:test')
const assert = require('node:assert/strict')
const { createRouteActionStore } = require('../../pages/play/utils/play-route-state')

function storage() {
  const data = new Map()
  return { data, getStorageSync: key => data.get(key), setStorageSync: (key, value) => data.set(key, JSON.parse(JSON.stringify(value))) }
}

test('同一意图冻结原输入与期望版本，明确完成后相同答案产生新 attempt', () => {
  let seq = 0
  const store = createRouteActionStore(() => 'intent-' + ++seq)
  const original = store.payload('answer', 11, { version: 3 }, { answer: 'A', nodeId: 11 })
  const retry = store.payload('answer', 11, { version: 8 }, { answer: 'B', nodeId: 11 })
  assert.deepEqual(retry, original)
  retry.answer = 'mutated'
  assert.equal(store.payload('answer', 11, { version: 9 }, {}).answer, 'A')
  store.resolve('answer', 11)
  assert.notEqual(store.payload('answer', 11, { version: 8 }, { answer: 'A' }).routeActionId, original.routeActionId)
})

test('进程恢复只恢复脱敏回执身份，账号与局隔离，存储失败不得发送', () => {
  const disk = storage()
  const options = { storage: disk, scope: ['7', 'a:10', 'run:20'] }
  const a = createRouteActionStore(() => 'original', options)
  a.payload('answer', 11, { version: 3 }, { activityId: 10, nodeId: 11, answer: 'secret-answer', code: 'secret-code', latitude: 31 })
  assert.equal(JSON.stringify([...disk.data.values()]).includes('secret'), false)
  assert.equal(JSON.stringify([...disk.data.values()]).includes('latitude'), false)
  const restored = createRouteActionStore(() => 'replacement', options).payload('answer', 11, { version: 99 }, { answer: 'replacement' })
  assert.equal(restored.routeActionId, 'original')
  assert.equal(restored.expectedRouteVersion, 3)
  assert.equal(restored.receiptOnly, true)
  assert.equal(restored.answer, undefined)
  const b = createRouteActionStore(() => 'B', { storage: disk, scope: ['8', 'a:10', 'run:20'] })
  assert.equal(b.payload('answer', 11, { version: 3 }, {}).routeActionId, 'B')
  const otherRun = createRouteActionStore(() => 'other-run', { storage: disk, scope: ['7', 'a:10', 'run:21'] })
  assert.equal(otherRun.payload('answer', 11, { version: 3 }, {}).routeActionId, 'other-run')
  assert.throws(() => createRouteActionStore(() => 'lost', { scope: ['7', 'a:10', 'run:20'], storage: { getStorageSync() {}, setStorageSync() { throw Error('full') } } }).payload('answer', 11, {}, {}), /保存/)
})

test('显式重试不能把原 expectedRouteVersion 换成最新值', () => {
  let page
  global.getApp = () => ({ globalData: {} })
  global.Page = value => { page = value }
  global.wx = {}
  const file = require.resolve('../../pages/play/index')
  delete require.cache[file]
  require(file)
  const payload = { routeActionId: 'same', expectedRouteVersion: 3, answer: 'A' }
  assert.deepEqual(page._retriedRouteWriteBody.call({ _routeState: { version: 8 } }, payload, true), payload)
})

function host(subject, disk, requests, respond, restart) {
  let definition
  let owner = subject
  const app = { globalData: {}, _loginEpoch: 1, getUserID: () => owner,
    getAuthorization: () => 'session-' + owner + '-' + app._loginEpoch,
    sendRequest: options => { requests.push({ ...options, subject: owner }); if (respond) respond(options) } }
  global.getApp = () => app
  global.Page = value => { definition = value }
  global.wx = Object.assign({ showToast() {}, nextTick(fn) { fn() } }, disk)
  if (restart) delete require.cache[require.resolve('../../pages/play/utils/play-route-state')]
  const file = require.resolve('../../pages/play/index')
  delete require.cache[file]; require(file)
  const page = Object.assign({}, definition, { data: { ...definition.data, activityId: 20, mode: 1 }, _routeState: { sessionId: '99', version: 3 },
    setData(patch) { Object.assign(this.data, patch) }, diegetic() {} })
  return { page, switchTo(value) { owner = value; app._loginEpoch++ } }
}

test('提交后断网保留原身份，换页面/重启只查询原命令，实体变新版本不改原期望', async () => {
  const disk = storage(), requests = []
  const first = host('701', disk, requests, request => {
    if (request.url === '/api/play/action-context') request.success({ code: 200, data: { routeSessionId: 99, expectedRouteVersion: 3, runtimeInstance: 'original-instance', authorityEpoch: 1 } })
    else request.fail()
  }, true)
  const body = first.page._routeWritePayload('answer', 11, { nodeId: 11, answer: 'original' })
  await first.page._sendBasicRouteIntent('/api/play/answer', body)
  assert.equal(requests.filter(row => row.method === 'POST').length, 1)
  const originalId = body.routeActionId
  for (const restart of [false, true]) {
    const next = host('701', disk, requests, request => request.success({ code: 200, intentTerminal: true, data: { firstTime: true } }), restart)
    next.page._routeState.version = 8
    const retry = next.page._routeWritePayload('answer', 11, { nodeId: 11, answer: 'replacement' })
    assert.equal(retry.routeActionId, originalId)
    assert.equal(retry.expectedRouteVersion, 3)
    const response = await next.page._sendBasicRouteIntent('/api/play/answer', retry)
    assert.equal(response.intentTerminal, true)
    assert.equal(requests.at(-1).url, '/api/play/action-receipt')
    assert.equal(requests.at(-1).method, 'GET')
  }
  assert.equal(requests.filter(row => row.method === 'POST').length, 1)
})

test('A 的预检完成前切到 B，不把 A 的命令用 B token 发出', async () => {
  const disk = storage(), requests = []
  const a = host('702', disk, requests, null, true)
  const body = a.page._routeWritePayload('answer', 11, { nodeId: 11, answer: 'A' })
  const sending = a.page._sendBasicRouteIntent('/api/play/answer', body)
  assert.equal(requests.length, 1)
  a.switchTo('703')
  requests[0].success({ code: 200, data: { routeSessionId: 99, expectedRouteVersion: 3 } })
  assert.equal((await sending).obsolete, true)
  assert.equal(requests.filter(row => row.method === 'POST').length, 0)
  const b = host('703', disk, requests, null, false)
  assert.notEqual(b.page._routeWritePayload('answer', 11, { nodeId: 11, answer: 'B' }).routeActionId, body.routeActionId)
})

test('预检发现另一运行时直接阻止写入，不自动绑定新局', async () => {
  const requests = []
  const h = host('704', storage(), requests, request => request.success({ code: 200, data: { routeSessionId: 100, expectedRouteVersion: 3 } }), true)
  const body = h.page._routeWritePayload('answer', 11, { nodeId: 11, answer: 'A' })
  const response = await h.page._sendBasicRouteIntent('/api/play/answer', body)
  assert.equal(response.intentNotExecuted, true)
  assert.equal(requests.some(row => row.method === 'POST'), false)
})

test('A→B→A 后原登录代次的预检回调不能继续发写', async () => {
  const requests = [], h = host('705', storage(), requests, null, true)
  const body = h.page._routeWritePayload('answer', 11, { nodeId: 11, answer: 'A' })
  const sending = h.page._sendBasicRouteIntent('/api/play/answer', body)
  h.switchTo('706'); h.switchTo('705')
  requests[0].success({ code: 200, data: { routeSessionId: 99, expectedRouteVersion: 3 } })
  assert.equal((await sending).obsolete, true)
  assert.equal(requests.some(row => row.method === 'POST'), false)
})


test('页面清理不能让同局未确认命令换号或改输入', () => {
  const disk = storage(), store = createRouteActionStore(() => 'original', { storage: disk, scope: ['7', 'a:10', '99'] })
  const body = store.payload('photo', 11, { sessionId: '99', version: 3 }, { nodeId: 11, picUrl: 'private-original' })
  body._bindRouteContext({ routeSessionId: '99', expectedRouteVersion: 3 })
  body._markIntentUnknown(); store.clear()
  const retry = store.payload('photo', 11, { sessionId: '99', version: 8 }, { picUrl: 'replacement' })
  assert.equal(retry.routeActionId, 'original'); assert.equal(retry.receiptOnly, true)
  assert.equal(retry.picUrl, 'private-original'); assert.equal(retry.expectedRouteVersion, 3)
  assert.equal(JSON.stringify([...disk.data.values()]).includes('private-original'), false)
})

for (const [kind, url, input] of [
  ['photo', '/api/play/photo', { picUrl: 'original-photo' }],
  ['puzzleReveal', '/api/play/puzzle/reveal', {}],
  ['sensor', '/api/play/sensor-result', { heldMs: 1234 }],
  ['preference', '/api/play/preference/11/submit', { choices: ['original'] }],
]) test(kind + ': 原共享局身份贯穿写入，UNKNOWN 重启仅原号 GET', async () => {
  const disk = storage(), requests = [];
  const first = host('owner-' + kind, disk, requests, r => {
    if (r.url === '/api/play/action-context') r.success({ code: 200, data: { routeSessionId: '99', expectedRouteVersion: 3, runtimeInstance: 'frozen-shared', authorityEpoch: 7 } });
    else r.fail();
  }, true);
  const body = first.page._routeWritePayload(kind, 11, input);
  await first.page._sendBasicRouteIntent(url, body);
  const write = requests.find(r => r.method === 'POST'); assert.ok(write);
  assert.equal(write.data.routeSessionId, '99'); assert.equal(write.data.runtimeInstance, 'frozen-shared'); assert.equal(write.data.authorityEpoch, 7);
  const next = host('owner-' + kind, disk, requests, r => r.success({ code: 520 }), true);
  await next.page._sendBasicRouteIntent(url, next.page._routeWritePayload(kind, 11, { replacement: true }));
  assert.equal(requests.filter(r => r.method === 'POST').length, 1);
  const read = requests.at(-1); assert.equal(read.url, '/api/play/action-receipt'); assert.equal(read.method, 'GET');
  assert.equal(read.data.routeActionId, write.data.routeActionId); assert.equal(read.data.picUrl, undefined); assert.equal(read.data.choices, undefined);
});

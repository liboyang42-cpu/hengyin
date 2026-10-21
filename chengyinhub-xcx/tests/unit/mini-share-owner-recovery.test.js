'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const share = require('../../utils/roam-share-snapshot.js')
const TOKEN = 'a'.repeat(32)
const SESSION = { sessionId: '9223372036854775807', ts: 123, track: [], pois: [] }
function harness() {
  let owner = 'A', authorization = 'session-A-1'
  const values = {}, requests = []
  const app = {
    getUserID: () => owner, getAuthorization: () => authorization,
    sendRequest: options => requests.push({ owner, options }),
  }
  const storage = {
    getStorageSync: key => values[key],
    setStorageSync: (key, value) => { values[key] = value },
    removeStorageSync: key => { delete values[key] },
  }
  const key = id => 'roam_share_revoke_pending:' + id
  return { app, storage, values, requests, key,
    switchTo(id, session) { owner = id; authorization = session || 'session-' + id + '-1' },
    success(index, res) { requests[index].options.success(res) },
  }
}
const failed = { token: '', reason: 'failed' }
test('late A revoke clears A pending only while B is current', async () => {
  const h = harness()
  h.values[h.key('B')] = 1
  const result = share.revokeShareSnapshots(h.app, h.storage)
  h.switchTo('B')
  h.success(0, { code: 200 })
  assert.equal(await result, true)
  assert.equal(h.values[h.key('A')], undefined)
  assert.equal(h.values[h.key('B')], 1)
})
test('A-B-A late revoke never clears the independent B pending marker', async () => {
  const h = harness()
  const a = share.revokeShareSnapshots(h.app, h.storage)
  h.switchTo('B')
  const b = share.revokeShareSnapshots(h.app, h.storage)
  h.switchTo('A', 'session-A-2')
  h.success(1, { code: 500 })
  h.success(0, { code: 200 })
  assert.equal(await a, true)
  assert.equal(await b, false)
  assert.equal(h.values[h.key('A')], undefined)
  assert.equal(h.values[h.key('B')], 1)
})
for (const change of ['B', 'ABA']) {
  test('pending repair cannot publish after owner/session change: ' + change, async () => {
    const h = harness()
    h.values[h.key('A')] = 1
    const result = share.publishShareSnapshot(h.app, h.storage, SESSION)
    h.switchTo('B')
    if (change === 'ABA') h.switchTo('A', 'session-A-2')
    h.success(0, { code: 200 })
    await Promise.resolve()
    await Promise.resolve()
    if (h.requests[1]) h.success(1, { code: 200, data: { token: TOKEN } })
    assert.deepEqual(await result, failed)
    assert.equal(h.requests.length, 1)
  })
  test('no-pending microtask cannot publish after owner/session change: ' + change, async () => {
    const h = harness()
    const result = share.publishShareSnapshot(h.app, h.storage, SESSION)
    h.switchTo('B')
    if (change === 'ABA') h.switchTo('A', 'session-A-2')
    await Promise.resolve()
    // Resolve an unexpected POST too, so the old implementation fails rather than hangs.
    if (h.requests.length) h.success(0, { code: 200, data: { token: TOKEN } })
    assert.deepEqual(await result, failed)
    assert.equal(h.requests.length, 0)
  })
  test('snapshot response after owner/session change cannot expose a token: ' + change, async () => {
    const h = harness()
    const result = share.publishShareSnapshot(h.app, h.storage, SESSION)
    await Promise.resolve()
    assert.equal(h.requests.length, 1)
    assert.equal(h.requests[0].owner, 'A')
    h.switchTo('B')
    if (change === 'ABA') h.switchTo('A', 'session-A-2')
    h.success(0, { code: 200, data: { token: TOKEN } })
    assert.deepEqual(await result, failed)
  })
}
test('failed repair remains retryable; successful retry precedes a normal publish', async () => {
  const h = harness()
  h.values[h.key('A')] = 1
  const first = share.publishShareSnapshot(h.app, h.storage, SESSION)
  h.requests[0].options.fail()
  assert.deepEqual(await first, { token: '', reason: 'revoke-pending' })
  assert.equal(h.values[h.key('A')], 1)
  const retry = share.publishShareSnapshot(h.app, h.storage, SESSION)
  h.success(1, { code: '200' })
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(h.requests[2].options.url, '/api/roam/share/snapshot')
  assert.equal(h.requests[2].options.data.sessionId, SESSION.sessionId)
  assert.equal(h.requests[2].options.data.clientTs, 123)
  h.success(2, { code: '200', data: { token: TOKEN } })
  assert.deepEqual(await retry, { token: TOKEN, reason: '' })
  assert.equal(h.values[h.key('A')], undefined)
})
test('exact session id, location expiry and client timestamp protections remain effective', async () => {
  const h = harness()
  for (const patch of [
    { sessionId: 9223372036854775807 }, { sessionId: '9223372036854775808' },
    { locationExpired: true }, { ts: 0 }, { ts: 1.5 }, { ts: Infinity },
  ]) assert.deepEqual(await share.publishShareSnapshot(h.app, h.storage, Object.assign({}, SESSION, patch)), failed)
  assert.equal(h.requests.length, 0)
})
for (const response of [
  { code: 500, msg: 'temporarily unavailable' }, { code: 200, data: null },
  { code: 200, data: [] }, { code: 200, data: 'bad' }, null, undefined,
  { code: 401, msg: '请先登录' }, { code: 500, msg: '这条足迹已不再公开，请重试' },
]) test('unknown or malformed read remains retryable: ' + JSON.stringify(response), async () => {
  const h = harness()
  const result = share.readShareSnapshot(h.app, TOKEN)
  h.success(0, response)
  assert.deepEqual(await result, { state: 'error', session: null })
})
test('explicit server gone and locally invalid token are the only gone cases', async () => {
  const h = harness()
  assert.deepEqual(await share.readShareSnapshot(h.app, 'bad'), { state: 'gone', session: null })
  assert.equal(h.requests.length, 0)
  const result = share.readShareSnapshot(h.app, TOKEN)
  h.success(0, { code: 500, msg: '这条足迹已不再公开' })
  assert.deepEqual(await result, { state: 'gone', session: null })
})
test('ready read, business retry and transport failure preserve their separate states', async () => {
  const h = harness()
  const data = { routeName: '公开足迹', track: [] }
  const ready = share.readShareSnapshot(h.app, TOKEN)
  h.success(0, { code: '200', data })
  assert.deepEqual(await ready, { state: 'ready', session: data })
  for (const callback of ['fail', 'successStatusAbnormal']) {
    const retry = share.readShareSnapshot(h.app, TOKEN)
    h.requests[h.requests.length - 1].options[callback]()
    assert.deepEqual(await retry, { state: 'error', session: null })
  }
})

const { createRequestClient } = require('../../utils/transport/request-client.js')
function transportHarness(onRequest) {
  const h = harness(), wire = []
  const client = createRequestClient({
    getBaseUrl: () => 'https://example.invalid',
    getUserID: h.app.getUserID, getAuthorization: h.app.getAuthorization,
    wxRequest(options) {
      wire.push(options)
      if (onRequest) onRequest(h, wire)
      return { abort() {} }
    },
  })
  h.app.sendRequest = client.send
  h.wire = wire
  h.reply = (index, body) => wire[index].success({ statusCode: 200, data: body })
  return h
}
test('real transport: P insert between R1 DELETE and late success cannot erase failed R2 pending', async () => {
  const h = transportHarness()
  let snapshotExists = false
  const publish = share.publishShareSnapshot(h.app, h.storage, SESSION)
  await Promise.resolve()
  const r1 = share.revokeShareSnapshots(h.app, h.storage)
  assert.equal(h.wire.length, 2)
  snapshotExists = false // R1 DELETE commits; its HTTP response remains delayed.
  snapshotExists = true // The already in-flight P INSERT commits afterwards.
  h.reply(0, { code: 200, data: { token: TOKEN } })
  assert.deepEqual(await publish, { token: TOKEN, reason: '' })
  const r2 = share.revokeShareSnapshots(h.app, h.storage)
  h.wire[2].fail({ errMsg: 'request:fail' })
  assert.equal(await r2, false)
  h.reply(1, { code: 200 })
  assert.equal(await r1, true, 'acknowledge this DELETE without settling the newer pending intent')
  assert.equal(h.values[h.key('A')], 1)
  const read = share.readShareSnapshot(h.app, TOKEN)
  h.reply(3, snapshotExists ? { code: 200, data: { track: [] } } : { code: 500, msg: '这条足迹已不再公开' })
  assert.equal((await read).state, 'ready', 'this protection does not invent a backend tombstone')
  const retry = share.publishShareSnapshot(h.app, h.storage, SESSION)
  assert.equal(h.wire[4].url, 'https://example.invalid/api/roam/share/revoke')
  assert.equal(h.wire[4].header.Authorization, 'session-A-1')
  snapshotExists = false
  h.reply(4, { code: 200 })
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(h.wire[5].url, 'https://example.invalid/api/roam/share/snapshot')
  h.reply(5, { code: 200, data: { token: TOKEN } })
  assert.deepEqual(await retry, { token: TOKEN, reason: '' })
  assert.equal(h.values[h.key('A')], undefined)
})
test('real transport: synchronous reentry creates a distinct newer revoke, never singleflights it', async () => {
  let nested
  const h = transportHarness((state, wire) => {
    if (wire.length === 1) nested = share.revokeShareSnapshots(state.app, state.storage)
  })
  const first = share.revokeShareSnapshots(h.app, h.storage)
  assert.equal(h.wire.length, 2)
  h.reply(1, { code: 500, msg: 'temporarily unavailable' })
  assert.equal(await nested, false)
  h.reply(0, { code: 200 })
  assert.equal(await first, true)
  assert.equal(h.values[h.key('A')], 1)
})
test('real transport: older completion cannot clear a newer pending intent or restore one after success', async () => {
  const h = transportHarness()
  const first = share.revokeShareSnapshots(h.app, h.storage)
  const second = share.revokeShareSnapshots(h.app, h.storage)
  h.reply(0, { code: 200 })
  assert.equal(await first, true)
  assert.equal(h.values[h.key('A')], 1)
  h.reply(1, { code: 200 })
  assert.equal(await second, true)
  assert.equal(h.values[h.key('A')], undefined)
  const third = share.revokeShareSnapshots(h.app, h.storage)
  const fourth = share.revokeShareSnapshots(h.app, h.storage)
  h.reply(3, { code: 200 })
  assert.equal(await fourth, true)
  h.wire[2].fail({ errMsg: 'request:fail' })
  assert.equal(await third, false)
  assert.equal(h.values[h.key('A')], undefined)
})
test('real transport: different accounts keep independent revoke intents and session rejection', async () => {
  const h = transportHarness()
  const a = share.revokeShareSnapshots(h.app, h.storage)
  h.switchTo('B')
  const b = share.revokeShareSnapshots(h.app, h.storage)
  h.reply(0, { code: 200 }) // Real transport rejects this old account callback.
  h.reply(1, { code: 200 })
  assert.equal(await a, false)
  assert.equal(await b, true)
  assert.equal(h.values[h.key('A')], 1)
  assert.equal(h.values[h.key('B')], undefined)
})
test('real transport: sequential successful revokes retain the persisted boolean contract', async () => {
  const h = transportHarness()
  for (let i = 0; i < 2; i++) {
    const result = share.revokeShareSnapshots(h.app, h.storage)
    assert.equal(h.values[h.key('A')], 1)
    h.reply(i, { code: 200 })
    assert.equal(await result, true)
    assert.equal(h.values[h.key('A')], undefined)
  }
})

test('real transport: successful old repair cannot resume publish across a newer failed revoke', async () => {
  const h = transportHarness()
  h.values[h.key('A')] = 1
  const publish = share.publishShareSnapshot(h.app, h.storage, SESSION)
  const newer = share.revokeShareSnapshots(h.app, h.storage)
  h.wire[1].fail({ errMsg: 'request:fail' })
  assert.equal(await newer, false)
  h.reply(0, { code: 200 })
  assert.deepEqual(await publish, { token: '', reason: 'revoke-pending' })
  assert.equal(h.wire.length, 2, 'no snapshot POST across the unresolved newer intent')
  assert.equal(h.values[h.key('A')], 1)
})

'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

// Execute the actual registered component, retaining callback order under test control.
const component = path.resolve(__dirname, '../../pages/publish/components/topic-state-keys/index.js')
function mount({ transport = false } = {}) {
  let definition
  const requests = [], dialog = [], writes = []
  const identity = { member: '11', authorization: 'fixture-session-a' }
  const app = { getUserID: () => identity.member, getAuthorization: () => identity.authorization,
    sendRequest: request => { requests.push(request) } }
  const wire = [], logins = []
  if (transport) {
    const { createRequestClient } = require('../../utils/transport/request-client.js')
    const client = createRequestClient({
      wxRequest: request => { wire.push(request); return { abort() {} } },
      getBaseUrl: () => 'https://fixture.invalid', getUserID: app.getUserID, getAuthorization: app.getAuthorization,
      reLogin: callback => {
        logins.push(identity.member)
        identity.authorization = 'fixture-session-renewed'
        callback(true, { isCurrentSession: () => true })
      },
    })
    app.sendRequest = request => { requests.push(request); return client.send(request) }
  }
  vm.runInNewContext(fs.readFileSync(component, 'utf8'), {
    getApp: () => app, Component: value => { definition = value },
    require: name => require(path.resolve(path.dirname(component), name)),
  }, { filename: component })
  const dc = Object.fromEntries(['open', 'busyOn', 'done', 'failed', 'close'].map(name =>
    [name, (...args) => dialog.push([name, ...args])]))
  if (transport) {
    const dialogPath = path.resolve(__dirname, '../../components/cy/danger-confirm/index.js')
    let realDialog
    vm.runInNewContext(fs.readFileSync(dialogPath, 'utf8'), {
      Component: value => { realDialog = value },
      require: name => require(path.resolve(path.dirname(dialogPath), name)), console,
    }, { filename: dialogPath })
    dc.data = JSON.parse(JSON.stringify(realDialog.data))
    dc.setData = value => Object.assign(dc.data, value)
    Object.entries(realDialog.methods).forEach(([name, fn]) => {
      dc[name] = (...args) => { dialog.push([name, ...args]); return fn.apply(dc, args) }
    })
  }
  const host = { properties: { topicId: '1', scope: '', readOnly: false },
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(value) { writes.push(value); Object.assign(this.data, value) }, selectComponent: () => dc }
  Object.entries(definition.methods).forEach(([name, fn]) => { host[name] = fn.bind(host) })
  const observe = changed => Object.entries(definition.observers || {}).forEach(([fields, fn]) => {
    const names = fields.split(',').map(name => name.trim())
    if (names.some(name => changed.includes(name))) fn.apply(host, names.map(name => host.properties[name]))
  })
  observe(['topicId', 'scope', 'readOnly'])
  definition.lifetimes?.attached?.call(host)
  const change = value => { Object.assign(host.properties, value); observe(Object.keys(value)) }
  const reply = (request, name = 'old') => request.success({ code: 200, data: [
    { stateKey: 'counter.' + name, keyType: 'counter', label: name, initialValue: 1 },
  ] })
  const save = name => { const label = name.replace(/^counter\./, '')
    Object.assign(host.data, { adding: 'tag', addName: label }); host.onSave(); return requests.at(-1) }
  const remove = key => { host.onAskDelete({ currentTarget: { dataset: { key } } });
    const params = dialog.filter(row => row[0] === 'open').at(-1)?.[2] || { key }
    host.onConfirmDelete({ detail: { params } }); return requests.at(-1) }
  return { host, requests, dialog, writes, identity, change, reply, save, remove, wire, logins, dc,
    detach: () => definition.lifetimes?.detached?.call(host),
    attach: () => definition.lifetimes?.attached?.call(host),
    show: () => definition.pageLifetimes?.show?.call(host) }
}
const snapshot = h => JSON.stringify({ data: h.host.data, dialog: h.dialog, requests: h.requests.length, writes: h.writes.length })
const completions = {
  success: request => request.success({ code: 200 }),
  rejection: request => request.success({ code: 500, msg: 'fixture rejection' }),
  fail: request => request.fail({ errMsg: 'fixture network failure' }),
  abnormal: request => request.successStatusAbnormal({ code: 503 }),
}

test('reads: reverse completion accepts only the newest request', () => {
  const h = mount(), first = h.requests.at(-1)
  h.host.load(); const latest = h.requests.at(-1)
  h.reply(latest, 'new'); const before = snapshot(h)
  h.reply(first); assert.equal(snapshot(h), before)
  assert.equal(h.host._keys[0].stateKey, 'counter.new')
})

for (const [name, context] of Object.entries({ topic: { topicId: '2' }, scope: { scope: 'MERCHANT' },
  readOnly: { readOnly: true }, empty: { topicId: '' } })) {
  test('read context changes: ' + name, () => {
    const h = mount(), old = h.requests.at(-1)
    h.reply(old); h.host.load(); const pending = h.requests.at(-1)
    h.change(context)
    assert.equal(h.host._keys.length, 0, 'previous context rows must clear')
    const before = snapshot(h)
    h.reply(pending); pending.fail(); pending.successStatusAbnormal()
    assert.equal(snapshot(h), before, 'old read must not alter the new context')
    if (context.topicId !== '') {
      const current = h.requests.at(-1)
      assert.notEqual(current, pending, 'scope/readOnly changes must reload')
      h.reply(current, 'new'); assert.equal(h.host._keys[0].stateKey, 'counter.new')
    }
  })
}

for (const operation of ['save', 'remove']) {
  for (const [outcome, complete] of Object.entries(completions)) {
    for (const [contextName, context] of Object.entries({ topic: { topicId: '2' }, scope: { scope: 'MERCHANT' }, readOnly: { readOnly: true } })) {
      test(`${operation} ${outcome}: late callback after ${contextName} preserves current busy and dialog`, () => {
        const h = mount(), old = h[operation]('counter.old')
        assert.equal(old.method, operation === 'save' ? 'POST' : 'DELETE')
        h.change(context)
        if (!context.readOnly) h[operation]('counter.new')
        const before = snapshot(h)
        complete(old)
        assert.equal(snapshot(h), before, 'no stale state, dialog completion or automatic reload/replay')
      })
    }
    test(`${operation} ${outcome}: detached callbacks have no effects`, () => {
      const h = mount(), read = h.requests[0], old = h[operation]('counter.old')
      h.detach(); const before = snapshot(h)
      complete(old); h.reply(read); read.fail(); read.successStatusAbnormal()
      assert.equal(snapshot(h), before)
    })
    for (const changed of ['member', 'authorization']) {
      test(`${operation} ${outcome}: changed ${changed} rejects stale callback`, () => {
        const h = mount(), old = h[operation]('counter.old')
        h.identity[changed] = 'fixture-other'
        const before = snapshot(h), count = h.requests.length
        complete(old)
        if (changed === 'member') assert.equal(snapshot(h), before)
        else {
          assert.equal(h.host.data.saving, false)
          assert.equal(h.host.data.deleting, '')
          assert.equal(h.requests.length, count + 1, 'renewed session recovers without page show')
          assert.equal(h.requests.at(-1).method, 'GET')
        }
        h.show()
        assert.equal(h.host.data.saving, false)
        assert.equal(h.host.data.deleting, '')
        assert.equal(h.requests.at(-1).method, 'GET', 'foreground recovery reads; never replays writes')
      })
    }
  }
}

test('current successful writes finish once and reload once; current failures remain visible', () => {
  const h = mount()
  const save = h.save('first'), count = h.requests.length
  completions.success(save)
  assert.equal(h.host.data.saving, false); assert.equal(h.requests.length, count + 1)
  const failedSave = h.save('second'); completions.fail(failedSave)
  assert.ok(h.host.data.formError); assert.equal(h.host.data.saving, false)
  const remove = h.remove('counter.first'), before = h.requests.length
  completions.success(remove)
  assert.equal(h.host.data.deleting, ''); assert.equal(h.requests.length, before + 1)
  assert.equal(h.dialog.filter(row => row[0] === 'done').length, 1)
  const failedDelete = h.remove('counter.second'); completions.rejection(failedDelete)
  assert.ok(h.host.data.deleteError); assert.equal(h.host.data.deleting, '')
})

test('readonly and detached handlers cannot start writes; old confirmation cannot delete in new context', () => {
  const h = mount()
  h.host.onAskDelete({ currentTarget: { dataset: { key: 'counter.old' } } })
  const params = h.dialog.filter(row => row[0] === 'open').at(-1)[2]
  h.change({ topicId: '2' })
  let before = h.requests.length
  h.host.onConfirmDelete({ detail: { params } })
  assert.equal(h.requests.length, before)
  h.change({ readOnly: true }); before = h.requests.length
  h.save('readonly'); h.remove('counter.old')
  assert.equal(h.requests.length, before)
  h.detach(); h.change({ readOnly: false }); before = h.requests.length
  h.save('detached'); h.remove('counter.old'); h.host.load()
  assert.equal(h.requests.length, before)
})

test('account change invalidates reads before foreground reload', () => {
  const h = mount(), old = h.requests.at(-1)
  h.identity.member = '22'; const before = snapshot(h)
  h.reply(old); old.fail(); old.successStatusAbnormal()
  assert.equal(snapshot(h), before)
  h.show(); h.reply(h.requests.at(-1), 'new')
  assert.equal(h.host._keys[0].stateKey, 'counter.new')
})

test('reattach and A→B→A changes do not revive old reads', () => {
  const h = mount(), old = h.requests.at(-1)
  h.change({ topicId: '2' }); h.change({ topicId: '1' })
  let before = snapshot(h); h.reply(old); assert.equal(snapshot(h), before)
  const pending = h.requests.at(-1)
  h.detach(); h.attach()
  before = snapshot(h); h.reply(pending); assert.equal(snapshot(h), before)
  h.reply(h.requests.at(-1), 'new'); assert.equal(h.host._keys[0].stateKey, 'counter.new')
})

test('duplicate write callbacks cannot clear a subsequent operation in the same context', () => {
  for (const operation of ['save', 'remove']) {
    const h = mount(), old = h[operation]('counter.old')
    completions.success(old)
    h[operation]('counter.new'); const before = snapshot(h)
    Object.values(completions).forEach(complete => complete(old))
    assert.equal(snapshot(h), before)
  }
})

test('callbacks compare properties even before their observer runs', () => {
  const h = mount(), read = h.requests.at(-1), save = h.save('old')
  h.host.properties.scope = 'MERCHANT'
  const before = snapshot(h)
  h.reply(read); completions.success(save)
  assert.equal(snapshot(h), before)
})

test('superseded operations cannot recover a renewed session over a newer busy operation', () => {
  for (const operation of ['read', 'save', 'remove']) {
    const h = mount()
    const old = operation === 'read' ? h.requests.at(-1) : h[operation]('counter.old')
    if (operation === 'read') h.host.load()
    else { completions.success(old); h[operation]('counter.new') }
    const active = h.requests.at(-1)
    h.identity.authorization = 'fixture-session-renewed'
    const before = snapshot(h)
    Object.values(completions).forEach(complete => complete(old))
    assert.equal(snapshot(h), before, 'operation ownership is checked before session recovery')
    active.fail({ errMsg: 'current retry failed' })
    assert.equal(h.host.data.saving, false)
    assert.equal(h.host.data.deleting, '')
    assert.equal(h.requests.at(-1).method, 'GET')
  }
})

for (const operation of ['read', 'save', 'remove']) {
  for (const outcome of ['success', 'rejection', 'fail', 'abnormal']) {
    test(`real 401→same member renewal→${operation} ${outcome} recovers in foreground`, () => {
      const h = mount({ transport: true })
      if (operation !== 'read') {
        h.wire[0].success({ statusCode: 200, data: { code: 200, data: [] } })
        h[operation]('counter.old')
      }
      const first = h.wire.at(-1), requestCount = h.requests.length
      if (operation === 'save') assert.equal(h.host.data.saving, true)
      if (operation === 'remove') {
        assert.equal(h.host.data.deleting, 'counter.old')
        assert.equal(h.dc.data.busy, true, 'real dangerous confirmation is locked before renewal')
      }
      first.success({ statusCode: 401, data: { code: 401 } })
      const retry = h.wire.at(-1)
      assert.notEqual(retry, first)
      assert.equal(h.logins.length, 1)
      assert.equal(h.identity.member, '11')
      assert.equal(retry.header.Authorization, 'fixture-session-renewed')
      assert.equal(retry.method, first.method)
      assert.equal(JSON.stringify(retry.data), JSON.stringify(first.data))
      assert.equal(h.requests.length, requestCount, 'only transport retries the original request')
      if (outcome === 'fail') retry.fail({ errMsg: 'fixture retry failure' })
      else retry.success({ statusCode: outcome === 'abnormal' ? 503 : 200,
        data: { code: outcome === 'success' ? 200 : 500, data: [] } })
      assert.equal(h.host.data.saving, false)
      assert.equal(h.host.data.deleting, '')
      assert.equal(h.dc.data.busy, false)
      assert.equal(h.dc.data.stage, '', 'old dialog closes rather than reporting old success/failure')
      assert.equal(h.dialog.some(row => ['done', 'failed'].includes(row[0])), false)
      assert.equal(h.requests.length, requestCount + 1)
      assert.equal(h.requests.at(-1).method, 'GET', 'recovery only reads; component never replays writes')
      const recovery = h.wire.at(-1)
      assert.notEqual(recovery, retry)
      recovery.success({ statusCode: 200, data: { code: 200, data: [
        { stateKey: 'counter.fresh', keyType: 'counter', initialValue: 1 },
      ] } })
      assert.equal(h.host.data.state, 'ready')
      assert.equal(h.host._keys[0].stateKey, 'counter.fresh')
      const before = snapshot(h)
      retry.fail({ errMsg: 'duplicate late callback' })
      assert.equal(snapshot(h), before, 'settled old retry cannot clear the recovered context')
    })
  }
}

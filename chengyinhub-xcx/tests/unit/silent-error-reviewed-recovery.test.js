'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const gate = require('../../../ci/silent-error-gate')
const { findU5Issues } = require('../../scripts/ui-validity-gates')
const shared = require('../../scripts/ui-silent-error-allowlist')
const { createVerificationWorkflow } = require('../../utils/verification-readback')
const { captureLogin, loginCurrent, seatKey, seatRecovery } = require('../../utils/registration-seat')
const root = path.resolve(__dirname, '../../..')
const parsed = gate.parseAllowlist(fs.readFileSync(path.join(root, 'ci/silent-error-allowlist.txt'), 'utf8'))
const reviewed = [
  { file: 'pages/merchant/citynode/index.js', count: 8 },
  { file: 'pages/play/index.js', count: 2 },
  { file: 'utils/registration-seat.js', count: 1 },
]
const sources = reviewed.map(item => ({ file: item.file,
  source: fs.readFileSync(path.join(root, 'chengyinhub-xcx', item.file), 'utf8') }))
const mini = shared.filter(entry => reviewed.some(item => entry.file === item.file))
const originalEntries = new Map(reviewed.map(item => {
  const file = 'chengyinhub-xcx/' + item.file
  return [file, parsed.entries.get(file)]
}))
function rootResult(input) {
  const actual = new Map(input.map(item => ['chengyinhub-xcx/' + item.file, gate.findSilentErrorSites(item.source)])
    .filter(([, sites]) => sites.length))
  return gate.reconcile(actual, originalEntries)
}
function rejectionCount(result) { return Object.values(result).reduce((sum, issues) => sum + issues.length, 0) }

test('both U5 readers share exact reviewed counts while frozen baseline stays unchanged', () => {
  assert.deepEqual(parsed.problems, [])
  assert.equal(parsed.baselineTotal.declared, 72)
  for (const item of reviewed) {
    const entry = originalEntries.get('chengyinhub-xcx/' + item.file)
    assert.equal(entry.allowed, item.count)
    assert.equal(mini.find(value => value.file === item.file).count, item.count)
  }
  for (const file of ['pages/merchant/citynode/index.js', 'pages/play/index.js']) {
    const baseline = originalEntries.get('chengyinhub-xcx/' + file).lines.filter(line => line.baseline)
    assert.equal(baseline.length, 1)
    assert.equal(baseline[0].count, 1)
  }
  const added = [...originalEntries.values()].flatMap(entry => entry.lines)
    .filter(line => line.reason.startsWith('2026-10-24 '))
  assert.equal(added.length, 4)
  assert.ok(added.every(line => line.count === 1 && /UNKNOWN/.test(line.reason)))
  assert.equal(rejectionCount(rootResult(sources)), 0)
  const result = findU5Issues(sources, mini)
  assert.deepEqual(result.issues, [])
  assert.equal(result.fileCount, 3)
  assert.equal(result.callCount, 11)
})

test('adding/removing any reviewed call or using a sibling path still fails both gates', () => {
  for (const item of sources) {
    for (const source of [item.source + '\napp.sendRequest({silentError:true})',
      item.source.replace(/\bsilentError\s*:\s*true\b/, 'silentError:false')]) {
      assert.notEqual(source, item.source)
      const changed = sources.map(value => value === item ? { ...item, source } : value)
      assert.ok(rejectionCount(rootResult(changed)) > 0, item.file)
      assert.ok(findU5Issues(changed, mini).issues.length > 0, item.file)
    }
    const sibling = [{ file: item.file.replace(/\.js$/, '-unreviewed.js'), source: 'x({silentError:true})' }]
    assert.equal(rootResult(sibling).unlisted.length, 1)
    assert.match(findU5Issues(sibling, mini).issues.join('\n'), /未进白名单/)
  }
})

test('shared receipt failure keeps original UNKNOWN without repost and ignores a retired login', () => {
  const rows = new Map(), requests = [], results = []
  const identity = { member: '9', revision: 1 }
  const storage = { getStorageSync: key => rows.get(key),
    setStorageSync: (key, value) => rows.set(key, value), removeStorageSync: key => rows.delete(key) }
  const app = { getUserID: () => identity.member, getAuthorization: () => 'original-auth',
    getSession: () => ({ getRevision: () => identity.revision }),
    sendRequest(request) { requests.push(request); return { abort() {} } } }
  const login = captureLogin(app, storage)
  const current = () => loginCurrent(app, storage, login)
  const target = { registrationId: '17', seatNo: 1, activityId: '71' }
  const workflow = createVerificationWorkflow({ storage, getOwner: app.getUserID,
    setTimer: () => 1, clearTimer() {}, deadlineMs: 1000 })
  const recovery = seatRecovery(app, target, current), key = seatKey(login.owner, target)
  let starts = 0
  const start = done => { starts++; done({ status: 'unknown', response: { code: 202 } }) }
  const run = () => workflow.run(key, start, result => results.push(result.status), recovery)
  assert.equal(run(), true)
  const original = JSON.stringify([...rows])
  for (const kind of ['fail', 'successStatusAbnormal', 'success']) {
    assert.equal(run(), true)
    const request = requests.at(-1)
    assert.equal(request.url, '/api/verify/seat/receipt')
    assert.equal(request.silentError, true)
    assert.deepEqual(request.data, target)
    request[kind]({ code: 500, msg: 'controlled unreadable receipt' })
    assert.equal(results.at(-1), 'unknown')
    assert.equal(JSON.stringify([...rows]), original)
  }
  assert.equal(starts, 1, 'UNKNOWN recovery must never enter the write callback again')
  assert.deepEqual(results, ['unknown', 'unknown', 'unknown', 'unknown'])
  assert.equal(run(), true)
  identity.member = '10'; identity.revision++
  requests.at(-1).success({ code: 200, data: { seatId: '18', registrationId: '17', seatNo: 1,
    activityId: '71', verifierMemberId: '70', replayed: true, verifiedAt: '2026-10-24 00:00:00' } })
  assert.equal(results.length, 4)
  assert.equal(JSON.stringify([...rows]), original)
  assert.equal(run(), false)
  assert.equal(starts, 1)
  workflow.destroy()
})

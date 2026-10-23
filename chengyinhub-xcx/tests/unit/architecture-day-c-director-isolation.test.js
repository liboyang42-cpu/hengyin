const test = require('node:test')
const assert = require('node:assert/strict')
const { DIRECTOR_DATA, DIRECTOR_METHODS } = require('../../pages/club/topic-detail/director')

function harness() {
  let owner = 'A', authorization = 'session-A-1'
  const values = new Map(), pending = [], submissions = []
  const app = { _featureFlagsEpoch: 1, getUserID: () => owner, getAuthorization: () => authorization }
  global.getApp = () => app
  global.wx = { getStorageSync: key => values.get(key), setStorageSync: (key, value) => values.set(key, value), removeStorageSync: key => values.delete(key), showToast() {} }
  const adapter = { submit(command) { submissions.push({ command, owner }); return new Promise(resolve => pending.push(resolve)) }, readReceipt() { return Promise.resolve({ state: 'pending' }) } }
  const page = Object.assign({}, DIRECTOR_METHODS, { data: { ...DIRECTOR_DATA, loadState: 'ready', revision: 3 }, _directorAdapter: adapter,
    setData(patch) { Object.assign(this.data, patch) }, loadProjection() {} })
  page.initDirector(701)
  const change = value => { owner = value; app._featureFlagsEpoch++; authorization = 'session-' + value + '-' + app._featureFlagsEpoch }
  return { page, values, pending, submissions, change }
}
const tick = () => new Promise(resolve => setImmediate(resolve))
const key = actor => 'cy.gameDirector.pendingWrite.m' + actor + '.a701'

test('A 的成功迟到后不能删除 B 的 pending，也不能清理 A 的未知身份', async () => {
  const h = harness(); h.page.executeAction('START', {})
  const original = h.values.get(key('A'))
  h.change('B'); const b = { requestId: 'B-original' }; h.values.set(key('B'), b)
  h.pending[0]({ state: 'confirmed' }); await tick()
  assert.equal(h.values.get(key('B')), b)
  assert.deepEqual(h.values.get(key('A')), original)
})

test('A UNKNOWN 后切 B，retry 不复制 A 命令到 B key、不以 B token 发送', async () => {
  const h = harness(); h.page.executeAction('START', {})
  h.pending[0]({ state: 'unknown-write' }); await tick()
  h.change('B'); h.page.retryUnknownWrite(); await tick()
  assert.equal(h.submissions.length, 1)
  assert.equal(h.values.has(key('B')), false)
  assert.ok(h.values.get(key('A')))
})

test('A→B→A 的新登录代次拒绝原请求迟到；同页重初始化也隔离旧命令', async () => {
  const h = harness(); h.page.executeAction('START', {})
  const original = h.values.get(key('A'))
  h.change('B'); h.change('A')
  h.pending[0]({ state: 'confirmed' }); await tick()
  assert.deepEqual(h.values.get(key('A')), original)
  h.page.retryUnknownWrite(); assert.equal(h.submissions.length, 1)
  h.change('B'); h.page.initDirector(701); h.page.data.loadState = 'ready'; h.page.executeAction('START', {})
  const newer = h.values.get(key('B')); assert.ok(newer)
  h.pending[0]({ state: 'confirmed' }); await tick()
  assert.deepEqual(h.values.get(key('B')), newer)
})

test('页面移出栈后忽略原写回调，保留原回执索引', async () => {
  const h = harness(); h.page.executeAction('START', {})
  const original = h.values.get(key('A'))
  const previous = global.getCurrentPages
  global.getCurrentPages = () => []
  try { h.pending[0]({ state: 'confirmed' }); await tick(); assert.deepEqual(h.values.get(key('A')), original) }
  finally { if (previous) global.getCurrentPages = previous; else delete global.getCurrentPages }
})

test('A 打开的确认弹窗在 B 登录后不能执行旧操作', () => {
  const h = harness(), modal = require('../../utils/modal')
  const original = modal.show
  let confirmation
  modal.show = options => { confirmation = options }
  try {
    h.page.data.readiness = { canStart: true }
    h.page.onStartSession(); h.change('B')
    confirmation.success({ confirm: true })
    assert.equal(h.submissions.length, 0)
    assert.equal(h.values.has(key('B')), false)
  } finally { modal.show = original }
})

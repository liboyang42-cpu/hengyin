const test = require('node:test')
const assert = require('node:assert/strict')
const loadPage = require('./helpers/vue-options.cjs')
const file = 'business/officialevent/index.vue'
const permitted = { hasPermi: () => true }
function load(globals) {
  const cache = globals.cache || { local: { setJSON() {}, getJSON() { return null } } }
  const result = loadPage(file, { auth: permitted, cache, ...globals })
  result.page.$store = { state: { user: { id: 1 } } }
  result.page.$modal = { msgWarning() {}, msgSuccess() {} }
  return result
}

test('提交未知保留原表单并阻止重复提交，不声称全部送达', async () => {
  let sent = 0
  const { page, source } = load({ broadcastOfficial: async () => { sent++; throw Error('timeout') } })
  page.notify.audience = ['player']; page.notify.content = '原通知'
  await page.submitNotify(); await page.submitNotify(); page.openNotify(null)
  assert.equal(sent, 1)
  assert.equal(page.notifyState, 'unknown')
  assert.equal(page.notify.content, '原通知')
  assert.doesNotMatch(source, /msgSuccess\(res.msg \|\| '通知已发送'/)
})

test('提交获得原批次后只读回结果，不重发，缺字段保持未知', async () => {
  let sent = 0; let readId
  const { page } = load({ broadcastOfficial: async () => { sent++; return { data: 7, msg: '全部送达' } }, getBroadcastStats: async id => { readId = id; return { data: { reached: 0, clicked: 2 } } } })
  page.notify.audience = ['merchant']
  await page.submitNotify(); await page.openBroadcastStats(page.notifyBatchId)
  assert.equal(sent, 1); assert.equal(readId, 7)
  assert.equal(page.notifyState, 'submitted')
  assert.equal(page.broadcastCount('reached'), 0)
  assert.equal(page.broadcastCount('read'), '待确认')
  await page.loadBroadcastStats(); assert.equal(sent, 1)
})

test('通知统计拒绝明确失败，未知不当零或失败次数', async () => {
  const { page, source } = load({ getBroadcastStats: async () => { throw Error('forbidden') } })
  await page.openBroadcastStats(8)
  assert.equal(page.broadcastStatsError, true)
  assert.equal(page.broadcastStatsLoading, false)
  assert.equal(page.broadcastCount('reached'), '待确认')
  for (const label of ['站内持久保存', '微信接受', '用户已读', '目标人数']) assert.ok(source.includes(`label="${label}">待确认`))
})

test('没有通知渠道或权限时不发送', async () => {
  let sent = 0
  const { page } = load({ broadcastOfficial: async () => { sent++ } })
  page.notify.audience = ['player']; page.notify.channels = []
  await page.submitNotify(); assert.equal(sent, 0)
  const denied = load({ auth: { hasPermi: () => false }, broadcastOfficial: async () => { sent++ } }).page
  denied.notify.audience = ['player']; await denied.submitNotify(); assert.equal(sent, 0)
})

test('没有合法数值批次号不得把业务响应当提交已确认', async () => {
  for (const data of [null, {}, { id: 7 }, true, false, 0, -1, 'unknown']) {
    const { page } = load({ broadcastOfficial: async () => ({ data }) })
    page.notify.audience = ['player']
    await page.submitNotify()
    assert.equal(page.notifyState, 'unknown')
  }
})


test('刷新只恢复本人请求编号并查询原批次，不重发、不保存文案', async () => {
  const records = new Map()
  const cache = { local: { setJSON: (key, value) => records.set(key, value), getJSON: key => records.get(key) } }
  let writes = 0; let requestId
  const first = load({ cache, broadcastOfficial: async payload => { writes++; requestId = payload.requestId; throw Error('lost') } }).page
  first.notify.audience = ['player']; first.notify.content = 'private copy'
  await first.submitNotify()
  assert.ok(requestId)
  assert.equal(JSON.stringify([...records.values()]).includes('private copy'), false)
  const restored = load({ cache, getBroadcastByRequest: async id => { assert.equal(id, requestId); return { data: 7 } } }).page
  restored.restoreNotify(); await restored.submitNotify(); await restored.recoverNotify()
  assert.equal(restored.notifyBatchId, 7); assert.equal(restored.notifyState, 'submitted')
  assert.equal(writes, 1)
  const other = load({ cache }).page; other.$store.state.user.id = 2; other.restoreNotify()
  assert.equal(other.notifyState, 'idle')
})

test('恢复查询空结果保留未知；存储失败在写请求之前阻止提交', async () => {
  let writes = 0
  const { page } = load({ cache: { local: { setJSON() { throw Error('full') } } }, broadcastOfficial: async () => writes++ })
  page.notify.audience = ['player']; await page.submitNotify()
  assert.equal(writes, 0)
  const recovered = load({ getBroadcastByRequest: async () => ({ data: null }) }).page
  recovered.notifyRequestId = 'original'; recovered.notifyState = 'unknown'; await recovered.recoverNotify()
  assert.equal(recovered.notifyState, 'unknown')
})

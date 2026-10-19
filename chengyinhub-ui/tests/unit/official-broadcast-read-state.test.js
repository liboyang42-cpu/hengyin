const test = require('node:test')
const assert = require('node:assert/strict')
const loadPage = require('./helpers/vue-options.cjs')
const file = 'business/officialevent/index.vue'
const permitted = { hasPermi: () => true }
function load(globals) {
  const result = loadPage(file, { auth: permitted, ...globals })
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
  const { page } = load({ broadcastOfficial: async () => { sent++; return { data: { id: 7 }, msg: '全部送达' } }, getBroadcastStats: async id => { readId = id; return { data: { reached: 0, clicked: 2 } } } })
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

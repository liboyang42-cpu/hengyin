const test = require('node:test')
const assert = require('node:assert/strict')
const loadPage = require('./helpers/vue-options.cjs')
const path = 'business/analytics/index.vue'

test('行为统计不把无结果、拒绝或故障变成健康零值', async () => {
  let response = {}
  const { page, source } = loadPage(path, { listFunnel: async () => response })
  await page.getList()
  assert.equal(page.loadError, true)
  assert.equal(page.loading, false)
  response = { data: { rows: [{ memberCount: 0, eventCount: 2 }] } }
  await page.getList()
  assert.equal(page.loadError, false)
  assert.equal(page.funnelList[0].memberCount, 0)
  assert.match(source, /跨日归因与退款回改口径待定/)
  assert.match(source, /支付事件不代表渠道到账/)
  assert.doesNotMatch(source, /prop="conversionRate"/)
})

test('晚到的旧日期结果不覆盖新查询', async () => {
  const resolvers = []
  const { page } = loadPage(path, { listFunnel: () => new Promise(resolve => resolvers.push(resolve)) })
  const first = page.getList(); const second = page.getList()
  resolvers[1]({ data: { rows: [{ eventCount: 2 }] } }); await second
  resolvers[0]({ data: { rows: [{ eventCount: 1 }] } }); await first
  assert.equal(page.funnelList[0].eventCount, 2)
})

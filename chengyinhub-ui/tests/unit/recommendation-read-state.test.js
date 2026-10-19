const test = require('node:test')
const assert = require('node:assert/strict')
const loadPage = require('./helpers/vue-options.cjs')
const path = 'business/recommendation/index.vue'

test('评分查询使用真实分页字段，展示已有日期并明示未知版本', async () => {
  let query
  const { page, source } = loadPage(path, { listRecommendationScores: async q => { query = q; return { rows: [{ score: 0 }], total: 1 } } })
  await page.getList()
  assert.equal(query.pageSize, 20)
  assert.equal(query.pageNum, 1)
  assert.equal(query.limit, undefined)
  assert.equal(page.scoreList[0].score, 0)
  for (const field of ['statDate', 'createTime', 'memberId', 'rankPosition']) assert.ok(source.includes(`prop="${field}"`))
  assert.match(source, /生产批次、规则版本、有效期与线上采用状态尚未提供/)
})

test('失败与畸形结果不是健康空表，重读后恢复', async () => {
  let response = {}
  const { page } = loadPage(path, { listRecommendationScores: async () => response })
  await page.getList()
  assert.equal(page.loadError, true)
  assert.equal(page.loading, false)
  response = { rows: [], total: 0 }
  await page.getList()
  assert.equal(page.loadError, false)
})

test('旧评分查询晚到不能覆盖新筛选结果', async () => {
  const resolve = []
  const { page } = loadPage(path, { listRecommendationScores: () => new Promise(r => resolve.push(r)) })
  const old = page.getList(); const current = page.getList()
  resolve[1]({ rows: [{ candidateId: 2 }], total: 1 }); await current
  resolve[0]({ rows: [{ candidateId: 1 }], total: 1 }); await old
  assert.equal(page.scoreList[0].candidateId, 2)
})

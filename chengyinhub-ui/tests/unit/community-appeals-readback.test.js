const test = require('node:test')
const assert = require('node:assert/strict')
const loadPage = require('./helpers/vue-options.cjs')
const file = 'business/audit/community-appeals.vue'
const auth = { hasPermi: () => true }
function load(globals = {}) {
  const result = loadPage(file, { cache: { local: { setJSON() {}, getJSON() { return null }, remove() {} } }, ...globals })
  result.page.$store = { state: { user: { id: 1 } } }; return result
}
const row = { id: 7, status: 'SUBMITTED', version: 0 }

test('申诉复核沿原接口、原案版本，拒绝后无终态回读不解除待确认', async () => {
  let submitted
  let calls = 0
  const { page, source } = load({ auth, listCommunityAppeals: async () => { calls++; return { data: { items: [row] } } }, reviewCommunityAppeal: async (id, payload) => { submitted = { id, payload }; throw Error('timeout') } })
  page.openReview(row); page.reason = '复核理由'
  await page.submit(); await page.submit()
  assert.equal(submitted.id, 7)
  assert.equal(submitted.payload.expectedVersion, 0)
  assert.equal(page.pending.id, '7')
  assert.equal(calls, 1)
  assert.match(source, /business:audit:review/)
})

test('查询原申诉跨游标到同一ID，其他原案终态不能解除锁', async () => {
  const queries = []
  const { page } = load({ auth, listCommunityAppeals: async query => { queries.push(query); return { data: query.cursor ? { items: [{ id: 7, status: 'REVERSED', version: 1 }] } : { items: [{ id: 8, status: 'UPHELD', version: 2 }], nextCursor: 8 } } } })
  page.pending = { id: '7', version: 0 }; page.open = true
  await page.readOriginal()
  assert.equal(queries[1].cursor, 8)
  assert.equal(page.pending, null)
  assert.equal(page.open, false)
  assert.match(page.errorText, /原处置已撤销/)
})

test('读失败与异常游标保留未知；无权限、未知版本不提交', async () => {
  let writes = 0
  const { page } = load({ auth: { hasPermi: () => false }, listCommunityAppeals: async () => { throw Error('offline') }, reviewCommunityAppeal: async () => { writes++ } })
  page.selected = row; page.reason = '说明'; await page.submit()
  assert.equal(writes, 0)
  assert.equal(page.canReview({ ...row, version: null }), false)
  page.pending = { id: '7', version: 0 }; await page.readOriginal()
  assert.equal(page.pending.id, '7')
  assert.equal(page.loading, false)
})


test('复核刷新恢复本人原案版本，未确认不接受第二次处理', async () => {
  const records = new Map()
  const cache = { local: { setJSON: (k, v) => records.set(k, v), getJSON: k => records.get(k), remove: k => records.delete(k) } }
  const first = load({ auth, cache, reviewCommunityAppeal: async () => { throw Error('lost') }, listCommunityAppeals: async () => ({ data: { items: [] } }) }).page
  first.openReview(row); first.reason = 'private reason'; await first.submit()
  assert.equal(JSON.stringify([...records.values()]).includes('private reason'), false)
  const next = load({ auth, cache }).page; next.restorePending()
  assert.equal(next.pending.id, '7'); assert.equal(next.pending.version, 0)
  next.openReview(row); assert.equal(next.selected, null)
  const other = load({ auth, cache }).page; other.$store.state.user.id = 2; other.restorePending()
  assert.equal(other.pending, null)
})

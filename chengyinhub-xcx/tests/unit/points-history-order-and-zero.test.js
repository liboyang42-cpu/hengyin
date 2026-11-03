'use strict'

// Round 7B 积分明细:同月不重复出现(按行时间分组,不看 id 顺序),变动为 0 的行全页隐藏(含空态与分页判断)。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const points = require('../../subpackageMember/points-history/rows.js')

const PAGE = path.join(__dirname, '../../subpackageMember/points-history/index.js')

test('id 小但时间更晚的行:同月只出现一次,组内新的在前', () => {
  // 接口按 id 倒序返回;id 1 是 10 月补记的行,时间比 id 3 晚
  const rows = points.normalizeRows([
    { id: 3, changePoints: 5, changeType: 1, createTime: '2026-10-02 08:00:00' },
    { id: 2, changePoints: 4, changeType: 1, createTime: '2026-09-15 09:00:00' },
    { id: 1, changePoints: 3, changeType: 1, createTime: '2026-10-20 10:00:00' },
  ])
  const groups = points.groupByMonth(rows)
  assert.deepEqual(groups.map((g) => g.label), ['2026年10月', '2026年9月'])
  assert.deepEqual(groups[0].rows.map((r) => r.id), ['1', '3'])
  assert.deepEqual(groups[1].rows.map((r) => r.id), ['2'])
})

test('同一时间保持接口顺序,读不出时间的行排最后', () => {
  const rows = points.normalizeRows([
    { id: 9, changePoints: 1, changeType: 1, createTime: '' },
    { id: 8, changePoints: 1, changeType: 1, createTime: '2026-10-01 07:00' },
    { id: 7, changePoints: 1, changeType: 1, createTime: '2026-10-01T07:00:00' },
  ])
  assert.deepEqual(points.sortByTime(rows).map((r) => r.id), ['8', '7', '9'])
  assert.deepEqual(points.groupByMonth(rows).map((g) => g.label), ['2026年10月', '时间未知'])
})

test('变动为 0 的行不显示', () => {
  const rows = points.normalizeRows([
    { id: 2, changePoints: 5, changeType: 1, createTime: '2026-10-02 08:00:00' },
    { id: 1, changePoints: 0, changeType: 1, changeReason: '注册赠送', createTime: '2026-10-01 08:00:00' },
  ])
  assert.deepEqual(points.visibleRows(rows).map((r) => r.id), ['2'])
})

function loadPage(list) {
  let cfg = null
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest(opts) { opts.success({ code: 200, data: list }) },
  })
  global.wx = {
    stopPullDownRefresh() {}, navigateBack() {}, switchTab() {},
    getSystemInfoSync: () => ({}), getWindowInfo: () => ({}), getAppBaseInfo: () => ({}),
    nextTick: (fn) => fn(),
  }
  global.getCurrentPages = () => []
  global.Page = (c) => { cfg = c }
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  const page = Object.assign({}, cfg, { data: JSON.parse(JSON.stringify(cfg.data)) })
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page.selectComponent = () => null
  page.load()
  return page
}

test('页面:全部是 0 的行 → 显示空态', () => {
  const page = loadPage([
    { id: 1, changePoints: 0, changeType: 1, changeReason: '注册赠送', createTime: '2026-10-01 08:00:00' },
  ])
  assert.equal(page.data.state, 'empty')
  assert.deepEqual(page.data.groups, [])
})

test('页面:0 行不计入分页,也不出现在任何分组里', () => {
  const list = []
  for (let i = 0; i < 20; i += 1) {
    list.push({ id: 100 - i, changePoints: 1, changeType: 1, createTime: `2026-10-${String(28 - i).padStart(2, '0')} 08:00:00` })
  }
  list.push({ id: 1, changePoints: 0, changeType: 1, changeReason: '注册赠送', createTime: '2026-09-01 08:00:00' })
  const page = loadPage(list)
  assert.equal(page.data.state, 'ready')
  assert.equal(page.data.hasMore, false)
  assert.equal(page.data.paged, false)
  const shown = page.data.groups.flatMap((g) => g.rows)
  assert.equal(shown.length, 20)
  assert.ok(shown.every((r) => r.delta !== 0))
  assert.deepEqual(page.data.groups.map((g) => g.label), ['2026年10月'])
})

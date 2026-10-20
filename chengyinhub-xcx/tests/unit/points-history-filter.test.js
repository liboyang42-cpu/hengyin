'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const points = require('../../subpackageMember/points-history/rows.js')

const rows = points.normalizeRows([
  { id: 4, changePoints: 5, changeType: 1, changeReason: '签到', afterPoints: 20, createTime: '2026-10-02 08:30:12' },
  { id: 3, changePoints: 3, changeType: 2, afterPoints: 15, createTime: '2026-10-01T07:00:00' },
  { id: 2, changePoints: 8, changeType: 1, afterPoints: 18, createTime: '2026-09-30 21:00:00' },
  { id: 1, changePoints: 2, changeType: 1, afterPoints: 10, createTime: '' },
])

test('BL-04 全部/获得/使用 端上筛选', () => {
  assert.deepEqual(points.FILTERS.map((f) => f.label), ['全部', '获得', '使用'])
  assert.deepEqual(points.filterRows(rows, 'all').map((r) => r.id), ['4', '3', '2', '1'])
  assert.deepEqual(points.filterRows(rows, 'income').map((r) => r.id), ['4', '2', '1'])
  assert.deepEqual(points.filterRows(rows, 'expense').map((r) => r.id), ['3'])
  assert.deepEqual(points.filterRows(null, 'income'), [])
})

test('BL-04 按月分组,读不出时间的归「时间未知」且不丢行', () => {
  const groups = points.groupByMonth(rows)
  assert.deepEqual(groups.map((g) => g.label), ['2026年10月', '2026年9月', '时间未知'])
  assert.deepEqual(groups.map((g) => g.rows.length), [2, 1, 1])
  assert.equal(new Set(groups.map((g) => g.key)).size, groups.length)
})

test('BL-04 页面接线:cy-tabs 切换 + 分组渲染 + 筛选空态', () => {
  const dir = path.join(__dirname, '../../subpackageMember/points-history')
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(dir, 'index.json'), 'utf8'))
  assert.equal(json.usingComponents['cy-tabs'], '/components/cy/tabs/index')
  assert.match(wxml, /<cy-tabs variant="chip" tabs="\{\{filters\}\}" active="\{\{filter\}\}" bind:change="onFilter" \/>/)
  assert.match(wxml, /wx:for="\{\{groups\}\}" wx:for-item="group" wx:key="key"/)
  assert.match(wxml, /wx:if="\{\{filterEmpty\}\}"/)
  assert.match(js, /groups: groupByMonth\(list\.slice\(0, this\._shown\)\)/)
  assert.doesNotMatch(js, /url: '\/api\/points\/list', method: 'POST', data: \{[^}]/)
})

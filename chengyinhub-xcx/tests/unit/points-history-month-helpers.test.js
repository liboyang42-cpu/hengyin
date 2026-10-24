'use strict'

// BL-145:补 #40 subpackageMember/points-history/rows.js 的 monthKey/monthLabel 单测(依 #180 TEST-GAPS.md),不改被测代码。
const test = require('node:test')
const assert = require('node:assert/strict')
const p = require('../../subpackageMember/points-history/rows.js')

test('BL-145 monthKey:取 YYYY-MM,兼容空格/T 分隔与前后空白', () => {
  assert.equal(p.monthKey('2026-10-02 08:30:12'), '2026-10')
  assert.equal(p.monthKey('2026-10-01T07:00:00'), '2026-10')
  assert.equal(p.monthKey('  2026-01-31 23:59:59 '), '2026-01')
  assert.equal(p.monthKey('2026-09'), '2026-09')
})

test('BL-145 monthKey 跨年:12 月与次年 1 月是不同分组键', () => {
  assert.equal(p.monthKey('2025-12-31 23:59:59'), '2025-12')
  assert.equal(p.monthKey('2026-01-01 00:00:00'), '2026-01')
  const rows = p.normalizeRows([
    { id: 3, changePoints: 1, changeType: 1, createTime: '2026-01-01 00:00:00' },
    { id: 2, changePoints: 1, changeType: 1, createTime: '2025-12-31 23:59:59' },
    { id: 1, changePoints: 1, changeType: 1, createTime: '2025-12-01 08:00:00' },
  ])
  const groups = p.groupByMonth(rows)
  assert.deepEqual(groups.map((g) => g.label), ['2026年1月', '2025年12月'])
  assert.deepEqual(groups.map((g) => g.rows.length), [1, 2])
})

test('BL-145 monthKey 非法/缺失时间:返回空串(归入「时间未知」)', () => {
  for (const v of [undefined, null, '', '   ', 'abc', '2026/10/02', '26-10-02', 1696000000000, {}]) {
    assert.equal(p.monthKey(v), '', JSON.stringify(v))
  }
})

test('BL-145 monthLabel:YYYY-MM → 年月,月份去前导零;非法键 → 时间未知', () => {
  assert.equal(p.monthLabel('2026-01'), '2026年1月')
  assert.equal(p.monthLabel('2025-12'), '2025年12月')
  for (const v of ['', null, undefined, '2026-1', '2026-10-02', 'unknown', '202610']) {
    assert.equal(p.monthLabel(v), '时间未知', JSON.stringify(v))
  }
})

// 月份范围校验已落地，非法月份仍须归入时间未知组。
test('BL-145 月份越界应视为非法', () => {
  for (const month of ['00', '13', '99']) {
    assert.equal(p.monthKey(`2026-${month}-01 00:00:00`), '')
    assert.equal(p.monthLabel(`2026-${month}`), '时间未知')
  }
})

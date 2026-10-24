'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { monthDay, monthDayWeek } = require('../../utils/month-day.js')

test('U-109 monthDay / monthDayWeek: 本地月日 + 周几,非法日期走调用方兜底', () => {
  const d = new Date(2026, 9, 24, 9, 30) // 本地 2026-10-24 周六
  assert.equal(monthDay(d), '10月24日')
  assert.equal(monthDayWeek(d), '10月24日 周六')
  assert.equal(monthDay(new Date(NaN)), '')
  assert.equal(monthDay(new Date(NaN), '—'), '—')
  assert.equal(monthDayWeek(new Date(NaN), '日期不可用'), '日期不可用')
  assert.equal(monthDay('2026-10-24'), '', '非 Date 不猜')
})

test('U-109 漫游页与会话详情不再内联拼「月日」', () => {
  const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
  for (const file of ['pages/roam/index.js', 'subpackageRoam/session/index.js']) {
    assert.doesNotMatch(read(file), /getMonth\(\) \+ 1\)? ?\+? ?'月'|getMonth\(\) \+ 1\}月/, file + ' 仍有内联月日格式化')
  }
})

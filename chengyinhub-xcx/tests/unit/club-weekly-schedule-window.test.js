// 每周排期期数上限:与后端 ClubEventScheduleMaterializer 的 90 天窗口同口径。
// 原来表单只校 1–64,后端在 90 天处静默停 —— 请求 64 期只落 13 期,没人告诉主理人。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const { maxWeeklyOccurrences } = require('../../pages/club/utils/weekly-schedule-window.js')

test('明天起排每周:90 天窗口内最多 13 期(与后端回归用例同一组日期)', () => {
  assert.equal(maxWeeklyOccurrences('2026-08-24', '2026-08-23'), 13)
})

test('首次日期越晚,可排期数越少;窗口最后一天仍可排 1 期', () => {
  assert.equal(maxWeeklyOccurrences('2026-08-23', '2026-08-23'), 13)
  assert.equal(maxWeeklyOccurrences('2026-10-01', '2026-08-23'), 8)
  assert.equal(maxWeeklyOccurrences('2026-11-21', '2026-08-23'), 1)
})

test('negative control:窗口外或日期非法 ⇒ 0(交给既有日期校验与后端报错,不猜上限)', () => {
  assert.equal(maxWeeklyOccurrences('2026-08-22', '2026-08-23'), 0)
  assert.equal(maxWeeklyOccurrences('2026-11-22', '2026-08-23'), 0)
  assert.equal(maxWeeklyOccurrences('', '2026-08-23'), 0)
  assert.equal(maxWeeklyOccurrences('2026/08/24', '2026-08-23'), 0)
})

test('活动运营页提交前按上限拦截每周期数', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/club/event-ops/index.js'), 'utf8')
  assert.match(src, /require\('\.\.\/utils\/weekly-schedule-window\.js'\)/)
  assert.match(src, /maxWeeklyOccurrences\(this\.data\.startDate, shanghaiToday\(\)\)/)
  assert.match(src, /count > maxWeekly/)
})

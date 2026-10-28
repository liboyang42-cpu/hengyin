const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const { buildMonthCalendar, shiftMonth } = require('../../pages/club/utils/club-event-calendar.js')
const ROOT = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

test('月历按周一开头生成完整六周，并把同日真实场次聚合到同一格', () => {
  const calendar = buildMonthCalendar('2028-02', [
    { id: 1, name: '夜走', dateText: '2028-02-29' },
    { id: 2, name: '摄影', startDate: '2028-02-29 10:00:00' },
    { id: 3, name: '跨月', dateText: '2028-03-01' },
    { id: 4, name: '坏日期', dateText: '待定' },
  ])

  assert.equal(calendar.monthKey, '2028-02')
  assert.equal(calendar.title, '2028年2月')
  assert.equal(calendar.cells.length, 42)
  assert.equal(calendar.cells[0].dateText, '2028-01-31', '2028-02-01 是周二，周一开头需补 1 月 31 日')
  const leapDay = calendar.cells.find((cell) => cell.dateText === '2028-02-29')
  assert.equal(leapDay.inMonth, true)
  assert.deepEqual(leapDay.events.map((event) => event.id), [1, 2])
  const march = calendar.cells.find((cell) => cell.dateText === '2028-03-01')
  assert.equal(march.inMonth, false)
  assert.deepEqual(march.events.map((event) => event.id), [3])
})

test('月份切换跨年稳定，非法 monthKey 明确拒绝而不是猜当前月', () => {
  assert.equal(shiftMonth('2028-01', -1), '2027-12')
  assert.equal(shiftMonth('2028-12', 1), '2029-01')
  assert.throws(() => buildMonthCalendar('2028-13', []), /月份格式不合法/)
  assert.throws(() => shiftMonth('not-a-month', 1), /月份格式不合法/)
})

// 2026-10-27 P-14 owner(真机走查 p90/p107):活动不再分「列表 / 月历」,删掉月历,直接一列活动卡。
// 原断言「原位提供列表与月历切换」按 owner 决定翻成删除契约;上面两条纯函数用例只覆盖 utils,保留。
test('俱乐部活动 tab 不再有列表/月历切换，也不再挂月历', () => {
  const logic = read('pages/club/detail/index.js')
  const view = read('pages/club/detail/index.wxml')
  assert.doesNotMatch(logic, /require\('\.\.\/utils\/club-event-calendar\.js'\)/)
  assert.doesNotMatch(logic, /onEventViewChange\s*\(|onShiftEventMonth\s*\(|loadCalendarSessions\s*\(/)
  assert.doesNotMatch(view, /eventViewTabs|eventView ===|eventCalendarCells|event-view-tabs/)
  assert.match(view, /<template wx:for="\{\{upcomingTopics\}\}"[^>]*is="clubEventCard"/)
})

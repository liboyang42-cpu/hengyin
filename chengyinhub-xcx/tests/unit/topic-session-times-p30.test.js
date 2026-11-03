// P-30(2026-11-02 第三轮):自由探索详情页场次卡写真实场次时间,不写主题票自己的窗口。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { sessionWindowsText } = require('../../pages/topic/utils/topic-detail-facts.js')

test('一场:写场次自己的开始–结束(走查:票 14:15–18:15,场次 14:05–23:59)', () => {
  assert.equal(sessionWindowsText([{ startDate: '2026-11-02 14:05:00', endDate: '2026-11-02 23:59:00' }]),
    '11-02 14:05–23:59')
})

test('多场按开始时间排,11.2 在 11.3 前', () => {
  assert.equal(sessionWindowsText([
    { startDate: '2026-11-03 10:00:00', endDate: '2026-11-03 12:00:00' },
    { startDate: '2026-11-02 14:05:00', endDate: '2026-11-02 23:59:00' },
  ]), '11-02 14:05–23:59 / 11-03 10:00–12:00')
})

test('跨日写两端;超过三场写「等 N 场」;读不出时间返回空串', () => {
  assert.equal(sessionWindowsText([{ startDate: '2026-11-02 20:00:00', endDate: '2026-11-03 02:00:00' }]),
    '11-02 20:00 ~ 11-03 02:00')
  const four = [1, 2, 3, 4].map((d) => ({ startDate: `2026-11-0${d} 10:00:00`, endDate: `2026-11-0${d} 11:00:00` }))
  assert.match(sessionWindowsText(four), / 等 4 场$/)
  assert.equal(sessionWindowsText([]), '')
  assert.equal(sessionWindowsText([{ name: '无时间' }]), '')
})

test('详情页把场次时间写进自由探索的场次卡', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/topic/index/index.js'), 'utf8')
  assert.match(js, /sessionWindowsText\(res\.data\['activityList'\]\)/)
  assert.match(js, /t\.sessionTimeText = sessionText/)
})

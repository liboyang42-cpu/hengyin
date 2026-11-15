'use strict'
// T6 mini-program / P7:成长中心今日任务、连续天数、补签卡、下一枚徽章(与 App 同源同文案)。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const board = require('../../subpackageP3/utils/daily-board.js')

const PAGE_PATH = path.resolve(__dirname, '../../subpackageP3/pages/growthcenter/index/index.js')
const read = rel => fs.readFileSync(path.resolve(__dirname, '../..', rel), 'utf8')

const daily = {
  date: '2026-11-15', completedCount: 1, allDone: false,
  missions: [
    { missionCode: 'DAILY_WALK', missionName: 'x', event: 'daily_walk', target: 1, progress: 1, completed: true, expReward: 10 },
    { missionCode: 'DAILY_REVEAL', event: 'daily_reveal', target: 20, progress: 7, completed: false, expReward: 0 },
    { missionCode: 'DAILY_PLAY', event: 'daily_play', target: 1, progress: 0, completed: false, expReward: 5 },
    { missionCode: 'CUSTOM', missionName: '自定义', event: 'other', target: 2, progress: 9, completed: false, expReward: 0 },
  ],
}
const streakData = { totalMileage: 1, currentStreak: 6, streakActiveToday: false, streakAtRisk: true, streakFreezes: 1,
  streakFreezesPending: 0, streakFreezeMax: 2, streakFreezeEveryDays: 7, nextStreakBadgeDays: 7, walkMinMeters: 500, walkMinCells: 10 }

test('任务:App 的名字、进度封顶、奖励文案、完成计数', () => {
  const m = board.shapeMissions({ daily })
  assert.equal(m.state, 'ready'); assert.equal(m.countText, '已完成 1/4')
  assert.deepEqual(m.missions.map(x => [x.name, x.progressText, x.rewardText]), [
    ['走一走', '1/1', '已得 10 探索值'],
    ['点亮迷雾', '7/20', '这项任务还没有设置奖励'],
    ['玩一关', '0/1', '奖励 5 探索值'],
    ['自定义', '2/2', '这项任务还没有设置奖励'],
  ])
})

test('任务:旧服务端没有 daily 键 → 不显示;daily=null → 暂时还用不了;空列表 → 明天再来', () => {
  assert.equal(board.shapeMissions({ growth: {} }), null)
  assert.equal(board.shapeMissions({ daily: null }).text, '每日任务暂时还用不了。')
  assert.equal(board.shapeMissions({ daily: { missions: [] } }).text, '今天没有任务，明天再来看看。')
})

test('连续天数:天数、今日状态、补签卡、规则、下一枚徽章', () => {
  const s = board.shapeStreak(streakData)
  assert.equal(s.daysText, '连续 6 天'); assert.equal(s.statusText, '今天还没打卡')
  assert.equal(s.freezesText, '补签卡：1/2 张')
  assert.deepEqual(s.lines, ['每连续 7 天得 1 张；漏掉一天会自动用掉一张。', '连续 7 天可得下一枚徽章',
    '一次漫游走满 500 米或点亮 10 个新格子就算今天来过。'])
  const done = board.shapeStreak(Object.assign({}, streakData, { streakActiveToday: true, nextStreakBadgeDays: null, streakFreezesPending: 2 }))
  assert.equal(done.statusText, '今天已打卡')
  assert.ok(done.lines.includes('连续天数徽章已经全部拿到。'))
  assert.ok(done.lines.includes('正在补上漏掉的天，要用 2 张补签卡'))
  assert.equal(board.shapeStreak({ totalMileage: 1, streakDays: 3 }), null, '旧服务端只有 streakDays:不显示')
  assert.equal(board.shapeStreak({ currentStreak: 2 }).freezesText, '补签卡暂时还用不了。')
})

function loadPage(requests) {
  global.getApp = () => ({ getUserID: () => 7, sendRequest(o) { requests.push(o) },
    getRequestErrorMessage: (r, f) => f })
  global.wx = { stopPullDownRefresh() {}, switchTab() {}, navigateBack() {} }
  let cfg
  global.Page = c => { cfg = c }
  delete require.cache[require.resolve(PAGE_PATH)]
  require(PAGE_PATH)
  const page = Object.assign({}, cfg, { data: JSON.parse(JSON.stringify(cfg.data)) })
  page.setData = patch => Object.assign(page.data, patch)
  return page
}

test('页面:成长中心读到 daily 与连续天数字段后出现今日任务;同一组三条请求,不加新接口', async () => {
  const requests = []
  const page = loadPage(requests)
  page.loadOverview()
  assert.deepEqual(requests.map(r => r.url), ['/api/growth/center', '/api/play/growth', '/api/play/my-completed'])
  requests[0].success({ code: 200, data: { growth: { levelNo: 2, expValue: 18 }, badges: [], daily } })
  requests[1].success({ code: 200, data: streakData })
  requests[2].success({ code: 200, data: [] })
  await new Promise(r => setImmediate(r))
  assert.equal(page.data.daily.show, true)
  assert.equal(page.data.daily.missions.countText, '已完成 1/4')
  assert.equal(page.data.daily.streak.daysText, '连续 6 天')
})

test('页面:旧服务端(无 daily、无 currentStreak)→ 今日任务不出现,今天的页面不变', async () => {
  const requests = []
  const page = loadPage(requests)
  page.loadOverview()
  requests[0].success({ code: 200, data: { growth: { levelNo: 2, expValue: 18 }, badges: [] } })
  requests[1].success({ code: 200, data: { totalMileage: 1.2, streakDays: 3 } })
  requests[2].success({ code: 200, data: [] })
  await new Promise(r => setImmediate(r))
  assert.equal(page.data.daily.show, false)
  assert.match(read('subpackageP3/pages/growthcenter/index/index.wxml'), /wx:if="\{\{daily\.show\}\}"/)
})

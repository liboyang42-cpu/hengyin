'use strict'

// Doc 2 · O-09:勋章墙(收藏)切后台/改手机时间后回来,点亮数与锁定数仍只来自服务端回读,
// 不因 onShow 重复叠加,也不随手机时钟变化;获得日期取服务端时间字段,不读本机时钟。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const PAGE_PATH = path.resolve(__dirname, '../../subpackageP3/pages/badge-wall/index/index.js')
let pageConfig
let requests
const realDateNow = Date.now
let nowMs = Date.UTC(2026, 9, 22, 4, 0, 0)

global.getApp = () => ({
  getUserID: () => 7,
  sendRequest(options) { requests.push(options) },
})
global.wx = {
  getWindowInfo: () => ({ statusBarHeight: 20, pixelRatio: 2 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20, pixelRatio: 2 }),
  createSelectorQuery: () => {
    const q = { in: () => q, select: () => q, fields: () => q, exec: (cb) => { if (cb) cb([null]) } }
    return q
  },
}
global.Page = (config) => { pageConfig = config }
Date.now = () => nowMs
test.after(() => { Date.now = realDateNow })

function setByPath(target, key, value) {
  const parts = key.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function loadPage() {
  delete require.cache[require.resolve(PAGE_PATH)]
  pageConfig = null
  require(PAGE_PATH)
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => setByPath(page.data, key, value))
    if (callback) callback()
  }
  return page
}

const WALL = {
  medals: [{ templateId: 18, medalName: '梧桐下的十分钟', medalImg: '/medal/wutong.png', topicId: 7,
    getTime: '2026-07-27 08:30:00', condition: '完成绑定此勋章模板的城市节点' }],
}
const WALL_V2 = {
  identity: [
    { badgeCode: 'FIRST_STEP', badgeName: '开始在场', category: 'EXPLORE', statement: '我开始在场。',
      unlocked: true, unlockTime: '2026-07-25 09:10:11', unlockHint: '完成你的第一个有效节点' },
    { badgeCode: 'ID_CITY_PROPOSAL', badgeName: '城市提案', category: 'CREATE', statement: '提交了第一个玩法。',
      unlocked: false, unlockHint: '发布第一个可被体验的城市任务' },
  ],
}

async function answerWall(page) {
  const [wall, wallV2] = requests.slice(-2)
  wall.success({ code: '200', data: WALL })
  wallV2.success({ code: '200', data: WALL_V2 })
  await new Promise((resolve) => setImmediate(resolve))
}

function snapshot(page) {
  return {
    unlocked: page.data.unlockedCount,
    locked: page.data.lockedCount,
    total: page.data.badges.length,
    times: page.data.badges.map((badge) => badge.time),
  }
}

test('O-09 勋章墙:切后台 + 改手机时间后回来,计数与获得日期不变、不重复叠加', async () => {
  requests = []
  const page = loadPage()
  page.onLoad({})
  await answerWall(page)
  const before = snapshot(page)
  assert.deepEqual(before, { unlocked: 2, locked: 1, total: 3, times: ['2026-07-25', '', '2026-07-27'] })

  page.onHide()
  nowMs += 400 * 24 * 3600 * 1000 // 后台期间手机时间被拨快一年多
  const sent = requests.length
  page.onShow()
  assert.equal(requests.length, sent, '同一账号回前台不重复拉取，读数保持服务端那一份')
  assert.deepEqual(snapshot(page), before)

  // 重新回读(例如登录态续期后)同一份服务端数据:计数相同,不叠加。
  page.loadData()
  await answerWall(page)
  assert.deepEqual(snapshot(page), before, '刷新同一份服务端事实，计数与日期都不随手机时钟变')
})

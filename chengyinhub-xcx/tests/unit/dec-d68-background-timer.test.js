'use strict'
// D-68（业主 2026-11-11）：游玩页切后台继续计时 —— 用时 = 墙钟 − 显式暂停。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createSessionClock } = require('../../utils/play-ui-contract.js')

const playJs = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8')

test('D-68 时钟按墙钟累积：进后台的那段时间照样算进去', () => {
  let now = 1_000_000
  const clock = createSessionClock(() => now)
  clock.start()
  now += 10 * 60 * 1000          // 前台 10 分钟
  now += 25 * 60 * 1000          // 切后台 25 分钟（页面没有任何 pause 调用）
  now += 5 * 60 * 1000           // 回前台再走 5 分钟
  assert.equal(clock.elapsedSeconds(), 40 * 60)
})

test('D-68 只有显式暂停才从墙钟里扣除', () => {
  let now = 0
  const clock = createSessionClock(() => now)
  clock.start()
  now += 60_000
  clock.pause()                  // 玩家主动暂停
  now += 600_000                 // 暂停期间不计
  clock.resume()
  now += 30_000
  assert.equal(clock.elapsedSeconds(), 90)
})

test('D-68 onHide 不再无条件停表：只有真正离开/未开局/预览才停', () => {
  assert.match(playJs, /const keepsRunningInBackground = this\._runStarted && !this\._playDisposed && !leaving/)
  // 停表必须挂在 guard 上，不能再出现裸的 _pauseSessionClock() 紧跟落盘
  assert.match(playJs, /if \(!keepsRunningInBackground\) this\._pauseSessionClock\(\);/)
  assert.match(playJs, /this\._persistRunSession\(keepsRunningInBackground\);/)
})

test('D-68 落盘支持 keepRunning：写快照但不停表', () => {
  assert.match(playJs, /_persistRunSession\(keepRunning\)/)
  assert.match(playJs, /if \(!keepRunning && this\._sessionClock && !this\._sessionClock\.isPaused\(\)\) this\._sessionClock\.pause\(\);/)
})

test('D-68 回前台只在「非玩家暂停、非恢复态」时续走', () => {
  assert.match(playJs, /!this\._userPaused && !this\._restoredRun\) this\._sessionClock\.resume\(\)/)
  assert.match(playJs, /this\._userPaused = true/)   // 只有 HUD 暂停才置位
})

// P-10/P-11 第四轮(rig 实测):作者「结果反馈」只在任务型主题(themeRuntimeConfig.gameplayRuntime.quests)里结算。
// 真走一遍暴露两处断头:① 开局不读个人局主题运行时(只在第一次到达的回包里带回来),任务不可见;
// ② 带玩法的 GPS 站要「先过玩法再到达」,而任务要「先到达再开始任务再玩」—— 两边互等,首站「请先开始当前任务」。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')

const PLAY = path.join(__dirname, '../../pages/play/index.js')
const SRC = fs.readFileSync(PLAY, 'utf8')

let cfg
global.getApp = () => ({ globalData: {}, getUserID: () => 1, sendRequest: () => {} })
global.Page = (c) => { cfg = c }
global.wx = {
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }),
  getStorageSync: () => null, setStorageSync() {}, removeStorageSync() {}, showToast() {}, vibrateShort() {},
  createInnerAudioContext: () => ({ play() {}, pause() {}, stop() {}, destroy() {}, onPlay() {}, onPause() {}, onStop() {}, onEnded() {}, onError() {}, onWaiting() {}, onCanplay() {} }),
}
function page(bag) {
  const m = new Module(PLAY, module); m.filename = PLAY; m.paths = Module._nodeModulePaths(path.dirname(PLAY)); m._compile(SRC, PLAY)
  const p = Object.assign({}, cfg); p.data = JSON.parse(JSON.stringify(cfg.data)); p.data.themeBag = bag; return p
}
const QUEST = (reason) => ({ sessionId: 3, quests: [{ questKey: 'q1', nodeId: 82, operation: 'QUEST_START', enabled: !reason, disabledReason: reason }] })

test('任务只差到达:带玩法的站也算「靠近即到达」;其它站、其它原因、没有任务一律不算', () => {
  assert.equal(page(QUEST('NOT_ARRIVED'))._questAwaitsArrival(82), true)
  assert.equal(page(QUEST('NOT_ARRIVED'))._questAwaitsArrival(83), false)
  assert.equal(page(QUEST(''))._questAwaitsArrival(82), false, '已到达(可开始)不再重复到达')
  assert.equal(page(QUEST('PROOF_PENDING'))._questAwaitsArrival(82), false)
  assert.equal(page({ quests: [] })._questAwaitsArrival(82), false, '没有个人局主题运行时')
  const preview = page(QUEST('NOT_ARRIVED')); preview.data.isPreview = true
  assert.equal(preview._questAwaitsArrival(82), false, '试玩不走任务')
})

test('GPS 自动到达:0/5 站带玩法时,玩法通过或任务只差到达才到达', () => {
  assert.match(SRC, /const arrivesHere = \(\(node\.vm === 0 \|\| node\.vm === 5\) && \(!node\.hasAdvanced \|\| that\._advancedReadyNodeId === node\.nodeId \|\| that\._questAwaitsArrival\(node\.nodeId\)\)\)/)
})

test('开局已建局就读一次主题运行时(任务 / 背包),不等第一次到达', () => {
  assert.match(SRC, /that\._loadPlayerGameModule\(\);\n[^\n]*\n[^\n]*\n\s+if \(routeState\.sessionId\) that\.refreshThemeBackpack\(\);/)
})

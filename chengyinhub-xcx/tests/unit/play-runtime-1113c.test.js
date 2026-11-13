'use strict'
// 2026-11-13 平台城市路线验收走查:开局 300m 闸不看首站玩法;逐字猜词解开后必须能「继续」收尾本站。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8')

test('开局 300m 定位闸与首站是否用高级玩法无关', () => {
  const play = read('pages/play/index.js')
  const tap = play.slice(play.indexOf('  onArriveTap() {'), play.indexOf('  onScanArrive() {'))
  assert.match(tap, /const needsLocation = this\._startLocationNeeded\(\);/)
  assert.doesNotMatch(tap, /_advancedReadyNodeId/, '解开玩法不能替代开局定位')
  // 没定位且需要开局定位:先申请(问过则提示已拒),不发 arrive
  assert.match(tap, /!this\.data\.gpsOk && !this\._isStoryGame\(node\) && needsLocation\) \{ if \(this\._locAsked\) this\._locationDeclinedToast\(\); else this\.requestPlayLocation\(\); return; \}/)
})

test('逐字猜词解开后在故事流里也有「继续」,并回到页面收尾本站', () => {
  const wxml = read('pages/play/components/playkit-hangman/index.wxml')
  assert.match(wxml, /<view wx:if="\{\{solved\}\}" class="g-btn" bindtap="onContinue"/)
  assert.doesNotMatch(wxml, /solved && !inline/)
  const js = read('pages/play/components/playkit-hangman/index.js')
  assert.match(js, /onContinue\(\) \{ this\.triggerEvent\(this\.data\.inline \? 'continue' : 'close'\); \}/)
  assert.match(read('pages/play/components/playkit/index.wxml'), /<cy-playkit-hangman[\s\S]*?bind:continue="onContinue" \/>/)
  const play = read('pages/play/index.js')
  assert.match(play, /\(evt\.type === 'qa' \|\| evt\.type === 'hangman'\) && this\._kit\(\)\.type === evt\.type && this\.data\.playKit\.readyForBase/)
})

test('逐字猜词组件:内嵌时「继续」抛 continue,整屏时抛 close', () => {
  let def
  global.Component = (d) => { def = d }
  global.Behavior = (d) => d
  const sub = '../../pages/play/components/playkit-hangman/index.js'
  delete require.cache[require.resolve(sub)]
  require(sub)
  delete global.Component; delete global.Behavior
  const fired = []
  const make = (inline) => ({ data: { inline }, triggerEvent: (name) => fired.push(name) })
  def.methods.onContinue.call(make(true))
  def.methods.onContinue.call(make(false))
  assert.deepEqual(fired, ['continue', 'close'])
})

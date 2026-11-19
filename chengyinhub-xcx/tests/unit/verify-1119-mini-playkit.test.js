const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')

function loadComponent(rel) {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  global.Behavior = global.Behavior || ((b) => b)
  try { delete require.cache[require.resolve(path.join(ROOT, rel))]; require(path.join(ROOT, rel)) } finally { global.Component = prev }
  return def
}

test('CG-ALL-13 题图读不出来:换成替代文字 + 重试,不留破图', () => {
  const def = loadComponent('pages/play/components/playkit-qa/index.js')
  const vm = { data: { imgFailed: false, imageUrl: 'https://x/a.png' }, setData(p) { Object.assign(this.data, p) } }
  def.methods.onQuestionImgError.call(vm)
  assert.equal(vm.data.imgFailed, true)
  def.methods.onRetryQuestionImg.call(vm)
  assert.equal(vm.data.imgFailed, false, '重试重挂 image')
  vm.data.imgFailed = true
  def.observers.imageUrl.call(vm)
  assert.equal(vm.data.imgFailed, false, '换题复位')
  const wxml = read('pages/play/components/playkit-qa/index.wxml')
  assert.match(wxml, /<image wx:if="\{\{!imgFailed\}\}" class="qa__img"[^>]*binderror="onQuestionImgError"/)
  assert.match(wxml, /\{\{imageAlt \|\| '图片没加载出来'\}\}/)
  assert.match(wxml, /bindtap="onRetryQuestionImg"[^>]*>重试</)
  assert.match(read('pages/play/components/playkit/index.wxml'), /image-alt="\{\{kit\.imageAlt\}\}"/)
})

test('CG-ALL-13 服务端给 imageAlt 才带,没给为空串(组件用通用一句)', () => {
  const now = new Date('2026-11-19T10:00:00+08:00')
  const kit = pickPlayKit({ sessionId: 's1', version: 1, playKit: { qa: { mode: 'TYPE', title: '题', imageUrl: 'https://x/a.png', imageAlt: ' 门楣上的字 ' } } }, now)
  assert.equal(kit.imageAlt, '门楣上的字')
  const bare = pickPlayKit({ sessionId: 's1', version: 1, playKit: { qa: { mode: 'TYPE', title: '题', imageUrl: 'https://x/a.png' } } }, now)
  assert.equal(bare.imageAlt, '')
})

test('CG-01-P2-5 逐字猜词题面下常驻「怎么填」入口(含 V＝ü),本站说明优先', () => {
  const { hangmanKit } = require('../../pages/play/utils/playkit-questo.js')
  assert.equal(hangmanKit({ slots: [], howto: ' 一格一个字母 ' }).howto, '一格一个字母')
  assert.equal(hangmanKit({ slots: [] }).howto, '')
  const def = loadComponent('pages/play/components/playkit-hangman/index.js')
  assert.match(def.data.DEFAULT_HOWTO, /ü 用 V 代替/)
  const vm = { data: { howtoOpen: false }, setData(p) { Object.assign(this.data, p) } }
  def.methods.onToggleHowto.call(vm)
  assert.equal(vm.data.howtoOpen, true)
  def.methods.onToggleHowto.call(vm)
  assert.equal(vm.data.howtoOpen, false, '可反复开合')
  const wxml = read('pages/play/components/playkit-hangman/index.wxml')
  assert.match(wxml, /<view class="hm__how pk-in" wx:if="\{\{!solved\}\}">/)
  assert.match(wxml, /bindtap="onToggleHowto"[^>]*>怎么填</)
  assert.match(wxml, /\{\{howto \|\| DEFAULT_HOWTO\}\}/)
  assert.match(read('pages/play/components/playkit/index.wxml'), /howto="\{\{kit\.howto\}\}"/)
})

test('CG-07-P2-2 结算总览单独数出辅助完成(跳过 / 看过答案)的节点,没有就不出格子', () => {
  const v = require('../../subpackageJourney/utils/journey-view.js')
  const r = v.reportView({ runState: 'COMPLETED', completionKind: 'ASSISTED_COMPLETION', completedNodes: 3, totalNodes: 3,
    nodes: [{ nodeId: 1, completionMode: 'SKIPPED' }, { nodeId: 2, completionMode: 'REVEALED' }, { nodeId: 3, completionMode: 'SOLVED' }] })
  assert.equal(r.result, '辅助完成')
  assert.equal(r.assistedCount, 2)
  assert.deepEqual(r.nodes.map((n) => n.mode), ['已跳过', '看过答案', ''])
  assert.equal(v.reportView({ runState: 'COMPLETED', completionKind: 'LEGITIMATE_COMPLETION' }).assistedCount, 0)
  const wxml = read('subpackageJourney/pages/settle/index.wxml')
  assert.match(wxml, /wx:if="\{\{report\.assistedCount > 0\}\}"><text class="st-two__v">\{\{report\.assistedCount\}\}<\/text><text class="st-two__l">辅助完成<\/text>/)
})

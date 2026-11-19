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

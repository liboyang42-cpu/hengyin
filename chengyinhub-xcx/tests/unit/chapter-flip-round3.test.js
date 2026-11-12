// 章节翻转过场第三轮:g20(通关彩纸撒到通关卡出来之后)。
// P-08 第三轮的「停在门上」被业主第四轮否掉,等待放行改成 play 页自己的一屏(play-chapter-hold-screen-p08-r4.test.js)。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const DIR = path.join(ROOT, 'pages/play/components/chapter-flip')

function load() {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  delete require.cache[require.resolve(path.join(DIR, 'index.js'))]
  require(path.join(DIR, 'index.js'))
  global.Component = prev
  return def
}

function instance(def, props) {
  const timers = []
  const inst = {
    data: Object.assign({}, def.data, { mode: 'gate', faces: [], endInfo: null, info: { title: '钟楼背后' }, reducedMotion: false }, props),
    events: [],
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name) { this.events.push(name) },
  }
  Object.keys(def.methods).forEach((k) => { inst[k] = def.methods[k].bind(inst) })
  // 计时器只记下来,测试里手动推进
  inst._at = (fn) => { timers.push(fn) }
  inst._decode = () => {}
  inst._showScene = () => {}
  inst._scatter = () => {}
  inst._turnTo = () => {}
  inst._clear = () => {}
  inst.flush = () => { while (timers.length) timers.shift()() }
  return inst
}

test('翻转门播完自己进(第四轮:held 已从组件退场,等待放行改在 play 页)', () => {
  const def = load()
  const c = instance(def)
  const real = global.setTimeout, held = []
  global.setTimeout = (fn, ms) => { held.push({ fn, ms }); return held.length }
  try { c._gate(); c.flush(); while (held.length) held.shift().fn() } finally { global.setTimeout = real }
  assert.deepEqual(c.events, ['continue'])
})

// 章节翻转过场第三轮:P-08(等队长放行停在门上)与 g20(通关彩纸撒到通关卡出来之后)。
// P-08 第三轮:带领场被挡住的章停在翻转门上 —— 底部「等队长放行」代替「轻点跳过」,
// 不自己进正文、轻点也不进;held 变 false 后门自己发 continue。
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
    data: Object.assign({}, def.data, { mode: 'gate', faces: [], endInfo: null, info: { title: '钟楼背后' }, reducedMotion: false, held: false }, props),
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

test('held:翻转播完不自己进正文,轻点也不进', () => {
  const def = load()
  const c = instance(def, { held: true })
  c._gate()
  c.flush()
  assert.equal(c.data.out, false)
  c.onTap()
  c.flush()
  assert.deepEqual(c.events, [])
})

test('held → 放行:门自己淡出并发 continue', () => {
  const def = load()
  const c = instance(def, { held: true })
  c._gate()
  c.flush()
  c.data.held = false
  def.observers.held.call(c, false)
  c.flush()
  assert.equal(c.data.out, true)
  assert.deepEqual(c.events, ['continue'])
})

test('翻转还没播完就放行:按原计时自己进,不提前', () => {
  const def = load()
  const c = instance(def, { held: true })
  c._gate()
  c.data.held = false
  def.observers.held.call(c, false)
  assert.deepEqual(c.events, [])
  c.flush()
  assert.deepEqual(c.events, ['continue'])
})

test('不带 held 的门照旧:播完自己进', () => {
  const def = load()
  const c = instance(def)
  c._gate()
  c.flush()
  assert.deepEqual(c.events, ['continue'])
})

test('底部提示:held 时写「等队长放行」,不写「轻点跳过」', () => {
  const wxml = fs.readFileSync(path.join(DIR, 'index.wxml'), 'utf8')
  assert.match(wxml, /<view class="cf__hint" wx:if="\{\{mode === 'gate'\}\}">\{\{held \? '等队长放行' : '轻点跳过'\}\}<\/view>/)
})

// g20 第三轮(业主):通关卡留着,彩纸要回来 —— 彩纸要一直撒到通关卡立起来之后。
test('g20:通关炸开后彩纸撒到通关卡出来之后,不是卡一出来就停', () => {
  const def = load()
  const c = instance(def, { mode: 'finale' })
  const at = []
  c._at = (fn, ms) => { at.push({ fn, ms }) }
  c._cx = 100; c._cy = 100; c._S = 1; c._scene = {}
  c._makeShards = () => {}; c._burstConfetti = () => {}; c._assembleLogo = () => {}
  c._waves = []; c._sparks = []
  const t0 = Date.now()
  def.methods._explode.call(c)
  const cardAt = Math.max(...at.map(a => a.ms))
  assert.ok(c._confettiUntil - t0 >= cardAt + 1500, '卡出来以后至少还撒 1.5 秒')
  at.forEach(a => a.fn())
  assert.equal(c.data.cta, true)
})

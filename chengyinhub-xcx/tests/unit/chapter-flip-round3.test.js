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

test('翻转门 D-46 点了才能进(取代原「播完自己进」;held 已从组件退场,等待放行改在 play 页)', () => {
  const def = load()
  const c = instance(def)
  c._gate()
  c.flush()
  assert.deepEqual(c.events, [], 'D-46:不点不进')
  c.onTap()
  c.flush()
  assert.deepEqual(c.events, ['continue'])
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

test('章节完成卡第一帧即显示真用时与完成数，不从零计数', () => {
  const c = instance(load(), { endInfo: { number: '01', title: '报到与校准', rows: [{ k: '用时', v: '02:15' }, { k: '坐标', v: '2/2' }] } });
  c._gate();
  assert.deepEqual(c.data.endStats.map(row => row.shown), ['02:15', '2/2']);
  c.flush();
  assert.deepEqual(c.data.endStats.map(row => row.shown), ['02:15', '2/2']);
});

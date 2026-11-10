// r4-motion 地基契约(2026-11-10,MOTION.md §2 / M14 / §5.5 phase 0)
// 钉三件事:
//   ① tokens.wxss 的玩家侧 page{} 有 §2 的全部时长 / 缓动 / 位移 / 投影 token,商家浅色块与 merchant-light.wxss 镜像了浅色投影值;
//   ② utils/motion.js 的四个具名触感档位:强度固定、答错是两下(medium → 120ms → light)、没有 wx.vibrateShort 时空操作、不读减动效;
//   ③ settle() 在勾出现的那一刻给一次 confirm 触感(减动效下不出勾也就不震);wizard-morph 读减动效。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const tokens = read('style/tokens.wxss')

function block(src, startRe) {
  const m = src.match(startRe)
  assert.ok(m, '找不到块 ' + startRe)
  const i = m.index
  const j = src.indexOf('\n}', i)
  return src.slice(i, j)
}
function val(name, src) {
  const m = src.match(new RegExp(name.replace(/-/g, '\\-') + ':\\s*([^;]+);'))
  assert.ok(m, '缺 token ' + name)
  return m[1].replace(/\/\*.*?\*\//g, '').replace(/\s+/g, '').trim()
}

const player = block(tokens, /^page \{/m)

test('§2.1 时长 token 齐全且值如 MOTION.md', () => {
  const want = {
    '--cy-motion-micro': '100ms', '--cy-motion-reveal': '450ms', '--cy-motion-roll': '520ms',
    '--cy-motion-curl': '420ms', '--cy-motion-flip': '480ms', '--cy-motion-dissolve': '900ms',
    '--cy-motion-stagger': '40ms', '--cy-motion-stagger-hero': '90ms', '--cy-motion-hold': '720ms',
    '--cy-motion-ambient': '6000ms',
  }
  for (const [k, v] of Object.entries(want)) assert.equal(val(k, player), v, k)
  // hold 必须与 morph-success 的 HOLD_MS 同值(JS 镜像)
  assert.equal(require('../../utils/morph-success.js').HOLD_MS + 'ms', val('--cy-motion-hold', player))
})

test('§2.2 缓动 token 齐全;旧的 spring / out / accelerate / page-rise 不变', () => {
  assert.equal(val('--cy-ease-settle', player), 'cubic-bezier(.16,1,.3,1)')
  assert.equal(val('--cy-ease-spring-soft', player), 'cubic-bezier(.34,1.3,.64,1)')
  assert.equal(val('--cy-ease-flip', player), 'cubic-bezier(.45,0,.2,1)')
  assert.equal(val('--cy-ease-roll', player), 'cubic-bezier(.2,.9,.2,1)')
  assert.equal(val('--cy-ease-spring', player), 'cubic-bezier(.2,1.4,.5,1)')
  assert.equal(val('--cy-ease-out', player), 'cubic-bezier(.22,.61,.36,1)')
  assert.equal(val('--cy-ease-page-rise', player), 'cubic-bezier(.2,.82,.2,1)')
})

test('§2.3 位移 / 缩放 token 与 §2.4 投影:玩家暗底一组、商家浅色一组,merchant-light.wxss 镜像浅色值', () => {
  const want = {
    '--cy-motion-rise-sm': '16rpx', '--cy-motion-rise-md': '40rpx', '--cy-motion-rise-lg': '160rpx',
    '--cy-motion-scale-under': '.94', '--cy-motion-shift-under': '-10px', '--cy-motion-scale-lift': '1.02',
    '--cy-motion-scale-press': '.97', '--cy-motion-tilt-max': '8deg', '--cy-motion-stack-rot': '-6deg',
    '--cy-motion-stack-rot-mid': '4deg',
    '--cy-shadow-lift': '024rpx48rpxrgba(0,0,0,.45)', '--cy-shadow-stack': '012rpx32rpxrgba(0,0,0,.35)',
  }
  for (const [k, v] of Object.entries(want)) assert.equal(val(k, player), v, k)
  const light = block(tokens, /^page\.theme-light,/m)
  assert.equal(val('--cy-shadow-lift', light), '016rpx40rpxrgba(0,0,0,.14)')
  assert.equal(val('--cy-shadow-stack', light), '08rpx24rpxrgba(0,0,0,.10)')
  const ml = block(read('style/merchant-light.wxss'), /^page \{/m)
  assert.equal(val('--cy-shadow-lift', ml), val('--cy-shadow-lift', light), 'merchant-light 镜像漂移')
  assert.equal(val('--cy-shadow-stack', ml), val('--cy-shadow-stack', light), 'merchant-light 镜像漂移')
})

test('M14 触感四档:强度固定;答错两下间隔 120ms;没有 vibrateShort 时空操作;不读减动效开关', () => {
  const motion = require('../../utils/motion.js')
  const calls = []
  const haptics = { vibrateShort: (o) => { calls.push(o.type); return { catch() {} } } }
  assert.equal(motion.hapticTap({ haptics }), true)
  assert.equal(motion.hapticConfirm({ haptics }), true)
  assert.equal(motion.hapticHit({ haptics }), true)
  assert.deepEqual(calls, ['light', 'medium', 'heavy'])
  calls.length = 0
  const timers = []
  assert.equal(motion.hapticMiss({ haptics, setTimeout: (fn, ms) => timers.push({ fn, ms }) }), true)
  assert.deepEqual(calls, ['medium'], '答错先一下 medium')
  assert.equal(timers.length, 1)
  assert.equal(timers[0].ms, 120)
  timers[0].fn()
  assert.deepEqual(calls, ['medium', 'light'], '120ms 后再一下 light')
  // 没有震动源:空操作,返回 false,不抛
  for (const fn of ['hapticTap', 'hapticConfirm', 'hapticHit', 'hapticMiss']) assert.equal(motion[fn]({ haptics: {} }), false, fn)
  // 机型 reject / 抛错不掀翻调用方
  assert.equal(motion.hapticTap({ haptics: { vibrateShort() { throw new Error('nope') } } }), false)
  // 不读减动效:传 reducedMotion:true 也照震(这是视觉设置,MOTION.md §1.7)
  calls.length = 0
  assert.equal(motion.hapticConfirm({ haptics, reducedMotion: true }), true)
  assert.deepEqual(calls, ['medium'])
  // 老接口保持原门控
  assert.equal(motion.haptic({ haptics, reducedMotion: true }), false)
})

test('M7:settle() 在勾出现时给一次 confirm 触感,减动效下不出勾也不震;cancel 后不再回调', () => {
  const morph = require('../../utils/morph-success.js')
  const calls = []
  const haptics = { vibrateShort: (o) => { calls.push(o.type) } }
  const writes = []
  const vm = { setData: (p) => writes.push(p) }
  const realSet = global.setTimeout
  const timers = []
  global.setTimeout = (fn, ms) => { timers.push({ fn, ms }); return timers.length }
  try {
    assert.equal(morph.settle(vm, 'xDone', () => writes.push('then'), { reduced: false, with: { busy: false }, haptics }), true)
    assert.deepEqual(calls, ['medium'], '勾一出现就震一次 medium')
    assert.deepEqual(writes[0], { busy: false, xDone: true })
    assert.equal(timers[0].ms, morph.HOLD_MS)
    timers[0].fn()
    assert.deepEqual(writes.slice(1), ['then', { xDone: false }])
    calls.length = 0; writes.length = 0
    assert.equal(morph.settle(vm, 'xDone', () => writes.push('then'), { reduced: true, haptics }), false)
    assert.deepEqual(calls, [], '减动效:没有勾,也就没有那一下')
    assert.deepEqual(writes, ['then'])
  } finally {
    global.setTimeout = realSet
  }
})

test('wizard-morph 读减动效开关:开着时直接切到表单态,不跑形变', () => {
  const src = read('utils/wizard-morph.js')
  assert.match(src, /readReducedMotion/, 'wizard-morph 必须读 motion-preference 的开关')
})

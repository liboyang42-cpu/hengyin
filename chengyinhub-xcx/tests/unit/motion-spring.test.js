// utils/motion-spring.js 单测(r4-motion,2026-11-10,MOTION.md §5.4)
// 只测性质:step() 收敛、不发散、落定后钉在目标;releaseToTransition() 的时长边界与快慢关系;
// curve() 的四条曲线与 tokens.wxss 的缓动/时长 token 同值(JS 镜像不许漂移)。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const spring = require('../../utils/motion-spring.js')
const { PRESETS, curve, step, releaseToTransition } = spring
const ROOT = path.resolve(__dirname, '../..')
const tokens = fs.readFileSync(path.join(ROOT, 'style/tokens.wxss'), 'utf8')

function tokenValue(name) {
  const m = tokens.match(new RegExp(name.replace(/-/g, '\\-') + ':\\s*([^;]+);'))
  assert.ok(m, '缺 token ' + name)
  return m[1].replace(/\s+/g, '').trim()
}

function runToRest(state, dt, maxFrames) {
  let s = state
  let frames = 0
  while (!s.settled && frames < maxFrames) { s = step(s, dt); frames++ }
  return { state: s, frames }
}

test('step:四个预设都在 2s 内收敛到目标,落定后 x 钉在 target、v 归零', () => {
  for (const name of Object.keys(PRESETS)) {
    const { state, frames } = runToRest({ x: 0, v: 0, target: 120, preset: name }, 16, 125)
    assert.ok(state.settled, `${name} 应在 2s(125 帧 × 16ms)内落定,实际跑满未落定`)
    assert.equal(state.x, 120, `${name} 落定后 x 必须精确等于 target`)
    assert.equal(state.v, 0)
    assert.ok(frames > 1, `${name} 不是一帧到位(那就不是弹簧了)`)
  }
})

test('step:firm 预设会过冲再回来,snappy 几乎不过冲(两条曲线的手感差别就在这里)', () => {
  function maxOvershoot(name) {
    let s = { x: 0, v: 0, target: 100, preset: name }
    let max = 0
    for (let i = 0; i < 200 && !s.settled; i++) { s = step(s, 16); if (s.x - 100 > max) max = s.x - 100 }
    return max
  }
  assert.ok(maxOvershoot('firm') > 2, 'firm 应明显过冲')
  assert.ok(maxOvershoot('snappy') < 1, 'snappy 应几乎不过冲')
})

test('step:大步长(100ms)不发散,结果与 16ms 小步长同向收敛;dt 非法时按一帧处理', () => {
  const big = runToRest({ x: 0, v: 0, target: 50, preset: 'settle' }, 100, 60)
  assert.ok(big.state.settled, '100ms 步长也要落定(内部拆子步)')
  const bad = step({ x: 0, v: 0, target: 50, preset: 'settle' }, NaN)
  assert.ok(bad.x > 0 && bad.x < 50, 'dt 非法时按默认一帧积分,x 向目标走一点')
})

test('step:纯函数——不改入参;带初速度(松手一甩)也能收敛', () => {
  const s0 = { x: 10, v: 1.5, target: 0, preset: 'soft' }
  const copy = JSON.stringify(s0)
  const r = runToRest(s0, 16, 200)
  assert.equal(JSON.stringify(s0), copy, '入参不许被改')
  assert.ok(r.state.settled)
  assert.equal(r.state.x, 0)
})

test('releaseToTransition:时长夹在 [dur/1.4, dur/0.6],快甩比慢放落得快,到位时时长为 0', () => {
  for (const name of Object.keys(PRESETS)) {
    const dur = PRESETS[name].dur
    const slow = releaseToTransition(0, 0, 100, name)
    const fast = releaseToTransition(0, 9, 100, name)
    const mid = releaseToTransition(0, 2, 100, name)
    assert.equal(slow.duration, Math.round(dur / 0.6), `${name} 慢放落在上界`)
    assert.equal(fast.duration, Math.round(dur / 1.4), `${name} 快甩落在下界`)
    assert.equal(mid.duration, dur, `${name} v=2 px/ms 正好是预设时长`)
    assert.ok(fast.duration < mid.duration && mid.duration < slow.duration, `${name} 越快越短`)
    assert.equal(slow.easing, PRESETS[name].ease)
    assert.equal(slow.distance, 100)
    assert.equal(releaseToTransition(100, 3, 100, name).duration, 0, '已经在目标上就不要过渡')
  }
  // 负速度(反向甩)按绝对值算
  assert.equal(releaseToTransition(0, -9, 100, 'snappy').duration, Math.round(320 / 1.4))
})

test('curve:未知预设回落到 settle;四条曲线与 tokens.wxss 的缓动 token 同值', () => {
  assert.deepEqual(curve('nope'), { duration: 450, easing: PRESETS.settle.ease })
  assert.equal(curve('snappy').easing, tokenValue('--cy-ease-roll'))
  assert.equal(curve('soft').easing, tokenValue('--cy-ease-spring-soft'))
  assert.equal(curve('firm').easing, tokenValue('--cy-ease-spring'))
  assert.equal(curve('settle').easing, tokenValue('--cy-ease-settle'))
  assert.equal(curve('settle').duration + 'ms', tokenValue('--cy-motion-reveal'), 'settle 时长 = --cy-motion-reveal')
})

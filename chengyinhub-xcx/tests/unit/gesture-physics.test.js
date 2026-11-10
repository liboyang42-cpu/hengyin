// qx-u1(2026-11-10):手势物理 —— 阈值 / 阻尼 / 档位 / 速度
//
// 业主定的参数(LANES.md「qx-u1」):下滑速度 > 0.5 px/ms 或位移 > 面板高 30% 关闭;否则吸附最近档;
// 越过最高档往上拽,位移 × 0.25 并弹回;减动效下没有阻尼。侧滑返回用同一套阈值。
const assert = require('node:assert/strict')
const test = require('node:test')
const P = require('../../utils/gesture-physics.js')

test('参数就是业主给的那几个数', () => {
  assert.equal(P.DISMISS_VELOCITY, 0.5)
  assert.equal(P.DISMISS_RATIO, 0.3)
  assert.equal(P.RUBBER, 0.25)
})

test('阻尼:最高档之上只走 25%,之下 1:1;减动效顶住不动', () => {
  assert.equal(P.resist(120, 0, false), 120)
  assert.equal(P.resist(0, 0, false), 0)
  assert.equal(P.resist(-100, 0, false), -25)
  assert.equal(P.resist(-100, 0, true), 0)
  // 停在低档(base 200)时往上拽:仍按「最高档 = 0」算,越过 0 的部分才打折
  assert.equal(P.resist(-40, 0, false), -10)
  assert.equal(P.resist(50, 0, false), 50)
})

test('速度:只看末段;没有上一采样点时为 0;dt 至少按 1ms 算', () => {
  assert.equal(P.velocity(null, null, 100, 1000), 0)
  assert.equal(P.velocity(100, 1000, 130, 1016), 30 / 16)
  assert.equal(P.velocity(100, 1000, 110, 1000), 10)
  assert.ok(P.velocity(200, 0, 180, 10) < 0, '向上为负')
})

test('档位偏移:最高档恒为 0;低档 = 面板高 - 比例 × 窗口高;非法档忽略、去重、升序', () => {
  assert.deepEqual(P.detentOffsets('', 600, 800), [0])
  assert.deepEqual(P.detentOffsets('full,half', 640, 800), [0, 160])      // 640 - 0.6*800
  assert.deepEqual(P.detentOffsets('half,peek,bogus', 640, 800), [0, 160, 400])
  assert.deepEqual(P.detentOffsets('full', 640, 800), [0], '面板本身就是 full 高,没有更低的档')
  assert.deepEqual(P.detentOffsets('half', 0, 800), [0], '没量到面板高时只有一档')
})

test('单档弹层:慢拖超过 30% 关,没超过吸回 0', () => {
  assert.deepEqual(P.sheetRelease(181, 0.1, 600, [0]), { action: 'dismiss' })
  assert.deepEqual(P.sheetRelease(179, 0.1, 600, [0]), { action: 'snap', to: 0 })
})

test('单档弹层:位移很小但甩得快 ⇒ 关;速度刚好 0.5 不算', () => {
  assert.deepEqual(P.sheetRelease(40, 0.51, 600, [0]), { action: 'dismiss' })
  assert.deepEqual(P.sheetRelease(40, 0.5, 600, [0]), { action: 'snap', to: 0 })
})

test('往上拽过头(偏移为负)哪怕向下甩也不关,弹回 0', () => {
  assert.deepEqual(P.sheetRelease(-20, 1.2, 600, [0]), { action: 'snap', to: 0 })
})

test('向上甩回最高档', () => {
  assert.deepEqual(P.sheetRelease(120, -0.8, 600, [0]), { action: 'snap', to: 0 })
})

test('量不到面板高时位移闸失效,只看速度 —— 慢拖到底也不误关', () => {
  assert.deepEqual(P.sheetRelease(900, 0.1, 0, [0]), { action: 'snap', to: 0 })
  assert.deepEqual(P.sheetRelease(30, 0.9, 0, [0]), { action: 'dismiss' })
})

test('多档:慢放落到最近的档;向下甩落到下一档;在最低档之下再甩才关', () => {
  const det = [0, 160, 400]
  assert.deepEqual(P.sheetRelease(70, 0, 640, det), { action: 'snap', to: 0 })
  assert.deepEqual(P.sheetRelease(90, 0, 640, det), { action: 'snap', to: 160 })
  assert.deepEqual(P.sheetRelease(20, 0.9, 640, det), { action: 'snap', to: 160 })
  assert.deepEqual(P.sheetRelease(200, 0.9, 640, det), { action: 'snap', to: 400 })
  assert.deepEqual(P.sheetRelease(410, 0.9, 640, det), { action: 'dismiss' })
  assert.deepEqual(P.sheetRelease(300, -0.9, 640, det), { action: 'snap', to: 160 })
  // 最低档时可见 240px,再往下 30%(72px)才算关
  assert.deepEqual(P.sheetRelease(473, 0.1, 640, det), { action: 'dismiss' })
  assert.deepEqual(P.sheetRelease(472, 0.1, 640, det), { action: 'snap', to: 400 })
})

test('侧滑返回:屏宽 30% 或速度 > 0.5 关;往左甩不算', () => {
  assert.equal(P.swipeRelease(118, 0.1, 390), true)
  assert.equal(P.swipeRelease(116, 0.1, 390), false)
  assert.equal(P.swipeRelease(40, 0.7, 390), true)
  assert.equal(P.swipeRelease(0, 0.7, 390), false)
  assert.equal(P.swipeRelease(200, 0.1, 0), false, '量不到屏宽时只看速度')
})

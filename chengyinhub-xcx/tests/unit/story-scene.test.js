// 故事流背景随剧情换景(2026-09-23 用户裁决「优化顺序第 3 项」):照原型 cosmos 的场景参数,
// 场景由氛围决定,梦块换成「苏醒」那一景;切换是逐帧插值过去,不是硬切。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const scene = require('../../pages/play/utils/story-scene.js')

test('场景跟氛围走,梦块换苏醒;不认识的回城市', () => {
  assert.equal(scene.sceneFor('atmosphere--yellow'), 'afternoon')
  assert.equal(scene.sceneFor('WHITE'), 'office')
  assert.equal(scene.sceneFor('', true), 'awake')
  assert.equal(scene.sceneFor('whatever'), 'city')
  for (const k of ['void', 'awake', 'city', 'afternoon', 'hush', 'grown', 'office']) assert.ok(scene.SCENES[k], k)
})

test('换景是插值过去:一步只走一小段,足够多步后到位;数组参数(颜色)逐分量插', () => {
  let cur = scene.initial('city')
  const target = scene.SCENES.afternoon
  cur = scene.step(cur, 'afternoon', 0.1)
  assert.ok(cur.n > scene.SCENES.city.n && cur.n < target.n, '一步就到位 = 硬切')
  for (let i = 0; i < 200; i++) cur = scene.step(cur, 'afternoon', 0.1)
  assert.ok(Math.abs(cur.n - target.n) < 1e-3)
  assert.ok(Math.abs(cur.col[0] - target.col[0]) < 0.5)
  assert.equal(scene.step(cur, 'afternoon', 1).n, target.n, '减动效:k=1 直接到位')
})

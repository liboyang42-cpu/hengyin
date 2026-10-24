'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { SCALE_HALF_VIEW_M, BEYOND_TABLE_HALF_VIEW_M, halfViewM, scaleForHalfViewM } = require('../../utils/map-scale.js')

// 收口前 pages/roam/index.js 的两段 if 阶梯原文冻结,作对照 oracle。
function legacyHalfViewM(scale) {
  if (scale >= 20) return 35
  if (scale >= 19) return 70
  if (scale >= 18) return 140
  if (scale >= 17) return 280
  if (scale >= 16) return 560
  if (scale >= 15) return 1100
  if (scale >= 14) return 2200
  if (scale >= 13) return 4400
  if (scale >= 12) return 8800
  return 12000
}
function legacyScaleForHalfViewM(halfM) {
  if (halfM <= 35) return 20
  if (halfM <= 70) return 19
  if (halfM <= 140) return 18
  if (halfM <= 280) return 17
  if (halfM <= 560) return 16
  if (halfM <= 1100) return 15
  if (halfM <= 2200) return 14
  if (halfM <= 4400) return 13
  if (halfM <= 8800) return 12
  return 14 // SCALE_FAR
}

test('U-090: 阶梯表 scale 严格降序、视距严格递增,且每级约为上一级 2 倍', () => {
  for (let i = 1; i < SCALE_HALF_VIEW_M.length; i++) {
    const [s0, h0] = SCALE_HALF_VIEW_M[i - 1], [s1, h1] = SCALE_HALF_VIEW_M[i]
    assert.equal(s1, s0 - 1)
    assert.ok(h1 > h0)
    assert.ok(h1 / h0 >= 1.9 && h1 / h0 <= 2.1, s1 + ':' + h1 / h0)
  }
  assert.ok(BEYOND_TABLE_HALF_VIEW_M > SCALE_HALF_VIEW_M[SCALE_HALF_VIEW_M.length - 1][1])
})

test('U-090: 与收口前两段 if 阶梯逐值一致', () => {
  for (let scale = 3; scale <= 22; scale += 0.25) assert.equal(halfViewM(scale), legacyHalfViewM(scale), 'scale=' + scale)
  for (let half = 0; half <= 20000; half += 5) assert.equal(scaleForHalfViewM(half, 14), legacyScaleForHalfViewM(half), 'half=' + half)
})

test('U-090: 两个方向互逆 —— 表内每级 scale 的视距反查回同一 scale', () => {
  for (const [s, half] of SCALE_HALF_VIEW_M) {
    assert.equal(scaleForHalfViewM(halfViewM(s), 14), s)
    assert.ok(halfViewM(scaleForHalfViewM(half, 14)) >= half, '反查得到的 scale 必须装得下所需视距')
  }
})

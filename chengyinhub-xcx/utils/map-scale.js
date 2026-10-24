'use strict'

// 微信 <map> scale ↔ 视距(屏幕中心到边缘的米数)阶梯表(C:U-090)。
// 原先写死在 pages/roam/index.js 的 _halfViewM / _scaleForHalfViewM 两处 if 阶梯里,两边要手工保持互逆。
// 每升一级 scale 视距减半(微信地图按 2 的幂缩放);表必须按 scale 降序、视距严格递增,由单测钉住。
const SCALE_HALF_VIEW_M = Object.freeze([
  [20, 35], [19, 70], [18, 140], [17, 280], [16, 560],
  [15, 1100], [14, 2200], [13, 4400], [12, 8800],
])
const BEYOND_TABLE_HALF_VIEW_M = 12000 // scale < 12 时的视距

/** scale → 视距(米) */
function halfViewM(scale) {
  for (const [s, half] of SCALE_HALF_VIEW_M) if (scale >= s) return half
  return BEYOND_TABLE_HALF_VIEW_M
}

/** 视距(米) → 能装下它的最大 scale;超出表尾返回 farScale(调用方的最远视角)。 */
function scaleForHalfViewM(halfM, farScale) {
  for (const [s, half] of SCALE_HALF_VIEW_M) if (halfM <= half) return s
  return farScale
}

module.exports = { SCALE_HALF_VIEW_M, BEYOND_TABLE_HALF_VIEW_M, halfViewM, scaleForHalfViewM }

'use strict'
// 「我走过的路」:宽半透明垫一层 + 窄亮线,原生 map 的 polyline 没有阴影,发光只能这样叠。
// 漫游与主题游玩共用。点数受控:已稳定的前段抽稀一次缓存,只有最近的尾巴原样追加,
// 每次 setData 最多约 MAX_BASE + TAIL 个点,不随走路时长无限变长。
const { simplifyTrack } = require('./roam-track-simplify.js')

const MAX_BASE = 160
const TAIL = 48

function createRouteLine(style) {
  const s = style || {}
  let baseLen = 0, base = [], ref = null
  function points(track) {
    const list = Array.isArray(track) ? track : []
    if (list !== ref || list.length < baseLen) { baseLen = 0; base = []; ref = list }
    if (list.length - baseLen > TAIL) {
      base = simplifyTrack(list, { maxPoints: MAX_BASE, epsilonMeters: 4 })
      baseLen = list.length
    }
    return base.concat(list.slice(baseLen)).filter((q) => q && Number.isFinite(Number(q.lat)) && Number.isFinite(Number(q.lng)))
      .map((q) => ({ latitude: Number(q.lat), longitude: Number(q.lng) }))
  }
  return {
    lines(track) {
      const pts = points(track)
      if (pts.length < 2) return []
      return [
        { points: pts, color: s.glow || '#FFFFFF38', width: s.glowWidth || 14, borderWidth: 0 }, /* ds-ok 原生 polyline 只吃字面色 */
        { points: pts, color: s.core || '#FFFFFFF2', width: s.coreWidth || 4, borderColor: '#00000052', borderWidth: 1 }, /* ds-ok 原生 polyline 只吃字面色 */
      ]
    },
    reset() { baseLen = 0; base = []; ref = null },
  }
}

module.exports = { createRouteLine, MAX_BASE, TAIL }

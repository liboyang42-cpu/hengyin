'use strict'
// 入场码点阵(2026-10-29 业主:码要居中、码心 logo 用黑色、可以有彩色动效,参考 spottedinprod 那段圆点码)。
// 码的内容仍由后端出图(H 级纠错 + 中心 25% 留白,后端已逐个掩码验证能读回);
// 这里只把后端那张 PNG 按模块中心取样还原成矩阵,再换成圆点重画 —— 取样点与解码器读的是同一组点,
// 所以点阵和原图读出来是同一个码。还原不出合法尺寸(21 + 4k)时返回 null,页面退回显示原图。

const DARK = 128

function isDark(px, w, x, y) {
  const i = (y * w + x) * 4
  return (px[i] * 299 + px[i + 1] * 587 + px[i + 2] * 114) / 1000 < DARK && px[i + 3] > 127
}

// px: RGBA 像素数组(长度 w*h*4)。返回 { n, cells: Uint8Array(n*n), hole: 中心全白方块的边长(模块数) } 或 null
function matrixFromPixels(px, w, h) {
  let minX = w, minY = h, maxX = -1, maxY = -1
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (!isDark(px, w, x, y)) continue
    if (x < minX) minX = x; if (x > maxX) maxX = x
    if (y < minY) minY = y; if (y > maxY) maxY = y
  }
  if (maxX < 0) return null
  // 左上定位块最上一行:从左边起连续的深色正好 7 个模块宽
  let run = 0
  while (minX + run <= maxX && isDark(px, w, minX + run, minY)) run++
  const span = maxX - minX + 1
  const n = Math.round(span / (run / 7))
  if (n < 21 || n > 177 || (n - 17) % 4 !== 0 || Math.abs((maxY - minY + 1) - span) > span / n) return null
  const m = span / n
  const cells = new Uint8Array(n * n)
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
    cells[r * n + c] = isDark(px, w, Math.min(w - 1, Math.floor(minX + (c + 0.5) * m)), Math.min(h - 1, Math.floor(minY + (r + 0.5) * m))) ? 1 : 0
  }
  // 三个定位块必须是 7×7 的「回」字,否则当作没认出来
  const finder = (r0, c0) => {
    for (let r = 0; r < 7; r++) for (let c = 0; c < 7; c++) {
      const ring = r === 0 || r === 6 || c === 0 || c === 6
      const core = r >= 2 && r <= 4 && c >= 2 && c <= 4
      if (cells[(r0 + r) * n + c0 + c] !== (ring || core ? 1 : 0)) return false
    }
    return true
  }
  if (!finder(0, 0) || !finder(0, n - 7) || !finder(n - 7, 0)) return null
  // 中心留白:找出包住中心的全白方块,logo 画在里面
  const mid = (n - 1) / 2
  let k = 0
  while (k < n / 2) {
    const a = Math.floor(mid - k), b = Math.ceil(mid + k)
    let white = true
    for (let i = a; i <= b && white; i++) if (cells[a * n + i] || cells[b * n + i] || cells[i * n + a] || cells[i * n + b]) white = false
    if (!white) break
    k++
  }
  return { n, cells, hole: k > 0 ? 2 * k - 1 : 0 }
}

function inFinder(n, r, c) {
  return (r < 7 && c < 7) || (r < 7 && c >= n - 7) || (r >= n - 7 && c < 7)
}

// 彩色波:黑 → 一种颜色从码心往外扫一遍 → 下一种 …… → 回到黑。颜色都压在深色区,对白底对比度 ≥ 4.5,扫码不受影响。
const PALETTE = ['#111111', '#0B7A3B', '#C2410C', '#B4123A', '#4338CA', '#6D28D9', '#111111']
const SWEEP_MS = 1100
const HOLD_MS = 900

// 第 t 毫秒时,离码心 d(0..1)处的颜色下标与「波前」强度(0..1,刚被扫到的点稍微放大一下)
function waveAt(t, d) {
  const step = SWEEP_MS + HOLD_MS
  const total = step * (PALETTE.length - 1)
  const tt = t % total
  const i = Math.floor(tt / step)
  const front = Math.min(1.2, (tt - i * step) / SWEEP_MS * 1.2)
  const passed = d <= front
  const pulse = passed ? Math.max(0, 1 - (front - d) / 0.18) : 0
  return { color: PALETTE[passed ? i + 1 : i], pulse }
}

module.exports = { matrixFromPixels, inFinder, waveAt, PALETTE, SWEEP_MS, HOLD_MS }

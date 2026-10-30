'use strict'
// 10-29 第三轮(业主):入场码居中、码心 logo 黑色、彩色动效(参考 spottedinprod 圆点码)。
// 码仍由后端出图;前端把那张图按模块中心取样还原成矩阵再画成圆点 —— 这里验取样还原、彩色波和两处挂载。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const { matrixFromPixels, inFinder, waveAt, PALETTE, SWEEP_MS, HOLD_MS } = require('../../utils/dot-qr.js')

// 造一个 n×n 的合法外形矩阵(三个「回」字定位块 + 伪随机数据 + 中心留白),按后端那样放大成像素
function fixture(n, scale, margin, hole) {
  const cells = new Uint8Array(n * n)
  let seed = 7
  for (let i = 0; i < n * n; i++) { seed = (seed * 1103515245 + 12345) & 0x7fffffff; cells[i] = seed % 3 === 0 ? 1 : 0 }
  const finder = (r0, c0) => {
    for (let r = -1; r <= 7; r++) for (let c = -1; c <= 7; c++) {
      const rr = r0 + r, cc = c0 + c
      if (rr < 0 || cc < 0 || rr >= n || cc >= n) continue
      const ring = r === 0 || r === 6 || c === 0 || c === 6, core = r >= 2 && r <= 4 && c >= 2 && c <= 4
      cells[rr * n + cc] = r >= 0 && r <= 6 && c >= 0 && c <= 6 && (ring || core) ? 1 : 0
    }
  }
  finder(0, 0); finder(0, n - 7); finder(n - 7, 0)
  const mid = (n - 1) / 2
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (Math.abs(r - mid) <= (hole - 1) / 2 && Math.abs(c - mid) <= (hole - 1) / 2) cells[r * n + c] = 0
  const w = (n + margin * 2) * scale
  const px = new Uint8Array(w * w * 4).fill(255)
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
    if (!cells[r * n + c]) continue
    for (let y = 0; y < scale; y++) for (let x = 0; x < scale; x++) {
      const i = (((r + margin) * scale + y) * w + (c + margin) * scale + x) * 4
      px[i] = px[i + 1] = px[i + 2] = 0
    }
  }
  return { cells, px, w }
}

test('后端码图按模块中心取样,还原出同一个矩阵和中心留白', () => {
  for (const [n, scale] of [[37, 7], [49, 6], [57, 5]]) {
    const f = fixture(n, scale, 1, 11)
    const m = matrixFromPixels(f.px, f.w, f.w)
    assert.ok(m, n + ' 还原成功')
    assert.equal(m.n, n)
    assert.deepEqual(Array.from(m.cells), Array.from(f.cells))
    assert.ok(m.hole >= 11, '中心留白 ≥ 11 个模块,logo 画在里面')
  }
})

test('不是二维码(尺寸不合法或没有定位块)时返回 null,页面退回原图', () => {
  const blank = new Uint8Array(100 * 100 * 4).fill(255)
  assert.equal(matrixFromPixels(blank, 100, 100), null)
  const f = fixture(37, 6, 1, 9)
  const broken = Uint8Array.from(f.px)
  for (let i = 0; i < broken.length; i += 4) if (((i / 4) % f.w) < 6 * 4 && Math.floor(i / 4 / f.w) > 6 * 3 && Math.floor(i / 4 / f.w) < 6 * 5) broken[i] = broken[i + 1] = broken[i + 2] = 255
  assert.equal(matrixFromPixels(broken, f.w, f.w), null)
  assert.ok(inFinder(37, 3, 3) && inFinder(37, 3, 33) && inFinder(37, 33, 3) && !inFinder(37, 33, 33))
})

test('彩色波:先从码心往外扫,一圈颜色走完回到黑;颜色都是深色,白底上扫码读得出', () => {
  assert.equal(PALETTE[0], '#111111'); assert.equal(PALETTE[PALETTE.length - 1], '#111111')
  const half = SWEEP_MS / 2
  assert.equal(waveAt(half, 0.05).color, PALETTE[1], '码心先变色')
  assert.equal(waveAt(half, 0.95).color, PALETTE[0], '外圈还没扫到')
  assert.equal(waveAt(SWEEP_MS + 10, 0.95).color, PALETTE[1], '一遍扫完整个码都换色')
  assert.equal(waveAt((SWEEP_MS + HOLD_MS) * (PALETTE.length - 1) + 1, 0.5).color, PALETTE[0], '一轮之后从黑重新开始')
  const lum = (hex) => {
    const ch = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4))
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]
  }
  for (const c of PALETTE) assert.ok(1.05 / (lum(c) + 0.05) >= 4.5, c + ' 对白底对比度 ≥ 4.5')
})

test('票夹背面和分享入场码用同一个 cy-dot-qr;卡内居中;减少动态效果时只画静止黑码', () => {
  const comp = read('components/cy/dot-qr/index.js')
  assert.match(comp, /readReducedMotion\(\)\) \{ this\._draw\(-1\); return \}/)
  assert.match(comp, /globalCompositeOperation = 'source-in'; x\.fillStyle = INK/, 'logo 压成黑色')
  assert.match(read('components/cy/dot-qr/index.wxml'), /<image class="dq__img" src="\{\{src\}\}"/, '还原失败退回原图')
  assert.match(read('components/cy/dot-qr/index.wxss'), /\.dq__logo-img \{[^}]*filter: brightness\(0\)/, '退回原图时 logo 也是黑的')
  assert.match(read('subpackageMember/signup/index.json'), /"cy-dot-qr": "\/components\/cy\/dot-qr\/index"/)
  assert.match(read('components/cy/scene-qr-ticket/index.json'), /"cy-dot-qr": "\/components\/cy\/dot-qr\/index"/)
  assert.match(read('subpackageMember/signup/index.wxss'), /\.wc__back \{[^}]*justify-content: center;/)
  assert.match(read('components/cy/scene-qr-ticket/index.wxss'), /\.voucher__card \{[^}]*justify-content: center;/)
  assert.doesNotMatch(read('subpackageMember/signup/index.wxml') + read('components/cy/scene-qr-ticket/index.wxml'), /qr-logo/)
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

function sources() {
  return {
    badgeJs: read('subpackageP3/pages/badge-wall/index/index.js'),
    badgeWxml: read('subpackageP3/pages/badge-wall/index/index.wxml'),
    badgeWxss: read('subpackageP3/pages/badge-wall/index/index.wxss'),
    badgeGlyphs: read('subpackageP3/pages/badge-wall/index/glyphs.js'),
    badgeEngine: read('subpackageP3/pages/badge-wall/index/engine.js'),
    growthJs: read('subpackageP3/pages/growthcenter/index/index.js'),
    growthWxml: read('subpackageP3/pages/growthcenter/index/index.wxml'),
    growthWxss: read('subpackageP3/pages/growthcenter/index/index.wxss'),
  }
}

function hexColors(source) {
  return [...source.matchAll(/#([0-9a-f]{6})(?![0-9a-f])/gi)].map((match) => match[1])
}

function isPurple(hex) {
  const r = parseInt(hex.slice(0, 2), 16) / 255
  const g = parseInt(hex.slice(2, 4), 16) / 255
  const b = parseInt(hex.slice(4, 6), 16) / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const delta = max - min
  if (!delta || max < 0.18 || delta / max < 0.22) return false
  let hue
  if (max === r) hue = 60 * (((g - b) / delta) % 6)
  else if (max === g) hue = 60 * ((b - r) / delta + 2)
  else hue = 60 * ((r - g) / delta + 4)
  if (hue < 0) hue += 360
  return hue >= 250 && hue <= 330
}

// 2026-10-29:A05 的广场列表页(pages/square/list)已删,横滑即将开始卡随页撤走,A05 契约与负控一并移除。
function assertBadgeWallContract(files) {
  assert.match(files.badgeWxml, /<canvas[^>]*type="webgl"[^>]*id="wall"/, '单 canvas WebGL 引擎必须保留')
  // 2026-10-27 owner P-30:墙 / 列表切换按业主要求撤掉,只剩一页(上已点亮、下未解锁)
  assert.doesNotMatch(files.badgeWxml, /class="bw-view-switch"|bindtap="setWallMode"|bindtap="setListMode"/)
  assert.match(files.badgeWxml, /wx:for="\{\{badgeGroups\}\}"/)
  assert.match(files.badgeWxml, /class="bw-list-h"/)
  assert.match(files.badgeWxml, /<scroll-view[^>]*class="bw-list"[^>]*scroll-y/)
  // 2026-10-28 p75:每组两列勋章(金属边 + 珐琅面),行列表撤掉
  assert.match(files.badgeWxml, /class="bw-cell"[^>]*wx:for="\{\{group\.items\}\}"/)
  assert.match(files.badgeWxml, /<cy-empty[^>]*class="bw-empty-state"[^>]*fill[^>]*size="lg"[^>]*title="还没有点亮勋章"/)
  assert.match(files.badgeWxml, /\{\{unlockedCount\}\}[\s\S]*\{\{badges\.length\}\}/)
  assert.match(files.badgeJs, /viewMode:\s*'list'/)
  assert.match(files.badgeJs, /BADGE_SECTIONS/)
  assert.match(files.badgeWxss, /\.bw-head\s*\{[^}]*padding:\s*0 var\(--cy-page-x\) var\(--cy-space-3\)/)
  assert.match(files.badgeWxss, /\.bw-page-title\s*\{[^}]*font-size:\s*var\(--cy-type-page-title\)/)

  const badgeColorSources = [files.badgeJs, files.badgeWxss, files.badgeGlyphs, files.badgeEngine].join('\n')
  const purple = hexColors(badgeColorSources).filter(isPurple)
  assert.deepEqual(purple, [], `B67 不得保留紫色字面量: ${purple.join(', ')}`)
  assert.doesNotMatch(badgeColorSources, /紫/, 'B67 引擎与徽记注释也不得继续声明紫色轨道')
}

function assertGrowthContract(files) {
  assert.match(files.growthWxml, /class="gc-score-card"/)
  assert.match(files.growthWxml, /class="gc-score-value"/)
  assert.match(files.growthWxml, /class="gc-metric-list"/)
  assert.match(files.growthWxml, /class="gc-badge-list"/)
  assert.doesNotMatch(files.growthWxml, /discStyle|me\.bg/, '积分页不得再以内联彩色仪表着色')
  assert.doesNotMatch(files.growthJs, /ME_GRAD|BADGE_PALETTE|discStyle/, '积分页数据层不得生成彩色渐变仪表')
  assert.match(files.growthWxss, /\.gc-score-rank\s*\{[^}]*background:\s*var\(--cy-color-bg-elevated\)/)
  assert.match(files.growthWxss, /\.gc-badge-disc\s*\{[^}]*background:\s*var\(--cy-color-bg-surface-subtle\)/)
  assert.match(files.growthWxss, /\.gc-score-value\s*\{[^}]*font-size:\s*var\(--cy-type-data-xl\)/)
}

function assertAll(files) {
  assertBadgeWallContract(files)
  assertGrowthContract(files)
}

test('B67/B68：WebGL 墙/列表双视图与中性成长层级全部落地', () => {
  assertAll(sources())
})

test('负控二：B68 恢复彩色 discStyle 内联仪表必须判红', () => {
  const files = sources()
  files.growthWxml = files.growthWxml.replace('class="gc-badge-disc"', 'class="gc-badge-disc" style="{{item.discStyle}}"')
  assert.throws(() => assertAll(files), assert.AssertionError)
})

test('负控三：B67 信息层写回紫色字面量必须判红', () => {
  const files = sources()
  files.badgeWxss += '\n.bw-head { color: #8b5cf6; }\n'
  assert.throws(() => assertAll(files), assert.AssertionError)
})

test('负控四：B67 标题区恢复额外顶部间距必须判红', () => {
  const files = sources()
  files.badgeWxss = files.badgeWxss.replace(
    'padding: 0 var(--cy-page-x) var(--cy-space-3)',
    'padding: var(--cy-space-3) var(--cy-page-x) var(--cy-space-3)',
  )
  assert.throws(() => assertAll(files), assert.AssertionError)
})

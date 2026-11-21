// 2026-11-21 业主反馈(XA01《今晚，轮到我们上场》· 章节「那算了」):「页面设计不好看,别用这种光、别都一个样式,字现在很乱。」
// 钉住:章节故事流没有整屏斜光带 / 场景光;底色走本路线配色;正文书页式左对齐、段距用 token;站点页脚在流里、不再压字。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '../..')
const read = (f) => fs.readFileSync(path.join(root, f), 'utf8')
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '')
const strip2 = (s) => s.replace(/<!--[\s\S]*?-->/g, '')

function rule(css, sel) {
  const i = css.indexOf(sel + '{')
  assert.ok(i >= 0, '缺少规则 ' + sel)
  return css.slice(i, css.indexOf('}', i) + 1)
}

test('故事流画布不再画场景光:没有斜光束 / 整屏光晕 / 散景', () => {
  const js = read('pages/play/index.js')
  assert.ok(!/_paintSceneLight/.test(js), '场景光层已删')
  assert.ok(!/createLinearGradient\(W, 0, 0, H\)/.test(js), '右上到左下的斜光带不再画')
  assert.ok(!/_bokeh/.test(js), '散景光斑不再画')
  // 画布循环仍在:浮尘与风格层(behaviour 不变)
  assert.match(js, /lookFx\.draw\(that\._lookFx, ctx, W, H, Date\.now\(\)\)/)
  assert.match(js, /paint\('stBack', false\);\n    paint\('stFront', true\);/)
})

test('章节故事流根节点:没有整屏光带类,底色走本路线配色', () => {
  const wxml = strip2(read('pages/play/index.wxml'))
  const open = wxml.match(/<view class="chfull [^"]*" wx:if="\{\{chapterFull\}\}"/)
  assert.ok(open, '找到 chfull 根节点')
  assert.ok(!/sheen|sweep|beam|light-band|lightband|stage-light/.test(open[0]), '根节点不挂光带类')
  assert.match(open[0], /\{\{chapter\.atmosphereClass\}\}/, '每条路线都铺自己的章节 / 主题配色')
  const css = strip(read('pages/play/index.wxss'))
  assert.match(css, /\.chfull\[class\*="atmosphere--"\]\{ background:var\(--atmo-bg\);/, '平涂本路线底色')
  assert.ok(!/\.chfull[^{]*(sheen|sweep|beam|light-band)/.test(css), '故事流没有光带样式')
})

test('正文书页式:左对齐、段距 token、左页边给说话人名字', () => {
  const css = strip(read('pages/play/index.wxss'))
  assert.equal(rule(css, '.chfull .st-line'), '.chfull .st-line{margin-bottom:var(--cy-space-5);}')
  const col = rule(css, '.chfull .chfull__para:not(.chfull__para--wide)')
  assert.match(col, /text-align:left;/)
  assert.match(col, /margin-left:var\(--cy-space-8\);margin-right:var\(--cy-space-6\);/)
  assert.ok(!/360rpx/.test(rule(css, '.chfull .st-line')), '不再一屏一段')
  const cast = strip(read('pages/play/cast-play.wxss'))
  assert.match(rule(cast, '.chfull__who'), /left:calc\(var\(--cy-space-3\) - var\(--cy-space-8\)\);/)
  assert.match(rule(cast, '.chfull__who'), /font-size:20rpx; font-weight:400;[^}]*opacity:\.46;/)
  for (const sel of ['.chfull__choice-q', '.chfull__remember', '.chfull__vrow']) {
    assert.ok(!/text-align:center/.test(rule(cast, sel)), sel + ' 跟正文同一条左边线')
  }
  // 正文第一段落在顶栏渐隐终点下方,不贴顶部控件
  assert.match(css, /\.chfull__pad:first-child\{height:max\(440rpx, calc\(var\(--chfull-fade-end\) \+ var\(--cy-space-6\)\)\);\}/)
})

test('站点页脚在章末流里,不绝对定位、不压正文', () => {
  const wxml = strip2(read('pages/play/index.wxml'))
  const scroll = wxml.slice(wxml.indexOf('<scroll-view class="chfull__scroll'), wxml.indexOf('</scroll-view>', wxml.indexOf('<scroll-view class="chfull__scroll')))
  assert.match(scroll, /class="chfull__runfoot" wx:if="\{\{cast\.on && cast\.footer\}\}"/, '页脚在滚动流里')
  assert.ok(scroll.indexOf('chfull__runfoot') > scroll.indexOf('class="chfull__end"'), '在章末按钮之后')
  assert.equal((wxml.match(/class="chfull__runfoot"/g) || []).length, 1)
  const foot = rule(strip(read('pages/play/cast-play.wxss')), '.chfull__runfoot')
  assert.ok(!/position:\s*(absolute|fixed)/.test(foot), '不绝对定位')
})

test('老主题白底章节:状态栏换黑字只在章节故事流里', () => {
  const methods = require('../../pages/play/utils/play-look.js').methods()
  const calls = []
  global.wx = { setNavigationBarColor: (o) => calls.push(o.frontColor) }
  try {
    const page = Object.assign({ _look: { legacy: true }, data: { chapterFull: true, chapter: { atmospherePreset: 'WHITE' } } }, methods)
    page._storyStatusBar(true)
    assert.deepEqual(calls, ['#000000'])
    page.data = { chapterFull: false, chapter: { atmospherePreset: 'WHITE' } }
    page._storyStatusBar(true)
    assert.equal(calls.length, 1, '自由探索仍是纯黑底,不换')
    page.data = { chapterFull: true, chapter: { atmospherePreset: 'BLUE' } }
    page._storyStatusBar(true)
    assert.equal(calls.length, 1, '深色配色不换')
  } finally { delete global.wx }
})

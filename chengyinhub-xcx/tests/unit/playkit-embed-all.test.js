// 2026-10-30 业主裁决「所有玩法都内嵌」:原来只能整屏 / 默认整屏的玩法都要能在故事流里原位渲染。
//   ① 半屏玩法(cy-sheet 外壳)内嵌时不能再套 cy-sheet —— .chfull__para 带 transform/filter,是 fixed 后代的包含块;
//   ② 整屏 fixed 的玩法(今日城市签、扫码取景)内嵌时收成流里一块;弹球的墙 = 内嵌球场四边;
//   ③ 外壳把 inline 传给每一个需要分档的玩法;
//   ④ 皮肤表内嵌时取章节配色,整屏落回原型原值。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const SHEET_KITS = ['diyname', 'musiccorner', 'silentorder', 'slowtask', 'steps', 'timewindow']

test('半屏玩法:整屏走 cy-sheet,内嵌走原位 view,两处 include 同一份正文', () => {
  for (const k of SHEET_KITS) {
    const wxml = read('pages/play/components/playkit-' + k + '/index.wxml')
    assert.match(wxml, /<cy-sheet wx:if="\{\{!inline\}\}"[^>]*>\s*<include src="body\.wxml" \/>\s*<\/cy-sheet>/, k + ' 整屏档')
    assert.match(wxml, /<view wx:elif="\{\{show\}\}" class="pk-embed">\s*<include src="body\.wxml" \/>\s*<\/view>/, k + ' 内嵌档')
    assert.ok(!read('pages/play/components/playkit-' + k + '/body.wxml').includes('<cy-sheet'), k + ' 正文里不许再套半屏')
    assert.match(read('pages/play/components/playkit-' + k + '/index.js'), /inline: \{ type: Boolean, value: false \}/, k + ' 缺 inline 属性')
  }
  assert.match(read('pages/play/style/playkit.wxss'), /\.pk-embed \{/)
})

test('外壳把 inline 传给每一个分档玩法', () => {
  const shell = read('pages/play/components/playkit/index.wxml')
  for (const k of SHEET_KITS.concat(['dailysign', 'scan', 'ballshake'])) {
    assert.match(shell, new RegExp('<cy-playkit-' + k + ' wx:elif="\\{\\{kit\\.type === \'' + k + '\'\\}\\}"\\n  show="\\{\\{show\\}\\}" inline="\\{\\{inline\\}\\}"'), k)
  }
})

test('整屏 fixed 的玩法内嵌时收成一块;扫码内嵌不接真 AR;弹球墙 = 内嵌球场', () => {
  assert.match(read('pages/play/components/playkit-dailysign/index.wxss'), /\.ds--embed \{ position: relative;/)
  assert.match(read('pages/play/components/playkit-scan/index.wxss'), /\.sc--embed \.sc__cam \{ position: relative;/)
  assert.match(read('pages/play/components/playkit-scan/index.js'), /const live = !this\.data\.inline && shouldUseAr/)
  const js = read('pages/play/components/playkit-ballshake/index.js')
  const rpx = Number(js.match(/const EMBED_H_RPX = (\d+);/)[1])
  assert.match(read('pages/play/components/playkit-ballshake/index.wxss'), new RegExp('\\.bl--embed \\{ position: relative; height: ' + rpx + 'rpx;'))
  assert.match(js, /this\._h = this\.data\.inline \? EMBED_H_RPX \* \(info\.windowWidth \/ 750\) : info\.windowHeight;/)
})

test('皮肤表:每个色值都有内嵌通道,整屏缺省逐字是原型值', () => {
  const surface = read('pages/play/style/play-surface.wxss')
  const rows = surface.match(/^\.skin-[a-z]+\s*\{[^}]*\}/gm)
  assert.ok(rows.length >= 11)
  for (const row of rows) {
    for (const key of ['bg', 'ink', 'sub', 'soft', 'accent', 'ok']) {
      assert.match(row, new RegExp('--' + key + ':var\\(--pk-embed-' + key + ', #[0-9a-fA-F]{3,8}\\)'), row.slice(0, 20) + ' ' + key)
    }
  }
  const page = read('pages/play/index.wxss').match(/\.chfull__kit\s*\{[^}]*\}/)[0]
  for (const key of ['bg', 'ink', 'sub', 'soft', 'accent', 'ok']) assert.ok(page.includes('--pk-embed-' + key + ':'), key)
})

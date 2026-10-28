// 2026-10-28 完成页改版(业主定稿 claude.ai/artifact/DF1tPhSNWFrzQhDu8hoUAy):整屏深色、NRC 顺序、只写真读数。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '..', '..')
const js = fs.readFileSync(path.join(ROOT, 'pages/play/index.js'), 'utf8')
const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')
const wxss = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxss'), 'utf8')
const fn = (name) => new Function(js.match(new RegExp('function ' + name + '\\([\\s\\S]*?\\n}\\n'))[0] + '; return ' + name)()

test('成就行:「标题 · 值」拆开,站名做副行,首达的值本身就是站名', () => {
  const rows = fn('achievementRows')({ 1: '首达 · 白马咖啡馆', 3: '最快一段 · 1分20秒' }, [{ nodeId: 1, name: '白马咖啡馆' }, { nodeId: 2, name: '摩西会堂' }, { nodeId: 3, name: '霍山公园' }])
  assert.deepEqual(rows, [
    { key: '1', title: '首达', value: '', sub: '白马咖啡馆', glyph: '首', kind: 'ink' },
    { key: '3', title: '最快一段', value: '1分20秒', sub: '霍山公园', glyph: '速', kind: 'metal' },
  ])
})

test('眉标日期:「10月28日 周三」', () => {
  assert.equal(fn('finishDateLabel')(new Date(2026, 9, 28)), '10月28日 周三')
})

test('版式顺序:地图在最上,然后标题、大数字、成就、城市探索,按钮固定在底部', () => {
  const sheet = wxml.slice(wxml.indexOf('<view class="finsheet" wx:if="{{showFinish}}">'), wxml.indexOf('class="finsheet__cta"'))
  const order = ['finsheet__map"', 'finsheet__head', 'finsheet__grid" wx:if="{{mode!=2}}"', '<view class="fsec__h">成就', '<view class="fsec__h">城市探索', 'finish-journal-entry']
  const at = order.map((k) => sheet.indexOf(k))
  at.forEach((i, n) => assert.ok(i > -1, order[n] + ' 不见了'))
  assert.deepEqual([...at].sort((a, b) => a - b), at, '顺序必须是 NRC 跑步总结的顺序')
  assert.doesNotMatch(sheet, /finsheet__grab/, '整屏页不再有半屏拉手')
  assert.match(sheet, /wx:if="\{\{reviewElapsed\}\}"[^>]*><text class="fst__v">\{\{reviewElapsed\}\}/, '用时是真读数,没有就整格不出')
  assert.match(sheet, /wx:if="\{\{finishPuzzleCount\}\}"/, '没有解谜题不出解谜分 0')
  assert.match(sheet, /wx:if="\{\{mode != 2 && finishScore > 0\}\}"/, '探索值拿不到不写 0')
  assert.match(wxss, /\.finsheet\{ position:fixed; inset:0;/, '整屏')
  assert.match(wxss, /--cy-color-play-story-page:#000000;/, '深色')
  assert.match(wxss, /\.finsheet \.finsheet__cta\{ position:fixed;/, '按钮固定在底部')
})

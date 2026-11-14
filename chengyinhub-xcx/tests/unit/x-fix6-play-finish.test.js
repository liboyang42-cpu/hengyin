'use strict'
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const root = path.resolve(__dirname, '../..')
const wxml = fs.readFileSync(path.join(root, 'pages/play/index.wxml'), 'utf8')
const wxss = fs.readFileSync(path.join(root, 'pages/play/index.wxss'), 'utf8')

test('R4-2:两按钮完成面板给路线卡多收 123rpx,保存图片/分享行不被查看结算压住', () => {
  assert.match(wxml, /class="finsheet \{\{allDone && !isPreview && !isMock && topicId \? 'finsheet--two' : ''\}\}"/)
  assert.match(wxss, /\.finsheet--two \.finsheet__routecv\{[^}]*100vh - 1023rpx/)
  // 与底座高度同源:dock--two 比单按钮多 123rpx
  assert.match(wxss, /\.finsheet__dock--two\{ height:calc\(323rpx/)
  assert.match(wxss, /\.finsheet__dock\{[^}]*calc\(200rpx/)
})

test('R4-3:ending 读取失败 → 页面跳过结局直接进完成页;回空对象(主题没配结局)→ 仍给同一张「本局完成」卡', async () => {
  const source = fs.readFileSync(path.join(root, 'pages/play/index.js'), 'utf8')
  const begin = source.indexOf('  _loadLifeEnding(next) {')
  const end = source.indexOf('  /** 把一章结局章', begin)
  const lifeArchive = require('../../pages/play/utils/life-archive.js')
  const run = async (response) => {
    let reply
    const req = () => new Promise((resolve) => { reply = resolve })
    const method = new Function('req', 'lifeArchive', 'return ({' + source.slice(begin, end) + '})._loadLifeEnding;')(req, lifeArchive)
    const shown = []
    const page = { data: { topicId: 1, activityId: 0, topicName: '提篮桥', nodes: [] }, _routeState: { sessionId: 2 }, _isPlayAccountCurrent: () => true,
      _showEnding: (card) => shown.push(card) }
    let finished = 0
    method.call(page, () => finished++)
    reply(response)
    await Promise.resolve()
    return { finished, shown }
  }
  const failed = await run({ code: 500 })
  assert.equal(failed.finished, 1)
  assert.equal(failed.shown.length, 0)
  const empty = await run({ code: 200, data: {} })
  assert.equal(empty.finished, 0)
  assert.equal(empty.shown.length, 1)
  assert.equal(empty.shown[0].real, true)
  assert.equal(empty.shown[0].list[0].title, '提篮桥')
})

test('R4-4:发布页底栏为整条实底,不再渐变透出身后控件', () => {
  const css = fs.readFileSync(path.join(root, 'pages/publish/fabu/index.wxss'), 'utf8')
  const rule = css.slice(css.indexOf('\n.slopes-tabbar {'), css.indexOf('.pd-edit-gate {'))
  assert.match(rule, /background: var\(--cy-color-bg-page\);/)
  assert.doesNotMatch(rule, /transparent/)
})

test('R4-5:通关卡文案区不再 overflow:hidden(否则乱码字被切成边缘字影、底部标签被切半)', () => {
  const css = fs.readFileSync(path.join(root, 'pages/play/components/chapter-flip/index.wxss'), 'utf8')
  const rule = css.split('\n').find((l) => l.startsWith('.cf--finale .cf__copy { top:'))
  assert.ok(rule)
  assert.doesNotMatch(rule.split('/*')[0], /overflow:\s*hidden/)
  assert.match(css, /\.cf--finale \.cf__copy \{ box-sizing: border-box; width: calc\(100vw - 92rpx\);/)
})

test('R4-6:章节选择 / 工具菜单下的地图底取外观底色,不是纯黑', () => {
  assert.match(wxss, /\.play \{ position:relative;[^]*?background:var\(--lk-bg, var\(--cy-color-play-page\)\);/)
  assert.match(wxss, /\.story-map\{[^}]*background:var\(--lk-bg, #000000\);/)
})

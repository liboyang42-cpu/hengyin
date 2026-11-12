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

test('R4-3:ending 回空对象(主题没配结局)→ 页面跳过结局直接进完成页', async () => {
  const source = fs.readFileSync(path.join(root, 'pages/play/index.js'), 'utf8')
  const begin = source.indexOf('  _loadLifeEnding(next) {')
  const end = source.indexOf('  /** 把一章结局章', begin)
  let reply
  const req = () => new Promise((resolve) => { reply = resolve })
  const method = new Function('req', 'return ({' + source.slice(begin, end) + '})._loadLifeEnding;')(req)
  const page = { data: { topicId: 1, activityId: 0 }, _routeState: { sessionId: 2 }, _isPlayAccountCurrent: () => true }
  let finished = 0
  method.call(page, () => finished++)
  reply({ code: 200, data: {} })
  await Promise.resolve()
  assert.equal(finished, 1)
})

test('R4-4:发布页底栏为整条实底,不再渐变透出身后控件', () => {
  const css = fs.readFileSync(path.join(root, 'pages/publish/fabu/index.wxss'), 'utf8')
  const rule = css.slice(css.indexOf('\n.slopes-tabbar {'), css.indexOf('.pd-edit-gate {'))
  assert.match(rule, /background: var\(--cy-color-bg-page\);/)
  assert.doesNotMatch(rule, /transparent/)
})

test('R4-5:通关卡文案不裁底部标签(动效 v2:读数卡是普通流式块,不设 overflow:hidden 的文案区)', () => {
  const css = fs.readFileSync(path.join(root, 'pages/play/components/chapter-moment/index.wxss'), 'utf8')
  const rule = css.split('\n').find((l) => l.startsWith('.cm__fcol {'))
  assert.ok(rule)
  assert.doesNotMatch(rule, /overflow:\s*hidden/)
})

test('R4-6:章节选择 / 工具菜单下的地图底取外观底色,不是纯黑', () => {
  assert.match(wxss, /\.play \{ position:relative;[^]*?background:var\(--lk-bg, var\(--cy-color-play-page\)\);/)
  assert.match(wxss, /\.story-map\{[^}]*background:var\(--lk-bg, #000000\);/)
})

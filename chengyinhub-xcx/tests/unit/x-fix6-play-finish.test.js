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

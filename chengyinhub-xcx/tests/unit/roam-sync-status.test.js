'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createRoamSyncStatus, FAIL_THRESHOLD, OFFLINE_TEXT } = require('../../utils/roam-sync-status.js')

test('BL-03 连续网络失败达到阈值才提示,一次成功即消失', () => {
  const s = createRoamSyncStatus()
  assert.equal(FAIL_THRESHOLD, 2)
  assert.equal(s.report({ netFail: true }), false)
  assert.equal(s.report({ netFail: true }), true)
  assert.equal(s.report({ code: 200 }), false)
  assert.equal(s.report({ netFail: true }), false)
})

test('BL-03 HTTP 异常与业务错误不算断网,也不清零', () => {
  const s = createRoamSyncStatus()
  s.report({ netFail: true })
  assert.equal(s.report({ httpFail: true }), false)
  assert.equal(s.report({ code: 500, msg: 'x' }), false)
  assert.equal(s.report({ netFail: true }), true)
})

test('BL-03 系统断网立即提示,恢复后消失', () => {
  const s = createRoamSyncStatus()
  assert.equal(s.setConnected(false), true)
  assert.equal(s.report({ netFail: true }), true)
  assert.equal(s.setConnected(true), false)
  assert.equal(OFFLINE_TEXT, '网络断开，位置暂不同步')
})

test('BL-03 页面:reveal 与 presence 结果都进状态,断网监听成对注销', () => {
  const root = path.join(__dirname, '../..')
  const js = fs.readFileSync(path.join(root, 'pages/roam/index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(root, 'pages/roam/index.wxml'), 'utf8')
  assert.match(js, /req\('\/api\/roam\/reveal'[^\n]*\n\s*this\._markRoamSync\(res\);/)
  assert.match(js, /\{ silentError: true \}\)\.then\(\(res\) => this\._markRoamSync\(res\)\);/)
  assert.match(js, /onLoad\(q\) \{[\s\S]*?this\._bindRoamNetwork\(\);/)
  assert.match(js, /onUnload\(\) \{[\s\S]*?this\._unbindRoamNetwork\(\);/)
  assert.match(js, /wx\.offNetworkStatusChange\(this\._onRoamNetwork\)/)
  assert.match(wxml, /wx:if="\{\{syncOffline && screen=='map'[^"]*\}\}" aria-role="alert"/)
})

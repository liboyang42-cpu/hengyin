'use strict'
// F-W1-12 / F-R2P-02 / F-W1-02:登录失效与权限否认是身份说明,必须可见;只有读取失败才留白(F-R2U-01)。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.resolve(__dirname, '../..')
const read = (relative) => fs.readFileSync(path.join(ROOT, relative), 'utf8')

test('口碑管理登录失败落可见的 login-required 态,不复用留白的加载失败态', () => {
  const js = read('pages/merchant/reviews/index.js')
  const wxml = read('pages/merchant/reviews/index.wxml')
  assert.doesNotMatch(js, /pageState: 'error', errorMessage: '登录/)
  assert.match(js, /pageState: 'login-required'/)
  const login = wxml.match(/<cy-inline-error[^>]*pageState === 'login-required'[^>]*>/)
  assert.ok(login, 'login-required 必须渲染')
  assert.doesNotMatch(login[0], /load-failure/)
  assert.match(login[0], /bind:action="retryLogin"/)
})

test('客户名单接口 401/403 显示无权限说明', () => {
  const js = read('pages/merchant/customer/index.js')
  assert.match(js, /if \(isAccessDenied\(res\)\) that\.setData\(\{ noPermission: true \}\)/)
  assert.match(js, /_clearScopedData\('商家资格已失效', true\);\s*that\.setData\(\{ noPermission: true \}\)/)
})

test('账本无权限说明位于导航与标题之后,且正文条件链整段不渲染', () => {
  const wxml = read('pages/merchant/ledger/index.wxml')
  const title = wxml.indexOf('<cy-page-title')
  const denied = wxml.indexOf('<cy-empty wx:if="{{accessDenied}}"')
  assert.ok(title > 0 && denied > title, '无权限说明不能被固定导航压住')
  assert.match(wxml, /<block wx:if="\{\{!accessDenied\}\}">\s*<cy-state-shell[^>]*wx:if="\{\{routeError\}\}"/)
})

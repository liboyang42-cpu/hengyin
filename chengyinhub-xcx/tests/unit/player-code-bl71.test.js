'use strict'
// BL-71:个人主页「邀请好友」现有二维码位接入本人个人小程序码 POST /api/user/player-code。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { readPlayerCode } = require('../../utils/player-code.js')

const ROOT = path.resolve(__dirname, '../..')
const COMP_JS = path.join(ROOT, 'components/cy/scene-share-invite/index.js')
const COMP_WXML = fs.readFileSync(path.join(ROOT, 'components/cy/scene-share-invite/index.wxml'), 'utf8')
const PROFILE_WXML = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml'), 'utf8')

test('readPlayerCode 只认 code 200 + http(s) qr', () => {
  assert.equal(readPlayerCode({ code: 200, data: { qr: 'https://oss/x.png' } }), 'https://oss/x.png')
  assert.equal(readPlayerCode({ code: '200', data: { qr: ' http://oss/y.jpg ' } }), 'http://oss/y.jpg')
  assert.equal(readPlayerCode({ code: 200, data: { qr: '/profile/x.png' } }), '')
  assert.equal(readPlayerCode({ code: 200, data: {} }), '')
  assert.equal(readPlayerCode({ code: 500, msg: '个人码生成服务繁忙，请稍后重试' }), '')
  assert.equal(readPlayerCode(null), '')
})

function load(requests, previews) {
  let def
  const prev = global.Component
  global.Component = (d) => { def = d }
  global.getApp = () => ({ sendRequest: (o) => requests.push(o), isDevEnv: () => false })
  global.wx = { previewImage: (o) => previews.push(o) }
  delete require.cache[require.resolve(COMP_JS)]
  require(COMP_JS)
  global.Component = prev
  const c = Object.assign({}, def.methods, { data: Object.assign({}, def.data, { qrUrl: '', designPreview: true, playerCode: true, theme: 'player' }) })
  c.setData = (patch) => Object.assign(c.data, patch)
  c.detached = def.lifetimes.detached
  return c
}

test('playerCode:请求正确接口，成功后进入图片加载，加载完成可预览', () => {
  const requests = []; const previews = []
  const c = load(requests, previews)
  c.syncQr()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/user/player-code')
  assert.equal(requests[0].method, 'POST')
  assert.equal(c.data.qrState, 'loading')
  assert.equal(c.data.qrSrc, '')
  requests[0].success({ code: 200, data: { qr: 'https://oss/me.png' } })
  assert.equal(c.data.qrSrc, 'https://oss/me.png')
  c.onQrLoad({ currentTarget: { dataset: { src: 'https://oss/me.png' } } })
  assert.equal(c.data.qrState, 'ready')
  c.previewQr()
  assert.deepEqual(previews, [{ current: 'https://oss/me.png', urls: ['https://oss/me.png'] }])
})

test('生成失败显示后端文案并可重试；网络失败如实说明；迟到回包丢弃', () => {
  const requests = []
  const c = load(requests, [])
  c.syncQr()
  requests[0].success({ code: 500, msg: '个人码生成服务繁忙，请稍后重试' })
  assert.equal(c.data.qrState, 'code-error')
  assert.equal(c.data.qrErrorText, '个人码生成服务繁忙，请稍后重试')
  c.retryQr()
  assert.equal(requests.length, 2)
  requests[1].fail()
  assert.equal(c.data.qrState, 'code-error')
  assert.match(c.data.qrErrorText, /网络异常/)
  c.retryQr()
  c.detached()
  requests[2].success({ code: 200, data: { qr: 'https://oss/late.png' } })
  assert.equal(c.data.qrSrc, '', '卸载后迟到回包不写入')
})

test('显式 qrUrl 优先，不请求；未开 playerCode 时行为不变', () => {
  const requests = []
  const c = load(requests, [])
  c.data.qrUrl = 'https://given/qr.png'
  c.syncQr()
  assert.equal(requests.length, 0)
  assert.equal(c.data.qrSrc, 'https://given/qr.png')
  c.data.qrUrl = ''; c.data.playerCode = false
  c.syncQr()
  assert.equal(requests.length, 0)
  assert.equal(c.data.qrState, 'empty')
})

test('模板:沿用现有二维码位与 cy-error,个人主页本人视角开启 playerCode', () => {
  assert.match(COMP_WXML, /wx:elif="\{\{qrState === 'code-error'\}\}"[\s\S]*?<cy-error title="个人码加载失败" sub="\{\{qrErrorText\}\}"/)
  assert.match(COMP_WXML, /show-menu-by-longpress="\{\{playerCode\}\}"/)
  assert.match(PROFILE_WXML, /<cy-scene-share-invite theme="\{\{sceneCurrent\.theme\}\}" player-code="\{\{isSelf\}\}" \/>/)
})

test('首开 observer 与 attached 连调 syncQr 只发一次个人码请求；回包后可再次请求', () => {
  const requests = []
  const c = load(requests, [])
  c.syncQr()
  c.syncQr()
  assert.equal(requests.length, 1, '进行中不连发')
  requests[0].success({ code: 500, msg: '个人码生成服务繁忙，请稍后重试' })
  c.retryQr()
  assert.equal(requests.length, 2, '回包后重试照常请求')
})

test('模板:预览点击挂在带条件按压态的二维码框上，图片本身不再绑 tap', () => {
  assert.match(COMP_WXML, /class="siv__qr-frame"\s+hover-class="\{\{playerCode && qrState === 'ready' \? 'cy-pressed' : 'none'\}\}" hover-stay-time="80" bindtap="previewQr">/)
  assert.doesNotMatch(COMP_WXML, /<image[^>]*bindtap=/)
})

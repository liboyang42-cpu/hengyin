'use strict'
// qx-a7 V1-P59 / G-11:邀请记录的入场动效(叠放头像 + 卡片依次上浮 + 正文整块推上来)。
// 只用过渡,走 utils/page-motion.js;头像只来自真实邀请记录;减少动态效果时不触发。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const DIR = path.join(ROOT, 'subpackageA/components/scene-member-invite-history')
const COMPONENT_PATH = path.join(DIR, 'index.js')
const read = (file) => fs.readFileSync(file, 'utf8')

let componentConfig
let requests
let storage

global.getApp = () => ({
  getUserID: () => 7, /* Explicit authenticated private-list fixture. */
  getPageSize: () => 10,
  sendRequest(options) { requests.push(options) },
})
global.Component = (config) => { componentConfig = config }

function loadComponent() {
  delete require.cache[require.resolve(COMPONENT_PATH)]
  requests = []
  require(COMPONENT_PATH)
  const component = Object.assign({}, componentConfig.methods, { data: JSON.parse(JSON.stringify(componentConfig.data)) })
  component.patches = []
  component.setData = (patch) => { component.patches.push(patch); Object.assign(component.data, patch) }
  return component
}

async function loadWith(rows) {
  const component = loadComponent()
  component.load(true)
  requests[1].success({ code: 200, data: { rows: [], total: 0 } })
  requests[0].success({ code: 200, data: { rows, total: rows.length } })
  await new Promise((resolve) => setImmediate(resolve))
  return component
}

const people = (n) => Array.from({ length: n }, (_, i) => ({ id: i + 1, nickname: '友' + (i + 1), avatar: i % 2 ? '' : 'a' + i + '.png', createTime: '2026-11-0' + ((i % 9) + 1) + ' 10:00:00' }))

test('叠放头像只取真实邀请记录,最多 5 个', async () => {
  global.wx = { getStorageSync: () => '' }
  const component = await loadWith(people(7))
  assert.equal(component.data.stack.length, 5)
  assert.deepEqual(component.data.stack[0], { id: 1, avatar: 'a0.png', nickname: '友1' })
  component._detached = true
  clearTimeout(component._pmInTimer)
})

test('首次拿到记录先隐着渲染再上浮;刷新不重播', async () => {
  global.wx = { getStorageSync: () => '' }
  const component = await loadWith(people(2))
  const first = component.patches.findIndex((p) => p.pmIn === 1)
  const groups = component.patches.findIndex((p) => Array.isArray(p.groups) && p.groups.length)
  assert.ok(first >= 0 && first < groups, '入场标记必须先于数据落地')
  await new Promise((resolve) => setTimeout(resolve, 60))
  assert.equal(component.data.pmIn, 2)
  const before = component.patches.length
  component.load(true)
  requests[3].success({ code: 200, data: { rows: [], total: 0 } })
  requests[2].success({ code: 200, data: { rows: people(2), total: 2 } })
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(component.patches.slice(before).some((p) => p.pmIn === 1), false)
  component._detached = true
  clearTimeout(component._pmInTimer)
})

test('空列表与减少动态效果时不做入场', async () => {
  global.wx = { getStorageSync: () => '' }
  const empty = await loadWith([])
  assert.equal(empty.patches.some((p) => p.pmIn === 1), false)
  assert.deepEqual(empty.data.stack, [])
  global.wx = { getStorageSync: (key) => key === 'play_reduced_motion' }
  const reduced = await loadWith(people(3))
  assert.equal(reduced.patches.some((p) => p.pmIn === 1), false)
  assert.equal(reduced.data.stack.length, 3)
})

test('模板与样式:只用过渡,不加关键帧,系统减少动态效果时关掉', () => {
  const wxml = read(path.join(DIR, 'index.wxml'))
  const wxss = read(path.join(DIR, 'index.wxss'))
  assert.match(wxml, /<view class="ir-stack" wx:if="\{\{stack\.length\}\}"/)
  assert.match(wxml, /--pm-i:1;/)
  assert.doesNotMatch(wxss, /@keyframes|animation:/)
  assert.match(wxss, /@media \(prefers-reduced-motion: reduce\)/)
  assert.match(wxss, /\.ir-stack-i \+ \.ir-stack-i \{ margin-left: -16rpx; \}/)
  const pageWxss = read(path.join(ROOT, 'subpackageMember/myinvite/myinvite.wxss'))
  const pageJs = read(path.join(ROOT, 'subpackageMember/myinvite/myinvite.js'))
  assert.match(pageJs, /pageMotion\.enter\(this, 'pmPage', \{ once: true \}\)/)
  assert.doesNotMatch(pageWxss, /@keyframes|animation:/)
  assert.match(pageWxss, /translate: 0 calc\(var\(--pm-p, 0\) \* 160rpx\)/)
})

'use strict'
// PR #69 V3 · CL-03(F-W5-04):模板作者在「我的玩法」看到个人审核未通过与审核/广场反馈原因。
// 只验证 decorateItem 产出的展示字段与 WXML 真正渲染的绑定;后端 myinfo 文案由
// ApiTemplateMerchantOperatorScopeTest::ownerPendingReadDoesNotExpandRemovedRejectedUnlistedOrUnknownStates 覆盖。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const PAGE = '../../subpackageMember/mytemplate/mytemplate.js'
const WXML = fs.readFileSync(path.join(ROOT, 'subpackageMember/mytemplate/mytemplate.wxml'), 'utf8')

function loadPage() {
  let config
  global.getApp = () => ({ globalData: {}, getUserID: () => 9, getAuthorization: () => 'token', sendRequest() {} })
  global.Page = value => { config = value }
  global.wx = { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {}, showToast() {} }
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  return config
}

// 把 wx:for 里的 {{item.x}} 用真实 item 求值:只认本页用到的简单绑定。
function renderFeedback(item) {
  const rows = []
  const re = /<view class="dd" wx:if="\{\{item\.(\w+)\}\}">\s*<view class="label">([^<]+)<\/view>\s*<view class="cont flex-ac"><text>\{\{item\.(\w+)\}\}<\/text><\/view>/g
  for (const [, cond, label, field] of WXML.matchAll(re)) {
    if (item[cond]) rows.push([label, String(item[field])])
  }
  return rows
}

test('个人审核驳回的模板:列表显示「个人审核未通过」并渲染审核反馈与广场反馈原因', () => {
  const page = loadPage()
  const item = page.decorateItem({ id: 7, title: '夜游', draftStatus: 1, status: 2, checkStatus: 2,
    reason: '封面含二维码', marketTemplateId: 70, marketStatus: 2, marketReason: '玩法说明不完整' })
  assert.equal(item._statusText, '个人审核未通过')
  assert.equal(item._marketText, '广场需重新提交')
  assert.match(WXML, /<text>\{\{item\._statusText\}\}<\/text>/)
  assert.deepEqual(renderFeedback(item), [['审核反馈', '封面含二维码'], ['广场反馈', '玩法说明不完整']])
})

test('没有驳回原因时不渲染空的反馈行；服务端过滤产生的实体在原因里解码一次', () => {
  const page = loadPage()
  assert.deepEqual(renderFeedback(page.decorateItem({ id: 8, draftStatus: 1, status: 1, checkStatus: 1 })), [])
  const encoded = page.decorateItem({ id: 9, draftStatus: 1, status: 2, checkStatus: 2, reason: '人数&lt;2' })
  assert.deepEqual(renderFeedback(encoded), [['审核反馈', '人数<2']])
})

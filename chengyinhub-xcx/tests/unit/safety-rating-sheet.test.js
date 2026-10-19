// BL-89 到访安全感受评价:选项来自 taxonomy,提交体对照 MerchantSafetyRatingRequest,资格以后端报错为准。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')
const { levelTabs, buildRatingBody, receiptText } = require('../../utils/safety-rating.js')

const SHEET = path.resolve(__dirname, '../../pages/play/merchant/components/safety-rating-sheet/index.js')

function loadSheet() {
  let def
  const requests = []
  global.getApp = () => ({ globalData: {}, sendRequest: o => requests.push(o), getRequestErrorMessage: (r, f) => (r && r.msg) || f })
  global.Component = c => { def = flattenComponentToPage(c) }
  delete require.cache[require.resolve(SHEET)]
  require(SHEET)
  const sheet = Object.assign({}, def)
  sheet.data = JSON.parse(JSON.stringify(def.data))
  sheet.data.merchantMemberId = '900'
  sheet.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  sheet.triggerEvent = () => {}
  return { sheet, requests }
}

test('档位只认 taxonomy 里的已知值', () => {
  assert.deepEqual(levelTabs({ levels: ['UNSAFE', 'NEUTRAL', 'SAFE', 'X'] }).map(t => t.label), ['不安心', '一般', '安心'])
  assert.deepEqual(levelTabs(null), [])
  assert.deepEqual(buildRatingBody(7, 'SAFE', ' 灯很亮 ', 'k'), { merchantId: 7, overallLevel: 'SAFE', reason: '灯很亮', idempotencyKey: 'k' })
  assert.equal(receiptText({ ratingId: 3, overallLevel: 'SAFE', ratingPeriod: '2026-10' }), '你的感受：安心 · 评价周期 2026-10 · 受理编号 3')
})

test('提交:先用会员 ID 换商家 ID,再按请求体提交,成功显示回执', () => {
  const { sheet, requests } = loadSheet()
  sheet.open()
  assert.equal(requests[0].url, '/api/community-trust/safety/taxonomy')
  requests[0].success({ code: 200, data: { levels: ['UNSAFE', 'NEUTRAL', 'SAFE'] } })
  sheet.onLevel({ detail: { key: 'SAFE' } })
  sheet.onReason({ detail: { value: '店员会引导' } })
  assert.equal(sheet.data.canSubmit, true)
  sheet.submit()
  assert.equal(requests[1].url, '/api/merchant/public-home')
  assert.deepEqual(JSON.parse(requests[1].data), { memberId: 900 })
  requests[1].success({ code: 200, data: { id: 55, memberId: 900 } })
  assert.equal(requests[2].url, '/api/community-trust/safety/ratings')
  const body = JSON.parse(requests[2].data)
  assert.equal(body.merchantId, 55)
  assert.equal(body.overallLevel, 'SAFE')
  assert.ok(body.idempotencyKey)
  requests[2].success({ code: 200, data: { ratingId: 9, overallLevel: 'SAFE', ratingPeriod: '2026-10' } })
  assert.equal(sheet.data.stage, 'done')
})

test('无资格(403)如实回显后端文案,不显示成功', () => {
  const { sheet, requests } = loadSheet()
  sheet.open()
  requests[0].success({ code: 200, data: { levels: ['SAFE'] } })
  sheet.onLevel({ detail: { key: 'SAFE' } })
  sheet.onReason({ detail: { value: 'x' } })
  sheet.submit()
  requests[1].success({ code: 200, data: { id: 55 } })
  requests[2].success({ code: 403, msg: '仅真实核销到店记录可提交安全感评分' })
  assert.equal(sheet.data.stage, 'form')
  assert.equal(sheet.data.submitError, '仅真实核销到店记录可提交安全感评分')
  assert.equal(sheet.data.canSubmit, true)
})

test('BL-89 评价按钮留在原 CTA if/elif/else 链的 else 分支内,不拆开原「已核销」', () => {
  const wxml = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../pages/play/merchant/index.wxml'), 'utf8')
  const chain = wxml.slice(wxml.indexOf('wx:elif="{{!node.done}}"'))
  const elseAt = chain.indexOf('<block wx:else>')
  assert.ok(elseAt > 0, '最后一个 wx:elif 之后紧接 <block wx:else>')
  assert.doesNotMatch(chain.slice(0, elseAt).split('\n').slice(1).join('\n'), /wx:if=/, 'elif 与 else 之间不得插入新的 wx:if')
  const block = chain.slice(elseAt, chain.indexOf('</block>', elseAt))
  assert.match(block, /<view wx:if="\{\{node\.merchantId\}\}" class="mp-cta__ghost" bindtap="openSafetyRating"/)
  assert.match(block, /<view class="mp-cta__main is-dead" aria-role="status">已核销<\/view>/)
})

test('BL-220 提交中关不掉半屏,回包后照常显示回执;done 态「知道了」照常关', () => {
  const { sheet, requests } = loadSheet()
  const closes = []
  sheet.triggerEvent = name => closes.push(name)
  sheet.open()
  requests[0].success({ code: 200, data: { levels: ['SAFE'] } })
  sheet.onLevel({ detail: { key: 'SAFE' } })
  sheet.onReason({ detail: { value: '灯很亮' } })
  sheet.submit()
  sheet.onClose()
  assert.deepEqual(closes, [], '换商家 ID 挂起时不关')
  requests[1].success({ code: 200, data: { id: 55 } })
  sheet.onClose()
  assert.deepEqual(closes, [], '评价提交挂起时不关')
  requests[2].success({ code: 200, data: { ratingId: 9, overallLevel: 'SAFE', ratingPeriod: '2026-10' } })
  assert.equal(sheet.data.stage, 'done')
  assert.equal(sheet.data.receiptText, '你的感受：安心 · 评价周期 2026-10 · 受理编号 9')
  sheet.onClose()
  assert.deepEqual(closes, ['close'])
})

test('BL-220 提交失败后可以关;未提交时照常关', () => {
  const { sheet, requests } = loadSheet()
  const closes = []
  sheet.triggerEvent = name => closes.push(name)
  sheet.open()
  requests[0].success({ code: 200, data: { levels: ['SAFE'] } })
  sheet.onClose()
  assert.deepEqual(closes, ['close'])
  sheet.open()
  requests[1].success({ code: 200, data: { levels: ['SAFE'] } })
  sheet.onLevel({ detail: { key: 'SAFE' } })
  sheet.onReason({ detail: { value: 'x' } })
  sheet.submit()
  requests[2].fail({ errMsg: 'request:fail timeout' })
  assert.equal(sheet.data.submitting, false)
  sheet.onClose()
  assert.deepEqual(closes, ['close', 'close'])
})

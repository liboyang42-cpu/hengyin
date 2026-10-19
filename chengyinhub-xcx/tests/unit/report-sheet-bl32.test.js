'use strict'
// BL-32 共用举报半屏 + 他人主页「…」举报入口。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const model = require('../../components/cy/report-sheet/model.js')

test('BL-32: taxonomy 只认后端给的原因与规则版本', () => {
  assert.equal(model.normalizeTaxonomy(null), null)
  assert.equal(model.normalizeTaxonomy({ reasons: [{ code: 'SPAM', label: '垃圾' }] }), null)
  assert.equal(model.normalizeTaxonomy({ policyVersion: 'v2', reasons: [] }), null)
  const t = model.normalizeTaxonomy({ policyVersion: ' v2 ', reasons: [{ code: 'SPAM', label: '垃圾', priority: 'P2' }, { code: '', label: 'x' }, null] })
  assert.deepEqual(t.reasons, [{ code: 'SPAM', label: '垃圾' }])
  assert.equal(t.policyVersion, 'v2')
})

test('BL-32: 对象校验、说明校验、幂等键长度', () => {
  assert.equal(model.validTarget('MEMBER', '12'), true)
  assert.equal(model.validTarget('MEMBER', '0'), false)
  assert.equal(model.validTarget('CONTENT', '12'), false)
  assert.equal(model.checkDescription('  ').ok, false)
  assert.equal(model.checkDescription('x'.repeat(2001)).ok, false)
  assert.deepEqual(model.checkDescription(' 骚扰 '), { ok: true, value: '骚扰' })
  const id = model.makeRequestId('MEMBER', 12, 1700000000000, 0.123456789)
  assert.ok(id.length >= 8 && id.length <= 96)
  assert.match(id, /^rpt-member-12-/)
  assert.deepEqual(model.buildPayload({ targetType: 'MEMBER', targetId: '12', reasonCode: 'SPAM', policyVersion: 'v2', description: 'a', requestId: id }),
    { targetType: 'MEMBER', targetId: 12, reasonCode: 'SPAM', policyVersion: 'v2', description: 'a', requestId: id })
  assert.equal(model.receiptId({ id: 9 }), '9')
  assert.equal(model.receiptId({}), '')
})

test('BL-32: 组件走真实接口、无证据上传、回调不用 this 方法简写', () => {
  const js = read('components/cy/report-sheet/index.js')
  assert.match(js, /\/api\/community-trust\/reports\/taxonomy/)
  assert.match(js, /\/api\/community-trust\/reports\/submit/)
  assert.doesNotMatch(js, /evidenceAssetIds/)
  assert.doesNotMatch(js, /success\s*\(res\)\s*\{|fail\s*\(\)\s*\{/)
  const wxml = read('components/cy/report-sheet/index.wxml')
  assert.match(wxml, /<cy-sheet [^>]*title="\{\{title\}\}"/)
})

test('BL-32: 他人主页「…」里有举报，本人与商家主页不出现', () => {
  const wxml = read('subpackageMember/components/cy/profile/index.wxml')
  assert.match(wxml, /<cy-cell wx:if="\{\{!isSelf && !subjectIsMerchant\}\}" title="举报"[^>]*bind:tap="onReportMember"/)
  assert.match(wxml, /<cy-report-sheet wx:if="\{\{!isSelf\}\}" show="\{\{reportShow\}\}" target-type="\{\{reportTargetType\}\}" target-id="\{\{reportTargetId\}\}"/)
  assert.match(read('subpackageMember/components/cy/profile/index.json'), /"cy-report-sheet": "\/components\/cy\/report-sheet\/index"/)
  const js = read('subpackageMember/components/cy/profile/index.js')
  assert.match(js, /onReportMember: function \(\) \{\n    this\.closeMoreMenu\(\);\n    this\.setData\(\{ reportShow: true, reportTargetType: 'MEMBER', reportTargetId: String\(this\.data\.userId \|\| ''\)/)
})

test('BL-33: 商家公开主页「…」举报商家，targetId 用商家档案主键', () => {
  const wxml = read('subpackageMember/components/cy/profile/index.wxml')
  assert.match(wxml, /<cy-cell wx:if="\{\{!isSelf && subjectIsMerchant && subjectMerchant\.id\}\}" title="举报"[^>]*bind:tap="onReportMerchant"/)
  const js = read('subpackageMember/components/cy/profile/index.js')
  assert.match(js, /reportTargetType: 'MERCHANT', reportTargetId: String\(merchantId\)/)
  assert.match(js, /if \(merchantId == null \|\| merchantId === ''\) return;/)
})

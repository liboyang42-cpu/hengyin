'use strict'
// BL-51 举报半屏:原因的首次响应时效说明,只由 taxonomy 字段推出。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const model = require('../../subpackageMember/components/cy/report-sheet/model.js')
const root = path.join(__dirname, '..', '..')

test('BL-51 时效文案:紧急 15 分钟 / 标准 24 小时,字段缺失或非法不显示', () => {
  assert.equal(model.reasonResponseText({ firstResponseMinutes: 15, emergency: true }), '此类举报会优先处理，平台目标在约 15 分钟内首次响应。')
  assert.equal(model.reasonResponseText({ firstResponseMinutes: 1440, emergency: false }), '平台目标在约 24 小时内首次响应。')
  assert.equal(model.reasonResponseText({ firstResponseMinutes: 90 }), '平台目标在约 1 小时 30 分钟内首次响应。')
  assert.equal(model.reasonResponseText({ emergency: true }), '')
  assert.equal(model.reasonResponseText({ firstResponseMinutes: 0 }), '')
  assert.equal(model.reasonResponseText({ firstResponseMinutes: '15' }), '平台目标在约 15 分钟内首次响应。')
  assert.equal(model.reasonResponseText(null), '')
})

test('BL-51 normalizeTaxonomy 带出 responseText,缺字段的原因不带', () => {
  const t = model.normalizeTaxonomy({
    policyVersion: 'COMMUNITY_REPORT_POLICY_V1',
    reasons: [
      { code: 'DANGEROUS', label: '现实人身危险或违法行为', priority: 'URGENT', firstResponseMinutes: 15, emergency: true },
      { code: 'SPAM', label: '垃圾广告或站外导流' },
    ],
  })
  assert.equal(t.reasons[0].responseText, '此类举报会优先处理，平台目标在约 15 分钟内首次响应。')
  assert.equal('responseText' in t.reasons[1], false)
})

test('BL-51 表单页在原因下方显示时效说明,沿用 rs__note 样式', () => {
  const wxml = fs.readFileSync(path.join(root, 'subpackageMember/components/cy/report-sheet/index.wxml'), 'utf8')
  assert.match(wxml, /<text class="rs__note" wx:if="\{\{reasonResponseText\}\}">\{\{reasonResponseText\}\}<\/text>/)
  const js = fs.readFileSync(path.join(root, 'subpackageMember/components/cy/report-sheet/index.js'), 'utf8')
  assert.match(js, /reasonResponseText: reason\.responseText \|\| ''/)
})

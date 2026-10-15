'use strict'
// BL-51 举报半屏显示 taxonomy 自带的首次响应时效与紧急提示。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const model = require('../../components/cy/report-sheet/model.js')

test('BL-51: 时效文案按后端分钟数换算（15 分钟 / 24 小时）', () => {
  assert.equal(model.responseText(15), '平台目标在约 15 分钟内首次响应')
  assert.equal(model.responseText(1440), '平台目标在约 24 小时内首次响应')
  assert.equal(model.responseText(90), '平台目标在约 2 小时内首次响应')
  assert.equal(model.responseText(4320), '平台目标在约 3 天内首次响应')
  assert.equal(model.responseText(0), '')
  assert.equal(model.responseText('x'), '')
})

test('BL-51: 紧急提示只在 emergency 原因且后端给了已知指引码时出现', () => {
  const t = model.normalizeTaxonomy({ policyVersion: 'V1', emergencyGuidanceCode: 'CONTACT_LOCAL_EMERGENCY_SERVICES', reasons: [
    { code: 'DANGEROUS', label: '现实人身危险', firstResponseMinutes: 15, emergency: true },
    { code: 'SPAM', label: '垃圾广告', firstResponseMinutes: 1440, emergency: false },
  ] })
  const urgent = model.reasonNote(t.reasons[0], t.emergencyGuidanceCode)
  assert.match(urgent.emergency, /110/)
  assert.equal(urgent.response, '平台目标在约 15 分钟内首次响应')
  assert.equal(model.reasonNote(t.reasons[1], t.emergencyGuidanceCode).emergency, '')
  assert.equal(model.reasonNote(t.reasons[0], 'UNKNOWN').emergency, '')
})

test('BL-51: 说明步骤渲染两行说明', () => {
  const wxml = read('components/cy/report-sheet/index.wxml')
  assert.match(wxml, /<text wx:if="\{\{reasonEmergency\}\}" class="rs__emergency" aria-role="alert">\{\{reasonEmergency\}\}<\/text>/)
  assert.match(wxml, /<text wx:if="\{\{reasonResponse\}\}" class="rs__note">\{\{reasonResponse\}\}<\/text>/)
})

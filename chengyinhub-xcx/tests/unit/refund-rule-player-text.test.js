'use strict'
// 2026-11-03 业主:玩家视图不得出现与 100/50/0 三档相抵的旧退款文字(包括票上存的 refund_rule)。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const ROOT = path.resolve(__dirname, '../..')
const { refundRuleText } = require('../../pages/topic/utils/refund-rule.js')

test('收费票显示平台三档,不看存的 refund_rule', () => {
  const t = { price: 39.99, mode: 1, refundRule: '开场前24小时可退' }
  assert.strictEqual(refundRuleText(t), '集合前 24 小时以上全额退;24 小时内退 50%;开始后不退')
  assert.strictEqual(refundRuleText({ price: 20, mode: 2, refundRule: '不退' }),
    '开始前 24 小时以上全额退;24 小时内退 50%;开始后不退')
})

test('免费票 / 空票不写退款说明', () => {
  assert.strictEqual(refundRuleText({ price: 0, refundRule: '开场前24小时可退' }), '')
  assert.strictEqual(refundRuleText(null), '')
})

test('主题页场次行与选场次弹层都不再读 refundRule 原文', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/index/index.wxml'), 'utf8')
  assert.doesNotMatch(wxml, /item\.refundRule\b(?!Text)/)
  assert.match(wxml, /退改 · \{\{item\.refundRuleText\}\}/)
  const js = fs.readFileSync(path.join(ROOT, 'pages/topic/index/index.js'), 'utf8')
  assert.match(js, /list\[i\]\['refundRuleText'\] = require\('\.\.\/utils\/refund-rule\.js'\)\.refundRuleText\(list\[i\]\)/)
  const picker = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/session-picker/index.js'), 'utf8')
  assert.match(picker, /rule: refundRuleText\(t\)/)
  assert.doesNotMatch(picker, /t\.refundRule/)
})

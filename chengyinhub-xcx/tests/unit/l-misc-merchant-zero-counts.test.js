'use strict'

// l-misc(业主 round2「统一0」):商家端计数(名单人数、送达、点评条数、回复率……)没有数就写 0,不写「—」。
// 金额类(结算、到账)仍按「未知不写 0」,不在这里。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (p) => fs.readFileSync(path.join(__dirname, '..', '..', p), 'utf8')

test('营销活动四格计数:没有就 0', () => {
  const wxml = read('pages/merchant/crm-campaigns/index.wxml')
  for (const k of ['recipientCount', 'deliveredCount', 'noConsentCount', 'frequencySkippedCount']) {
    assert.ok(wxml.includes(`{{detail.${k} != null ? detail.${k} : 0}}`), k)
  }
  assert.doesNotMatch(wxml, /Count : '—'/)
})

test('评价页:条数与回复率没有就 0', () => {
  const wxml = read('pages/merchant/reviews/index.wxml')
  assert.ok(wxml.includes('{{total != null ? total : 0}} 条已核销评价'))
  assert.ok(wxml.includes('{{replyRatePct != null ? replyRatePct : 0}}%'))
  assert.doesNotMatch(wxml, /replyRatePct \+ '%' : '—'/)
})

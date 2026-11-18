// F12 亲子票(1 大 1 小,照搬携程/美团亲子票):编辑器模板、承接方开关、购票只选儿童年龄、核销页显示构成。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')
const family = require('../../utils/family-ticket.js')

test('票种:模板预填 1 大 1 小与 3–12 岁;只允许 1+0 / 1+1;亲子票年龄段必填', () => {
  const draft = family.familyTicketDraft()
  assert.deepEqual(draft, { name: '亲子票（1 大 1 小）', adultCount: 1, childCount: 1, childAgeMin: 3, childAgeMax: 12 })
  assert.equal(family.familyBlockReason(draft), '')
  assert.equal(family.familyBlockReason({ childCount: 1, childAgeMin: '', childAgeMax: 12 }), '请填写儿童年龄段')
  assert.match(family.familyBlockReason({ childCount: 1, childAgeMin: 12, childAgeMax: 3 }), /起始不大于结束/)
  assert.match(family.familyBlockReason({ childCount: 1, childAgeMin: 3, childAgeMax: 18 }), /0–17/)
  assert.equal(family.familyBlockReason({ childCount: 0 }), '')
  assert.deepEqual(family.familyFields({ childCount: 1, childAgeMin: '3', childAgeMax: '12' }), { adultCount: 1, childCount: 1, childAgeMin: 3, childAgeMax: 12 })
  assert.deepEqual(family.familyFields({ childCount: 0, childAgeMin: 3 }), { adultCount: 1, childCount: 0, childAgeMin: null, childAgeMax: null }, '普通票不带年龄段')
  assert.equal(family.familyLabel({ childCount: 1, childAgeMin: 3, childAgeMax: 12 }), '1 大 1 小（儿童 3–12 岁）')
})

test('编辑器:票种区有「亲子票」模板按钮,亲子票才出年龄段,保存带人数构成', () => {
  const wxml = read('pages/publish/fabu/step3.wxml')
  assert.match(wxml, /bindtap="openFamilyTicketEditor"[^>]*aria-label="添加亲子票（1 大 1 小）"/)
  assert.match(wxml, /<view class="node-card" wx:if="\{\{editingTicket\.childCount == 1\}\}">/)
  assert.match(wxml, /data-field="childAgeMin"/)
  assert.match(wxml, /data-field="childAgeMax"/)
  const js = read('pages/publish/fabu/index.js')
  assert.match(js, /Object\.assign\(ticketData, familyTicket\.familyFields\(et\)\)/)
  assert.match(js, /return familyTicket\.familyBlockReason\(et\);/)
})

test('承接方权益卡:PERK 档有「接受亲子票」开关与儿童份说明,随入驻一起提交', () => {
  const wxml = read('pages/topic/components/cy/chapter-node-form/index.wxml')
  assert.match(wxml, /<cy-switch checked="\{\{form\.familyAccepted\}\}"[^>]*bind:change="onFamilyAcceptedChange"/)
  assert.match(wxml, /wx:if="\{\{termsMode === 'PERK' && form\.familyAccepted\}\}"/)
  const js = read('pages/topic/components/cy/chapter-node-form/index.js')
  assert.match(js, /detail\.familyAccepted = form\.familyAccepted \? 1 : 0;/)
  assert.match(read('pages/topic/merchantinfo/merchantinfo.js'), /payload\.familyAccepted = draft\.familyAccepted \? 1 : 0;/)
})

test('购票:亲子票只选儿童年龄(年龄段内每一岁),没选不下单,建单带 childAge;不要姓名', () => {
  assert.deepEqual(family.childAgeOptions({ childCount: 1, childAgeMin: 3, childAgeMax: 6 }), [3, 4, 5, 6])
  assert.deepEqual(family.childAgeOptions({ childCount: 0 }), [])
  assert.deepEqual(family.familyAgeState({ childCount: 1, childAgeMin: 3, childAgeMax: 4 }),
    { childAgeOptions: [3, 4], childAgeLabels: ['3 岁', '4 岁'], childAge: null })
  const js = read('pages/activity/baoming/baoming.js')
  assert.match(js, /if \(familyTicket\.isFamily\(that\.data\.selectedTicket\) && that\.data\.childAge == null\) \{\s*toast\('请选择儿童年龄'\);/)
  assert.match(js, /if \(childAge != null\) data\.childAge = childAge;/)
  assert.match(js, /const familyAge = familyTicket\.familyAgeState\(ticket\);[^\n]*\n    this\.setData\(\{\n      childAgeOptions: familyAge\.childAgeOptions,/, '换票种时重置年龄')
  const wxml = read('pages/activity/baoming/baoming.wxml')
  assert.match(wxml, /<cy-dropdown wx:if="\{\{childAgeOptions\.length\}\}" range="\{\{childAgeLabels\}\}" bind:change="onChildAgeChange">/)
  assert.doesNotMatch(wxml, /儿童姓名/)
})

test('核销:回执带 party 时结果页显示「亲子票：1 大 1 小(儿童 N 岁)· 儿童份」', () => {
  const { resolveVerificationResult, verificationSheetView } = require('../../utils/merchant-verification.js')
  const label = '亲子票：1 大 1 小（儿童 7 岁）· 儿童份：儿童份小杯'
  const result = resolveVerificationResult({ code: 200, msg: '核销成功', data: { chapterName: '示例站点', perkName: '饮品一杯', party: { adultCount: 1, childCount: 1, childAge: 7, label } } }, '核销')
  assert.equal(result.party, label)
  const view = verificationSheetView(result)
  assert.equal(view.meta, label)
  assert.equal(view.pill, '请提供：饮品一杯')
  const plain = verificationSheetView(resolveVerificationResult({ code: 200, msg: '核销成功', data: { chapterName: '示例站点' } }, '核销'))
  assert.equal(plain.meta, '')
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const { isValidMobile } = require('../../utils/form-state.js')
const src = fs.readFileSync(path.join(__dirname, '../../pages/merchant/apply/index.js'), 'utf8')

test('商家入驻第一步:手机号不是11位手机号时不能继续,并在栏下写明', () => {
  assert.ok(src.includes('ok = !!(d.name && isValidMobile(d.phone) && d.industryIds.length)'))
  assert.ok(src.includes("else if (!isValidMobile(d.phone)) errors.phone = '请填写11位手机号'"))
  assert.equal(isValidMobile('1380000'), false)
  assert.equal(isValidMobile('13800000009'), true)
})

test('俱乐部申请:置灰的下一步被点时,缺的必填写成栏下红字', () => {
  const dir = path.join(__dirname, '../../pages/club/apply')
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  assert.ok(wxml.includes('bind:disabledtap="onNextDisabledTap"'))
  for (const key of ['leaderName', 'phone', 'experience']) {
    assert.ok(wxml.includes(`wx:if="{{fieldErrors.${key}}}" kind="field"`), key)
  }
  assert.ok(js.includes("errors.experience = '请选择你带过多少场'"))
})

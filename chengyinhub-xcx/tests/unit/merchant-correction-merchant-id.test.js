'use strict'

// BL-145:补 #110(BL-67)merchant-correction-sheet/model.js 的 validMerchantId 单测(依 #180 TEST-GAPS.md),不改被测代码。
const test = require('node:test')
const assert = require('node:assert/strict')
const model = require('../../subpackageMember/components/cy/merchant-correction-sheet/model.js')

test('BL-145 validMerchantId:正安全整数(数字或数字字符串)才放行', () => {
  for (const v of [1, 12, '12', ' 12 ', Number.MAX_SAFE_INTEGER, String(Number.MAX_SAFE_INTEGER)]) {
    assert.equal(model.validMerchantId(v), true, JSON.stringify(v))
  }
})

test('BL-145 validMerchantId:0/负数拒绝', () => {
  for (const v of [0, '0', -1, '-1', -0]) {
    assert.equal(model.validMerchantId(v), false, JSON.stringify(v))
  }
})

test('BL-145 validMerchantId:非数字字符串/小数/超安全整数/空值拒绝', () => {
  for (const v of ['', '   ', 'abc', '12abc', '1.5', 1.5, 'NaN', NaN, Infinity, '9007199254740993',
    Number.MAX_SAFE_INTEGER + 1, null, undefined, {}, []]) {
    assert.equal(model.validMerchantId(v), false, JSON.stringify(v))
  }
})

// 严格十进制校验已落地，保持非法写法为硬断言回归。
test('BL-145 validMerchantId:非十进制写法应拒绝', () => {
  for (const v of ['0x10', '0b10', '0o10', '1e3', ' 1e3 ', '+12', '12.0', true, [5]]) {
    assert.equal(model.validMerchantId(v), false, JSON.stringify(v))
  }
})

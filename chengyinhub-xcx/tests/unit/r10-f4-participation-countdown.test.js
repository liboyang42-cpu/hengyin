'use strict'
// 第十轮 f4 第 5 项(f1 遗留):参与详情对 12 小时后开始的活动写「距离路线开始还剩1天」。
// 不足 24 小时按小时说;普通活动说「活动」不说「路线」;24 小时以上仍按中国自然日差。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

function load() {
  let definition
  global.getApp = () => ({ globalData: {}, sendRequest() {}, getRequestErrorMessage(_r, f) { return f } })
  global.wx = { getStorageSync() { return '' }, showToast() {}, getAccountInfoSync() { return { miniProgram: { envVersion: 'release' } } } }
  global.Component = (v) => { definition = v }; global.Page = () => {}; global.Behavior = (v) => v
  global.getCurrentPages = () => []
  const file = path.join(__dirname, '../../subpackageMember/components/scene-member-participation-detail/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  return Object.assign({}, definition, definition.methods)
}

function at(iso, fn) {
  const RealDate = Date
  const fixed = new RealDate(iso).getTime()
  global.Date = class extends RealDate {
    constructor(...args) { if (args.length) super(...args); else super(fixed) }
    static now() { return fixed }
  }
  try { return fn() } finally { global.Date = RealDate }
}

test('12 小时后开始的活动说小时,不说「还剩1天」', () => {
  const vm = load()
  // 北京时间 11-04 22:00,活动 11-05 10:00 开始
  const status = at('2026-11-04T14:00:00Z', () => vm.calculateTimeStatus('2026-11-05 10:00:00', '2026-11-05 12:00:00', '活动'))
  assert.equal(status.remainingDaysText, '距离活动开始还剩12小时')
})

test('两天以后仍按自然日说天数,主题说「路线」', () => {
  const vm = load()
  const status = at('2026-11-04T14:00:00Z', () => vm.calculateTimeStatus('2026-11-07 10:00:00', '2026-11-07 12:00:00', '路线'))
  assert.equal(status.remainingDaysText, '距离路线开始还剩3天')
  assert.equal(status.daysToStart, 3)
})

test('普通活动订单的参与详情把「活动」传进去', () => {
  const vm = load()
  let noun
  vm.calculateTimeStatus = function (s, e, n) { noun = n; return { remainingDaysText: '' } }
  vm.formatDateRange = () => ''
  vm.prepareDisplayData({ ownerType: 2, cmsActivity: { name: '读书夜', startDate: '2026-11-05 10:00:00', endDate: '2026-11-05 12:00:00' } })
  assert.equal(noun, '活动')
})

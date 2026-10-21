'use strict'
// BL-213:纠错半屏成功态「查看我的纠错」连点只跳一次;complete 回调或重新 reset() 后可再次打开。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const FILE = path.join(__dirname, '..', '..', 'subpackageMember/components/cy/merchant-correction-sheet/index.js')
const URL = '/subpackageMember/correction-mine/index'

function boot() {
  const navs = []
  const events = []
  global.getApp = () => ({ globalData: {}, sendRequest: () => ({ abort() {} }) })
  global.wx = { navigateTo: (o) => navs.push(o) }
  let def
  global.Component = (c) => { def = c }
  delete require.cache[require.resolve(FILE)]
  require(FILE)
  const comp = Object.assign({}, def.methods)
  comp.data = JSON.parse(JSON.stringify(def.data))
  comp.properties = { show: true, merchantId: '' }
  comp.setData = function (patch) { Object.assign(this.data, patch) }
  comp.triggerEvent = (name) => events.push(name)
  return { comp, navs, events }
}

test('complete 未回调时连点两次只跳 1 次,close 照常触发', () => {
  const { comp, navs, events } = boot()
  comp.openMine()
  comp.openMine()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, URL)
  assert.ok(events.includes('close'))
})

test('complete 回调后可再次打开', () => {
  const { comp, navs } = boot()
  comp.openMine()
  navs[0].complete()
  comp.openMine()
  assert.equal(navs.length, 2)
})

test('重新 reset() 后可再次打开', () => {
  const { comp, navs } = boot()
  comp.openMine()
  comp.reset()
  comp.openMine()
  assert.equal(navs.length, 2)
})

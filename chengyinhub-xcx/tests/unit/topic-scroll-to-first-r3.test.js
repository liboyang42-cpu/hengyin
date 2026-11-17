'use strict'

// 打磨第 3 轮 §4-11:主题页回到第一屏 350ms,减弱动态时直接到位。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function mount(reducedMotion = false) {
  const scrolls = [], timers = []
  let definition, measured = 0
  global.getApp = () => ({ globalData: {}, sendRequest() { throw new Error('scroll must not request business data') } })
  global.wx = {
    getStorageSync: () => '',
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    pageScrollTo: options => scrolls.push(options),
  }
  global.Page = value => { definition = value }
  global.Component = () => {}
  global.Behavior = value => value
  const file = path.join(__dirname, '../../pages/topic/index/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  const page = { ...definition, data: { ...structuredClone(definition.data), currentScreen: 0, screen2Top: 844, reducedMotion },
    setData(patch) { Object.assign(this.data, patch) },
    _later(ms, callback) { timers.push({ ms, callback }) },
    _measureHero() { measured += 1 },
  }
  return { page, scrolls, timers, measured: () => measured,
    advance() { const next = timers.shift(); assert.ok(next); next.callback() } }
}

test('动画滚动失败仍在封面：complete 不冒充抵达，解锁后可正常重试', () => {
  const h = mount()
  h.page.scrollToSecond(); h.advance()
  assert.equal(h.scrolls[0].scrollTop, 844)
  h.scrolls[0].fail?.({ errMsg: 'pageScrollTo:fail' })
  h.scrolls[0].complete({ errMsg: 'pageScrollTo:fail' })
  assert.equal(h.page.data.currentScreen, 0)
  assert.equal(h.page.data.detailEnter, false)
  assert.equal(h.measured(), 0)
  assert.equal(h.page.data.isScrolling, false)
  assert.equal(h.page.data.coverLeaving, false)
  h.page.scrollToSecond(); h.advance()
  assert.equal(h.scrolls.length, 2)
})

test('动画滚动成功才进入介绍，连点不重复发起滚动且保留退出动画清理', () => {
  const h = mount()
  h.page.scrollToSecond(); h.page.scrollToSecond(); h.advance(); h.page.scrollToSecond()
  assert.equal(h.scrolls.length, 1)
  h.scrolls[0].success?.({ errMsg: 'pageScrollTo:ok' })
  h.scrolls[0].complete({ errMsg: 'pageScrollTo:ok' })
  assert.equal(h.page.data.currentScreen, 1)
  assert.equal(h.page.data.detailEnter, true)
  assert.equal(h.page.data.isScrolling, false)
  assert.equal(h.page.data.coverLeaving, false)
  assert.equal(h.measured(), 1)
  h.advance()
  assert.equal(h.page.data.detailEnter, false)
})

test('减弱动态仍直接滚动，失败不切屏，返回第一屏维持原时长与解锁', () => {
  for (const reduced of [false, true]) {
    const h = mount(reduced)
    if (reduced) {
      h.page.scrollToSecond()
      assert.equal(h.scrolls[0].duration, 0)
      h.scrolls[0].fail()
      assert.equal(h.page.data.currentScreen, 0)
      assert.equal(h.page.data.isScrolling, false)
      h.page.scrollToSecond(); h.scrolls[1].success()
      assert.equal(h.page.data.currentScreen, 1)
    }
    h.page.scrollToFirst()
    const back = h.scrolls.at(-1)
    assert.equal(back.scrollTop, 0)
    assert.equal(back.duration, reduced ? 0 : 350)
    back.success()
    assert.equal(h.page.data.currentScreen, 0)
    assert.equal(h.page.data.isScrolling, false)
  }
})

test('scrollToFirst 时长 350ms,减弱动态为 0', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/topic/index/index.js'), 'utf8')
  const body = js.slice(js.indexOf('  scrollToFirst() {'), js.indexOf('  scrollToFirst() {') + 600)
  assert.match(body, /duration: this\.data\.reducedMotion \? 0 : 350,/)
})

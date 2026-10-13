const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')

const file = path.resolve(__dirname, '../../pages/square/components/cy/popover/index.js')

function load(anchor = { left: 16, top: 200, bottom: 240 }) {
  let config
  const callbacks = []
  const events = []
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component(value) { config = value },
    require: createRequire(file),
    wx: {
      getWindowInfo: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 44, safeArea: { bottom: 778 } }),
      getMenuButtonBoundingClientRect: () => ({ left: 280, top: 48, bottom: 80 }),
    },
  }, { filename: file })
  const instance = Object.assign({}, config.methods, {
    data: { ...config.data, show: true, anchor, items: [{ key: 'profile', label: '访问个人主页' }] },
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    createSelectorQuery() {
      return { select() { return this }, boundingClientRect() { return this }, exec(callback) { callbacks.push(callback) } }
    },
  })
  return { instance, config, events, measure(rect) { callbacks.shift()([rect]) } }
}

function position(instance) {
  const style = instance.data.panelStyle
  return Object.fromEntries(['left', 'top', 'max-height'].map(key => [key, Number(style.match(new RegExp(`${key}:([\\d.]+)px`))[1])]))
}

test('短菜单贴着锚点；测量期间不闪到屏幕左上角', () => {
  const { instance, measure } = load()
  instance.place()
  assert.match(instance.data.panelStyle, /visibility:hidden/)
  measure({ width: 132, height: 100 })
  assert.equal(position(instance).left, 16)
  assert.equal(position(instance).top, 246)
  assert.doesNotMatch(instance.data.panelStyle, /visibility:hidden/)
})

test('右下角长菜单按真实宽高向左、向上收，避开底部安全区', () => {
  const { instance, measure } = load({ left: 340, top: 700, bottom: 744 })
  instance.place()
  measure({ width: 340, height: 210 })
  const p = position(instance)
  assert.equal(p.left, 27)
  assert.equal(p.top, 484)
  assert.ok(p.left + 340 <= 367)
  assert.ok(p.top + 210 <= 778)
})

test('超长菜单限制可滚动高度，不能盖住微信胶囊', () => {
  const { instance, measure } = load({ left: 10, top: 380, bottom: 424 })
  instance.place()
  measure({ width: 350, height: 693 })
  const p = position(instance)
  assert.equal(p['max-height'], 693)
  assert.equal(p.top, 85)
  assert.equal(p.top + 693, 778)
})

test('关闭后迟到的测量不能重新显示菜单', () => {
  const { instance, config, measure } = load()
  instance.place()
  instance.onClose()
  instance.data.show = false
  config.observers['show, anchor, items'].call(instance, false)
  const style = instance.data.panelStyle
  measure({ width: 132, height: 100 })
  assert.equal(instance.data._render, false)
  assert.equal(instance.data.panelStyle, style)
})

test('快速换锚点只接受最后一次测量', () => {
  const { instance, measure } = load()
  instance.place()
  instance.data.anchor = { left: 120, top: 300, bottom: 344 }
  instance.place()
  measure({ width: 340, height: 210 })
  assert.match(instance.data.panelStyle, /visibility:hidden/)
  measure({ width: 132, height: 100 })
  assert.equal(position(instance).left, 120)
  assert.equal(position(instance).top, 350)
})

test('卸载后测量回调不再修改组件', () => {
  const { instance, config, measure } = load()
  instance.place()
  config.lifetimes.detached.call(instance)
  const style = instance.data.panelStyle
  measure({ width: 132, height: 100 })
  assert.equal(instance.data.panelStyle, style)
})

test('无效锚点或面板未量到时关闭，避免留透明遮挡层', () => {
  const { instance, measure } = load({ left: NaN, top: 100 })
  instance.place()
  assert.equal(instance.data._render, false)
  instance.data.anchor = { left: 16, top: 200, bottom: 240 }
  instance.place()
  measure(null)
  assert.equal(instance.data._render, false)
})

test('选中仍先关闭，再按原契约回传 key', () => {
  const { instance, events } = load()
  instance.onPick({ currentTarget: { dataset: { key: 'profile' } } })
  assert.equal(events[0].name, 'close')
  assert.equal(events[1].name, 'pick')
  assert.equal(events[1].detail.key, 'profile')
})

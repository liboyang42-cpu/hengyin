'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ROOT = path.resolve(__dirname, '../..')
const nav = require(path.join(ROOT, 'utils/nav-safe-area.js'))

function attach(relativePath, windowInfo, capsule, legacyApi = false) {
  let definition
  const sandbox = {
    Component(value) { definition = value },
    getApp: () => ({ globalData: { menuButtonInfo: capsule } }),
    wx: {
      getSystemInfoSync: () => windowInfo,
      getMenuButtonBoundingClientRect: () => capsule,
    },
    require(request) {
      if (request.endsWith('nav-safe-area.js')) return nav
      if (request.endsWith('reduced-motion.js')) return {}
      if (/behaviors\/(exit-motion|morph-entrance)\.js$/.test(request)) return () => ({})
      // 2026-10-27 L02:共享弹层套了返回守卫(G-2),沙箱里原样返回组件定义
      if (request.endsWith('native-back-guard.js')) return { withNativeBackGuard: (definition) => definition }
      throw new Error('Unexpected dependency: ' + request)
    },
  }
  if (!legacyApi) sandbox.wx.getWindowInfo = () => windowInfo
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, relativePath), 'utf8'), sandbox)
  const defaults = Object.fromEntries(Object.entries(definition.properties).map(([key, property]) => [key, property.value]))
  const instance = { data: { ...defaults, ...definition.data }, ...definition.methods }
  instance.setData = (patch) => Object.assign(instance.data, patch)
  definition.lifetimes.attached.call(instance)
  return instance.data
}

test('长场景上的确认弹窗必须保持在前景', () => {
  const data = attach('components/cy/scene-sheet/index.js', { windowWidth: 390, statusBarHeight: 47 }, null)
  assert.equal(data.zIndex, 0, '默认不应覆盖共用层级，否则放弃修改弹窗会被长表单挡住')
  const template = fs.readFileSync(path.join(ROOT, 'components/cy/scene-sheet/index.wxml'), 'utf8')
  assert.match(template, /z-index:\{\{zIndex > 0 \? zIndex : 'var\(--cy-z-sheet\)'\}\}/)
  const tokens = fs.readFileSync(path.join(ROOT, 'style/tokens.wxss'), 'utf8')
  const layer = (name) => Number(tokens.match(new RegExp(`--cy-z-${name}:\\s*(\\d+)`))[1])
  assert.ok(layer('modal') > layer('sheet'), '确认弹窗必须压过场景表单')
})

const shells = [
  ['components/cy/sheet/index.js', 'fullMaxH'],
  ['components/cy/date-sheet/index.js', 'panelStyle'],
  ['components/cy/scene-sheet/index.js', 'panelTopPx'],
  ['pages/play/components/story-sheet/index.js', 'panelTop'],
  ['pages/topic/components/project-drawer/index.js', 'panelTop'],
]

for (const device of [
  { name: 'SE', windowWidth: 375, windowHeight: 667, statusBarHeight: 20, capsuleBottom: 64 },
  { name: '刘海屏', windowWidth: 390, windowHeight: 844, statusBarHeight: 47, capsuleBottom: 91 },
  { name: '灵动岛', windowWidth: 393, windowHeight: 852, statusBarHeight: 59, capsuleBottom: 103 },
  { name: '安卓', windowWidth: 360, windowHeight: 780, statusBarHeight: 24, capsuleBottom: 68 },
  { name: '旧窗口接口', windowWidth: 375, windowHeight: 667, statusBarHeight: 20, capsuleBottom: 64, legacyApi: true },
  { name: '胶囊坐标缺失', windowWidth: 393, windowHeight: 852, statusBarHeight: 59 },
]) {
  test(`${device.name}：五类弹窗挂载后均避开微信导航`, () => {
    const capsule = device.capsuleBottom ? {
      left: device.windowWidth - 97,
      top: device.capsuleBottom - 32,
      bottom: device.capsuleBottom,
    } : null
    const expectedTop = capsule ? capsule.bottom + 5 : device.statusBarHeight + 44 + 5
    for (const [file, key] of shells) {
      const data = attach(file, device, capsule, device.legacyApi)
      const actualTop = typeof data[key] === 'number'
        ? data[key] : Number(data[key].match(/100vh - (\d+)px/)[1])
      assert.equal(actualTop, expectedTop, file)
      assert.ok(device.windowHeight - actualTop > 0, `${file} 可用高度必须为正`)
    }
  })
}

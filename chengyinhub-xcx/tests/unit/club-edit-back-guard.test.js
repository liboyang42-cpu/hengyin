const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

function loadEditPage() {
  const sandbox = { nav: 0 }
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 } })
  global.wx = { navigateBack: () => { sandbox.nav += 1 }, switchTab() {} }
  global.Page = (def) => { sandbox.def = def }
  const mod = path.join(ROOT, 'pages/club/edit/index.js')
  delete require.cache[require.resolve(mod)]
  require(mod)
  const page = Object.assign({}, sandbox.def, {
    data: JSON.parse(JSON.stringify(sandbox.def.data)),
    setData(patch) { Object.assign(this.data, patch) },
  })
  return { page, sandbox }
}

test('BL-18 俱乐部资料编辑页挂 page-container 返回守卫', () => {
  const m = read('pages/club/edit/index.wxml').match(/<page-container[\s\S]*?\/>/)
  assert.ok(m, '缺 page-container')
  assert.match(m[0], /show="\{\{!backGuardOff && \(dirty \|\| confirm\.show\)\}\}"/)
  assert.match(m[0], /overlay="\{\{false\}\}"/)
  assert.match(m[0], /bind:afterleave="onNativeBackGuard"/)
})

test('BL-18 物理返回：有改动先弹确认，确认框开着时返回 = 继续编辑，不退页', () => {
  const { page, sandbox } = loadEditPage()
  page.onDirtyChange({ detail: { dirty: true } })
  page.onNativeBackGuard()
  assert.equal(page.data.confirm.show, true)
  assert.equal(page.data.backGuardOff, true, '仍需守卫时先撤再挂')
  page.onNativeBackGuard()
  assert.equal(page.data.confirm.show, false)
  assert.equal(page.data.dirty, true)
  assert.equal(sandbox.nav, 0)
  page.confirmBack()
  assert.equal(sandbox.nav, 1)
})

test('BL-18 无改动时守卫不挂，afterleave 不做任何事', () => {
  const { page, sandbox } = loadEditPage()
  page.onNativeBackGuard()
  assert.equal(page.data.confirm.show, false)
  assert.equal(page.data.backGuardOff, false)
  assert.equal(sandbox.nav, 0)
})

test('BL-18 俱乐部详情里的资料编辑弹窗同样有返回守卫', () => {
  const wxml = read('pages/club/detail/index.wxml')
  const m = wxml.match(/<page-container[\s\S]*?\/>/)
  assert.ok(m)
  assert.match(m[0], /sceneCurrent\.id === 'club-edit' && \(sceneDirty \|\| sceneConfirm\)/)
  assert.match(m[0], /bind:afterleave="onSceneNativeBackGuard"/)
  const js = read('pages/club/detail/index.js')
  const body = js.slice(js.indexOf('onSceneNativeBackGuard()'), js.indexOf('onSceneNativeBackGuard()') + 500)
  assert.ok(body.indexOf('cancelSceneDiscard') < body.indexOf('requestSceneClose'))
  assert.match(body, /sceneBackGuardOff: true/)
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '../..')

// 第十轮 f3 j6 F8:非管理者打开编辑深链时只见守卫的拒绝半屏一种状态,底下不再叠「暂无编辑权限」。
test('编辑深链:守卫报 deny 后不再渲染编辑场景', () => {
  const wxml = fs.readFileSync(path.join(root, 'pages/club/edit/index.wxml'), 'utf8')
  assert.match(wxml, /<cy-access-gate[^>]*bind:state="onGateState"/)
  assert.match(wxml, /<cy-scene-club-edit wx:if="\{\{routeReady && !gateDenied\}\}"/)
  let def
  global.getApp = () => ({ globalData: {} })
  global.Page = d => { def = d }
  const file = path.join(root, 'pages/club/edit/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  const page = Object.assign({}, def, { data: structuredClone(def.data), setData(p) { Object.assign(this.data, p) } })
  page.onGateState({ detail: { state: 'allow' } })
  assert.equal(page.data.gateDenied, false)
  page.data.dirty = true
  page.onGateState({ detail: { state: 'deny' } })
  assert.equal(page.data.gateDenied, true)
  assert.equal(page.data.dirty, false, '拒绝后不能再留返回拦截')
})

test('守卫每次判定后都把 state 报给页面', () => {
  const js = fs.readFileSync(path.join(root, 'components/cy/access-gate/index.js'), 'utf8')
  // 2026-11-09 qx-a6:放行时多带已归一的岗位权限 access(店铺参谋经营优化卡按它显隐),state 照旧每次都报
  assert.match(js, /triggerEvent\('state', \{ state: that\.data\.state, access: /)
})

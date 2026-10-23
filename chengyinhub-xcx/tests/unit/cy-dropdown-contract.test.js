// cy-dropdown 契约(2026-08-25)
//
// 它是全仓 28 处 <picker range=...> 的替代品。能不能安全替换,全看一件事:
// **API 与原生 picker 逐字兼容** —— range / range-key / value 语义相同,
// bind:change 的 detail.value 同样是【下标】而不是值本身。
// 只要这条成立,迁移就只是改标签名,调用方的 JS 处理器一行都不用动;
// 一旦漂移成「回传值本身」,所有调用方的 options[index] 都会取到 undefined 而且不报错。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { flattenComponentToPage } = require('../helpers/component-as-page')

const ROOT = path.resolve(__dirname, '../..')
const REL = 'components/cy/dropdown/index.js'
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

// behaviors/*.js 是被 createRequire 在 Node 模块作用域里加载的,不走 vm 沙箱,
// 所以 Behavior/wx 必须先挂到 global 上,否则 require 那一刻就 ReferenceError。
function withGlobals(fn) {
  const savedBehavior = global.Behavior
  const savedWx = global.wx
  global.Behavior = (b) => b
  global.wx = { getStorageSync: () => null, getWindowInfo: () => ({ windowWidth: 375, windowHeight: 667 }) }
  try { return fn() } finally {
    if (savedBehavior === undefined) delete global.Behavior; else global.Behavior = savedBehavior
    if (savedWx === undefined) delete global.wx; else global.wx = savedWx
  }
}

function loadDropdown(source = read(REL)) {
  const abs = path.join(ROOT, REL)
  let captured = null
  const events = []
  withGlobals(() => vm.runInNewContext(source, {
    console,
    getApp: () => ({ globalData: {} }),
    Component(config) { captured = flattenComponentToPage(config) },
    Behavior: (b) => b,
    require: createRequire(abs),
    setTimeout,
    clearTimeout,
    wx: {
      getWindowInfo: () => ({ windowWidth: 375, windowHeight: 667 }),
      getStorageSync: () => null,
    },
  }, { filename: abs }))
  assert.ok(captured, 'cy-dropdown 必须注册 Component')

  const instance = Object.assign({}, captured, {
    data: Object.assign({}, captured.data, { disabled: false, _closing: false, labels: [], value: 0 }),
  })
  instance.setData = (patch, cb) => {
    Object.assign(instance.data, patch)
    if (typeof cb === 'function') cb()
  }
  instance.triggerEvent = (name, detail) => events.push({ name, detail })
  return { instance, events }
}

test('labels 派生:字符串数组、对象+range-key、对象缺 key 都不能崩', () => {
  const { instance } = loadDropdown()
  const derive = instance.__observers__ || null
  // observers 不经 flatten 暴露,直接验证与它等价的规则在组件源码里存在
  const src = read(REL)
  assert.match(src, /rangeKey \? item\[rangeKey\]/, 'range-key 必须用于对象项取名')
  assert.match(src, /item\.name \|\| item\.label/, '没给 range-key 时要有可预期的保底，不能显示 [object Object]')
  assert.equal(derive, null)
})

test('★ bind:change 回传的是下标,不是值本身(与原生 picker 一致)', () => {
  const { instance, events } = loadDropdown()
  instance.data.labels = ['甲', '乙', '丙']
  instance.data.value = 0
  instance.onPick({ currentTarget: { dataset: { index: '2' } } })
  assert.equal(events.length, 1)
  assert.equal(events[0].name, 'change')
  assert.equal(events[0].detail.value, 2, 'detail.value 必须是下标；回传值本身会让所有调用方 options[index] 变 undefined')
  assert.equal(typeof events[0].detail.value, 'number', '下标必须是数字，字符串会让 === 比较全部落空')
})

test('退场动画播放期间不再接受选择,避免关闭途中又发一次 change', () => {
  const { instance, events } = loadDropdown()
  instance.data._closing = true
  instance.onPick({ currentTarget: { dataset: { index: '1' } } })
  assert.equal(events.length, 0)
})

test('disabled 时点击不展开', () => {
  const { instance } = loadDropdown()
  instance.data.disabled = true
  instance.data.show = false
  instance.onTrigger()
  assert.equal(instance.data.show, false)
})

test('面板走 fixed 层:调用方常把选择器放在 overflow:hidden 容器里', () => {
  const wxss = read('components/cy/dropdown/index.wxss')
  assert.match(wxss, /\.cdd__layer\s*\{[^}]*position:\s*fixed/, '面板层必须 fixed,absolute 会被祖先裁掉')
  assert.match(wxss, /\.cdd__mask\s*\{[^}]*background:\s*transparent/, 'dropdown 是轻量态，不压暗整页')
  const wxml = read('components/cy/dropdown/index.wxml')
  assert.match(wxml, /class="cdd__mask"[^>]*bindtap="onMask"/, '点外部必须能收起')
})

test('negative control:detail.value 改成回传值本身必须判红', () => {
  const src = read(REL).replace(
    "this.triggerEvent('change', { value: index });",
    "this.triggerEvent('change', { value: this.data.labels[index] });",
  )
  assert.notEqual(src, read(REL), '负控锚点失效')
  assert.match(src, /value: this\.data\.labels\[index\]/)
  // 用改过的源码跑同一条断言,必须炸
  const abs = path.join(ROOT, REL)
  let captured = null
  const events = []
  withGlobals(() => vm.runInNewContext(src, {
    console, getApp: () => ({ globalData: {} }),
    Component(config) { captured = flattenComponentToPage(config) },
    Behavior: (b) => b, require: createRequire(abs), setTimeout, clearTimeout,
    wx: { getWindowInfo: () => ({ windowWidth: 375, windowHeight: 667 }), getStorageSync: () => null },
  }, { filename: abs }))
  const inst = Object.assign({}, captured, { data: { labels: ['甲', '乙', '丙'], value: 0, _closing: false } })
  inst.setData = (p) => Object.assign(inst.data, p)
  inst.triggerEvent = (n, d) => events.push({ n, d })
  inst.onPick({ currentTarget: { dataset: { index: '2' } } })
  assert.throws(() => assert.equal(events[0].d.value, 2), assert.AssertionError)
})


test('延迟打开去重，关闭后不能被定时器重新打开', async () => {
  const { instance } = loadDropdown()
  instance.data.labels = ['甲']
  instance.data.measureDelay = 20
  let opened = 0
  instance.openAtAnchor = () => { opened++ }
  instance.onTrigger()
  const timer = instance._openTimer
  instance.onTrigger()
  assert.equal(instance._openTimer, timer)
  instance.close()
  await new Promise(resolve => setTimeout(resolve, 40))
  assert.equal(opened, 0)
})

test('关闭使已发起的锚点测量失效', () => {
  const { instance } = loadDropdown()
  instance.data.labels = ['甲']
  let receive
  const query = { select() { return this }, boundingClientRect() { return this }, selectViewport() { return this }, scrollOffset() { return this }, exec(fn) { receive = fn } }
  instance.createSelectorQuery = () => query
  instance.openAtAnchor()
  instance.close()
  receive([{ top:100, bottom:144, right:200, width:180 }])
  assert.equal(instance.data.show, false)
})

test('RUN-003: 空选项不打开空面板，自定义面板仍可打开', () => {
  const { instance } = loadDropdown()
  let opened = 0
  instance.openAtAnchor = () => { opened++ }
  instance.onTrigger()
  assert.equal(opened, 0)
  instance.data.customPanel = true
  instance.onTrigger()
  assert.equal(opened, 1)
})

// 2026-10-22 主人定稿推翻 RUN-012 的位置:勾移到文字右侧(与 Figma 标签、原型一致),未选中行仍保留同宽位置
test('选择勾在文字后，未选中行保留同宽位置', () => {
  const wxml = read('components/cy/dropdown/index.wxml')
  assert.ok(wxml.indexOf('cdd__check') > wxml.indexOf('cdd__item-txt'))
  assert.match(wxml, /<view class="cdd__check">/)
})

// 2026-10-10 两列卡片档(主人:下拉弹层一行两项)
test('两列档:不可选卡片(带原因)点了不收起、不回传;可选卡片照常回传下标', () => {
  const { instance, events } = loadDropdown()
  instance.data.labels = ['选项问答', '九宫格']
  instance.data.cells = [{ label: '选项问答', sub: '文字 / 图片选项', reason: '' }, { label: '九宫格', sub: '', reason: '在主题里配置' }]
  instance.data.columns = 2
  instance.onPick({ currentTarget: { dataset: { index: '1' } } })
  assert.equal(events.length, 0, '不可选项不能回传 change')
  instance.onPick({ currentTarget: { dataset: { index: '0' } } })
  assert.equal(events.length, 1)
  assert.equal(events[0].detail.value, 0)
})

test('说明档(原两列档)改为单列菜单:名称+说明/原因、勾在文字后、header 插槽,不再排两列、行间无分隔线', () => {
  const wxml = read('components/cy/dropdown/index.wxml')
  assert.match(wxml, /wx:elif="\{\{columns > 1\}\}"/)
  assert.match(wxml, /<slot name="header" \/>/)
  assert.match(wxml, /\{\{item\.reason \|\| item\.sub\}\}/, '不可选时原因代替说明显示')
  assert.match(wxml, /class="cdd__item \{\{index === value/, '单列档条目必须仍在')
  assert.match(wxml, /class="cdd__item cdd__cell /, '说明档用同一种菜单行')
  assert.doesNotMatch(wxml, /cdd__grid/, '不再排两列卡片')
  const wxss = read('components/cy/dropdown/index.wxss')
  assert.doesNotMatch(wxss, /grid-template-columns/, '不再一行两项')
  assert.doesNotMatch(wxss, /\.cdd__item[^{]*\{[^}]*border-(top|bottom)/, '菜单行之间不画分隔线')
})

test('说明档无说明时与普通行同高、贴着字段弹出；R5 编辑器下拉不显示灰色说明，只保留不可选原因', () => {
  const src = read(REL)
  assert.match(src, /compact: !cells\.some\(function \(cell\) \{ return cell\.sub \|\| cell\.reason; \}\)/, '全部无说明/原因才紧凑')
  assert.match(src, /that\.data\.compact \? CELL_H_COMPACT_RPX : CELL_H_RPX/, '面板高度跟随紧凑档')
  assert.match(src, /const CELL_H_COMPACT_RPX = ITEM_H_RPX;/, '无说明的行与普通行同高')
  assert.doesNotMatch(src, /windowWidth \|\| 375\) - 24 : rect\.width/, '不再铺满屏宽')
  const temp = read('pages/publish/temp/index.wxml')
  // 2026-10-23 主人:「形式」下拉去掉了(每种形式在选择玩法里各是一枚标签),只剩参与方式这一个
  assert.doesNotMatch(temp, /range="\{\{r5Editor\.forms\}\}"/, '卡里不再有「形式」下拉')
  for (const key of ['r5Editor.participationOptions']) {
    const tags = temp.match(new RegExp('<cy-dropdown[^>]*range="\\{\\{' + key.replace('.', '\\.') + '\\}\\}"[^>]*>', 'g')) || []
    assert.ok(tags.length > 0, key)
    for (const tag of tags) { assert.doesNotMatch(tag, /sub-key=/, key + ' 不显示灰色说明'); assert.match(tag, /reason-key="reason"/, key + ' 仍显示不可选原因') }
  }
  assert.doesNotMatch(read('pages/publish/fabu/zero-hp-editor.wxml'), /sub-key=/, '归零策略下拉不显示灰色说明')
})

// 2026-10-22 主人定稿:选择玩法 = 半屏弹层 + Figma 胶囊标签,所有分类排成一条只左右滑;互动工具从底部弹出的标签里加
test('选择玩法：半屏标签条按分类左右滑，只列玩法（不含互动工具），页头左退出右完成，底部可预览', () => {
  const temp = read('pages/publish/temp/index.wxml')
  const sheet = temp.slice(temp.indexOf('选择玩法 sheet(2026-10-22'), temp.indexOf('添加互动工具(底部弹出'))
  assert.match(sheet, /wx:for="\{\{ r5Editor\.gameGroups \}\}"/, '分组来自玩法分类')
  assert.match(sheet, /<scroll-view scroll-x class="cg-tstrip"/, '标签条只左右滑')
  assert.match(sheet, /cg-edit-head__exit[^>]*bindtap="closeR5GameSheet"[^>]*>退出<\/view>/)
  assert.match(sheet, /cg-edit-head__done[^>]*bindtap="confirmR5GameSheet"[^>]*>完成<\/view>/)
  assert.match(sheet, /bindtap="previewGameFromSheet"/, '选中后可以预览')
  assert.doesNotMatch(sheet, /toolSelected|addR5Tool|toolOptions/, '玩法列表里没有互动工具')
  assert.doesNotMatch(temp, /range="\{\{r5Editor\.gameItems\}\}"/, '不再用两列下拉选玩法')
  assert.match(temp, /bindtap="openToolPicker"/, '互动工具从底部弹出的标签里单独添加')
  assert.match(temp, /bindtap="pickToolTag"/)
})

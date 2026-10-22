'use strict'
/**
 * L11(owner 2026-10-22)· cy-tag-sheet:Figma「Edit Interests」胶囊半屏弹窗,标签横向翻页。
 * 覆盖:分页数 / 点选切换 / max 上限 + toast / change·confirm·close 事件 / 图标映射 / 门店信息接线。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const DIR = 'pages/merchant/components/cy/tag-sheet'
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const COMPONENT_PATH = path.join(ROOT, DIR, 'index.js')
const model = require(path.join(ROOT, DIR, 'model.js'))

const items = (n) => Array.from({ length: n }, (_, i) => ({ key: 'k' + i, label: '标签' + i, icon: '' }))

function create(properties) {
  const prevComponent = global.Component
  let def
  global.Component = (options) => { def = options }
  delete require.cache[COMPONENT_PATH]
  require(COMPONENT_PATH)
  global.Component = prevComponent
  const props = {}
  Object.keys(def.properties).forEach(k => { props[k] = def.properties[k].value })
  const c = {
    data: Object.assign(JSON.parse(JSON.stringify(def.data)), props, properties),
    events: [],
    setData(update) { Object.assign(this.data, update) },
    triggerEvent(name, detail) { this.events.push({ name, detail }) },
  }
  Object.assign(c, def.methods)
  c._def = def
  def.observers.value.call(c, c.data.value)
  return c
}

const tap = (c, key) => c.onTap({ currentTarget: { dataset: { key } } })

test('页数 = ceil(items / (pageRows × pageCols)),默认 4 行 2 列一页 8 个', () => {
  assert.equal(model.buildPages(items(0), [], 4, 2).length, 0)
  assert.equal(model.buildPages(items(8), [], 4, 2).length, 1)
  assert.equal(model.buildPages(items(9), [], 4, 2).length, 2)
  assert.equal(model.buildPages(items(17), [], 4, 2).length, 3)
  assert.equal(model.buildPages(items(9), [], 2, 2).length, 3)
  const pages = model.buildPages(items(10), ['k9'], 4, 2)
  assert.deepEqual(pages.map(p => p.length), [8, 2])
  assert.equal(pages[1][1].checked, true)
  const c = create({ items: items(10) })
  assert.equal(c.data._pages.length, 2)
  assert.equal(c.data._swiperH, 4 * 94 + 3 * 20, 'swiper 高按满页 4 行算')
})

test('点选切换:选中 → 再点取消,每次发 change(detail.value)', () => {
  const c = create({ items: items(3) })
  tap(c, 'k1')
  tap(c, 'k2')
  assert.deepEqual(c.data._sel, ['k1', 'k2'])
  assert.equal(c.data._pages[0][1].checked, true)
  tap(c, 'k1')
  assert.deepEqual(c.data._sel, ['k2'])
  assert.deepEqual(c.events.map(e => e.name), ['change', 'change', 'change'])
  assert.deepEqual(c.events.at(-1).detail, { value: ['k2'] })
})

test('max:到上限再点未选中的不改选中、弹 toast;取消已选不受限', () => {
  const shown = []
  global.wx = { showToast: (o) => shown.push(o.title) }
  try {
    const c = create({ items: items(4), value: ['k0', 'k1'], max: 2 })
    tap(c, 'k2')
    assert.deepEqual(c.data._sel, ['k0', 'k1'])
    assert.equal(c.events.length, 0)
    assert.deepEqual(shown, ['最多选 2 个'])
    tap(c, 'k0')
    assert.deepEqual(c.data._sel, ['k1'])
    assert.equal(model.toggleValue(['a'], 'b', 0).length, 2, 'max=0 不限')
  } finally { delete global.wx }
})

test('confirm 带当前选中;close 发 close;saving 期间点选/保存/关闭都不响应', () => {
  const c = create({ items: items(3), value: ['k0'] })
  c.onConfirm()
  c.onClose()
  assert.deepEqual(c.events, [{ name: 'confirm', detail: { value: ['k0'] } }, { name: 'close', detail: undefined }])
  c.events.length = 0
  c.data.saving = true
  tap(c, 'k1'); c.onConfirm(); c.onClose()
  assert.equal(c.events.length, 0)
})

test('图标映射:owner 给的行业 / 经营类目对照;未入库的图标不出路径', () => {
  const pairs = {
    餐饮美食: 'noodles', 咖啡茶饮: 'goblet-full', 酒吧: 'goblet-full', 运动健身: 'sport', 瑜伽: 'viencharts',
    球类: 'tennis', 户外出行: 'outdoor', 极限运动: 'parachute', 水上运动: 'ripple', KTV: 'voice',
    音乐演出: 'music', 文化艺术: 'platte', 娱乐休闲: 'game-handle', 摄影: 'camera', 零售购物: 'weixin-market',
    城市运动: 'sport', 户外活动: 'outdoor', 健康生活: 'viencharts', 美食: 'noodles', 咖啡: 'goblet-full',
    手作: 'platte', 亲子: 'game-handle', 夜生活: 'music', 宠物友好: '',
  }
  Object.entries(pairs).forEach(([label, icon]) => assert.equal(model.tagIconName(label), icon, label))
  assert.equal(model.tagIconSrc('not-shipped-name'), '')
  model.SHIPPED.forEach(name => {
    assert.ok(fs.existsSync(path.join(ROOT, model.ICON_DIR, name + '.svg')), name + '.svg 已入库')
  })
})

test('模板:原生横向 swiper + 页码点 + 底部保存;只用 cy-sheet,不进主包', () => {
  const wxml = read(DIR + '/index.wxml')
  assert.match(wxml, /<cy-sheet[^>]*\bhandle\b[^>]*\bfooter\b/)
  assert.match(wxml, /<swiper[^>]*bindchange="onSwipe"/)
  assert.doesNotMatch(wxml, /<swiper[^>]*vertical/)
  assert.match(wxml, /ts__dot/)
  assert.match(wxml, /slot="footer"[\s\S]*bindtap="onConfirm"/)
  assert.match(wxml, /已选 \{\{_sel\.length\}\}/)
  const json = JSON.parse(read(DIR + '/index.json'))
  assert.equal(json.usingComponents['cy-sheet'], '/components/cy/sheet/index')
  const wxss = read(DIR + '/index.wxss')
  assert.doesNotMatch(wxss, /prefers-color-scheme/, '商家白底档,不跟系统深色')
  assert.match(wxss, /--cy-comp-sheet-role-glass: #FFFFFF/)
})

test('门店信息:经营类目 / 行业类型改挂 cy-tag-sheet,沿用 chipSheet 数据与保存', () => {
  const wxml = read('pages/merchant/decor/index.wxml')
  const block = wxml.slice(wxml.indexOf('<cy-tag-sheet'), wxml.indexOf('/>', wxml.indexOf('<cy-tag-sheet')))
  assert.match(block, /items="\{\{chipSheet\.options\}\}"/)
  assert.match(block, /value="\{\{chipSheet\.value\}\}"/)
  assert.match(block, /max="\{\{chipSheet\.kind === 'category' \? 5 : 0\}\}"/)
  assert.match(block, /bind:change="onChipSheetChange"/)
  assert.match(block, /bind:confirm="saveChipSheet"/)
  assert.match(block, /bind:close="closeChipSheet"/)
  assert.doesNotMatch(wxml, /dc-chip/)
  const json = JSON.parse(read('pages/merchant/decor/index.json'))
  assert.equal(json.usingComponents['cy-tag-sheet'], '/pages/merchant/components/cy/tag-sheet/index')
})

test('门店信息:onChipSheetChange 把组件回传的选中落回 options.checked 与 value(选项顺序,首个仍是主行业)', () => {
  const vm = require('../helpers/ui-sandbox-vm.js')
  const { createRequire } = require('node:module')
  const abs = path.join(ROOT, 'pages/merchant/decor/index.js')
  const requests = []
  let def
  const localRequire = createRequire(abs)
  vm.runInNewContext(read('pages/merchant/decor/index.js'), {
    Array, Date, JSON, Math, Number, Object, RegExp, String, clearTimeout, setTimeout,
    getApp: () => ({ globalData: {}, getUserID: () => '7', sendRequest: (r) => requests.push(r) }),
    getCurrentPages: () => [{}, {}],
    Page(config) { def = config },
    require(request) {
      if (request.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} }
      return localRequire(request)
    },
    wx: { getSystemInfoSync: () => ({ statusBarHeight: 20 }), showToast() {} },
  }, { filename: abs })
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.setData = function (patch) {
    Object.entries(patch).forEach(([k, v]) => {
      const parts = k.split('.'); let cur = this.data
      parts.slice(0, -1).forEach(p => { cur = cur[p] })
      cur[parts.at(-1)] = v
    })
  }
  page.data.m = { memberId: 3, categoryIds: '12' }
  page._categoryRows = [{ id: 11, categoryName: '美食烹饪' }, { id: 12, categoryName: '运动健身' }]
  page.goCategory()
  assert.deepEqual(JSON.parse(JSON.stringify(page.data.chipSheet.value)), ['12'])
  page.onChipSheetChange({ detail: { value: ['12', '11'] } })
  assert.deepEqual(JSON.parse(JSON.stringify(page.data.chipSheet.value)), ['11', '12'], '按选项顺序,不按点选顺序')
  page.saveChipSheet()
  assert.deepEqual(JSON.parse(requests.at(-1).data), { categoryIds: '11,12' })
})

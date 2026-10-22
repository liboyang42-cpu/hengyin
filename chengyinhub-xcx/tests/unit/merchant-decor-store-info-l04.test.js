'use strict'
/**
 * L04(owner 2026-10-22 拍板)· 品牌中心 + 门店信息:
 *  (a) 选图跳裁剪页会让 page-container 守卫 leave → 以前 closeImage 把编辑器清空,上传回来的图被静默丢掉;
 *  (c) 经营类目/行业类型改底部 sheet 胶囊多选;联系电话/门店微信可编辑并校验;微信号与二维码拆开;
 *      经营时间改底部 sheet;返回页面不再插「正在刷新/已保存」横幅。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js')
const { createRequire } = require('node:module')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const DECOR_JS = 'pages/merchant/decor/index.js'

function applyPatch(target, patch) {
  Object.entries(patch).forEach(([key, value]) => {
    const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
    let cursor = target
    for (let i = 0; i < parts.length - 1; i += 1) {
      if (!cursor[parts[i]] || typeof cursor[parts[i]] !== 'object') cursor[parts[i]] = {}
      cursor = cursor[parts[i]]
    }
    cursor[parts.at(-1)] = value
  })
}

function loadPage() {
  const absolutePath = path.join(ROOT, DECOR_JS)
  const requests = []
  const picks = []
  let definition
  const app = {
    globalData: { navBarHeight: 44 },
    getUserID: () => '7',
    sendRequest(request) { requests.push(request) },
    getRequestErrorMessage(error, fallback) { return error && error.msg || fallback },
    chooseImage(callback, count, options) { picks.push({ callback, count, options: options || {} }) },
  }
  const wx = {
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    navigateBack() {}, navigateTo() {}, showToast() {},
    setNavigationBarColor() {}, setBackgroundColor() {},
  }
  const localRequire = createRequire(absolutePath)
  vm.runInNewContext(read(DECOR_JS), {
    Array, Date, JSON, Math, Number, Object, RegExp, String, clearTimeout, setTimeout,
    getApp: () => app,
    getCurrentPages: () => [{}, {}],
    Page(config) { definition = config },
    require(request) {
      if (request.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} }
      return localRequire(request)
    },
    wx,
  }, { filename: absolutePath })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function (patch, cb) { applyPatch(this.data, patch); if (typeof cb === 'function') cb.call(this) }
  page.selectComponent = () => ({ close() {}, open() {} })
  return { page, requests, picks }
}

const body = (request) => JSON.parse(request.data)
// 页面跑在 vm 沙箱里,数组/对象来自另一个 realm:先过一遍 JSON 再 deepEqual
const plain = (value) => JSON.parse(JSON.stringify(value))

test('(a) 选图跳裁剪页:守卫 leave 不关编辑器,回页不刷新,上传结果进预览并可保存', () => {
  const h = loadPage()
  h.page.data.m = { memberId: 3, logo: 'old.png' }
  h.page.data.loadState = 'ok'
  h.page._loadedOnce = true
  h.page.data.merchantAccess = { canWriteProfile: true }
  h.page.openImage('logo')
  h.page.chooseEditImage()
  assert.equal(h.picks.length, 1)
  assert.equal(h.picks[0].options.cropScale, '1:1')
  assert.equal(h.page.data.imagePicking, true, '离页前必须先标记 picking,让 page-container 守卫让位')

  h.page.onHide()               // 相册/裁剪页盖上来
  h.page.onNativeBackGuard()    // 离页触发 afterleave
  assert.ok(h.page.data.imageEditor, '选图离页不是返回键,不能关图片编辑')
  const before = h.requests.length
  h.page.onShow()               // 裁剪完回到本页
  assert.equal(h.page.data.imagePicking, false, '回页后重新挂上返回守卫')
  const reloads = h.requests.slice(before).filter(r => /\/api\/merchant\/(info|coop-profile)$/.test(r.url))
  assert.equal(reloads.length, 0, '回页时上传还没落地,不能先刷新资料把随后的保存挡掉')
  assert.equal(h.page.data.refreshing, false)

  h.picks[0].callback(['https://cdn.example/cropped.png'])
  assert.equal(h.page.data.imageEditor.value, 'https://cdn.example/cropped.png', '裁好的图显示为编辑器预览')
  h.page.saveImage()
  const save = h.requests.at(-1)
  assert.equal(save.url, '/api/merchant/update')
  assert.deepEqual(body(save), { logo: 'https://cdn.example/cropped.png' })
  save.success({ code: 200 })
  assert.equal(h.page.data.m.logo, 'https://cdn.example/cropped.png', '保存后品牌中心头像立即更新')
  assert.equal(h.page.data.imageEditor, null)
})

test('(a) 编辑器在选图期间被关掉,上传结果也不丢:重新打开编辑器带上新图', () => {
  const h = loadPage()
  h.page.data.m = { memberId: 3, coverImage: 'old-cover.png' }
  h.page.openImage('coverImage')
  h.page.chooseEditImage()
  assert.equal(h.picks[0].options.cropScale, '5:3')
  h.page.setData({ imageEditor: null })
  h.picks[0].callback(['https://cdn.example/cover.png'])
  assert.deepEqual(plain(h.page.data.imageEditor),
    { key: 'coverImage', value: 'https://cdn.example/cover.png', initial: 'old-cover.png' })
})

test('(c) 经营类目:固定词库胶囊多选,保留已有自定义值,以「、」拼回 preference', () => {
  const h = loadPage()
  h.page.data.m = { memberId: 3, preference: '美食 · 老街小馆' }
  h.page.openPreference()
  const sheet = h.page.data.chipSheet
  assert.equal(sheet.show, true)
  assert.deepEqual(plain(sheet.options.slice(0, 10).map(o => o.label)),
    ['城市运动', '户外活动', '健康生活', '美食', '咖啡', '手作', '亲子', '夜生活', '文化艺术', '宠物友好'])
  assert.deepEqual(plain(sheet.options.filter(o => o.checked).map(o => o.label)), ['美食', '老街小馆'])
  h.page.toggleChip({ currentTarget: { dataset: { key: '咖啡' } } })
  h.page.saveChipSheet()
  const save = h.requests.at(-1)
  assert.equal(save.url, '/api/merchant/update')
  assert.deepEqual(body(save), { preference: '美食、咖啡、老街小馆' })
})

test('(c) 行业类型:常见行业短表映射到真实分类记录,多选带图标,存 categoryIds', () => {
  const h = loadPage()
  h.page.data.m = { memberId: 3, categoryId: 42 }
  h.page.goCategory()
  const list = h.requests.at(-1)
  assert.equal(list.url, '/api/category/list')
  list.success({ code: 200, data: [
    { id: 11, categoryName: '美食烹饪' }, { id: 12, categoryName: '运动健身' },
    { id: 13, categoryName: '家政维修' }, { id: 42, categoryName: '冷门旧类目' },
  ] })
  const options = h.page.data.chipSheet.options
  assert.deepEqual(plain(options.map(o => o.label)), ['餐饮美食', '运动健身', '冷门旧类目'],
    '只出短表里认领到的行业 + 已选的旧类目,不平铺整张分类表')
  // L11:图标改用 cy-tag-sheet 的 Figma 图标表,映射不到的(冷门旧类目)按 owner 口径留空
  assert.deepEqual(plain(options.map(o => o.icon)), ['noodles', 'sport', ''])
  assert.equal(options.filter(o => o.checked).map(o => o.key).join(), '42')
  h.page.toggleChip({ currentTarget: { dataset: { key: '11' } } })
  h.page.saveChipSheet()
  const save = h.requests.at(-1)
  assert.equal(save.url, '/api/merchant/decor/save')
  assert.deepEqual(body(save), { categoryIds: '11,42' })
  save.success({ code: 200 })
  assert.equal(h.page.data.catNames, '美食烹饪 · 冷门旧类目', '行上与重进页面一致,显示真实分类名')
  assert.equal(h.page.data.chipSheet.show, false)
})

test('(c) 行业类型最多选 5 个', () => {
  const h = loadPage()
  h.page.data.m = { memberId: 3, categoryIds: '1,2,3,4,5' }
  h.page._categoryRows = [1, 2, 3, 4, 5, 6].map(id => ({ id, categoryName: '类' + id }))
  h.page.goCategory()
  h.page.toggleChip({ currentTarget: { dataset: { key: '6' } } })
  assert.equal(h.page.data.chipSheet.options.filter(o => o.checked).length, 5)
})

test('(c) 联系电话 11 位手机号校验;门店微信号只收字母数字 _ -', () => {
  const h = loadPage()
  h.page.data.m = { memberId: 3, phone: '13800000000', wechat: '' }
  h.page.openField({ currentTarget: { dataset: { k: 'phone' } } })
  assert.equal(h.page.data.fieldSheet.inputType, 'number')
  h.page.onFieldInput({ detail: { value: '1380000' } })
  const before = h.requests.length
  h.page.confirmField()
  assert.equal(h.requests.length, before, '非 11 位手机号不发请求')
  h.page.onFieldInput({ detail: { value: ' 13912345678 ' } })
  h.page.confirmField()
  assert.deepEqual(body(h.requests.at(-1)), { phone: '13912345678' })

  const w = loadPage()
  w.page.data.m = { memberId: 3, wechat: '' }
  w.page.openField({ currentTarget: { dataset: { k: 'wechat' } } })
  w.page.onFieldInput({ detail: { value: '微信号' } })
  w.page.confirmField()
  assert.equal(w.requests.length, 0)
  w.page.onFieldInput({ detail: { value: 'cy_store-01' } })
  w.page.confirmField()
  assert.deepEqual(body(w.requests.at(-1)), { wechat: 'cy_store-01' })
})

test('(c) 旧数据 wechat 里的二维码 URL 不当微信号文本显示;二维码按自由比例上传', () => {
  const h = loadPage()
  h.page.data.m = { memberId: 3, wechat: 'https://cdn.example/qr.png' }
  h.page.refreshContact()
  assert.equal(h.page.data.wechatId, '')
  assert.equal(h.page.data.wechatQr, 'https://cdn.example/qr.png')
  h.page.data.m = { memberId: 3, wechat: 'cy_store', wechatQrcode: 'https://cdn.example/new-qr.png' }
  h.page.refreshContact()
  assert.equal(h.page.data.wechatId, 'cy_store')
  assert.equal(h.page.data.wechatQr, 'https://cdn.example/new-qr.png')

  h.page.data.basicEditing = true
  h.page.onQrcodeTap()
  const pick = h.picks.at(-1)
  assert.equal(pick.options.crop, undefined, '二维码不能按固定比例裁,会裁掉定位角')
  assert.equal(pick.options.cropScale, undefined)
  pick.callback(['https://cdn.example/qr2.png'])
  assert.deepEqual(body(h.requests.at(-1)), { wechatQrcode: 'https://cdn.example/qr2.png' })
})

test('(c) 门店信息结构:二维码独立一行、经营时间是底部 sheet、返回不插横幅', () => {
  const wxml = read('pages/merchant/decor/index.wxml')
  const basic = wxml.split(`wx:elif="{{view === 'basic'}}"`)[1].split(`wx:elif="{{view === 'qualification'}}"`)[0]
  assert.doesNotMatch(basic, /入驻时填写，修改请联系平台/)
  assert.doesNotMatch(basic, /m\.wechat \|\|/, '门店微信不能直接把 wechat 列(可能是 URL)当文本')
  assert.match(basic, /title="门店二维码"/)
  assert.match(basic, /data-action="openHours"/)
  assert.doesNotMatch(basic, /id="category-menu"/, '行业类型不再是下拉小面板')
  const scroll = wxml.slice(wxml.indexOf('<scroll-view'), wxml.indexOf('</scroll-view>'))
  assert.doesNotMatch(scroll, /<decor-hours/, '经营时间 sheet 挂在 scroll-view 外,不被滚动容器裁切')
  assert.doesNotMatch(wxml, /dc-refreshing|dc-save-receipt/)
  const hours = read('pages/merchant/decor/components/hours/index.wxml')
  assert.match(hours, /<cy-sheet/)
  assert.doesNotMatch(hours, /<cy-dropdown/)
  assert.match(read(DECOR_JS), /toast\.success\(receipt\)/, '保存确认改用现有 toast')
})

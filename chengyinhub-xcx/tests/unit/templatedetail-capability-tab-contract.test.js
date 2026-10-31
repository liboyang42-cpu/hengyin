/* CU-M-130 · 模板详情「能力」标签与内容对不上(2026-09-24 走查 R50)
 * 2026-10-29 V-11:业主改判「每个模板都要有 详情 / 能力」,下面「不给 tab」的几条已翻转。
 *
 * 隔离模板的「能力」页只有「来源：城市运动装备馆」+ 一句和主按钮重复的说明 ——
 * 来源就是头部的发布者署名,场景在详情栏已经铺成 chips,能力 tab 里独有的只有「方式」。
 * 三者只剩署名时不给这个 tab(而不是继续摆一个点进去没内容的标签)。
 *
 * 判据写在页面里(patchNormalized / applyDetailPayload 都算好的 capRows),测试用 vm 装真页面跑,
 * 不复制一份判据到测试里。
 */
'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')
const { loadMediaPreview } = require('../helpers/media-preview-sandbox.js')

const ROOT = path.resolve(__dirname, '../..')
const SOURCE = fs.readFileSync(path.join(ROOT, 'pages/templatedetail/templatedetail.js'), 'utf8')

function loadPage() {
  let definition = null
  vm.runInNewContext(SOURCE, {
    console,
    getApp: () => ({
      globalData: { statusBarHeight: 20, navBarHeight: 44 },
      getUserRole: () => 'player',
      getUserType: () => '2',
      getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
      sendRequest() {},
    }),
    Page(value) { definition = value },
    require(request) {
      if (request === './utils/scroll-motion.js') return { applyHeroRecede: () => false }
      if (request === './utils/templateDetail.js') return require(path.join(ROOT, 'pages/templatedetail/utils/templateDetail.js'))
      if (request === './shelf/art.js') return require(path.join(ROOT, 'pages/templatedetail/shelf/art.js'))
      if (request === '../../utils/mockData.js') return { getTemplateById: () => null }
      if (request === '../../utils/analytics.js') return { track() {} }
      if (request === '../../utils/identity/identity-policy.js') return require(path.join(ROOT, 'utils/identity/identity-policy.js'))
      if (request === '../../utils/merchant-theme.js') return { merchantPageShow() {}, merchantPageRestore() {} }
      if (request === '../../utils/response-shape.js') return require(path.join(ROOT, 'utils/response-shape.js'))
      if (request === '../../utils/media-preview.js') return loadMediaPreview({ previewImage() {} })
      throw new Error(`unexpected require: ${request}`)
    },
    wx: {
      getStorageSync: () => '',
      setStorageSync() {},
      navigateTo() {}, switchTab() {}, navigateBack() {}, redirectTo() {},
      getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
      createAnimation: () => ({}),
      nextTick: (cb) => cb(),
    },
  })
  assert.ok(definition, 'templatedetail 页面没有注册 Page()')
  return { page: definition }
}

/** patchNormalized 是 dev mock 与 onLoad 共用的那条装配路。 */
function normalizedTarget(raw, startTab) {
  const { page } = loadPage()
  const target = { scope: 'library' }
  if (startTab) target.activeTab = startTab
  page.patchNormalized.call(page, target, raw)
  return target
}

function tabsOf(source) {
  return Array.from((source && source.tplTabs) || [], (tab) => tab.key)
}

const WITH_WAY = { id: 7, title: '站起来回答', validationMethodStr: '选项验证', publisher: '城市运动装备馆' }
const ONLY_SOURCE = { id: 8, title: '口令换券', publisher: '城市运动装备馆' }

test('CU-M-130 有能力字段的模板照常给「能力」tab', () => {
  const target = normalizedTarget(WITH_WAY)
  assert.deepEqual(tabsOf(target), ['details', 'capabilities'])
  assert.deepEqual(Array.from(target.capRows, (row) => row.key), ['way', 'from'])
})

// 2026-10-29 V-11 业主:每个模板都给「详情 / 能力」两个 tab(原 CU-M-130「只剩署名不给 tab」按业主决定翻转)。
test('V-11 只剩署名的模板也给「能力」tab,深链停在能力', () => {
  const target = normalizedTarget(ONLY_SOURCE, 'capabilities')
  assert.deepEqual(tabsOf(target), ['details', 'capabilities'])
  assert.equal(target.activeTab, 'capabilities')
})

test('V-11 真接口那条路(applyDetailPayload)同样恒给两个 tab', () => {
  const { page } = loadPage()
  let patch = null
  page.setData = (value) => { patch = value }
  page.data = { scope: 'library', activeTab: 'capabilities' }
  page.applyDetailPayload.call(page, ONLY_SOURCE)
  assert.deepEqual(tabsOf(patch), ['details', 'capabilities'])
  assert.equal(patch.activeTab, undefined, '不再把深链改回详情')
  page.applyDetailPayload.call(page, WITH_WAY)
  assert.deepEqual(tabsOf(patch), ['details', 'capabilities'])
})

test('V-11 b27 机制型官方模板(validation_method=0,无「方式」行)也有「能力」tab', () => {
  const target = normalizedTarget({
    id: 9, title: '一桌任务宾果', publisher: '城瘾Hub', usageLocation: '餐厅/咖啡馆/茶饮店', validationMethod: 0,
    sysCategoryList: [{ categoryName: '美食烹饪' }],
  })
  assert.deepEqual(Array.from(target.capRows, (row) => row.key), ['scene', 'from'])
  assert.deepEqual(tabsOf(target), ['details', 'capabilities'])
})

test('V-11 负控:能力 tab 回到按「方式」行条件给出时判红', () => {
  assert.doesNotMatch(SOURCE, /hasCapabilityInfo/, '按行判 tab 的条件不得回潮')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/templatedetail/templatedetail.wxml'), 'utf8')
  assert.match(wxml, /<view class="xb-caps" wx:else>\s*<view class="xb-caprow"><text class="xb-caprow-k">—<\/text><\/view>/, '没有能力行时显示「—」,不出空白')
})

test('RUN-024 分类名称含中点也保持一个标签，不能伪装商家类型', () => {
  const target = normalizedTarget({ id: 9, title: '分类测试', sysCategoryList: [{ categoryName: 'CU走查·解谜互动' }] })
  assert.deepEqual(Array.from(target.sceneChips), ['CU走查·解谜互动'])
  // 2026-10-30 业主「不能只是占位」:每个分类配自己的 3D 图标,对不上关键词用 other
  assert.deepEqual(Array.from(target.sceneIcons), ['/pages/templatedetail/art/cat/other.png'])
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/templatedetail/templatedetail.wxml'), 'utf8')
  assert.doesNotMatch(wxml, /推荐商家类型/)
})

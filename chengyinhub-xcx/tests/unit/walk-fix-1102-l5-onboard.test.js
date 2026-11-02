const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// walk-fix 2026-11-02 l5-onboard:M-08 / M-09 / M-20 的页面行为。

const ROOT = path.resolve(__dirname, '../..')

global.getApp = global.getApp || (() => new Proxy({ globalData: {} }, { get(t, k) { return k in t ? t[k] : () => '' } }))
global.wx = global.wx || new Proxy({}, { get() { return () => ({}) } })

function loadPage(rel, extraWx) {
  let definition
  const navs = []
  const base = Object.assign({ navigateTo: (o) => navs.push(o), getStorageSync: () => '', getSystemInfoSync: () => ({}), getWindowInfo: () => ({}) }, extraWx || {})
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } } }, {
    get(t, k) { return k in t ? t[k] : () => '' },
  })
  const pageDir = path.dirname(path.join(ROOT, rel))
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, rel), 'utf8'), {
    getApp: () => app,
    Page(page) { definition = page },
    Component() {},
    require(request) { return require(path.resolve(pageDir, request)) },
    Set, Map, Date, JSON, Promise, wx, setTimeout: (f) => { f(); return 0 }, clearTimeout() {}, console,
  }, { filename: path.join(ROOT, rel) })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) {
      for (const [k, v] of Object.entries(patch)) {
        const parts = k.split('.'); let o = this.data
        while (parts.length > 1) { const p = parts.shift(); o[p] = o[p] || {}; o = o[p] }
        o[parts[0]] = v
      }
      if (cb) cb.call(this)
    },
  })
  return { page, navs }
}

test('M-08:从「去完善品类」回来,重拉可承接章节并把选章节弹层原样打开', () => {
  const { page, navs } = loadPage('pages/topic/merchantinfo/merchantinfo.js')
  let reloads = 0
  page.loadMerchantRecruitmentChapters = () => { reloads += 1 }
  page.data.topicShow = true
  page.goMerchantCategory()
  assert.equal(navs[0].url, '/pages/merchant/decor/index')
  assert.equal(page.data.topicShow, false)
  page._projectReadOwner = undefined
  page.onShow()
  assert.equal(reloads, 1)
  assert.equal(page._reopenAfterCategory, true, '章节拉回来后按最新章节重算招商状态并重开弹层')
  page.onShow()
  assert.equal(reloads, 1, '普通返回不重复拉')
  const src = fs.readFileSync(path.join(ROOT, 'pages/topic/merchantinfo/merchantinfo.js'), 'utf8')
  assert.match(src, /if \(that\._reopenAfterCategory\) \{ that\._reopenAfterCategory = false; that\.bmClick2\(\{ currentTarget: \{ dataset: \{ apply: 1 \} \} \}\); \}/)
})

test('M-09:选章节弹层只选章节,不再出玩法模板块', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.wxml'), 'utf8')
  assert.doesNotMatch(wxml, /选择玩法模板|去创建玩法|ctp-tpl/)
  const js = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.js'), 'utf8')
  assert.doesNotMatch(js, /\/api\/template\/my-list/)
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.wxss'), 'utf8')
  const card = wxss.slice(wxss.indexOf('.ctp-card {'), wxss.indexOf('}', wxss.indexOf('.ctp-card {')))
  assert.match(card, /background: var\(--cy-bg-card-2\)/, '未选章节卡要和 sheet 底色区分开')
})

test('M-20:置灰发布点出的奖励缺项滚到「完成奖励」卡,模块被移除时退回模块区', () => {
  const { page } = loadPage('pages/publish/temp/index.js')
  page.data.moduleList = [{ key: 'reward' }]
  page.data.gameKey = 'qa'
  page.scrollToError('reward')
  assert.equal(page.data.scrollIntoView, 'sec-reward')
  page.data.moduleList = []
  page.scrollToError('reward')
  assert.equal(page.data.scrollIntoView, 'sec-module')
})

test('M-09:from=join 发布成功回报名表并回传新玩法,不 reLaunch 模板库', () => {
  const backs = []
  const launches = []
  const { page } = loadPage('pages/publish/temp/index.js', {
    navigateBack: (o) => backs.push(o), reLaunch: (o) => launches.push(o), showToast() {},
  })
  const emitted = []
  page.getOpenerEventChannel = () => ({ emit: (name, payload) => emitted.push([name, payload]) })
  page.data.formData = { title: '回信藏在哪' }
  const src = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.js'), 'utf8')
  assert.match(src, /\} else if \(that\.data\.from === 'join' && !isDraft\) \{ that\._returnToJoinForm\(createdId, current\); \}/)
  page._returnToJoinForm(91, () => true)
  assert.equal(JSON.stringify(emitted), JSON.stringify([['templateCreated', { id: 91, title: '回信藏在哪' }]]))
  assert.equal(backs.length, 1)
  assert.equal(launches.length, 0)
})

test('M-17:无封面的广场卡收拢高度;权益核销卡写明怎么结算', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/coop-center/index.wxml'), 'utf8')
  assert.match(wxml, /item\.cover && !item\._coverFailed \? '' : 'cc-route-cover--bare'/)
  assert.match(wxml, /wx:if="\{\{item\.settleText\}\}"/)
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/merchant/coop-center/index.wxss'), 'utf8')
  assert.match(wxss, /\.cc-route-cover--bare \{ height: auto; \}/)
  let definition
  global.Page = (config) => { definition = config }
  const sent = []
  const prevApp = global.getApp
  global.getApp = () => ({ globalData: {}, sendRequest(o) { sent.push(o) }, getRequestErrorMessage: (r, f) => f, tips() {} })
  global.getCurrentPages = () => [{ route: 'pages/merchant/coop-center/index' }]
  const mod = path.join(ROOT, 'pages/merchant/coop-center/index.js')
  delete require.cache[require.resolve(mod)]
  require(mod)
  global.getApp = prevApp
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) })
  page.setData = function (patch) { Object.assign(this.data, patch) }
  assert.ok(definition, 'coop-center 页面已注册')
  const src = fs.readFileSync(mod, 'utf8')
  assert.match(src, /modes\.length === 1 && modes\[0\] === 'PERK' \? '玩家到店核销你的权益，平台不按次结算现金' : ''/)
})

test('M-16:入驻第一步用行业类型胶囊问一次,必选,提交带 categoryIds', () => {
  const { page } = loadPage('pages/merchant/apply/index.js')
  page.data.step = 1
  page.data.name = '梧桐里书房'
  page.data.phone = '13800001111'
  page.validate()
  assert.equal(page.data.canNext, false, '没选行业类型不能继续')
  page.data.industrySheet = { show: true, options: [{ key: '4003', label: '书店' }, { key: '4002', label: '茶馆' }], value: [] }
  page.confirmIndustrySheet({ detail: { value: ['4003', '4002'] } })
  assert.equal(JSON.stringify(page.data.industryIds), JSON.stringify(['4003', '4002']))
  assert.equal(page.data.industryNames, '书店 · 茶馆')
  assert.equal(page.data.industrySheet.show, false)
  assert.equal(page.data.canNext, true)
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/apply/index.wxml'), 'utf8')
  assert.doesNotMatch(wxml, /placeholder="如 餐饮、零售、文创"/, '自由填写的经营类目已换成胶囊')
  assert.match(wxml, /bindtap="openIndustrySheet"/)
  const src = fs.readFileSync(path.join(ROOT, 'pages/merchant/apply/index.js'), 'utf8')
  assert.match(src, /categoryIds: d\.industryIds\.join\(','\),/)
})

test('M-16:行业短表覆盖书店、茶馆、画廊、手作、古着、文创、花店', () => {
  const { buildIndustryOptions } = require('../../utils/merchant-industry.js')
  const rows = ['咖啡', '茶馆', '书店', '艺术展览', '手作工坊', '古着中古', '文创杂货', '花店', '城市漫步']
    .map((categoryName, i) => ({ id: 100 + i, categoryName }))
  const labels = buildIndustryOptions(rows, ['102']).map(o => o.label)
  for (const want of ['咖啡茶饮', '茶馆', '书店', '画廊展览', '手作工坊', '古着中古', '文创杂货', '花店']) assert.ok(labels.includes(want), want)
  assert.ok(!labels.includes('城市漫步'), '主题向类别不进商家短表')
  assert.equal(buildIndustryOptions(rows, ['102']).filter(o => o.checked).map(o => o.key).join(), '102')
})

test('M-19:置灰的「提交申请」点了滚到经营者实名', () => {
  const scrolls = []
  const { page } = loadPage('pages/merchant/apply/index.js', { pageScrollTo: (o) => scrolls.push(o.selector) })
  page.data.step = 4
  page.data.identityRegistered = false
  page.onNextDisabledTap()
  assert.equal(JSON.stringify(scrolls), JSON.stringify(['#apply-identity']), '页面本身滚动(scroll-view 不定高),用 pageScrollTo')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/apply/index.wxml'), 'utf8')
  assert.match(wxml, /id="apply-identity"/)
})

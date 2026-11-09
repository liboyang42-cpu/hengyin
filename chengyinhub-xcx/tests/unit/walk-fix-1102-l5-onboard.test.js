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
  return { page, navs, app }
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

function mountPicker(myListRows) {
  let def
  const sent = []
  const rel = 'pages/topic/components/cy/chapter-target-picker/index.js'
  const app = { sendRequest(o) { sent.push(o) }, getRequestErrorMessage: (r, f) => f }
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, rel), 'utf8'), {
    getApp: () => app, Component(c) { def = c }, console,
  }, { filename: path.join(ROOT, rel) })
  const c = Object.assign({}, def.methods, {
    data: JSON.parse(JSON.stringify(def.data)),
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent() {},
  })
  const observe = (show, chapters, state) => def.observers['show, chapters, chaptersState'].call(c, show, chapters, state)
  const reply = (rows) => sent[sent.length - 1].success({ code: '200', data: { rows } })
  return { c, sent, observe, reply }
}

test('M-09(第三轮):选章节弹层恢复玩法模板块(稿 159:346),三态各自成立', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.wxml'), 'utf8')
  assert.match(wxml, /<view class="ctp-h">选择玩法模板<\/view>/)
  // 第四轮(2026-11-03):「只有玩法」只给零章节主题(emptyTopic);章节都没开时出关闭空态
  assert.match(wxml, /<block wx:if="\{\{!noChapters \|\| !emptyTopic\}\}">\s*<view class="ctp-h">选择章节<\/view>/)
  assert.match(wxml, /<block wx:if="\{\{templatesState !== 'empty' && !\(noChapters && !emptyTopic\)\}\}">\s*<view class="ctp-h">选择玩法模板<\/view>/)
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.wxss'), 'utf8')
  const card = wxss.slice(wxss.indexOf('.ctp-card {'), wxss.indexOf('}', wxss.indexOf('.ctp-card {')))
  assert.match(card, /background: var\(--cy-bg-card-2\)/, '未选章节卡要和 sheet 底色区分开')
  assert.match(wxss, /\.ctp-card\.is-on \{ background: var\(--cy-text-title\); \}/)

  // 章节 + 玩法
  const both = mountPicker()
  both.observe(true, [{ id: 7, name: '第一章' }], 'ready')
  assert.equal(both.sent[0].url, '/api/template/my-list')
  both.reply([{ id: 3, title: '回信藏在哪' }])
  assert.equal(both.c.data.noChapters, false)
  assert.equal(both.c.data.templatesState, 'ready')
  // 只有章节:没有玩法 → 玩法块不出(templatesState=empty)
  const onlyChapters = mountPicker()
  onlyChapters.observe(true, [{ id: 7, name: '第一章' }], 'ready')
  onlyChapters.reply([])
  assert.equal(onlyChapters.c.data.templatesState, 'empty')
  assert.equal(onlyChapters.c.data.noChapters, false)
  // 只有玩法:没有可选章节 → 章节块不出,下一步仍要章节(服务端「缺少章节」)
  const onlyTpl = mountPicker()
  onlyTpl.observe(true, [], 'ready')
  onlyTpl.reply([{ id: 3, title: '回信藏在哪' }])
  assert.equal(onlyTpl.c.data.noChapters, true)
  assert.equal(onlyTpl.c.data.templatesState, 'ready')
  // 加载中不算「没有章节」
  const loading = mountPicker()
  loading.observe(true, [], 'loading')
  assert.equal(loading.c.data.noChapters, false)
})

test('M-09(第三轮):回到弹层时重拉玩法,新建的玩法立刻出现;选中的玩法随确认带出', () => {
  const p = mountPicker()
  p.observe(true, [{ id: 7, name: '第一章' }], 'ready')
  p.reply([])
  assert.equal(p.c.data.templatesState, 'empty')
  p.observe(true, [{ id: 7, name: '第一章' }], 'ready')
  assert.equal(p.sent.length, 1, '同一次打开里章节刷新不重复拉玩法')
  p.observe(false, [], 'ready')
  p.observe(true, [{ id: 7, name: '第一章' }], 'ready')
  assert.equal(p.sent.length, 2, '重新打开就重拉')
  assert.equal(p.c.data.templatesState, 'empty', '静默刷新不闪骨架')
  p.reply([{ id: 91, title: '新玩法' }])
  assert.equal(p.c.data.templatesState, 'ready')
  const events = []
  p.c.triggerEvent = (name, detail) => events.push([name, detail])
  p.c.onPickTemplate({ currentTarget: { dataset: { id: 91 } } })
  p.c.onNext()
  assert.equal(events[0][0], 'confirm')
  assert.equal(events[0][1].chapterId, 7)
  assert.equal(events[0][1].templateId, 91)
  assert.equal(events[0][1].templateName, '新玩法')
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

test('M-17:无封面的广场卡收拢高度;卡上不加结算说明行', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/coop-center/index.wxml'), 'utf8')
  assert.match(wxml, /item\.cover && !item\._coverFailed \? '' : 'cc-route-cover--bare'/)
  assert.doesNotMatch(wxml, /settleText/)
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
  assert.doesNotMatch(src, /平台不按次结算现金|settleText/)
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
  const { buildIndustryOptions } = require('../../pages/merchant/utils/merchant-industry.js')
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
  // 2026-11-03 集成:l3 第五轮 D 改为滚到第一条红字;姓名缺失时第一条红字就是实名块里的「姓名」栏,
  // 所以仍是滚到经营者实名(落在块内第一栏)。只差单独同意时没有红字,滚到 #apply-identity 本身。
  assert.equal(JSON.stringify(scrolls), JSON.stringify(['#apply-field-realName']), '页面本身滚动(scroll-view 不定高),用 pageScrollTo')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/apply/index.wxml'), 'utf8')
  assert.match(wxml, /id="apply-identity"/)
  assert.ok(wxml.indexOf('id="apply-identity"') < wxml.indexOf('id="apply-field-realName"'), '姓名栏在实名块内')
})

// ---------- 第四轮(2026-11-03 业主) ----------

test('R4:申请承接章节弹层不列灰色事实行;玩法卡只留封面、名称和一行截断的描述', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.wxml'), 'utf8')
  const js = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.js'), 'utf8')
  assert.doesNotMatch(wxml, /ctp-fact|pickedFacts/)
  assert.doesNotMatch(js, /pickedFacts|factsOf/)
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.wxss'), 'utf8')
  const sub = wxss.slice(wxss.indexOf('.ctp-tpl-s {'), wxss.indexOf('}', wxss.indexOf('.ctp-tpl-s {')))
  assert.match(sub, /white-space: nowrap/)
  assert.match(sub, /text-overflow: ellipsis/)
  // 选中章节后也不再算事实
  const p = mountPicker()
  p.observe(true, [{ id: 7, name: '第一章', categoryLabel: '书店', perkMinValueLabel: '权益零售价不少于 ¥30' }], 'ready')
  assert.equal(p.c.data.pickedFacts, undefined)
})

test('R4:零章节主题只选玩法就能下一步,确认带 emptyTopic 且没有 chapterId', () => {
  const p = mountPicker()
  p.c.data.emptyTopic = true
  p.observe(true, [], 'ready')
  p.reply([{ id: 3, title: '回信藏在哪' }])
  assert.equal(p.c.data.noChapters, true)
  const events = []
  p.c.triggerEvent = (name, detail) => events.push([name, detail])
  p.c.onPickTemplate({ currentTarget: { dataset: { id: 3 } } })
  p.c.onNext()
  assert.equal(events.length, 1)
  assert.equal(events[0][1].emptyTopic, true)
  assert.equal(events[0][1].chapterId, 0)
  assert.equal(events[0][1].templateId, 3)
  // 有章节但都没开(emptyTopic=false):不出按钮,onNext 也不放行
  const closed = mountPicker()
  closed.observe(true, [], 'ready')
  closed.reply([{ id: 3, title: '回信藏在哪' }])
  const ev2 = []
  closed.c.triggerEvent = (name, detail) => ev2.push([name, detail])
  closed.c.onPickTemplate({ currentTarget: { dataset: { id: 3 } } })
  closed.c.onNext()
  assert.equal(ev2.length, 0, '章节都没开时不能绕过主办方招商')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-target-picker/index.wxml'), 'utf8')
  assert.match(wxml, /<cy-btn wx:if="\{\{!\(noChapters && !emptyTopic\)\}\}"/, '关闭空态不留死按钮')
})

test('R4:零章节报名直接走 chapter-node/submit 带 topicId,不先调 chapter-application/apply', () => {
  const { page, app } = loadPage('pages/topic/merchantinfo/merchantinfo.js')
  const sent = []
  page.data.topicId = 990020
  page.data.chapterEmptyTopic = true
  page.data.chapterNodeFormMode = 'application'
  page.data.info = { memberId: 1 }
  page.loadMerchantRecruitmentChapters = () => sent.push({ url: 'reload' })
  page.loadMyChapterNodes = () => {}
  page.loadMyChapterApplications = () => {}
  const original = app.sendRequest
  app.sendRequest = (o) => { sent.push(o); return { abort() {} } }
  try {
    page.onChapterNodeFormSubmit({ detail: { chapterId: 0, name: '街角书店', templateId: 3, address: 'a', longitude: '1', latitude: '2', message: '想承接' } })
  } finally { app.sendRequest = original }
  const urls = sent.map((o) => o.url)
  assert.ok(!urls.includes('/api/merchant/chapter-application/apply'))
  const submit = sent.find((o) => o.url === '/api/merchant/chapter-node/submit')
  assert.ok(submit, '要直接提交点位')
  const body = JSON.parse(submit.data)
  assert.equal(body.topicId, 990020)
  assert.equal(body.chapterId, undefined)
  assert.equal(body.message, '想承接')
  // 非零章节主题、没有 chapterId:什么都不发
  const second = loadPage('pages/topic/merchantinfo/merchantinfo.js')
  const again = second.page
  again.data.chapterEmptyTopic = false
  const sent2 = []
  second.app.sendRequest = (o) => { sent2.push(o); return { abort() {} } }
  again.onChapterNodeFormSubmit({ detail: { chapterId: 0, name: 'x', templateId: 3 } })
  assert.equal(sent2.length, 0)
})

test('R4:报名表把权益门槛挂在「常备权益」字段下,所选权益低于门槛时字段下红字并拦提交', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-node-form/index.wxml'), 'utf8')
  assert.match(wxml, /零售价不少于 \{\{money\.amount\(perkMinText\)\}\}/)
  assert.match(wxml, /<cy-inline-error wx:if="\{\{perkBelowMin\}\}" kind="error"/)
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-node-form/index.json'), 'utf8'))
  assert.equal(json.usingComponents['cy-inline-error'], '/components/cy/inline-error/index')
  const host = fs.readFileSync(path.join(ROOT, 'pages/topic/merchantinfo/merchantinfo.wxml'), 'utf8')
  assert.match(host, /perkMinValue="\{\{selectedChapterPerkMin\}\}"/)
})

test('R4 M-16:商家侧读 type=MERCHANT(商家行业),主题类别与路线偏好仍读 type=1', () => {
  const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
  assert.match(read('pages/merchant/decor/index.js'), /url:'\/api\/category\/list', method:'POST', data:\{type:'MERCHANT',parentid:0\}/)
  assert.match(read('pages/merchant/apply/index.js'), /url: '\/api\/category\/list', method: 'POST', data: \{ type: 'MERCHANT', parentid: '0' \}/)
  const fabu = read('pages/publish/fabu/index.js')
  const recruit = fabu.slice(fabu.indexOf('  loadMerchantCategories() {'), fabu.indexOf('  _updateChapterRecruitConfig('))
  assert.match(recruit, /data: \{ type: 'MERCHANT', parentid: '0' \}/, '章节招募品类 = 商家行业')
  assert.match(read('pages/searchmap/index.wxml'), /<cy-category-sheet show="\{\{merchantCategorySheetVisible\}\}" type="MERCHANT"/)
  // 主题类别(发布页)与玩家路线偏好不变
  assert.match(read('pages/publish/fabu/index.wxml'), /<cy-category-sheet show="\{\{categorySheetVisible\}\}" type="\{\{1\}\}"/)
  assert.match(read('pages/gerenziliao/gerenziliao.wxml'), /<cy-category-sheet class="profile-edit-sheet" show="\{\{categorySheetVisible\}\}" type="\{\{1\}\}"/)
  // 组件 type 属性要能收字符串 MERCHANT
  assert.match(read('components/cy/category-sheet/index.js'), /type: \{ type: null, value: 0 \}/)
  const sql = fs.readFileSync(path.join(ROOT, '../chengyinhub-admin/sql/migration_20261102_merchant_industry_categories.sql'), 'utf8')
  assert.match(sql, /SELECT 6, 0, n\.name/)
  assert.doesNotMatch(sql, /SELECT 1, 0, n\.name/)
})

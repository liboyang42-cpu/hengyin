// 第 2 轮 W5 尾项(F-W5-01/03/05/06/10/11)在 PR #5 之上补的缺口。
// 每条:行为(跑真代码)或展示绑定 + 负控(把修复改回去必须真红)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function rule(css, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matched = css.match(new RegExp('(?:^|\\n|\\})\\s*' + escaped + '\\s*\\{([^}]*)\\}'))
  assert.ok(matched, '缺少样式规则 ' + selector)
  return matched[1]
}

function mutated(source, from, to) {
  assert.ok(source.includes(from), '负控锚点失效: ' + from)
  return source.replace(from, to)
}

// ---------- F-W5-01 帖子正文:多段短文也要能展开 ----------

function loadPostCard(source) {
  let definition
  vm.runInNewContext(source || read('components/cy/post-card/index.js'), {
    Component: (value) => { definition = value },
  }, { filename: 'post-card.js' })
  const card = { data: Object.assign({}, definition.data) }
  card.setData = (patch) => Object.assign(card.data, patch)
  return { card, observer: definition.observers['post.contents'] }
}

test('F-W5-01 帖子卡:超过 40 字或换行成 3 段以上才给「展开」', () => {
  const { card, observer } = loadPostCard()
  observer.call(card, '入社须知\n1. 准时集合\n2. 带水')
  assert.equal(card.data.textLong, true, '短句多段的公告收起态会被夹成 2 行，必须能展开')
  observer.call(card, '一行就说完')
  assert.equal(card.data.textLong, false)
  observer.call(card, '第一段\n第二段')
  assert.equal(card.data.textLong, false, '两段刚好占两行，不夹')
  observer.call(card, 'x'.repeat(41))
  assert.equal(card.data.textLong, true)
  observer.call(card, undefined)
  assert.equal(card.data.textLong, false)

  const wxml = read('components/cy/post-card/index.wxml')
  assert.match(wxml, /wx:if="\{\{expandableText && textLong\}\}" catchtap="toggleText"/, '展开按钮独立于点卡片(评论)')
  assert.doesNotMatch(wxml, /post\.contents\.length > 40/, '展开判据只认 textLong 一处')
  assert.match(rule(read('components/cy/post-card/index.wxss'), '.post-card__text--expanded'), /white-space:\s*pre-wrap/, '展开后保留换行')
})

test('F-W5-01 负控:展开判据退回只看字数,多段短文必须真红', () => {
  const source = mutated(read('components/cy/post-card/index.js'),
    "text.length > 40 || text.split(/\\r?\\n/).length > 2", 'text.length > 40')
  const { card, observer } = loadPostCard(source)
  observer.call(card, '入社须知\n1. 准时集合\n2. 带水')
  assert.notEqual(card.data.textLong, true)
})

// ---------- F-W5-03 「我的」项目预览:草稿 / 审核态不得写成「进行中」 ----------

function loadProfile(profileSource) {
  const PROFILE_JS = path.join(ROOT, 'subpackageMember/components/cy/profile/index.js')
  const requests = []
  let definition
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => '42', getAuthorization: () => 'Bearer existing-member-fixture', getUserRole: () => 'club', getUserType: () => 2,
    isDevEnv: () => false, setUserRole() {},
    sendRequest(options) { requests.push(options) },
    tips() {},
  }
  const previous = { Component: global.Component, getApp: global.getApp, wx: global.wx }
  global.getApp = () => app
  global.wx = { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {}, hideTabBar() {}, setNavigationBarColor() {}, setBackgroundColor() {} }
  global.Component = (value) => { definition = value }
  try {
    const Module = require('node:module')
    const m = new Module(PROFILE_JS, module)
    m.filename = PROFILE_JS
    m.paths = Module._nodeModulePaths(path.dirname(PROFILE_JS))
    m._compile(profileSource || read('subpackageMember/components/cy/profile/index.js'), PROFILE_JS)
  } finally {
    global.Component = previous.Component
    global.getApp = previous.getApp
    global.wx = previous.wx
  }
  const component = Object.assign({}, definition.methods, { data: JSON.parse(JSON.stringify(definition.data)) })
  component.setData = (patch) => Object.assign(component.data, patch)
  return { component, requests }
}

async function previewWith(rows, source) {
  const { component, requests } = loadProfile(source)
  const pending = component.loadProjectPreview()
  const request = requests.find((item) => item.url === '/api/topic/list')
  assert.ok(request, '缺少 /api/topic/list 请求')
  request.success({ code: 200, data: { rows, total: rows.length } })
  await pending
  return component.data.projectPreviewList
}

// 档期覆盖今天:旧实现按档期一律写「进行中」
const RUNNING = { startDate: '2020-01-01 00:00:00', endDate: '2099-12-31 23:59:59', status: 1, userStatus: 1 }

test('F-W5-03 我的项目预览:与 /api/project/my 同序 草稿 > 未通过 > 审核中 > 已下架 > 档期', async () => {
  const list = await previewWith([
    Object.assign({ id: 1, name: '（草稿）苏州河滨水步道', isDraft: true }, RUNNING, { status: 0 }),
    Object.assign({ id: 2, name: '未通过' }, RUNNING, { status: 2 }),
    Object.assign({ id: 3, name: '审核中' }, RUNNING, { status: 0 }),
    Object.assign({ id: 4, name: '已下架' }, RUNNING, { userStatus: 0 }),
    Object.assign({ id: 5, name: '在架' }, RUNNING, { isDraft: false }),
  ])
  assert.deepEqual(list.map((item) => item.statusText), ['草稿', '审核未通过', '审核中', '已下架', '进行中'])
})

test('F-W5-03 负控:预览退回只看 item.state,草稿必须真红', async () => {
  const source = mutated(read('subpackageMember/components/cy/profile/index.js'),
    'item = Object.assign({}, item, { state: previewState(item) });', '')
  const list = await previewWith([Object.assign({ id: 1, name: '草稿', isDraft: true }, RUNNING, { status: 0 })], source)
  assert.notEqual(list[0].statusText, '草稿')
})

// ---------- F-W5-05 资料保存只回写真正提交的昵称 ----------

function loadProfileEditor() {
  let definition
  const requests = []
  const session = { nickname: '王小明', avatar: 'a.png' }
  const app = {
    globalData: {},
    sendRequest(options) { requests.push(options) },
    setNickname(v) { session.nickname = v },
    setAvatar(v) { session.avatar = v },
    tips() {},
  }
  vm.runInNewContext(read('pages/gerenziliao/gerenziliao.js'), {
    getApp: () => app,
    Page: (value) => { definition = value },
    require: (id) => {
      if (/response-shape\.js$/.test(id)) return require(path.join(ROOT, 'utils/response-shape.js'))
      if (/form-state\.js$/.test(id)) return require(path.join(ROOT, 'utils/form-state.js'))
      // 2026-10-27 L08 G-5:资料保存成功广播身份变化(纯内存模块,用真身)
      if (/identity-sync\.js$/.test(id)) return require(path.join(ROOT, 'utils/identity-sync.js'))
      if (/toast\.js$/.test(id)) return Object.assign(() => {}, { success() {}, error() {} })
      return { show() {}, hide() {} }
    },
    setTimeout: () => 0,
    clearTimeout() {},
    console,
  }, { filename: 'gerenziliao.js' })
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) })
  page.setData = (patch) => Object.assign(page.data, patch)
  return { page, requests, session }
}

function save(page, requests, userInfo) {
  page.data.userInfo = Object.assign({}, page.data.userInfo, userInfo)
  page.data.canSave = true
  page.data.selectedCategoryIds = []
  page.saveInfo()
  const request = requests[requests.length - 1]
  request.success({ code: '200' })
  return JSON.parse(request.data)
}

test('F-W5-05 资料保存:改了昵称写回会话;没改昵称不得把会话昵称清空', () => {
  const changed = loadProfileEditor()
  changed.page._loadedProfileName = '城瘾用户'
  save(changed.page, changed.requests, { name: '王小明二号', avatar: 'b.png' })
  assert.equal(changed.session.nickname, '王小明二号')
  assert.equal(changed.session.avatar, 'b.png')

  const unchanged = loadProfileEditor()
  unchanged.page._loadedProfileName = '王小明'
  const body = save(unchanged.page, unchanged.requests, { name: '王小明', avatar: 'a.png', introduction: '新简介' })
  assert.equal(body.name, undefined, '未改的昵称不重复提交(避免触发昵称占用校验)')
  assert.equal(unchanged.session.nickname, '王小明', 'PR #5 原实现 setNickname(undefined) 会把会话昵称清空')
})

// ---------- F-W5-06 换行:俱乐部简介块级 + 模板规则按行成步骤 ----------

test('F-W5-06 俱乐部简介:<text> 保留换行且为块级,多段每行都对齐页边', () => {
  assert.match(read('pages/club/detail/index.wxml'), /<text class="ov-intro">\{\{club\.description \|\| '暂无简介'\}\}<\/text>/)
  assert.match(rule(read('pages/club/detail/index.wxss'), '.ov-intro'), /display:\s*block/)
})

test('F-W5-06 模板规则:按编辑器「一行一步」拆步骤,每步一个序号', () => {
  const { splitRuleSteps } = require('../../pages/templatedetail/utils/templateDetail.js')
  assert.deepEqual(splitRuleSteps('第一步\r\n第二步\n\n  第三步  \n'), ['第一步', '第二步', '第三步'])
  assert.deepEqual(splitRuleSteps(null), [])
  assert.deepEqual(splitRuleSteps('   '), [])

  const wxml = read('pages/templatedetail/templatedetail.wxml')
  assert.match(wxml, /wx:for="\{\{ ruleSteps \}\}"[\s\S]*?<view class="xb-rule-mark">\{\{ index \+ 1 \}\}<\/view>/)
  assert.doesNotMatch(wxml, /<view class="xb-rule-mark">1<\/view>/, '不得再把整段规则挂在唯一的序号 1 下')
  assert.match(wxml, /wx:if="\{\{ ruleSteps\.length \}\}"/, '只有空白行的规则不出空卡')

  const js = read('pages/templatedetail/templatedetail.js')
  assert.equal((js.match(/ruleSteps: templateDetailUtil\.splitRuleSteps\(normalized\.info\.ruleInstructions\)|target\.ruleSteps = templateDetailUtil\.splitRuleSteps\(normalized\.info\.ruleInstructions\)/g) || []).length, 2,
    '首屏补丁与详情回包两条写入路径都要派生步骤')
  assert.match(js, /res\[1\]\.height > res\[2\]\.height \+ 1/, '「更多」按内层步骤是否超出收起高度判断')
})

test('F-W5-06 私信列表预览:换行折成空格单行显示', () => {
  const js = read('subpackageB/pages/im/list/index.js')
  assert.match(js, /String\(c\.lastMsgText \|\| ''\)\.replace\(\/\[\\r\\n\]\+\/g, ' '\)/)
  assert.match(rule(read('subpackageB/pages/im/list/index.wxss'), '.conv-preview'), /white-space:\s*nowrap/)
})

// ---------- F-W5-10 主题详情吸顶返回键 ----------

test('F-W5-10 吸顶返回按钮:图标取浅色前景,不沿用原生 button 的黑字', () => {
  const css = rule(read('pages/topic/index/index.wxss'), '.sticky-nav-bar .sticky-back button')
  assert.match(css, /color:\s*var\(--cy-color-text-primary\)/)
  assert.match(read('components/cy/icon/index.wxss'), /background-color:\s*currentColor/, 'cy-icon 颜色来自 currentColor')
})

// ---------- F-W5-11 俱乐部管理 tab ----------

test('F-W5-11 管理 tab:快捷区五等分;项目行工具按钮收进第二行', () => {
  const css = read('pages/club/detail/index.wxss')
  assert.match(rule(css, '.quick-acts'), /grid-template-columns:\s*repeat\(5, minmax\(0, 1fr\)\)/)
  assert.match(rule(css, '.quick-act-label'), /white-space:\s*nowrap/)
  assert.match(rule(css, '.manage-project-tools'), /grid-row:\s*2/)
  assert.match(rule(css, '.manage-chevron'), /grid-row:\s*1/)
  const wxml = read('pages/club/detail/index.wxml')
  const tools = wxml.match(/<view wx:if="[^"]*" class="manage-project-tools">([\s\S]*?)\n {12}<\/view>/)
  assert.ok(tools, '缺少工具按钮行')
  assert.equal((tools[1].match(/class="manage-project-director"/g) || []).length, 3, '导演台(主理人/管理员)与场次工具都在工具行里')
})

test('F-W5-11 管理 tab:项目标题单行省略,不被工具按钮挤成多行', () => {
  const name = rule(read('pages/club/detail/index.wxss'), '.manage-project-name')
  assert.match(name, /white-space:\s*nowrap/)
  assert.match(name, /text-overflow:\s*ellipsis/)
  assert.match(name, /overflow:\s*hidden/)
})

// ---------- F-W5-08 主题管理页「接下来」 ----------

function loadTopicDetailPage() {
  const pagePath = path.join(ROOT, 'pages/club/topic-detail/index.js')
  let config = null
  const previous = { getApp: global.getApp, Page: global.Page, wx: global.wx, getCurrentPages: global.getCurrentPages }
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, sendRequest: () => {} })
  global.Page = (value) => { config = value }
  global.getCurrentPages = () => [{}]
  global.wx = { getStorageSync: () => '', setStorageSync: () => {}, removeStorageSync: () => {}, getSystemInfoSync: () => ({}) }
  delete require.cache[pagePath]
  try { require(pagePath) } finally { Object.assign(global, previous) }
  return config
}

test('F-W5-08 接下来:排除已取消/已过去,按集合时刻升序,缺时刻的排最后,文案带时分', () => {
  const page = loadTopicDetailPage()
  const future = (days, hh) => {
    const d = new Date(Date.now() + days * 86400000 + 8 * 3600000)
    return d.toISOString().slice(0, 10) + ' ' + hh + ':00'
  }
  const rows = page.buildSessions({ activityList: [
    { id: 3, name: '后天', startDate: future(2, '09:30'), endDate: future(2, '23:59') },
    { id: 9, name: '缺集合时刻', endDate: future(5, '23:59') },
    { id: 1, name: '明天', startDate: future(1, '19:00'), endDate: future(1, '23:59') },
    { id: 4, name: '已取消', cancelTime: '2026-01-01 00:00:00', startDate: future(1, '08:00'), endDate: future(1, '23:59') },
    { id: 5, name: '已过去', startDate: '2020-01-01 10:00:00', endDate: '2020-01-01 23:59:00' },
    // F-W5-08:主题自办活动壳(后端标 selfHostShell)不是俱乐部场次,不进「接下来」。
    { id: 6, name: '自办壳', selfHostShell: true, startDate: future(1, '00:00'), endDate: future(3, '23:59') },
  ] })
  assert.deepEqual(rows.map((r) => r.id), [1, 3, 9])
  assert.match(rows[0].whenText, /^\d+月\d+日 19:00$/)
  assert.match(rows[1].whenText, /^\d+月\d+日 09:30$/)
  const dot = rule(read('pages/club/topic-detail/index.wxss'), '.ctd-next__row')
  assert.match(dot, /padding:[^;]*calc\(/, '文字左边距要让出圆点')
})

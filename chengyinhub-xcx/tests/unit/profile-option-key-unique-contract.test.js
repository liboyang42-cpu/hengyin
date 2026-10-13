// 角色建档:选项 key 必须跨题唯一。
//
// 2026-09-22 走查实跑(配《预制人生》第一站「新生儿登记」):出生地、性别两道选择题,
// 前端各自从 A/B 起编,保存时服务端回「建档选项的 key 不能重复」——
// AdvancedGameConfigValidator 要求选项 key 跨题唯一(加成按 key 定位,重名会写错变量且不报错)。
// 作者从头到尾没见过「key」,这句报错对他毫无意义;github/master 上前后端都一样,
// 即线上任何「角色建档」只要有两道选择题就存不进去。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

function installGlobals() {
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserRole: () => 'player',
    getUserType: () => 1,
    getUserID: () => 9004,
    getAuthorization: () => 'Bearer test',
    getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest: () => ({ abort() {} }),
    chooseImage: () => {},
    chooseDocument: () => {},
    tips: () => {},
  })
  global.wx = {
    getStorageSync: () => undefined,
    setStorageSync: () => {},
    removeStorageSync: () => {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showToast: () => {},
    hideLoading: () => {},
    showLoading: () => {},
    nextTick: (cb) => cb(),
    navigateTo() {},
    navigateBack() {},
    createSelectorQuery: () => ({ in: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }), exec() {} }) }),
  }
}

function loadTempPage() {
  let config
  global.Page = (value) => { config = value }
  const absolute = path.resolve(__dirname, '../../pages/publish/temp/index.js')
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const page = Object.assign({}, config)
  page.data = JSON.parse(JSON.stringify(config.data))
  page.setData = function (patch, callback) {
    Object.keys(patch).forEach((key) => {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let cursor = this.data
      for (let i = 0; i < parts.length - 1; i += 1) {
        if (cursor[parts[i]] == null) cursor[parts[i]] = {}
        cursor = cursor[parts[i]]
      }
      cursor[parts[parts.length - 1]] = patch[key]
    })
    if (callback) callback()
  }
  page._setFormState = page._setFormState || function (patch) { this.setData(patch) }
  return page
}

const ev = (dataset) => ({ currentTarget: { dataset } })

function allOptionKeys(page) {
  return page.data.advanced.profile.questions
    .flatMap((question) => (question.options || []).map((option) => option.key))
}

beforeEach(installGlobals)

test('两道选择题 + 各补第三个选项:所有选项 key 跨题不重复', () => {
  const page = loadTempPage()
  page.data.advanced.profile = { enabled: true, questions: [{ key: 'name', label: '', kind: 'text' }] }
  page.addProfileQuestion()
  page.addProfileQuestion()
  page.pickProfileKind(Object.assign(ev({ index: 1 }), { detail: { value: page.data.profileKinds.findIndex((k) => k.key === 'pick') } }))   // #1156 起是下拉:detail.value 是下标
  page.pickProfileKind(Object.assign(ev({ index: 2 }), { detail: { value: page.data.profileKinds.findIndex((k) => k.key === 'pick') } }))   // #1156 起是下拉:detail.value 是下标
  page.addProfileOption(ev({ qindex: 1 }))
  page.addProfileOption(ev({ qindex: 2 }))

  const keys = allOptionKeys(page)
  assert.equal(keys.length, 6)
  assert.equal(new Set(keys).size, keys.length, '选项 key 跨题重名:' + keys.join(','))
  keys.forEach((key) => assert.match(key, /^[A-Za-z0-9_-]{1,32}$/, '要过服务端 GAME_ID 规则'))
})

test('已有选项的题切回选择题,不另发新 key(不改动作者已有的选项)', () => {
  const page = loadTempPage()
  page.data.advanced.profile = {
    enabled: true,
    questions: [
      { key: 'a', label: '', kind: 'pick', options: [{ key: 'A', label: '甲' }, { key: 'B', label: '乙' }] },
      { key: 'b', label: '', kind: 'text' },
    ],
  }
  page.pickProfileKind(Object.assign(ev({ index: 1 }), { detail: { value: page.data.profileKinds.findIndex((k) => k.key === 'pick') } }))   // #1156 起是下拉:detail.value 是下标
  assert.deepEqual(page.data.advanced.profile.questions[0].options.map((o) => o.key), ['A', 'B'])
  const keys = allOptionKeys(page)
  assert.equal(new Set(keys).size, keys.length, '新题避开已占用的 A/B:' + keys.join(','))
})

// ── 勋章样式不该单独出现 ──
// 服务端 TemplatePublishServiceImpl.assertCapabilities 把 medalName / medalImg / medalStyle
// 任一非空都当成「要设计勋章」,没有 canDesignMedal(注释写「仅俱乐部」)就拒。前端却永远带着
// 默认 medalStyle:'glow' —— 没配勋章、甚至删掉奖励模块也照带 —— 于是商家、玩家存任何自建
// 玩法都被「当前身份未解锁『勋章设计』」挡回(2026-09-22 走查实跑撞上;判定自 #944 / 08-30 起)。
test('没配勋章时不发 medalStyle(删掉奖励模块、或奖励模块在但没勋章)', () => {
  const page = loadTempPage()
  page.data.selectedCategoryIds = [16]

  page.hasModule = (key) => key !== 'reward'
  let body = page.prepareFormData()
  assert.equal(body.medalStyle, '', '奖励模块都删了还带 medalStyle,会被当成要设计勋章')

  page.hasModule = () => true
  page.data.formData.couponId = 0
  page.data.formData.medalName = ''
  page.data.formData.medalImg = ''
  body = page.prepareFormData()
  assert.equal(body.medalStyle, '', '没有勋章名也没有勋章图,样式就没有意义')
})

test('配了勋章时样式照常带上(负控:别把真设计勋章的样式也清掉)', () => {
  const page = loadTempPage()
  page.data.selectedCategoryIds = [16]
  page.hasModule = () => true
  page.data.formData.medalName = '出生证'
  page.data.formData.medalStyle = 'foil'
  assert.equal(page.prepareFormData().medalStyle, 'foil')
})

// ── 故事玩法 ≠ 公共玩法库模板(2026-09-22 走查 W-04 / W-05 / W-07)──
// 从发布页进来配的是故事里的一个环节(新生儿登记、BIOS),不会进公共玩法库。
// 走查实跑:逼作者选「运动健身 / 美食烹饪」这种类别、填人数时长、配奖励;起的名字没带过来;
// 成功页印「1-3人 人玩法」。公共玩法库那条路(不是 from=fabu)的必填一条都不许松。

test('从发布页进来:用发布页起的名字预填标题', () => {
  const page = loadTempPage()
  page.onLoad({ from: 'fabu', nodeName: encodeURIComponent('新生儿登记'), game: 'profile' })
  assert.equal(page.data.formData.title, '新生儿登记')
})

test('故事玩法不要求描述 / 人数 / 时长 / 类别 / 空奖励;公共玩法库照旧要求(负控)', () => {
  const story = loadTempPage()
  story.data.from = 'fabu'
  story.data.formData.title = '新生儿登记'
  story.data.formData.description = ''
  story.data.formData.players = ''
  story.data.formData.duration = ''
  story.data.selectedCategoryIds = []
  story.hasModule = (key) => key === 'finish' || key === 'reward'
  story.data.gameKey = 'profile'
  story._refreshOutcomeContract = () => ({ errors: [] })
  const errs = story._collectValidationErrors(false)
  for (const key of ['description', 'players', 'duration', 'categoryIds', 'reward']) {
    assert.equal(errs[key], undefined, '故事玩法被要求了 ' + key + ':' + errs[key])
  }

  const pub = loadTempPage()
  pub.data.from = ''
  pub.data.formData.title = '某个公共玩法'
  pub.data.formData.description = ''
  pub.data.selectedCategoryIds = []
  pub.hasModule = (key) => key === 'finish'
  pub.data.gameKey = 'profile'
  pub._refreshOutcomeContract = () => ({ errors: [] })
  const pubErrs = pub._collectValidationErrors(false)
  assert.ok(pubErrs.description && pubErrs.categoryIds, '公共玩法库的必填被一起松了')
})

test('成功页人数不重字:「1-3人」不再印成「1-3人 人玩法」', () => {
  const src = require('fs').readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.js'), 'utf8')
  assert.doesNotMatch(src, /tpl\.players \+ ' 人玩法'/, '人数本身带「人」,再拼「 人玩法」就印两遍')
})

test('故事玩法不出「模板信息」卡(公共玩法库字段)', () => {
  const wxml = require('fs').readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8')
  assert.match(wxml, /<cy-card class="cg-card" id="sec-basic" wx:if="\{\{from !== 'fabu'\}\}">/)
})

test('故事玩法的预览不从「你已抵达」开始(无需到达地点);公共玩法库照旧有到达这一步', () => {
  const story = loadTempPage()
  story.data.from = 'fabu'
  story.hasModule = () => false
  assert.equal(story._buildPvSteps()[0], 'challenge', '故事玩法预览第一步还是到达点位')
  const pub = loadTempPage()
  pub.data.from = ''
  pub.hasModule = () => false
  assert.equal(pub._buildPvSteps()[0], 'arrival', '公共玩法库的到达那一步被一起删了')
})

// 分支能力位(2026-09-22 走查):商家号配完一整张分支图,点保存才被服务端拒「当前身份未解锁『分支 /
// 条件玩法』」。服务端注释自己写着「只在最后一步被拒比一开始就说不能用更糟」,前端却没提前说。
function withRoleGuard(fake, fn) {
  const key = require.resolve(path.resolve(__dirname, '../../utils/roleGuard.js'))
  const saved = require.cache[key]
  require.cache[key] = { id: key, filename: key, loaded: true, exports: fake }
  try { return fn() } finally { if (saved) require.cache[key] = saved; else delete require.cache[key] }
}
const fakeGuard = (caps) => ({
  hasSnapshot: () => true,
  can: (cap) => !!caps[cap],
  load: () => {}, role: () => 'merchant', permission: () => caps, quota: () => null,
})

test('没有分支能力位:分支剧情标成锁住,点「选择」不选中', () => {
  withRoleGuard(fakeGuard({}), () => {
    const page = loadTempPage()
    page.onLoad({ from: 'fabu' })
    assert.ok(page.data.lockedGames && page.data.lockedGames.branch, '分支没有被标成锁住')
    page.pickGame({ currentTarget: { dataset: { key: 'branch' } } })
    assert.notEqual(page.data.gameKey, 'branch', '没能力位还能选中分支,配完才被服务端拒')
  })
})

test('有分支能力位:照常可选(负控)', () => {
  withRoleGuard(fakeGuard({ canBranch: true }), () => {
    const page = loadTempPage()
    page.onLoad({ from: 'fabu' })
    assert.ok(!(page.data.lockedGames || {}).branch)
    page.pickGame({ currentTarget: { dataset: { key: 'branch' } } })
    assert.equal(page.data.gameKey, 'branch')
  })
})

// W-04 余项(2026-09-22 走查复测):故事玩法的成功页还印着「1-3 人 / 预计 10 分钟」——
// 作者从没填过(故事玩法不出人数时长),那是表单默认值。
test('故事玩法保存成功页不印作者没填过的人数与时长', () => {
  const src = require('fs').readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.js'), 'utf8')
  const block = src.slice(src.indexOf("eyebrow: '节点玩法已保存'"), src.indexOf("primaryText: '返回路线编辑'"))
  assert.match(block, /metaPrimary: ''/)
  assert.match(block, /metaSecondary: ''/)
})

test('编辑自己的模板(scope=my)沿用它原有的来源,不把自己的 id 当成引用来源(2026-09-24 147/149/154 实证)', () => {
  const mine = loadTempPage()
  mine.data.isMyScope = true
  mine.fillFormWithTemplateData({ id: 147, originalTemplateId: 0, title: '入职登记' })
  assert.equal(mine.data.formData.originalTemplateId, 0)
  const adopted = loadTempPage()
  adopted.data.isMyScope = false
  adopted.fillFormWithTemplateData({ id: 88, title: '库里的模板' })
  assert.equal(adopted.data.formData.originalTemplateId, 88, '引用库模板仍记来源')
})

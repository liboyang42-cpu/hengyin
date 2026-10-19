const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const PAGE = '../../pages/publish/fabu/index.js'
const ROOT = path.join(__dirname, '../..')
const draftStore = require('../../utils/publish/pro-editor-draft.js')

let pageConfig
let storage
let toasts
let modals
let chooseLocationCalls
let chooseLocationOptions
let requests
let currentOwner
let deferModal

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => currentOwner,
  getNickname: () => '测试创作者',
  getAvatar: () => '',
  sendRequest: options => { requests.push(options) },
  chooseImage: () => {},
  tips: () => {},
})

global.wx = {
  getStorageSync: key => storage[key],
  setStorageSync: (key, value) => { storage[key] = value },
  removeStorageSync: key => { delete storage[key] },
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast: options => { toasts.push(options && options.title) },
  hideLoading: () => {},
  showModal: options => {
    modals.push(options)
    if (!deferModal && options && options.success) options.success({ confirm: true })
  },
  chooseLocation: options => {
    chooseLocationCalls += 1
    chooseLocationOptions = options
  },
  createMapContext: () => ({ getCenterLocation: () => {} }),
  pageScrollTo: () => {},
  nextTick: callback => callback(),
}

global.Page = config => { pageConfig = config }

function setAtPath(target, rawPath, value) {
  const parts = rawPath.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

function makePage() {
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.setData = function (patch, callback) {
    Object.keys(patch).forEach(key => setAtPath(page.data, key, patch[key]))
    if (callback) callback()
  }
  page.updateAllStatistics = function () { this.refreshPrimaryActionState() }
  return page
}

test('主题日期同步票跟随档期，独立票保留原日期，旧草稿提交也按同步开关处理', () => {
  const page = makePage()
  const start = '2026-09-28 09:00:00'
  const end = '2026-10-02 18:00:00'
  const independent = { name: '独立票', startTime: '2026-10-01', endTime: '2026-10-03', syncWithTheme: false }
  page.data.formData.productType = 2
  page.data.formData.startDate = start
  page.data.formData.endDate = end
  page.data.formData.tickets = [{ ...independent, name: '同步票', syncWithTheme: true }, independent]
  page.updateTicketTimes(start, end)
  assert.equal(page.data.formData.tickets[0].startTime, start)
  assert.equal(page.data.formData.tickets[0].endTime, end)
  assert.deepEqual(page.data.formData.tickets[1], independent)
  page.data.formData.tickets[0].startTime = '2026-09-29'
  page.data.formData.tickets[0].endTime = '2026-10-06'
  let submitted
  page.sendData = payload => { submitted = payload }
  page._doSubmit()
  assert.equal(submitted.tickets[0].startTime, start)
  assert.equal(submitted.tickets[0].endTime, end)
  assert.equal(submitted.tickets[1].startTime, '2026-10-01 00:00:00')
  assert.equal(submitted.tickets[1].endTime, '2026-10-03 23:59:59')
})

beforeEach(() => {
  storage = {}
  currentOwner = 42
  deferModal = false
  toasts = []
  modals = []
  chooseLocationCalls = 0
  chooseLocationOptions = null
  requests = []
  pageConfig = null
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
})

test('自由探索倒序售票日期禁止保存，正确日期恢复可保存且仍为零元票', () => {
  const page = makePage()
  const ticket = { name: '自由探索票', mode: 2, price: 0, totalStock: 100,
    startTime: '2026-10-06 09:00:00', endTime: '2026-10-07 18:00:00',
    saleStartTime: '2026-10-05', saleEndTime: '2026-10-04', syncWithTheme: true }
  page.data.formData.productType = 2
  page.data.formData.startDate = ticket.startTime
  page.data.formData.endDate = ticket.endTime
  page.data.formData.tickets = [Object.assign({}, ticket, { saleEndTime: '2026-10-06' })]
  page.data.editingTicket = Object.assign({ editIndex: 0 }, ticket)
  page.data.showTicketEditor = true
  const original = JSON.parse(JSON.stringify(page.data.formData.tickets))
  page.refreshTicketSaveState()
  assert.equal(page.data.canSaveTicket, false)
  assert.equal(page._ticketSaveBlockReason(), '售票结束日期不能早于开始日期')
  page.saveTicket()
  assert.deepEqual(page.data.formData.tickets, original)
  assert.equal(page.data.showTicketEditor, true)
  assert.ok(page._buildPublishPassed(false).some(item => /已配置.*票单/.test(item.label)),
    '原来有效的票单仍可被计入完成项')

  page.onEditTicketChange({ currentTarget: { dataset: { field: 'saleEndTime' } },
    detail: { value: '2026-10-06' } })
  assert.equal(page.data.canSaveTicket, true)
  page.refreshRouteGraph = () => {}
  page.refreshPrimaryActionState = () => {}
  page.saveTicket()
  assert.equal(page.data.showTicketEditor, false)
  assert.equal(page.data.formData.tickets[0].saleEndTime, '2026-10-06')
  assert.equal(page.data.formData.tickets[0].price, 0)
  page.data.formData.tickets[0].saleEndTime = '2026-10-04'
  assert.ok(!page._buildPublishPassed(false).some(item => /已配置.*票单/.test(item.label)),
    '旧草稿的倒序票单不能被计入已通过项')
})

test('自由探索独立票的倒序有效期既不能保存，也不能通过发布检查', () => {
  const page = makePage()
  const ticket = { name: '自由探索票', mode: 2, price: 0, totalStock: 100,
    startTime: '2026-10-06 09:00:00', endTime: '2026-10-05 18:00', syncWithTheme: false,
    saleStartTime: '2026-10-05', saleEndTime: '2026-10-06' }
  page.data.formData.productType = 2
  page.data.formData.recruitDeadline = '2026-10-05'
  page.data.formData.tickets = [ticket]
  page.data.editingTicket = Object.assign({ editIndex: 0 }, ticket)
  page.data.showTicketEditor = true
  page.refreshTicketSaveState()
  assert.equal(page.data.canSaveTicket, false)
  assert.equal(page._buildValidationBag().errors.ticketEnd0, '票单结束时间必须晚于开始时间')
  page.saveTicket()
  assert.equal(page.data.showTicketEditor, true)
  page.onEditTicketChange({ currentTarget: { dataset: { field: 'endTime' } },
    detail: { value: '2026-10-07 18:00' } })
  assert.equal(page.data.canSaveTicket, true)
})

test('iOS 不解析裸日期时间时，结束时间仍拒绝相等、倒序及无效开始时间', () => {
  const NativeDate = global.Date
  global.Date = class extends NativeDate {
    constructor(...args) {
      if (typeof args[0] === 'string' && /^\d{4}-\d{2}-\d{2} /.test(args[0])) {
        super(NaN)
      } else {
        super(...args)
      }
    }
  }
  try {
    for (const [start, accepted] of [
      ['2026-10-03 09:00:00', true],
      ['2026-10-03 18:00:00', false],
      ['2026-10-03 19:00:00', false],
      ['2026-02-30 09:00:00', false],
    ]) {
      const page = makePage()
      page.refreshPrimaryActionState = () => {}
      page.data.formData.startDate = start
      page.data.formData.endDate = ''
      page.data.dateList = [{ date: '2026-10-03', display: '10月3日' }]
      page.data.endDateIndex = [0]
      page.data.endTimeIndex = [18, 0]
      page.data.endTimePicker.show = true
      page.confirmEndTime()
      assert.equal(page.data.formData.endDate, accepted ? '2026-10-03 18:00:00' : '', start)
      assert.equal(page.data.endTimePicker.show, !accepted, start)
    }
  } finally {
    global.Date = NativeDate
  }
})

test('恢复草稿后日期滚轮选中已保存时间，取消的滚轮修改不影响再次打开', () => {
  const page = makePage()
  page.refreshPrimaryActionState = () => {}
  page.data.dateList = [
    { date: '2026-10-03', display: '10月3日' },
    { date: '2026-10-04', display: '10月4日' },
    { date: '2026-10-07', display: '10月7日' },
  ]
  page.data.formData.startDate = '2026-10-04 10:15:00'
  page.data.formData.endDate = '2026-10-07 19:30:00'
  page.showStartTimePicker()
  assert.deepEqual(page.data.startDateIndex, [1])
  assert.deepEqual(page.data.startTimeIndex, [10, 15])
  page.closeStartTimePicker()
  page.showEndTimePicker()
  assert.deepEqual(page.data.endDateIndex, [2])
  assert.deepEqual(page.data.endTimeIndex, [19, 30])
  page.onEndDateChange({ detail: { value: [0] } })
  page.onEndTimePickerChange({ detail: { value: [8, 0] } })
  page.closeEndTimePicker()
  page.showEndTimePicker()
  assert.deepEqual(page.data.endDateIndex, [2])
  assert.deepEqual(page.data.endTimeIndex, [19, 30])
  page.confirmEndTime()
  assert.equal(page.data.formData.startDate, '2026-10-04 10:15:00')
  assert.equal(page.data.formData.endDate, '2026-10-07 19:30:00')
  assert.equal(page.data.endTimePicker.show, false)
})

test('自由探索空态主动作自动落「第1章」并打开正式节点弹窗（2026-08-20 拍板，取代旧的待编排入口）', () => {
  const page = makePage()
  page.data.formData.productType = 2
  page.data.formData.chapters = []
  // 2026-09-05:三条起点的 CTA 收敛成同一颗「创建章节」,分支判据从 action.key 换成
  // productType —— 这条用例守的行为没变:自由探索建完章节必须把节点弹窗开在第 1 章上。
  page.data.starterAction = { key: 'createChapter', label: '＋ 创建章节' }

  page.onTapPrimaryStarter()

  assert.equal(page.data.formData.chapters.length, 1, '必须自动创建章节')
  assert.equal(page.data.formData.chapters[0].name, '第1章')
  assert.equal(page.data.popChapterNodes, true)
  assert.equal(page.data.nodeDraftDestination, 'formal')
  assert.equal(page.data.editTargetChapterLid, page.data.formData.chapters[0]._localId,
    '节点必须直接建进第1章，不再进待编排池')
})

test('添加章节不再弹弹窗：onTapAddChapter 直接以默认名落一章', () => {
  const page = makePage()
  page.data.formData.productType = 2
  page.data.formData.chapters = []

  page.onTapAddChapter()

  assert.equal(page.data.popChapter, false, '不许打开章节弹窗')
  assert.equal(page.data.formData.chapters.length, 1)
  assert.equal(page.data.formData.chapters[0].name, '第1章')

  page.onTapAddChapter()
  assert.equal(page.data.formData.chapters.length, 2)
  assert.equal(page.data.formData.chapters[1].name, '第2章')
})

// ★ 2026-09-05 用户实测:「我先添加了,再删除了,就没办法再添加了」。
// 死锁长这样:starterAction 是派生态,只在 refreshPrimaryActionState 里算。章节有了内容之后
// 它变成 null;deleteChapter 当时不重算,于是删掉最后一章后它还是 null ——
// 而空态卡的 CTA 挂 `wx:if="{{starterAction}}"`、「添加章节」行挂
// `wx:if="{{chapters.length || pendingMaterials.length}}"`,两个条件同时不成立,
// 页面上再没有任何添加章节的入口。
// 这条必须是**行为测**:静态查 deleteChapter 里有没有那行调用,改个写法就绕过去了。
test('删掉最后一章之后必须还能再添加 —— 空态入口不能因为派生态没重算而消失', () => {
  const page = makePage()
  page.data.formData.productType = 2
  page.data.formData.chapters = [{
    _localId: 'ch-1', name: '第1章', nodes: [{ _localId: 'n-1', name: '站点', address: '某地' }], blocks: [],
  }]
  page.data.popChapterIndex = 0

  // 前置条件不硬写,由现码自己派生出来:有内容的章节 ⇒ 不是空草稿 ⇒ 没有空态 CTA
  page.refreshPrimaryActionState()
  assert.equal(page.data.starterAction, null, '前置:有内容时空态 CTA 本来就不该在')

  page.deleteChapter({ currentTarget: { dataset: {} } })

  assert.equal(page.data.formData.chapters.length, 0)
  assert.ok(page.data.starterAction, '删空之后空态 CTA 必须回来,否则页面上没有任何添加入口')
  // 把 WXML 那两个条件原样复述一遍:两个都不成立 = 用户看到的那个死锁
  const emptyCtaVisible = page.data.formData.chapters.length === 0 && !!page.data.starterAction
  const addRowVisible = !!(page.data.formData.chapters.length || page.data.pendingMaterials.length)
  assert.ok(emptyCtaVisible || addRowVisible, '空态 CTA 与「添加章节」行必须至少有一个在')
})

test('自由探索节点可不填地点保存，但只能进入待编排区', () => {
  const page = makePage()
  page.data.formData.productType = 2
  page.data.formData.chapters = []
  page.data.nodeDraftDestination = 'pending'
  page.data.nodesForm = Object.assign({}, page.data.nodesForm, {
    name: '先写好的观察任务',
    description: '找到一扇蓝色的门',
    longitude: '',
    latitude: '',
  })

  page.confrimNode()

  assert.equal(page.data.pendingMaterials.length, 1)
  assert.equal(page.data.pendingMaterials[0].kind, 'node')
  assert.equal(page.data.pendingMaterials[0].name, '先写好的观察任务')
  assert.equal(page.data.pendingMaterials[0].longitude, '')
  assert.deepEqual(page.data.formData.chapters, [])
  assert.equal(toasts.at(-1), '已保存到待编排区')
})

test('每次素材变更立即原子保存完整草稿信封，重进恢复同一新草稿', () => {
  const first = makePage()
  first.onLoad({ mode: '2' })
  first.data.nodeDraftDestination = 'pending'
  first.data.nodesForm = Object.assign({}, first.data.nodesForm, {
    name: '稍后选地点的观察任务',
    description: '先记录任务内容',
    longitude: '',
    latitude: '',
  })

  first.confrimNode()

  const draftUuid = first.data.draftUuid
  const draftKey = draftStore.draftKeyFor({ draftUuid })
  const envelope = storage[draftKey]
  assert.equal(envelope.memberId, 42)
  assert.equal(envelope.draftUuid, draftUuid)
  assert.deepEqual(envelope.chapters, first.data.formData.chapters)
  assert.deepEqual(envelope.pendingMaterials, first.data.pendingMaterials)
  assert.equal(envelope.pendingMaterials.length, 1)

  const reopened = makePage()
  reopened.onLoad({ mode: '2' })
  assert.equal(reopened.data.draftUuid, draftUuid, '同一账号重进必须恢复活动草稿，不得另开固定 _new 桶')
  assert.equal(reopened.data.pendingMaterials.length, 1)
  assert.equal(reopened.data.pendingMaterials[0].name, '稍后选地点的观察任务')
  // 2026-08-11:常驻提示条下线,改成恢复瞬间弹一次 toast(见 _applyDraftEnvelope)。
  // 契约钉 toast 的文案。原来这里还有一条 assert.equal(pendingRestoreNotice, '') ——
  // 字段本身已随提示条一起删掉,那条是恒真断言,一并去掉,别留橡皮图章。
  assert.ok(toasts.includes('已恢复上次未编排完的 1 个素材'),
    '恢复草稿必须弹一次 toast:提示条撤了之后,不弹就等于什么都没告诉人')

  reopened.data.nodeDraftDestination = 'pending'
  reopened.data.nodesForm = Object.assign({}, reopened.data.nodesForm, {
    name: '恢复后新增的第二个任务', description: '验证本地 ID 不复用', longitude: '', latitude: '',
  })
  reopened.confrimNode()
  assert.equal(new Set(reopened.data.pendingMaterials.map(item => item._localId)).size, 2,
    '恢复后必须推进本地 ID 序号，不能用新素材覆盖旧素材')
})

test('俱乐部锁定入口不恢复其它俱乐部的活跃草稿', () => {
  draftStore.saveDraft(wx, {
    memberId: 42,
    draftUuid: 'club-a-draft',
    formData: Object.assign({}, pageConfig.data.formData, { clubId: '11', chapters: [] }),
    pendingMaterials: [{
      _localId: 'place-a', kind: 'place', name: 'A 俱乐部地点', description: '',
      address: 'A 路', longitude: 121.4, latitude: 31.2, imgUrl: '', duration: 30, gameplay: null,
    }],
  })
  const page = makePage()
  page.startClubPlaceCapture = () => {}

  page.onLoad({ clubId: '22' })

  assert.notEqual(page.data.draftUuid, 'club-a-draft')
  assert.equal(page.data.formData.clubId, '22')
  assert.deepEqual(page.data.pendingMaterials, [])
})

test('待编排状态与归章闸共用可用坐标语义，零值变体不能显示已配置', () => {
  const page = makePage()
  page.data.pendingMaterials = [{
    _localId: 'zero-place', kind: 'place', name: '错误地点', description: '', address: '测试路',
    longitude: '0.0', latitude: '31.2', imgUrl: '', duration: 30, gameplay: null,
  }]

  page.refreshPrimaryActionState()

  assert.equal(page.data.pendingMaterialStates['zero-place'], false)
})

test('节点弹窗坐标状态也由同一可用坐标闸派生', () => {
  const page = makePage()
  page.onLoad({ mode: '2' })

  page.setData({ nodesForm: Object.assign({}, page.data.nodesForm, { longitude: '0.0', latitude: '31.2' }) })
  assert.equal(page.data.nodesFormLocationReady, false)

  page.setData({ nodesForm: Object.assign({}, page.data.nodesForm, { longitude: '121.48', latitude: '31.23' }) })
  assert.equal(page.data.nodesFormLocationReady, true)
})

test('恢复完整草稿后用户资料回调不能清空已有合作者', () => {
  const formData = Object.assign({}, pageConfig.data.formData, {
    productType: 2,
    chapters: [],
    collaboratorList: [
      { id: 42, nickname: '旧昵称', isOwner: 1 },
      { id: 99, nickname: '同行者' },
    ],
    collaboratorIds: [42, 99],
  })
  draftStore.saveDraft(wx, {
    memberId: 42,
    draftUuid: 'collaborator-draft',
    formData,
    pendingMaterials: [],
  })
  const page = makePage()

  page.onLoad({ mode: '2' })
  const userRequest = requests.find(request => request.url === '/api/user/info')
  userRequest.success({ code: '200', data: { id: 42, nickname: '新昵称', avatar: 'new.png' } })

  assert.deepEqual(page.data.formData.collaboratorIds, [42, 99])
  assert.equal(page.data.formData.collaboratorList.length, 2)
  assert.equal(page.data.formData.collaboratorList[1].nickname, '同行者')
})

test('既有主题服务端版本变化时明确询问，确认后才恢复本地完整草稿', () => {
  const page = makePage()
  const localForm = Object.assign({}, page.data.formData, { name: '本地未保存版本', chapters: [] })
  page.data.editingTopicId = '77'
  page.data.formData.name = '服务端新版'
  draftStore.saveDraft(wx, {
    memberId: 42,
    topicId: '77',
    baseRevision: 'server-v1',
    formData: localForm,
    pendingMaterials: [],
  })

  page._finishEditingDraftBootstrap({ topic: { memberId: 42, updateTime: 'server-v2' } })

  assert.equal(modals.at(-1).content, '其他设备上的草稿已更新，是否用本机草稿覆盖？')
  assert.equal(page.data.formData.name, '本地未保存版本')
  assert.equal(page.data.baseRevision, 'server-v2')
})

test('发布校验精确报 pendingMaterials，不能被其它必填闸掩盖', () => {
  const page = makePage()
  page.data.pendingMaterials = [{ _localId: 'p1', kind: 'node', name: '未归章节点' }]

  const bag = page._buildValidationBag()

  assert.equal(bag.errors.pendingMaterials, '还有 1 个素材未编排进章节')
})

test('发布校验拒绝越界坐标并指认到具体章节节点', () => {
  const page = makePage()
  page.data.formData.productType = 2
  page.data.formData.chapters = [{
    name: '第一章',
    description: '',
    nodes: [{ name: '错误地点', longitude: 181, latitude: 31.2 }],
  }]

  const bag = page._buildValidationBag()

  assert.equal(bag.errors.chapter0, '第1章第1个节点还没有选地点')
})

test('城市定向新建第一章进入空故事流，章节卡不再写剧情或自动塞节点', () => {
  const page = makePage()
  page.data.formData.productType = 1
  page.showAddChapter({ currentTarget: { dataset: {} } })
  page.data.chapterForm.name = '第一章'
  page.data.chapterForm.description = ''

  page.confrimChapter()
  assert.equal(page.data.formData.chapters.length, 1)
  assert.deepEqual(page.data.formData.chapters[0].nodes, [])
  assert.deepEqual(page.data.formData.chapters[0].blocks, [])
  assert.equal(page.data.storyEditor.show, true)
})

test('俱乐部连续 POI：每次有效选择落入地点素材并立即继续搜索，取消才结束', () => {
  const page = makePage()
  page._scheduleClubPoiContinuation = callback => callback()

  page.startClubPlaceCapture()
  assert.equal(chooseLocationCalls, 1)
  chooseLocationOptions.success({
    type: 0,
    name: '旧码头',
    address: '滨江路 8 号',
    longitude: 121.48,
    latitude: 31.23,
  })

  assert.equal(page.data.pendingMaterials.length, 1)
  assert.equal(page.data.pendingMaterials[0].kind, 'place')
  assert.equal(page.data.pendingMaterials[0].longitude, 121.48)
  assert.equal(chooseLocationCalls, 2, '成功录入后 POI 搜索必须保持连续')

  chooseLocationOptions.success({ name: '', address: '', longitude: 0, latitude: 0 })
  assert.equal(page.data.pendingMaterials.length, 1, '无可用坐标的返回不能落入地点素材')
  assert.equal(chooseLocationCalls, 3, '拒绝无坐标结果后仍应保持连续搜索，不能被 active 状态卡死')

  chooseLocationOptions.fail({ errMsg: 'chooseLocation:fail cancel' })
  assert.equal(page.data.clubPlaceCaptureActive, false)
})

test('城市定向待编排素材先进入故事流选缝，补文字后才在目标缝转正', () => {
  const page = makePage()
  const pending = {
    _localId: 'p1', kind: 'node', name: '河畔线索', description: '找刻字',
    address: '滨江路 8 号', longitude: '121.48', latitude: '31.23',
    imgUrl: '', duration: 30, gameplay: null,
  }
  page.data.pendingMaterials = [pending]
  page.data.formData.productType = 1
  page.data.formData.chapters = [{ _localId: 'c1', name: '第一章', description: '', nodes: [] }]
  page._canAddFormalNodes = () => true

  page.arrangePendingIntoChapter({
    detail: { value: '0' },
    currentTarget: { dataset: { localid: 'p1' } },
  })
  assert.equal(page.data.pendingMaterials.length, 1)
  assert.equal(page.data.formData.chapters[0].nodes.length, 0)
  assert.equal(page.data.storyEditor.show, true)
  assert.equal(page.data.storyPendingMaterialLocalId, 'p1')
  assert.equal(toasts.at(-1), '请先添加文字，再点击目标缝插入节点')

  const textBlock = page.data.formData.chapters[0].blocks[0]
  page.onStoryTextInput({
    currentTarget: { dataset: { blockkey: textBlock.key } },
    detail: { value: '沿河寻找旧码头的来历。' },
  })
  page.insertStoryNodeAt({ currentTarget: { dataset: { index: 1 } } })
  page.confrimNode()
  assert.equal(page.data.pendingMaterials.length, 0)
  assert.equal(page.data.formData.chapters[0].nodes.length, 1)
  assert.equal(page.data.formData.chapters[0].nodes[0].name, '河畔线索')
  assert.equal(page.data.formData.chapters[0].blocks[1].nodeKey, 'p1')
})

test('待编排素材放入已有章节或新章节都必须经过正式节点配额闸', () => {
  const material = {
    _localId: 'quota-pending', kind: 'node', name: '额外节点', description: '测试配额',
    address: '测试路', longitude: 121.4, latitude: 31.2,
    imgUrl: '', duration: 30, gameplay: null,
  }
  const existing = makePage()
  existing.data.formData.productType = 2
  existing.data.formData.chapters = [{ _localId: 'c1', name: '第一章', description: '', nodes: [] }]
  existing.data.pendingMaterials = [material]
  existing._canAddFormalNodes = () => false

  existing.arrangePendingIntoChapter({
    detail: { value: '0' }, currentTarget: { dataset: { localid: material._localId } },
  })
  assert.equal(existing.data.formData.chapters[0].nodes.length, 0)
  assert.equal(existing.data.pendingMaterials.length, 1)

  const created = makePage()
  created.data.formData.productType = 2
  created.data.formData.chapters = []
  created.data.pendingMaterials = [material]
  created.showAddChapter({ currentTarget: { dataset: {} } })
  created.setData({ assignPendingLocalId: material._localId })
  created._canAddFormalNodes = () => false
  created.confrimChapter()
  assert.equal(created.data.formData.chapters.length, 0)
  assert.equal(created.data.pendingMaterials.length, 1)
})

test('新建章节归入素材后撤销必须同时恢复章节与待编排区', () => {
  const page = makePage()
  const material = {
    _localId: 'undo-pending', kind: 'node', name: '可撤销节点', description: '测试完整快照',
    address: '测试路', longitude: 121.4, latitude: 31.2,
    imgUrl: '', duration: 30, gameplay: null,
  }
  page.data.formData.productType = 2
  page.data.formData.chapters = []
  page.data.pendingMaterials = [material]
  page.showAddChapter({ currentTarget: { dataset: {} } })
  page.setData({ assignPendingLocalId: material._localId })
  page._canAddFormalNodes = () => true

  page.confrimChapter()
  assert.equal(page.data.formData.chapters.length, 1)
  assert.equal(page.data.pendingMaterials.length, 0)

  page.undo()
  assert.equal(page.data.formData.chapters.length, 0)
  assert.equal(page.data.pendingMaterials.length, 1)
  assert.equal(page.data.pendingMaterials[0]._localId, material._localId)
})

test('删除正式节点不是销毁，而是原样移回待编排区', () => {
  const page = makePage()
  page.data.formData.chapters = [{
    _localId: 'c1', name: '第一章', description: '真实剧情', nodes: [{
      _localId: 'n1', name: '旧码头', description: '找刻字', address: '滨江路 8 号',
      longitude: '121.48', latitude: '31.23', nodeTime: 45,
      templateId: 7, templateInfo: { title: '文字暗号' }, hookText: '看桥洞',
    }],
  }]
  page.data.editTargetChapterLid = 'c1'
  page.data.editTargetNodeLid = 'n1'

  page.deleteNode()

  assert.equal(page.data.formData.chapters[0].nodes.length, 0)
  assert.equal(page.data.pendingMaterials.length, 1)
  assert.equal(page.data.pendingMaterials[0].name, '旧码头')
  assert.equal(page.data.pendingMaterials[0].gameplay.templateId, 7)
  assert.equal(page.data.pendingUndo.show, true)
})

test('WXML 提供三起点主动作、待编排区、补内容与两种归章入口', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/index.wxml'), 'utf8')
  const js = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/index.js'), 'utf8')
  assert.match(wxml, /bindtap="onTapPrimaryStarter"/)
  assert.match(wxml, /\{\{starterAction\.label\}\}/)
  assert.match(wxml, /待编排区/)
  assert.match(wxml, /bindtap="editPendingMaterial"/)
  // 2026-08-25:选择器由原生 <picker> 迁到 <cy-dropdown>,绑定写法从 bindchange 变成
  // bind:change。断言的意图(控件必须接上 handler)不变,所以两种写法都认。
  assert.match(wxml, /bind:?change="arrangePendingIntoChapter"/)
  assert.match(wxml, /bindtap="createChapterForPending"/)
  assert.match(wxml + js, /把这段剧情落到地点/)
  assert.match(wxml, /bindinput="onNodeNameInput"/)
})


test('开局先于第一章，复用编辑器且不抢占章节编号，保存后可再次编辑', () => {
  const page = makePage()
  page.data.formData.productType = 1
  page._createChapterDirect()
  page.closeStoryEditor()
  page.openTopicOpening()
  assert.equal(page.data.formData.chapters.length, 2)
  assert.equal(page.data.formData.chapters[0].opening, true)
  assert.equal(page.data.formData.chapters[1].name, '第1章')
  assert.equal(page.data.storyEditor.chapterIndex, 0)
  page.closeStoryEditor()
  page.openTopicOpening()
  assert.equal(page.data.formData.chapters.length, 2)
  const payload = page._buildSubmitChapters(1)
  assert.equal(payload[0].opening, true)
  page.closeStoryEditor()
  page._createChapterDirect()
  assert.equal(page.data.formData.chapters[2].name, '第2章')
  assert.equal(page.data.formData.chapters[2].opening, false)
})

test('编辑结局后，两种新建入口都清空上章身份和设置，连续新增章节互不串写', () => {
  for (const create of ['_createChapterDirect', 'showAddChapter']) {
    const page = makePage()
    page.data.formData.chapters = [{ id: 70, _localId: 'old', name: '旧结局', nodes: [], blocks: [],
      ending: { fallback: true } }]
    page.showEditChapter({ currentTarget: { dataset: { index: 0 } } })
    page.confrimChapter()
    const before = JSON.stringify(page.data.formData.chapters[0])
    for (let i = 0; i < 3; i += 1) {
      page[create]()
      if (create === 'showAddChapter') page.confrimChapter()
      page.closeStoryEditor()
    }
    const added = page.data.formData.chapters.slice(1)
    assert.equal(new Set(added.map(c => c._localId)).size, 3)
    for (const chapter of added) {
      assert.equal(chapter.id, undefined)
      assert.equal(chapter.ending, null)
      assert.equal(chapter.opening, false)
    }
    assert.equal(JSON.stringify(page.data.formData.chapters[0]), before)
  }
})

test('伏笔配置留在主题详情，正文两处菜单只提供文字、图片、音频、地点', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/index.wxml'), 'utf8')
  const topic = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/topic-detail-sheet.wxml'), 'utf8')
  assert.match(topic, /bindtap="openThoughts"/)
  const menus = [...wxml.matchAll(/<view class="story-insert__menu">([\s\S]*?)\n\s*<\/view>/g)]
  assert.equal(menus.length, 2)
  for (const menu of menus) {
    assert.deepEqual([...menu[1].matchAll(/catchtap="(\w+)"/g)].map(m => m[1]),
      ['insertStoryTextAt', 'insertStoryImageAt', 'insertStoryAudioAt', 'insertStoryNodeAt'])
  }
  assert.match(wxml, /bind:change="onToggleNodeGame"/)
})

test('服务端开局引用回显为开局，文本内容和原章节 id 不丢失', () => {
  const page = makePage()
  page.data.editingTopicId = 1
  page.applyEditingTopic({ topic: { id: 1, name: '任意主题', productType: 1 },
    openingChapterId: 70, chapters: [{ id: 70, name: '序幕', blocks: [{ type: 'text', key: 'a', content: '开场' }], cmsTopicNodeList: [] },
      { id: 80, name: '出发', cmsTopicNodeList: [] }], tickets: [] })
  assert.equal(page.data.formData.chapters[0].opening, true)
  assert.equal(page.data.formData.chapters[1].opening, false)
  const payload = page._buildSubmitChapters(1)
  assert.equal(payload[0].id, 70)
  assert.equal(payload[0].blocks[0].content, '开场')
})


test('开局动画使用序章编号，第一章仍为一；自由探索可选值不被序列化改写', () => {
  const page = makePage()
  page._createChapterDirect()
  page.closeStoryEditor()
  page.openTopicOpening()
  page.previewChapterOpening({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(page.data.chapterPreview.number, 0)
  page.previewChapterOpening({ currentTarget: { dataset: { index: 1 } } })
  assert.equal(page.data.chapterPreview.number, 1)
  assert.equal(page.data.chapterPreview.total, 1)
  page.data.formData.productType = 2
  page.data.formData.chapters[1].required = 0
  page.openStoryEditor({ currentTarget: { dataset: { index: 1 } } })
  assert.equal(page.data.formData.chapters[1].required, 0)
  assert.equal(page._buildSubmitChapters(2)[1].required, 0)
})

// 权限快照拉失败后必须能恢复(2026-09-22 走查实跑)。
// 生产部署重启窗口里 /api/role/info 回了 502,发布页之后再也不重拉:作者在玩法弹层点「完成」,
// 提示「权限加载中,请稍候重试」—— 可重试一万次都是这句,只能退出页面,刚配好的玩法白配。
test('没有权限快照时,「完成」顺手重拉一次,让「稍候重试」这句话成真', () => {
  // 前面用例发出的 /api/role/info 在测试里永远不回,roleGuard 会按「同身份在途就合并」不再发 ——
  // 换一份干净的模块状态,才测得到「这一次有没有重拉」。
  delete require.cache[require.resolve('../../utils/roleGuard.js')]
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  const page = makePage()
  requests.length = 0
  assert.equal(page._canAddFormalNodes(1), false, '没快照不能放行(fail-closed 不变)')
  assert.ok(toasts.some((t) => /权限加载中/.test(t)))
  assert.ok(requests.some((r) => r && r.url === '/api/role/info'), '没快照却不重拉:作者重试多少次都一样')
})

// W-10(2026-09-22 走查):从发布页存下的故事玩法是 status=0 草稿,而「我的模板」按 is_quote 只列已发布
// —— 绑定一旦丢了(关了弹层、页面被重置),那条草稿再也选不回来,作者只能重配一遍,库里留孤儿。
// 走查里两次撞上(模板 140、141)。后端 my-list 本来就支持 draft_status=0。
test('选玩法面板每次打开都重拉「我的故事玩法草稿」,选中后能绑回节点', () => {
  const page = makePage()
  requests.length = 0
  page.showTemp()
  const req = requests.find((r) => r && r.url === '/api/template/my-list' && r.data && String(r.data.draft_status) === '0')
  assert.ok(req, '打开面板没有拉草稿')
  assert.equal(req.data.is_quote, undefined, '带了 is_quote 就只回已发布,草稿永远拉不到')
  req.success({ code: 200, data: { rows: [{ id: 141, title: '新生儿登记', validationMethod: 0, advancedConfigJson: '{}' }] } })
  assert.deepEqual(page.data.storyDraftList.map((t) => t.id), [141])

  page.selectTemp({ currentTarget: { dataset: { item: page.data.storyDraftList[0] } } })
  page.confirmTemp()
  assert.equal(page.data.nodesForm.templateId, 141)
  // 2026-09-23 走查:选回草稿后节点名是空的,点「确定」被「请填写节点名称」挡住 —— 名字就是玩法名
  assert.equal(page.data.nodesForm.name, '新生儿登记', '选回玩法没带上名字')
  page.setData({ 'nodesForm.name': '作者自己起的名' })
  page.confirmTemp()
  assert.equal(page.data.nodesForm.name, '作者自己起的名', '负控:作者已起的名字不覆盖')
})

// 伏笔的名字表(2026-09-22 走查):编辑器里伏笔块显示的是随机键「tmud5hgwt」而不是「那扇窗」;
// 本地草稿恢复后伏笔下拉也是空的(名字表只在服务端回填那条路算过)。
test('本地草稿恢复后伏笔下拉与名字表都在;编辑器里伏笔块显示名字而不是键', () => {
  const page = makePage()
  const rules = JSON.stringify({ schemaVersion: 1, thoughts: [{ key: 'tmud5hgwt', name: '那扇窗', need: 3000 }] })
  page._applyDraftEnvelope({ formData: Object.assign({}, page.data.formData, { journeyRules: rules, chapters: [] }) })
  assert.deepEqual(page.data.thoughtOptions.map((t) => t.name), ['那扇窗'], '草稿恢复后伏笔下拉是空的')
  assert.equal(page.data.thoughtNameOf.tmud5hgwt, '那扇窗')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/index.wxml'), 'utf8')
  assert.doesNotMatch(wxml, /story-mood-block__name">\{\{block\.thoughtKey\}\}/, '伏笔块还在显示随机键')
  assert.match(wxml, /thoughtNameOf\[block\.thoughtKey\]/)
})

// 伏笔由选项得到 / 应验(2026-09-22 用户裁决「伏笔不能只是微信步数,回答出特定选项时也有」)。
// 作者在伏笔面板里从「本主题玩法里挂了贴标签效果的选项」挑,不用自己记 tag.xxx。
test('伏笔保存按钮属于伏笔面板，其他面板不重复挂载', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/index.wxml'), 'utf8')
  const start = wxml.indexOf('<cy-sheet show="{{thoughtsEditor.show}}"')
  assert.ok(start >= 0)
  const sheet = wxml.slice(start, wxml.indexOf('</cy-sheet>', start))
  assert.match(sheet, /bindtap="saveThoughts"[^>]*aria-label="保存伏笔"/)
  assert.match(sheet, /variant="bottom"/)
  assert.ok(sheet.indexOf('data-field="need"') < sheet.indexOf('data-field="desc"'))
  assert.ok(sheet.indexOf('data-field="desc"') < sheet.indexOf('bindtap="saveThoughts"'))
  assert.equal((sheet.match(/<cy-field\b/g) || []).length, 2)
  assert.doesNotMatch(sheet, /variant="full"|nav-title|data-field="name"|>内容<|toggleThoughtTag|slot="footer"|trash-2/)
  assert.doesNotMatch(sheet, /node-sheet__done|bindtap="addThought"/)
  assert.equal((wxml.match(/bindtap="saveThoughts"/g) || []).length, 1)
  assert.match(sheet, /data-index="\{\{ti\}\}" bindtap="removeThought"/, '多条存量配置有明确删除出口')
})

test('添加直接进入单个伏笔配置，完成才回填，重开编辑不再追加', () => {
  const page = makePage()
  const original = page.data.formData.journeyRules
  page.openThoughts()
  assert.equal(page.data.thoughtsEditor.show, true)
  assert.equal(page.data.thoughtsEditor.list.length, 1)
  assert.equal(page.data.thoughtsEditor.count, 0)
  const key = page.data.thoughtsEditor.list[0].key
  page.addThought(); page.addThought()
  assert.equal(page.data.thoughtsEditor.list.length, 1)
  page.saveThoughts()
  assert.equal(page.data.thoughtsEditor.show, true, '未填文字不能完成')
  assert.equal(page.data.thoughtsEditor.error, '请输入伏笔文字')
  assert.equal(page.data.formData.journeyRules, original)
  const input = value => page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'desc' } }, detail: { value } })
  input('那扇窗')
  assert.equal(page.data.thoughtsEditor.error, '', '修改输入后清除上一次缺填提示')
  page.closeThoughts()
  assert.equal(page.data.formData.journeyRules, original, '关闭不写入本轮修改')
  page.openThoughts()
  assert.equal(page.data.thoughtsEditor.list[0].desc, '')
  input('那扇窗')
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'need' } }, detail: { value: '' } })
  page.saveThoughts()
  assert.equal(page.data.thoughtsEditor.error, '请输入步数')
  assert.equal(page.data.formData.journeyRules, original, '缺步数不能完成')
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'need' } }, detail: { value: '0' } })
  page.saveThoughts()
  assert.equal(page.data.formData.journeyRules, original, '非法步数不能完成')
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'need' } }, detail: { value: '3000' } })
  assert.equal(page.data.thoughtsEditor.error, '', '步数填好后不残留非法步数提示')
  page.saveThoughts()
  const saved = page.data.formData.journeyRules
  assert.equal(page.data.thoughtsEditor.show, false)
  assert.equal(JSON.parse(saved).thoughts.length, 1)
  assert.equal(page.data.thoughtOptions[0].name, '伏笔', '名称由旧协议固定，不要求作者输入')
  assert.equal(page.data.thoughtOptions[0].desc, '那扇窗')
  assert.equal(page.data.thoughtOptions[0].need, 3000)
  assert.equal(page.data.thoughtsEditor.error, '')
  page.openThoughts()
  assert.equal(page.data.thoughtsEditor.list[0].desc, '那扇窗')
  assert.equal(page.data.thoughtsEditor.list[0].need, 3000)
  const savedKey = page.data.thoughtsEditor.list[0].key
  page.addThought()
  input('窗后的光')
  page.saveThoughts()
  assert.equal(JSON.parse(page.data.formData.journeyRules).thoughts[0].key, savedKey)
  assert.equal(page.data.thoughtOptions.length, 1)
  assert.equal(page.data.thoughtOptions[0].desc, '窗后的光')
  assert.ok(key, '添加时生成内部标识')
})

test('旧资料多个伏笔不静默截断，也不能保存多个', () => {
  const page = makePage()
  const raw = JSON.stringify({ thoughts: [{ key: 'a', name: '甲', need: 10 }, { key: 'b', name: '乙', need: 20 }] })
  page.setData({ 'formData.journeyRules': raw })
  page.openThoughts()
  page.addThought()
  assert.equal(page.data.thoughtsEditor.list.length, 2)
  page.saveThoughts()
  assert.equal(page.data.thoughtsEditor.show, true)
  assert.match(page.data.thoughtsEditor.error, /只能配置一个伏笔/)
  assert.equal(page.data.formData.journeyRules, raw)
})

test('简化伏笔面板编辑文字时保留旧选项条件，步数仍须填写', () => {
  const page = makePage()
  page.setData({ 'formData.journeyRules': JSON.stringify({ schemaVersion: 1, thoughts: [{
    key: 'w', name: '那扇窗', need: 3000,
    when: [{ op: 'HAS_TAG', value: 'tag.saw_window' }],
    doneWhen: [{ op: 'HAS_TAG', value: 'tag.opened_window' }],
  }] }) })
  page.openThoughts()
  assert.equal(page.data.thoughtsEditor.list[0].desc, '那扇窗', '旧资料只有名称时文字仍可回显')
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'desc' } }, detail: { value: '窗外有一道光' } })
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'need' } }, detail: { value: '' } })
  page.saveThoughts()
  assert.equal(page.data.thoughtsEditor.error, '请输入步数')
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'need' } }, detail: { value: '3000' } })
  page.saveThoughts()

  assert.equal(page.data.thoughtsEditor.error, '')
  const saved = JSON.parse(page.data.formData.journeyRules).thoughts[0]
  assert.deepEqual(saved.when, [{ op: 'HAS_TAG', value: 'tag.saw_window' }])
  assert.deepEqual(saved.doneWhen, [{ op: 'HAS_TAG', value: 'tag.opened_window' }])
  assert.equal(saved.need, 3000)
  assert.equal(saved.desc, '窗外有一道光')
  assert.equal(saved.name, '那扇窗', '存量名称作为旧协议数据保留')

  page.openThoughts()
  assert.deepEqual(page.data.thoughtsEditor.list[0].doneWhen, saved.doneWhen)
})

test('伏笔面板保留界面表达不了的条件(到过某站),不在保存时抹掉', () => {
  const page = makePage()
  page.setData({ 'formData.chapters': [], 'formData.journeyRules': JSON.stringify({ schemaVersion: 1, thoughts: [
    { key: 'w', name: '那扇窗', need: 120, doneWhen: [{ op: 'NODE_COMPLETED', nodeId: 88 }] }] }) })
  page.openThoughts()
  page.saveThoughts()
  assert.deepEqual(JSON.parse(page.data.formData.journeyRules).thoughts[0].doneWhen, [{ op: 'NODE_COMPLETED', nodeId: 88 }])
})

// 2026-09-22 走查:商家号序章已有 2 个玩法(名额 2),在第一章再插「拍照审核」—— 作者配完、
// 模板都存进库了,回来点「确定」才弹「节点已达上限」。名额要在点「插入玩法」那一刻就判。
// 2026-09-23 用户裁决:故事玩法不算节点名额 —— 名额只数地点。所以插故事玩法不判名额,插地点照判。
test('插入地点前先判节点名额:满了当场说,不进配置;插故事玩法不判', () => {
  const page = makePage()
  page.setData({ 'formData.productType': 1, 'formData.chapters': [{ _localId: 'c1', name: '第一章',
    blocks: [{ key: 'b1', type: 'text', content: '正文' }], nodes: [] }],
    'storyEditor.chapterIndex': 0, 'storyEditor.show': true })
  let asked = 0
  page._canAddFormalNodes = () => { asked += 1; return false }
  page.insertStoryNodeAt({ currentTarget: { dataset: { index: 1, kind: 'place' } } })
  assert.equal(asked, 1, '插地点前没判名额')
  assert.notEqual(page.data.popChapterNodes, true, '名额满了还打开了地点配置')

  page.insertStoryNodeAt({ currentTarget: { dataset: { index: 1, kind: 'game' } } })
  assert.equal(asked, 1, '故事玩法不算名额,不该去判')
  assert.equal(page.data.popChapterNodes, true, '名额满了也照样能插故事玩法')
})

test('节点数量不限，旧权限缓存的2节点额度不再限制地点和玩法', () => {
  const page = makePage()
  const roleGuard = require('../../utils/roleGuard.js')
  const saved = { hasSnapshot: roleGuard.hasSnapshot, quota: roleGuard.quota }
  roleGuard.hasSnapshot = () => true
  roleGuard.quota = (k) => (k === 'maxNodesPerTheme' ? 2 : null)
  try {
    page.setData({ 'formData.chapters': [{ _localId: 'c1', nodes: [
      { _localId: 'g1' }, { _localId: 'g2' }, { _localId: 'p1', latitude: 31.2, longitude: 121.4 },
    ], blocks: [
      { key: 'b1', type: 'node', nodeKey: 'g1', locationRequired: false },
      { key: 'b2', type: 'node', nodeKey: 'g2', locationRequired: false },
      { key: 'b3', type: 'node', nodeKey: 'p1' },
    ] }] })
    assert.equal(page._canAddFormalNodes(1), true, '两个故事玩法 + 一个地点 = 占 1 个名额,还能再加 1 个地点')
    page.data.formData.chapters[0].blocks[0].locationRequired = true
    assert.equal(page._canAddFormalNodes(1000), true, '地点和玩法都不受旧节点额度限制')
  } finally { Object.assign(roleGuard, saved) }
})

// 结局 = 结局章(2026-09-24 用户:「结局应该是添加章节,可以设定这个章节为结局,打开开关」)
test('草稿恢复:剧情里的老结局清单转成结局章(章名 = 标题,正文 = 那一句);已有结局章就不再转', () => {
  const page = makePage()
  const story = JSON.stringify({ schemaVersion: 1, ending: { endings: [
    { code: 'window', title: '窗外', summary: '你推开了窗。', when: [{ op: 'HAS_TAG', value: 'thought.w.done' }] },
    { code: 'default', title: '余波', fallback: true },
  ] } })
  const base = [{ _localId: 'c1', name: '出生', nodes: [], blocks: [] }]
  page._applyDraftEnvelope({ formData: Object.assign({}, page.data.formData, { journeyStory: story, chapters: base }) })
  const chapters = page.data.formData.chapters
  assert.deepEqual(chapters.map((c) => c.name), ['出生', '窗外', '余波'])
  assert.deepEqual(JSON.parse(JSON.stringify(chapters[1].ending)), { fallback: false, kind: 'thought', thoughtKey: 'w', tag: '', nodeId: null })
  assert.equal(chapters[1].blocks[0].content, '你推开了窗。')
  assert.equal(chapters[2].ending.fallback, true)

  const again = makePage()
  again._applyDraftEnvelope({ formData: Object.assign({}, again.data.formData, { journeyStory: story, chapters }) })
  assert.equal(again.data.formData.chapters.length, 3, '已经是结局章了,不重复转')
})

test('打开结局章的设置:只有键的老结局按清单回显名字,不写「请选择」', () => {
  const page = makePage()
  page.setData({ 'formData.journeyRules': JSON.stringify({ schemaVersion: 1, thoughts: [{ key: 'w', name: '那扇窗', need: 10 }] }),
    'formData.chapters': [{ _localId: 'c1', name: '窗外', nodes: [], blocks: [], ending: { fallback: false, kind: 'thought', thoughtKey: 'w' } }] })
  page.showEditChapter({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(page.data.chapterForm.ending.thoughtName, '那扇窗')
})

<<<<<<< public

test('legacy multiple thoughts can be reduced without silently removing story or ending references', () => {
  const page = makePage();
  const thoughts = [{ key: 'old_a', name: '伏笔', desc: '保留文字', need: 120 },
    { key: 'old_b', name: '伏笔', desc: '删除文字', need: 240 }];
  page.data.formData.journeyRules = JSON.stringify({ version: 1, thoughts });
  page.data.formData.journeyStory = JSON.stringify({ ending: { thoughtKey: 'old_b' } });
  page.openThoughts();
  const event = { currentTarget: { dataset: { index: 1 } } };
  page.removeThought(event);
  assert.equal(page.data.thoughtsEditor.list.length, 2);
  assert.match(page.data.thoughtsEditor.error, /仍引用/);
  assert.equal(JSON.parse(page.data.formData.journeyStory).ending.thoughtKey, 'old_b');
  page.data.formData.journeyStory = '';
  page.data.formData.chapters = [{ storyBlocks: [{ kind: 'thought', thoughtKey: 'old_b' }] }];
  page.removeThought(event);
  assert.equal(page.data.thoughtsEditor.list.length, 2, '正文引用也必须先移除');
  page.data.formData.chapters = [];
  page.removeThought(event);
  assert.equal(page.data.thoughtsEditor.list.length, 1);
  page.saveThoughts();
  const saved = JSON.parse(page.data.formData.journeyRules).thoughts;
  assert.deepEqual(saved.map(row => row.key), ['old_a']);
  assert.equal(saved[0].desc, '保留文字');
=======
test('运行时关闭时已有配置不被清空，也不发送新字段', () => {
  const page = makePage(); page.data.formData.themeRuntimeConfig = '{not parsed while disabled';
  let submitted; page.sendData = payload => { submitted = payload }; page._doSubmit();
  assert.ok(submitted); assert.equal(Object.prototype.hasOwnProperty.call(submitted, 'themeRuntimeConfig'), false);
  assert.equal(page.data.formData.themeRuntimeConfig, '{not parsed while disabled');
});

test('运行时能力打开后保存配置与显式清空，普通旧表单继续省略', () => {
  const page = makePage(); page.data.runtimeEnabled = true;
  let submitted; page.sendData = payload => { submitted = payload }; page._doSubmit();
  assert.equal(Object.prototype.hasOwnProperty.call(submitted, 'themeRuntimeConfig'), false);
  page.data.formData.themeRuntimeConfig = JSON.stringify({schemaVersion:1,capacity:3,items:[],chapters:[],grants:[],events:[]});
  page._doSubmit(); assert.equal(JSON.parse(submitted.themeRuntimeConfig).capacity, 3);
  page.data.formData.themeRuntimeConfig = ''; page._doSubmit(); assert.equal(submitted.themeRuntimeConfig, '');
});

test('编辑回读保留明确空字符串，不用旧topic字段覆盖清空', () => {
  const page = makePage(); page.data.editingTopicId = 1; const old = JSON.stringify({schemaVersion:1,capacity:3,items:[],chapters:[],grants:[],events:[]});
  page.applyEditingTopic({topic:{id:1,name:'主题',productType:1,themeRuntimeConfigJson:old},themeRuntimeConfig:'',chapters:[],tickets:[]});
  assert.equal(page.data.formData.themeRuntimeConfig, '');
  page.applyEditingTopic({topic:{id:1,name:'主题',productType:1,themeRuntimeConfigJson:old},chapters:[],tickets:[]});
  assert.equal(page.data.formData.themeRuntimeConfig, old);
});

test('本地草稿恢复保留运行时配置且不自行启用能力', () => {
  const page = makePage(); const raw = JSON.stringify({schemaVersion:1,capacity:8,items:[],chapters:[],grants:[],events:[]});
  page._applyDraftEnvelope({formData:Object.assign({},page.data.formData,{themeRuntimeConfig:raw}),pendingMaterials:[],editorMeta:{}});
  assert.equal(page.data.formData.themeRuntimeConfig, raw); assert.equal(page.data.runtimeEnabled, false); assert.equal(page._runtimePayload(), undefined);
});

test('章节取消丢弃新增库存，章节完成提交而不移除旧结局入口', () => {
  const page = makePage(); page.data.runtimeEnabled = true;
  page.data.formData.chapters = [{name:'第一章',nodes:[],blocks:[]}];
  page.showEditChapter({currentTarget:{dataset:{index:0}}});page.openChapterBackpack();page.addRuntimeContent({currentTarget:{dataset:{kind:'ITEM'}}});
  page.closeRuntimeEditor();page.cancelChapter();assert.equal(page.data.formData.themeRuntimeConfig, undefined);
  page.showEditChapter({currentTarget:{dataset:{index:0}}});page.openChapterBackpack();page.addRuntimeContent({currentTarget:{dataset:{kind:'ITEM'}}});
  page.closeRuntimeEditor();page.confrimChapter();assert.equal(JSON.parse(page.data.formData.themeRuntimeConfig).items.length,1);
  assert.equal(typeof page.onChapterEndingToggle,'function');assert.equal(typeof page.onBingoToggle,'function');
});

// Staged initial attributes: no network or registration call until original save flow.
const initialEvent = (id, field, value) => ({ currentTarget: { dataset: { id, field } }, detail: { value } });
function initialPage(raw = '') { const page = makePage(); page.data.formData.journeyRules = raw; page.openInitialState(); return page; }
function fillAttribute(page, key = 'counter.focus') {
  page.addInitialAttribute(); const id = page.data.initialStateEditor.attributes.at(-1).id;
  page.onInitialAttributeField(initialEvent(id, 'key', key));
  page.onInitialAttributeField(initialEvent(id, 'label', '专注'));
  return id;
}
test('初始属性打开/完成/取消均保留原始缺省、null、空数组、未知字段和空白', () => {
  for (const raw of ['', null, '{ }', ' { "attributes" : [] , "extra" : {"a":1} } ', '{"attributes":null}', '{"stateEnabled":false,"attributes":[{"key":"counter.a","label":" A ","future":1}]}']) {
    const page = initialPage(raw); page.saveInitialState(); assert.equal(page.data.formData.journeyRules, raw);
    page.openInitialState(); if (!page.data.initialStateEditor.attributeError) fillAttribute(page);
    page.closeInitialState(); assert.equal(page.data.formData.journeyRules, raw);
  }
  assert.equal(requests.length, 0);
});
test('属性从空草稿新增、完成、重开回填，只有显式总开关启用且仍零请求', () => {
  const page = initialPage(''); const id = fillAttribute(page);
  page.onInitialAttributeField(initialEvent(id, 'initial', '5'));
  page.onInitialAttributeField(initialEvent(id, 'visible', false));
  assert.equal(page.data.formData.journeyRules, '');
  assert.equal(page.data.initialStateEditor.rules.stateEnabled, false);
  page.saveInitialState(); const saved = JSON.parse(page.data.formData.journeyRules);
  assert.equal(saved.stateEnabled, undefined); assert.equal(saved.hp, undefined);
  assert.deepEqual(saved.attributes[0], { key: 'counter.focus', label: '专注', initial: 5, min: -10000, max: 10000, visible: false });
  page.openInitialState(); assert.equal(page.data.initialStateEditor.attributes[0].existing, true);
  page.onInitialAttributesEnabled({ detail: { value: true } }); page.saveInitialState();
  assert.equal(JSON.parse(page.data.formData.journeyRules).stateEnabled, true); assert.equal(requests.length, 0);
});
test('既有标识不可修改，属性改名保留高级行数据及无关 HP/念头原值', () => {
  const root = { stateEnabled: false, hp: { enabled: true, strange: 1 }, thoughts: null, attributes: [{ key: 'relation.a', label: '旧名', future: { grants: [1] } }] };
  const page = initialPage(JSON.stringify(root)), id = page.data.initialStateEditor.attributes[0].id;
  page.onInitialAttributeField(initialEvent(id, 'key', 'relation.b'));
  page.onInitialAttributeField(initialEvent(id, 'label', '新名')); page.saveInitialState();
  assert.deepEqual(JSON.parse(page.data.formData.journeyRules), { ...root, attributes: [{ ...root.attributes[0], label: '新名' }] });
});
test('flag 属性限定上下限，坏数值/重键阻止暂存并保持原草稿', () => {
  const page = initialPage('{}'), id = fillAttribute(page, 'tag.a');
  page.onInitialAttributeField(initialEvent(id, 'min', '-5'));
  assert.equal(page.data.initialStateEditor.rules.attributes[0].min, 0);
  page.onInitialAttributeField(initialEvent(id, 'initial', '2')); page.saveInitialState();
  assert.equal(page.data.formData.journeyRules, '{}'); assert.match(page.data.initialStateEditor.error, /上下限/);
  page.onInitialAttributeField(initialEvent(id, 'initial', '1')); fillAttribute(page, 'tag.a'); page.saveInitialState();
  assert.match(page.data.initialStateEditor.error, /重复/); assert.equal(page.data.formData.journeyRules, '{}');
});
test('最多 32 个属性，坏导入声明只显示错误并保留原始数据', () => {
  const raw = JSON.stringify({ attributes: Array.from({ length: 32 }, (_, i) => ({ key: 'counter.a' + i, label: 'A' })) });
  const page = initialPage(raw); page.addInitialAttribute(); assert.equal(page.data.initialStateEditor.attributes.length, 32);
  page.closeInitialState(); page.data.formData.journeyRules = '{"attributes":null}'; page.openInitialState(); page.addInitialAttribute();
  assert.equal(page.data.initialStateEditor.attributes.length, 0); assert.ok(page.data.initialStateEditor.attributeError);
  page.onInitialAttributesEnabled({ detail: { value: true } }); page.saveInitialState(); assert.ok(page.data.initialStateEditor.error);
  assert.equal(page.data.formData.journeyRules, '{"attributes":null}');
});
test('移除仅暂存声明，不删除注册表/引用；取消确认不改任何行', () => {
  deferModal = true;
  const root = { stateEnabled: true, ref: { var: 'counter.a' }, attributes: [{ key: 'counter.a', label: 'A' }] };
  const page = initialPage(JSON.stringify(root)), id = page.data.initialStateEditor.attributes[0].id;
  page.removeInitialAttribute({ currentTarget: { dataset: { id } } });
  assert.match(modals.at(-1).content, /不删除已登记的状态键/); modals.at(-1).success({ confirm: false });
  assert.equal(page.data.initialStateEditor.attributes.length, 1);
  page.removeInitialAttribute({ currentTarget: { dataset: { id } } }); modals.at(-1).success({ confirm: true });
  assert.equal(page.data.formData.journeyRules, JSON.stringify(root)); page.saveInitialState();
  assert.deepEqual(JSON.parse(page.data.formData.journeyRules), { ...root, attributes: [] }); assert.equal(requests.length, 0);
});
test('延迟移除确认在编辑后/关闭重开后不能作用，新旧行 id 永不复用', () => {
  deferModal = true; const page = initialPage('{}'), id = fillAttribute(page);
  page.removeInitialAttribute({ currentTarget: { dataset: { id } } }); const delayed = modals.at(-1);
  page.onInitialAttributeField(initialEvent(id, 'label', '修改')); delayed.success({ confirm: true });
  assert.equal(page.data.initialStateEditor.attributes.length, 1);
  page.removeInitialAttribute({ currentTarget: { dataset: { id } } }); const retired = modals.at(-1);
  page.closeInitialState(); page.openInitialState(); const newId = fillAttribute(page); retired.success({ confirm: true });
  assert.notEqual(newId, id); assert.equal(page.data.initialStateEditor.attributes.length, 1);
  page.removeInitialAttribute({ currentTarget: { dataset: { id: newId } } }); const done = modals.at(-1); done.success({ confirm: true });
  const third = fillAttribute(page); done.success({ confirm: true }); assert.notEqual(third, newId); assert.equal(page.data.initialStateEditor.attributes.length, 1);
});
test('身份/主题/源内容/版本/权限改变使属性暂存和延迟回调失效', () => {
  const changes = [p => { currentOwner = 43 }, p => { p.data.editingTopicId = 99 }, p => { p.data.formData.name = '另一草稿' }, p => { p.data.baseRevision = 2 }, p => { p.data.draftUuid = 'another' }, p => { p.data.editScope = 'WHITELIST' }, p => { p._editLoadToken = 3 }];
  for (const change of changes) {
    currentOwner = 42; deferModal = true; const page = initialPage('{}'), id = fillAttribute(page);
    page.removeInitialAttribute({ currentTarget: { dataset: { id } } }); const pending = modals.at(-1); change(page); pending.success({ confirm: true }); page.saveInitialState();
    assert.equal(page.data.formData.journeyRules, '{}'); assert.equal(page.data.initialStateEditor.show, false);
  }
});
test('hide/unload 后属性声明和确认回调退休，保留 saved-check 生命周期', () => {
  for (const method of ['onHide', 'onUnload']) {
    deferModal = true; const page = initialPage('{}'), id = fillAttribute(page);
    page.removeInitialAttribute({ currentTarget: { dataset: { id } } }); const pending = modals.at(-1);
    page.destroyPreviewAudio = () => {}; page[method](); pending.success({ confirm: true });
    assert.equal(page.data.initialStateEditor.rules, null); assert.equal(page.data.formData.journeyRules, '{}'); assert.equal(page._savedCheckHidden, true);
  }
});
test('WHITELIST 可读取配置但属性/总开关/资源不能编辑，原始 bytes 保留', () => {
  const page = makePage(); page.data.editScope = 'WHITELIST'; const raw = '{ "attributes": [] }'; page.data.formData.journeyRules = raw; page.openInitialState();
  page.addInitialAttribute(); page.onInitialAttributesEnabled({ detail: { value: true } }); page.addInitialState({ currentTarget: { dataset: { type: 'hp' } } }); page.saveInitialState();
  assert.equal(page.data.formData.journeyRules, raw); assert.equal(requests.length, 0);
});
test('现有 HP/LUCK 独立增减行为保留，显式总开关在混合修改时优先', () => {
  const page = initialPage(''); page.addInitialState({ currentTarget: { dataset: { type: 'hp' } } }); page.saveInitialState();
  assert.equal(JSON.parse(page.data.formData.journeyRules).stateEnabled, true);
  page.openInitialState(); fillAttribute(page); page.onInitialStateField(initialEvent(null, 'hp.init', 8)); page.onInitialAttributesEnabled({ detail: { value: false } }); page.saveInitialState();
  const saved = JSON.parse(page.data.formData.journeyRules); assert.equal(saved.stateEnabled, false); assert.equal(saved.hp.init, 8);
});
test('初始属性视图绑定五个必要字段、稳定行键、总开关及原关闭/完成入口', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/index.wxml'), 'utf8');
  for (const marker of ['wx:key="id"', 'bind:change="onInitialAttributesEnabled"', 'bindtap="addInitialAttribute"', 'bindtap="removeInitialAttribute"', 'data-field="initial"', 'data-field="visible"', 'bindtap="saveInitialState"', 'bind:close="closeInitialState"', 'initialStateEditor.show || routeMappingShow']) assert.ok(wxml.includes(marker), marker);
});

test('移除初始属性使用页面统一确认宿主，取消保留声明且不触发原生弹窗', () => {
  const page = makePage();
  page.data.formData.journeyRules = JSON.stringify({ attributes: [{ key: 'counter.focus', label: '专注', initial: 1, min: 0, max: 10, visible: true }] });
  page.openInitialState();
  const id = page.data.initialStateEditor.attributes[0].id;
  const previous = global.getCurrentPages;
  let shown;
  page.selectComponent = selector => selector === '#cy-modal-host' ? { open: options => { shown = options; } } : null;
  global.getCurrentPages = () => [page];
  try {
    page.removeInitialAttribute({ currentTarget: { dataset: { id } } });
    assert.ok(shown, '确认必须交给页面现有 cy-modal-host');
    assert.equal(modals.length, 0, '宿主存在时不得直接打开原生弹窗');
    assert.equal(page.data.initialStateEditor.attributes.length, 1);
    shown.success({ confirm: false });
    assert.equal(page.data.initialStateEditor.attributes.length, 1);
    assert.equal(requests.length, 0);
  } finally {
    if (previous === undefined) delete global.getCurrentPages;
    else global.getCurrentPages = previous;
  }
>>>>>>> private-github/featu
});

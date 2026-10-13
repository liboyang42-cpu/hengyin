const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const VueRouter = require('vue-router')

const uiRoot = path.resolve(__dirname, '../..')
const read = relativePath => fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
const topicIndexSource = read('src/views/business/topic/index.vue')
const workspaceSource = read('src/views/business/topic/workspace.vue')
const detailSource = read('src/views/business/topic/detail.vue')
const topicApiSource = read('src/api/business/topic.js')
const ticketApiSource = read('src/api/business/ticket.js')
const routerSource = read('src/router/index.js')
const topicMenuMigrationSource = read('../chengyinhub-admin/sql/migration_topic_source_menu_20260722.sql')
const chapterControllerSource = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsTopicChapterController.java')
const ticketControllerSource = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/OmsTicketController.java')
const fundsControllerSource = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/TopicEditionFundsController.java')

function loadTopicDetail(dependencies) {
  const script = detailSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[\s\S]*?\} from "@\/api\/business\/topic"$/m,
      'const { getTopic, getTopicChapter, addTopicChapter, updateTopicChapter, delTopicChapter, configureChapterSupplyTerms, getChapterServiceCapacity, configureChapterServiceCapacity, getTopicLeadFulfillment } = dependencies')
    .replace(/^import \{ addTicket, updateTicket \} from "@\/api\/business\/ticket"$/m,
      'const { addTicket, updateTicket } = dependencies')
    .replace(/^import \{ addTopicNode, updateTopicNode, delTopicNode \} from "@\/api\/business\/topicnode"$/m,
      'const { addTopicNode, updateTopicNode, delTopicNode } = dependencies')
    .replace(/^import \{ getTopicFunnel \} from "@\/api\/business\/topicFunnel"$/m,
      'const { getTopicFunnel } = dependencies')
    .replace(/^import \{ parseTime \} from "@\/utils\/ruoyi"$/m, 'const parseTime = () => null')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

function methodBody(source, signature, nextSignature) {
  const start = source.indexOf(signature)
  const end = source.indexOf(nextSignature, start)
  assert.notEqual(start, -1, `${signature} must exist`)
  assert.notEqual(end, -1, `${nextSignature} must follow ${signature}`)
  const block = source.slice(start + signature.length, end)
  return block.replace(/\n\s*\},\s*$/, '')
}

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve))
}

function topicDetailDependencies(overrides = {}) {
  return Object.assign({
    getTopic: () => Promise.resolve({ code: 200, data: {} }),
    getTopicChapter: () => Promise.resolve({ code: 200, data: {} }),
    addTopicChapter: () => Promise.resolve({ code: 200 }),
    updateTopicChapter: () => Promise.resolve({ code: 200 }),
    delTopicChapter: () => Promise.resolve({ code: 200 }),
    configureChapterSupplyTerms: () => Promise.resolve({ code: 200 }),
    getChapterServiceCapacity: () => Promise.resolve({ code: 200, data: {} }),
    configureChapterServiceCapacity: () => Promise.resolve({ code: 200 }),
    getTopicLeadFulfillment: () => Promise.resolve({ code: 200, data: [] }),
    addTicket: () => Promise.resolve({ code: 200 }),
    updateTicket: () => Promise.resolve({ code: 200 })
  }, overrides)
}

test('管理员从主题列表经工作台的真实按钮进入章节与票务编辑页', () => {
  assert.match(topicMenuMigrationSource, /SET menu_name = '官方主题',[\s\S]*path = 'official-topic',[\s\S]*route_name = 'OfficialTopic'/)
  assert.match(topicMenuMigrationSource, /component = 'business\/topic\/index'[\s\S]*route_name = 'OfficialTopic'/)
  assert.match(topicIndexSource, /@click="goDetail\(scope\.row\)"[\s\S]*?>工作台<\/el-button>/)
  assert.match(workspaceSource, /<el-tab-pane label="内容与售卖"[\s\S]*?@click="openLegacyDetail"/)
  assert.match(workspaceSource, /<h2>章节、节点与票务<\/h2>/)

  const pushes = []
  const goDetail = new Function('row', methodBody(topicIndexSource,
    '    goDetail(row) {', '\n\n    workspaceScope(row) {'))
  goDetail.call({
    workspaceScope: () => 'OFFICIAL',
    $router: { push: target => pushes.push(target) },
    $modal: { msgWarning: () => assert.fail('official topic must be navigable') }
  }, { id: 17 })
  assert.deepEqual(pushes[0], { path: '/topic/workspace', query: { id: 17, scope: 'OFFICIAL' } })

  const openLegacyDetail = new Function(methodBody(workspaceSource,
    '    openLegacyDetail() {', '\n    operationSourceLabel(sourceRef) {'))
  openLegacyDetail.call({
    canOpenTopicEditor: true,
    topic: { id: 17 },
    $router: { push: target => pushes.push(target) }
  })
  assert.deepEqual(pushes[1], { path: '/topic/detail', query: { id: 17 } })

  const routeStart = routerSource.indexOf("  {\n    path: '/topic',")
  const routeEnd = routerSource.indexOf("\n  {\n    path: '/refund',", routeStart)
  const route = new Function('Layout', `return (${routerSource.slice(routeStart + 2, routeEnd).replace(/,$/, '')})`)({})
  const router = new VueRouter({ mode: 'abstract', routes: [route] })
  const resolved = router.match('/topic/detail?id=17')
  assert.equal(resolved.name, 'topicDetail')
  assert.deepEqual(route.permissions, ['business:topic:query'])
})

test('主题详情的真实控件与既有章节、票务 API 和后台权限合同相连', () => {
  assert.match(detailSource, /@click="handleAddChapter"[^>]*>新增章节<\/el-button>/)
  assert.match(detailSource, /@click="submitChapterForm"[^>]*>确 定<\/el-button>/)
  assert.match(detailSource, /@click="handleAddTicket"[^>]*>添加票务<\/el-button>/)
  assert.match(detailSource, /@click="submitTicketForm"[^>]*>确 定<\/el-button>/)
  assert.match(topicApiSource, /export function addTopicChapter\(data\)[\s\S]*url:\s*'\/business\/topicchapter'[\s\S]*method:\s*'post'/)
  assert.match(ticketApiSource, /export function addTicket\(data\)[\s\S]*url:\s*'\/business\/ticket'[\s\S]*method:\s*'post'/)
  assert.match(chapterControllerSource, /hasPermi\('business:topicchapter:add'\)[\s\S]*@PostMapping[\s\S]*insertCmsTopicChapter/)
  assert.match(ticketControllerSource, /hasPermi\('business:ticket:add'\)[\s\S]*@PostMapping[\s\S]*insertOmsTicket/)
})

test('organizer 与期级资金都有真实后台入口、权限和回读', () => {
  assert.match(detailSource, /@click="declareOrganizer"/)
  assert.match(topicApiSource, /declareTopicOrganizerActor[\s\S]*organizer-actor[\s\S]*method: 'put'/)
  assert.match(detailSource, /期级资金快照（PROVISIONAL）[\s\S]*@click="recomputeFunds"/)
  assert.match(topicApiSource, /getTopicEditionFunds[\s\S]*topic-edition-funds[\s\S]*current/)
  assert.match(fundsControllerSource, /hasPermi\('business:topic:query'\)[\s\S]*GetMapping\("\/\{topicId\}\/current"\)/)
  assert.match(fundsControllerSource, /hasPermi\('business:topic:edit'\)[\s\S]*PostMapping\("\/\{topicId\}\/recompute"\)/)
})

test('管理员从空主题连续创建四个必选 PERK 店位章节且每次保存后回读', async () => {
  const chapters = []
  let readbacks = 0
  const component = loadTopicDetail(topicDetailDependencies({
    getTopic: id => {
      readbacks += 1
      return Promise.resolve({
        code: 200,
        data: { id, memberId: 9, cmsTopicChapterList: chapters.map(chapter => Object.assign({}, chapter)) }
      })
    },
    addTopicChapter: payload => {
      chapters.push(Object.assign({ id: chapters.length + 1 }, payload))
      return Promise.resolve({ code: 200 })
    }
  }))
  const vm = Object.assign(component.data(), {
    topicDetail: { id: 17, memberId: 9 },
    chapterList: [],
    $refs: { chapterForm: { validate: callback => callback(true), clearValidate: () => {} } },
    $nextTick: callback => callback(),
    $modal: { msgSuccess: () => {} }
  })
  vm.handleAddChapter = component.methods.handleAddChapter.bind(vm)
  vm.submitChapterForm = component.methods.submitChapterForm.bind(vm)
  vm.getDetail = component.methods.getDetail.bind(vm)

  for (const [index, name] of ['咖啡店', '甜品店', '花店', '手作店'].entries()) {
    vm.handleAddChapter()
    vm.chapterForm.name = name
    vm.chapterForm.description = `第 ${index + 1} 站`
    vm.submitChapterForm()
    await flushPromises()
  }

  assert.equal(readbacks, 4)
  assert.equal(vm.chapterList.length, 4)
  assert.deepEqual(chapters.map(chapter => ({
    topicId: chapter.topicId,
    name: chapter.name,
    sortId: chapter.sortId,
    required: chapter.required,
    termsMode: chapter.termsMode,
    maxMerchant: chapter.maxMerchant,
    recruitEnabled: chapter.recruitEnabled
  })), ['咖啡店', '甜品店', '花店', '手作店'].map((name, index) => ({
    topicId: 17,
    name,
    sortId: index + 1,
    required: 1,
    termsMode: 'PERK',
    maxMerchant: 1,
    recruitEnabled: 0
  })))
})

test('管理员连续保存四张同价异时 oms_ticket，逐张回读且不携带 H11 禁项', async () => {
  const tickets = []
  let readbacks = 0
  const component = loadTopicDetail(topicDetailDependencies({
    getTopic: id => {
      readbacks += 1
      return Promise.resolve({
        code: 200,
        data: { id, memberId: 9, omsTicketList: tickets.map(ticket => Object.assign({}, ticket)) }
      })
    },
    addTicket: payload => {
      tickets.push(Object.assign({ id: tickets.length + 101 }, payload))
      return Promise.resolve({ code: 200 })
    }
  }))
  const vm = Object.assign(component.data(), {
    topicDetail: { id: 17, memberId: 9 },
    ticketList: [],
    $refs: { ticketForm: { validate: callback => callback(true), clearValidate: () => {} } },
    $nextTick: callback => callback(),
    $modal: { msgSuccess: () => {}, msgError: message => assert.fail(message) }
  })
  vm.handleAddTicket = component.methods.handleAddTicket.bind(vm)
  vm.submitTicketForm = component.methods.submitTicketForm.bind(vm)
  vm.getDetail = component.methods.getDetail.bind(vm)

  const slots = [
    ['2026-09-05 09:00:00', '2026-09-05 12:00:00'],
    ['2026-09-05 13:00:00', '2026-09-05 16:00:00'],
    ['2026-09-06 09:00:00', '2026-09-06 12:00:00'],
    ['2026-09-06 13:00:00', '2026-09-06 16:00:00']
  ]
  for (const [index, slot] of slots.entries()) {
    vm.handleAddTicket()
    Object.assign(vm.ticketForm, {
      name: `探店日场次 ${index + 1}`,
      price: 69,
      totalInventory: 30,
      startTime: slot[0],
      endTime: slot[1]
    })
    vm.submitTicketForm()
    await flushPromises()
  }

  assert.equal(readbacks, 4)
  assert.equal(vm.ticketList.length, 4)
  assert.deepEqual(tickets.map(ticket => ticket.price), [69, 69, 69, 69])
  assert.deepEqual(tickets.map(ticket => [ticket.startTime, ticket.endTime]), slots)
  assert.deepEqual(tickets.map(ticket => ticket.ownerType), [1, 1, 1, 1])
  assert.deepEqual(tickets.map(ticket => ticket.ownerId), [17, 17, 17, 17])
  assert.deepEqual(tickets.map(ticket => ticket.totalInventory), [30, 30, 30, 30])
  const prohibitedPayloadKeys = ['kMin', 'openLine', 'tierK', 'unlockedAt', 'minPeople', 'maxPeople', 'teamStatus']
  for (const ticket of tickets) {
    for (const key of prohibitedPayloadKeys) assert.equal(Object.hasOwn(ticket, key), false, key)
  }
})

test('票务保存失败给出可见反馈，建期入口不出现 H11 禁用合同', async () => {
  const errors = []
  const originalConsoleError = console.error
  console.error = () => {}
  const component = loadTopicDetail(topicDetailDependencies({
    addTicket: () => Promise.reject(new Error('network down')),
    updateTicket: () => Promise.resolve({ code: 200 })
  }))
  const vm = Object.assign(component.data(), {
    topicDetail: { id: 17, memberId: 9 },
    editingTicketIndex: -1,
    ticketForm: {
      id: null, ownerId: 17, ownerType: 1, memberId: 9, name: '场次 1', price: 69,
      totalInventory: 30, remainingInventory: 30,
      startTime: '2026-09-05 09:00:00', endTime: '2026-09-05 12:00:00'
    },
    $refs: { ticketForm: { validate: callback => callback(true) } },
    $modal: { msgSuccess: () => {}, msgError: message => errors.push(message) }
  })
  try {
    vm.submitTicketForm = component.methods.submitTicketForm.bind(vm)
    vm.submitTicketForm()
    await flushPromises()
  } finally {
    console.error = originalConsoleError
  }
  assert.deepEqual(errors, ['操作失败，请重试'])

  const prohibitedUiContracts = [
    /k_min/i, /open_line/i, /tier_k/i, /unlocked_at/i,
    /逐场解锁/, /阶梯表/, /最低人数/, /成团线/, /条件预售池/, /N_BE\s*(?:闸|门)/i
  ]
  for (const contract of prohibitedUiContracts) assert.doesNotMatch(detailSource, contract)
})

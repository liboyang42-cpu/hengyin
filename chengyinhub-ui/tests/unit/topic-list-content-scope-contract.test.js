const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const page = fs.readFileSync(
  path.resolve(__dirname, '../../src/views/business/topic/index.vue'),
  'utf8'
)
const topicApi = fs.readFileSync(
  path.resolve(__dirname, '../../src/api/business/topic.js'),
  'utf8'
)

function loadComponent() {
  const script = page.match(/<script>([\s\S]*?)<\/script>/)[1]
  const body = script
    .replace(/^import (\w+) from ['"][^'"]+\.vue['"]$/gm, 'const $1 = {}')
    .replace(/^import .*$/gm, '')
    .replace(/export default/, 'return ')
  return new Function(body)()
}

test('主题列表按 CONTENT 范围查询，并用内容主体替代旧 topicType 展示', () => {
  assert.match(page, /label="内容主体"[\s\S]*queryParams\.contentScope/)
  assert.match(page, /OFFICIAL/)
  assert.match(page, /PLAZA/)
  assert.match(page, /HISTORICAL/)
  assert.match(page, /urlParams\.get\('scope'\)/,
    '官方/广场入口的 URL scope 必须进入查询参数')
  assert.match(page, /contentScope: null/)
  assert.match(page, /publisherLabel\(row\)/)
  assert.match(page, /历史待归属/)
  assert.match(page, /玩法/)
  assert.match(page, /productTypeLabel\(row\)/)
  assert.match(page, /运营阶段/)
  assert.match(page, /lifecycleLabel\(row\)/)
  assert.match(page, /beforeRouteUpdate\(to, from, next\)/,
    '同一列表路由切换官方/广场时必须重新同步范围')
  assert.match(page, /syncRouteContentScope\(to\.query\.scope\)/)
  assert.match(page, /__INVALID_ROUTE_SCOPE__/,
    '未知 URL scope 必须传给后端 fail-closed，而不是回落全量')
  assert.doesNotMatch(page, /scope\.row\.topicType/,
    '列表不能继续把 topicType 当作官方/广场或主题类型的展示真源')
})

test('两个入口的顶部搜索可显式跨来源，但不把历史待归属混入结果', () => {
  const component = loadComponent()
  const vm = {
    routeContentScope: 'OFFICIAL',
    crossSourceSearch: false,
    isSourceEntry() {
      return this.routeContentScope === 'OFFICIAL' || this.routeContentScope === 'PLAZA'
    }
  }

  assert.match(page, /跨来源检索/)
  assert.equal(component.methods.effectiveContentScope.call(vm), 'OFFICIAL')
  vm.crossSourceSearch = true
  assert.equal(component.methods.effectiveContentScope.call(vm), 'CROSS_SOURCE')
  vm.routeContentScope = 'HISTORICAL'
  assert.equal(component.methods.effectiveContentScope.call(vm), 'HISTORICAL')
})

test('主题广场提供俱乐部与会员子队列，并以独立权限治理推荐位', () => {
  const component = loadComponent()
  const vm = {
    routeContentScope: 'PLAZA',
    crossSourceSearch: false,
    plazaPublisherType: 'CLUB',
    isPlazaEntry() { return this.routeContentScope === 'PLAZA' }
  }

  assert.match(page, /label="广场子队列"[\s\S]*俱乐部发布[\s\S]*会员发布/)
  assert.match(page, /v-else-if="isPlazaEntry\(\)"[\s\S]*business:topic:plaza:recommend/)
  assert.equal(component.methods.effectivePlazaPublisherType.call(vm), 'CLUB')
  vm.plazaPublisherType = 'MEMBER'
  assert.equal(component.methods.effectivePlazaPublisherType.call(vm), 'MEMBER')
  vm.crossSourceSearch = true
  assert.equal(component.methods.effectivePlazaPublisherType.call(vm), null,
    '跨来源检索不能残留广场子队列，避免把官方主题静默排除')
  assert.match(page, /LIFECYCLE_TERMINAL: '已结算或关闭'/)
  assert.match(topicApi, /export function updatePlazaRecommendation\(id, data\)/)
  assert.match(topicApi, /url: '\/business\/topic\/' \+ id \+ '\/plaza-recommendation'/)
})

test('跨来源检索按行主体收紧创作操作，并按被点主题选择推荐接口', () => {
  const component = loadComponent()
  const vm = {}

  assert.match(page, /<el-table-column v-if="isOfficialEntry\(\) && !crossSourceSearch" type="selection"/)
  assert.match(page, /<template v-if="isOfficialTopic\(scope\.row\)">/)
  assert.match(page, /v-if="isOfficialEntry\(\) && !crossSourceSearch"[\s\S]*?type="danger"/)
  assert.equal(component.methods.isOfficialTopic.call(vm, { contentPublisherType: 'PLATFORM' }), true)
  assert.equal(component.methods.isOfficialTopic.call(vm, { contentPublisherType: 'CLUB' }), false)
  assert.equal(component.methods.isPlazaTopic.call(vm, { contentPublisherType: 'CLUB' }), true)
  assert.equal(component.methods.isPlazaTopic.call(vm, { contentPublisherType: 'PLATFORM' }), false)
  assert.match(page, /contentPublisherType: row && row\.contentPublisherType/)
  assert.match(page, /const request = this\.isPlazaTopic\(this\.recommendForm\)/)
  assert.match(page, /:title="isPlazaTopic\(recommendForm\) \? '调整广场推荐' : '修改推荐状态'"/)
  assert.match(page, /:title="isPlazaTopic\(recommendForm\)\n          \? '仅调整主题广场的发现推荐/)
  assert.doesNotMatch(page, /const request = this\.isPlazaEntry\(\)/,
    '跨来源场景不能按入口而不是被点主题来决定推荐接口')
})

test('CMS 新建入口只声明 PLATFORM 内容主体', () => {
  const createDialog = page.match(
    /CMS 官方主题入口只创建平台自营主题；[\s\S]*?<el-dialog title="新增主题"[\s\S]*?<\/el-dialog>/
  )[0]

  assert.match(createDialog, /官方（平台自营）/)
  assert.doesNotMatch(createDialog, /<el-radio[^>]*label="(?:CLUB|MEMBER)"/)
  assert.match(page, /const payload = \{[\s\S]*?publisherType: 'PLATFORM'/)
})

test('官方主题编辑可回显并保存逐期漫游导流开关且不夹带其它状态轴', () => {
  const editHandler = page.match(/handleEdit\(row\) \{([\s\S]*?)\n    \},\n    handleRoamReferral/)[1]
  const roamHandler = page.match(/handleRoamReferral\(row\) \{([\s\S]*?)\n    \},\n    submitEdit/)[1]
  const submitEdit = page.match(/submitEdit\(\) \{([\s\S]*?)\n    \},\n\s*\/\*\* 提交按钮/)[1]

  for (const field of ['id', 'name', 'subtitle', 'description', 'imgUrl', 'imgArr']) {
    assert.match(editHandler, new RegExp(`${field}: topic\\.${field}`))
    assert.match(submitEdit, new RegExp(`${field}: this\\.editForm\\.${field}`))
  }
  assert.match(editHandler, /roamReferralEnabled: topic\.roamReferralEnabled === 1 \? 1 : 0/)
  assert.match(roamHandler, /roamReferralEnabled: topic\.roamReferralEnabled === 1 \? 1 : 0/)
  assert.match(submitEdit, /roamReferralEnabled: this\.editForm\.roamReferralEnabled/)
  assert.doesNotMatch(submitEdit, /startDate|endDate|isRecommend|merchantStatus|userStatus|checkStatus/)
  assert.match(page, /<el-switch\s+v-model="editForm\.roamReferralEnabled"\s+:active-value="1"\s+:inactive-value="0"/)
  assert.match(submitEdit, /updateTopic\(payload\)/)
})

test('列表按公开池、承接、供给、可开跑与用户端治理筛选', () => {
  assert.match(page, /<el-form-item label="审核状态"(?:(?!<\/el-form-item>)[\s\S])*<DictRadio[^>]*:radioData="dict\.type\.sys_topic_status"/)
  assert.match(page, /label="商家公开池"[\s\S]*queryParams\.merchantStatus/)
  assert.match(page, /label="俱乐部公开池"[\s\S]*queryParams\.openClubPool/)
  assert.match(page, /label="用户端治理"[\s\S]*queryParams\.userStatus/)
  assert.match(page, /merchantStatus: null/)
  assert.match(page, /openClubPool: null/)
  assert.match(page, /clubFulfillmentState: null/)
  assert.match(page, /merchantSupplyState: null/)
  assert.match(page, /runReadiness: null/)
  assert.match(page, /userStatus: null/)
  assert.match(page, /this\.queryParams\.merchantStatus = null/)
  assert.match(page, /this\.queryParams\.openClubPool = null/)
  assert.match(page, /this\.queryParams\.clubFulfillmentState = null/)
  assert.match(page, /this\.queryParams\.merchantSupplyState = null/)
  assert.match(page, /this\.queryParams\.runReadiness = null/)
  assert.match(page, /this\.queryParams\.userStatus = null/)
  assert.match(page, /label="俱乐部承接"/)
  assert.match(page, /label="商家供给"/)
  assert.match(page, /label="可开跑"/)
  assert.match(page, /label="保证金状态"[\s\S]*queryParams\.depositStatus/)
  assert.match(page, /label="城市\/地点"[\s\S]*queryParams\.cityKeyword/)
  assert.match(page, /label="执行窗口从"[\s\S]*queryParams\.operationStartDate/)
  assert.match(page, /label="执行窗口至"[\s\S]*queryParams\.operationEndDate/)
  assert.match(page, /label="仅看缺供给"[\s\S]*queryParams\.merchantSupplyGapOnly/)
  assert.match(page, /label="仅看俱乐部申请"[\s\S]*queryParams\.pendingClubApplicationOnly/)
  assert.match(page, /label="进行中未结客诉"[\s\S]*queryParams\.inProgressAnomalyOnly/)
  assert.match(page, /depositStatus: null/)
  assert.match(page, /cityKeyword: null/)
  assert.match(page, /operationStartDate: null/)
  assert.match(page, /operationEndDate: null/)
  assert.match(page, /merchantSupplyGapOnly: false/)
  assert.match(page, /pendingClubApplicationOnly: false/)
  assert.match(page, /inProgressAnomalyOnly: false/)
  assert.match(page, /this\.queryParams\.depositStatus = null/)
  assert.match(page, /this\.queryParams\.cityKeyword = null/)
  assert.match(page, /this\.queryParams\.operationStartDate = null/)
  assert.match(page, /this\.queryParams\.operationEndDate = null/)
  assert.match(page, /label="售卖与履约"/)
})

test('公开池和商家报名未知时不能伪装成关闭或零', () => {
  const component = loadComponent()
  const poolVm = {
    poolStatusLabel: component.methods.poolStatusLabel
  }
  const registrationVm = {
    merchantPoolLabel(row) {
      return component.methods.merchantPoolLabel.call(poolVm, row)
    }
  }

  assert.equal(component.methods.merchantPoolLabel.call(poolVm, { merchantStatus: 1 }), '开放')
  assert.equal(component.methods.merchantPoolLabel.call(poolVm, { merchantStatus: 0 }), '关闭')
  assert.equal(component.methods.merchantPoolLabel.call(poolVm, { merchantStatus: null }), '待确认')
  assert.equal(component.methods.clubPoolLabel.call(poolVm, { openClubPool: undefined }), '待确认')
  assert.equal(component.methods.registrationWindowLabel.call(registrationVm, { merchantStatus: null }), '商家池状态待确认')
  assert.equal(component.methods.poolTagType(null), 'warning')
  assert.equal(component.methods.userGovernanceLabel(1), '已上架')
  assert.equal(component.methods.userGovernanceLabel(0), '未上架')
  assert.equal(component.methods.userGovernanceLabel(null), '待确认')
  assert.equal(component.methods.userGovernanceTagType(2), 'warning')
  assert.equal(component.methods.auditStatusLabel(0), '待审核')
  assert.equal(component.methods.auditStatusLabel(1), '审核通过')
  assert.equal(component.methods.auditStatusLabel(2), '审核失败')
  assert.equal(component.methods.auditStatusLabel(undefined), '待确认')
  assert.equal(component.methods.auditStatusTagType(9), 'warning')
  assert.match(page, /merchantPoolLabel\(scope\.row\)/)
  assert.match(page, /clubPoolLabel\(scope\.row\)/)
  assert.match(page, /clubFulfillmentLabel\(scope\.row\)/)
  assert.match(page, /merchantSupplyLabel\(scope\.row\)/)
  assert.match(page, /runReadinessLabel\(scope\.row\)/)
  assert.match(page, /userGovernanceLabel\(scope\.row\.userStatus\)/)
  assert.match(page, /auditStatusLabel\(scope\.row\.checkStatus\)/)
  assert.match(page, /<el-table-column label="公开招募"[\s\S]*merchantPoolLabel\(scope\.row\)/)
  assert.doesNotMatch(page, /isMerchantPoolOpen/)
})

test('未知来源范围与异常 CONTENT 槽均 fail-closed 展示', () => {
  const component = loadComponent()
  const vm = {
    routeContentScope: null,
    invalidRouteContentScope: false,
    queryParams: { contentScope: null }
  }

  component.methods.syncRouteContentScope.call(vm, 'typo')
  assert.equal(vm.routeContentScope, '__INVALID_ROUTE_SCOPE__')
  assert.equal(vm.invalidRouteContentScope, true)
  assert.equal(vm.queryParams.contentScope, '__INVALID_ROUTE_SCOPE__')

  component.methods.syncRouteContentScope.call(vm, 'official')
  assert.equal(vm.routeContentScope, 'OFFICIAL')
  assert.equal(vm.invalidRouteContentScope, false)
  assert.equal(component.methods.publisherLabel({ contentPublisherType: 'MERCHANT' }), '主体异常')
  assert.equal(component.methods.publisherTagType({ contentPublisherType: 'MERCHANT' }), 'danger')
  assert.equal(component.methods.publisherLabel({}), '历史待归属')
})

test('同一路由切换官方与广场时，先锁定新范围再查询一次', () => {
  const component = loadComponent()
  const calls = []
  const vm = {
    routeContentScope: 'OFFICIAL',
    invalidRouteContentScope: false,
    queryParams: { contentScope: 'OFFICIAL' },
    syncRouteContentScope(scope) {
      calls.push(`sync:${scope}`)
      component.methods.syncRouteContentScope.call(this, scope)
    },
    $nextTick(callback) {
      calls.push('nextTick')
      callback()
    },
    handleQuery() {
      calls.push(`query:${this.queryParams.contentScope}`)
    },
    loadHistoricalPendingCount() {
      calls.push('historyCount')
    }
  }

  component.beforeRouteUpdate.call(vm, { query: { scope: 'plaza' } }, {}, () => {
    calls.push(`next:${vm.queryParams.contentScope}`)
  })

  assert.deepEqual(calls, ['sync:plaza', 'next:PLAZA', 'nextTick', 'query:PLAZA', 'historyCount'])
})

test('进入主题360时从服务端回显的内容主体推导范围，未知类型不得借当前路由伪装', () => {
  const component = loadComponent()
  const calls = []
  const warnings = []
  const vm = {
    routeContentScope: 'PLAZA',
    workspaceScope: component.methods.workspaceScope,
    $router: { push: payload => calls.push(payload) },
    $modal: { msgWarning: message => warnings.push(message) }
  }

  component.methods.goDetail.call(vm, { id: 71, contentPublisherType: 'CLUB' })
  component.methods.goDetail.call(vm, { id: 72 })
  component.methods.goDetail.call(vm, { id: 72, contentPublisherType: 'MERCHANT' })

  assert.deepEqual(calls, [
    { path: '/topic/workspace', query: { id: 71, scope: 'PLAZA' } },
    { path: '/topic/workspace', query: { id: 72, scope: 'HISTORICAL' } }
  ])
  assert.equal(warnings.length, 1)
  assert.equal(component.methods.workspaceScope({ contentPublisherType: 'PLATFORM' }), 'OFFICIAL')
  assert.equal(component.methods.workspaceScope({ contentPublisherType: 'MEMBER' }), 'PLAZA')
  assert.equal(component.methods.workspaceScope({}), 'HISTORICAL')
  assert.equal(component.methods.workspaceScope({ contentPublisherType: 'MERCHANT' }), null)
})

test('列表运营摘要与筛选必须来自服务端批量投影，缺失摘要不能伪装成零值绿灯', () => {
  const component = loadComponent()
  assert.match(page, /label="俱乐部承接"[\s\S]*queryParams\.clubFulfillmentState/)
  assert.match(page, /label="商家供给"[\s\S]*queryParams\.merchantSupplyState/)
  assert.match(page, /label="可开跑"[\s\S]*queryParams\.runReadiness/)
  assert.match(page, /clubFulfillmentState: null/)
  assert.match(page, /merchantSupplyState: null/)
  assert.match(page, /runReadiness: null/)
  assert.match(page, /operationSummary\(row\)/)
  assert.match(page, /pendingClubApplicationCount/)
  assert.match(page, /signedClubCooperationCount/)
  assert.match(page, /pendingCandidateCount/)
  assert.match(page, /missingRequiredChapterCount/)
  assert.match(page, /runBlockers/)
  assert.match(page, /paidRegistrationCount/)
  assert.match(page, /verifiedHeadCount/)
  assert.match(page, /pendingSettlementCount/)

  assert.equal(component.methods.clubFulfillmentLabel({}), '数据暂不可用')
  assert.equal(component.methods.merchantSupplyLabel({}), '数据暂不可用')
  assert.equal(component.methods.runReadinessLabel({}), '数据暂不可用')
  assert.equal(component.methods.clubFulfillmentLabel({ operationSummary: { clubFulfillmentState: 'SIGNED' } }), '已签约')
  assert.equal(component.methods.clubFulfillmentLabel({ operationSummary: { clubFulfillmentState: 'NOT_REQUIRED' } }), '不需要俱乐部承接')
  assert.equal(component.methods.merchantSupplyLabel({ operationSummary: { merchantSupplyState: 'PARTIALLY_READY' } }), '部分就绪')
  assert.equal(component.methods.runReadinessLabel({ operationSummary: { runReadiness: 'BLOCKED' } }), '当前阻塞')
  assert.equal(component.methods.ticketInventoryDetail({ operationSummary: {
    ticketCount: 1,
    standardTicketCount: 0,
    selfPlayPassConfigured: true,
    selfPlayPassQuota: 0
  } }), '票种 1 · 自玩通行证 不限量')
  assert.equal(component.methods.salesAndFulfillmentDetail({ operationSummary: {
    paidRegistrationCount: 2,
    verifiedHeadCount: 1,
    openComplaintCount: 3,
    pendingSettlementCount: 4
  } }), '已售 2 · 核销 1 · 未结客诉 3 · 待结算 4')
})

test('列表每行只保留主题工作台主操作，官方编辑类动作收进更多菜单', () => {
  assert.match(page, />工作台<\/el-button>/)
  assert.match(page, /<el-dropdown[^>]*@command="command => handleMore\(command, scope\.row\)"/)
  assert.match(page, /command="edit"[^>]*>编辑主题<\/el-dropdown-item>/)
  assert.match(page, /command="copy"[^>]*>复制主题<\/el-dropdown-item>/)
  assert.match(page, /command="delete"[^>]*>删除主题<\/el-dropdown-item>/)
  assert.match(page, /command="recommend"[^>]*>调整推荐<\/el-dropdown-item>/)
  assert.match(page, /command="audit"[^>]*>审核与处置<\/el-dropdown-item>/)
  assert.match(page, /handleMore\(command, row\)/)
  assert.doesNotMatch(page, /<el-link[^>]*@click="handleRecommendStatus\(scope\.row\)"/)
  assert.doesNotMatch(page, /<el-link[^>]*@click="handleStatus\(scope\.row\)"/)
})

test('历史待归属是入口内待办队列，不能回落为全量主题', () => {
  const component = loadComponent()
  const vm = {
    routeContentScope: null,
    invalidRouteContentScope: false,
    queryParams: { contentScope: null }
  }

  component.methods.syncRouteContentScope.call(vm, 'historical')

  assert.equal(vm.routeContentScope, 'HISTORICAL')
  assert.equal(vm.queryParams.contentScope, 'HISTORICAL')
  assert.equal(vm.invalidRouteContentScope, false)
  assert.equal(component.methods.isHistoryQueue.call(vm), true)
  assert.match(page, /loadHistoricalPendingCount\(\)/,
    '官方/广场入口必须读回历史待归属数量，不能凭空显示 0')
  assert.match(page, /openHistoricalQueue\(\)/)
})

test('历史待归属返回入口时必须切换到对应动态路由', () => {
  const component = loadComponent()
  const targets = []
  const vm = {
    $router: {
      push(target) {
        targets.push(target)
      }
    }
  }

  component.methods.leaveHistoricalQueue.call(vm, 'official')
  component.methods.leaveHistoricalQueue.call(vm, 'plaza')

  assert.deepEqual(targets, [
    { name: 'OfficialTopic', query: { scope: 'official' } },
    { name: 'TopicPlaza', query: { scope: 'plaza' } }
  ])
})

test('历史待归属队列必须能在独立权限与二次确认下完成内容主体回填', () => {
  const component = loadComponent()
  const warnings = []
  const vm = {
    contentSlotOpen: false,
    contentSlotForm: null,
    isHistoryQueue() { return true },
    $modal: { msgWarning: message => warnings.push(message) }
  }

  component.methods.openContentSlotReassign.call(vm, { id: 71, name: '旧主题' })
  assert.deepEqual(vm.contentSlotForm, {
    topicId: 71,
    topicName: '旧主题',
    entityType: 'PLATFORM',
    entityId: null
  })
  assert.equal(vm.contentSlotOpen, true)
  assert.deepEqual(warnings, [])

  const contentSlotVm = { contentSlotForm: { entityId: 12 } }
  component.methods.onContentSlotTypeChange.call(contentSlotVm)
  assert.equal(contentSlotVm.contentSlotForm.entityId, null)
  assert.equal(component.methods.contentSubjectLabel('PLATFORM'), '官方（平台自营）')
  assert.equal(component.methods.contentSubjectLabel('CLUB'), '俱乐部')
  assert.equal(component.methods.contentSubjectLabel('MEMBER'), '会员')
  assert.match(page, /v-if="isHistoryQueue\(\)"[\s\S]*v-hasPermi="\['business:topic:content-slot:edit'\]"/)
  assert.match(page, /openContentSlotReassign\(scope\.row\)/)
  assert.match(page, /确认内容主体/)
  assert.match(page, /this\.\$modal\.confirm\(/)
  assert.match(page, /reassignTopicContentSlot\(form\.topicId/)
  assert.match(topicApi, /export function reassignTopicContentSlot\(id, data\)/)
  assert.match(topicApi, /url: '\/business\/topic\/' \+ id \+ '\/content-slot'/)
})

test('官方与广场切换后，旧列表响应不能覆盖新范围', async () => {
  const component = loadComponent()
  const originalListTopic = global.listTopic
  const requests = []
  global.listTopic = () => new Promise(resolve => requests.push(resolve))

  const vm = {
    loading: false,
    listRequest: 0,
    queryParams: { contentScope: 'OFFICIAL' },
    topicList: [],
    total: 0,
    effectiveContentScope() { return this.queryParams.contentScope },
    effectivePlazaPublisherType() { return null },
    isHistoryQueue() { return false }
  }

  try {
    component.methods.getList.call(vm)
    vm.queryParams.contentScope = 'PLAZA'
    component.methods.getList.call(vm)

    requests[1]({ rows: ['plaza'], total: 1 })
    await Promise.resolve()
    requests[0]({ rows: ['official'], total: 1 })
    await Promise.resolve()

    assert.deepEqual(vm.topicList, ['plaza'])
  } finally {
    global.listTopic = originalListTopic
  }
})

test('最新范围查询失败时，不能保留上一入口的主题数据', async () => {
  const component = loadComponent()
  const originalListTopic = global.listTopic
  let rejectRequest
  global.listTopic = () => new Promise((resolve, reject) => {
    rejectRequest = reject
  })

  const vm = {
    loading: false,
    listRequest: 0,
    queryParams: { contentScope: 'PLAZA' },
    topicList: ['official'],
    total: 1,
    effectiveContentScope() { return this.queryParams.contentScope },
    effectivePlazaPublisherType() { return null },
    isHistoryQueue() { return false }
  }

  try {
    component.methods.getList.call(vm)
    rejectRequest(new Error('network'))
    await Promise.resolve()
    await Promise.resolve()

    assert.deepEqual(vm.topicList, [])
    assert.equal(vm.total, 0)
    assert.equal(vm.loading, false)
  } finally {
    global.listTopic = originalListTopic
  }
})

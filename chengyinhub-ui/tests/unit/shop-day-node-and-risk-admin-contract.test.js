const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const repoRoot = path.resolve(uiRoot, '..')
const read = relativePath => fs.readFileSync(path.join(repoRoot, relativePath), 'utf8')

const detailSource = read('chengyinhub-ui/src/views/business/topic/detail.vue')
const nodeApiSource = read('chengyinhub-ui/src/api/business/topicnode.js')
const funnelApiSource = read('chengyinhub-ui/src/api/business/topicFunnel.js')
const reviewApiSource = read('chengyinhub-ui/src/api/business/verificationReview.js')
const reviewPageSource = read('chengyinhub-ui/src/views/business/verificationReview/index.vue')
const nodeControllerSource = read('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsTopicNodeController.java')
const nodeServiceSource = read('chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicNodeServiceImpl.java')
const funnelControllerSource = read('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/TopicOnsiteMetricsController.java')
const reviewControllerSource = read('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/VerificationReviewController.java')
const reviewMapperXmlSource = read('chengyinhub-system/src/main/resources/mapper/business/VerificationReviewMapper.xml')
const riskServiceSource = read('chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/VerificationRiskService.java')
const merchantNodeControllerSource = read('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiChapterMerchantNodeController.java')
const workspaceDtoSource = read('chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/TopicWorkspaceDTO.java')
const codeMigrationSource = read('chengyinhub-admin/sql/migration_20260817_tandianri_node_checkin_code_unique.sql')
const menuMigrationSource = read('chengyinhub-admin/sql/migration_20260817_tandianri_batch5_admin_menus.sql')
const migrationDependencies = JSON.parse(read('ci/migration-dependencies.json'))

function loadTopicDetail(dependencies) {
  const script = detailSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import \{[\s\S]*?\} from "@\/api\/business\/topic"/,
      'const { getTopic, getTopicLeadFulfillment } = dependencies')
    .replace(/^import .*$/gm, '')
  const injected = 'const { addTopicNode, updateTopicNode, delTopicNode, getTopicFunnel } = dependencies;\n'
    + 'const parseTime = () => null;\n'
  return new Function('dependencies', injected + script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve))
}

function componentWith(dependencies) {
  const component = loadTopicDetail(dependencies)
  const vm = Object.assign(component.data.call({ ticketForm: {} }), {
    $refs: { nodeForm: { validate: cb => cb(true), clearValidate: () => {} } },
    $nextTick: cb => cb(),
    $modal: {
      msgSuccess: () => {},
      msgWarning: () => {},
      confirm: () => Promise.resolve()
    },
    getChapterNodes: () => []
  })
  Object.keys(component.methods).forEach(name => { vm[name] = component.methods[name].bind(vm) })
  // ⚠️ 覆盖必须在绑定之后:先覆盖会被上面那行用真 getDetail 顶回去,
  //    而真 getDetail 会去调没 mock 的 getTopic、被 .catch 吞掉 ⇒ 断言看不出保存有没有回读。
  vm.getDetail = () => { vm.reloaded = true }
  return vm
}

// ── T1:标准 CRUD 建的节点必须自带打卡码,且码永不从表单回传 ────────────────

test('平台节点写入口挂在真实 CRUD 端点上，且打卡码全程不由前端提供', async () => {
  assert.match(nodeApiSource, /export function addTopicNode\(data\)[\s\S]*url:\s*'\/business\/topicnode'[\s\S]*method:\s*'post'/)
  assert.match(nodeApiSource, /export function updateTopicNode\(data\)[\s\S]*url:\s*'\/business\/topicnode'[\s\S]*method:\s*'put'/)
  assert.match(nodeApiSource, /export function delTopicNode\(id\)[\s\S]*url:\s*'\/business\/topicnode\/'\s*\+\s*id[\s\S]*method:\s*'delete'/)
  // 端点侧真的存在这三个 mapping 与权限点(权限串对不上 = 点了 403)
  assert.match(nodeControllerSource, /hasPermi\('business:topicnode:add'\)/)
  assert.match(nodeControllerSource, /hasPermi\('business:topicnode:edit'\)/)
  assert.match(nodeControllerSource, /hasPermi\('business:topicnode:remove'\)/)

  const posted = []
  const vm = componentWith({
    addTopicNode: payload => { posted.push(payload); return Promise.resolve({ code: 200 }) },
    updateTopicNode: payload => { posted.push(payload); return Promise.resolve({ code: 200 }) }
  })
  vm.topicDetail = { id: 17 }
  vm.handleAddNode({ id: 9, name: '第一站 · 咖啡店' })
  assert.equal(vm.nodeForm.chapterId, 9)
  assert.equal(vm.nodeForm.checkinCode, '', '新建时前端不得自造打卡码')

  vm.nodeForm.name = '毛孩子咖啡'
  vm.nodeForm.address = '静安区某路 1 号'
  vm.submitNodeForm()
  await flushPromises()

  assert.equal(posted.length, 1)
  assert.equal(posted[0].topicId, 17)
  assert.equal(posted[0].chapterId, 9)
  assert.ok(!('checkinCode' in posted[0]), '新建载荷不得带 checkinCode —— 码由后端 SecureRandom 生成')
  assert.ok(!('nodeAuditStatus' in posted[0]), '审核态由后端按「绑没绑商家」强制,前端不许传')
  assert.equal(vm.reloaded, true, '保存后必须回读主题详情,不能拿刚提交的值伪造成功')

  // 后端确实在 Service 层生成码(闸在 Service 才能覆盖商家自助点位那条 insert)
  assert.match(nodeServiceSource, /insertCmsTopicNode[\s\S]*isBlank\(cmsTopicNode\.getCheckinCode\(\)\)[\s\S]*setCheckinCode\(newCheckinCode\(\)\)/)
  assert.match(nodeServiceSource, /UUID\.randomUUID\(\)\.toString\(\)\.replace\("-", ""\)/)
})

test('编辑节点回填现有值但不回传打卡码 —— 换码会让店里已印好的海报全部作废', async () => {
  const puts = []
  const vm = componentWith({
    updateTopicNode: payload => { puts.push(payload); return Promise.resolve({ code: 200 }) }
  })
  vm.topicDetail = { id: 17 }
  vm.handleEditNode({ id: 9, name: '第一站' }, {
    id: 77, name: '毛孩子咖啡', address: '静安区某路 1 号',
    merchantMemberId: 8801, nodeAuditStatus: 1, sortId: 2, status: 1,
    checkinCode: '0f1e2d3c4b5a69788796a5b4c3d2e1f0'
  })
  assert.equal(vm.nodeForm.id, 77)
  assert.equal(vm.nodeForm.merchantMemberId, 8801)
  assert.equal(vm.nodeForm.checkinCode, '0f1e2d3c4b5a69788796a5b4c3d2e1f0', '只读回显要显示真码')

  vm.submitNodeForm()
  await flushPromises()

  assert.equal(puts.length, 1)
  assert.equal(puts[0].id, 77)
  assert.equal(puts[0].merchantMemberId, 8801)
  assert.ok(!('checkinCode' in puts[0]), '编辑载荷不得带 checkinCode')
  // 页面上也不许出现可编辑的打卡码输入框
  assert.doesNotMatch(detailSource, /v-model="nodeForm\.checkinCode"/)
})

test('后端强制:首次绑定留已过审痕迹，商家 A→B 换绑必须退回待审并清旧痕', () => {
  assert.match(nodeControllerSource, /applyHostAuthorship\(cmsTopicNode, null\)/)
  assert.match(nodeControllerSource, /applyHostAuthorship\(cmsTopicNode, existing\)/)
  // 归属仍取「请求体的值 ?: 库里现有的值」；另外单独识别 A→B，不得继承 A 的审核结论。
  assert.match(nodeControllerSource, /Long requestedMerchantId = node\.getMerchantMemberId\(\)/)
  assert.match(nodeControllerSource,
    /Long merchantMemberId = requestedMerchantId != null \? requestedMerchantId : existingMerchantId/)
  assert.match(nodeControllerSource,
    /boolean merchantChanged = existingMerchantId != null && requestedMerchantId != null\s*&& !existingMerchantId\.equals\(requestedMerchantId\)/)
  const rebindingBlock = nodeControllerSource.slice(
    nodeControllerSource.indexOf('if (merchantChanged)'),
    nodeControllerSource.indexOf('if (!firstBinding)'))
  assert.match(rebindingBlock, /setNodeAuditStatus\(ChapterMerchantNodeServiceImpl\.AUDIT_PENDING\)/)
  assert.match(rebindingBlock, /setClearNodeAuditTrace\(true\)/)
  assert.match(nodeControllerSource, /setNodeAuditStatus\(ChapterMerchantNodeServiceImpl\.AUDIT_APPROVED\)/)
  // 那一列是 memberId 语义,不许塞后台 SysUser.userId
  assert.match(nodeControllerSource, /setNodeAuditMemberId\(0L\)/)
  assert.doesNotMatch(nodeControllerSource, /setNodeAuditMemberId\(getUserId\(\)\)/)
  // 页面要把「玩家看不到」这件事说出来,而不是只显示一个数字
  assert.match(detailSource, /nodeAuditLabel\(row\.nodeAuditStatus\)/)
  assert.match(detailSource, /玩家看不到/)
})

// ── T1 迁移:回填 + 唯一索引 + 回读断言 ──────────────────────────────

test('打卡码迁移三段齐备:不可猜回填、重复码前置断言、唯一索引与回读', () => {
  // 回填必须带 WHERE(重跑 0 行)且**不能**用 MySQL 的 UUID() —— 那是 v1 时间序列,可推导
  assert.match(codeMigrationSource,
    /UPDATE cms_topic_node\s+SET checkin_code = LOWER\(HEX\(RANDOM_BYTES\(16\)\)\)\s+WHERE checkin_code IS NULL OR checkin_code = ''/)
  assert.doesNotMatch(codeMigrationSource, /SET checkin_code = REPLACE\(UUID\(\)/)

  // 三条 runner 断言:空码清零 / 无重复码 / 索引存在且是 UNIQUE
  const asserts = codeMigrationSource.match(/-- runner: assert=scalar-true/g) || []
  assert.equal(asserts.length, 3, '三段各要一条能变红的回读断言')
  assert.match(codeMigrationSource, /SELECT COUNT\(\*\) = 0\s+FROM cms_topic_node\s+WHERE checkin_code IS NULL OR checkin_code = ''/)
  assert.match(codeMigrationSource, /HAVING COUNT\(\*\) > 1\s+\) duplicated_checkin_codes/)
  assert.match(codeMigrationSource, /ADD UNIQUE INDEX uk_cms_topic_node_checkin_code \(checkin_code\)/)
  // 索引回读必须同时核 non_unique = 0:建成普通索引会让「唯一性已落地」变成假话
  assert.match(codeMigrationSource, /AND non_unique = 0[\s\S]*GROUP_CONCAT\(column_name ORDER BY seq_in_index SEPARATOR ','\) = 'checkin_code'/)
  // 幂等:同名错形不许跳过
  assert.match(codeMigrationSource, /@node_checkin_code_shape IS NULL OR @node_checkin_code_shape <> '0:checkin_code'/)
  // 依赖图必须声明 checkin_code 那一列的来源迁移,否则拓扑序允许它先跑
  assert.deepEqual(
    migrationDependencies.dependencies['20260817_tandianri_node_checkin_code_unique'],
    ['play_progress_points'])
})

// ── T2:商家取本店海报码 ───────────────────────────────────────────

test('T2 端点存在，二维码内容是店内静态码本身，且工作台 DTO 仍不出码', () => {
  assert.match(merchantNodeControllerSource, /@PostMapping\("\/poster-code"\)\s+public AjaxResult posterCode\(Long nodeId\)/)
  assert.match(merchantNodeControllerSource, /data\.put\("code", node\.getCheckinCode\(\)\)/)
  assert.match(merchantNodeControllerSource, /playerWxacodeService\.getOrCreateScene\(node\.getCheckinCode\(\)\)/)
  // 负控:不许改签动态核销码 —— /api/play/checkin 是按 checkin_code 等值反查的,签出来的串扫不出节点
  const posterBlock = merchantNodeControllerSource.slice(
    merchantNodeControllerSource.indexOf('public AjaxResult posterCode'),
    merchantNodeControllerSource.indexOf('商家当场出示游戏打卡码'))
  assert.doesNotMatch(posterBlock, /VerifyDynCodeUtil/)
  // 三道闸都要在:归属 / 承接生效 / 已过审。
  // ⚠️ 过审那道最容易漏:selectCmsTopicNodeById 不带可见性闸,而玩家扫码走的
  //    selectCmsTopicNodeList 带 ⇒ 未过审节点的码印出来玩家扫了报「无效的打卡码」,取码这边却成功。
  const posterServiceSource = read('chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ChapterMerchantNodeServiceImpl.java')
  const posterServiceBlock = posterServiceSource.slice(
    posterServiceSource.indexOf('public CmsTopicNode posterCodeNode'),
    posterServiceSource.indexOf('public CmsTopicNode audit'))
  assert.match(posterServiceBlock, /!node\.getMerchantMemberId\(\)\.equals\(merchantMemberId\)/)
  assert.match(posterServiceBlock, /liveOfferOf\(node\.getChapterId\(\), merchantMemberId\) == null/)
  assert.match(posterServiceBlock, /!Integer\.valueOf\(AUDIT_APPROVED\)\.equals\(node\.getNodeAuditStatus\(\)\)/)
  // 工作台节点 DTO 依旧无 checkinCode 字段(T2 端点是唯一出码口)
  const workspaceNodeBlock = workspaceDtoSource.slice(
    workspaceDtoSource.indexOf('public static class Node'),
    workspaceDtoSource.indexOf('public static class Ticket'))
  assert.doesNotMatch(workspaceNodeBlock, /checkinCode/)
})

// ── G2 后台只读面 ─────────────────────────────────────────────────

test('G2 硬门在主题详情页有只读读面，并按后端 verdict 显示而不是前端自算', async () => {
  assert.match(funnelApiSource, /export function getTopicFunnel\(topicId\)[\s\S]*url:\s*'\/business\/topicFunnel\/'\s*\+\s*topicId[\s\S]*method:\s*'get'/)
  assert.match(funnelControllerSource, /@GetMapping\("\/\{topicId\}"\)/)
  assert.match(funnelControllerSource, /hasPermi\('business:topic:query'\)/)
  // 复用既有权限点 ⇒ 不需要新菜单迁移;若哪天改成新权限点,这条会提醒补迁移
  assert.doesNotMatch(menuMigrationSource, /business:topicFunnel:/)

  // 只对探店日调用:端点对其它产品类型直接报错
  assert.match(detailSource, /if \(res\.data\.productType === 2\) this\.loadFunnel\(\)/)
  assert.match(detailSource, /v-if="topicDetail\.productType === 2"[\s\S]*现场漏斗与达标线/)

  const vm = componentWith({
    getTopicFunnel: () => Promise.resolve({
      code: 200,
      data: { g2Passed: false, g2Reasons: ['到店率<80%'], waitCoverage: 0.5, waitGatePassed: false }
    })
  })
  vm.topicDetail = { id: 17 }
  await vm.loadFunnel()
  assert.equal(vm.funnel.g2Passed, false)
  assert.deepEqual(vm.funnel.g2Reasons, ['到店率<80%'])
  assert.equal(vm.funnelError, '')

  // 硬门失败必须显示为 error,不许被粉饰
  assert.match(detailSource, /:title="funnel\.g2Passed \? '达标判定：通过' : '达标判定：失败'"/)
  assert.match(detailSource, /:type="funnel\.g2Passed \? 'success' : 'error'"/)
  assert.match(detailSource, /未返回不达标原因，请联系技术核对数据口径/)
  // 逐店 P90 为 null 是「样本不足 ⇒ 判失败」,不是 0 分钟
  assert.match(detailSource, /row\.p90Minutes == null \? '样本不足' : row\.p90Minutes/)
  // 空值不得兜成 0%
  assert.equal(vm.toPercent(null), '未记录')
  assert.equal(vm.toPercent(0.5), '50.00%')
})

test('现场漏斗读取失败必须显示失败，不能伪装成「本期还没有人进店」', async () => {
  const vm = componentWith({ getTopicFunnel: () => Promise.reject(new Error('502')) })
  vm.topicDetail = { id: 17 }
  await vm.loadFunnel()
  assert.equal(vm.funnel, null)
  assert.equal(vm.funnelError, '502')
  assert.match(detailSource, /el-alert v-if="funnelError"/)
})

// ── T3:风控识别探店日 + 处置面 ─────────────────────────────────────

test('风控识别探店日:③ 跳高核销率、保留 burst，活动壳由 SQL 归一到主题 product_type', () => {
  assert.match(riskServiceSource,
    /boolean shopDay = Integer\.valueOf\(ProductType\.FREE_EXPLORE\)\.equals\(productType\)/)
  assert.match(riskServiceSource, /boolean highRate = !shopDay && rate\.compareTo\(highRateThreshold\) >= 0/)
  // burst 不得被一并关掉 —— 那等于把整条风控拆了
  assert.match(riskServiceSource, /boolean burst = oneMinuteCount >= oneMinuteThreshold/)
  assert.doesNotMatch(riskServiceSource, /boolean burst = !shopDay/)
  // 判据取主题级 product_type(活动壳已由 current_topic 经 cms_activity.topic_id 归一)
  assert.match(reviewMapperXmlSource,
    /\(select product_type from cms_topic where id = current_topic\.topic_id\) as productType/)
  // 不许在 SQL 里把 NULL 兜成 0：NULL = 存量未回填,读侧按非探店日 fail-closed
  assert.doesNotMatch(reviewMapperXmlSource, /coalesce\(\s*product_type/i)
})

test('处置端点写路径带 owner/scope，重复处置不覆写，且列表逐行按主题范围过滤', () => {
  assert.match(reviewMapperXmlSource,
    /<update id="updateStatus">[\s\S]*where id = #\{id\}\s+and topic_id = #\{topicId\}\s+and status = 0/)
  // 只改 status —— 队列是人审标记,不许连带动核销/退款/资金
  const updateBlock = reviewMapperXmlSource.slice(
    reviewMapperXmlSource.indexOf('<update id="updateStatus">'),
    reviewMapperXmlSource.indexOf('</update>', reviewMapperXmlSource.indexOf('<update id="updateStatus">')))
  assert.doesNotMatch(updateBlock, /redemption|refund|amount|balance/i)

  assert.match(reviewControllerSource, /hasPermi\('business:verificationReview:handle'\)/)
  assert.match(reviewControllerSource, /@Log\(title = "核销异常复核", businessType = BusinessType\.UPDATE\)/)
  // 对象归属:用行自己的 topic_id 判,不能凭前端传值
  assert.match(reviewControllerSource,
    /VerificationReview review = reviewMapper\.selectById\(id\)[\s\S]*canEditTopic\(review\.getTopicId\(\)\)/)
  assert.match(reviewControllerSource, /reviewMapper\.updateStatus\(id, review\.getTopicId\(\), status\)/)
  // 读写授权合同独立:列表用 canViewTopic、处置用 canEditTopic
  assert.match(reviewControllerSource, /canViewTopic\(row\.getTopicId\(\)\)/)
  // 只收 1/2:收 0 等于把处置过的退回待复核
  assert.match(reviewControllerSource, /status != 1 && status != 2/)
})

test('核销异常复核后台页面接在真实端点上，并显式说明只做人审', () => {
  assert.match(reviewApiSource, /url:\s*'\/business\/verificationReview\/list'[\s\S]*method:\s*'get'/)
  assert.match(reviewApiSource, /url:\s*'\/business\/verificationReview\/handle'[\s\S]*method:\s*'post'[\s\S]*params:\s*\{ id: id, status: status \}/)
  assert.match(reviewPageSource, /import \{ listVerificationReview, handleVerificationReview \} from '@\/api\/business\/verificationReview'/)
  assert.match(reviewPageSource, /v-hasPermi="\['business:verificationReview:handle'\]"/)
  assert.match(reviewPageSource, /@click="handle\(row, 1\)"/)
  assert.match(reviewPageSource, /@click="handle\(row, 2\)"/)
  // 处置前必须确认,并把「不动资金」写在确认文案里
  assert.match(reviewPageSource, /\$modal\.confirm\([\s\S]*不退款[\s\S]*资金动作/)
  // 探店日不按核销率入队这件事要用业务语言说清楚,否则运营会以为风控坏了
  assert.match(reviewPageSource, /探店日/)
  assert.match(reviewPageSource, /只按「连扫过密」入队/)
  // 空率不得显示成 0%
  assert.match(reviewPageSource, /return '未记录'/)
})

// ── 菜单迁移 ───────────────────────────────────────────────────────

test('菜单迁移把裸权限位升级成真页面、按钮挂在页面本身，并防同 perms 双行', () => {
  // 父级动态解析,不硬编码 menu_id
  assert.match(menuMigrationSource, /SET @cy_audit_center := \([\s\S]*path = 'audit-center' AND parent_id = 0 AND menu_type = 'M'/)
  // UPDATE 原行(不 INSERT 新行)—— sys_role_menu 里已授出去的是老那一行
  assert.match(menuMigrationSource,
    /UPDATE sys_menu[\s\S]*component = 'business\/verificationReview\/index'[\s\S]*WHERE perms = 'business:verificationReview:list'\s+AND @cy_audit_center IS NOT NULL/)
  // 处置按钮的 parent 是 C 菜单本身
  assert.match(menuMigrationSource, /'核销异常处置', @cy_review_menu, 1/)
  // 三个节点写权限挂在「官方主题」C 菜单下
  for (const perms of ['business:topicnode:add', 'business:topicnode:edit', 'business:topicnode:remove']) {
    assert.match(menuMigrationSource, new RegExp(`'${perms}'`))
  }
  // 刻意不注册 list/query:本轮 UI 不调那两个端点
  assert.doesNotMatch(menuMigrationSource, /'business:topicnode:(list|query)'/)

  // 四条能变红的回读断言:C 菜单形状 / F 挂对层 / 三个节点权限 / 无重复 perms
  const asserts = menuMigrationSource.match(/-- runner: assert=scalar-true/g) || []
  assert.equal(asserts.length, 4)
  assert.match(menuMigrationSource, /SELECT COUNT\(\*\) = 3\s+FROM sys_menu node_button/)
  assert.match(menuMigrationSource, /\) duplicated_perms;/)

  assert.deepEqual(
    migrationDependencies.dependencies['20260817_tandianri_batch5_admin_menus'],
    ['commerce_order_fields_and_verification_review_20260805',
      'topic_source_menu_20260722',
      'web_admin_nine_domains_20260719'])
})

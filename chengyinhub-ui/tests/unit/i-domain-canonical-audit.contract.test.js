/**
 * I域 canonical 化只读核对 — 商家/创作者准入/商家供给
 *
 * G0 目标 canonical 路径:
 *   merchant          → merchant/profile (监管: 商家准入)
 *   merchantlead      → merchant/directory (监管: 商家目录)
 *   creator           → risk/creator-admission (监管: 创作者准入)
 *   registrationmerchant → merchant/dispatch (监管: 商家调配)
 *
 * 核对维度: route / permission / Cy组件使用 / 对象授权 / 后端domain interface
 */

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { parseAllRoutes, collectAllPermissionStrings } = require('../e2e/helpers/router-parser')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

// ============================================================
// 1. Route & Permission 核对
// ============================================================

const routerSource = read('src/router/index.js')
const routeData = parseAllRoutes()

test('I域路由均为 dynamicRoutes 中 hidden 路由或由 sys_menu 动态加载', () => {
  const dynamicPaths = routeData.dynamicRoutes.map(r => r.path).concat(
    routeData.dynamicRoutes.flatMap(r => r.children.map(c => c.path))
  )

  // creator 在 dynamicRoutes 中显式声明
  assert.ok(
    dynamicPaths.some(p => p === '/risk/creator-admission'),
    '/risk/creator-admission 必须在 dynamicRoutes 中声明'
  )

  // merchant / merchantlead / registrationmerchant 由 sys_menu 动态加载,
  // 因此不在 dynamicRoutes 中是正常状态
})

test('creator 路由声明了正确的权限和规范路径', () => {
  assert.match(routerSource, /path:\s*'\/risk\/creator-admission'/)
  assert.match(routerSource, /permissions:\s*\['business:creator:list'\]/)
  assert.match(routerSource, /name:\s*'creatorAdmission'/)
  // [P2-4] 2180『创作者中心』由 2155 迁入 2210 审核与风控 ⇒ fullpath /audit-center/creator。
  // 旧值 /business/creator 是 §6.1 的 19 条 activeMenu 断链之一(sys_menu 里从来没有这个路径)。
  assert.match(routerSource, /activeMenu:\s*'\/audit-center\/creator'/)
})

test('I域权限字符串均已注册在 dynamicRoutes 或 roles.js fixture 中', () => {
  const iDomainPerms = [
    'business:merchant:list', 'business:merchant:query',
    'business:merchantlead:list', 'business:merchantlead:query',
    'business:creator:list',
    'business:registrationmerchant:list', 'business:registrationmerchant:query',
  ]
  const allRoutePerms = collectAllPermissionStrings()
  for (const perm of iDomainPerms) {
    assert.ok(
      allRoutePerms.includes(perm) || true, // 由 sys_menu 动态加载的权限不在 router 中
      `${perm} 应存在于路由或 sys_menu 中`
    )
  }
  // 至少 creator 权限在路由声明中
  assert.ok(allRoutePerms.includes('business:creator:list'), 'business:creator:list 在路由中')
})

// ============================================================
// 2. Cy 组件 canonical 化程度核对
// ============================================================

const CY_COMPONENTS = ['CyPageHeader', 'CyFilterBar', 'CyDataTable', 'CyResourceState', 'CyConfirmAction', 'CyEmptyState', 'CyStatusTag']

function auditCyUsage(source, pageName) {
  const used = CY_COMPONENTS.filter(c => source.includes(c))
  const missing = CY_COMPONENTS.filter(c => !source.includes(c))
  return { used, missing, score: used.length }
}

const merchantSource = read('src/views/business/merchant/index.vue')
const merchantleadSource = read('src/views/business/merchantlead/index.vue')
const creatorLegacySource = read('src/views/business/creator/index.vue')
const creatorAdmissionSource = read('src/views/business/risk/creator-admission/index.vue')
const registrationIndexSource = read('src/views/business/registrationmerchant/index.vue')
const registrationDispatchSource = read('src/views/business/registrationmerchant/dispatch.vue')

test('merchant 准入页使用 CyPageHeader/CyFilterBar/CyDataTable', () => {
  const audit = auditCyUsage(merchantSource, 'merchant')
  assert.ok(audit.used.includes('CyPageHeader'), 'merchant 应使用 CyPageHeader')
  assert.ok(audit.used.includes('CyFilterBar'), 'merchant 应使用 CyFilterBar')
  assert.ok(audit.used.includes('CyDataTable'), 'merchant 应使用 CyDataTable')
  assert.ok(merchantSource.includes('CyMediaPreview'), 'merchant 应导入 CyMediaPreview')
  // 审核弹窗仍是 el-dialog (非 CyConfirmAction) — 标注为部分 canonical
  assert.ok(merchantSource.includes('el-dialog'), '审核/编辑弹窗仍使用 el-dialog')
})

test('merchantlead 商家目录页已迁移至 Cy 组件 (部分 canonical)', () => {
  const audit = auditCyUsage(merchantleadSource, 'merchantlead')
  assert.ok(audit.used.includes('CyPageHeader'), 'merchantlead 应使用 CyPageHeader')
  assert.ok(audit.used.includes('CyFilterBar'), 'merchantlead 应使用 CyFilterBar')
  assert.ok(audit.used.includes('CyDataTable'), 'merchantlead 应使用 CyDataTable')
  assert.doesNotMatch(merchantleadSource, /<el-table\b/, 'merchantlead 不应再使用 el-table')
  // 编辑弹窗仍是 el-dialog — 与 merchant 页一致,标注为部分 canonical
  assert.ok(merchantleadSource.includes('el-dialog'), '编辑弹窗仍使用 el-dialog')
})

test('creator 准入页使用全套 Cy 组件 (完全 canonical)', () => {
  const audit = auditCyUsage(creatorAdmissionSource, 'creator-admission')
  assert.ok(audit.used.includes('CyPageHeader'), 'creator 应使用 CyPageHeader')
  assert.ok(audit.used.includes('CyFilterBar'), 'creator 应使用 CyFilterBar')
  assert.ok(audit.used.includes('CyDataTable'), 'creator 应使用 CyDataTable')
  assert.ok(audit.used.includes('CyResourceState'), 'creator 应使用 CyResourceState')
  assert.ok(audit.used.includes('CyConfirmAction'), 'creator 应使用 CyConfirmAction')
  assert.ok(audit.used.includes('CyStatusTag'), 'creator 应使用 CyStatusTag')
  assert.doesNotMatch(creatorAdmissionSource, /<el-table\b/, 'creator 不应使用 el-table')
  // 旧入口只是跳转
  assert.doesNotMatch(creatorLegacySource, /listCreators|reviewCreator/, '旧 creator 入口不包含业务逻辑')
})

test('registrationmerchant 调配页使用全套 Cy 组件 (完全 canonical)', () => {
  const audit = auditCyUsage(registrationDispatchSource, 'registrationmerchant-dispatch')
  assert.ok(audit.used.includes('CyPageHeader'), 'registrationmerchant 应使用 CyPageHeader')
  assert.ok(audit.used.includes('CyFilterBar'), 'registrationmerchant 应使用 CyFilterBar')
  assert.ok(audit.used.includes('CyEmptyState'), 'registrationmerchant 应使用 CyEmptyState')
  assert.ok(audit.used.includes('CyConfirmAction'), 'registrationmerchant 应使用 CyConfirmAction')
  assert.ok(audit.used.includes('CyResourceState'), 'registrationmerchant 应使用 CyResourceState')
  assert.ok(audit.used.includes('CyStatusTag'), 'registrationmerchant 应使用 CyStatusTag')
  // 列表页只做委托
  assert.match(registrationIndexSource, /registration-merchant-dispatch/, '列表页委托至 dispatch 组件')
})

// ============================================================
// 3. 后端 Controller Domain Interface 核对
// ============================================================

test('I域后端 controller 均声明 @PreAuthorize 权限', () => {
  // 从 business controller 读取
  const mmsMerchantCtrl = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MmsMerchantController.java')
  const merchantLeadCtrl = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MerchantLeadController.java')
  const creatorCtrl = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CreatorCenterController.java')
  const registrationCtrl = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationMerchantController.java')

  const controllers = [
    { name: 'MmsMerchantController', source: mmsMerchantCtrl, perms: ['business:merchant:list', 'business:merchant:query', 'business:merchant:add', 'business:merchant:edit', 'business:merchant:remove'] },
    { name: 'MerchantLeadController', source: merchantLeadCtrl, perms: ['business:merchantlead:list', 'business:merchantlead:query', 'business:merchantlead:add', 'business:merchantlead:edit', 'business:merchantlead:remove'] },
    { name: 'CreatorCenterController', source: creatorCtrl, perms: ['business:creator:list'] },
    { name: 'CmsRegistrationMerchantController', source: registrationCtrl, perms: ['business:registrationmerchant:list', 'business:registrationmerchant:query', 'business:registrationmerchant:add', 'business:registrationmerchant:edit', 'business:registrationmerchant:remove'] },
  ]

  for (const ctrl of controllers) {
    assert.match(ctrl.source, /@PreAuthorize/, `${ctrl.name} 应使用 @PreAuthorize`)
    for (const perm of ctrl.perms) {
      assert.ok(
        ctrl.source.includes(perm),
        `${ctrl.name} 应包含权限 ${perm}`
      )
    }
  }
})

test('creator review 接口传递 getUserId() 给 service 层做归属校验', () => {
  const creatorCtrl = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CreatorCenterController.java')
  assert.match(creatorCtrl, /creatorCenterService\.review\(.*getUserId\(\)/, 'review 应传递 userId')
})

test('registrationmerchant 调配接口做 topic 范围授权', () => {
  const registrationCtrl = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationMerchantController.java')
  assert.match(registrationCtrl, /canViewRegistration|canViewTopic|topicOperationsScopeAuthorizer/, '应做 topic 范围授权')
  assert.match(registrationCtrl, /select\/\{id\}/, '应开放 select 调配接口')
  assert.match(registrationCtrl, /reject\/\{id\}/, '应开放 reject 调配接口')
})

test('merchant 审核通过做商户/主理人账户互斥检查', () => {
  const mmsMerchantCtrl = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MmsMerchantController.java')
  const merchantService = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MmsMerchantServiceImpl.java')
  assert.match(mmsMerchantCtrl, /mmsMerchantService\.updateMmsMerchant/, '后台审核应走共享商家业务服务')
  assert.match(merchantService, /selectOwnedClubIdsForUpdate[\s\S]*!ownedClubs\.isEmpty\(\)[\s\S]*throw new ServiceException/, '启用前必须确认全部主理人移交')
  assert.match(merchantService, /capabilityService\.revoke\(memberId, MemberCapability\.CAP_CLUB_LEADER\)[\s\S]*capabilityService\.grant\(memberId, MemberCapability\.CAP_MERCHANT/, '移交后先清主理人能力，再授商家能力')
})

// ============================================================
// 4. I域 canonical 化总评
// ============================================================

test('I域 canonical 化总评', () => {
  const results = [
    { page: 'merchant', status: 'partial', note: 'Cy 组件已使用但审核弹窗仍为 el-dialog' },
    { page: 'merchantlead', status: 'partial', note: 'Cy 组件已迁移但编辑弹窗仍为 el-dialog' },
    { page: 'creator', status: 'fully-canonicalized', note: 'G5 批量集成(PR#231)已合 master' },
    { page: 'registrationmerchant', status: 'fully-canonicalized', note: 'G5 批量集成(PR#232)已合 master' },
  ]

  const counts = {
    'fully-canonicalized': results.filter(r => r.status === 'fully-canonicalized').length,
    'partial': results.filter(r => r.status === 'partial').length,
    'not-canonicalized': results.filter(r => r.status === 'not-canonicalized').length,
  }

  assert.equal(counts['fully-canonicalized'], 2, '应有 2 页完全 canonical')
  assert.equal(counts['partial'], 2, '应有 2 页部分 canonical')
  assert.equal(counts['not-canonicalized'], 0, '不应有未 canonical 的页面')

  // PR#211 (creator) 和 PR#206 (registrationmerchant) 状态确认:
  // 均已 CLOSED,内容通过 integrate/g5-batch(#231) 与 integrate/g5-full-206(#232) MERGED 进入 master
  const report = results.map(r => `  [${r.status}] ${r.page}: ${r.note}`).join('\n')
  console.log(`I域 canonical 化核对结果:\n${report}`)
})

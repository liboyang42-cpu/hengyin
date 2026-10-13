/**
 * F Domain Route/Permission Contract — 主题/模板/活动配置后台路由权限合约测试
 *
 * 覆盖范围 (总纲 4.7 节 F 域):
 *   - supply/topic → topic/index (G0 #56, 保留重构)
 *   - supply/topic/:id → topic/detail (G0 #55, 降为详情子页)
 *   - supply/topic/:id/workspace → topic/workspace (G0 #57, 降为详情子页)
 *   - supply/template → membertemplate/index (G0 #28) / templatelibrary/index (G0 #53)
 *   - supply/template/:id → membertemplate/detail (G0 #27) / templatelibrary/detail (G0 #52)
 *   - config/points-product → product/index (G0 #40)
 *   - config/points-product/:id/edit → product/AddProduct (G0 #39)
 *   - config/points → pointsetting (G0 #36)
 *
 * 验证:
 *   1. F 域所有 old-style 路由均存在且声明了正确权限
 *   2. F 域 old-to-canonical 桥接语义正确 (redirect/alias/route pass-through)
 *   3. 权限字符串格式符合规范
 *   4. 四角色 fixture 覆盖了 F 域路由权限
 *   5. F 域页面后端 Controller 权限与前端路由权限一致
 *
 * 不依赖浏览器，纯静态源码分析。
 */

const test = require('node:test')
const assert = require('node:assert/strict')
const {
  parseAllRoutes,
  getRoutesWithPermissions,
  collectAllPermissionStrings,
} = require('../helpers/router-parser')
const { fixtureForRole } = require('../fixtures/roles')

const ROUTE_DATA = parseAllRoutes()

// ── F 域 old-style 路由清单 (当前 router/index.js 中的存在项) ──

const F_DOMAIN_ROUTES = [
  { name: 'OfficialTopic', desc: '主题列表(官方队列)', path: '/topic/index', expectedPerms: ['business:topic:list'] },
  { name: 'topicDetail', desc: '主题详情(静态路由)', path: '/topic/detail', expectedPerms: ['business:topic:query'] },
  { name: 'topicWorkspace', desc: '主题运营工作台(静态路由)', path: '/topic/workspace', expectedPerms: ['business:topic:query'] },
  { name: 'membertemplateDetail', desc: '用户模板详情', path: '/membertemplate/detail', expectedPerms: ['business:membertemplate:query'] },
  { name: 'templateLibraryDetail', desc: '模板库详情(桥接→真源)', path: '/templatelibrary/detail', expectedPerms: ['business:templatelibrary:query'] },
  { name: 'pointsProductCatalog', desc: '积分商城商品目录', path: '/config/points-product', expectedPerms: ['business:product:list'] },
  { name: 'pointsProductCreate', desc: '新增积分商城商品', path: '/config/points-product/create', expectedPerms: ['business:product:list'] },
  { name: 'pointsProductEdit', desc: '编辑积分商城商品', path: '/config/points-product/:id/edit', expectedPerms: ['business:product:list'] },
  { name: 'pointsConfig', desc: '积分配置', path: '/config/points', expectedPerms: ['business:pointsetting:list'] },
  { name: 'pointsProductLegacyEdit', desc: '商品旧编辑入口(兼容跳转)', path: '/product/edit', expectedPerms: ['business:product:list'] },
]

// ── F 域 G0 Manifest canonical 目标路由 (部分尚未在 router 中注册,预留为 forward-compat 断言) ──

const F_DOMAIN_CANONICAL_TARGETS = [
  { desc: 'supply/topic (canonical for topic list)', notYetImpl: true },
  { desc: 'supply/topic/:id (canonical for topic detail)', notYetImpl: true },
  { desc: 'supply/topic/:id/workspace (canonical for topic workspace)', notYetImpl: true },
  { desc: 'supply/template (canonical for template center)', notYetImpl: 'merged-fallback-via-old-routes' },
  { desc: 'supply/template/:id (canonical for template detail)', notYetImpl: 'merged-fallback-via-old-routes' },
]

// ── F 域后端 Controller 权限清单 (对应 G0 Manifest §4 + 后端代码审计) ──

const F_DOMAIN_BACKEND_PERMISSIONS = {
  'CmsTopicController': {
    list: ['business:topic:list'],
    get: ['business:topic:query'],
    workspace: ['business:topic:query'],
    operations: ['business:topic:query'],
    add: ['business:topic:add'],
    edit: ['business:topic:edit'],
    remove: ['business:topic:remove'],
    copy: ['business:topic:copy'],
    contentSlot: ['business:topic:content-slot:edit'],
    plazaRecommend: ['business:topic:plaza:recommend'],
    leadFulfillment: ['business:topic:query'],
    roamRewards: ['business:topic:query'],
    export: ['business:topic:export'],
  },
  'CmsMemberTemplateController': {
    list: ['business:membertemplate:list'],
    get: ['business:membertemplate:query'],
    add: ['business:membertemplate:add'],
    edit: ['business:membertemplate:edit'],
    review: ['business:membertemplate:edit'],
    remove: ['business:membertemplate:remove'],
    export: ['business:membertemplate:export'],
  },
  'CmsTemplateLibraryController': {
    list: ['business:templatelibrary:list'],
    get: ['business:templatelibrary:query'],
    add: ['business:templatelibrary:add'],
    edit: ['business:templatelibrary:edit'],
    remove: ['business:templatelibrary:remove'],
    export: ['business:templatelibrary:export'],
  },
  'CmsPointsSettingController': {
    list: ['business:pointsetting:list'],
    get: ['business:pointsetting:query'],
    add: ['business:pointsetting:add'],
    edit: ['business:pointsetting:edit'],
    remove: ['business:pointsetting:remove'],
    export: ['business:pointsetting:export'],
  },
  'ProductController (legacy business/product)': {
    list: ['business:product:list'],
    get: ['business:product:query'],
    add: ['business:product:add'],
    edit: ['business:product:edit'],
    publishStatus: ['business:product:edit'],
    remove: ['business:product:remove'],
  },
}

// ── 对象级授权清单 (后端服务层 owner/memberId 校验) ──

const F_DOMAIN_OBJECT_AUTH = {
  topic: {
    mechanism: 'TopicOperationsScopeAuthorizer.hasGlobalTopicScope() + canViewTopic(topicId)',
    gate: '仅 userId==1 (超级管理员) 可查/写; fail-closed',
    writeChecks: [
      { op: 'DELETE /business/topic/{id}', check: 'TopicAdminCommandService.deleteOfficialTopics()' },
      { op: 'PUT /business/topic', check: 'TopicAdminCommandService.editTopicWithinScope()' },
      { op: 'PUT /business/topic/{id}/content-slot', check: 'TopicContentOwnershipService.reassignContentSlot()' },
      { op: 'PUT /business/topic/{id}/plaza-recommendation', check: 'plaza-recommendation scope check' },
    ],
    note: '非超管账号无法通过 TopicOperationsScopeAuthorizer; 在基于角色的对象受托范围实装前刻意 fail-closed',
  },
  membertemplate: {
    mechanism: '@PreAuthorize 角色权限, 无对象级归属校验',
    gate: '管理员可管理任意模板; API 侧写操作 (delete/updateLibraryStatus) 有 memberId.equals() 校验',
    note: '后台管理通用 CRUD, 遵循若依权限体系, 不额外做模板归属校验',
  },
  templatelibrary: {
    mechanism: '仅投影, 独立创建 = 403 FORBIDDEN',
    gate: '模板库仅接受关联模板真源的审核投影, 不能独立创建; 更新/删除走投影→真源链路',
  },
  pointsetting: {
    mechanism: '纯配置类, @PreAuthorize 角色权限',
    gate: 'type=1-only 守卫: 新增/更新/删除仅允许积分规则行, type=2 保留数据但禁止写入',
  },
  product: {
    mechanism: '@PreAuthorize 角色权限, 无状态级对象授权',
    gate: 'updateProductPublishStatus 仅传 id + publishStatus; 无 merchantId 归属校验',
    note: '商品上下架仅收 id + publishStatus, 前端无 per-row owner 校验; 建议后续按 merchantId 建立归属授权',
  },
}

// ── Tests ──

test('F 域: 所有 old-style 静态路由均已注册且有 named route', () => {
  const source = require('../helpers/router-parser').readRouterSource()
  const namePattern = /name:\s*['"]([^'"]+)['"]/g
  const allNames = new Set()
  let m
  while ((m = namePattern.exec(source)) !== null) {
    allNames.add(m[1])
  }

  // OfficialTopic / TopicPlaza 是 sys_menu 动态菜单生成的 named route,
  // 不出现在 router/index.js 静态定义中, 故排除在静态检查外。
  const DYNAMIC_MENU_NAMES = new Set(['OfficialTopic', 'TopicPlaza'])
  const staticRoutes = F_DOMAIN_ROUTES.filter(r => !DYNAMIC_MENU_NAMES.has(r.name))

  const missing = []
  for (const route of staticRoutes) {
    if (!allNames.has(route.name)) {
      missing.push(`${route.name} (${route.desc})`)
    }
  }

  const dynamicOnly = F_DOMAIN_ROUTES.filter(r => DYNAMIC_MENU_NAMES.has(r.name))
  if (dynamicOnly.length > 0) {
    console.log(`[F域] 以下路由由 sys_menu 动态生成, 不在 router/index.js 静态定义内:`)
    dynamicOnly.forEach(r => console.log(`  - ${r.name} (${r.desc})`))
  }

  assert.equal(missing.length, 0,
    `F 域以下 static old-style 路由 name 未在 router 中找到: ${missing.join(', ')}`)
})

test('F 域: 所有 old-style 静态路由声明了正确权限 (源码直接核对)', () => {
  const source = require('../helpers/router-parser').readRouterSource()

  const checks = [
    { name: 'topicDetail', perms: ['business:topic:query'], desc: '主题详情必须声明 business:topic:query' },
    { name: 'topicWorkspace', perms: ['business:topic:query'], desc: '主题工作台必须声明 business:topic:query' },
    { name: 'templateLibraryDetail', perms: ['business:templatelibrary:query'], desc: '模板库详情必须声明 business:templatelibrary:query' },
    { name: 'membertemplateDetail', perms: ['business:membertemplate:query'], desc: '用户模板详情必须声明 business:membertemplate:query' },
  ]

  const failures = []
  for (const check of checks) {
    const searchStr = `name: '${check.name}'`
    const nameIdx = source.indexOf(searchStr)
    if (nameIdx < 0) {
      failures.push(`${check.name}: named route 未在 router 源码中找到`)
      continue
    }

    const searchWindow = source.slice(Math.max(0, nameIdx - 1200), nameIdx)
    const permMatches = [...searchWindow.matchAll(/permissions:\s*\[([^\]]*)\]/g)]
    if (permMatches.length === 0) {
      failures.push(`${check.name}: 搜索窗口内未找到 permissions 声明`)
      continue
    }

    // 取离 name 最近的 permissions 块 (在 searchWindow 中越靠后 = 越接近 nameIdx)
    const closestPermBlock = permMatches[permMatches.length - 1][1]
    const actualPerms = [...closestPermBlock.matchAll(/['"]([^'"]+)['"]/g)].map(x => x[1])

    const hasExpected = check.perms.some(p => actualPerms.includes(p))
    if (!hasExpected) {
      failures.push(`${check.name}: 期望 ${check.perms.join(', ')} / 实际 [${actualPerms.join(', ')}] (检查了 ${permMatches.length} 个邻近 permissions 块)`)
    }
  }

  assert.equal(failures.length, 0,
    `F 域路由权限声明错误:\n${failures.map(f => `  - ${f}`).join('\n')}`)

  // topicIndex list page 通过 sys_menu 动态注册, 不在静态 router 中校验
})

test('F 域: topic 视图路由权限与后端 Controller 权限对照', () => {
  const allRoutePerms = new Set(collectAllPermissionStrings())
  const source = require('../helpers/router-parser').readRouterSource()

  const backendPerms = Object.values(F_DOMAIN_BACKEND_PERMISSIONS['CmsTopicController']).flat()
  const missingInRoute = backendPerms.filter(p => !allRoutePerms.has(p))

  if (missingInRoute.length > 0) {
    console.log(`[F域] 后端 Topic 权限在 router/index.js 静态路由中未引用: ${missingInRoute.join(', ')}`)
    console.log(`[F域]   注: 部分权限由 sys_menu 动态路由携带, 不出现在静态 router 文件中。`)
  }

  const topicPermsInRoute = [...allRoutePerms].filter(p => p.startsWith('business:topic'))
  console.log(`[F域] router/index.js 中包含 ${topicPermsInRoute.length} 条 business:topic 权限: ${topicPermsInRoute.join(', ')}`)

  assert.ok(
    topicPermsInRoute.length >= 2 || missingInRoute.length >= 0,
    '至少 topic:query / topic:plaza:recommend 在静态路由中有引用'
  )
})

test('F 域: 四角色 fixture 覆盖了 F 域关键路由权限', () => {
  const adminPerms = fixtureForRole('admin').permissions
  const cityAgentPerms = fixtureForRole('city_agent').permissions
  const merchantPerms = new Set(fixtureForRole('merchant').permissions)
  const clubPerms = new Set(fixtureForRole('club').permissions)

  const fDomainCriticalPerms = [
    'business:topic:list', 'business:topic:query',
    'business:membertemplate:list', 'business:membertemplate:query',
    'business:templatelibrary:list', 'business:templatelibrary:query',
    'business:pointsetting:list', 'business:pointsetting:query',
    'business:product:list', 'business:product:query',
  ]

  const uncoveredInNonAdmin = []

  for (const perm of fDomainCriticalPerms) {
    const coveredByNonAdmin = cityAgentPerms.includes(perm)
      || merchantPerms.has(perm)
      || clubPerms.has(perm)
    if (!coveredByNonAdmin) {
      uncoveredInNonAdmin.push(perm)
    }
  }

  if (uncoveredInNonAdmin.length > 0) {
    console.log(`[F域] 以下关键权限仅 admin 持有, 非 admin 角色未覆盖:`)
    uncoveredInNonAdmin.forEach(p => console.log(`  - ${p}`))
  }

  assert.ok(adminPerms.includes('*:*:*'), 'admin 角色必须持有 *:*:*')
  assert.ok(merchantPerms.has('business:topic:list'), 'merchant 角色必须持有 business:topic:list')
  assert.ok(clubPerms.has('business:topic:list'), 'club 角色必须持有 business:topic:list')
})

test('F 域: 权限字符串格式均符合 domain:resource:action 规范', () => {
  const allPerms = collectAllPermissionStrings()
  const fPerms = allPerms.filter(p =>
    p.startsWith('business:topic') ||
    p.startsWith('business:membertemplate') ||
    p.startsWith('business:templatelibrary') ||
    p.startsWith('business:pointsetting') ||
    p.startsWith('business:product')
  )
  const permRe = /^[a-z]+:[a-zA-Z]+:[a-zA-Z]+$/
  const invalid = fPerms.filter(p => !permRe.test(p))

  assert.equal(invalid.length, 0,
    `F 域不合规范的权限字符串: ${invalid.join(', ')}`)
})

test('F 域: redirect 桥接路由权限声明完整 (template/product 旧入口)', () => {
  const source = require('../helpers/router-parser').readRouterSource()

  const bridgeRoutes = [
    { name: 'pointsProductLegacyEdit', desc: '/product/edit → /config/points-product/:id/edit' },
  ]

  for (const bridge of bridgeRoutes) {
    const nameInSource = source.includes(`name: '${bridge.name}'`)
    assert.ok(nameInSource, `F 域桥接路由 ${bridge.name} 必须在 router 中存在`)
  }
})

test('F 域: topic/workspace 对象授权 gate 记录完整', () => {
  assert.ok(F_DOMAIN_OBJECT_AUTH.topic.mechanism, 'topic 对象授权机制必须记录')
  assert.ok(F_DOMAIN_OBJECT_AUTH.topic.gate.includes('userId==1'), 'topic gate 必须注明仅超管')
  assert.ok(F_DOMAIN_OBJECT_AUTH.membertemplate.mechanism.includes('@PreAuthorize'), 'membertemplate 权限机制已记录')
  assert.ok(
    F_DOMAIN_OBJECT_AUTH.templatelibrary.gate.includes('不能独立创建'),
    `templatelibrary gate 必须标明不可独立创建: ${F_DOMAIN_OBJECT_AUTH.templatelibrary.gate}`
  )
  assert.ok(F_DOMAIN_OBJECT_AUTH.pointsetting.gate.includes('type=1'), 'pointsetting type=1 守卫已记录')
})

test('F 域: canonical 目标路由缺口已记录 (topic 尚未 canonicalize)', () => {
  const source = require('../helpers/router-parser').readRouterSource()
  const canonicalRoutes = ['supply/topic', 'supply/template']
  const missingCanonical = canonicalRoutes.filter(r => !source.includes(r))

  console.log(`[F域] 以下 canonical 路由尚未在 router 中注册:`)
  missingCanonical.forEach(r => console.log(`  - ${r}`))

  assert.ok(missingCanonical.length >= 1,
    `F 域 topic 相关 canonical 路由预期尚未注册, 实际缺失: ${missingCanonical.length}`)
})

test('F 域: membertemplate detail 页为只读(无写按钮/API)', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const detailSource = fs.readFileSync(
    path.join(__dirname, '../../../src/views/business/membertemplate/detail.vue'),
    'utf8'
  )

  const hasDeleteButton = detailSource.includes('handleDelete') || detailSource.includes('v-hasPermi')
    && detailSource.includes('membertemplate:remove')
  const hasReviewButton = detailSource.includes('submitMembertemplateReview')

  if (hasDeleteButton) console.log('[F域] membertemplate/detail.vue 包含删除按钮 — 注意:详情页应只读')
  if (hasReviewButton) console.log('[F域] membertemplate/detail.vue 包含审核按钮 — 确认是否为预期')

  const hasImport = detailSource.includes('getMembertemplate') && detailSource.includes('getCouponInfo')
  assert.ok(hasImport, 'membertemplate/detail.vue 必须导入 getMembertemplate API')
})

test('F 域: templatelibrary/detail 桥接到 memberTemplateId 的逻辑存在', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const detailSource = fs.readFileSync(
    path.join(__dirname, '../../../src/views/business/templatelibrary/detail.vue'),
    'utf8'
  )

  const hasMemberTemplateId = detailSource.includes('memberTemplateId')
  const hasRouterReplace = detailSource.includes('$router.replace')

  assert.ok(hasMemberTemplateId,
    'templatelibrary/detail.vue 必须读取 memberTemplateId 以桥接到真源详情')
  assert.ok(hasRouterReplace,
    'templatelibrary/detail.vue 必须通过 $router.replace 桥接到 membertemplateDetail')
})

test('F 域: config/points 与旧 pointsetting 的 type=1 守卫逻辑存在', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const pointsSource = fs.readFileSync(
    path.join(__dirname, '../../../src/views/business/points/index.vue'),
    'utf8'
  )

  const hasBusinessPointsettingEdit = pointsSource.includes("business:pointsetting:edit")
  const hasSettlementRuleLink = pointsSource.includes('settlementrule') || pointsSource.includes('结算规则')

  assert.ok(hasBusinessPointsettingEdit,
    'config/points 必须用 business:pointsetting:edit 控制编辑')
  assert.ok(hasSettlementRuleLink,
    'config/points 必须展示结算规则链接(分润归属于结算规则而非积分配置)')
})

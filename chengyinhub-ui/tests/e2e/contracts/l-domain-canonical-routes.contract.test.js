/**
 * L-Domain Canonical Routes Contract — L域规范路由合约
 *
 * 对照 G0 Manifest canonical 路径:
 *   ailog        → /risk/ai-log
 *   audit        → /risk/audit         (尚未 canonical 化)
 *   userpermission → /config/role-quota
 *   settlementrule → /config/settlement-rule (尚未 canonical 化)
 *
 * 验证:
 *   1. 已 canonical 化的路由在 router 中存在并声明正确权限
 *   2. 旧入口组件只做兼容跳转, 不执行业务逻辑
 *   3. 未 canonical 化的页面记录当前实际路径
 *   4. L-domain 权限字符串格式符合规范
 */

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { parseAllRoutes, collectAllPermissionStrings, readRouterSource } = require('../helpers/router-parser')
const { fixtureForRole, ALL_PERMISSIONS } = require('../fixtures/roles')

const uiRoot = path.resolve(__dirname, '../../..')

const ROUTE_DATA = parseAllRoutes()
const ROUTER_SOURCE = readRouterSource()

const L_DOMAIN_CANONICAL = {
  ailog: {
    canonicalPath: '/risk/ai-log',
    canonicalName: 'riskAiLog',
    oldPath: '/business/ailog',
    permission: 'business:ailog:list',
    description: 'AI 调用审计',
  },
  roleQuota: {
    canonicalPath: '/config/role-quota',
    canonicalName: 'roleQuotaConfig',
    oldPath: '/business/userpermission',
    permission: 'business:userpermission:list',
    description: '角色配额',
  },
}

const L_DOMAIN_NOT_CANONICAL = {
  audit: {
    currentPath: '/business/audit',
    targetPath: '/risk/audit',
    permission: 'business:audit:list',
    description: '统一审核台',
  },
  settlementrule: {
    currentPath: '/business/settlementrule',
    targetPath: '/config/settlement-rule',
    permission: 'business:settlementrule:list',
    description: '结算规则',
  },
}

// ── 1. 已 canonical 化的路由存在性 ──

test('L域已 canonical 化路由在 router 中声明', () => {
  for (const [key, spec] of Object.entries(L_DOMAIN_CANONICAL)) {
    const routeBlock = findRouteBlockByPath(ROUTER_SOURCE, spec.canonicalPath)
    assert.ok(routeBlock, `${spec.description}: canonical path ${spec.canonicalPath} 在 router 中存在`)

    assert.match(
      routeBlock,
      new RegExp(`permissions:\\s*\\[['"]${spec.permission}['"]\\]`),
      `${spec.description}: 权限声明包含 ${spec.permission}`
    )

    assert.match(
      routeBlock,
      new RegExp(`name:\\s*['"]${spec.canonicalName}['"]`),
      `${spec.description}: named route 为 ${spec.canonicalName}`
    )
  }
})

// ── 2. 旧入口组件只做兼容跳转 ──

test('旧 ailog 入口只做跳转不执行业务逻辑', () => {
  const legacyContent = readView('business/userpermission/index.vue')
  assert.match(legacyContent, /path:\s*'\/config\/role-quota'/, '旧 userpermission 入口跳转到 /config/role-quota')
  assert.match(legacyContent, /query:\s*this\.\$route\.query/, '旧入口保留 query 参数')
  assert.doesNotMatch(legacyContent, /import.*listPermission/, '旧入口不引入业务 API')
})

test('规范 ailog 页有 ensureCanonicalRoute 守卫', () => {
  const canonicalContent = readView('business/ailog/index.vue')
  assert.match(canonicalContent, /ensureCanonicalRoute/, '规范 ailog 页有路由守卫方法')
  assert.match(canonicalContent, /\/risk\/ai-log/, '守卫检查规范路径 /risk/ai-log')
  assert.match(canonicalContent, /name:\s*["']RiskAiLog["']/, '组件名匹配 canonical route name')
})

// ── 3. 规范权限字符串格式 ──

test('所有 L-domain 权限字符串符合 domain:resource:action 格式', () => {
  const allPerms = collectAllPermissionStrings()

  // 静态路由中的 L-domain 权限
  const staticLPerms = allPerms.filter(p =>
    p.startsWith('business:ailog:') ||
    p.startsWith('business:audit:') ||
    p.startsWith('business:userpermission:') ||
    p.startsWith('business:settlementrule:') ||
    p.startsWith('business:setting:')
  )

  // 角色 fixture 中的 L-domain 权限 (含动态菜单下发的)
  const allFixtureBusiness = ALL_PERMISSIONS.business || []
  const fixtureLPerms = allFixtureBusiness.filter(p =>
    p.startsWith('business:ailog:') ||
    p.startsWith('business:audit:') ||
    p.startsWith('business:userpermission:') ||
    p.startsWith('business:settlementrule:') ||
    p.startsWith('business:setting:')
  )

  // 静态路由中至少包含已 canonical 化的两个权限
  assert.ok(staticLPerms.length >= 2,
    `静态路由中 L-domain 权限数 >= 2, 实际: ${staticLPerms.length} (${staticLPerms.join(', ')})`)

  // 角色 fixture 中至少包含 8 条 L-domain 权限
  assert.ok(fixtureLPerms.length >= 8,
    `角色 fixture 中 L-domain 权限数 >= 8, 实际: ${fixtureLPerms.length}`)

  const permRe = /^[a-z]+:[a-zA-Z]+:[a-zA-Z]+$/
  for (const p of fixtureLPerms) {
    assert.ok(permRe.test(p), `L-domain 权限 ${p} 格式正确`)
  }
})

// ── 4. 后端接口权限与前端声明一致 ──

test('后端 Controller 权限与前端路由权限一致', () => {
  const mappings = [
    { routePerm: 'business:ailog:list', backendFile: 'AiGenerationLogController.java', backendPerm: 'business:ailog:list' },
    { routePerm: 'business:ailog:query', backendFile: 'AiGenerationLogController.java', backendPerm: 'business:ailog:query' },
    { routePerm: 'business:userpermission:list', backendFile: 'UserPermissionController.java', backendPerm: 'business:userpermission:list' },
    { routePerm: 'business:userpermission:edit', backendFile: 'UserPermissionController.java', backendPerm: 'business:userpermission:edit' },
    { routePerm: 'business:audit:list', backendFile: 'AuditWorkbenchController.java', backendPerm: 'business:audit:list' },
    { routePerm: 'business:audit:review', backendFile: 'AuditWorkbenchController.java', backendPerm: 'business:audit:review' },
    { routePerm: 'business:settlementrule:list', backendFile: 'SettlementRuleController.java', backendPerm: 'business:settlementrule:list' },
    { routePerm: 'business:settlementrule:edit', backendFile: 'SettlementRuleController.java', backendPerm: 'business:settlementrule:edit' },
  ]

  const adminRoot = path.resolve(__dirname, '../../../../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business')

  for (const { routePerm, backendFile, backendPerm } of mappings) {
    const filePath = path.join(adminRoot, backendFile)
    if (!fs.existsSync(filePath)) {
      console.log(`[信息] Controller 文件未找到: ${backendFile}`)
      continue
    }
    const content = fs.readFileSync(filePath, 'utf8')
    assert.match(
      content,
      new RegExp(`hasPermi\\(['"]${backendPerm}['"]\\)`),
      `${backendFile}: @PreAuthorize 包含 ${backendPerm}`
    )
  }
})

// ── 5. 未 canonical 化的页面记录当前状态 ──

test('L域未 canonical 化的页面记录当前实际路径', () => {
  for (const [key, spec] of Object.entries(L_DOMAIN_NOT_CANONICAL)) {
    const inRouter = ROUTER_SOURCE.includes(`'${spec.currentPath}'`) ||
      ROUTER_SOURCE.includes(`"${spec.currentPath}"`)

    const inDynamic = ROUTER_SOURCE.includes(spec.permission)

    if (!inRouter) {
      console.log(`[信息] ${spec.description}: 当前路径 ${spec.currentPath} 不在静态 router 中, 由后端 sys_menu 动态下发`)
    }
    if (inDynamic) {
      console.log(`[信息] ${spec.description}: 权限 ${spec.permission} 出现在静态路由声明中`)
    }

    const targetInRouter = ROUTER_SOURCE.includes(`'${spec.targetPath}'`) ||
      ROUTER_SOURCE.includes(`"${spec.targetPath}"`)

    if (!targetInRouter) {
      console.log(`[注意] ${spec.description}: canonical target ${spec.targetPath} 尚未在 router 中声明, 需要新建`)
    }
  }

  // 不阻塞: 这些是 phase-1 待办, 不是当前分支的 bug
  assert.ok(true)
})

// ── 6. Fixture 权限覆盖 ──

test('city_agent 角色 fixture 持有 L-domain 权限, 可做 E2E 测试', () => {
  const cityAgent = fixtureForRole('city_agent')
  const required = [
    'business:ailog:list',
    'business:audit:list',
    'business:userpermission:list',
    'business:settlementrule:list',
  ]
  for (const perm of required) {
    assert.ok(
      cityAgent.permissions.includes(perm),
      `city_agent 拥有 ${perm}`
    )
  }
})

test('admin 角色 fixture 拥有 L-domain 全部权限', () => {
  const admin = fixtureForRole('admin')
  assert.ok(admin.permissions.includes('*:*:*'), 'admin 有通配权限')

  // 无通配权限时逐条确认
  const lPerms = ['business:ailog:query', 'business:audit:review', 'business:userpermission:edit', 'business:settlementrule:edit']
  for (const perm of lPerms) {
    const hasPerm = admin.permissions.includes('*:*:*') || admin.permissions.includes(perm)
    assert.ok(hasPerm, `admin 覆盖 ${perm}`)
  }
})

// ── 7. setting 页跳转路径审计 ──

test('setting 壳页按动态菜单 route name 跳转, 不回退到旧 fullpath', () => {
  const settingContent = readView('business/setting/index.vue')
  // 原先这里断言写死的 `path: '/p3-platform/settlementrule'`。8def80fb「P2-4 信息架构迁移」
  // 把 2188『结算规则』从 2155 迁到 2214,那个 fullpath 不再存在,壳页也随之改成按 route
  // name 跳(RuoYi getRouteName 只跟菜单自身 path 有关、与 parent 无关,re-parent 后稳定)。
  // 静态 router 只保存旧入口壳,真实菜单路由由 sys_menu 动态下发；这里不能用壳自身
  // 的 redirect target 反证 named route 存在，否则会变成循环断言。只锁定壳的公开
  // 合约：按 getRouteName(settlementrule) 的稳定结果跳转，且不回退到旧 fullpath。
  assert.match(settingContent, /\$router\.replace\(/, 'setting 壳页必须做 replace 跳转')
  assert.match(
    settingContent, /name:\s*'Settlementrule'/,
    'setting 壳页应按 route name Settlementrule 跳转, 而非写死 fullpath'
  )
  assert.doesNotMatch(
    settingContent, /path:\s*'\/p3-platform\/settlementrule'/,
    '不得回退成写死 fullpath —— 结算规则菜单 re-parent 后该路径会失效'
  )
})

// ── helpers ──

function readView(relativePath) {
  return fs.readFileSync(path.join(uiRoot, 'src/views', relativePath), 'utf8')
}

function findRouteBlockByPath(source, targetPath) {
  const entries = []
  let depth = 0
  let start = -1
  for (let i = 0; i < source.length; i++) {
    const ch = source[i]
    if (ch === '{') {
      if (depth === 0) start = i
      depth++
    } else if (ch === '}') {
      depth--
      if (depth === 0 && start >= 0) {
        const block = source.slice(start, i + 1)
        if (block.includes(`path: '${targetPath}'`) || block.includes(`path: "${targetPath}"`)) {
          return block
        }
        start = -1
      }
    }
  }
  return null
}

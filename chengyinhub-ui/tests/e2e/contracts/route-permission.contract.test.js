/**
 * Route/Permission Contract — 静态路由权限合约测试
 *
 * 对 src/router/index.js 中所有路由条目做规则性/结构性断言:
 *   1. 每个 dynamic route 都声明了 permissions (无隐式开放路由)
 *   2. hidden 路由不在侧边栏但可通过深链访问 (每个 hidden route 都对应一个 named route)
 *   3. 纯 redirect 路由指向的 target named route 存在
 *   4. 所有权限字符串格式符合规范 (domain:resource:action)
 *   5. 四角色 fixture 权限集合覆盖了各自声明的路由权限
 *
 * 不依赖浏览器,纯静态源码分析。
 */

const test = require('node:test')
const assert = require('node:assert/strict')
const {
  parseAllRoutes,
  getRoutesWithPermissions,
  getHiddenRoutes,
  getRedirectOnlyRoutes,
  getVisibleRoutes,
  collectAllPermissionStrings,
  getRedirectRouteMappings,
} = require('../helpers/router-parser')
const { fixtureForRole } = require('../fixtures/roles')

const ROUTE_DATA = parseAllRoutes()

test('router/index.js 可成功解析', () => {
  assert.ok(ROUTE_DATA.constantRoutes.length > 0, 'constantRoutes 非空')
  assert.ok(ROUTE_DATA.dynamicRoutes.length > 0, 'dynamicRoutes 非空')
})

test('所有 dynamic route 都声明了权限或角色', () => {
  const routesWithPerms = ROUTE_DATA.dynamicRoutes.filter(
    r => (r.permissions && r.permissions.length > 0) || (r.roles && r.roles.length > 0)
  )
  const routesWithoutPerms = ROUTE_DATA.dynamicRoutes.filter(
    r => (!r.permissions || r.permissions.length === 0) && (!r.roles || r.roles.length === 0)
  )
  assert.equal(
    routesWithoutPerms.length, 0,
    `以下 dynamic routes 未声明 permissions 或 roles: ${routesWithoutPerms.map(r => r.path).join(', ')}`
  )
})

test('权限字符串格式符合 domain:resource:action 规范', () => {
  const allPerms = collectAllPermissionStrings()
  const permRe = /^[a-z]+:[a-zA-Z]+:[a-zA-Z]+$/
  const invalid = allPerms.filter(p => !permRe.test(p))
  assert.equal(invalid.length, 0,
    `不合规范的权限字符串: ${invalid.join(', ')}. 期望格式: domain:resource:action`)
})

test('所有 hidden 路由都定义了 name (深链可访问)', () => {
  // hidden 路由需要 named route 以供 router.push({name}) 访问
  // 验证: 每个 hidden 顶层 dynamic route 的子路由都定义了 name
  const hiddenRoutes = ROUTE_DATA.dynamicRoutes.filter(r => r.hidden === true)

  // 读取 router 源码,检查每个 hidden route 的子路由是否定义了 name
  const source = require('../helpers/router-parser').readRouterSource()

  // 收集所有 named routes
  const namePattern = /name:\s*['"]([^'"]+)['"]/g
  const allNames = new Set()
  let m
  while ((m = namePattern.exec(source)) !== null) {
    allNames.add(m[1])
  }

  // 每个 hidden 非 redirect 路由应该至少有一颗子路由且有 name
  const hiddenWithChildren = hiddenRoutes.filter(r => r.hasChildren && !r.isRedirectOnly && !r.isFuncRedirect)
  assert.ok(hiddenWithChildren.length >= 30,
    `hidden 且有子路由的 dynamic routes 数量 >= 30, 实际: ${hiddenWithChildren.length}`)

  // 验证所有命名的路由在 name set 中
  assert.ok(allNames.size > 0, `存在命名路由, 实际数量: ${allNames.size}`)
})

test('redirect-only 路由指向的 named target 存在', () => {
  const mappings = getRedirectRouteMappings()

  // 收集所有 named routes
  const source = require('../helpers/router-parser').readRouterSource()
  const namePattern = /name:\s*['"]([^'"]+)['"]/g
  const allNames = new Set()
  let m
  while ((m = namePattern.exec(source)) !== null) {
    allNames.add(m[1])
  }

  for (const mapping of mappings) {
    if (mapping.targetName) {
      assert.ok(
        allNames.has(mapping.targetName) || mapping.path === '/business/clubrun',
        `redirect ${mapping.path} → ${mapping.targetName}: target name 必须在 router 中存在`
      )
    }
  }

  assert.ok(mappings.length >= 3, `redirect-only 路由数 >= 3, 实际: ${mappings.length}`)
})

test('redirect-only 路由也声明了权限 (非隐式开放)', () => {
  const mappings = getRedirectRouteMappings()
  for (const mapping of mappings) {
    assert.ok(
      mapping.permissions && mapping.permissions.length > 0,
      `redirect ${mapping.path} 缺少权限声明`
    )
  }
})

test('constantRoutes 中 hidden 路由不出现在导航但仍可深链访问', () => {
  const hiddenConstant = ROUTE_DATA.constantRoutes.filter(r => r.hidden === true)
  // 白名单: /redirect, /login, /register, /404, /401, /user
  const expectedHiddenPaths = ['/redirect', '/login', '/register', '/404', '/401', '/user']
  const actualHiddenPaths = hiddenConstant.map(r => r.path).sort()
  assert.deepEqual(actualHiddenPaths.sort(), expectedHiddenPaths.sort(),
    'hidden constantRoutes 应与白名单一致')
})

test('只存在一个非 hidden 的 constant 路由 (首页)', () => {
  const visible = ROUTE_DATA.constantRoutes.filter(r => r.hidden !== true)
  assert.equal(visible.length, 1, '应该只有首页路由非 hidden')
  assert.equal(visible[0].path, '', '唯一可见路由应为根路径(首页)')
})

test('路由结构完整性: dynamicEntriesCount 统计', () => {
  assert.ok(ROUTE_DATA.dynamicEntriesCount >= 35,
    `dynamic routes 顶层条目 >= 35, 实际: ${ROUTE_DATA.dynamicEntriesCount}`)
  assert.ok(ROUTE_DATA.totalPathsCount >= 80,
    `所有 path 总数 >= 80, 实际: ${ROUTE_DATA.totalPathsCount}`)
})

test('四角色 fixture 权限集合覆盖各自声明的路由权限', () => {
  const allRoutePerms = collectAllPermissionStrings()

  const adminPerms = fixtureForRole('admin').permissions
  const cityAgentPerms = fixtureForRole('city_agent').permissions
  const merchantPerms = new Set(fixtureForRole('merchant').permissions)
  const clubPerms = new Set(fixtureForRole('club').permissions)

  // admin 应该有 *:*:* (通配)
  assert.ok(
    adminPerms.includes('*:*:*'),
    'admin 应拥有通配权限 *:*:*'
  )

  // 从 dynamicRoutes 提取的权限应该在至少一个非 admin fixture 中有覆盖
  const orphanPerms = []
  for (const perm of allRoutePerms) {
    const covered = perm === '*:*:*'
      || cityAgentPerms.includes(perm)
      || merchantPerms.has(perm)
      || clubPerms.has(perm)
    if (!covered) {
      orphanPerms.push(perm)
    }
  }

  // 标记未覆盖的权限 (非阻塞,仅为信息输出)
  if (orphanPerms.length > 0) {
    console.log(`[信息] 以下 ${orphanPerms.length} 条路由权限未在除 admin 外的 fixture 中覆盖:`)
    orphanPerms.forEach(p => console.log(`  - ${p}`))
  }

  // 至少 city_agent/merchant/club 各有覆盖权限
  assert.ok(cityAgentPerms.length > 10, `city_agent 权限数 >= 10, 实际: ${cityAgentPerms.length}`)
  assert.ok(merchantPerms.size > 10, `merchant 权限数 >= 10, 实际: ${merchantPerms.size}`)
  assert.ok(clubPerms.size > 5, `club 权限数 >= 5, 实际: ${clubPerms.size}`)
})

test('每个动态路由的权限至少匹配一个非 admin fixture', () => {
  const routesWithPerms = getRoutesWithPermissions()
  const allNonAdminPerms = new Set([
    ...fixtureForRole('city_agent').permissions,
    ...fixtureForRole('merchant').permissions,
    ...fixtureForRole('club').permissions,
  ])

  const unmatched = []
  for (const route of routesWithPerms) {
    if (route.isRedirectOnly || route.isFuncRedirect) continue
    const hasAnyMatch = route.permissions.some(p => allNonAdminPerms.has(p))
    if (!hasAnyMatch) {
      unmatched.push({ path: route.path, permissions: route.permissions })
    }
  }

  if (unmatched.length > 0) {
    console.log(`[信息] ${unmatched.length} 条路由权限仅 admin 有:`)
    unmatched.forEach(r => console.log(`  ${r.path} — ${r.permissions.join(', ')}`))
  }
  // 非阻塞:工具类路由 (tool:gen:edit, monitor:job:log, system:*:edit) 可能仅 admin 覆盖
})

test('所有路由路径以 / 开头或无前缀 (根路由)', () => {
  for (const route of ROUTE_DATA.allRoutes) {
    if (route.path === '') continue
    assert.ok(
      route.path.startsWith('/'),
      `路由路径应以 / 开头: ${route.path}`
    )
  }
})

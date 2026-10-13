/**
 * 四角色 mock 登录态与权限 Fixture
 *
 * 后台管理系统的四个角色:
 *   - 系统管理员 (admin)
 *   - 城市代理 (city_agent)
 *   - 商家 (merchant)
 *   - 俱乐部 (club)
 *
 * 每一个 fixture 提供:
 *   - token: mock JWT token 字符串 (Admin-Token)
 *   - user: 用户基本信息 (userName, nickName, userId, deptId)
 *   - roles: 角色数组 (如 ['admin'])
 *   - permissions: 权限字符串数组 (如 ['system:user:list', ...])
 *   - getInfoResponse: 模拟 /getInfo 接口返回
 *   - cookieAuth: 可注入浏览器的 Cookie 对象
 */

/**
 * 权限清单 — 按业务域分组
 * 从 dynamicRoutes 提取 + sys_menu 常见权限枚举
 */
const ALL_PERMISSIONS = {
  system: [
    'system:user:list', 'system:user:query', 'system:user:add', 'system:user:edit', 'system:user:remove', 'system:user:export', 'system:user:import', 'system:user:resetPwd',
    'system:role:list', 'system:role:query', 'system:role:add', 'system:role:edit', 'system:role:remove',
    'system:menu:list', 'system:menu:query', 'system:menu:add', 'system:menu:edit', 'system:menu:remove',
    'system:dept:list', 'system:dept:query', 'system:dept:add', 'system:dept:edit', 'system:dept:remove',
    'system:post:list', 'system:post:query', 'system:post:add', 'system:post:edit', 'system:post:remove',
    'system:dict:list', 'system:dict:query', 'system:dict:add', 'system:dict:edit', 'system:dict:remove',
    'system:notice:list', 'system:notice:query', 'system:notice:add', 'system:notice:edit', 'system:notice:remove',
    'system:config:list', 'system:config:query', 'system:config:add', 'system:config:edit', 'system:config:remove',
    'system:operlog:list', 'system:logininfor:list',
    'system:oss:list',
  ],
  monitor: [
    'monitor:job:list', 'monitor:job:query', 'monitor:job:add', 'monitor:job:edit', 'monitor:job:remove', 'monitor:job:changeStatus', 'monitor:job:export',
    'monitor:online:list', 'monitor:online:batchLogout', 'monitor:online:forceLogout',
    'monitor:druid:list',
    'monitor:server:list',
    'monitor:cache:list', 'monitor:cache:getCacheValue', 'monitor:cache:getCacheNames', 'monitor:cache:getCacheKeys', 'monitor:cache:clearCacheName', 'monitor:cache:clearCacheKey',
  ],
  tool: [
    'tool:gen:list', 'tool:gen:query', 'tool:gen:add', 'tool:gen:edit', 'tool:gen:remove', 'tool:gen:import',
    'tool:form:list', 'tool:form:query', 'tool:form:add', 'tool:form:edit', 'tool:form:remove',
  ],
  business: [
    'business:activity:list', 'business:activity:query', 'business:activity:add', 'business:activity:edit', 'business:activity:remove',
    'business:topic:list', 'business:topic:query', 'business:topic:add', 'business:topic:edit', 'business:topic:remove',
    'business:order:list', 'business:order:query',
    'business:product:list', 'business:product:query',
    'business:coupon:list', 'business:coupon:query', 'business:coupon:add', 'business:coupon:edit', 'business:coupon:remove',
    'business:couponhistory:list',
    'business:member:list', 'business:member:query', 'business:member:edit',
    'business:memberbalancedetail:list',
    'business:payablebatch:list',
    'business:cashwithdraw:list',
    'business:chapterOffer:list', 'business:chapterOffer:query',
    'business:memberwithdrawal:list',
    'business:refund:list', 'business:refund:review',
    'business:templatelibrary:list', 'business:templatelibrary:query',
    'business:membertemplate:list', 'business:membertemplate:query',
    'business:pointsetting:list', 'business:pointsetting:query', 'business:pointsetting:edit',
    'business:creator:list', 'business:creator:query', 'business:creator:edit',
    'business:ailog:list', 'business:ailog:query',
    'business:userpermission:list', 'business:userpermission:query', 'business:userpermission:edit',
    'business:betagame:list', 'business:betagame:query', 'business:betagame:add', 'business:betagame:edit',
    'business:clubRun:list', 'business:clubRun:query',
    'business:im:list', 'business:im:query',
    'business:infomation:list',
    'business:gift:list', 'business:gift:query', 'business:gift:add', 'business:gift:edit',
    'business:verifyEvidence:list', 'business:verifyEvidence:query',
    'business:roamPoi:list', 'business:roamPoi:query', 'business:roamPoi:edit',
    'business:officialevent:list', 'business:officialevent:query', 'business:officialevent:add', 'business:officialevent:edit',
    'business:officialevent:settle', 'business:officialevent:authorize', 'business:officialevent:notify', 'business:officialevent:audit',
    'business:coopcomplaint:list', 'business:coopcomplaint:query',
    'business:audit:list', 'business:audit:review',
    'business:settlementrule:list', 'business:settlementrule:edit',
    'business:setting:list', 'business:setting:query', 'business:setting:edit',
    'business:settlement:list', 'business:settlement:query',
  ],
}

/**
 * 系统管理员 — 全部权限
 */
const SUPER_ADMIN = {
  id: 'admin-fixture',
  roleKey: 'admin',
  roleName: '系统管理员',
  defaultPermissions: '*:*:*',
}

/**
 * 城市代理 — 可查看系统/监控模块,部分业务模块只读
 */
const CITY_AGENT = {
  id: 'city_agent-fixture',
  roleKey: 'city_agent',
  roleName: '城市代理',
  defaultPermissions: [
    ...ALL_PERMISSIONS.system,
    ...ALL_PERMISSIONS.monitor,
    'business:activity:list', 'business:activity:query',
    'business:topic:list', 'business:topic:query',
    'business:order:list', 'business:order:query',
    'business:member:list', 'business:member:query',
    'business:clubRun:list', 'business:clubRun:query',
    'business:roamPoi:list', 'business:roamPoi:query',
    'business:officialevent:list', 'business:officialevent:query',
    'business:coopcomplaint:list', 'business:coopcomplaint:query',
    'business:ailog:list', 'business:ailog:query',
    'business:audit:list', 'business:audit:review',
    'business:userpermission:list',
    'business:settlementrule:list',
    'business:creator:list', 'business:creator:query',
  ],
}

/**
 * 商家 — 订单/商品/优惠券/财务
 */
const MERCHANT = {
  id: 'merchant-fixture',
  roleKey: 'merchant',
  roleName: '商家',
  defaultPermissions: [
    'business:activity:list', 'business:activity:query',
    'business:topic:list', 'business:topic:query',
    'business:order:list', 'business:order:query',
    'business:product:list', 'business:product:query',
    'business:coupon:list', 'business:coupon:query',
    'business:couponhistory:list',
    'business:member:list', 'business:member:query',
    'business:memberbalancedetail:list',
    'business:payablebatch:list',
    'business:cashwithdraw:list',
    'business:chapterOffer:list', 'business:chapterOffer:query',
    'business:memberwithdrawal:list',
    'business:refund:list', 'business:refund:review',
    'business:templatelibrary:list', 'business:templatelibrary:query',
    'business:membertemplate:list', 'business:membertemplate:query',
    'business:pointsetting:list', 'business:pointsetting:query',
    'business:betagame:list', 'business:betagame:query',
    'business:im:list',
    'business:gift:list', 'business:gift:query',
    'business:verifyEvidence:list', 'business:verifyEvidence:query',
    'business:coopcomplaint:list', 'business:coopcomplaint:query',
  ],
}

/**
 * 俱乐部 — 带队/活动/主题/POI
 */
const CLUB = {
  id: 'club-fixture',
  roleKey: 'club',
  roleName: '俱乐部',
  defaultPermissions: [
    'business:activity:list', 'business:activity:query',
    'business:topic:list', 'business:topic:query',
    'business:order:list', 'business:order:query',
    'business:member:list', 'business:member:query',
    'business:clubRun:list', 'business:clubRun:query',
    'business:roamPoi:list', 'business:roamPoi:query',
    'business:officialevent:list', 'business:officialevent:query',
    'business:chapterOffer:list', 'business:chapterOffer:query',
    'business:coopcomplaint:list', 'business:coopcomplaint:query',
  ],
}

const ROLE_DEFS = [SUPER_ADMIN, CITY_AGENT, MERCHANT, CLUB]

function buildGetInfoResponse(roleDef) {
  return {
    code: 200,
    msg: '操作成功',
    user: {
      userId: 1,
      userName: roleDef.roleName + '_测试',
      nickName: roleDef.roleName,
      deptId: 100,
      deptName: '总部',
      avatar: '',
      phonenumber: '13800138000',
      status: '0',
    },
    roles: [roleDef.roleKey],
    permissions: [roleDef.defaultPermissions].flat(),
    isDefaultModifyPwd: false,
    isPasswordExpired: false,
  }
}

function buildCookieAuth(mockToken) {
  return {
    name: 'Admin-Token',
    value: mockToken,
    domain: 'localhost',
    path: '/',
    httpOnly: false,
    secure: false,
    sameSite: 'Lax',
  }
}

function buildFixture(roleDef) {
  const mockToken = `eyJhbGciOiJIUzUxMiJ9.mock_${roleDef.roleKey}_fixture_token`
  const getInfoResponse = buildGetInfoResponse(roleDef)
  return {
    id: roleDef.id,
    roleKey: roleDef.roleKey,
    roleName: roleDef.roleName,
    token: mockToken,
    user: getInfoResponse.user,
    roles: getInfoResponse.roles,
    permissions: getInfoResponse.permissions,
    getInfoResponse,
    cookieAuth: buildCookieAuth(mockToken),
  }
}

const FIXTURES = {
  /** 系统管理员 — 全权限,admin 角色 */
  superAdmin: buildFixture(SUPER_ADMIN),
  /** 城市代理 — 系统/监控 + 部分业务只读 */
  cityAgent: buildFixture(CITY_AGENT),
  /** 商家 — 订单/商品/优惠券/财务 */
  merchant: buildFixture(MERCHANT),
  /** 俱乐部 — 带队/活动/主题/POI */
  club: buildFixture(CLUB),
}

const ALL_ROLES = Object.values(FIXTURES)

/**
 * 根据 roleKey 查找 fixture
 * @param {'admin'|'city_agent'|'merchant'|'club'} roleKey
 */
function fixtureForRole(roleKey) {
  return FIXTURES[Object.keys(FIXTURES).find(k => FIXTURES[k].roleKey === roleKey)]
}

/**
 * 获取某个 fixture 拥有的所有权限字符串去重数组
 */
function getPermissionsForRole(roleKey) {
  const f = fixtureForRole(roleKey)
  if (!f) return []
  return [...new Set(f.permissions)]
}

/**
 * 生成可用于 page.evaluate() 注入 store 的初始 state 对象
 */
function buildStoreState(roleKey) {
  const f = fixtureForRole(roleKey)
  if (!f) return null
  return {
    token: f.token,
    name: f.user.userName,
    nickName: f.user.nickName,
    roles: f.roles,
    permissions: f.permissions,
  }
}

module.exports = {
  ROLE_DEFS,
  FIXTURES,
  ALL_ROLES,
  ALL_PERMISSIONS,
  fixtureForRole,
  getPermissionsForRole,
  buildStoreState,
}

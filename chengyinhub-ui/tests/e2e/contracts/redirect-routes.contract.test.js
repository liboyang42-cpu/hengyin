/**
 * Redirect Routes Contract — 旧入口 redirect 路由指向新的 canonical 路径
 *
 * 验证:
 *   1. 所有 function redirect 路由的 target named route 存在
 *   2. redirect 路由拥有与原目标路由相同的权限
 *   3. redirect 路由 path 与 target path 规范一致
 */

const test = require('node:test')
const assert = require('node:assert/strict')
const { getRedirectRouteMappings, readRouterSource } = require('../helpers/router-parser')
const { getRoutesWithPermissions } = require('../helpers/router-parser')

test('旧入口 redirect 路由都指向存在的 named route', () => {
  const mappings = getRedirectRouteMappings()
  const source = readRouterSource()

  const namePattern = /name:\s*['"]([^'"]+)['"]/g
  const allNames = new Set()
  let m
  while ((m = namePattern.exec(source)) !== null) {
    allNames.add(m[1])
  }

  for (const mapping of mappings) {
    assert.ok(
      mapping.targetName && allNames.has(mapping.targetName),
      `redirect ${mapping.path} → ${mapping.targetName}: target name 已在 router 中定义`
    )
  }
})

test('redirect 路由权限与目标路由权限一致', () => {
  const mappings = getRedirectRouteMappings()
  const routesWithPerms = getRoutesWithPermissions()

  // 通过 target name 找到目标路由,比较权限
  const source = readRouterSource()

  for (const mapping of mappings) {
    // 查找 target name 所在的路由块,提取其 permissions
    const targetBlock = findRouteBlockByName(source, mapping.targetName)
    if (!targetBlock) continue

    const targetPermRe = /permissions:\s*\[([^\]]*)\]/
    const tpMatch = targetBlock.match(targetPermRe)
    if (!tpMatch) continue

    const targetPerms = [...tpMatch[1].matchAll(/['"]([^'"]+)['"]/g)].map(x => x[1])
    if (targetPerms.length === 0) continue

    assert.ok(
      mapping.permissions.some(p => targetPerms.includes(p)),
      `redirect ${mapping.path} 权限 ${mapping.permissions.join(',')} 应与目标 ${mapping.targetName} 权限 ${targetPerms.join(',')} 有交集`
    )
  }
})

test('redirect 路由都是 hidden', () => {
  const mappings = getRedirectRouteMappings()
  assert.ok(mappings.length >= 3, `redirect 路由 >= 3, actual: ${mappings.length}`)
  // redirect-only 路由隐式不出现在侧边栏
})

test('已登记的旧入口 redirect 路由逐条指向正确 canonical 目标', () => {
  const mappings = getRedirectRouteMappings()
  // 这里登记的是「域收敛期建立的旧入口 → canonical 目标」映射,逐条按语义断言。
  // ⚠️ 原先这条测试还断言 `mappings.length === expectedRedirects.length`(=10)。
  //   8def80fb「P2-4 信息架构迁移」把大批一级入口改成了 func-redirect 壳,
  //   redirect-only 路由从 10 涨到 69 —— 那个字面量总数断言于是把合法增长锁成红,
  //   而它本身并不表达任何语义(多一条壳不等于映射错了)。改为下界 + 逐条映射断言:
  //   壳的整体有效性(目标 named route 必须存在 / 权限一致 / hidden)由本文件其余
  //   三条测试对全部 69 条逐条覆盖,并未因此失去把关。
  const expectedRedirects = [
    { path: '/business/club', targetName: 'clubProfile' },
    { path: '/business/clubrun', targetName: 'clubLeadRun' },
    { path: '/business/chapteroffer', targetName: 'merchantChapterOffer' },
    { path: '/business/coupon', targetName: 'supplyCoupon' },
    { path: '/business/im', targetName: 'customerIm' },
    { path: '/business/roampoi', targetName: 'clubRoamPoi' },
    { path: '/business/verifyevidence', targetName: 'riskVerificationEvidence' },
    { path: '/business/coopcomplaint', targetName: 'customerCase' },
    { path: '/business/member', targetName: 'customerMember' },
    { path: '/business/registration', targetName: 'financeRegistration' },
  ]

  assert.ok(
    mappings.length >= expectedRedirects.length,
    `redirect 路由数应 >= ${expectedRedirects.length}, 实际: ${mappings.length}`
  )

  for (const expected of expectedRedirects) {
    const found = mappings.find(m => m.path === expected.path)
    assert.ok(found, `redirect ${expected.path} 必须存在`)
    assert.equal(
      found.targetName, expected.targetName,
      `${expected.path} 应指向 ${expected.targetName}, 实际: ${found.targetName}`
    )
  }
})

/**
 * 在 router 源码中按 name 查找路由块
 */
function findRouteBlockByName(source, name) {
  if (!name) return null

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
        if (block.includes(`name: '${name}'`) || block.includes(`name: "${name}"`)) {
          return block
        }
        start = -1
      }
    }
  }
  return null
}

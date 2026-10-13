/**
 * Router Parser — 从 router/index.js 源码提取路由条目结构化数据
 *
 * 不引入 JS 解析器,遵循仓库现有 tests/unit/*.test.js 的文本模式匹配风格。
 * 产出: 所有顶层路由 + 子路由的 path / hidden / permissions / redirect / component 信息。
 */

const fs = require('node:fs')
const path = require('node:path')

const ROUTER_FILE = path.join(__dirname, '../../../src/router/index.js')

let _cache = null

/**
 * 读取并缓存 router 源码
 */
function readRouterSource() {
  if (!_cache) {
    _cache = fs.readFileSync(ROUTER_FILE, 'utf8')
  }
  return _cache
}

/**
 * 提取数组中每个顶层对象的范围 (start, end 字符位置)
 * 适用于 `export const XXX = [ ... ]` 形式
 */
function extractTopLevelEntries(arrayBlock) {
  const entries = []
  let depth = 0
  let start = -1
  for (let i = 0; i < arrayBlock.length; i++) {
    const ch = arrayBlock[i]
    if (ch === '{') {
      if (depth === 0) start = i
      depth++
    } else if (ch === '}') {
      depth--
      if (depth === 0 && start >= 0) {
        entries.push(arrayBlock.slice(start, i + 1))
        start = -1
      }
    }
  }
  return entries
}

/**
 * 从路由文本块中提取指定 key 的字符串值 (单行匹配)
 */
function extractStrValue(block, key) {
  const re = new RegExp(`${key}:\\s*['"]([^'"]*)['"]`)
  const m = block.match(re)
  return m ? m[1] : null
}

/**
 * 从路由文本块中提取指定 key 的布尔值
 */
function extractBoolValue(block, key) {
  const re = new RegExp(`${key}:\\s*(true|false)`)
  const m = block.match(re)
  return m ? m[1] === 'true' : null
}

/**
 * 从路由文本块中提取 permissions 数组
 */
function extractPermissions(block) {
  const re = /permissions:\s*\[([^\]]*)\]/
  const m = block.match(re)
  if (!m) return null
  return [...m[1].matchAll(/['"]([^'"]+)['"]/g)].map(x => x[1])
}

/**
 * 从路由文本块中提取 roles 数组
 */
function extractRoles(block) {
  const re = /roles:\s*\[([^\]]*)\]/
  const m = block.match(re)
  if (!m) return null
  return [...m[1].matchAll(/['"]([^'"]+)['"]/g)].map(x => x[1])
}

/**
 * 检测是否是纯 redirect 路由 (无 component, 无 children)
 */
function isRedirectOnly(block) {
  const hasComponent = /component:/.test(block)
  const hasRedirect = /redirect:/.test(block)
  const hasChildren = /children:\s*\[/.test(block)
  return hasRedirect && !hasComponent && !hasChildren
}

/**
 * 检测是否是 function redirect
 */
function isFuncRedirect(block) {
  return /redirect:\s*(\(|to\s*=>|function)/.test(block)
}

/**
 * 提取 children 数组文本块
 */
function extractChildrenBlock(block) {
  const childrenMatch = block.match(/children:\s*(\[[\s\S]*?\n\s*\]\s*)/)
  return childrenMatch ? childrenMatch[1] : null
}

/**
 * 从单个路由条目文本块解析结构化信息
 */
function parseRouteEntry(entryText, parentPath) {
  const routePath = extractStrValue(entryText, 'path')
  const fullPath = parentPath
    ? parentPath + '/' + (routePath || '').replace(/^\//, '')
    : (routePath || '')

  const childrenBlock = extractChildrenBlock(entryText)
  const children = childrenBlock
    ? extractTopLevelEntries(childrenBlock).map(c => parseRouteEntry(c, fullPath))
    : []

  return {
    path: fullPath,
    rawPath: routePath,
    hidden: extractBoolValue(entryText, 'hidden'),
    permissions: extractPermissions(entryText),
    roles: extractRoles(entryText),
    isRedirectOnly: isRedirectOnly(entryText),
    isFuncRedirect: isFuncRedirect(entryText),
    redirectTarget: extractStrValue(entryText, 'redirect'),
    hasChildren: children.length > 0,
    children,
  }
}

/**
 * 提取命名导出数组的全部内容
 */
function extractExportArray(source, exportName) {
  const re = new RegExp(`export const ${exportName}\\s*=\\s*\\[([\\s\\S]*?)\\n\\]`, 'm')
  const m = source.match(re)
  if (!m) return null
  return `[${m[1]}\n]`
}

/**
 * 解析所有路由,返回 { constantRoutes, dynamicRoutes, allLeafPaths }
 */
function parseAllRoutes() {
  const source = readRouterSource()

  const constantBlock = extractExportArray(source, 'constantRoutes')
  const dynamicBlock = extractExportArray(source, 'dynamicRoutes')

  const constantEntries = constantBlock ? extractTopLevelEntries(constantBlock) : []
  const dynamicEntries = dynamicBlock ? extractTopLevelEntries(dynamicBlock) : []

  const constantRoutes = constantEntries.map(e => parseRouteEntry(e, ''))
  const dynamicRoutes = dynamicEntries.map(e => parseRouteEntry(e, ''))

  /**
   * 递归收集所有叶子路径 (带 children 的路由也会包含)
   */
  function collectLeafPaths(routes, acc) {
    for (const r of routes) {
      acc.push(r.path)
      if (r.children.length > 0) {
        for (const c of r.children) {
          acc.push(c.path)
        }
      }
    }
    return acc
  }

  const allPaths = []
  collectLeafPaths(constantRoutes, allPaths)
  collectLeafPaths(dynamicRoutes, allPaths)

  return {
    constantRoutes,
    dynamicRoutes,
    allRoutes: [...constantRoutes, ...dynamicRoutes],
    allLeafPaths: allPaths,
    constantEntriesCount: constantEntries.length,
    dynamicEntriesCount: dynamicEntries.length,
    totalPathsCount: allPaths.length,
  }
}

/**
 * 获取所有 dynamic route 中声明了 permissions 的路由
 */
function getRoutesWithPermissions() {
  const { dynamicRoutes } = parseAllRoutes()
  return dynamicRoutes.filter(r => r.permissions && r.permissions.length > 0)
}

/**
 * 获取所有 hidden 路由
 */
function getHiddenRoutes() {
  const { allRoutes } = parseAllRoutes()
  return allRoutes.filter(r => r.hidden === true)
}

/**
 * 获取所有纯 redirect 路由 (无组件,仅重定向)
 */
function getRedirectOnlyRoutes() {
  const { dynamicRoutes } = parseAllRoutes()
  return dynamicRoutes.filter(r => r.isRedirectOnly)
}

/**
 * 获取所有非 hidden 路由 (应该在侧边栏可见)
 */
function getVisibleRoutes() {
  const { allRoutes } = parseAllRoutes()
  return allRoutes.filter(r => r.hidden === false)
}

/**
 * 收集所有动态路由中引用的 permission 字符串 (去重)
 */
function collectAllPermissionStrings() {
  const routes = getRoutesWithPermissions()
  const perms = new Set()
  for (const r of routes) {
    for (const p of r.permissions) {
      perms.add(p)
    }
  }
  return [...perms].sort()
}

/**
 * 提取 redirect 路由的 name target
 */
function extractRedirectNameTarget(block) {
  const m = block.match(/name:\s*['"]([^'"]+)['"]/)
  return m ? m[1] : null
}

/**
 * 获取 function redirect 路由及其目标 named route
 */
function getRedirectRouteMappings() {
  const source = readRouterSource()
  const dynamicBlock = extractExportArray(source, 'dynamicRoutes')
  const entries = dynamicBlock ? extractTopLevelEntries(dynamicBlock) : []
  const redirectRoutes = entries.filter(isRedirectOnly)
  return redirectRoutes.map(block => ({
    path: extractStrValue(block, 'path'),
    targetName: extractRedirectNameTarget(block),
    permissions: extractPermissions(block),
    isFuncRedirect: isFuncRedirect(block),
  }))
}

module.exports = {
  parseAllRoutes,
  getRoutesWithPermissions,
  getHiddenRoutes,
  getRedirectOnlyRoutes,
  getVisibleRoutes,
  collectAllPermissionStrings,
  getRedirectRouteMappings,
  readRouterSource,
}

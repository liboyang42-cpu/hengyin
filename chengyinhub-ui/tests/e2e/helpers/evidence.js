/**
 * 证据格式规范 — screenshot / console / network
 *
 * 本模块定义 E2E 测试证据产出的命名、存储、元数据格式。
 * 这是骨架约定,后续 P2-2/P2-3 补真实浏览器断言时统一遵循此格式。
 */

const path = require('node:path')

const EVIDENCE_DIR = path.join(__dirname, '../evidence')

/**
 * 证据文件命名模板
 */
const NAMING = {
  /** 页面截图: {roleKey}_{pageName}_{descriptor}.png */
  screenshot(roleKey, pageName, descriptor) {
    return `${roleKey}_${pageName}_${descriptor}.png`
  },
  /** Console 日志: {roleKey}_{pageName}_console.json */
  console(roleKey, pageName) {
    return `${roleKey}_${pageName}_console.json`
  },
  /** Network HAR: {roleKey}_{pageName}_network.json */
  network(roleKey, pageName) {
    return `${roleKey}_${pageName}_network.json`
  },
  /** 差异对比截图: {roleKey}_{pageName}_diff.png */
  diff(roleKey, pageName) {
    return `${roleKey}_${pageName}_diff.png`
  },
}

/**
 * 标准测试证据路径
 * @param {'screenshot'|'console'|'network'|'diff'} type
 * @param {'admin'|'city_agent'|'merchant'|'club'} roleKey
 * @param {string} pageName
 * @param {string} [descriptor]
 */
function evidencePath(type, roleKey, pageName, descriptor) {
  const filename = NAMING[type](roleKey, pageName, descriptor)
  return path.join(EVIDENCE_DIR, roleKey, pageName, filename)
}

/**
 * Console 条目格式定义
 */
function consoleEntry(type, text, location) {
  return {
    type,      // 'log' | 'warn' | 'error' | 'info'
    text,
    location,  // { url, lineNumber, columnNumber }
    timestamp: new Date().toISOString(),
  }
}

/**
 * Network 请求条目格式定义
 */
function networkEntry(method, url, status, duration, requestHeaders, responseHeaders, body) {
  return {
    method,
    url,
    status,
    duration,       // ms
    requestHeaders,
    responseHeaders,
    body: body ? body.slice(0, 2000) : null,  // 截断长响应体
    timestamp: new Date().toISOString(),
  }
}

/**
 * 证据元数据 — 每个证据文件带的 JSON sidecar
 */
function evidenceMeta(roleKey, pageName, route, timestamp) {
  return {
    evidenceVersion: '1.0',
    roleKey,
    pageName,
    route,
    timestamp: timestamp || new Date().toISOString(),
    framework: 'playwright',
    note: 'E2E evidence — verify against business requirements',
  }
}

module.exports = {
  EVIDENCE_DIR,
  NAMING,
  evidencePath,
  consoleEntry,
  networkEntry,
  evidenceMeta,
}

/** Pure, fail-closed browser fixture policy. This file never makes a request. */
const ORIGIN = 'http://127.0.0.1:4173'
const THEME_CSS_PATH = '/styles/theme-chalk/index.css'
const SPA_PATHS = new Set(['/audit-center/audit', '/audit-center/browser-away', '/p3-platform/audit', '/401', '/404', '/index', '/login'])
const ASSET_TYPES = {
  script: /\.js$/,
  stylesheet: /\.css$/,
  image: /\.(?:svg|png|jpe?g|gif|ico|webp|avif)$/,
  font: /\.(?:woff2?|ttf|otf|eot)$/
}
function safePathname(raw) {
  // Validate before WHATWG URL parsing can normalize dot segments/backslashes.
  if (typeof raw !== 'string' || /[\\\x00-\x20\x7f]/.test(raw)) return false
  const pathname = raw.split(/[?#]/, 1)[0].replace(/^https?:\/\/[^/]+/, '')
  if (/%(?:2e|2f|5c|25|00)/i.test(pathname) || pathname.split('/').some(part => part === '.' || part === '..')) return false
  try { decodeURIComponent(pathname) } catch (_) { return false }
  return true
}
function classifyRequest({ url, method, resourceType, redirectedFrom = false, serviceWorker = false }) {
  const block = reason => ({ kind: 'block', reason })
  if (redirectedFrom) return block('redirect chain')
  if (serviceWorker) return block('service worker')
  if (!safePathname(url)) return block('unsafe URL')
  let parsed
  try { parsed = new URL(url) } catch (_) { return block('invalid URL') }
  if (parsed.origin !== ORIGIN || parsed.username || parsed.password || parsed.hash) return block('external or noncanonical origin')
  const pathname = parsed.pathname
  if (pathname.startsWith('/prod-api/')) {
    if (!['xhr', 'fetch'].includes(resourceType)) return block('API resource type')
    const endpoint = pathname.slice('/prod-api'.length)
    const read = ['/getInfo', '/getRouters', '/captchaImage', '/business/audit/list'].includes(endpoint)
      || /^\/business\/audit\/[1-9]\d*\/(?:detail|candidate-preview)$/.test(endpoint)
    const write = ['/business/audit/review', '/logout'].includes(endpoint)
    if (method === 'GET' && read || method === 'POST' && write) return { kind: 'api', endpoint }
    return block('unknown API or method')
  }
  if (method !== 'GET') return block('non-GET bundle request')
  if (resourceType === 'document' && (pathname === '/' || SPA_PATHS.has(pathname))) return { kind: 'document' }
  if (parsed.search) return block('asset query')
  if (pathname === THEME_CSS_PATH && ['xhr', 'stylesheet'].includes(resourceType)) return { kind: 'asset' }
  if (pathname === '/favicon.ico' && ['image', 'other'].includes(resourceType)) return { kind: 'asset' }
  if (/^\/static\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_.-]+$/.test(pathname)
      && !pathname.split('/').some(part => part.startsWith('.'))
      && ASSET_TYPES[resourceType] && ASSET_TYPES[resourceType].test(pathname)) return { kind: 'asset' }
  return block('unknown URL or resource type')
}
module.exports = { ORIGIN, SPA_PATHS, THEME_CSS_PATH, safePathname, classifyRequest }

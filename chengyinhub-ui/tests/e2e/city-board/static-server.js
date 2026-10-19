/** Immutable loopback snapshot of a verified isolated test bundle. Never proxies or redirects. */
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const { SPA_PATHS, THEME_CSS_PATH, safePathname } = require('./network-policy')
const { verifyReceipt, readRegular, digest } = require('./bundle-provenance')
const HOST = '127.0.0.1'
const PORT = 4173
const UI_ROOT = path.resolve(__dirname, '../../..')
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf', '.eot': 'application/vnd.ms-fontobject', '.gif': 'image/gif', '.webp': 'image/webp', '.avif': 'image/avif' }
function resolveRequest(rawUrl, method = 'GET') {
  if (!['GET', 'HEAD'].includes(method)) return { status: 405 }
  if (typeof rawUrl !== 'string' || !/^\/(?!\/)/.test(rawUrl) || rawUrl.includes('#')) return { status: 400 }
  try { decodeURIComponent(rawUrl) } catch (_) { return { status: 400 } }
  if (!safePathname(rawUrl)) return { status: 403 }
  const url = new URL(rawUrl, `http://${HOST}:${PORT}`)
  const pathname = decodeURIComponent(url.pathname)
  if (pathname === '/__city_board_browser_health' && !url.search) return { status: 200, health: true }
  if (pathname === '/' || SPA_PATHS.has(pathname)) return { status: 200, file: 'index.html' }
  if (url.search) return { status: 404 }
  if (pathname === THEME_CSS_PATH) return { status: 200, file: THEME_CSS_PATH.slice(1) }
  if (pathname === '/favicon.ico') return { status: 200, file: 'favicon.ico' }
  if (/^\/static\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_.-]+$/.test(pathname)
      && !pathname.split('/').some(part => part.startsWith('.')) && MIME[path.extname(pathname)]) return { status: 200, file: pathname.slice(1) }
  return { status: 404 }
}
function bundleScripts(html) {
  return [...html.matchAll(/<script\b[^>]*\bsrc=(?:"([^"]+)"|'([^']+)'|([^\s>]+))/gi)]
    .map(match => match[1] || match[2] || match[3])
    .filter(src => /^\/static\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_.-]+\.js$/.test(src) && safePathname(src))
}
function loadProductionSnapshot(uiRoot = UI_ROOT) {
  const { dist, outputs, receipt } = verifyReceipt(uiRoot)
  const snapshot = new Map()
  let total = 0
  for (const [relative, expected] of Object.entries(outputs)) {
    if (relative !== 'index.html' && relative !== 'favicon.ico' && relative !== THEME_CSS_PATH.slice(1) && !relative.startsWith('static/')) continue
    const bytes = readRegular(dist, relative)
    if (digest(bytes) !== expected.sha256 || bytes.length !== expected.bytes) throw new Error('Production bundle changed while loading')
    total += bytes.length
    if (total > 256 * 1024 * 1024) throw new Error('Production bundle exceeds static-server memory limit')
    snapshot.set(relative, bytes)
  }
  if (!snapshot.has(THEME_CSS_PATH.slice(1))) throw new Error('Missing production theme stylesheet')
  const scripts = bundleScripts((snapshot.get('index.html') || Buffer.alloc(0)).toString('utf8'))
  if (!scripts.length || scripts.some(src => !snapshot.has(src.slice(1)))) throw new Error('Missing production index scripts')
  return { snapshot, receipt }
}
function createRequestHandler(snapshot) {
  return (req, res) => {
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('X-Content-Type-Options', 'nosniff')
    if (req.headers.host !== `${HOST}:${PORT}`) { res.writeHead(400); res.end(); return }
    const result = resolveRequest(req.url, req.method)
    if (result.health) { res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end(req.method === 'HEAD' ? undefined : 'city-board-isolated-bundle-only'); return }
    const bytes = result.file && snapshot.get(result.file)
    if (!bytes) { res.writeHead(result.file ? 404 : result.status, result.status === 405 ? { Allow: 'GET, HEAD' } : {}); res.end(); return }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(result.file)], 'Content-Length': bytes.length })
    res.end(req.method === 'HEAD' ? undefined : bytes)
  }
}
async function startServer() {
  const { snapshot } = loadProductionSnapshot()
  const server = http.createServer({ requestTimeout: 10000, headersTimeout: 5000, keepAliveTimeout: 1000 }, createRequestHandler(snapshot))
  server.on('clientError', (_, socket) => socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'))
  // No socket upgrade path and no upstream client exist.
  server.on('upgrade', (_, socket) => socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'))
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen({ port: PORT, host: HOST, exclusive: true }, resolve)
  })
  const parentPid = Number(process.env.CITY_BOARD_BROWSER_GATE_PARENT_PID)
  const watchdog = Number.isInteger(parentPid) && parentPid > 1 ? setInterval(() => {
    try { process.kill(parentPid, 0) } catch (_) { stop() }
  }, 1000) : undefined
  if (watchdog) watchdog.unref()
  const stop = () => {
    server.close(() => process.exit(0))
    server.closeAllConnections()
  }
  process.once('SIGTERM', stop)
  process.once('SIGINT', stop)
  server.once('close', () => { clearInterval(watchdog); process.removeListener('SIGTERM', stop); process.removeListener('SIGINT', stop) })
  console.log(`City board isolated bundle: http://${HOST}:${PORT}`)
  return server
}
if (require.main === module) startServer().catch(error => { console.error(`${error.message}\nRun the hosted city-board build.js after the ordinary production build.`); process.exitCode = 1 })
module.exports = { HOST, PORT, SPA_PATHS, resolveRequest, bundleScripts, loadProductionSnapshot, createRequestHandler, startServer }

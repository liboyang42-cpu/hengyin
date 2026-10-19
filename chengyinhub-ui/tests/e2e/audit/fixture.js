const { test: base, expect } = require('@playwright/test')
const { classifyRequest } = require('../audit-network-policy')
const { buttonByText } = require('./visible-controls')
const ORIGIN = 'http://127.0.0.1:4173'
const AUDIT_PATH = '/audit-center/audit'
const TOKEN_A = 'synthetic-audit-session-A-not-a-real-credential'
const TOKEN_B = 'synthetic-audit-session-B-not-a-real-credential'
const PERMISSIONS = ['business:audit:list', 'business:audit:query', 'business:audit:review']

function candidate(overrides = {}, bindingOverrides = {}) {
  const task = { id: 8101, bizId: 9101, submitterId: 7101, bizType: 'CONTENT_CAPTURE_REVIEW',
    sourceType: 'content_capture_review_v1', status: 0, version: 3,
    submittedAt: '2026-01-01 10:00:00', ...overrides }
  task.snapshotJson = JSON.stringify({ schemaVersion: 1, contract: 'content_capture_review_v1',
    candidateContract: 'questify.content-capture-candidate.v1', canonicalizationVersion: 'questify.canonical-content-json.v1',
    completeRelease: false, reviewScope: 'INCOMPLETE_CANDIDATE_ONLY', snapshotId: 6101,
    topicId: task.bizId, submitterMemberId: task.submitterId, sourceConfigVersion: 12,
    contentHash: 'a'.repeat(64), unknownBody: 'SYNTHETIC_BODY_MUST_NOT_BE_RENDERED', ...bindingOverrides })
  return task
}

function preview(task = candidate(), overrides = {}) {
  const binding = JSON.parse(task.snapshotJson)
  return { contract: 'questify.content-capture-preview.v1', auditTaskId: String(task.id),
    auditTaskVersion: task.version, status: task.status, snapshotId: String(binding.snapshotId),
    topicId: String(task.bizId), sourceConfigVersion: String(binding.sourceConfigVersion), contentHash: binding.contentHash,
    textEncoding: 'HTML_ESCAPED_PLAIN_TEXT', reviewScope: 'INCOMPLETE_CANDIDATE_TEXT_ONLY',
    completeRelease: false, publicationEligible: false, previewComplete: false,
    entries: [{ path: 'nodes[0].name', text: '冻结合成节点' },
      { path: 'nodes[0].description', text: '冻结的合成内容；不是当前主题正文' }], ...overrides }
}

const test = base.extend({
  audit: async ({ page, context }, use, testInfo) => {
    const queues = { list: [], detail: [], review: [], logout: [], preview: [] }
    const held = new Set()
    const activeRoutes = new Set()
    let closing = false
    const state = {
      tasks: [candidate()], permissions: [...PERMISSIONS], calls: [], unexpected: [], pageErrors: [],
      callsOf(kind) { return this.calls.filter(call => call.kind === kind) },
      // Release controlled responses only after the assertion has observed the real request.
      holdNext(kind) {
        let seenResolve, releaseResolve
        const gate = { seen: new Promise(resolve => { seenResolve = resolve }),
          response: new Promise(resolve => { releaseResolve = resolve }),
          markSeen: seenResolve, release: releaseResolve }
        held.add(gate)
        queues[kind].push(gate)
        return gate
      }
    }
    const observePage = tab => tab.on('pageerror', error => state.pageErrors.push(String(error)))
    context.pages().forEach(observePage)
    context.on('page', observePage)
    context.on('serviceworker', worker => state.unexpected.push(`ServiceWorker ${worker.url()}`))
    context.on('response', response => {
      if (response.status() >= 300 && response.status() < 400) state.unexpected.push(`Redirect ${response.status()} ${response.url()}`)
    })
    await context.addCookies([{ name: 'Admin-Token', value: TOKEN_A, url: ORIGIN }])
    await context.routeWebSocket('**/*', socket => { state.unexpected.push(`WebSocket ${socket.url()}`); socket.close() })
    const handleRoute = async route => {
      const request = route.request()
      const url = new URL(request.url())
      const method = request.method()
      const failClosed = async () => {
        state.unexpected.push(`${method} ${url.origin}${url.pathname}`)
        await route.abort('blockedbyclient')
      }
      const classification = classifyRequest({ url: request.url(), method, resourceType: request.resourceType(),
        redirectedFrom: Boolean(request.redirectedFrom()), serviceWorker: Boolean(request.serviceWorker()) })
      if (classification.kind === 'block') return failClosed()
      const endpoint = classification.endpoint
      if (classification.kind === 'api') {
        const authorization = request.headers().authorization
        if (authorization && ![`Bearer ${TOKEN_A}`, `Bearer ${TOKEN_B}`].includes(authorization)) return failClosed()
        let kind, json
        if (method === 'GET' && endpoint === '/captchaImage') {
          kind = 'captcha'
          json = { code: 200, captchaEnabled: false }
        } else if (method === 'GET' && endpoint === '/getInfo') {
          kind = 'info'
          json = { code: 200, user: { userId: 7001, userName: 'synthetic_auditor', nickName: '合成审核员', avatar: '' },
            roles: ['synthetic_auditor'], permissions: state.permissions }
        } else if (method === 'GET' && endpoint === '/getRouters') {
          kind = 'routers'
          json = { code: 200, data: [{ path: '/audit-center', component: 'Layout', name: 'AuditCenter',
            meta: { title: '审核与风控', icon: 'form' }, children: [
              { path: 'audit', component: 'business/audit/index', name: 'Audit', meta: { title: '统一审核台', noCache: false } },
              // A real existing inert view exercises leaving the audit route; no cache is forced.
              { path: 'browser-away', component: 'error/401', name: 'BrowserAway', hidden: true, meta: { title: '无操作页面', noCache: true } }
            ] }] }
        } else if (method === 'GET' && endpoint === '/business/audit/list') {
          kind = 'list'
          const bizId = url.searchParams.get('bizId')
          const rows = state.tasks.filter(task => !bizId || String(task.bizId) === bizId)
          json = { code: 200, rows, total: rows.length }
        } else if (method === 'GET' && /^\/business\/audit\/\d+\/detail$/.test(endpoint)) {
          kind = 'detail'
          const id = Number(endpoint.split('/')[3])
          json = { code: 200, task: state.tasks.find(task => task.id === id), records: [], reportCount: 0 }
        } else if (method === 'GET' && /^\/business\/audit\/\d+\/candidate-preview$/.test(endpoint)) {
          kind = 'preview'
          const id = Number(endpoint.split('/')[3])
          json = { code: 200, data: preview(state.tasks.find(task => task.id === id)) }
        } else if (method === 'POST' && endpoint === '/business/audit/review') {
          kind = 'review'
          json = { code: 200 }
        } else if (method === 'POST' && endpoint === '/logout') {
          kind = 'logout'
          json = { code: 200 }
        } else return failClosed()
        const call = { kind, method, endpoint, query: url.search, rawBody: request.postData(),
          body: request.postData() ? request.postDataJSON() : null, authorization: request.headers().authorization }
        state.calls.push(call)
        const gate = queues[kind] && queues[kind].shift()
        if (gate) {
          gate.request = request
          gate.markSeen(call)
          const response = await gate.response
          if (response.abort) return route.abort(response.abort)
          return route.fulfill({ status: response.status || 200, json: response.json || json })
        }
        return route.fulfill({ status: 200, json })
      }
      // Fetch only a classified loopback bundle URL. Never let Chromium or the
      // API client automatically follow a Location header to another destination.
      if (classification.kind === 'document' || classification.kind === 'asset') {
        const response = await route.fetch({ maxRedirects: 0, timeout: 10000 })
        if (response.status() >= 300 && response.status() < 400) return failClosed()
        return route.fulfill({ response })
      }
      return failClosed()
    }
    await context.route('**/*', route => {
      const pending = handleRoute(route).catch(async error => {
        if (!closing) {
          state.unexpected.push(`Route handler failed: ${String(error)}`)
          await route.abort('blockedbyclient').catch(() => {})
        }
      }).finally(() => activeRoutes.delete(pending))
      activeRoutes.add(pending)
      return pending
    })
    try {
      await use(state)
    } finally {
      // Record failure pixels before closing the context. Release every held
      // response, including gates whose request never arrived, so cleanup cannot hang.
      if (testInfo.status !== testInfo.expectedStatus && !page.isClosed()) {
        await testInfo.attach('audit-failure-before-cleanup', {
          body: await page.screenshot({ fullPage: true, animations: 'disabled', timeout: 5000 }),
          contentType: 'image/png'
        }).catch(error => state.pageErrors.push(`Failure screenshot: ${String(error)}`))
      }
      closing = true
      for (const gate of held) gate.release({ abort: 'blockedbyclient' })
      await context.close()
      await Promise.all([...activeRoutes])
      await testInfo.attach('synthetic-network-ledger', {
        body: Buffer.from(JSON.stringify({ calls: state.calls, blocked: state.unexpected, pageErrors: state.pageErrors }, null, 2)),
        contentType: 'application/json'
      })
    }
    expect(state.unexpected, 'Unknown/external requests were blocked; add an explicit synthetic response instead of allowing networking').toEqual([])
    expect(state.pageErrors, 'The actual production application must not crash').toEqual([])
  }
})

async function openAudit(page, path = AUDIT_PATH) {
  await page.goto(path)
  await expect(page.getByRole('heading', { name: '统一审核台', exact: true })).toBeVisible()
  await expect(buttonByText(page, '查看证据')).toBeVisible()
}

function reviewDialog(page) { return page.getByRole('dialog', { name: '审核处理', exact: true }) }
function detailDrawer(page) { return page.locator('.el-drawer[aria-label="审核证据链"]') }

async function openReview(page, result = '通过') {
  // Element UI duplicates fixed table columns; choose the visible fixed-right action.
  const action = buttonByText(page.locator('.el-table__fixed-right'), result)
  await action.click()
  const dialog = reviewDialog(page)
  await expect(dialog).toBeVisible()
  await expect(buttonByText(dialog, '确认处理')).toBeEnabled()
  return dialog
}

const auditHistories = new WeakMap()

// Populate real browser history through an ordinary sidebar click, before a request
// is held. Back/Forward remain usable while an actual modal obscures the page.
async function prepareAuditHistory(page, previousPath) {
  await page.goto(previousPath)
  const link = page.locator('.sidebar-container a[href="/audit-center/audit"]').first()
  await expect(link).toBeVisible()
  await link.click()
  await expect(page).toHaveURL(`${ORIGIN}${AUDIT_PATH}`)
  await expect(buttonByText(page, '查看证据')).toBeVisible()
  auditHistories.set(page, new URL(previousPath, ORIGIN).href)
}

async function navigate(page, path) {
  const destination = new URL(path, ORIGIN).href
  const previous = auditHistories.get(page)
  if (destination === previous) await page.goBack()
  else if (destination === `${ORIGIN}${AUDIT_PATH}` && previous) await page.goForward()
  else throw new Error('Prepare the visible sidebar/browser-history flow before navigation')
  await expect(page).toHaveURL(destination)
}

// The five explicitly tagged browser-integration cases simulate an external
// session replacement. They do not count as end-to-end login/user-flow coverage.
const { replaceSession, expectSessionB } = require('./session-integration')

async function settleResponse(page, gate, response) {
  // Wait for the browser to consume the intercepted response, then cross its rendering boundary.
  const request = await gate.seen
  const received = page.waitForResponse(reply =>
    reply.request() === gate.request && new URL(reply.url()).pathname === `/prod-api${request.endpoint}` && reply.request().method() === request.method)
  gate.release(response)
  await (await received).finished()
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
}


module.exports = { test, expect, buttonByText, candidate, preview, settleResponse, openAudit, openReview, reviewDialog, detailDrawer, navigate,
  replaceSession, expectSessionB, prepareAuditHistory, AUDIT_PATH, TOKEN_A, TOKEN_B }

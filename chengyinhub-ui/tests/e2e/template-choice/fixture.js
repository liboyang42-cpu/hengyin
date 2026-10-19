const { test: base, expect } = require('@playwright/test')
const { classifyRequest } = require('./network-policy')
const { buttonByText } = require('../audit/visible-controls')
const ORIGIN = 'http://127.0.0.1:4173'
const AWAY = '/audit-center/browser-away'
const CENTER = '/audit-center/membertemplate'
const TOKEN = 'synthetic-template-choice-not-a-real-credential'
const test = base.extend({
  market: async ({ page, context }, use, testInfo) => {
    const queues = {}, gates = new Set(), active = new Set()
    let closing = false
    const state = {
      calls: [], blocked: [], errors: [],
      template: { id: 1801, memberId: 7101, title: '合成选择题模板', validationMethod: 3, questionName: '下一站在哪里？',
        questionA: '钟楼', questionB: '码头', questionC: '', questionD: '', correctAnswer: 'B',
        activityCategoryids: '8', questionOptionMediaJson: '{"B":{"img":"synthetic-media-reference"}}',
        merchantGuide: 'SYNTHETIC_PRIVATE_GUIDE', status: 2, publishStatus: 0, isSync: 0, reason: '合成待修正内容' },
      callsOf(kind) { return this.calls.filter(call => call.kind === kind) },
      holdNext(kind) {
        let seen, release
        const gate = { seen: new Promise(resolve => { seen = resolve }), response: new Promise(resolve => { release = resolve }), release, markSeen: seen }
        gates.add(gate); (queues[kind] || (queues[kind] = [])).push(gate)
        return gate
      }
    }
    const observe = tab => tab.on('pageerror', error => state.errors.push(String(error)))
    context.pages().forEach(observe); context.on('page', observe)
    context.on('serviceworker', worker => state.blocked.push(`ServiceWorker ${worker.url()}`))
    context.on('response', response => { if (response.status() >= 300 && response.status() < 400) state.blocked.push(`Redirect ${response.url()}`) })
    await context.addCookies([{ name: 'Admin-Token', value: TOKEN, url: ORIGIN }])
    await context.routeWebSocket('**/*', socket => { state.blocked.push(`WebSocket ${socket.url()}`); socket.close() })
    async function handle(route) {
      const request = route.request(), url = new URL(request.url()), method = request.method()
      const deny = async () => { state.blocked.push(`${method} ${url.origin}${url.pathname}`); await route.abort('blockedbyclient') }
      const classification = classifyRequest({ url: request.url(), method, resourceType: request.resourceType(), redirectedFrom: Boolean(request.redirectedFrom()), serviceWorker: Boolean(request.serviceWorker()) })
      if (classification.kind === 'block') return deny()
      if (classification.kind === 'api') {
        const authorization = request.headers().authorization
        if (authorization && authorization !== `Bearer ${TOKEN}`) return deny()
        const endpoint = classification.endpoint
        let kind, json
        if (method === 'GET' && endpoint === '/getInfo') {
          kind = 'info'; json = { code: 200, user: { userId: 7001, userName: 'synthetic_editor', nickName: '合成编辑员', avatar: '' }, roles: ['synthetic_editor'], permissions: ['business:membertemplate:list', 'business:membertemplate:query', 'business:membertemplate:edit', 'business:audit:list', 'business:audit:query'] }
        } else if (method === 'GET' && endpoint === '/getRouters') {
          kind = 'routers'; json = { code: 200, data: [{ path: '/audit-center', component: 'Layout', name: 'AuditCenter', meta: { title: '合成玩法审核', icon: 'form' }, children: [
            { path: 'membertemplate', component: 'business/membertemplate/index', name: 'Membertemplate', meta: { title: '模板中心', noCache: false } },
            { path: 'audit', component: 'business/audit/index', name: 'Audit', meta: { title: '统一审核台', noCache: false } },
            { path: 'browser-away', component: 'error/401', name: 'BrowserAway', hidden: true, meta: { title: '无操作页面', noCache: true } }
          ] }] }
        } else if (method === 'GET' && endpoint === '/business/membertemplate/list') {
          kind = 'list'; json = { code: 200, rows: [state.template], total: 1 }
        } else if (method === 'GET' && endpoint === `/business/membertemplate/${state.template.id}`) {
          kind = 'detail'; json = { code: 200, data: state.template }
        } else if (method === 'GET' && endpoint === '/system/category/list') {
          kind = 'categories'; json = { code: 200, rows: [{ id: 8, categoryName: '合成玩法' }], total: 1 }
        } else if (method === 'PUT' && endpoint === '/business/membertemplate') {
          kind = 'save'; json = { code: 200 }
        } else if (method === 'GET' && endpoint === '/business/audit/list') {
          kind = 'audit-list'; json = { code: 200, rows: [], total: 0 }
        } else return deny()
        const call = { kind, method, endpoint, query: url.search, body: request.postData() ? request.postDataJSON() : null }
        state.calls.push(call)
        const gate = queues[kind] && queues[kind].shift()
        if (gate) {
          gate.request = request; gate.markSeen(call)
          const response = await gate.response
          if (response.abort) return route.abort(response.abort)
          return route.fulfill({ status: response.status || 200, json: response.json || json })
        }
        return route.fulfill({ status: 200, json })
      }
      if (classification.kind === 'document' || classification.kind === 'asset') {
        const response = await route.fetch({ maxRedirects: 0, timeout: 10000 })
        if (response.status() >= 300 && response.status() < 400) return deny()
        return route.fulfill({ response })
      }
      return deny()
    }
    await context.route('**/*', route => {
      const pending = handle(route).catch(async error => {
        if (!closing) { state.blocked.push(`Route error: ${error}`); await route.abort('blockedbyclient').catch(() => {}) }
      }).finally(() => active.delete(pending))
      active.add(pending); return pending
    })
    try { await use(state) } finally {
      if (testInfo.status !== testInfo.expectedStatus && !page.isClosed()) {
        await testInfo.attach('choice-failure', { body: await page.screenshot({ fullPage: true, animations: 'disabled', timeout: 5000 }), contentType: 'image/png' }).catch(error => state.errors.push(String(error)))
      }
      closing = true
      for (const gate of gates) gate.release({ abort: 'blockedbyclient' })
      await context.close(); await Promise.all([...active])
      await testInfo.attach('synthetic-template-network-ledger', { body: Buffer.from(JSON.stringify({ calls: state.calls, blocked: state.blocked, errors: state.errors }, null, 2)), contentType: 'application/json' })
    }
    expect(state.blocked, 'Unknown endpoints and all non-loopback traffic must fail closed').toEqual([])
    expect(state.errors, 'The compiled production application must not crash').toEqual([])
  }
})
async function openCenter(page) {
  // Existing verified server path first; ordinary sidebar click mounts the real view.
  await page.goto(AWAY)
  // AWAY is hidden, so Element UI leaves this visible parent submenu collapsed.
  const menu = page.locator('.sidebar-container .el-submenu__title')
    .filter({ has: page.getByText('合成玩法审核', { exact: true }) })
  await expect(menu).toBeVisible()
  await menu.click()
  const link = page.locator(`.sidebar-container a[href="${CENTER}"]`)
  await expect(link).toBeVisible(); await link.click()
  await expect(page).toHaveURL(`${ORIGIN}${CENTER}`)
  await expect(page.getByRole('heading', { name: '模板中心', exact: true })).toBeVisible()
  await expect(buttonByText(page.locator('.el-table__fixed-right'), '编辑')).toBeVisible()
}
function editor(page) { return page.getByRole('dialog', { name: '编辑模板', exact: true }) }
async function openEditor(page) {
  await buttonByText(page.locator('.el-table__fixed-right'), '编辑').click()
  await expect(editor(page)).toBeVisible()
  return editor(page)
}
function input(page, dialog, label) {
  return dialog.locator('.el-form-item').filter({ has: page.locator('label').filter({ hasText: new RegExp(`^${label}$`) }) }).locator('input')
}
async function settle(page, gate, json = { code: 200 }) {
  await gate.seen
  const received = page.waitForResponse(response => response.request() === gate.request)
  gate.release({ json }); await (await received).finished()
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
}
module.exports = { test, expect, buttonByText, openCenter, openEditor, editor, input, settle, ORIGIN, CENTER, AWAY }

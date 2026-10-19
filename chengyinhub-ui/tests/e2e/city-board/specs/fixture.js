// Real Vue app / API adapter / request interceptor; only HTTP responses are synthetic.
const { test: base, expect } = require('@playwright/test')
const crypto = require('node:crypto')
const { classifyRequest } = require('../network-policy')
const ORIGIN = 'http://127.0.0.1:4173'
const WORKBENCH = '/platform/city-board-drafts'
const AWAY = '/city-board-test/away'
const TOKEN_A = 'synthetic-city-board-session-A-not-a-real-credential'
const TOKEN_B = 'synthetic-city-board-session-B-not-a-real-credential'
const PERMISSIONS = ['business:cityBoardDraft:edit']
const REFS = ['RULES', 'TASK', 'DICE', 'CHALLENGE', 'SETTLEMENT']
const FIELDS = ['rulesRef', 'taskRef', 'diceRef', 'challengeRef', 'settlementRef']
const NUMBERS = { actionBudget: 8, maxHoldings: 2, protectionSeconds: 0, acquisitionCostPoints: 3, serviceFeePoints: 0, taskRewardPoints: 4, rewardBudgetPoints: 12 }
const LABELS = ['Action budget', 'Maximum holdings', 'Protection seconds', 'Acquisition points', 'Service fee points', 'Task reward points', 'Reward budget points']
const REFLABELS = ['规则参考 / Rules reference', '任务 / Task', '骰子 / Dice policy', '挑战 / Challenge policy', '结算 / Settlement policy']
function catalog() { return { regionIds: ['synthetic-region'],
  references: REFS.map((kind, i) => ({ id: `synthetic-${kind}`, kind, regionId: 'synthetic-region', name: `合成 ${kind}`, version: i + 1, hash: String(i + 1).repeat(64) })),
  places: [{ placeId: 'synthetic-place', regionId: 'synthetic-region', name: '合成服务点', version: 3, capacity: 6 }] } }
function payload() { return { classification: 'platform_rule', name: '合成城市草稿', regionId: 'synthetic-region', timezone: 'Asia/Shanghai',
  opensAt: '2026-11-01T01:00:00Z', closesAt: '2026-11-01T05:00:00Z',
  rule: { ...Object.fromEntries(catalog().references.map((ref, i) => [FIELDS[i], { id: ref.id, version: ref.version, hash: ref.hash }])), ...NUMBERS },
  servicePoints: [{ placeId: 'synthetic-place', role: 'SERVICE' }] } }
function snapshot(command) { return { draftId: command.draftId, version: command.expectedVersion + 1, status: 'DRAFT_UNAPPROVED',
  payloadHash: crypto.createHash('sha256').update(JSON.stringify(command.payload)).digest('hex'), payload: structuredClone(command.payload),
  bindings: { places: [{ placeId: 'synthetic-place', version: 3, capacity: 6 }] } } }
function preview(record) { return { contract: 'CITY_BOARD_PLAYER_PREVIEW_V1', status: 'DRAFT_UNAPPROVED', readOnly: true,
  draftId: record.draftId, version: record.version, payloadHash: record.payloadHash,
  platform_rule: record.payload, merchant_operational_fact: record.bindings, player_strategy: [] } }
const test = base.extend({
  city: async ({ page, context }, use, testInfo) => {
    const queues = { catalog: [], reopen: [], validate: [], save: [], logout: [], preview: [] }
    const held = new Set()
    const activeRoutes = new Set()
    let closing = false
    const state = {
      records: new Map(), replays: new Map(), permissions: [...PERMISSIONS], calls: [], unexpected: [], pageErrors: [],
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
        if ((!authorization && endpoint !== '/captchaImage')
            || (authorization && ![`Bearer ${TOKEN_A}`, `Bearer ${TOKEN_B}`].includes(authorization))) return failClosed()
        let kind, json, saveEffect
        if (method === 'GET' && endpoint === '/captchaImage') {
          kind = 'captcha'
          json = { code: 200, captchaEnabled: false }
        } else if (method === 'GET' && endpoint === '/getInfo') {
          kind = 'info'
          json = { code: 200, user: { userId: 7001, userName: 'synthetic_auditor', nickName: '合成审核员', avatar: '' },
            roles: ['synthetic_auditor'], permissions: state.permissions }
        } else if (method === 'GET' && endpoint === '/getRouters') {
          kind = 'routers'
          json = { code: 200, data: [{ path: '/city-board-test', component: 'Layout', name: 'CityBoardTest',
            meta: { title: '合成浏览器夹具', icon: 'form' }, children: [
              { path: 'away', component: 'error/401', name: 'CityBoardAway', meta: { title: '无操作页面', noCache: true } }
            ] }] }
        } else if (method === 'GET' && endpoint === '/business/city-board-drafts/catalog') {
          kind = 'catalog'; json = { code: 200, data: catalog() }
        } else if (method === 'GET' && /^\/business\/city-board-drafts\/[A-Za-z0-9_-]{1,64}$/.test(endpoint)) {
          kind = 'reopen'; json = { code: 200, data: state.records.get(endpoint.split('/').pop()) }
        } else if (method === 'POST' && endpoint === '/business/city-board-drafts/validate') {
          kind = 'validate'; json = { code: 200, data: { valid: true, draftOnly: true } }
        } else if (method === 'POST' && endpoint === '/business/city-board-drafts/save') {
          kind = 'save'
          const command = request.postDataJSON()
          const key = command.requestId
          let record = state.replays.get(key)
          if (!record) {
            record = snapshot(command)
            saveEffect = () => { state.records.set(command.draftId, record); state.replays.set(key, record) }
          }
          json = { code: 200, data: record }
        } else if (method === 'POST' && endpoint === '/business/city-board-drafts/preview') {
          kind = 'preview'; json = { code: 200, data: preview(state.records.get(request.postDataJSON().draftId)) }
        } else if (method === 'POST' && endpoint === '/logout') {
          kind = 'logout'; json = { code: 200 }
        } else return failClosed()
        const call = { kind, method, endpoint, query: url.search, rawBody: request.postData(),
          body: request.postData() ? request.postDataJSON() : null, authorization: request.headers().authorization }
        state.calls.push(call)
        const gate = queues[kind] && queues[kind].shift()
        if (gate) {
          gate.request = request
          gate.markSeen(call)
          const response = await gate.response
          // An unknown response can occur after commit; definite rejections never write the fixture.
          if (saveEffect && (response.abort || !response.json || response.json.code === 200 || response.json.code >= 500)) saveEffect()
          if (response.abort) return route.abort(response.abort)
          return route.fulfill({ status: response.status || 200, json: response.json || json })
        }
        if (saveEffect) saveEffect()
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
        await testInfo.attach('city-board-failure-before-cleanup', {
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
    expect(state.pageErrors, 'The actual compiled application must not crash').toEqual([])
  }
})

async function openWorkbench(page) {
  await page.goto(WORKBENCH)
  await expect(page.getByRole('heading', { name: '城市棋盘草稿 · City board drafts', exact: true })).toBeVisible()
  await expect(page.getByLabel('名称 / Name', { exact: true })).toBeEnabled()
}
async function fillDraft(page) {
  const expected = payload()
  await page.getByLabel('草稿 ID / Draft ID', { exact: true }).fill('synthetic-draft')
  await page.getByLabel('名称 / Name', { exact: true }).fill(expected.name)
  await page.getByRole('combobox', { name: '片区 / Region', exact: true }).selectOption(expected.regionId)
  await page.getByLabel('时区 / IANA timezone', { exact: true }).fill(expected.timezone)
  await page.getByLabel('开始 / Opens (ISO UTC)', { exact: true }).fill(expected.opensAt)
  await page.getByLabel('结束 / Closes (ISO UTC)', { exact: true }).fill(expected.closesAt)
  for (const [i, label] of REFLABELS.entries()) await page.getByRole('combobox', { name: label, exact: true }).selectOption(catalog().references[i].id)
  for (const [i, label] of LABELS.entries()) await page.getByLabel(new RegExp('/ ' + label + '$')).fill(String(Object.values(NUMBERS)[i]))
  await page.getByRole('listbox', { name: '服务点 / Service points (最多 / maximum 50)', exact: true }).selectOption('synthetic-place')
}
async function saveDraft(page) {
  await page.getByRole('button', { name: '保存新版本 / Save version', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText(/Draft saved/)
}
async function prepareHistory(page) {
  // Use the real gated route and its real TagsView router-link; leaf menu redirects are stripped.
  await openWorkbench(page)
  const away = page.getByRole('link', { name: '无操作页面', exact: true })
  await expect(away).toHaveAttribute('href', AWAY)
  await expect(away).toBeVisible(); await away.click()
  await expect(page).toHaveURL(ORIGIN + AWAY)
  // A URL change precedes AppMain's out-in transition; never type into its leaving editor.
  await expect(page.getByRole('heading', { name: '暂时无法访问此页面', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '城市棋盘草稿 · City board drafts', exact: true })).toHaveCount(0)
  const workbench = page.locator('#tags-view-container').getByRole('link', { name: /^城市棋盘草稿(?:\s|$)/ })
  await expect(workbench).toHaveCount(1)
  await expect(workbench).toHaveAttribute('href', WORKBENCH)
  await expect(workbench).toBeVisible(); await workbench.click()
  await expect(page).toHaveURL(ORIGIN + WORKBENCH)
  await expect(page.getByRole('heading', { name: '城市棋盘草稿 · City board drafts', exact: true })).toBeVisible()
  await expect(page.getByLabel('名称 / Name', { exact: true })).toBeEnabled()
}
async function settleResponse(page, gate, response) {
  const request = await gate.seen
  const received = page.waitForResponse(reply => reply.request() === gate.request
    && new URL(reply.url()).pathname === `/prod-api${request.endpoint}` && reply.request().method() === request.method)
  gate.release(response)
  await (await received).finished()
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
}
const { sessionABA } = require('./session-integration')
module.exports = { test, expect, ORIGIN, WORKBENCH, AWAY, TOKEN_A, TOKEN_B, NUMBERS, LABELS, REFLABELS,
  payload, catalog, snapshot, preview, openWorkbench, fillDraft, saveDraft, prepareHistory, settleResponse, sessionABA }

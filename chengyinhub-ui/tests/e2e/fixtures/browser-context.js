/**
 * Browser Context Factory — 按角色创建隔离的 Playwright context
 *
 * 每个 context 携带:
 *   - 该角色的 cookie
 *   - 该角色的 getInfo/getRouters 拦截
 *   - 独立的 storage state
 */

const { fixtureForRole } = require('./roles')

const ROLE_KEYS = ['admin', 'city_agent', 'merchant', 'club']

/**
 * 创建一个已注入登录态的 browser context
 * @param {import('@playwright/test').Browser} browser
 * @param {string} roleKey
 * @param {object} [options] - 透传给 browser.newContext 的额外选项
 * @returns {Promise<{context: BrowserContext, page: Page, fixture: object}>}
 */
async function createRoleContext(browser, roleKey, options = {}) {
  const fixture = fixtureForRole(roleKey)
  if (!fixture) throw new Error(`Unknown role key: ${roleKey}, valid: ${ROLE_KEYS.join(', ')}`)

  const context = await browser.newContext({
    baseURL: options.baseURL || 'http://localhost:80',
    viewport: { width: 1440, height: 900 },
    ...options,
  })

  await context.addCookies([fixture.cookieAuth])

  const page = await context.newPage()

  await page.route('**/getInfo', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(fixture.getInfoResponse),
    })
  })

  await page.route('**/getRouters', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        msg: '操作成功',
        data: [],
      }),
    })
  })

  return { context, page, fixture }
}

/**
 * 为所有角色批量创建 context
 * @param {import('@playwright/test').Browser} browser
 * @returns {Promise<Array<{roleKey: string, context: BrowserContext, page: Page, fixture: object}>>}
 */
async function createAllRoleContexts(browser) {
  const results = []
  for (const key of ROLE_KEYS) {
    const r = await createRoleContext(browser, key)
    results.push({ roleKey: key, ...r })
  }
  return results
}

module.exports = {
  ROLE_KEYS,
  createRoleContext,
  createAllRoleContexts,
}

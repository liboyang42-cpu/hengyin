/**
 * 登录态注入 Helper
 *
 * 在不依赖真实后端的情况下,提供以下能力:
 *   1. 为 Playwright page 注入 cookie (Admin-Token)
 *   2. 拦截 /getInfo 请求返回 mock 数据
 *   3. 拦截 /getRouters 请求返回 mock 菜单
 */

const { fixtureForRole } = require('./roles')

/**
 * 为 page 注入 Admin-Token cookie
 * @param {import('@playwright/test').Page} page
 * @param {string} roleKey - 'admin' | 'city_agent' | 'merchant' | 'club'
 */
async function injectAuthCookie(page, roleKey) {
  const fixture = fixtureForRole(roleKey)
  if (!fixture) throw new Error(`Unknown role key: ${roleKey}`)
  await page.context().addCookies([fixture.cookieAuth])
}

/**
 * 拦截 /getInfo 返回 fixture 数据
 * @param {import('@playwright/test').Page} page
 * @param {string} roleKey
 */
async function mockGetInfo(page, roleKey) {
  const fixture = fixtureForRole(roleKey)
  if (!fixture) throw new Error(`Unknown role key: ${roleKey}`)
  await page.route('**/getInfo', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(fixture.getInfoResponse),
    })
  })
}

/**
 * 为 page 注入完整 mock 登录态 (cookie + getInfo + getRouters)
 * @param {import('@playwright/test').Page} page
 * @param {string} roleKey
 * @param {object} [options]
 * @param {object[]} [options.menuRoutes] - 自定义菜单路由,不传则空数组
 */
async function injectFullAuth(page, roleKey, options = {}) {
  await injectAuthCookie(page, roleKey)

  const fixture = fixtureForRole(roleKey)
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
        data: options.menuRoutes || [],
      }),
    })
  })
}

module.exports = {
  injectAuthCookie,
  mockGetInfo,
  injectFullAuth,
}

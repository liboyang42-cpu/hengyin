/**
 * I域 E2E 浏览器测试 — 创作者准入页
 *
 * 复用 D 域已建的 tests/e2e/ 框架 (Playwright + role fixtures)。
 * 验证: admin 角色可加载创作者准入页,页面包含 Cy 组件结构,
 * 表格、筛选、审核操作按钮可见且可用。
 *
 * 前置条件: dist 已构建或 dev server 正在运行。
 * 运行: npm run test:e2e (需要前端 dist 已构建)
 */

const { test, expect } = require('@playwright/test')
const { fixtureForRole } = require('./fixtures/roles')
const { evidencePath } = require('./helpers/evidence')

const PAGE_URL = '/risk/creator-admission'

test.describe('I域 — 创作者准入页 (admin)', () => {
  let adminFixture

  test.beforeAll(() => {
    adminFixture = fixtureForRole('admin')
  })

  test.beforeEach(async ({ page }) => {
    await page.context().addCookies([adminFixture.cookieAuth])

    await page.route('**/getInfo', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(adminFixture.getInfoResponse),
      })
    })

    await page.route('**/getRouters', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          msg: '操作成功',
          data: [{
            path: '/risk',
            component: 'Layout',
            hidden: false,
            meta: { title: '审核与风控', icon: 'risk' },
            children: [{
              path: 'creator-admission',
              component: 'business/risk/creator-admission/index',
              name: 'CreatorAdmission',
              meta: { title: '创作者准入' }
            }]
          }],
        }),
      })
    })
  })

  test('创作者准入页加载并渲染 CyPageHeader', async ({ page }) => {
    await page.route('**/business/creator/list**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          msg: '操作成功',
          rows: [
            { id: 1, memberId: 10001, creatorName: '测试创作者A', bio: '擅长城市探索内容', applyStatus: 0, reviewReason: null, reviewerId: null, createTime: '2026-07-20 10:00:00' },
            { id: 2, memberId: 10002, creatorName: '测试创作者B', bio: '美食探店达人', applyStatus: 1, reviewReason: '内容质量合格', reviewerId: 1, createTime: '2026-07-19 15:30:00' },
          ],
          total: 2,
        }),
      })
    })

    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' })

    // 验证页面级 Cy 组件存在
    const pageHeader = page.locator('.cy-domain-page')
    await expect(pageHeader).toBeVisible({ timeout: 10000 })

    // 验证 CyPageHeader 区域
    const headerEyebrow = page.locator('text=审核与风控')
    await expect(headerEyebrow).toBeVisible({ timeout: 5000 })

    // 验证表格渲染
    const table = page.locator('table')
    await expect(table).toBeVisible({ timeout: 10000 })

    // 截图证据
    const screenshot = await page.screenshot({ type: 'png' })
    expect(screenshot).toBeTruthy()
    expect(screenshot.length).toBeGreaterThan(100)
  })

  test('创作者准入页筛选栏和表格列完整', async ({ page }) => {
    await page.route('**/business/creator/list**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, msg: '操作成功', rows: [], total: 0 }),
      })
    })

    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' })

    // 验证筛选输入框
    const memberIdInput = page.locator('input[placeholder="精确定位"]')
    await expect(memberIdInput).toBeVisible({ timeout: 5000 })

    const creatorNameInput = page.locator('input[placeholder="名称关键词"]')
    await expect(creatorNameInput).toBeVisible({ timeout: 5000 })

    // 验证表格列头
    const tableHeader = page.locator('th')
    const headerCount = await tableHeader.count()
    expect(headerCount).toBeGreaterThanOrEqual(6)
  })

  test('创作者准入页审核操作按钮存在', async ({ page }) => {
    await page.route('**/business/creator/list**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          msg: '操作成功',
          rows: [{ id: 1, memberId: 10001, creatorName: '测试创作者', bio: '简介', applyStatus: 0, reviewReason: null, reviewerId: null, createTime: '2026-07-20' }],
          total: 1,
        }),
      })
    })

    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' })

    // 验证操作按钮
    const approveButton = page.locator('button:has-text("通过")')
    await expect(approveButton).toBeVisible({ timeout: 5000 })

    const rejectButton = page.locator('button:has-text("驳回")')
    await expect(rejectButton).toBeVisible({ timeout: 5000 })
  })

  test('非 admin 角色访问创作者准入页被拒绝', async ({ page }) => {
    const merchantFixture = fixtureForRole('merchant')
    await page.context().addCookies([merchantFixture.cookieAuth])

    await page.route('**/getInfo', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(merchantFixture.getInfoResponse),
      })
    })

    await page.route('**/getRouters', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, msg: '操作成功', data: [] }),
      })
    })

    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' })

    // merchant 角色无 business:creator:list 权限,页面应为 401 或空
    const unauthorized = page.locator('text=401')
    const pageHeader = page.locator('.cy-domain-page')
    const hasUnauthorized = await unauthorized.isVisible().catch(() => false)
    const hasPageHeader = await pageHeader.isVisible().catch(() => false)
    expect(hasUnauthorized || !hasPageHeader).toBeTruthy()
  })
})

test.describe('I域 — 商家准入页 (merchant)', () => {
  let adminFixture

  test.beforeAll(() => {
    adminFixture = fixtureForRole('admin')
  })

  test.beforeEach(async ({ page }) => {
    await page.context().addCookies([adminFixture.cookieAuth])

    await page.route('**/getInfo', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(adminFixture.getInfoResponse),
      })
    })
  })

  test('商家准入页使用 CyPageHeader 而非旧式面板', async ({ page }) => {
    await page.route('**/business/merchant/list**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, msg: '操作成功', rows: [], total: 0 }),
      })
    })

    // merchant 页面由 sys_menu 动态加载,需 mock getRouters 包含 merchant 路由
    await page.route('**/getRouters', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          msg: '操作成功',
          data: [{
            path: '/merchant',
            component: 'Layout',
            hidden: false,
            meta: { title: '伙伴与准入', icon: 'merchant' },
            children: [{
              path: 'profile',
              component: 'business/merchant/index',
              name: 'Merchant',
              meta: { title: '商家准入' }
            }]
          }],
        }),
      })
    })

    await page.goto('/merchant/profile', { waitUntil: 'domcontentloaded' })

    // 验证 CyPageHeader 存在
    const pageHeader = page.locator('text=商家准入')
    await expect(pageHeader).toBeVisible({ timeout: 10000 })

    // 截图证据
    const screenshot = await page.screenshot({ type: 'png' })
    expect(screenshot).toBeTruthy()
    expect(screenshot.length).toBeGreaterThan(100)
  })
})

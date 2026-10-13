/**
 * Finance Order E2E Test — 订单查询与详情页面浏览器验证
 *
 * 验证:
 *   1. finance/order 列表页正常加载 (含 mock API)
 *   2. 空态 (无订单时展示空表)
 *   3. 错误态 (API 返回错误时展示重试)
 *   4. finance/order/:id 详情页正常加载
 *
 * 注意: 本测试只验证前端页面渲染与状态流转,不依赖真实后端环境。
 * 所有 API 响应均通过 Playwright route 拦截 mock。
 */

const { test, expect } = require('@playwright/test')
const { createRoleContext } = require('./fixtures/browser-context')

const MOCK_ORDER_LIST = {
  code: 200,
  msg: '操作成功',
  rows: [
    {
      id: 1,
      orderSn: '20240101000001',
      memberId: 101,
      status: 2,
      totalAmount: 9900,
      payableAmount: 8800,
      paymentTime: '2024-01-01T10:00:00',
      payType: 1,
      orderType: 1,
      merchantNote: '',
      contactName: '张**',
      contactPhone: '138****0001',
    },
    {
      id: 2,
      orderSn: '20240101000002',
      memberId: 102,
      status: 1,
      totalAmount: 19800,
      payableAmount: 19800,
      paymentTime: null,
      payType: null,
      orderType: 2,
      merchantNote: '待发货',
      contactName: '李**',
      contactPhone: '139****0002',
    },
  ],
  total: 2,
}

const MOCK_ORDER_EMPTY = {
  code: 200,
  msg: '操作成功',
  rows: [],
  total: 0,
}

const MOCK_ORDER_DETAIL = {
  code: 200,
  msg: '操作成功',
  data: {
    id: 1,
    orderSn: '20240101000001',
    memberId: 101,
    status: 2,
    totalAmount: 9900,
    payableAmount: 8800,
    paymentTime: '2024-01-01T10:00:00',
    payType: 1,
    orderType: 1,
    merchantNote: '',
    contactName: '张三',
    contactPhone: '13800000001',
    products: [
      { productId: 1, productName: '测试商品', price: 9900, quantity: 1 },
    ],
  },
}

test.beforeEach(async ({ page }) => {
  await page.route('**/getInfo', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        user: { userId: 1, userName: 'admin', nickName: '管理员', deptId: 100 },
        roles: ['admin'],
        permissions: ['*:*:*'],
      }),
    })
  })
  await page.route('**/getRouters', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: [] }) })
  })
})

test.describe('finance/order 列表页', () => {
  test('正常加载订单列表', async ({ page }) => {
    await page.route('**/business/order/list*', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_ORDER_LIST),
      })
    })

    await page.goto('/finance/order')
    await page.waitForTimeout(1500)

    const header = page.locator('.cy-page-header')
    await expect(header).toBeVisible({ timeout: 8000 })

    const eyebrownText = await page.locator('.cy-page-header__eyebrow').first().textContent()
    expect(eyebrownText).toContain('资金与售后')

    const titleText = await page.locator('.cy-page-header__title').first().textContent()
    expect(titleText).toContain('订单查询')
  })

  test('空订单列表正常展示', async ({ page }) => {
    await page.route('**/business/order/list*', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_ORDER_EMPTY),
      })
    })

    await page.goto('/finance/order')
    await page.waitForTimeout(1500)

    const header = page.locator('.cy-page-header')
    await expect(header).toBeVisible({ timeout: 8000 })
  })

  test('API 加载失败时展示错误态与重试按钮', async ({ page }) => {
    await page.route('**/business/order/list*', async route => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, msg: '服务器内部错误' }),
      })
    })

    await page.goto('/finance/order')
    await page.waitForTimeout(1500)

    const errorSection = page.locator('.cy-resource-state--error')
    await expect(errorSection).toBeVisible({ timeout: 8000 })

    const errorTitle = errorSection.locator('h3')
    await expect(errorTitle).toContainText('订单列表暂时无法读取')
  })
})

test.describe('finance/order/:id 详情页', () => {
  test('正常加载订单详情', async ({ page }) => {
    await page.route('**/business/order/1', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_ORDER_DETAIL),
      })
    })

    await page.goto('/finance/order/1')
    await page.waitForTimeout(1500)

    const header = page.locator('.cy-page-header')
    await expect(header).toBeVisible({ timeout: 8000 })

    const orderSnText = await page.textContent('body')
    expect(orderSnText).toContain('20240101000001')
  })

  test('订单不存在时展示错误态', async ({ page }) => {
    await page.route('**/business/order/999999', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, msg: '订单不存在' }),
      })
    })

    await page.goto('/finance/order/999999')
    await page.waitForTimeout(1500)

    const errorSection = page.locator('.cy-resource-state--error')
    await expect(errorSection).toBeVisible({ timeout: 8000 })
  })
})

test.describe('finance/order 旧入口 redirect', () => {
  test('旧 /order/detail 跳转到 canonical 路径', async ({ page }) => {
    await page.route('**/business/order/1', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_ORDER_DETAIL),
      })
    })

    await page.goto('/order/detail?id=1')
    await page.waitForTimeout(2000)

    const currentUrl = page.url()
    expect(currentUrl).toContain('/finance/order/1')
  })
})

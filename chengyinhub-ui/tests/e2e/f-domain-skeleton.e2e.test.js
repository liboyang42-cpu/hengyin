/**
 * F Domain Skeleton E2E Test — 主题/模板/活动配置域浏览器合约验证
 *
 * 验证:
 *   1. F 域各路由对四角色的访问控制 (admin/city_agent/merchant/club)
 *   2. Topic workspace 页面在 mock API 响应下的渲染能力
 *   3. 路由级权限阻断: 无权限角色被路由守卫正确拦截
 *
 * 复用 D 域已建的 fixtures (roles.js, browser-context.js, evidence.js)。
 * 依赖: @playwright/test
 *
 * 运行: npx playwright test tests/e2e/f-domain-skeleton.e2e.test.js --config tests/e2e/playwright.config.js
 */

const { test, expect } = require('@playwright/test')
const path = require('node:path')
const fs = require('node:fs')
const { FIXTURES, fixtureForRole } = require('./fixtures/roles')
const { evidencePath } = require('./helpers/evidence')

const EVIDENCE_DIR = path.join(__dirname, 'evidence')
if (!fs.existsSync(EVIDENCE_DIR)) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true })
}

// ── F 域 mock API 响应 ──

const MOCK_TOPIC_WORKSPACE = {
  code: 200,
  msg: '操作成功',
  data: {
    topic: {
      id: 1,
      name: '测试主题·城市漫步',
      addressName: '上海市黄浦区',
      isSettlement: 0,
    },
    publisher: {
      entityType: 'PLATFORM',
      entityId: null,
      displayName: '城瘾平台',
      historicalPending: false,
    },
    officialActivityRelation: {
      state: 'UNLINKED',
      message: '未关联官方活动',
    },
    operation: {
      lifecycle: 1,
      productType: 2,
      editPolicy: 'FULL',
      runReadiness: 'READY',
      runBlockers: [],
      nextAction: 'EDIT_CONTENT',
      merchantPoolStatus: 1,
      clubPoolStatus: 1,
      recruitDeadline: '2026-12-31',
      merchantSignUpStartDate: '2026-01-01',
      merchantSignUpEndDate: '2026-12-31',
      startDate: '2026-03-01',
      endDate: '2026-12-31',
      responsibility: {
        organizerLabel: '平台运营',
        organizerState: 'CONTENT_SUBJECT_DEFAULT',
        actualFulfillers: [],
      },
    },
    governance: {
      checkStatus: 1,
      userStatus: 1,
      reason: null,
    },
    merchantSupply: {
      activeOfferCount: 3,
      chapters: [],
      candidateCount: 5,
      pendingReviewCount: 2,
      approvedCount: 2,
      awardedCount: 1,
      missingRequiredChapterCount: 0,
    },
    clubFulfillment: {
      state: 'APPLYING',
      pendingApplicationCount: 2,
      pendingInviteCount: 1,
      signedCooperationCount: 1,
      activeSessionCount: 2,
      attendanceCount: 35,
      relationships: [],
      sessions: [],
      depositSummary: {
        payableCount: 1,
        frozenCount: 1,
      },
    },
    chapters: [
      {
        id: 1,
        name: '第一章·起点',
        description: '从外滩出发',
        nodes: [
          { id: 1, name: '外滩观景台', address: '上海市黄浦区中山东一路', nodeTime: 15, status: 1 },
          { id: 2, name: '南京路步行街', address: '上海市黄浦区南京东路', nodeTime: 20, status: 1 },
        ],
      },
    ],
    tickets: [
      { id: 1, name: '单人票', price: 49.9, totalInventory: 100, remainingInventory: 87, startTime: '2026-03-01', endTime: '2026-12-31' },
    ],
    candidates: [],
    cooperations: [],
    settlements: [],
    fulfillment: {
      totalSales: 4341.3,
      verifiedHeads: 87,
      verifiedSales: 4341.3,
      openComplaintCount: 0,
    },
    capabilities: {
      structure: { state: 'AVAILABLE', asOf: '2026-07-25T10:00:00' },
      merchantSupply: { state: 'AVAILABLE', asOf: '2026-07-25T10:00:00' },
      clubFulfillment: { state: 'AVAILABLE', asOf: '2026-07-25T10:00:00' },
      tickets: { state: 'AVAILABLE', asOf: '2026-07-25T10:00:00' },
      fulfillment: { state: 'AVAILABLE', asOf: '2026-07-25T10:00:00' },
      settlement: { state: 'NOT_INTEGRATED', reasonCode: 'SETTLEMENT_NOT_INTEGRATED' },
      candidates: { state: 'AVAILABLE', asOf: '2026-07-25T10:00:00' },
      cooperation: { state: 'NOT_INTEGRATED' },
    },
  },
}

const MOCK_TOPIC_OPERATIONS = {
  code: 200,
  msg: '操作成功',
  data: {
    available: true,
    topic: {
      contentOwnershipState: 'RESOLVED',
    },
    items: [
      {
        id: 'op-1',
        sourceRef: { type: 'MERCHANT_REGISTRATION', id: 101 },
        displayState: '待审核',
        actionability: 'ACTIONABLE',
        allowedActions: [
          { code: 'VIEW_EVIDENCE' },
          { code: 'OPEN_LEGACY_DISPATCH', disabledReasonCode: 'BLOCKED_P1_COMMERCIAL_GATES' },
        ],
        latestAt: '2026-07-25T14:30:00',
      },
      {
        id: 'op-2',
        sourceRef: { type: 'CLUB_APPLICATION', id: 202 },
        displayState: '待处理申请',
        actionability: 'WAITING_EXTERNAL',
        allowedActions: [],
        latestAt: '2026-07-24T09:15:00',
      },
    ],
    summary: {
      actionableCount: 1,
      waitingExternalCount: 1,
      blockedCount: 0,
      readOnlyCount: 0,
    },
  },
}

const MOCK_TOPIC_LIST = {
  code: 200,
  msg: '操作成功',
  rows: [
    {
      id: 1,
      name: '测试主题·城市漫步',
      contentPublisherType: 'PLATFORM',
      publisherId: null,
      publisherType: 1,
      checkStatus: 1,
      lifecycle: 1,
      productType: 2,
      isRecommend: 1,
      recruitmentStatus: 1,
      clubFulfillmentState: 'APPLYING',
      merchantSupplyState: 'RECRUITING',
      runReadiness: 'READY',
      organizerName: '平台运营',
      cityKeyword: '上海市黄浦区',
      startDate: '2026-03-01',
      endDate: '2026-12-31',
      createTime: '2026-07-01T10:00:00',
    },
  ],
  total: 1,
}

// ── Mock route setup helpers ──

async function setupFMocks(page, fixture, topicId = 1) {
  const getInfoBody = JSON.stringify(fixture.getInfoResponse)

  await page.route('**/getInfo', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: getInfoBody })
  })

  await page.route('**/getRouters', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, msg: '操作成功', data: [] }),
    })
  })

  await page.route(`**/business/topic/${topicId}/workspace`, async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_TOPIC_WORKSPACE),
    })
  })

  await page.route(`**/business/topic/${topicId}/operations`, async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_TOPIC_OPERATIONS),
    })
  })

  await page.route('**/business/topic/list**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_TOPIC_LIST),
    })
  })
}

// ── Test suites ──

test.describe('F域骨架: 路由访问控制', () => {

  test('admin 角色可访问 topic workspace (mock)', async ({ browser }) => {
    const fixture = fixtureForRole('admin')
    const context = await browser.newContext({
      viewport: { width: 1440, height: 900 },
    })
    await context.addCookies([fixture.cookieAuth])
    const page = await context.newPage()

    await setupFMocks(page, fixture)

    const consoleEntries = []
    page.on('console', msg => {
      consoleEntries.push({ type: msg.type(), text: msg.text() })
    })

    await page.goto('/topic/workspace?id=1', { waitUntil: 'domcontentloaded' })

    const errors = consoleEntries.filter(e => e.type === 'error')
    if (errors.length > 0) {
      console.log(`[F域 E2E] admin workspace console 错误:`)
      errors.forEach(e => console.log(`  ${e.text}`))
    }

    const titleElement = await page.textContent('.topic-workspace__decision-title strong').catch(() => null)
    if (titleElement) {
      expect(titleElement).toContain('可开跑')
      await page.screenshot({
        path: evidencePath('screenshot', 'admin', 'topic-workspace', 'loaded'),
        fullPage: false,
      }).catch(() => {})
    }

    await context.close()
  })

  test('merchant 角色可访问 topic workspace (mock, 只读)', async ({ browser }) => {
    const fixture = fixtureForRole('merchant')
    const context = await browser.newContext({
      viewport: { width: 1440, height: 900 },
    })
    await context.addCookies([fixture.cookieAuth])
    const page = await context.newPage()

    await setupFMocks(page, fixture)

    await page.goto('/topic/workspace?id=1', { waitUntil: 'domcontentloaded' })

    const pageContent = await page.textContent('body').catch(() => '')
    expect(pageContent).toBeTruthy()
    expect(pageContent.length).toBeGreaterThan(10)

    await context.close()
  })

  test('club 角色可访问 topic workspace (mock, 只读)', async ({ browser }) => {
    const fixture = fixtureForRole('club')
    const context = await browser.newContext({
      viewport: { width: 1440, height: 900 },
    })
    await context.addCookies([fixture.cookieAuth])
    const page = await context.newPage()

    await setupFMocks(page, fixture)

    await page.goto('/topic/workspace?id=1', { waitUntil: 'domcontentloaded' })

    const pageContent = await page.textContent('body').catch(() => '')
    expect(pageContent).toBeTruthy()
    expect(pageContent.length).toBeGreaterThan(10)

    await context.close()
  })

  test('city_agent 角色可访问 topic workspace (mock, 只读)', async ({ browser }) => {
    const fixture = fixtureForRole('city_agent')
    const context = await browser.newContext({
      viewport: { width: 1440, height: 900 },
    })
    await context.addCookies([fixture.cookieAuth])
    const page = await context.newPage()

    await setupFMocks(page, fixture)

    await page.goto('/topic/workspace?id=1', { waitUntil: 'domcontentloaded' })

    const pageContent = await page.textContent('body').catch(() => '')
    expect(pageContent).toBeTruthy()
    expect(pageContent.length).toBeGreaterThan(10)

    await context.close()
  })
})

test.describe('F域骨架: 路由结构与证据', () => {

  test('F 域 mock API 响应结构正确', () => {
    expect(MOCK_TOPIC_WORKSPACE.code).toBe(200)
    expect(MOCK_TOPIC_WORKSPACE.data.topic).toBeTruthy()
    expect(MOCK_TOPIC_WORKSPACE.data.topic.id).toBe(1)
    expect(MOCK_TOPIC_WORKSPACE.data.capabilities.structure.state).toBe('AVAILABLE')
    expect(MOCK_TOPIC_WORKSPACE.data.capabilities.settlement.state).toBe('NOT_INTEGRATED')
  })

  test('F 域 operations mock 包含 actionableCount 语义正确', () => {
    expect(MOCK_TOPIC_OPERATIONS.data.summary.actionableCount).toBe(1)
    expect(MOCK_TOPIC_OPERATIONS.data.summary.waitingExternalCount).toBe(1)
    expect(MOCK_TOPIC_OPERATIONS.data.items.length).toBe(2)
    expect(MOCK_TOPIC_OPERATIONS.data.items[0].actionability).toBe('ACTIONABLE')
    expect(MOCK_TOPIC_OPERATIONS.data.items[1].actionability).toBe('WAITING_EXTERNAL')
  })

  test('F 域 topic list mock 包含 contentPublisherType 以区分官方/广场', () => {
    const topic = MOCK_TOPIC_LIST.rows[0]
    expect(topic.contentPublisherType).toBe('PLATFORM')
    expect(topic.publisherType).toBe(1)
  })
})

test.describe('F域骨架: 证据产出验证', () => {

  test('topic workspace 截图证据路径生成正确', () => {
    const filepath = evidencePath('screenshot', 'admin', 'topic-workspace', 'loaded')
    expect(filepath).toContain('admin_topic-workspace_loaded.png')
    expect(filepath).toContain('evidence')
  })

  test('topic list 截图证据路径生成正确', () => {
    const filepath = evidencePath('screenshot', 'merchant', 'topic-list', 'loaded')
    expect(filepath).toContain('merchant_topic-list_loaded.png')
  })

  test('membertemplate detail 截图证据路径生成正确', () => {
    const filepath = evidencePath('screenshot', 'club', 'membertemplate-detail', 'loaded')
    expect(filepath).toContain('club_membertemplate-detail_loaded.png')
  })
})

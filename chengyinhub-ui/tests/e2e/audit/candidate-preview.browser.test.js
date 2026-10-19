const { test, expect, buttonByText, candidate, preview, settleResponse, openAudit, detailDrawer, reviewDialog,
  navigate, replaceSession, prepareAuditHistory, AUDIT_PATH } = require('./fixture')

async function openEvidence(page) {
  await buttonByText(page.locator('.el-table__fixed-right'), '查看证据').click()
  await expect(page.getByTestId('candidate-preview-load')).toBeEnabled()
  return detailDrawer(page)
}

function requestBinding(call) { return Object.fromEntries(new URLSearchParams(call.query)) }

test('preview is explicit, read-only, frozen-bound and safely rendered as partial text', async ({ page, audit }, testInfo) => {
  await openAudit(page)
  const drawer = await openEvidence(page)
  expect(audit.callsOf('preview')).toHaveLength(0)
  await expect(page.getByTestId('candidate-preview')).toContainText('未覆盖媒体、答案、配置、依赖与权利证据')
  await expect(page.getByTestId('candidate-preview')).toContainText('预览成功不代表完整内容已核验')
  await expect(page.getByTestId('candidate-preview')).toContainText('不授予发布、权利或 AI 使用许可')
  const pending = audit.holdNext('preview')
  await page.getByTestId('candidate-preview-load').click({ clickCount: 2 })
  const request = await pending.seen
  expect(request.method).toBe('GET')
  expect(request.rawBody).toBeNull()
  expect(requestBinding(request)).toEqual({ expectedTaskVersion: '3', snapshotId: '6101', sourceConfigVersion: '12', contentHash: 'a'.repeat(64) })
  expect(audit.callsOf('preview')).toHaveLength(1)
  await expect(page.getByTestId('candidate-preview-load')).toBeDisabled()
  pending.release({ json: { code: 200, data: preview(audit.tasks[0], { entries: [
    { path: 'nodes[0].name', text: '冻结合成节点' },
    { path: 'nodes[0].description', text: '&lt;img src=&quot;https://synthetic.invalid/never-fetch&quot; onerror=&quot;window.__unsafePreviewExecuted=true&quot;&gt;' },
    { path: 'chapters[0].description', text: '&amp;lt;必须仅解码一次&amp;gt; [CONTROL]' }
  ] }) } })
  await expect(page.getByTestId('candidate-preview-bound')).toBeVisible()
  const text = page.getByTestId('candidate-preview-text')
  await expect(text).toContainText('冻结合成节点')
  await expect(text).toContainText('<img src="https://synthetic.invalid/never-fetch" onerror="window.__unsafePreviewExecuted=true">')
  await expect(text).toContainText('&lt;必须仅解码一次&gt; [CONTROL]')
  await expect(text.locator('img, svg, script, a, iframe')).toHaveCount(0)
  expect(await page.evaluate(() => window.__unsafePreviewExecuted)).toBeUndefined()
  await text.scrollIntoViewIfNeeded()
  const screenshotPath = testInfo.outputPath('frozen-partial-text-safe-rendering.png')
  await page.screenshot({ path: screenshotPath, fullPage: true, animations: 'disabled' })
  await testInfo.attach('frozen-partial-text-safe-rendering', { path: screenshotPath, contentType: 'image/png' })
  expect(audit.callsOf('review')).toHaveLength(0)
  await page.getByTestId('candidate-preview-clear').click()
  await expect(page.getByTestId('candidate-preview-text')).toHaveCount(0)
  await expect(drawer).toBeVisible()
  expect(audit.callsOf('preview')).toHaveLength(1)
})

test('read-only permission cannot request the frozen text projection', async ({ page, audit }) => {
  audit.permissions = ['business:audit:list', 'business:audit:query']
  await openAudit(page)
  await buttonByText(page.locator('.el-table__fixed-right'), '查看证据').click()
  await expect(page.getByTestId('candidate-preview')).toContainText('当前会话没有审核权限')
  await expect(page.getByTestId('candidate-preview-load')).toHaveCount(0)
  expect(audit.callsOf('preview')).toHaveLength(0)
  expect(audit.callsOf('review')).toHaveLength(0)
})

test('preview 409 clears content and requires explicit refreshed binding then explicit retry', async ({ page, audit }) => {
  await openAudit(page)
  await openEvidence(page)
  await page.getByTestId('candidate-preview-load').click()
  await expect(page.getByTestId('candidate-preview-bound')).toBeVisible()
  const pending = audit.holdNext('preview')
  await page.getByTestId('candidate-preview-load').click()
  await pending.seen
  await expect(page.getByTestId('candidate-preview-text')).toHaveCount(0)
  audit.tasks = [candidate({ version: 4 }, { snapshotId: 6102, contentHash: 'b'.repeat(64) })]
  pending.release({ json: { code: 409, errorCode: 'CONTENT_PREVIEW_CONFLICT' } })
  await expect(page.getByTestId('candidate-preview-error')).toContainText('候选绑定或任务版本已变化')
  await expect(page.getByTestId('candidate-preview-load')).toBeDisabled()
  const readCount = audit.callsOf('preview').length
  await page.getByTestId('candidate-preview-refresh').click()
  await expect(detailDrawer(page)).toContainText('审核任务版本：4')
  await expect(page.getByTestId('candidate-preview-load')).toBeEnabled()
  await expect(page.getByTestId('candidate-preview-text')).toHaveCount(0)
  expect(audit.callsOf('preview')).toHaveLength(readCount)
  const retry = audit.holdNext('preview')
  await page.getByTestId('candidate-preview-load').click()
  expect(requestBinding(await retry.seen)).toEqual({ expectedTaskVersion: '4', snapshotId: '6102', sourceConfigVersion: '12', contentHash: 'b'.repeat(64) })
  retry.release({ json: { code: 200, data: preview(audit.tasks[0]) } })
  await expect(page.getByTestId('candidate-preview-bound')).toBeVisible()
  expect(audit.callsOf('review')).toHaveLength(0)
})

for (const mutation of ['wrong-task', 'wrong-version', 'wrong-hash', 'complete-claim', 'unknown-key', 'forbidden-path', 'unsafe-text']) {
  test(`preview fails closed for ${mutation}, without rendering a valid prefix`, async ({ page, audit }) => {
    await openAudit(page)
    await openEvidence(page)
    const pending = audit.holdNext('preview')
    await page.getByTestId('candidate-preview-load').click()
    await pending.seen
    const dto = preview(audit.tasks[0])
    if (mutation === 'wrong-task') dto.auditTaskId = '9999'
    if (mutation === 'wrong-version') dto.auditTaskVersion++
    if (mutation === 'wrong-hash') dto.contentHash = 'c'.repeat(64)
    if (mutation === 'complete-claim') dto.previewComplete = true
    if (mutation === 'unknown-key') dto.rawCanonicalJson = 'SYNTHETIC_FORBIDDEN_RAW_CONTENT'
    if (mutation === 'forbidden-path') dto.entries.push({ path: 'solution.answer', text: 'SYNTHETIC_FORBIDDEN_ANSWER' })
    if (mutation === 'unsafe-text') dto.entries.push({ path: 'nodes[0].description', text: '<b>unescaped</b>' })
    pending.release({ json: { code: 200, data: dto } })
    await expect(page.getByTestId('candidate-preview-error')).toContainText('返回内容无法安全核对')
    await expect(page.getByTestId('candidate-preview-text')).toHaveCount(0)
    await expect(page.getByText('冻结合成节点', { exact: true })).toHaveCount(0)
    await expect(page.getByText('SYNTHETIC_FORBIDDEN', { exact: false })).toHaveCount(0)
    expect(audit.callsOf('preview')).toHaveLength(1)
    expect(audit.callsOf('review')).toHaveLength(0)
    if (mutation === 'unsafe-text') {
      // The reviewed Unicode correction must also fail closed in the real DOM.
      await detailDrawer(page).locator('.el-drawer__close-btn').click()
      await expect(detailDrawer(page)).toBeHidden()
      await openEvidence(page)
      const malformed = audit.holdNext('preview')
      await page.getByTestId('candidate-preview-load').click()
      await malformed.seen
      malformed.release({ json: { code: 200, data: preview(audit.tasks[0], {
        entries: [{ path: 'nodes[0].name', text: 'valid prefix' },
          { path: 'nodes[0].description', text: 'lone surrogate \uD800' }]
      }) } })
      await expect(page.getByTestId('candidate-preview-error')).toContainText('返回内容无法安全核对')
      await expect(page.getByTestId('candidate-preview-text')).toHaveCount(0)
      await expect(page.getByText('valid prefix', { exact: true })).toHaveCount(0)
      expect(audit.callsOf('preview')).toHaveLength(2)
      expect(audit.callsOf('review')).toHaveLength(0)
    }
  })
}

test('preview 403 removes old text and blocks further reads until authorization is restored', async ({ page, audit }) => {
  await openAudit(page)
  await openEvidence(page)
  await page.getByTestId('candidate-preview-load').click()
  await expect(page.getByTestId('candidate-preview-bound')).toBeVisible()
  const pending = audit.holdNext('preview')
  await page.getByTestId('candidate-preview-load').click()
  await pending.seen
  pending.release({ status: 403, json: { errorCode: 'CONTENT_PREVIEW_FORBIDDEN' } })
  await expect(page.getByTestId('candidate-preview-error')).toContainText('不允许读取预览')
  await expect(page.getByTestId('candidate-preview-text')).toHaveCount(0)
  await expect(page.getByTestId('candidate-preview-load')).toBeDisabled()
  expect(audit.callsOf('preview')).toHaveLength(2)
  expect(audit.callsOf('review')).toHaveLength(0)
})

for (const interruption of ['clear', 'close', 'route', 'session', 'leave-view', 'decision']) {
  test(`${interruption === 'session' ? '[browser-integration] ' : ''}late preview text is discarded after ${interruption}`, { tag: interruption === 'session' ? ['@integration'] : [] }, async ({ page, audit }) => {
    if (interruption === 'route') await prepareAuditHistory(page, `${AUDIT_PATH}?bizId=9101`)
    else if (interruption === 'leave-view') await prepareAuditHistory(page, '/audit-center/browser-away')
    else await openAudit(page)
    await openEvidence(page)
    const pending = audit.holdNext('preview')
    await page.getByTestId('candidate-preview-load').click()
    await pending.seen
    if (interruption === 'clear') await page.getByTestId('candidate-preview-clear').click()
    if (interruption === 'close') await detailDrawer(page).locator('.el-drawer__close-btn').click()
    if (interruption === 'route') await navigate(page, `${AUDIT_PATH}?bizId=9101`)
    if (interruption === 'session') await replaceSession(page)
    if (interruption === 'leave-view') {
      await navigate(page, '/audit-center/browser-away')
      await expect(page.getByRole('heading', { name: '统一审核台', exact: true })).toHaveCount(0)
    }
    if (interruption === 'decision') {
      await buttonByText(detailDrawer(page), '通过').click()
      await expect(reviewDialog(page)).toBeVisible()
    }
    await settleResponse(page, pending, { json: { code: 200, data: preview(audit.tasks[0]) } })
    await expect(page.getByTestId('candidate-preview-text')).toHaveCount(0)
    await expect(page.getByText('冻结合成节点', { exact: true })).toHaveCount(0)
    expect(audit.callsOf('preview')).toHaveLength(1)
    expect(audit.callsOf('review')).toHaveLength(0)
    if (interruption === 'leave-view') {
      await navigate(page, AUDIT_PATH)
      await openEvidence(page)
      await expect(page.getByTestId('candidate-preview-text')).toHaveCount(0)
      expect(audit.callsOf('preview')).toHaveLength(1)
    }
  })
}

test('a late preview 401 after closing the drawer cannot open a login prompt', async ({ page, audit }) => {
  await openAudit(page)
  await openEvidence(page)
  const pending = audit.holdNext('preview')
  await page.getByTestId('candidate-preview-load').click()
  await pending.seen
  await detailDrawer(page).locator('.el-drawer__close-btn').click()
  await expect(detailDrawer(page)).toBeHidden()
  await settleResponse(page, pending, { json: { code: 401 } })
  await expect(page.locator('.el-message-box')).toBeHidden()
  expect(audit.callsOf('logout')).toHaveLength(0)
  expect(audit.callsOf('review')).toHaveLength(0)
})

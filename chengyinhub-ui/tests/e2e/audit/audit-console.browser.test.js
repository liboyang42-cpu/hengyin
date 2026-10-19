const { test, expect, buttonByText, candidate, openAudit, openReview, reviewDialog, detailDrawer, navigate,
  replaceSession, expectSessionB, prepareAuditHistory, settleResponse, AUDIT_PATH } = require('./fixture')

for (const viewport of [{ name: 'desktop', width: 1440, height: 1000 }, { name: 'narrow', width: 768, height: 1000 }]) {
  test(`real candidate metadata and scope warning at ${viewport.name} viewport`, async ({ page, audit }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await openAudit(page)
    await expect(page.locator('.cy-data-table .cy-entity-link__name').first()).toHaveText('内容候选审核（不发布）')
    await buttonByText(page.locator('.el-table__fixed-right'), '查看证据').click()
    const drawer = detailDrawer(page)
    await expect(drawer).toBeVisible()
    await expect(drawer.getByText('候选绑定元数据（不含正文）', { exact: true })).toBeVisible()
    await expect(drawer).toContainText('候选绑定元数据不含正文')
    await expect(drawer).toContainText('通过仅记录候选审核结论，不构成完整发布、上架或授权')
    await expect(drawer).toContainText('候选快照 ID：6101')
    await expect(drawer).toContainText('主题 ID：9101')
    await expect(drawer).toContainText('来源配置版本：12')
    await expect(drawer).toContainText(`内容 SHA-256：${'a'.repeat(64)}`)
    await expect(drawer).toContainText('审核任务版本：3')
    await expect(drawer).toContainText('INCOMPLETE_CANDIDATE_ONLY')
    await expect(page.getByText('SYNTHETIC_BODY_MUST_NOT_BE_RENDERED', { exact: false })).toHaveCount(0)
    await expect.poll(async () => { const box = await drawer.boundingBox(); return box.x + box.width }).toBeLessThanOrEqual(viewport.width + 1)
    const bounds = await drawer.boundingBox()
    expect(bounds.x).toBeGreaterThanOrEqual(0)
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width + 1)
    const screenshotPath = testInfo.outputPath(`candidate-evidence-${viewport.name}.png`)
    await page.screenshot({ path: screenshotPath, fullPage: true, animations: 'disabled' })
    await testInfo.attach(`candidate-evidence-${viewport.name}`, { path: screenshotPath, contentType: 'image/png' })
    expect(audit.callsOf('review')).toHaveLength(0)
  })
}

test('legacy audit URL redirects to the real canonical route', async ({ page, audit }) => {
  await openAudit(page, '/p3-platform/audit?bizType=CONTENT_CAPTURE_REVIEW&bizId=9101')
  await expect(page).toHaveURL(/\/audit-center\/audit\?.*bizId=9101/)
  expect(audit.callsOf('list').some(call => call.query.includes('bizType=CONTENT_CAPTURE_REVIEW'))).toBe(true)
})

test('existing permission directive removes every review action for a read-only reviewer', async ({ page, audit }) => {
  audit.permissions = ['business:audit:list', 'business:audit:query']
  await openAudit(page)
  await expect(buttonByText(page, '通过')).toHaveCount(0)
  await expect(buttonByText(page, '拒绝')).toHaveCount(0)
  await buttonByText(page.locator('.el-table__fixed-right'), '查看证据').click()
  const drawer = detailDrawer(page)
  await expect(drawer).toContainText('候选快照 ID：6101')
  await expect(buttonByText(drawer, '通过')).toHaveCount(0)
  await expect(buttonByText(drawer, '拒绝')).toHaveCount(0)
  await expect(reviewDialog(page)).toBeHidden()
  expect(audit.callsOf('review')).toHaveLength(0)
})

test('double-click sends one write and successful feedback never claims publication', async ({ page, audit }) => {
  await openAudit(page)
  const dialog = await openReview(page)
  const pending = audit.holdNext('review')
  await buttonByText(dialog, '确认处理').click({ clickCount: 2 })
  const request = await pending.seen
  expect(request.body).toEqual({ taskId: 8101, expectedVersion: 3, status: 1, reasonText: null })
  await expect(buttonByText(dialog, '重试原请求')).toBeDisabled()
  await expect(buttonByText(dialog, '关闭并重新核对')).toBeDisabled()
  await expect(dialog.locator('textarea')).toBeDisabled()
  expect(audit.callsOf('review')).toHaveLength(1)
  audit.tasks = [candidate({ status: 1, version: 4 })]
  pending.release({ json: { code: 200 } })
  await expect(dialog).toBeHidden()
  await expect(page.getByText('候选审核结论已记录；未发布或授权', { exact: true })).toBeVisible()
  await expect.poll(() => audit.callsOf('list').length).toBe(2)
  expect(audit.callsOf('review')).toHaveLength(1)
})

test('reject requires a reason before a browser write is issued', async ({ page, audit }) => {
  await openAudit(page)
  const dialog = await openReview(page, '拒绝')
  await buttonByText(dialog, '确认处理').click()
  await expect(dialog).toContainText('拒绝审核必须填写处理原因。')
  expect(audit.callsOf('review')).toHaveLength(0)
  await dialog.locator('textarea').fill('合成证据不完整')
  const pending = audit.holdNext('review')
  await buttonByText(dialog, '确认处理').click()
  expect((await pending.seen).body).toEqual({ taskId: 8101, expectedVersion: 3, status: 2, reasonText: '合成证据不完整' })
  pending.release({ json: { code: 200 } })
  await expect(dialog).toBeHidden()
})

for (const errorCode of ['AUDIT_SNAPSHOT_STALE', 'AUDIT_STATE_CONFLICT']) {
  test(`${errorCode} refreshes evidence without automatic resubmission`, async ({ page, audit }) => {
    await openAudit(page)
    // The list is v3; entering the dialog must fetch v4 before accepting a decision.
    audit.tasks = [candidate({ version: 4 })]
    const dialog = await openReview(page)
    await expect(dialog).toContainText('审核任务版本：4')
    const pending = audit.holdNext('review')
    await buttonByText(dialog, '确认处理').click()
    expect((await pending.seen).body.expectedVersion).toBe(4)
    audit.tasks = [candidate({ version: 5 }, { snapshotId: 6102, contentHash: 'b'.repeat(64) })]
    pending.release({ json: { code: 409, errorCode, msg: 'Synthetic conflict' } })
    await expect(dialog).toContainText('本次未自动重提')
    await expect(dialog.getByRole('status')).toContainText('任务版本 5')
    await expect(buttonByText(dialog, '确认处理')).toBeDisabled()
    await expect(buttonByText(dialog, '重试原请求')).toHaveCount(0)
    expect(audit.callsOf('review')).toHaveLength(1)
    await expect.poll(() => audit.callsOf('list').length).toBe(2)
    await buttonByText(dialog, '关闭并重新核对').click()
    await expect(dialog).toBeHidden()
    const reopened = await openReview(page)
    await expect(reopened).toContainText('候选快照 ID：6102')
    await expect(reopened).toContainText(`内容 SHA-256：${'b'.repeat(64)}`)
    await expect(reopened).toContainText('审核任务版本：5')
    const next = audit.holdNext('review')
    await buttonByText(reopened, '确认处理').click()
    expect((await next.seen).body.expectedVersion).toBe(5)
    next.release({ json: { code: 200 } })
    await expect(reopened).toBeHidden()
    expect(audit.callsOf('review')).toHaveLength(2)
  })
}

for (const failure of ['network', 'server']) {
  test(`unknown ${failure} result retains the exact original retry payload`, async ({ page, audit }) => {
    await openAudit(page)
    const dialog = await openReview(page, '拒绝')
    await dialog.locator('textarea').fill('合成审核原因（原请求）')
    const pending = audit.holdNext('review')
    await buttonByText(dialog, '确认处理').click()
    const original = await pending.seen
    audit.tasks = [candidate({ version: 4, status: 2 })]
    pending.release(failure === 'network' ? { abort: 'failed' } : { status: 500, json: { code: 500, msg: 'Synthetic unknown result' } })
    await expect(dialog).toContainText('未收到可确认的处理结果')
    await expect(dialog.getByRole('status')).toContainText('任务版本 4')
    await expect(dialog.locator('textarea')).toBeDisabled()
    await expect(dialog.locator('textarea')).toHaveValue('合成审核原因（原请求）')
    await expect(buttonByText(dialog, '重试原请求')).toBeEnabled()
    expect(audit.callsOf('review')).toHaveLength(1)
    // The ordinary request layer's 1-second duplicate fence remains unchanged.
    await page.waitForTimeout(1100)
    const retry = audit.holdNext('review')
    await buttonByText(dialog, '重试原请求').click()
    const repeated = await retry.seen
    expect(repeated.rawBody).toBe(original.rawBody)
    expect(repeated.body.expectedVersion).toBe(3)
    retry.release({ json: { code: 200 } })
    await expect(dialog).toBeHidden()
    expect(audit.callsOf('review')).toHaveLength(2)
  })
}

test('a delayed detail response cannot reopen a closed evidence drawer', async ({ page, audit }) => {
  await openAudit(page)
  const pending = audit.holdNext('detail')
  await buttonByText(page.locator('.el-table__fixed-right'), '查看证据').click()
  await pending.seen
  const drawer = detailDrawer(page)
  await drawer.locator('.el-drawer__close-btn').click()
  await expect(drawer).toBeHidden()
  await settleResponse(page, pending, { json: { code: 200, task: candidate(), records: [], reportCount: 99 } })
  await expect(drawer).toBeHidden()
  expect(audit.callsOf('review')).toHaveLength(0)
})

for (const oldResponse of ['success', '401', '500']) {
  test(`retired route list ${oldResponse} cannot replace a newer queue or show an old error`, async ({ page, audit }) => {
    await prepareAuditHistory(page, `${AUDIT_PATH}?bizId=9102`)
    const pending = audit.holdNext('list')
    await buttonByText(page, '刷新队列').click()
    await pending.seen
    audit.tasks = [candidate({ id: 8102, bizId: 9102 })]
    await navigate(page, `${AUDIT_PATH}?bizId=9102`)
    await expect(page.getByText('任务 #8102 · 候选审核 · 版本 3').first()).toBeVisible()
    const json = oldResponse === 'success' ? { code: 200, rows: [candidate()], total: 1 }
      : { code: Number(oldResponse), msg: 'SYNTHETIC_RETIRED_ERROR' }
    await settleResponse(page, pending, { json })
    await expect(page.getByText('任务 #8102 · 候选审核 · 版本 3').first()).toBeVisible()
    await expect(page.getByText('任务 #8101 · 候选审核 · 版本 3')).toHaveCount(0)
    await expect(page.locator('.el-message-box')).toBeHidden()
    await expect(page.getByText('SYNTHETIC_RETIRED_ERROR')).toHaveCount(0)
    expect(audit.callsOf('logout')).toHaveLength(0)
  })
}

for (const interruption of ['route', 'session', 'leave-view']) {
  for (const result of ['success', '401']) {
    test(`${interruption === 'session' ? '[browser-integration] ' : ''}late review ${result} is retired after ${interruption}`, { tag: interruption === 'session' ? ['@integration'] : [] }, async ({ page, context, audit }) => {
      if (interruption === 'session') await openAudit(page)
      else await prepareAuditHistory(page, interruption === 'route' ? `${AUDIT_PATH}?bizId=9102` : '/audit-center/browser-away')
      const dialog = await openReview(page)
      const pending = audit.holdNext('review')
      await buttonByText(dialog, '确认处理').click()
      await pending.seen
      audit.tasks = [candidate({ id: 8102, bizId: 9102 })]
      if (interruption === 'session') await replaceSession(page)
      else if (interruption === 'route') await navigate(page, `${AUDIT_PATH}?bizId=9102`)
      else {
        await navigate(page, '/audit-center/browser-away')
        await expect(page.getByRole('heading', { name: '统一审核台', exact: true })).toHaveCount(0)
      }
      await expect(dialog).toBeHidden()
      if (interruption !== 'leave-view') await expect(page.getByText('任务 #8102 · 候选审核 · 版本 3').first()).toBeVisible()
      const listsBefore = audit.callsOf('list').length
      await settleResponse(page, pending, { json: result === 'success' ? { code: 200 } : { code: 401, msg: 'Synthetic expired old session' } })
      await expect(dialog).toBeHidden()
      await expect(page.locator('.el-message-box')).toBeHidden()
      await expect(page.getByText('候选审核结论已记录；未发布或授权', { exact: true })).toHaveCount(0)
      expect(audit.callsOf('review')).toHaveLength(1)
      expect(audit.callsOf('list')).toHaveLength(listsBefore)
      expect(audit.callsOf('logout')).toHaveLength(0)
      if (interruption === 'session') await expectSessionB(page, context)
      if (interruption === 'leave-view') {
        await navigate(page, AUDIT_PATH)
        await expect(page.getByText('任务 #8102 · 候选审核 · 版本 3').first()).toBeVisible()
      }
    })
  }
}

for (const interruption of ['route', 'session']) {
  test(`${interruption === 'session' ? '[browser-integration] ' : ''}an already visible 401 dialog cannot log out the newer ${interruption} context`, { tag: interruption === 'session' ? ['@integration'] : [] }, async ({ page, context, audit }) => {
    if (interruption === 'session') await openAudit(page)
    else await prepareAuditHistory(page, `${AUDIT_PATH}?bizId=9101`)
    const pending = audit.holdNext('list')
    await buttonByText(page, '刷新队列').click()
    await pending.seen
    pending.release({ json: { code: 401 } })
    const prompt = page.locator('.el-message-box')
    await expect(prompt).toBeVisible()
    await expect(prompt).toContainText('登录状态已过期')
    if (interruption === 'session') await replaceSession(page)
    else await navigate(page, `${AUDIT_PATH}?bizId=9101`)
    await expect(buttonByText(page, '查看证据')).toBeVisible()
    await buttonByText(prompt, '重新登录').click()
    await expect(prompt).toBeHidden()
    await expect(page).toHaveURL(/\/audit-center\/audit/)
    expect(audit.callsOf('logout')).toHaveLength(0)
    if (interruption === 'session') await expectSessionB(page, context)
  })
}

test('[browser-integration] a delayed logout response cannot clear or redirect a replacement session', { tag: '@integration' }, async ({ page, context, audit }) => {
  await openAudit(page)
  const pending = audit.holdNext('list')
  await buttonByText(page, '刷新队列').click()
  await pending.seen
  pending.release({ json: { code: 401 } })
  const prompt = page.locator('.el-message-box')
  await expect(prompt).toBeVisible()
  const logout = audit.holdNext('logout')
  await buttonByText(prompt, '重新登录').click()
  await logout.seen
  await replaceSession(page)
  await expect(buttonByText(page, '查看证据')).toBeVisible()
  await settleResponse(page, logout, { json: { code: 200 } })
  await expectSessionB(page, context)
  await expect(page).toHaveURL(/\/audit-center\/audit/)
  expect(audit.callsOf('logout')).toHaveLength(1)
})

test('unauthenticated audit deep link shows the real login guard without fetching audit data', async ({ page, context, audit }) => {
  await context.clearCookies()
  await page.goto(`${AUDIT_PATH}?bizId=9101`)
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await expect(page.getByPlaceholder('账号', { exact: true })).toBeVisible()
  await expect(page.getByPlaceholder('密码', { exact: true })).toBeVisible()
  await expect(buttonByText(page, '查看证据')).toHaveCount(0)
  await expect.poll(() => audit.callsOf('captcha').length).toBe(1)
  for (const kind of ['info', 'routers', 'list', 'detail', 'preview', 'review', 'logout']) {
    expect(audit.callsOf(kind), `No ${kind} request before authentication`).toHaveLength(0)
  }
})

test('Back and Forward cannot restore a retired pending decision dialog', async ({ page, audit }) => {
  await prepareAuditHistory(page, `${AUDIT_PATH}?bizId=9102`)
  const dialog = await openReview(page)
  const pending = audit.holdNext('review')
  await buttonByText(dialog, '确认处理').click()
  await pending.seen
  audit.tasks = [candidate({ id: 8102, bizId: 9102 })]
  await navigate(page, `${AUDIT_PATH}?bizId=9102`)
  await expect(page.getByText('任务 #8102 · 候选审核 · 版本 3').first()).toBeVisible()
  await page.goForward()
  await expect(page).toHaveURL(new RegExp(`${AUDIT_PATH}$`))
  await expect(dialog).toBeHidden()
  await page.goBack()
  await expect(page).toHaveURL(/bizId=9102/)
  await expect(page.getByText('任务 #8102 · 候选审核 · 版本 3').first()).toBeVisible()
  await settleResponse(page, pending, { json: { code: 200 } })
  await expect(dialog).toBeHidden()
  await expect(page.getByText('候选审核结论已记录；未发布或授权', { exact: true })).toHaveCount(0)
  expect(audit.callsOf('review')).toHaveLength(1)
})

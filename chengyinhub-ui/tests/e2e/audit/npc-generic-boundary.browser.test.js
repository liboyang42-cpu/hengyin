const { test, expect, buttonByText, candidate, settleResponse, openAudit, detailDrawer, AUDIT_PATH } = require('./fixture')

function npc(overrides = {}) {
  return { ...candidate(), bizType: 'MERCHANT_NPC_KNOWLEDGE_DRAFT', sourceType: 'merchant_npc_public_facts_draft_v1',
    snapshotJson: '{"draft":"SYNTHETIC_NPC_SECRET_BODY","owner":"SYNTHETIC_NPC_SECRET_OWNER"}', ...overrides }
}
async function noNpc(page, audit) {
  await expect(page.locator('body')).not.toContainText('SYNTHETIC_NPC_SECRET')
  expect(audit.callsOf('review')).toHaveLength(0)
  expect(audit.callsOf('preview')).toHaveLength(0)
}

for (const [name, overrides] of [['both markers', {}], ['business only', { sourceType: null }], ['source only', { bizType: 'TOPIC' }]]) {
  test(`legacy NPC list is closed before count/render: ${name}`, async ({ page, audit }) => {
    const gate = audit.holdNext('list')
    await page.goto(AUDIT_PATH)
    await expect(page.getByRole('heading', { name: '统一审核台', exact: true })).toBeVisible()
    await gate.seen
    await settleResponse(page, gate, { json: { code: 200, rows: [npc(overrides)], total: 999 } })
    await expect(page.locator('.el-table__body-wrapper .el-table__row')).toHaveCount(0)
    await expect(page.locator('.cy-data-table__summary strong')).toHaveText('0 项结果')
    await expect(page.locator('.el-pagination__total')).not.toBeVisible()
    await noNpc(page, audit)
  })

  test(`legacy NPC detail never renders raw snapshot or actions: ${name}`, async ({ page, audit }) => {
    await openAudit(page)
    const gate = audit.holdNext('detail')
    await buttonByText(page.locator('.el-table__fixed-right'), '查看证据').click()
    await gate.seen
    await settleResponse(page, gate, { json: { code: 200, task: npc(overrides),
      records: [{ reasonText: 'SYNTHETIC_NPC_SECRET_RECORD' }], reportCount: 999 } })
    await expect(detailDrawer(page)).toContainText('审核证据加载失败，未使用队列中的旧快照。请重新加载。')
    await expect(buttonByText(detailDrawer(page), '通过')).toHaveCount(0)
    await expect(buttonByText(detailDrawer(page), '拒绝')).toHaveCount(0)
    await noNpc(page, audit)
  })
}

test('closed detail does not accept a late legacy NPC response', async ({ page, audit }) => {
  await openAudit(page)
  const gate = audit.holdNext('detail')
  await buttonByText(page.locator('.el-table__fixed-right'), '查看证据').click()
  await gate.seen
  await detailDrawer(page).locator('.el-drawer__close-btn').click()
  await settleResponse(page, gate, { json: { code: 200, task: npc(), records: [], reportCount: 999 } })
  await expect(detailDrawer(page)).not.toBeVisible()
  await noNpc(page, audit)
})

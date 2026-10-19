// Synthetic external-session integration only. No user-flow/keep-alive claims.
const { expect } = require('@playwright/test')
const TOKEN_B = 'synthetic-audit-session-B-not-a-real-credential'
const PERMISSIONS = ['business:audit:list', 'business:audit:query', 'business:audit:review']
async function replaceSession(page) {
  await page.evaluate(({ token, permissions }) => {
    const store = document.querySelector('#app').__vue__.$store
    document.cookie = `Admin-Token=${token}; path=/`
    store.commit('SET_TOKEN', token)
    store.commit('SET_ID', 7002)
    store.commit('SET_ROLES', ['synthetic_replacement_auditor'])
    store.commit('SET_PERMISSIONS', permissions)
  }, { token: TOKEN_B, permissions: PERMISSIONS })
}
async function expectSessionB(page, context) {
  await expect.poll(() => page.evaluate(() => document.querySelector('#app').__vue__.$store.getters.token)).toBe(TOKEN_B)
  expect((await context.cookies()).find(cookie => cookie.name === 'Admin-Token').value).toBe(TOKEN_B)
}
module.exports = { replaceSession, expectSessionB }

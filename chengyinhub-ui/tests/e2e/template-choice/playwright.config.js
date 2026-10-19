/** Separate seven-case suite. Existing audit43 config, identities and gate stay unchanged. */
const path = require('node:path')
const { defineConfig } = require('@playwright/test')
const UI_ROOT = path.resolve(__dirname, '../../..')
const EVIDENCE = path.resolve(__dirname, '../evidence/template-choice-browser')
module.exports = defineConfig({
  testDir: '.', testMatch: 'editor.browser.test.js', outputDir: path.join(EVIDENCE, 'results'),
  timeout: 45000, globalTimeout: 6 * 60 * 1000, expect: { timeout: 10000 },
  fullyParallel: false, forbidOnly: true, retries: 0, failOnFlakyTests: true, workers: 1,
  projects: [{ name: 'template-choice-chromium', use: { browserName: 'chromium' } }],
  reporter: [['list'], [require.resolve('./gate')], ['json', { outputFile: path.join(EVIDENCE, 'report.json') }]],
  use: { baseURL: 'http://127.0.0.1:4173', browserName: 'chromium', viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block', trace: 'retain-on-failure', screenshot: 'only-on-failure', actionTimeout: 10000 },
  webServer: { command: 'node tests/e2e/server/audit-static-server.js', cwd: UI_ROOT, url: 'http://127.0.0.1:4173/__audit_browser_health', reuseExistingServer: false, timeout: 15000 }
})

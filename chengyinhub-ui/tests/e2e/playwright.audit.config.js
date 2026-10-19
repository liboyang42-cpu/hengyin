/** Isolated synthetic API suite against the actual production Vue application. */
const path = require('node:path')
const { defineConfig } = require('@playwright/test')
const UI_ROOT = path.resolve(__dirname, '../..')
const EVIDENCE = path.join(__dirname, 'evidence/audit-browser')

module.exports = defineConfig({
  testDir: './audit',
  testMatch: '**/*.browser.test.js',
  outputDir: path.join(EVIDENCE, 'results'),
  timeout: 45000,
  globalTimeout: 8 * 60 * 1000,
  expect: { timeout: 10000 },
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  failOnFlakyTests: true,
  projects: [{ name: 'audit-chromium', use: { browserName: 'chromium' } }],
  workers: 1,
  reporter: [
    ['list'],
    [require.resolve('./audit-gate-reporter')],
    ['json', { outputFile: path.join(EVIDENCE, 'report.json') }],
    ['html', { outputFolder: path.join(EVIDENCE, 'html'), open: 'never' }]
  ],
  use: {
    // Intentionally no E2E_BASE_URL override: never target a live admin/API.
    baseURL: 'http://127.0.0.1:4173',
    browserName: 'chromium',
    viewport: { width: 1440, height: 1000 },
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10000
  },
  webServer: {
    command: 'node tests/e2e/server/audit-static-server.js',
    cwd: UI_ROOT,
    url: 'http://127.0.0.1:4173/__audit_browser_health',
    reuseExistingServer: false,
    timeout: 15000
  }
})

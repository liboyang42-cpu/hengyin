/**
 * Playwright E2E 配置 — 管理后台 (chengyinhub-ui)
 *
 * 用途: 启动 dev server 或连接已运行实例,按角色跑浏览器测试。
 *
 * 注意: 当前为骨架配置。真实业务页面 E2E 断言需等
 * Worktree A/B/C 合并进 master 后,在 P2-2/P2-3 阶段 rebase 最新 master 再补。
 */

const { defineConfig } = require('@playwright/test')

module.exports = defineConfig({
  testDir: './',
  testMatch: '**/*.e2e.test.js',

  timeout: 60000,
  expect: { timeout: 10000 },

  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,

  reporter: [
    ['list'],
    ['json', { outputFile: 'tests/e2e/evidence/e2e-report.json' }],
  ],

  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:80',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15000,
    launchOptions: {
      args: ['--headless=new'],
    },
  },

  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
  ],

  webServer: {
    command: 'npx http-server dist -p 8090 --proxy http://localhost:8080',
    port: 8090,
    reuseExistingServer: true,
    timeout: 120000,
    cwd: './',
  },
})

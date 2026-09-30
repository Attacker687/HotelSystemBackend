const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './src/test/e2e',
  timeout: 300_000,
  expect: { timeout: 10_000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  outputDir: `./target/e2e/artifacts/${process.env.E2E_CASE || 'all'}`,
  reporter: [['line'], ['junit', { outputFile: process.env.E2E_REPORT || 'target/e2e/playwright.xml' }]],
  use: {
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
    baseURL: process.env.E2E_BASE_URL,
    browserName: 'chromium',
    headless: true,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    screenshot: 'only-on-failure',
    trace: 'on'
  }
});

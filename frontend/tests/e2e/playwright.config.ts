import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:8068',
    trace: 'retain-on-failure',
    viewport: { width: 1440, height: 900 },
  },
  webServer: {
    command: 'npm run dev -- --port 8068',
    env: { API_PROXY_TARGET: 'http://127.0.0.1:9' },
    url: 'http://127.0.0.1:8068',
    reuseExistingServer: false,
  },
  projects: [
    {
      name: '桌面端 Chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
})

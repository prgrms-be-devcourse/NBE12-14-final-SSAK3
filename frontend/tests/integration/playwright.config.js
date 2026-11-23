import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'

// 별도 테스트 DB를 쓰는 실제 백엔드를 18080에 먼저 실행한다.
const baseURL = 'http://127.0.0.1:15173'
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.js',
  workers: 1,
  outputDir: '../../playwright-report/room-integration',
  use: { baseURL },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 15173 --strictPort',
    cwd: fileURLToPath(new URL('../../', import.meta.url)),
    env: { DEV_BACKEND_URL: 'http://127.0.0.1:18080' },
    url: baseURL,
    reuseExistingServer: false,
  },
})

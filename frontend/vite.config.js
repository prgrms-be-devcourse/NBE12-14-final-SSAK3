import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 별도 서버로 통합 테스트할 때에도 REST와 WebSocket이 같은 백엔드에 연결되어야 한다.
const backendUrl = process.env.DEV_BACKEND_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': backendUrl,
      '/ws': { target: backendUrl, ws: true },
    },
  },
})

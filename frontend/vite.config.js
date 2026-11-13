import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 로컬 개발: /api, /ws 는 백엔드(8080)로 전달. 배포에서는 Nginx가 같은 역할
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': mode === 'demo' ? 'http://localhost:8081' : 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
}))

import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// 开发服务器把 /api 转给本机后端;端到端测试时指向一个空端口,接口全部由测试打桩
const apiProxyTarget = process.env.API_PROXY_TARGET ?? 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 8067,
    strictPort: true,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: false,
      },
      '/scratch': {
        target: 'http://127.0.0.1:8069',
        changeOrigin: false,
        rewrite: (path) => path.replace(/^\/scratch/, ''),
      },
    },
  },
  preview: {
    host: '0.0.0.0',
    port: 8067,
    strictPort: true,
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    chunkSizeWarningLimit: 750,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
    clearMocks: true,
    globals: true,
    include: ['tests/unit/**/*.test.ts'],
  },
})

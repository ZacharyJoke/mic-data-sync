/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 构建与本地开发服务器配置
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // '@' 指向 src 目录，便于模块引用
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 开发代理：前后端分离开发时转发到本地后端端口
    proxy: {
      '/api': {
        target: 'http://localhost:19090',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:19090',
        changeOrigin: true,
      },
    },
  },
  test: {
    // 单元测试在 jsdom 环境下运行
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    exclude: ['e2e/**', 'node_modules/**'],
  },
})

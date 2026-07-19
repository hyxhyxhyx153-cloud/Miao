import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // Keep the proxy scoped to the real API namespace. A broad `/api`
      // matcher also captures the client-side `/api-keys` page and turns a
      // normal SPA navigation/direct entry into a backend 404.
      '/api/v1': { target: 'http://localhost:3000', changeOrigin: true },
      '/health': { target: 'http://localhost:3000', changeOrigin: true }
    }
  }
})

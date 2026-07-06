import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev proxy: the app calls same-origin /api/*, Vite forwards to the Spring backend on :8080.
// This means NO CORS config is needed on the backend for local development.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});

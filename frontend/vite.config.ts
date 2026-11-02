import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The proxy forwards /api calls to Spring Boot, so the browser sees one origin (no CORS setup needed).
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080' } },
});

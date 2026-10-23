import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    server: {
        // the management server's CORS pattern must admit this origin, http://localhost:5175 (see the root README)
        port: 5175,
        strictPort: true,
        // the Summit 42 server, which serves this route beside the built UI
        proxy: {
            '/api/signup': 'http://localhost:8080',
        },
    },
    preview: {
        port: 5175,
        strictPort: true,
    },
    build: {
        outDir: 'dist',
        sourcemap: true,
    },
})

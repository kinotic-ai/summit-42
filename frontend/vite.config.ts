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
        // the management server's development CORS pattern admits this origin, http://localhost:5175
        port: 5175,
        strictPort: true,
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

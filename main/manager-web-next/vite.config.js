import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxy = { '/xiaozhi': { target: env.API_PROXY_TARGET || env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:18893', changeOrigin: true, ws: true } };
  return {
    base: env.VITE_PUBLIC_PATH || '/',
    plugins: [vue()],
    resolve: {
      alias: [
        { find: '@', replacement: fileURLToPath(new URL('./src', import.meta.url)) },
        { find: /^vuex$/, replacement: fileURLToPath(new URL('./src/adapters/store-helpers.js', import.meta.url)) },
        { find: /^v-code-diff$/, replacement: 'v-code-diff/vue3' },
      ],
      dedupe: ['vue', 'pinia'],
    },
    define: {
      'import.meta.env.VITE_API_BASE_URL': JSON.stringify(env.VITE_API_BASE_URL || '/xiaozhi'),
      'import.meta.env.VITE_CONVERSATION_ENABLED': JSON.stringify(env.VITE_CONVERSATION_ENABLED || 'true'),
      'import.meta.env.VITE_ENV_LABEL': JSON.stringify(env.VITE_ENV_LABEL || 'Dev Vue 3'),
    },
    server: { host: '127.0.0.1', port: 18894, strictPort: true, proxy },
    preview: { host: '127.0.0.1', port: 18895, strictPort: true, proxy },
    build: { sourcemap: false, chunkSizeWarningLimit: 1600 },
    css: { preprocessorOptions: { scss: { silenceDeprecations: ['legacy-js-api', 'import', 'global-builtin', 'color-functions'] } } },
    test: {
      environment: 'jsdom',
      globals: false,
      setupFiles: ['./tests/setup.js'],
      include: ['tests/**/*.test.{js,mjs}'],
      exclude: ['tests/*Contract.test.mjs', 'node_modules/**'],
      pool: 'forks',
      maxWorkers: 3,
      coverage: { provider: 'v8', include: ['src/utils/**', 'src/stores/**', 'src/apis/**'] },
    },
  };
});

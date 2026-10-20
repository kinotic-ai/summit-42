<template>
  <main class="auth">
    <div class="auth__inner">
      <RouterLink to="/" class="auth__brand"><BrandMark /></RouterLink>
      <section class="auth__card">
        <h1 class="auth__title">{{ title }}</h1>
        <p v-if="subtitle" class="auth__subtitle">{{ subtitle }}</p>
        <slot />
      </section>
      <footer v-if="$slots.footer" class="auth__footer">
        <slot name="footer" />
      </footer>
    </div>
  </main>
</template>

<script setup lang="ts">
import BrandMark from '@/components/BrandMark.vue'

defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<style scoped>
.auth {
  min-height: 100%;
  display: flex;
  justify-content: center;
  padding: 3rem 1rem;
  background:
    radial-gradient(60rem 30rem at 50% -10%, var(--accent-soft), transparent 70%),
    var(--bg);
}

.auth__inner {
  width: 100%;
  max-width: 26rem;
}

.auth__brand {
  display: flex;
  justify-content: center;
  margin-bottom: 1.5rem;
  color: var(--text);
}

.auth__brand:hover {
  text-decoration: none;
}

.auth__card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  padding: 1.75rem 1.5rem;
}

.auth__title {
  margin: 0 0 0.25rem;
  font-size: 1.4rem;
  letter-spacing: -0.02em;
}

.auth__subtitle {
  margin: 0 0 1.5rem;
  color: var(--text-muted);
}

.auth__footer {
  margin-top: 1.25rem;
  text-align: center;
  color: var(--text-muted);
  font-size: 0.95rem;
}

@media (max-width: 480px) {
  .auth {
    padding: 1.5rem 0.75rem;
  }

  .auth__card {
    padding: 1.4rem 1.1rem;
  }
}
</style>

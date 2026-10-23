<template>
  <main :class="['auth', 'photo-bg', { 'auth--hero': $slots.hero }]">
    <div v-if="$slots.hero" class="auth__hero">
      <RouterLink to="/" class="auth__brand"><BrandMark /></RouterLink>
      <slot name="hero" />
    </div>
    <div class="auth__inner">
      <RouterLink v-if="!$slots.hero" to="/" class="auth__brand auth__brand--card"><BrandMark /></RouterLink>
      <section class="auth__card glass">
        <h1 class="auth__title">{{ title }}</h1>
        <p v-if="subtitle" class="auth__subtitle">{{ subtitle }}</p>
        <slot />
        <footer v-if="$slots.footer" class="auth__footer">
          <slot name="footer" />
        </footer>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import BrandMark from '@/components/BrandMark.vue'

/** The page around a sign-in or sign-up form: the photo, the brand, and a card holding the form. */
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<style scoped>
.auth {
  --photo-dim: 0.2;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4rem;
  padding: 3rem 1rem;
}

.auth__hero {
  max-width: 32rem;
  text-shadow: 0 2px 18px rgba(0, 0, 0, 0.45);
}

.auth__inner {
  --card-padding-x: 1.6rem;
  width: 100%;
  max-width: 25rem;
}

.auth--hero .auth__inner {
  flex: none;
}

.auth__brand {
  display: inline-flex;
  margin-bottom: 1.5rem;
  color: var(--text);
}

/* lines up with the card's left-aligned title, which a centered brand looks shifted right of */
.auth__brand--card {
  display: flex;
  padding-left: var(--card-padding-x);
}

.auth__card {
  padding: 1.75rem var(--card-padding-x);
  border-color: rgba(255, 255, 255, 0.1);
  border-radius: 18px;
  box-shadow: var(--shadow);
}

.auth__title {
  margin: 0 0 0.25rem;
  font-size: 1.35rem;
  font-weight: 600;
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
  font-size: 0.92rem;
}

@media (max-width: 860px) {
  .auth--hero {
    flex-direction: column;
    gap: 1.5rem;
  }

  .auth__hero {
    text-align: center;
  }
}

@media (max-width: 480px) {
  .auth {
    padding: 1.5rem 0.75rem;
  }

  .auth__inner {
    --card-padding-x: 1.1rem;
  }

  .auth__card {
    padding-top: 1.4rem;
    padding-bottom: 1.4rem;
  }
}
</style>

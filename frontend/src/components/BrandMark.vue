<template>
  <span class="brand">
    <svg class="brand__logo" viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="8" fill="var(--accent)" />
      <path d="M6 24 L14 10 L18 17 L21 13 L26 24 Z" fill="var(--bg)" />
    </svg>
    <span class="brand__name">Summit 42</span>
  </span>
</template>

<style scoped>
.brand {
  display: inline-flex;
  align-items: center;
  gap: 0.55rem;
  font-weight: 600;
  font-size: 1rem;
  color: var(--text);
  letter-spacing: -0.01em;
}

.brand__logo {
  width: 1.75rem;
  height: 1.75rem;
  flex: none;
}
</style>

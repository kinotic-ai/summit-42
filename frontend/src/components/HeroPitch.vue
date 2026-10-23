<template>
  <div>
    <h1 class="hero__title">Describe an app.<br />Watch it go live.</h1>
    <p class="hero__text">Chat about what you need. Summit 42 builds it, deploys it, and keeps changing it as you talk.</p>
  </div>
</template>

<style scoped>
.hero__title {
  margin: 0 0 0.75rem;
  font-size: clamp(2.2rem, 5vw, 3.25rem);
  font-weight: 700;
  line-height: 1.05;
  letter-spacing: -0.035em;
}

.hero__text {
  margin: 0;
  max-width: 28rem;
  font-size: 1.1rem;
  color: var(--text-2);
}

@media (max-width: 860px) {
  .hero__text {
    margin: 0 auto;
  }
}
</style>

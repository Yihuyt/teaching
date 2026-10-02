<template>
  <svg class="brand-mark" viewBox="0 0 64 64" aria-hidden="true" focusable="false">
    <defs>
      <linearGradient id="brand-mark-bg" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#3b7ddb" />
        <stop offset="1" stop-color="#1c4d98" />
      </linearGradient>
    </defs>
    <rect width="64" height="64" rx="16" fill="url(#brand-mark-bg)" />
    <path d="M32 17 53 27 32 37 11 27Z" fill="#fff" />
    <path d="M20 32.5v8.5c7.2 6.4 16.8 6.4 24 0v-8.5L32 38.2Z" fill="#fff" opacity="0.92" />
    <path d="M52.5 27.5v10" stroke="#fff" stroke-width="2.2" stroke-linecap="round" />
    <circle cx="52.5" cy="40" r="2.4" fill="#ffd166" />
    <path d="M47 9l1.5 3.5L52 14l-3.5 1.5L47 19l-1.5-3.5L42 14l3.5-1.5Z" fill="#ffd166" />
  </svg>
</template>

<style scoped>
.brand-mark {
  display: block;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 9px;
  box-shadow: 0 1px 2px rgb(16 40 84 / 0.18);
}
</style>

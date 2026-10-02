<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <header class="page-header">
    <div class="page-header__copy">
      <div class="page-header__title-row">
        <h1>{{ title }}</h1>
        <slot name="title-extra" />
      </div>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.page-header__copy {
  min-width: 0;
}

.page-header__title-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

h1 {
  margin: 0;
  color: var(--text);
  font-size: 24px;
  font-weight: 650;
  line-height: 1.35;
}

p {
  max-width: 760px;
  margin: 4px 0 0;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.55;
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}
</style>

<template>
  <header class="cy-page-header">
    <div class="cy-page-header__copy">
      <p v-if="eyebrow" class="cy-page-header__eyebrow">{{ eyebrow }}</p>
      <div class="cy-page-header__title-row">
        <h1>{{ title }}</h1>
        <span v-if="status" class="cy-page-header__status">{{ status }}</span>
      </div>
      <p v-if="description" class="cy-page-header__description">{{ description }}</p>
      <p v-if="updatedAt" class="cy-page-header__updated">数据更新于 {{ updatedAt }}</p>
    </div>
    <div v-if="$slots.default || $slots.primary" class="cy-page-header__actions">
      <slot />
      <slot name="primary" />
    </div>
  </header>
</template>

<script>
export default {
  name: 'CyPageHeader',
  props: {
    eyebrow: { type: String, default: '' },
    title: { type: String, required: true },
    description: { type: String, default: '' },
    status: { type: String, default: '' },
    updatedAt: { type: String, default: '' }
  }
}
</script>

<style lang="scss" scoped>
.cy-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--cy-space-6);
  margin-bottom: var(--cy-space-6);
}

.cy-page-header__copy {
  min-width: 0;
}

.cy-page-header__eyebrow {
  margin: 0 0 var(--cy-space-2);
  color: var(--cy-color-brand);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .12em;
}

.cy-page-header__title-row {
  display: flex;
  align-items: center;
  gap: var(--cy-space-3);
}

h1 {
  margin: 0;
  color: var(--cy-color-text-primary);
  font-size: 24px;
  font-weight: 700;
  line-height: 1.35;
}

.cy-page-header__status {
  padding: var(--cy-space-1) var(--cy-space-2);
  border-radius: 999px;
  background: var(--cy-color-status-success-soft);
  color: var(--cy-color-status-success);
  font-size: 12px;
  font-weight: 700;
}

.cy-page-header__description {
  max-width: 760px;
  margin: var(--cy-space-2) 0 0;
  color: var(--cy-color-text-secondary);
  font-size: 14px;
  line-height: 1.65;
}

.cy-page-header__updated {
  margin: var(--cy-space-2) 0 0;
  color: var(--cy-color-text-tertiary);
  font-size: 12px;
}

.cy-page-header__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--cy-space-2);
}

@media (max-width: 900px) {
  .cy-page-header {
    flex-direction: column;
  }
}
</style>

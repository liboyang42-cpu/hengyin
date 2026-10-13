<template>
  <span class="cy-status-tag" :class="`cy-status-tag--${tone}`">
    <span class="cy-status-tag__dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<script>
export default {
  name: 'CyStatusTag',
  props: {
    label: { type: String, required: true },
    tone: {
      type: String,
      default: 'neutral',
      validator: value => ['neutral', 'brand', 'success', 'warning', 'danger', 'info'].includes(value)
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-status-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--cy-space-0-5);
  min-height: 24px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--cy-color-bg-subtle);
  color: var(--cy-color-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.cy-status-tag__dot {
  width: var(--cy-space-0-5);
  height: var(--cy-space-0-5);
  border-radius: 50%;
  background: currentColor;
}

.cy-status-tag--brand { background: var(--cy-color-brand-soft); color: var(--cy-color-brand); }
.cy-status-tag--success { background: var(--cy-color-status-success-soft); color: var(--cy-color-status-success); }
.cy-status-tag--warning { background: var(--cy-color-status-warning-soft); color: var(--cy-color-status-warning); }
.cy-status-tag--danger { background: var(--cy-color-status-danger-soft); color: var(--cy-color-status-danger); }
.cy-status-tag--info { background: var(--cy-color-status-info-soft); color: var(--cy-color-status-info); }
</style>

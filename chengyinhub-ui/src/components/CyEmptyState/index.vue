<template>
  <section class="cy-empty-state" role="status" aria-live="polite">
    <div class="cy-empty-state__icon" aria-hidden="true"><i :class="icon" /></div>
    <div class="cy-empty-state__body">
      <h3>{{ title }}</h3>
      <p v-if="description">{{ description }}</p>
      <el-button
        v-if="actionText"
        type="primary"
        plain
        size="small"
        :loading="actionLoading"
        :disabled="actionLoading"
        @click="triggerAction"
      >{{ actionText }}</el-button>
    </div>
  </section>
</template>

<script>
export default {
  name: 'CyEmptyState',
  props: {
    title: { type: String, required: true },
    description: { type: String, default: '' },
    icon: { type: String, default: 'el-icon-document-remove' },
    actionText: { type: String, default: '' },
    actionLoading: { type: Boolean, default: false }
  },
  methods: {
    triggerAction() {
      if (!this.actionLoading) this.$emit('action')
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-empty-state { display: flex; align-items: flex-start; justify-content: center; gap: var(--cy-space-2-5); min-height: 136px; padding: 28px 24px; border: 1px dashed var(--cy-color-border-strong); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-subtle); text-align: left; }
.cy-empty-state__icon { display: grid; flex: none; width: 42px; height: 42px; place-items: center; border-radius: 14px; background: var(--cy-color-brand-soft); color: var(--cy-color-brand-strong); font-size: 21px; }
.cy-empty-state__body { max-width: 440px; min-width: 0; }
.cy-empty-state h3 { margin: 1px 0 0; color: var(--cy-color-text-primary); font-size: 15px; line-height: 1.5; }
.cy-empty-state p { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.65; }
.cy-empty-state .el-button { margin-top: 12px; }

@media (max-width: 560px) {
  .cy-empty-state { min-height: 112px; padding: var(--cy-space-4-5) var(--cy-space-3-5); }
}
</style>

<template>
  <section class="cy-resource-state" :class="`cy-resource-state--${kind}`" :role="kind === 'error' ? 'alert' : 'status'">
    <i :class="kind === 'error' ? 'el-icon-warning-outline' : 'el-icon-lock'" aria-hidden="true" />
    <div class="cy-resource-state__body">
      <h3>{{ title }}</h3>
      <p v-if="description">{{ description }}</p>
      <el-button v-if="retryText" type="text" icon="el-icon-refresh-right" :loading="retrying" :disabled="retrying" @click="retry">
        {{ retryText }}
      </el-button>
    </div>
  </section>
</template>

<script>
export default {
  name: 'CyResourceState',
  props: {
    kind: {
      type: String,
      required: true,
      validator: value => ['forbidden', 'error'].includes(value)
    },
    title: { type: String, required: true },
    description: { type: String, default: '' },
    retryText: { type: String, default: '' },
    retrying: { type: Boolean, default: false }
  },
  methods: {
    retry() {
      if (!this.retrying) this.$emit('retry')
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-resource-state { display: flex; align-items: flex-start; justify-content: center; gap: var(--cy-space-2-5); max-width: 620px; margin: 40px auto; padding: 30px 34px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-subtle); }
.cy-resource-state > i { flex: none; margin-top: 2px; font-size: 24px; }
.cy-resource-state--error { border-color: var(--cy-color-status-danger); }
.cy-resource-state--error > i { color: var(--cy-color-status-danger); }
.cy-resource-state--forbidden { border-color: var(--cy-color-status-warning); }
.cy-resource-state--forbidden > i { color: var(--cy-color-status-warning); }
.cy-resource-state__body { min-width: 0; }
.cy-resource-state h3 { margin: 0; color: var(--cy-color-text-primary); font-size: 16px; line-height: 1.5; }
.cy-resource-state p { margin: 7px 0 0; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.65; white-space: pre-wrap; }
.cy-resource-state .el-button { margin-top: var(--cy-legacy-5); }

@media (max-width: 560px) {
  .cy-resource-state { align-items: flex-start; margin: 24px 0; padding: var(--cy-space-4-5); }
}
</style>

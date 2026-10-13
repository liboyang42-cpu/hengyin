<template>
  <span class="cy-copy-value" :class="{ 'is-empty': !hasValue }">
    <span class="cy-copy-value__text" :title="hasValue ? displayValue : ''">{{ displayValue }}</span>
    <el-tooltip :content="hasValue ? `复制${label}` : `暂无${label}`" placement="top">
      <el-button
        v-clipboard:copy="value"
        v-clipboard:success="handleSuccess"
        v-clipboard:error="handleError"
        class="cy-copy-value__button"
        type="text"
        icon="el-icon-document-copy"
        :aria-label="`复制${label}`"
        :disabled="!hasValue"
        @click.stop
      />
    </el-tooltip>
  </span>
</template>

<script>
export default {
  name: 'CyCopyValue',
  props: {
    value: { type: [String, Number], required: true },
    label: { type: String, default: '内容' }
  },
  computed: {
    hasValue() {
      return this.value !== null && this.value !== undefined && this.value !== ''
    },
    displayValue() {
      return this.hasValue ? String(this.value) : '-'
    }
  },
  methods: {
    handleSuccess() {
      this.$modal.msgSuccess(`${this.label}已复制`)
    },
    handleError() {
      this.$modal.msgError(`复制${this.label}失败，请手动复制`)
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-copy-value {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  max-width: 100%;
  gap: var(--cy-legacy-3);
  color: var(--cy-color-text-primary);
}

.cy-copy-value__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cy-copy-value__button {
  flex: none;
  min-width: var(--cy-space-4-5);
  padding: var(--cy-legacy-3);
  color: var(--cy-color-text-tertiary);
  opacity: 0;
  transition: color 160ms ease, opacity 160ms ease;
}

.cy-copy-value:hover .cy-copy-value__button,
.cy-copy-value:focus-within .cy-copy-value__button { color: var(--cy-color-brand); opacity: 1; }
.cy-copy-value.is-empty { color: var(--cy-color-text-tertiary); }
.cy-copy-value.is-empty .cy-copy-value__button { cursor: not-allowed; }
.cy-copy-value__button:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 1px; border-radius: var(--cy-radius-control); opacity: 1; }
</style>

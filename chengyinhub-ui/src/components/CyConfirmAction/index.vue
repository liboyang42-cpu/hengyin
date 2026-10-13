<template>
  <el-dialog
    :visible="visible"
    :title="title"
    width="480px"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    :before-close="requestClose"
    @closed="reset"
  >
    <div class="cy-confirm-action__impact" :class="`cy-confirm-action__impact--${severity}`">
      <i :class="severity === 'danger' ? 'el-icon-warning' : 'el-icon-warning-outline'" aria-hidden="true" />
      <div>
        <span>影响说明</span>
        <p>{{ impact }}</p>
      </div>
    </div>
    <el-form label-position="top">
      <el-form-item :label="reasonLabel" :error="reasonError" required>
        <el-input
          v-model="reason"
          type="textarea"
          :rows="4"
          :maxlength="reasonMaxlength || undefined"
          :show-word-limit="Boolean(reasonMaxlength)"
          :placeholder="reasonPlaceholder"
          @input="reasonError = ''"
        />
      </el-form-item>
    </el-form>
    <span slot="footer" class="dialog-footer">
      <el-button :disabled="loading" @click="requestClose">取消</el-button>
      <el-button :type="severity" :loading="loading" @click="submit">{{ confirmText }}</el-button>
    </span>
  </el-dialog>
</template>

<script>
export default {
  name: 'CyConfirmAction',
  props: {
    visible: { type: Boolean, default: false },
    title: { type: String, required: true },
    impact: { type: String, required: true },
    severity: {
      type: String,
      default: 'danger',
      validator: value => ['warning', 'danger'].includes(value)
    },
    confirmText: { type: String, default: '确认执行' },
    reasonLabel: { type: String, default: '处理原因' },
    reasonPlaceholder: { type: String, default: '请填写处理原因' },
    reasonMaxlength: { type: Number, default: 0 },
    loading: { type: Boolean, default: false }
  },
  data() {
    return { reason: '', reasonError: '' }
  },
  watch: {
    visible(value) {
      if (value) this.reset()
    }
  },
  methods: {
    requestClose(done) {
      if (this.loading) return
      this.$emit('update:visible', false)
      if (done) done()
    },
    reset() {
      this.reason = ''
      this.reasonError = ''
    },
    submit() {
      if (this.loading) return
      const reason = this.reason.trim()
      if (!reason) {
        this.reasonError = '请填写处理原因'
        return
      }
      this.reasonError = ''
      this.$emit('confirm', reason)
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-confirm-action__impact { display: flex; align-items: flex-start; gap: 12px; margin-bottom: var(--cy-space-3-5); padding: var(--cy-space-2-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.cy-confirm-action__impact i { margin-top: 2px; font-size: 18px; }
.cy-confirm-action__impact--danger { border-color: var(--cy-color-status-danger); }
.cy-confirm-action__impact--danger i { color: var(--cy-color-status-danger); }
.cy-confirm-action__impact--warning { border-color: var(--cy-color-status-warning); }
.cy-confirm-action__impact--warning i { color: var(--cy-color-status-warning); }
.cy-confirm-action__impact span { color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 700; }
.cy-confirm-action__impact p { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-primary); line-height: 1.6; }
</style>

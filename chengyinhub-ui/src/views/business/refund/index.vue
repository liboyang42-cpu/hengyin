<template>
  <div class="app-container refund-legacy-redirect">
    <p>退款处理台已迁移，正在保留原筛选条件跳转。</p>
  </div>
</template>

<script>
export default {
  name: 'RefundLegacyRedirect',
  created() {
    this.redirectToCanonical()
  },
  beforeRouteUpdate(to, from, next) {
    this.redirectToCanonical(to.query)
    next()
  },
  methods: {
    redirectToCanonical(query = this.$route.query) {
      const targetQuery = Object.assign({}, query)
      delete targetQuery.queue
      return this.$router.replace({ name: 'financeRefund', query: targetQuery })
    }
  }
}
</script>

<style scoped>
.refund-legacy-redirect { color: var(--cy-color-text-secondary); }
</style>

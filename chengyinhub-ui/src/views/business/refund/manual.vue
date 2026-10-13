<template>
  <div class="app-container refund-manual-legacy-redirect">
    <p>人工打款队列已收进退款处理台，正在跳转。</p>
  </div>
</template>

<script>
export default {
  name: 'RefundManualLegacyRedirect',
  created() {
    this.redirectToCanonical()
  },
  beforeRouteUpdate(to, from, next) {
    this.redirectToCanonical(to.query)
    next()
  },
  methods: {
    redirectToCanonical(query = this.$route.query) {
      return this.$router.replace({ name: 'financeRefund', query: Object.assign({}, query, { queue: 'manual' }) })
    }
  }
}
</script>

<style scoped>
.refund-manual-legacy-redirect { color: var(--cy-color-text-secondary); }
</style>

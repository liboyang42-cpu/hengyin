<template>
  <div class="app-container member-balance-redirect">
    <p>余额明细已迁至资金流水。</p>
  </div>
</template>

<script>
export default {
  name: 'Memberbalancedetail',
  created() {
    this.$router.replace({ name: 'financeLedger', query: this.$route.query })
  }
}
</script>

<style scoped>
.member-balance-redirect { color: var(--cy-color-text-secondary); }
</style>

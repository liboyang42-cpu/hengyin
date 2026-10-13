<template>
  <div class="app-container couponhistory-redirect">
    <p>优惠券领取流水已合并到优惠券详情。</p>
  </div>
</template>

<script>
export default {
  name: 'Couponhistory',
  created() {
    const couponId = this.$route.query.couponId
    const target = couponId
      ? { name: 'couponLedger', params: { couponId } }
      : { path: '/business/coupon' }
    this.$router.replace(target)
  }
}
</script>

<style scoped>
.couponhistory-redirect { color: var(--cy-color-text-secondary); }
</style>

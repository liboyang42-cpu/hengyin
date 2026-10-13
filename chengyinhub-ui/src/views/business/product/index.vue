<template><div /></template>

<script>
export default {
  name: 'PointsProductLegacy',
  created() {
    this.$router.replace({
      path: '/config/points-product',
      query: this.$route.query
    })
  }
}
</script>

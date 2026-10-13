<template><div /></template>

<script>
export default {
  name: 'PointsProductLegacyEdit',
  created() {
    const query = Object.assign({}, this.$route.query)
    const id = query.id
    delete query.id
    this.$router.replace({
      path: id ? `/config/points-product/${id}/edit` : '/config/points-product/create',
      query
    })
  }
}
</script>

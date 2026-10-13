<template><div /></template>

<script>
export default {
  name: 'CreatorLegacy',
  created() {
    this.$router.replace({
      path: '/risk/creator-admission',
      query: this.$route.query
    })
  }
}
</script>

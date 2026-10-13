<template>
  <div class="app-container" />
</template>

<script>
export default {
  name: 'LegacyPayableBatchRedirect',
  created() {
    this.$router.replace({
      name: 'financePayableBatch',
      query: Object.assign({}, this.$route.query)
    })
  }
}
</script>

<template>
  <div />
</template>

<script>
export default {
  name: 'GiftLegacyRedirect',
  created() {
    this.$router.replace({
      path: '/config/gift',
      query: this.$route.query
    })
  }
}
</script>

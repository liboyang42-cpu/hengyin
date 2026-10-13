<template>
  <div />
</template>

<script>
export default {
  name: 'LegacyOrderRedirect',
  created() {
    this.redirectToCanonical(this.$route.query)
  },
  methods: {
    redirectToCanonical(query = {}) {
      return this.$router.replace({
        name: 'financeOrder',
        query: Object.assign({}, query)
      })
    }
  }
}
</script>

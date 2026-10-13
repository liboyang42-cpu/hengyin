<template>
  <div />
</template>

<script>
export default {
  name: 'InfomationLegacy',
  created() {
    this.$router.replace({
      path: '/config/content/guide',
      query: this.$route.query
    })
  }
}
</script>

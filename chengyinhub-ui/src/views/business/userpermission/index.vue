<template>
  <div />
</template>

<script>
export default {
  name: 'UserPermissionLegacy',
  created() {
    this.$router.replace({
      path: '/config/role-quota',
      query: this.$route.query
    })
  }
}
</script>

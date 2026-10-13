<template>
  <div />
</template>

<script>
export default {
  name: 'PointsSettingLegacy',
  created() {
    this.$router.replace({
      path: '/config/points',
      query: this.$route.query
    })
  }
}
</script>

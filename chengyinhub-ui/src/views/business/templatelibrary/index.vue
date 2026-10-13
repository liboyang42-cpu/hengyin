<template>
  <div class="app-container template-library-redirect">
    <p>模板库已合并到模板中心，正在保留原筛选条件跳转。</p>
  </div>
</template>

<script>
export default {
  name: 'Templatelibrary',
  created() {
    // [P2-4 Q5] 原先写死 path '/business/membertemplate'。2086『用户模板库』本轮从
    // 4 迁到 2209 并改名「模板中心」,该 fullpath 不再存在。改为按 route name 跳转,
    // 理由同 setting/index.vue。
    this.$router.replace({
      name: 'Membertemplate',
      query: this.$route.query
    })
  }
}
</script>

<style scoped>
.template-library-redirect { color: var(--cy-color-text-secondary); }
</style>

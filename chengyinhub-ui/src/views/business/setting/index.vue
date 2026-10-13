<template>
  <div class="app-container setting-redirect">
    <p>系统参数配置已合并到结算规则，正在保留原地址跳转。</p>
  </div>
</template>

<script>
export default {
  name: 'SettingConfig',
  created() {
    // [P2-4 Q5] 原先写死 path '/p3-platform/settlementrule'。2188『结算规则』本轮从
    // 2155 迁到 2214,该 fullpath 不再存在 —— 写死 path 的壳会被别人的迁移改坏。
    // 改为按 route name 跳转:RuoYi getRouteName = capitalize(route_name || path),
    // 只跟菜单自身的 path 有关、与 parent 无关,re-parent 后依然稳定。
    this.$router.replace({
      name: 'Settlementrule',
      query: this.$route.query
    })
  }
}
</script>

<style scoped>
.setting-redirect { color: var(--cy-color-text-secondary); }
</style>

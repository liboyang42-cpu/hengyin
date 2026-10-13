<template>
  <main class="cy-error-page cy-error-page--missing">
    <section class="cy-error-page__panel" aria-labelledby="page404-title">
      <div class="cy-error-page__icon" aria-hidden="true"><i class="el-icon-folder-opened" /></div>
      <p class="cy-error-page__eyebrow">页面不存在</p>
      <h1 id="page404-title">找不到这个页面</h1>
      <p class="cy-error-page__description">链接可能已失效、地址有误，或页面已经迁移。请返回工作台从当前业务导航重新进入。</p>
      <div class="cy-error-page__actions">
        <el-button type="primary" icon="el-icon-s-home" @click="goHome">返回工作台</el-button>
      </div>
    </section>
  </main>
</template>

<script>
export default {
  name: 'Page404',
  methods: {
    goHome() {
      this.$router.push({ path: '/' })
    }
  }
}
</script>

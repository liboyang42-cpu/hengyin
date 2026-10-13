<template>
  <main class="cy-error-page cy-error-page--forbidden">
    <section class="cy-error-page__panel" aria-labelledby="page401-title">
      <div class="cy-error-page__icon" aria-hidden="true"><i class="el-icon-lock" /></div>
      <p class="cy-error-page__eyebrow">访问受限</p>
      <h1 id="page401-title">暂时无法访问此页面</h1>
      <p class="cy-error-page__description">当前账号可能没有所需权限，或登录状态已经变化。请返回工作台继续处理有权限的任务。</p>
      <div class="cy-error-page__actions">
        <el-button icon="el-icon-back" @click="back">返回上一页</el-button>
        <el-button type="primary" icon="el-icon-s-home" @click="goHome">返回工作台</el-button>
      </div>
    </section>
  </main>
</template>

<script>
export default {
  name: 'Page401',
  methods: {
    goHome() {
      this.$router.push({ path: '/' })
    },
    back() {
      if (this.$route.query.noGoBack) {
        this.goHome()
      } else {
        this.$router.go(-1)
      }
    }
  }
}
</script>

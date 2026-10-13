<template>
  <div class="app-container template-library-detail-redirect">
    <cy-resource-state
      v-if="redirectError"
      kind="error"
      title="无法定位关联模板"
      :description="redirectMessage"
      retry-text="重新定位"
      :retrying="loading"
      @retry="redirectToCanonical"
    />
    <p v-else>模板库详情已合并到模板中心，正在定位关联的用户模板。</p>
  </div>
</template>

<script>
import { getTemplatelibrary } from '@/api/business/templatelibrary'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'TemplateLibraryDetailRedirect',
  components: { CyResourceState },
  data() {
    return {
      loading: false,
      redirectError: false,
      redirectRequestId: 0,
      redirectMessage: '该模板库投影无法读取，请检查权限或稍后重试。'
    }
  },
  created() {
    this.redirectToCanonical()
  },
  beforeRouteUpdate(to, from, next) {
    this.redirectToCanonical(to.query)
    next()
  },
  methods: {
    redirectToCanonical(query = this.$route.query) {
      const requestId = ++this.redirectRequestId
      const projectionId = query.id
      if (!projectionId) {
        this.loading = false
        this.redirectError = true
        this.redirectMessage = '旧模板库链接缺少投影编号，无法定位关联的用户模板。'
        return Promise.resolve()
      }
      this.loading = true
      this.redirectError = false
      return getTemplatelibrary(projectionId)
        .then(response => {
          if (requestId !== this.redirectRequestId) return
          const projection = response.data || {}
          if (!projection.memberTemplateId) {
            this.redirectError = true
            this.redirectMessage = '该模板库投影缺少关联的用户模板真源，不能把投影编号误当成模板编号。'
            return
          }
          return this.$router.replace({
            name: 'membertemplateDetail',
            query: Object.assign({}, query, { id: projection.memberTemplateId })
          })
        })
        .catch(() => {
          if (requestId !== this.redirectRequestId) return
          this.redirectError = true
          this.redirectMessage = '模板库投影读取失败，请检查权限或稍后重试。'
        })
        .finally(() => {
          if (requestId === this.redirectRequestId) this.loading = false
        })
    }
  }
}
</script>

<style scoped>
.template-library-detail-redirect { color: var(--cy-color-text-secondary); }
</style>

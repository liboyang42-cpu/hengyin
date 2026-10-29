<template>
  <div class="app-container cy-domain-page template-detail">
    <cy-page-header
      eyebrow="供给与发布 / 模板中心"
      title="模板详情"
      description="这里展示用户模板真源及其审核、上架、投影关联事实；不在详情页直接修改投影或审核结论。"
    >
      <el-button icon="el-icon-back" @click="backToCenter">返回模板中心</el-button>
    </cy-page-header>

    <cy-resource-state
      v-if="detailError"
      kind="error"
      :title="detailErrorTitle"
      :description="detailErrorMessage"
      retry-text="重新读取"
      :retrying="loading"
      @retry="loadTemplateDetail(templateId)"
    />

    <template v-else-if="templateDetail">
      <cy-entity-header
        eyebrow="用户模板真源"
        :title="templateDetail.title || `模板 #${templateId}`"
        :status="auditStatus"
        :facts="templateFacts"
        class="template-detail__header"
      />

      <section class="cy-panel template-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>模板事实</h2>
            <p>发布者、审核和上架状态均来自同一用户模板对象。</p>
          </div>
        </div>
        <div class="template-detail__overview">
          <cy-media-preview
            v-if="templateDetail.imgUrl"
            :sources="[templateDetail.imgUrl]"
            label="模板封面"
            :width="240"
            :height="160"
            fit="cover"
          />
          <div class="template-detail__facts">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="发布用户">{{ memberLabel }}</el-descriptions-item>
              <el-descriptions-item label="审核状态"><cy-status-tag :label="auditStatus.label" :tone="auditStatus.tone" /></el-descriptions-item>
              <el-descriptions-item label="上架状态"><cy-status-tag :label="publishLabel(templateDetail.publishStatus)" :tone="publishTone(templateDetail.publishStatus)" /></el-descriptions-item>
              <el-descriptions-item label="模板库投影"><cy-status-tag :label="syncLabel(templateDetail.isSync)" :tone="syncTone(templateDetail.isSync)" /></el-descriptions-item>
              <el-descriptions-item label="时长">{{ templateDetail.duration == null ? '未记录' : `${templateDetail.duration} 分钟` }}</el-descriptions-item>
              <el-descriptions-item label="难度">{{ valueOrFallback(templateDetail.difficulty) }}</el-descriptions-item>
              <el-descriptions-item label="参与人数">{{ valueOrFallback(templateDetail.players) }}</el-descriptions-item>
              <el-descriptions-item label="使用人数">{{ templateDetail.useNum == null ? '未记录' : templateDetail.useNum }}</el-descriptions-item>
              <el-descriptions-item label="评分">{{ valueOrFallback(templateDetail.rating) }}</el-descriptions-item>
              <el-descriptions-item label="最近更新">{{ templateDetail.updateTime || templateDetail.createTime || '未记录' }}</el-descriptions-item>
              <el-descriptions-item label="适用场景" :span="2">{{ valueOrFallback(templateDetail.usageLocation) }}</el-descriptions-item>
              <el-descriptions-item label="所需物料" :span="2">{{ valueOrFallback(templateDetail.requiredMaterials) }}</el-descriptions-item>
              <el-descriptions-item v-if="Number(templateDetail.status) === 2" label="驳回原因" :span="2">{{ valueOrFallback(templateDetail.reason) }}</el-descriptions-item>
            </el-descriptions>
          </div>
        </div>
      </section>

      <section v-if="templateDetail.description" class="cy-panel template-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>模板介绍</h2>
            <p>供运营与审核调证使用。</p>
          </div>
        </div>
        <p class="template-detail__copy">{{ templateDetail.description }}</p>
      </section>

      <section v-if="templateDetail.ruleInstructions" class="cy-panel template-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>规则说明</h2>
            <p>模板运行和参与约束的原始说明。</p>
          </div>
        </div>
        <p class="template-detail__copy">{{ templateDetail.ruleInstructions }}</p>
      </section>

      <section class="cy-panel template-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>基础完成方式</h2>
            <p>{{ validationLabel(templateDetail.validationMethod) }}</p>
          </div>
          <cy-status-tag :label="validationLabel(templateDetail.validationMethod)" :tone="validationTone(templateDetail.validationMethod)" />
        </div>
        <el-descriptions v-if="Number(templateDetail.validationMethod) === 1" :column="1" border size="small">
          <el-descriptions-item label="验证问题">{{ valueOrFallback(templateDetail.questionName) }}</el-descriptions-item>
          <el-descriptions-item label="标准答案">{{ valueOrFallback(templateDetail.questionAnswer) }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions v-else-if="Number(templateDetail.validationMethod) === 2" :column="1" border size="small">
          <el-descriptions-item label="验证问题">{{ valueOrFallback(templateDetail.questionName) }}</el-descriptions-item>
          <el-descriptions-item label="验证图片">
            <cy-media-preview
              v-if="templateDetail.questionAnswer"
              :sources="[templateDetail.questionAnswer]"
              label="验证图片"
              :width="200"
              :height="120"
              fit="contain"
            />
            <span v-else>未记录</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-descriptions v-else-if="Number(templateDetail.validationMethod) === 3" :column="1" border size="small">
          <el-descriptions-item label="验证问题">{{ valueOrFallback(templateDetail.questionName) }}</el-descriptions-item>
          <el-descriptions-item label="选项 A">{{ valueOrFallback(templateDetail.questionA) }}</el-descriptions-item>
          <el-descriptions-item label="选项 B">{{ valueOrFallback(templateDetail.questionB) }}</el-descriptions-item>
          <el-descriptions-item label="选项 C">{{ valueOrFallback(templateDetail.questionC) }}</el-descriptions-item>
          <el-descriptions-item label="选项 D">{{ valueOrFallback(templateDetail.questionD) }}</el-descriptions-item>
          <el-descriptions-item label="正确答案">{{ valueOrFallback(templateDetail.correctAnswer) }}</el-descriptions-item>
        </el-descriptions>
        <p v-else class="template-detail__muted">{{ validationLabel(templateDetail.validationMethod) }}；具体内容与运行参数见下方原配置。</p>
      </section>

      <section class="cy-panel template-detail__section" aria-label="完整游戏内容">
        <div class="cy-panel__heading"><div><h2>完整游戏内容与素材</h2><p>只读原模板配置；高级机制、资源槽与基础完成方式分开展示，不能用基础方式数量代替游戏支持范围。</p></div></div>
        <template-content-fields :value="templateDetail" read-only />
      </section>

      <section v-if="templateDetail.feedbackText || templateDetail.couponId" class="cy-panel template-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>反馈与权益</h2>
            <p>反馈文本或关联优惠券均为模板真源的附属事实。</p>
          </div>
        </div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item v-if="templateDetail.feedbackText" label="文字反馈">{{ templateDetail.feedbackText }}</el-descriptions-item>
          <template v-if="templateDetail.couponId">
            <el-descriptions-item label="关联优惠券">{{ coupon ? (coupon.name || `优惠券 #${templateDetail.couponId}`) : `优惠券 #${templateDetail.couponId}` }}</el-descriptions-item>
            <el-descriptions-item label="优惠券类型">{{ coupon ? couponTypeLabel(coupon.couponType) : couponError ? '详情暂时无法读取' : '正在读取' }}</el-descriptions-item>
            <el-descriptions-item v-if="coupon" label="有效期">{{ coupon.startTime || '未设置' }} 至 {{ coupon.endTime || '未设置' }}</el-descriptions-item>
            <el-descriptions-item v-if="coupon && coupon.description" label="优惠券说明">{{ coupon.description }}</el-descriptions-item>
          </template>
        </el-descriptions>
      </section>

      <section v-if="templateDetail.activityCategoryids" class="cy-panel template-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>玩法标签</h2>
            <p>标签仅解释模板分类，不形成第二个内容对象。</p>
          </div>
        </div>
        <div class="template-detail__categories">
          <cy-status-tag
            v-for="category in categoryList"
            :key="category.id"
            :label="category.categoryName || `分类 #${category.id}`"
            tone="brand"
          />
          <span v-if="categoryError" class="template-detail__muted">标签详情暂时无法读取（原始标签 ID：{{ templateDetail.activityCategoryids }}）。</span>
          <span v-else-if="!categoryList.length" class="template-detail__muted">正在读取标签。</span>
        </div>
      </section>
    </template>
  </div>
</template>

<script>
import { getMembertemplate } from '@/api/business/membertemplate'
import { getCouponInfo } from '@/api/business/coupon'
import { getCategoryList } from '@/api/system/category'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyMediaPreview from '@/components/CyMediaPreview'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'
import TemplateContentFields from './TemplateContentFields.vue'
import { validationLabel } from './template-content'

export default {
  name: 'MembertemplateDetail',
  components: { CyEntityHeader, CyMediaPreview, CyPageHeader, CyResourceState, CyStatusTag, TemplateContentFields },
  data() {
    return {
      loading: false,
      detailError: false,
      detailErrorTitle: '模板详情暂时无法读取',
      detailErrorMessage: '请检查权限或网络后重试。',
      detailRequestId: 0,
      templateId: null,
      templateDetail: null,
      coupon: null,
      couponError: false,
      categoryList: [],
      categoryError: false
    }
  },
  computed: {
    auditStatus() {
      return {
        label: this.auditLabel(this.templateDetail && this.templateDetail.status),
        tone: this.auditTone(this.templateDetail && this.templateDetail.status)
      }
    },
    templateFacts() {
      if (!this.templateDetail) return []
      return [
        { label: '模板编号', value: this.templateId ? `#${this.templateId}` : '未记录' },
        { label: '发布用户', value: this.memberLabel },
        { label: '模板库投影', value: this.syncLabel(this.templateDetail.isSync) },
        { label: '上架状态', value: this.publishLabel(this.templateDetail.publishStatus) }
      ]
    },
    memberLabel() {
      return this.templateDetail && this.templateDetail.memberId != null ? `用户 #${this.templateDetail.memberId}` : '未记录'
    }
  },
  created() {
    this.loadTemplateDetail(this.$route.query.id)
  },
  beforeRouteUpdate(to, from, next) {
    this.loadTemplateDetail(to.query.id)
    next()
  },
  methods: {
    loadTemplateDetail(id) {
      const requestId = ++this.detailRequestId
      this.templateId = id || null
      this.templateDetail = null
      this.coupon = null
      this.couponError = false
      this.categoryList = []
      this.categoryError = false
      this.detailError = false
      if (!this.templateId) {
        this.detailError = true
        this.detailErrorTitle = '缺少模板编号'
        this.detailErrorMessage = '请从模板中心或有效的旧模板库链接进入详情。'
        return Promise.resolve()
      }
      this.loading = true
      return getMembertemplate(this.templateId)
        .then(response => {
          if (requestId !== this.detailRequestId) return
          const detail = response.data || null
          if (!detail) {
            this.detailError = true
            this.detailErrorTitle = '模板不存在或不可读取'
            this.detailErrorMessage = '该模板可能已删除，或当前账号没有读取权限。'
            return
          }
          this.templateDetail = detail
          this.coupon = detail.smsCoupon || null
          const relatedReads = []
          if (detail.couponId && !this.coupon) relatedReads.push(this.loadCoupon(detail.couponId, requestId))
          if (detail.activityCategoryids) relatedReads.push(this.loadCategories(detail.activityCategoryids, requestId))
          return Promise.all(relatedReads)
        })
        .catch(() => {
          if (requestId !== this.detailRequestId) return
          this.detailError = true
          this.detailErrorTitle = '模板详情暂时无法读取'
          this.detailErrorMessage = '请检查权限或网络后重试。'
        })
        .finally(() => {
          if (requestId === this.detailRequestId) this.loading = false
        })
    },
    loadCoupon(couponId, requestId) {
      return getCouponInfo(couponId)
        .then(response => {
          if (requestId !== this.detailRequestId) return
          this.coupon = response.data || null
        })
        .catch(() => {
          if (requestId !== this.detailRequestId) return
          this.couponError = true
        })
    },
    loadCategories(categoryIds, requestId) {
      const ids = String(categoryIds).split(',').map(id => id.trim()).filter(Boolean)
      if (!ids.length) return Promise.resolve()
      return getCategoryList({ ids: ids.join(',') })
        .then(response => {
          if (requestId !== this.detailRequestId) return
          this.categoryList = response.data || []
        })
        .catch(() => {
          if (requestId !== this.detailRequestId) return
          this.categoryError = true
        })
    },
    backToCenter() {
      this.$router.push({ path: '/business/membertemplate' })
    },
    valueOrFallback(value) {
      return value === null || value === undefined || value === '' ? '未记录' : value
    },
    auditLabel(status) {
      if (Number(status) === 1) return '审核通过'
      if (Number(status) === 2) return '审核驳回'
      return '待审核'
    },
    auditTone(status) {
      if (Number(status) === 1) return 'success'
      if (Number(status) === 2) return 'danger'
      return 'warning'
    },
    publishLabel(status) {
      return Number(status) === 1 ? '已上架' : '未上架'
    },
    publishTone(status) {
      return Number(status) === 1 ? 'success' : 'neutral'
    },
    syncLabel(isSync) {
      return Number(isSync) === 1 ? '已同步模板库' : '仅用户模板'
    },
    syncTone(isSync) {
      return Number(isSync) === 1 ? 'brand' : 'neutral'
    },
    validationLabel,
    validationTone(method) {
      return method !== null && method !== '' && Number(method) >= 0 && Number(method) <= 10 ? 'info' : 'neutral'
    },
    couponTypeLabel(type) {
      if (Number(type) === 0) return '礼品券'
      if (Number(type) === 1) return '9 折券'
      if (Number(type) === 2) return '8 折券'
      if (Number(type) === 3) return '体验卡'
      return '未记录'
    }
  }
}
</script>

<style lang="scss" scoped>
.template-detail__header { margin-bottom: var(--cy-space-5); }
.template-detail__section { margin-bottom: var(--cy-space-4); }
.template-detail__overview { display: grid; grid-template-columns: minmax(0, 240px) minmax(0, 1fr); gap: var(--cy-space-5); align-items: start; }
.template-detail__facts { min-width: 0; }
.template-detail__copy { margin: 0; color: var(--cy-color-text-primary); line-height: 1.75; white-space: pre-wrap; }
.template-detail__categories { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cy-space-2); }
.template-detail__muted { color: var(--cy-color-text-tertiary); font-size: 13px; line-height: 1.6; }

@media (max-width: 720px) {
  .template-detail__overview { grid-template-columns: 1fr; }
}
</style>

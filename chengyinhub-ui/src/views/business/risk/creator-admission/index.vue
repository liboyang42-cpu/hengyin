<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="审核与风控"
      title="创作者准入"
      description="处理创作者申请。仅待审申请可被处理；通过或驳回后由服务端锁定结果。"
    >
      <el-button icon="el-icon-refresh" @click="getList">刷新队列</el-button>
    </cy-page-header>

    <section class="cy-panel creator-filter">
      <div class="cy-panel__heading">
        <div>
          <h2>申请队列</h2>
          <p>按申请人或处理状态定位；驳回必须说明原因，申请人可在创作者中心查看结果。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="72px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="会员 ID" prop="memberId">
            <el-input v-model="queryParams.memberId" placeholder="精确定位" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="创作者" prop="creatorName">
            <el-input v-model="queryParams.creatorName" placeholder="名称关键词" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="处理状态" prop="applyStatus">
            <el-select v-model="queryParams.applyStatus" placeholder="全部状态" clearable>
              <el-option label="待审核" :value="0" />
              <el-option label="已通过" :value="1" />
              <el-option label="已驳回" :value="2" />
            </el-select>
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="创作者申请队列暂时无法读取"
      description="请稍后重试；尚未写入任何审核决定。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <cy-data-table
      v-else
      :data="creatorList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前筛选下没有创作者申请"
      @pagination="handlePagination"
      @refresh="getList"
    >
      <template slot="applicant" slot-scope="scope">
        <div class="table-stack">
          <strong>{{ scope.row.creatorName || '未命名创作者' }}</strong>
          <small>会员 #{{ scope.row.memberId || '—' }}</small>
        </div>
      </template>
      <template slot="bio" slot-scope="scope">
        <span>{{ scope.row.bio || '未填写简介' }}</span>
      </template>
      <template slot="status" slot-scope="scope">
        <cy-status-tag :label="statusLabel(scope.row.applyStatus)" :tone="statusTone(scope.row.applyStatus)" />
      </template>
      <template slot="review" slot-scope="scope">
        <div class="table-stack">
          <span>{{ scope.row.reviewReason || '尚未处理' }}</span>
          <small v-if="scope.row.reviewerId">审核人 #{{ scope.row.reviewerId }}</small>
        </div>
      </template>
      <template slot="submitted" slot-scope="scope">
        <span>{{ scope.row.createTime || '—' }}</span>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button
          v-hasPermi="['business:creator:review']"
          size="mini"
          type="text"
          :disabled="!canReview(scope.row) || reviewSubmitting"
          @click="handleApprove(scope.row)"
        >通过</el-button>
        <el-button
          v-hasPermi="['business:creator:review']"
          size="mini"
          type="text"
          class="danger-action"
          :disabled="!canReview(scope.row) || reviewSubmitting"
          @click="openReject(scope.row)"
        >驳回</el-button>
      </template>
    </cy-data-table>

    <cy-confirm-action
      :visible.sync="rejectOpen"
      title="驳回创作者申请"
      :impact="rejectImpact"
      severity="danger"
      confirm-text="确认驳回"
      reason-label="驳回原因"
      reason-placeholder="请写明申请人需要补充或调整的内容"
      :reason-maxlength="500"
      :loading="reviewSubmitting"
      @confirm="submitReject"
    />
  </div>
</template>

<script>
import { listCreators, reviewCreator } from '@/api/business/creator'
import CyConfirmAction from '@/components/CyConfirmAction'
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const STATUS_PENDING = 0
const STATUS_APPROVED = 1
const STATUS_REJECTED = 2

const emptyQuery = () => ({
  pageNum: 1,
  pageSize: 10,
  memberId: null,
  creatorName: null,
  applyStatus: null
})

export default {
  name: 'CreatorAdmission',
  components: { CyConfirmAction, CyDataTable, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      loadError: false,
      reviewSubmitting: false,
      rejectOpen: false,
      rejectTarget: null,
      listRequestId: 0,
      total: 0,
      creatorList: [],
      queryParams: emptyQuery(),
      tableColumns: [
        { key: 'applicant', label: '申请人', minWidth: 180 },
        { key: 'bio', label: '申请简介', minWidth: 240, showOverflowTooltip: true },
        { key: 'status', label: '处理状态', width: 120 },
        { key: 'review', label: '审核说明', minWidth: 220, showOverflowTooltip: true },
        { key: 'submitted', label: '申请时间', minWidth: 165 },
        { key: 'actions', label: '处理', width: 150, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.memberId && { key: 'memberId', label: '会员 ID', value: this.queryParams.memberId },
        this.queryParams.creatorName && { key: 'creatorName', label: '创作者', value: this.queryParams.creatorName },
        this.queryParams.applyStatus !== null && this.queryParams.applyStatus !== undefined && {
          key: 'applyStatus', label: '处理状态', value: this.statusLabel(this.queryParams.applyStatus)
        }
      ].filter(Boolean)
    },
    rejectImpact() {
      if (!this.rejectTarget) return '申请人会看到驳回结果与原因；请确认判断无误。'
      return `驳回“${this.rejectTarget.creatorName || `会员 #${this.rejectTarget.memberId}`}”后，申请人会看到本次原因；请确认判断无误。`
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      const requestId = ++this.listRequestId
      const query = Object.assign({}, this.queryParams)
      this.loading = true
      this.loadError = false
      return listCreators(query).then(response => {
        if (requestId !== this.listRequestId) return
        this.creatorList = response.rows || []
        this.total = response.total || 0
      }).catch(() => {
        if (requestId === this.listRequestId) this.loadError = true
      }).finally(() => {
        if (requestId === this.listRequestId) this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.queryParams = emptyQuery()
      this.getList()
    },
    clearFilter(key) {
      this.queryParams[key] = null
      this.handleQuery()
    },
    handlePagination() {
      this.getList()
    },
    canReview(row) {
      return row && row.applyStatus === STATUS_PENDING
    },
    handleApprove(row) {
      if (!this.canReview(row) || this.reviewSubmitting) return
      return this.$modal.confirm(`确认通过“${row.creatorName || `会员 #${row.memberId}`}”的创作者申请？`)
        .then(() => this.submitReview(row, STATUS_APPROVED, null))
        .catch(() => {})
    },
    openReject(row) {
      if (!this.canReview(row) || this.reviewSubmitting) return
      this.rejectTarget = row
      this.rejectOpen = true
    },
    submitReject(reason) {
      if (!this.rejectTarget) return Promise.resolve()
      return this.submitReview(this.rejectTarget, STATUS_REJECTED, reason).then(() => {
        this.rejectOpen = false
        this.rejectTarget = null
      })
    },
    submitReview(row, applyStatus, reason) {
      if (!this.canReview(row) || this.reviewSubmitting) return Promise.resolve()
      this.reviewSubmitting = true
      return reviewCreator({
        id: row.id,
        applyStatus,
        reviewReason: reason
      }).then(() => {
        this.$modal.msgSuccess(applyStatus === STATUS_APPROVED ? '已通过创作者申请' : '已驳回创作者申请')
        return this.getList()
      }).finally(() => {
        this.reviewSubmitting = false
      })
    },
    statusLabel(status) {
      if (status === STATUS_APPROVED) return '已通过'
      if (status === STATUS_REJECTED) return '已驳回'
      return '待审核'
    },
    statusTone(status) {
      if (status === STATUS_APPROVED) return 'success'
      if (status === STATUS_REJECTED) return 'danger'
      return 'warning'
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-domain-page { color: var(--cy-color-text-primary); }
.cy-panel { margin-bottom: 20px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.cy-panel__heading { padding: 20px var(--cy-space-page) 16px; }
.cy-panel__heading h2 { margin: 0; font-size: 17px; }
.cy-panel__heading p { margin: var(--cy-space-0-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; }
.table-stack { display: grid; gap: var(--cy-legacy-3); min-width: 0; }
.table-stack strong, .table-stack span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-stack small { color: var(--cy-color-text-tertiary); }
.danger-action { color: var(--cy-color-status-danger); }
</style>

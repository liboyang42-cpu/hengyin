<template>
  <div class="app-container cy-domain-page">
    <manual-payout-queue v-if="isManualQueue" :route-query="$route.query" @back="goRefundQueue" />
    <activity-cancel-refund-cases v-else-if="isActivityCancelQueue" :route-query="$route.query" @back="goRefundQueue" />

    <template v-else>
      <cy-page-header
        eyebrow="资金与售后"
        title="退款处理台"
        description="审核决定与真实打款状态分开呈现。客服可发起售后，审核与打款按权限和双人约束继续流转。"
      >
        <el-button icon="el-icon-tickets" @click="goActivityCancelQueue" v-hasPermi="['business:refund:review']">活动取消退款</el-button>
        <el-button icon="el-icon-warning-outline" @click="goManualQueue" v-hasPermi="['business:refund:review']">人工打款队列</el-button>
        <el-button type="primary" icon="el-icon-plus" @click="handleAdd" v-hasPermi="['business:refund:review']">发起售后</el-button>
      </cy-page-header>

      <section class="refund-overview" aria-label="本页退款概览">
        <div v-for="item in overview" :key="item.label" class="refund-overview__item">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.note }}</small>
        </div>
      </section>

      <section class="cy-panel refund-filter">
        <div class="cy-panel__heading">
          <div>
            <h2>售后队列</h2>
            <p>退款来源、申请金额、审核状态与最终打款结果保持在同一行，避免“审核通过”被误认成“钱已到账”。</p>
          </div>
        </div>
        <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="66px">
          <cy-filter-bar
            :active-filters="activeFilters"
            @submit="handleQuery"
            @reset="resetQuery"
            @remove-filter="clearFilter"
          >
            <el-form-item label="商家 ID" prop="merchantId">
              <el-input v-model="queryParams.merchantId" placeholder="按商家定位" clearable @keyup.enter.native="handleQuery" />
            </el-form-item>
            <el-form-item label="用户 ID" prop="memberId">
              <el-input v-model="queryParams.memberId" placeholder="按用户定位" clearable @keyup.enter.native="handleQuery" />
            </el-form-item>
            <el-form-item label="审核状态" prop="status">
              <el-select v-model="queryParams.status" clearable placeholder="全部状态">
                <el-option label="待审核" :value="0" />
                <el-option label="已通过" :value="1" />
                <el-option label="已驳回" :value="2" />
              </el-select>
            </el-form-item>
            <el-form-item label="转平台" prop="platformTakeover">
              <el-select v-model="queryParams.platformTakeover" clearable placeholder="全部">
                <el-option label="商家超时·已转平台" :value="1" />
                <el-option label="未转平台" :value="0" />
              </el-select>
            </el-form-item>
            <el-form-item label="排序" prop="takeoverOrder">
              <el-select v-model="queryParams.takeoverOrder" clearable placeholder="默认（按单号）">
                <el-option label="按转平台时间" :value="1" />
              </el-select>
            </el-form-item>
          </cy-filter-bar>
        </el-form>
      </section>

      <cy-data-table
        :data="refundList"
        :columns="tableColumns"
        :loading="loading"
        :total="total"
        :page.sync="queryParams.pageNum"
        :limit.sync="queryParams.pageSize"
        default-density="comfortable"
        empty-text="当前筛选下没有退款申请"
        @pagination="getList"
        @refresh="getList"
      >
        <template slot="refund" slot-scope="scope">
          <div class="table-stack">
            <cy-entity-link
              :name="scope.row.refundNo || '退款申请'"
              entity-type="REFUND"
              type-label="退款申请"
              :entity-id="scope.row.id"
              :meta="`${sourceLabel(scope.row.sourceType)} #${scope.row.sourceId}`"
              @open="openDetail(scope.row)"
            />
            <cy-status-tag v-if="scope.row.platformTakeoverAt" label="商家超时·已转平台" tone="warning" />
            <small v-if="scope.row.platformTakeoverAt">转平台 {{ takeoverTimeText(scope.row.platformTakeoverAt) }}</small>
          </div>
        </template>
        <template slot="entity" slot-scope="scope">
          <div class="table-stack">
            <span>用户 #{{ scope.row.memberId || '—' }}</span>
            <small>商家 #{{ scope.row.merchantId || '—' }}</small>
          </div>
        </template>
        <template slot="amount" slot-scope="scope">
          <div class="table-stack table-stack--amount">
            <span>退 ¥{{ money(scope.row.refundAmount) }}</span>
            <small>实付 ¥{{ money(scope.row.paidAmount) }}</small>
          </div>
        </template>
        <template slot="review" slot-scope="scope">
          <cy-status-tag :label="reviewLabel(scope.row.status)" :tone="reviewTone(scope.row.status)" />
        </template>
        <template slot="payout" slot-scope="scope">
          <div class="table-stack">
            <cy-status-tag :label="payoutLabel(scope.row.payoutStatus, scope.row.status)" :tone="payoutTone(scope.row.payoutStatus, scope.row.status)" />
            <small v-if="payoutConflict(scope.row)" class="payout-conflict">{{ payoutConflict(scope.row) }}</small>
          </div>
        </template>
        <template slot="actions" slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-view" @click="openDetail(scope.row)">调证</el-button>
          <el-button v-if="scope.row.status === 0" size="mini" type="text" @click="openReview(scope.row, 1)" v-hasPermi="['business:refund:review']">通过</el-button>
          <el-button v-if="scope.row.status === 0" size="mini" type="text" class="danger-action" @click="openReview(scope.row, 2)" v-hasPermi="['business:refund:review']">驳回</el-button>
        </template>
      </cy-data-table>

      <el-drawer title="退款证据与处理轨迹" :visible.sync="detailOpen" size="560px" append-to-body>
        <div v-if="detailRow" class="refund-detail">
          <cy-entity-header
            :eyebrow="detailRow.refundNo || `退款申请 #${detailRow.id}`"
            :title="`¥${money(detailRow.refundAmount)}`"
            :status="{ label: payoutLabel(detailRow.payoutStatus, detailRow.status), tone: payoutTone(detailRow.payoutStatus, detailRow.status) }"
            :facts="refundHeaderFacts"
          >
            <template slot="actions">
              <el-button v-if="detailRow.status === 0" size="mini" type="primary" @click="openReview(detailRow, 1)" v-hasPermi="['business:refund:review']">通过</el-button>
              <el-button v-if="detailRow.status === 0" size="mini" type="text" class="danger-action" @click="openReview(detailRow, 2)" v-hasPermi="['business:refund:review']">驳回</el-button>
            </template>
          </cy-entity-header>
          <dl class="refund-detail__context">
            <div><dt>商家</dt><dd>#{{ detailRow.merchantId || '—' }}</dd></div>
            <div><dt>人工处理人</dt><dd>#{{ detailRow.payoutHandlerId || '—' }}</dd></div>
            <div><dt>二次复核人</dt><dd>#{{ detailRow.payoutReviewer2Id || '—' }}</dd></div>
          </dl>
          <section class="refund-detail__reason"><h3>申请原因</h3><p>{{ detailRow.reason || '未填写' }}</p></section>
          <section v-if="detailRow.rejectReason" class="refund-detail__reason refund-detail__reason--danger"><h3>驳回原因</h3><p>{{ detailRow.rejectReason }}</p></section>
          <section v-if="payoutConflict(detailRow)" class="refund-detail__reason refund-detail__reason--danger">
            <h3>状态冲突 · 需核查</h3>
            <p>{{ payoutConflict(detailRow) }}。审核结论与打款回读分别保留，请人工核对渠道结果后再处置，不要以单侧覆盖另一侧。</p>
          </section>
          <div class="payout-note">
            <i class="el-icon-info" /> 审核通过仅代表退款申请获批；微信原路退款成功是渠道回读，人工双人复核只代表凭证流程完成，不代表用户已到账。
          </div>
        </div>
      </el-drawer>

      <el-dialog title="发起售后申请" :visible.sync="open" width="560px" append-to-body>
        <el-form ref="form" :model="form" :rules="rules" label-width="100px">
          <el-form-item label="来源类型" prop="sourceType"><el-select v-model="form.sourceType"><el-option label="报名订单" value="registration" /></el-select></el-form-item>
          <el-form-item label="来源 ID" prop="sourceId"><el-input v-model="form.sourceId" /></el-form-item>
          <el-form-item label="用户 ID" prop="memberId"><el-input v-model="form.memberId" /></el-form-item>
          <el-form-item label="商家 ID" prop="merchantId"><el-input v-model="form.merchantId" /></el-form-item>
          <el-form-item label="支付金额" prop="paidAmount"><el-input v-model="form.paidAmount"><template slot="prepend">¥</template></el-input></el-form-item>
          <el-form-item label="退款金额" prop="refundAmount"><el-input v-model="form.refundAmount"><template slot="prepend">¥</template></el-input></el-form-item>
          <el-form-item label="申请原因" prop="reason"><el-input v-model="form.reason" type="textarea" :rows="3" /></el-form-item>
        </el-form>
        <div slot="footer" class="dialog-footer"><el-button type="primary" :loading="formSubmitting" @click="submitForm">提交申请</el-button><el-button :disabled="formSubmitting" @click="cancel">取消</el-button></div>
      </el-dialog>

      <el-dialog title="退款审核" :visible.sync="reviewOpen" width="520px" append-to-body>
        <el-form :model="reviewForm" label-width="84px">
          <el-form-item label="审核结果"><cy-status-tag :label="reviewLabel(reviewForm.status)" :tone="reviewTone(reviewForm.status)" /></el-form-item>
          <el-form-item label="审核原因"><el-input v-model="reviewForm.reviewReason" type="textarea" :rows="4" placeholder="写明判断依据" /></el-form-item>
        </el-form>
        <div slot="footer" class="dialog-footer"><el-button type="primary" :loading="reviewSubmitting" @click="submitReview">确认审核</el-button><el-button :disabled="reviewSubmitting" @click="reviewOpen = false">取消</el-button></div>
      </el-dialog>
    </template>
  </div>
</template>

<script>
import { listRefund, addRefund, reviewRefund } from '@/api/business/refund'
import auth from '@/plugins/auth'
import ActivityCancelRefundCases from './ActivityCancelRefundCases'
import ManualPayoutQueue from './ManualPayoutQueue'
import CyDataTable from '@/components/CyDataTable'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyEntityLink from '@/components/CyEntityLink'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyStatusTag from '@/components/CyStatusTag'

function firstQueryValue(value) {
  return Array.isArray(value) ? value[0] : value
}

function positiveInteger(value, fallback) {
  const parsed = Number(firstQueryValue(value))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

function optionalQueryValue(value) {
  const normalized = firstQueryValue(value)
  return normalized === undefined || normalized === null || normalized === '' ? null : String(normalized)
}

function refundStatus(value) {
  const parsed = Number(firstQueryValue(value))
  return [0, 1, 2].includes(parsed) ? parsed : null
}

// 转平台筛选只认 0/1,其余(空、数组、小数、字符串杂值)一律 null=全部 —— 失败关闭,
// 别让畸形路由参数把「只看已转平台」悄悄变成别的筛选。
// ⚠️ 空串必须单独挡掉:Number('') === 0,不挡会把「清空」读成「只筛未转平台」。
function takeoverFilter(value) {
  const normalized = firstQueryValue(value)
  if (normalized === undefined || normalized === null || normalized === '') return null
  const parsed = Number(normalized)
  return parsed === 0 || parsed === 1 ? parsed : null
}

// 排序只认 1(按转平台时间);其余一律 null=默认 id 序。
function takeoverOrderFlag(value) {
  const normalized = firstQueryValue(value)
  if (normalized === undefined || normalized === null || normalized === '') return null
  return Number(normalized) === 1 ? 1 : null
}

function createQueryParams(query = {}) {
  return {
    pageNum: positiveInteger(query.pageNum, 1),
    pageSize: positiveInteger(query.pageSize, 10),
    merchantId: optionalQueryValue(query.merchantId),
    memberId: optionalQueryValue(query.memberId),
    status: refundStatus(query.status),
    platformTakeover: takeoverFilter(query.platformTakeover),
    takeoverOrder: takeoverOrderFlag(query.takeoverOrder)
  }
}

const SOURCE_TEXT = { registration: '报名订单' }
const REVIEW_TEXT = { 0: '待审核', 1: '已通过', 2: '已驳回' }
const REVIEW_TONE = { 0: 'warning', 1: 'success', 2: 'danger' }

// 枚举只接受合同规定的标量键: 字符串枚举只认非空字符串, 数字枚举只认整数或纯整数字符串。
// 数组/布尔/对象/小数/空白一律失败关闭, 防止畸形回包把 [1]/true 渲染成正式审核/到账状态。
// 只认自有键，避免 'constructor'/'__proto__' 命中 Object 原型渲染 native function。
function strictInt(value) {
  if (typeof value === 'number') return Number.isInteger(value) ? value : null
  if (typeof value === 'string' && /^-?\d+$/.test(value)) return Number(value)
  return null
}
function enumKey(value, numeric) {
  if (numeric) {
    const parsed = strictInt(value)
    return parsed === null ? null : String(parsed)
  }
  return typeof value === 'string' && value !== '' ? value : null
}
function enumLookup(map, value, fallback, numeric) {
  const key = enumKey(value, numeric)
  if (key === null) return fallback
  return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : fallback
}

export default {
  name: 'FinanceRefund',
  components: { ActivityCancelRefundCases, ManualPayoutQueue, CyDataTable, CyEntityHeader, CyEntityLink, CyFilterBar, CyPageHeader, CyStatusTag },
  data() {
    return {
      isManualQueue: false,
      isActivityCancelQueue: false,
      loading: false,
      listRequestId: 0,
      refundList: [],
      total: 0,
      open: false,
      detailOpen: false,
      reviewOpen: false,
      detailRow: null,
      formSubmitting: false,
      reviewSubmitting: false,
      tableColumns: [
        { key: 'refund', label: '退款申请', minWidth: 210 },
        { key: 'entity', label: '对象', minWidth: 160 },
        { key: 'amount', label: '金额', minWidth: 150 },
        { key: 'review', label: '审核', width: 120 },
        { key: 'payout', label: '打款回读', minWidth: 150 },
        { key: 'reason', prop: 'reason', label: '申请原因', minWidth: 190, showOverflowTooltip: true },
        { key: 'actions', label: '处理', width: 190, fixed: 'right', align: 'right', locked: true }
      ],
      queryParams: createQueryParams(),
      form: {},
      reviewForm: { id: null, status: null, reviewReason: '' },
      rules: {
        sourceType: [{ required: true, message: '来源类型不能为空', trigger: 'change' }],
        sourceId: [{ required: true, message: '来源 ID 不能为空', trigger: 'blur' }],
        refundAmount: [{ required: true, message: '退款金额不能为空', trigger: 'blur' }]
      }
    }
  },
  computed: {
    refundHeaderFacts() {
      if (!this.detailRow) return []
      return [
        { label: '来源', value: `${this.sourceLabel(this.detailRow.sourceType)} #${this.detailRow.sourceId}` },
        { label: '申请人', value: this.detailRow.memberId ? `#${this.detailRow.memberId}` : '—' },
        { label: '审核状态', value: this.reviewLabel(this.detailRow.status) },
        { label: '审核人', value: this.detailRow.reviewerId ? `#${this.detailRow.reviewerId}` : '待分配' },
        this.detailRow.platformTakeoverAt && { label: '转平台', value: `商家超时·已转平台 ${this.takeoverTimeText(this.detailRow.platformTakeoverAt)}` }
      ].filter(Boolean)
    },
    activeFilters() {
      return [
        this.queryParams.merchantId && { key: 'merchantId', label: '商家 ID', value: this.queryParams.merchantId },
        this.queryParams.memberId && { key: 'memberId', label: '用户 ID', value: this.queryParams.memberId },
        this.queryParams.status !== null && this.queryParams.status !== undefined && { key: 'status', label: '审核状态', value: this.reviewLabel(this.queryParams.status) },
        this.queryParams.platformTakeover !== null && this.queryParams.platformTakeover !== undefined && { key: 'platformTakeover', label: '转平台', value: this.queryParams.platformTakeover === 1 ? '商家超时·已转平台' : '未转平台' },
        this.queryParams.takeoverOrder !== null && this.queryParams.takeoverOrder !== undefined && { key: 'takeoverOrder', label: '排序', value: '按转平台时间' }
      ].filter(Boolean)
    },
    overview() {
      const pending = this.refundList.filter(item => item.status === 0).length
      const manual = this.refundList.filter(item => item.payoutStatus === 2 || item.payoutStatus === 3).length
      const channelSuccess = this.refundList.filter(item => item.payoutStatus === 1).length
      const evidenceVerified = this.refundList.filter(item => item.payoutStatus === 4).length
      const amount = this.refundList.reduce((sum, item) => sum + Number(item.refundAmount || 0), 0)
      return [
        { label: '待审核', value: pending, note: '本页需要判断' },
        { label: '待人工处理 / 复核', value: manual, note: '本页打款异常' },
        { label: '原路渠道成功', value: channelSuccess, note: '微信退款成功回读' },
        { label: '人工凭证已复核', value: evidenceVerified, note: '不代表用户到账' },
        { label: '申请退款金额', value: `¥${this.money(amount)}`, note: '当前页合计' }
      ]
    }
  },
  created() {
    this.loadRoute(this.$route.query)
  },
  beforeRouteUpdate(to, from, next) {
    this.loadRoute(to.query)
    next()
  },
  methods: {
    loadRoute(query = {}) {
      const manualQueue = firstQueryValue(query.queue) === 'manual'
      const activityCancelQueue = firstQueryValue(query.queue) === 'activity-cancel'
      const requiredPermission = manualQueue || activityCancelQueue ? 'business:refund:review' : 'business:refund:list'
      if (!auth.hasPermi(requiredPermission)) {
        this.isManualQueue = false
        this.isActivityCancelQueue = false
        this.$router.replace('/401')
        return
      }
      this.isManualQueue = manualQueue
      this.isActivityCancelQueue = activityCancelQueue
      if (this.isManualQueue || this.isActivityCancelQueue) return
      this.queryParams = createQueryParams(query)
      this.getList()
    },
    getList() {
      const requestId = ++this.listRequestId
      this.loading = true
      return listRefund(this.queryParams).then(response => {
        if (requestId !== this.listRequestId) return
        this.refundList = response.rows || []
        this.total = response.total || 0
      }).finally(() => {
        if (requestId === this.listRequestId) this.loading = false
      })
    },
    sourceLabel(value) {
      const key = enumKey(value, false)
      if (key === null) return '未知来源'
      return Object.prototype.hasOwnProperty.call(SOURCE_TEXT, key) ? SOURCE_TEXT[key] : key
    },
    money(value) { return Number(value || 0).toFixed(2) },
    takeoverTimeText(value) { return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '' },
    reviewLabel(value) { return enumLookup(REVIEW_TEXT, value, '未知状态', true) },
    reviewTone(value) { return enumLookup(REVIEW_TONE, value, 'neutral', true) },
    payoutLabel(value, reviewStatus) {
      const review = strictInt(reviewStatus)
      const payout = strictInt(value)
      if (payout === null) return '待确认'
      if (payout === 0) {
        // 0 是新建退款的默认值，含义随审核终态而定；它不是"钱已动"的事实。
        if (review === 1) return '原路退款处理中'
        if (review === 0) return '未发起打款'
        if (review === 2) return '未执行退款'
        return '待确认'
      }
      return {
        1: '原路渠道成功',
        2: '待人工处理',
        3: '待二次复核',
        4: '人工凭证复核完成',
        5: '待派发微信',
        6: '派发中'
      }[payout] || '待确认'
    },
    payoutTone(value, reviewStatus) {
      const review = strictInt(reviewStatus)
      const payout = strictInt(value)
      if (payout === null) return 'neutral'
      if (payout === 0) return review === 1 ? 'info' : 'neutral'
      return { 1: 'success', 2: 'danger', 3: 'warning', 4: 'warning', 5: 'info', 6: 'warning' }[payout] || 'neutral'
    },
    // 审核与打款在后端解耦：钱已动/在途但审核未通过(或已驳回)是矛盾组合，必须并列标出，不能任选一侧覆盖。
    payoutConflict(row) {
      if (!row) return ''
      const review = strictInt(row.status)
      const payout = strictInt(row.payoutStatus)
      if (payout === null || payout === 0) return ''
      if (review === null || review === 1) return ''
      const label = this.payoutLabel(payout, review)
      if (review === 0) return `尚未审核通过，但打款已${label}，需核查`
      if (review === 2) return `审核已驳回，但打款已${label}，需核查`
      return ''
    },
    openDetail(row) { this.detailRow = row; this.detailOpen = true },
    goActivityCancelQueue() { this.$router.push({ name: 'financeRefund', query: { queue: 'activity-cancel' } }) },
    goManualQueue() { this.$router.push({ name: 'financeRefund', query: { queue: 'manual' } }) },
    goRefundQueue() { this.$router.push({ name: 'financeRefund' }) },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    clearFilter(key) { this.queryParams[key] = null; this.handleQuery() },
    reset() {
      this.form = { sourceType: 'registration', sourceId: null, memberId: null, merchantId: null, paidAmount: null, refundAmount: null, reason: '' }
      this.resetForm('form')
    },
    handleAdd() { this.reset(); this.open = true; this.formSubmitting = false },
    cancel() { this.open = false; this.reset() },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        this.formSubmitting = true
        addRefund(this.form).then(() => { this.$modal.msgSuccess('售后申请已提交'); this.open = false; this.getList() }).finally(() => { this.formSubmitting = false })
      })
    },
    openReview(row, status) {
      this.reviewForm = { id: row.id, status, reviewReason: '' }
      this.reviewSubmitting = false
      this.reviewOpen = true
    },
    submitReview() {
      this.reviewSubmitting = true
      reviewRefund(this.reviewForm).then(() => {
        this.$modal.msgSuccess('审核处理成功')
        this.reviewOpen = false
        this.detailOpen = false
        this.getList()
      }).finally(() => { this.reviewSubmitting = false })
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-domain-page { color: var(--cy-color-text-primary); }
.cy-panel { margin-bottom: 20px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.cy-panel__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 20px var(--cy-space-page) 16px; }
.cy-panel__heading h2 { margin: 0; font-size: 17px; }
.cy-panel__heading p { margin: var(--cy-space-0-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; }
.refund-overview { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: var(--cy-space-2-5); margin-bottom: 20px; }
.refund-overview__item { padding: var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.refund-overview span, .refund-overview strong, .refund-overview small { display: block; }
.refund-overview span { color: var(--cy-color-text-secondary); font-size: 12px; }
.refund-overview strong { margin: 8px 0 var(--cy-legacy-3); font-size: 24px; }
.refund-overview small { color: var(--cy-color-text-tertiary); }
.table-stack span, .table-stack small { display: block; }
.table-stack small { margin-top: 4px; color: var(--cy-color-text-tertiary); }
.table-stack--amount span { color: var(--cy-color-text-primary); font-weight: 700; }
.payout-conflict { display: block; margin-top: 4px; color: var(--cy-color-status-danger); line-height: 1.4; }
.danger-action { color: var(--cy-color-status-danger); }
.refund-detail { padding: 0 24px var(--cy-space-7); }
.refund-detail__context { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--cy-space-1-5); margin: var(--cy-space-3-5) 0; }
.refund-detail__context div { padding: var(--cy-space-2-5); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.refund-detail__context dt { color: var(--cy-color-text-tertiary); font-size: 11px; }
.refund-detail__context dd { margin: var(--cy-space-0-5) 0 0; font-weight: 700; }
.refund-detail__reason { margin-top: var(--cy-space-2-5); padding: 16px; border: 1px solid var(--cy-color-border-subtle); border-radius: 8px; }
.refund-detail__reason h3 { margin: 0 0 8px; font-size: 14px; }
.refund-detail__reason p { margin: 0; color: var(--cy-color-text-secondary); line-height: 1.6; }
.refund-detail__reason--danger { border-color: #f3cbd0; background: var(--cy-color-status-danger-soft); }
.payout-note { margin-top: var(--cy-space-3-5); padding: var(--cy-space-2-5); border-radius: 6px; background: var(--cy-color-status-info-soft); color: var(--cy-color-status-info); font-size: 13px; line-height: 1.6; }
@media (max-width: 900px) { .refund-overview { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 600px) { .refund-overview, .refund-detail__context { grid-template-columns: 1fr; } }
</style>

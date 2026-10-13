<template>
  <div class="app-container cy-domain-page bank-withdraw">
    <cy-page-header
      eyebrow="资金与售后"
      title="银行卡提现"
      description="处理用户发起的银行卡提现审核。列表始终只显示掩码资料；明文收款资料仅在受控查看时按需读取并留审计记录。"
    />

    <section class="bank-withdraw__filter" aria-label="银行卡提现筛选">
      <el-form :model="filters" :inline="true" size="small" label-width="108px" @submit.native.prevent>
        <el-form-item label="用户编号（精确）">
          <el-input-number v-model="filters.memberId" :min="1" :precision="0" :controls="false" placeholder="可选" @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item label="审核状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态">
            <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="银行卡提现队列暂时无法读取"
      description="没有修改任何提现记录。请重试；若仍失败，请核对列表权限与服务状态。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <template v-else>
      <cy-empty-state
        v-if="!loading && withdrawalList.length === 0"
        title="当前筛选没有银行卡提现申请"
        description="这表示没有符合条件的业务记录，不代表提现功能或数据源异常。"
      />

      <section v-else class="bank-withdraw__table-shell">
        <el-table v-loading="loading" :data="withdrawalList" class="bank-withdraw__table">
          <el-table-column label="用户" min-width="150">
            <template slot-scope="scope">
              <div class="bank-withdraw__identity">
                <strong>{{ scope.row.realnameMask || `用户 #${scope.row.memberId || '—'}` }}</strong>
                <span>用户 #{{ scope.row.memberId || '—' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="收款资料（掩码）" min-width="215">
            <template slot-scope="scope">
              <div class="bank-withdraw__payout-mask">
                <span>{{ scope.row.bankNameMask || '未记录银行' }}</span>
                <span>{{ scope.row.bankAccountMask || '未记录账号' }}</span>
                <span>{{ scope.row.mobilephoneMask || '未记录电话' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="申请金额" width="118" align="right">
            <template slot-scope="scope">{{ formatAmount(scope.row.withdrawalAmount) }}</template>
          </el-table-column>
          <el-table-column label="手续费" width="100" align="right">
            <template slot-scope="scope">{{ formatAmount(scope.row.serviceFee) }}</template>
          </el-table-column>
          <el-table-column label="实际到账" width="118" align="right">
            <template slot-scope="scope">{{ formatAmount(scope.row.receivedAmount) }}</template>
          </el-table-column>
          <el-table-column label="审核状态" width="150">
            <template slot-scope="scope">
              <el-tag :type="statusTagType(scope.row.status)" effect="plain">{{ statusLabel(scope.row.status) }}</el-tag>
              <p v-if="scope.row.status === 2 && scope.row.remark" class="bank-withdraw__reason">驳回：{{ scope.row.remark }}</p>
            </template>
          </el-table-column>
          <el-table-column label="申请时间" width="168">
            <template slot-scope="scope">{{ formatTime(scope.row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="审核记录" width="168">
            <template slot-scope="scope">
              <span v-if="scope.row.auditTime">{{ formatTime(scope.row.auditTime) }}</span>
              <span v-else>待处理</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="164" class-name="small-padding fixed-width">
            <template slot-scope="scope">
              <el-button
                v-if="scope.row.status === 0"
                type="text"
                size="small"
                icon="el-icon-s-check"
                v-hasPermi="['business:memberwithdrawal:audit']"
                @click="openReview(scope.row)"
              >审核</el-button>
              <el-button
                type="text"
                size="small"
                icon="el-icon-view"
                v-hasPermi="['business:memberwithdrawal:viewpii']"
                @click="openPayout(scope.row)"
              >查看收款资料</el-button>
            </template>
          </el-table-column>
        </el-table>

        <pagination
          v-show="total > 0"
          :total="total"
          :page.sync="filters.pageNum"
          :limit.sync="filters.pageSize"
          @pagination="getList"
        />
      </section>
    </template>

    <el-dialog
      title="审核银行卡提现"
      :visible.sync="reviewOpen"
      width="500px"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!reviewSaving"
      :show-close="!reviewSaving"
      :before-close="beforeReviewClose"
    >
      <el-alert
        :title="reviewNotice"
        :type="reviewForm.status === 2 ? 'warning' : 'info'"
        :closable="false"
        show-icon
        class="bank-withdraw__review-notice"
      />
      <el-form ref="reviewForm" :model="reviewForm" label-width="94px">
        <el-form-item label="提现申请">
          <span>{{ reviewForm.memberLabel }} · {{ formatAmount(reviewForm.withdrawalAmount) }}</span>
        </el-form-item>
        <el-form-item label="审核决定" required>
          <el-radio-group v-model="reviewForm.status" @change="reviewReasonError = ''">
            <el-radio :label="1">通过</el-radio>
            <el-radio :label="2">驳回并退回余额</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处理说明" :required="reviewForm.status === 2" :error="reviewReasonError">
          <el-input
            v-model="reviewForm.reviewReason"
            type="textarea"
            :rows="4"
            :maxlength="500"
            show-word-limit
            :placeholder="reviewForm.status === 2 ? '驳回必须填写原因' : '可选，供后续核对使用'"
            @input="reviewReasonError = ''"
          />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="reviewSaving" @click="closeReview">取消</el-button>
        <el-button type="primary" :loading="reviewSaving" @click="submitReview">确认审核</el-button>
      </div>
    </el-dialog>

    <el-dialog
      title="收款资料（明文）"
      :visible.sync="payout.open"
      width="500px"
      append-to-body
      :close-on-click-modal="false"
      @close="closePayout"
    >
      <el-alert
        title="本次查看已记入审计日志。请仅用于打款核对，不要截图或转发。"
        type="warning"
        :closable="false"
        show-icon
        class="bank-withdraw__payout-warning"
      />
      <cy-resource-state
        v-if="payout.loadError"
        kind="error"
        title="收款资料无法读取"
        description="请核对查看权限或稍后重试；未展示任何明文资料。"
        retry-text="重新读取"
        :retrying="payout.loading"
        @retry="retryPayout"
      />
      <template v-else>
        <cy-detail-grid v-loading="payout.loading" :items="payoutDetailItems" :columns="1"
          empty-text="未读取到收款资料"
        />
      </template>
      <div slot="footer" class="dialog-footer">
        <el-button @click="closePayout">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listMemberwithdrawal, reviewMemberwithdrawal, getMemberwithdrawalPayout } from '@/api/business/memberwithdrawal'
import CyDetailGrid from '@/components/CyDetailGrid'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'Memberwithdrawal',
  components: { CyDetailGrid, CyEmptyState, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      listError: false,
      listRequestId: 0,
      total: 0,
      withdrawalList: [],
      filters: {
        pageNum: 1,
        pageSize: 10,
        memberId: null,
        status: null
      },
      statusOptions: [
        { value: 0, label: '待审核' },
        { value: 1, label: '审核通过' },
        { value: 2, label: '审核驳回（余额已退回）' }
      ],
      reviewOpen: false,
      reviewSaving: false,
      reviewReasonError: '',
      reviewForm: {
        id: null,
        memberLabel: '',
        withdrawalAmount: null,
        status: 1,
        reviewReason: ''
      },
      payoutRequestId: 0,
      payout: {
        id: null,
        open: false,
        loading: false,
        loadError: false,
        data: {}
      }
    }
  },
  computed: {
    reviewNotice() {
      return this.reviewForm.status === 2
        ? '驳回会由服务端退回用户余额。请确认金额与处理原因，提交后不能在本页撤销。'
        : '通过仅确认当前待审核申请。服务端会拒绝已处理记录，避免重复审核。'
    },
    payoutDetailItems() {
      return [
        { key: 'realname', label: '姓名', value: this.payout.data.realname || '—' },
        { key: 'bankName', label: '银行名称', value: this.payout.data.bankName || '—' },
        { key: 'bankAccount', label: '银行账号', value: this.payout.data.bankAccount || '—' },
        { key: 'mobilephone', label: '联系电话', value: this.payout.data.mobilephone || '—' },
        { key: 'withdrawalAmount', label: '提现金额', value: this.formatAmount(this.payout.data.withdrawalAmount) },
        { key: 'receivedAmount', label: '实际到账', value: this.formatAmount(this.payout.data.receivedAmount) }
      ]
    }
  },
  created() {
    this.ensureCanonicalRoute()
  },
  watch: {
    '$route.fullPath'() {
      this.ensureCanonicalRoute()
    }
  },
  methods: {
    ensureCanonicalRoute() {
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/finance/withdraw/bank') {
        this.$router.replace({
          name: 'financeBankWithdraw',
          query: this.safeRouteQuery()
        })
        return
      }
      this.applyRouteQuery()
      this.getList()
    },
    safeRouteQuery() {
      const query = {}
      const memberId = this.positiveIntegerOrNull(this.$route.query.memberId)
      const status = this.withdrawalStatusOrNull(this.$route.query.status)
      const pageNum = this.positiveIntegerOrNull(this.$route.query.pageNum)
      const pageSize = this.positiveIntegerOrNull(this.$route.query.pageSize)
      if (memberId != null) query.memberId = String(memberId)
      if (status != null) query.status = String(status)
      if (pageNum != null) query.pageNum = String(pageNum)
      if (pageSize != null) query.pageSize = String(pageSize)
      return query
    },
    applyRouteQuery() {
      this.filters = Object.assign({}, this.filters, {
        pageNum: this.positiveIntegerOrNull(this.$route.query.pageNum) || 1,
        pageSize: this.positiveIntegerOrNull(this.$route.query.pageSize) || 10,
        memberId: this.positiveIntegerOrNull(this.$route.query.memberId),
        status: this.withdrawalStatusOrNull(this.$route.query.status)
      })
    },
    positiveIntegerOrNull(value) {
      const parsed = Number(value)
      return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
    },
    withdrawalStatusOrNull(value) {
      if (value == null || value === '') return null
      const parsed = Number(value)
      return [0, 1, 2].includes(parsed) ? parsed : null
    },
    toListQuery() {
      return {
        pageNum: this.filters.pageNum,
        pageSize: this.filters.pageSize,
        memberId: this.positiveIntegerOrNull(this.filters.memberId),
        status: this.withdrawalStatusOrNull(this.filters.status)
      }
    },
    getList() {
      const requestId = ++this.listRequestId
      this.loading = true
      this.listError = false
      return listMemberwithdrawal(this.toListQuery()).then(response => {
        if (requestId !== this.listRequestId) return
        this.withdrawalList = response.rows || []
        this.total = response.total || 0
      }).catch(() => {
        if (requestId !== this.listRequestId) return
        this.withdrawalList = []
        this.total = 0
        this.listError = true
      }).then(() => {
        if (requestId === this.listRequestId) this.loading = false
      })
    },
    handleQuery() {
      this.filters.pageNum = 1
      this.getList()
    },
    resetFilters() {
      this.filters = {
        pageNum: 1,
        pageSize: 10,
        memberId: null,
        status: null
      }
      this.getList()
    },
    statusLabel(status) {
      return { 0: '待审核', 1: '审核通过', 2: '审核驳回（余额已退回）' }[status] || '状态未知'
    },
    statusTagType(status) {
      return { 0: 'warning', 1: 'success', 2: 'danger' }[status] || 'info'
    },
    formatAmount(value) {
      if (value == null || value === '') return '—'
      const amount = Number(value)
      return Number.isFinite(amount) ? `¥${amount.toFixed(2)}` : '—'
    },
    formatTime(value) {
      return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '—'
    },
    openReview(row) {
      if (!row || row.status !== 0) return
      this.reviewForm = {
        id: row.id,
        memberLabel: row.realnameMask || `用户 #${row.memberId || '—'}`,
        withdrawalAmount: row.withdrawalAmount,
        status: 1,
        reviewReason: ''
      }
      this.reviewReasonError = ''
      this.reviewOpen = true
    },
    closeReview() {
      if (!this.reviewSaving) this.reviewOpen = false
    },
    beforeReviewClose(done) {
      if (!this.reviewSaving) done()
    },
    normalizedReviewReason() {
      return String(this.reviewForm.reviewReason || '').trim()
    },
    submitReview() {
      if (this.reviewSaving || ![1, 2].includes(this.reviewForm.status)) return
      const reviewReason = this.normalizedReviewReason()
      if (this.reviewForm.status === 2 && !reviewReason) {
        this.reviewReasonError = '驳回必须填写原因'
        return
      }
      this.reviewSaving = true
      reviewMemberwithdrawal({
        id: this.reviewForm.id,
        status: this.reviewForm.status,
        reviewReason: this.normalizedReviewReason()
      }).then(() => {
        this.$modal.msgSuccess(this.reviewForm.status === 2 ? '已驳回并提交余额退回' : '银行卡提现已审核通过')
        this.reviewOpen = false
        return this.getList()
      }).catch(() => {
        this.$modal.msgError('审核未完成，请刷新后确认当前状态')
      }).then(() => {
        this.reviewSaving = false
      })
    },
    openPayout(row) {
      if (!row || !row.id) return
      const requestId = ++this.payoutRequestId
      this.payout = {
        id: row.id,
        open: true,
        loading: true,
        loadError: false,
        data: {}
      }
      getMemberwithdrawalPayout(row.id).then(response => {
        if (requestId !== this.payoutRequestId) return
        this.payout.data = response.data || {}
        this.payout.loading = false
      }).catch(() => {
        if (requestId !== this.payoutRequestId) return
        this.payout.loading = false
        this.payout.loadError = true
      })
    },
    retryPayout() {
      const row = { id: this.payout.id }
      this.openPayout(row)
    },
    closePayout() {
      this.payoutRequestId += 1
      this.payout.open = false
      this.payout.data = {}
      this.payout.loadError = false
    }
  }
}
</script>

<style lang="scss" scoped>
.bank-withdraw__filter { margin-bottom: var(--cy-space-3-5); padding: 16px var(--cy-space-3-5) 2px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); }
.bank-withdraw__table-shell { overflow: hidden; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); }
.bank-withdraw__table { width: 100%; }
.bank-withdraw__identity { display: grid; gap: var(--cy-legacy-3); }
.bank-withdraw__identity strong { color: var(--cy-color-text-primary); font-weight: 600; }
.bank-withdraw__identity span, .bank-withdraw__payout-mask { color: var(--cy-color-text-secondary); font-size: 12px; }
.bank-withdraw__payout-mask { display: grid; gap: var(--cy-legacy-3); line-height: 1.45; }
.bank-withdraw__reason { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.45; white-space: normal; }
.bank-withdraw__review-notice, .bank-withdraw__payout-warning { margin-bottom: var(--cy-space-3-5); }

@media (max-width: 720px) {
  .bank-withdraw__filter { padding: var(--cy-space-2-5) var(--cy-space-2-5) 1px; }
}
</style>

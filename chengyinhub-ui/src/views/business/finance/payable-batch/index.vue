<template>
  <div class="app-container cy-domain-page">
    <club-direct-receivables />
    <cy-page-header
      eyebrow="资金与结算"
      title="商家应付月结"
      description="按自然月核对商家应付台账、记录已发生的对公打款与发票事实；这里不发起转账，也不改变应付计算。"
      :updated-at="updatedAt"
    >
      <el-button
        slot="primary"
        type="warning"
        icon="el-icon-plus"
        :loading="generating"
        v-hasPermi="['business:payablebatch:edit']"
        @click="handleGenerate"
      >生成该结算期批次</el-button>
    </cy-page-header>

    <section class="cy-panel payable-batch__filters" aria-label="商家应付月结筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>结算期与操作边界</h2>
          <p>只能结已经结束的自然月。对账、打款和开票均在服务端状态机约束下记录事实，不能替代对公网银或税务系统。</p>
        </div>
        <cy-status-tag label="仅记录已发生事实" tone="neutral" />
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="64px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="结算期" prop="periodYm">
            <el-date-picker
              v-model="queryForm.periodYm"
              type="month"
              value-format="yyyy-MM"
              placeholder="选择已结束的自然月"
              :picker-options="pickerOptions"
              style="width: 180px"
            />
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="batchError"
      kind="error"
      title="应付月结批次暂时无法读取"
      description="读取失败不会被显示成“没有批次”；请在网络或权限恢复后重新读取。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <section v-else class="cy-panel payable-batch__table-panel" aria-label="商家应付月结批次">
      <div class="cy-panel__heading">
        <div>
          <h2>{{ appliedPeriodYm || '未选择结算期' }} 的应付批次</h2>
          <p>{{ batchList.length ? `共 ${batchList.length} 个商家批次；净额可能为负，表示该结算期冲正后商家反欠平台。` : '该结算期没有批次不等于故障：可能尚无应付核销，或尚未生成批次。' }}</p>
        </div>
        <div v-if="batchList.length" class="payable-batch__summary">
          <strong>{{ formatMoney(batchTotal) }}</strong>
          <span>当前批次净额</span>
        </div>
      </div>

      <div v-loading="loading" class="payable-batch__table-wrap">
        <el-table v-if="batchList.length" :data="batchList" class="payable-batch__table">
          <el-table-column label="月结对象" min-width="170">
            <template slot-scope="scope">
              <div class="payable-batch__merchant">
                <strong>商家 #{{ scope.row.merchantMemberId }}</strong>
                <span>批次 #{{ scope.row.id }} · {{ scope.row.periodYm || appliedPeriodYm }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="应付净额" width="132" align="right">
            <template slot-scope="scope">
              <strong :class="['payable-batch__amount', { 'payable-batch__amount--negative': isNegative(scope.row.amountTotal) }]">{{ formatMoney(scope.row.amountTotal) }}</strong>
            </template>
          </el-table-column>
          <el-table-column label="结算状态" width="116" align="center">
            <template slot-scope="scope"><cy-status-tag :label="statusLabel(scope.row.status)" :tone="statusTone(scope.row.status)" /></template>
          </el-table-column>
          <el-table-column label="发票状态" width="116" align="center">
            <template slot-scope="scope"><cy-status-tag :label="invoiceLabel(scope.row.invoiceStatus)" :tone="invoiceTone(scope.row.invoiceStatus)" /></template>
          </el-table-column>
          <el-table-column label="凭证与发票" min-width="200">
            <template slot-scope="scope">
              <div class="payable-batch__references">
                <span>打款：{{ scope.row.payVoucherNo || '未记录' }}</span>
                <span>蓝票：{{ scope.row.invoiceNo || '未记录' }}</span>
                <span>红票：{{ scope.row.redInvoiceNo || '未记录' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="记录人" width="140">
            <template slot-scope="scope">
              <div class="payable-batch__operators">
                <span>对账：{{ scope.row.confirmedBy || '未记录' }}</span>
                <span>打款：{{ scope.row.paidBy || '未记录' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="记录动作" width="228" fixed="right" align="right">
            <template slot-scope="scope">
              <el-button
                v-if="scope.row.status === 'PENDING'"
                v-hasPermi="['business:payablebatch:edit']"
                size="mini"
                type="text"
                icon="el-icon-check"
                @click="handleConfirm(scope.row)"
              >对账确认</el-button>
              <el-button
                v-if="scope.row.status === 'CONFIRMED'"
                v-hasPermi="['business:payablebatch:edit']"
                size="mini"
                type="text"
                icon="el-icon-document-checked"
                @click="openPaid(scope.row)"
              >记录打款</el-button>
              <el-button
                v-if="scope.row.invoiceStatus === 'NONE'"
                v-hasPermi="['business:payablebatch:edit']"
                size="mini"
                type="text"
                icon="el-icon-tickets"
                @click="openInvoice(scope.row)"
              >记录开票</el-button>
              <el-button
                v-if="scope.row.invoiceStatus === 'ISSUED'"
                v-hasPermi="['business:payablebatch:edit']"
                size="mini"
                type="text"
                icon="el-icon-refresh-left"
                @click="openRed(scope.row)"
              >记录红冲</el-button>
            </template>
          </el-table-column>
        </el-table>

        <cy-empty-state
          v-else-if="!loading"
          title="这个结算期没有应付批次"
          description="没有可结算的核销事实时，空列表是正常结果；若确认该已结束结算期已有应付发生，可由具备编辑权限的人员生成该结算期批次。"
          icon="el-icon-document-remove"
        />
      </div>
    </section>

    <el-dialog :title="dialog.title" :visible.sync="dialog.visible" width="460px" append-to-body :close-on-click-modal="false">
      <el-alert v-if="dialog.hint" type="warning" :closable="false" show-icon class="mb8">
        <span slot="title" class="payable-batch__dialog-hint">{{ dialog.hint }}</span>
      </el-alert>
      <el-form label-width="92px">
        <el-form-item :label="dialog.label">
          <el-input v-model="dialog.value" :placeholder="dialog.placeholder" clearable />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button :disabled="dialog.loading" @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="submitDialog">确认记录</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listPayableBatch, generatePayableBatch, confirmPayableBatch,
  markPayableBatchPaid, markPayableBatchInvoice, markPayableBatchInvoiceRed
} from '@/api/business/payablebatch'
import ClubDirectReceivables from './ClubDirectReceivables'
import CyEmptyState from '@/components/CyEmptyState'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

export default {
  name: 'FinancePayableBatch',
  components: { ClubDirectReceivables, CyEmptyState, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      generating: false,
      batchError: false,
      batchRequestId: 0,
      updatedAt: '',
      appliedPeriodYm: '',
      batchList: [],
      queryForm: { periodYm: '' },
      dialog: { visible: false, loading: false, title: '', label: '', value: '', placeholder: '', hint: '', action: null, row: null },
      pickerOptions: {
        disabledDate(date) {
          const now = new Date()
          return date >= new Date(now.getFullYear(), now.getMonth(), 1)
        }
      }
    }
  },
  computed: {
    activeFilters() {
      return this.appliedPeriodYm ? [{ key: 'periodYm', label: '结算期', value: this.appliedPeriodYm }] : []
    },
    batchTotal() {
      return this.batchList.reduce((total, row) => total + (Number(row.amountTotal) || 0), 0)
    }
  },
  created() {
    this.queryForm.periodYm = this.prevMonth(new Date())
    this.appliedPeriodYm = this.queryForm.periodYm
    this.getList()
  },
  methods: {
    prevMonth(now) {
      const previous = new Date(now.getFullYear(), now.getMonth() - 1, 1)
      return previous.getFullYear() + '-' + String(previous.getMonth() + 1).padStart(2, '0')
    },
    statusLabel(status) {
      return { PENDING: '待对账', CONFIRMED: '已对账', PAID: '已记录打款' }[status] || '状态未记录'
    },
    statusTone(status) {
      return { PENDING: 'neutral', CONFIRMED: 'warning', PAID: 'success' }[status] || 'neutral'
    },
    invoiceLabel(status) {
      return { NONE: '未开票', ISSUED: '已开票', RED: '已红冲' }[status] || '状态未记录'
    },
    invoiceTone(status) {
      return { NONE: 'neutral', ISSUED: 'success', RED: 'danger' }[status] || 'neutral'
    },
    isNegative(value) {
      return Number(value) < 0
    },
    formatMoney(value) {
      const amount = Number(value)
      if (!Number.isFinite(amount)) return '未记录'
      return `${amount < 0 ? '-' : ''}¥${Math.abs(amount).toFixed(2)}`
    },
    getList() {
      if (!this.appliedPeriodYm) {
        this.$modal.msgWarning('请先选择结算期')
        return Promise.resolve()
      }
      const requestId = ++this.batchRequestId
      this.loading = true
      this.batchError = false
      return listPayableBatch(this.appliedPeriodYm)
        .then(response => {
          if (requestId !== this.batchRequestId) return
          this.batchList = response.data || []
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.batchRequestId) return
          this.batchList = []
          this.batchError = true
        })
        .finally(() => {
          if (requestId === this.batchRequestId) this.loading = false
        })
    },
    handleQuery() {
      if (!this.queryForm.periodYm) {
        this.$modal.msgWarning('请先选择结算期')
        return Promise.resolve()
      }
      this.appliedPeriodYm = this.queryForm.periodYm
      return this.getList()
    },
    resetQuery() {
      this.queryForm.periodYm = this.prevMonth(new Date())
      this.appliedPeriodYm = this.queryForm.periodYm
      return this.getList()
    },
    clearFilter() {
      return this.resetQuery()
    },
    handleGenerate() {
      const periodYm = this.queryForm.periodYm
      if (!periodYm) {
        this.$modal.msgWarning('请先选择结算期')
        return
      }
      this.$modal.confirm(`生成 ${periodYm} 的月结批次？每个有应付发生的商家一张，已生成的会跳过。`)
        .then(() => {
          this.generating = true
          return generatePayableBatch(periodYm).then(response => {
            this.$modal.msgSuccess(response.msg || '已生成')
            this.appliedPeriodYm = periodYm
            return this.getList()
          })
        })
        .catch(() => {})
        .finally(() => { this.generating = false })
    },
    handleConfirm(row) {
      this.$modal.confirm(`确认 ${row.periodYm} / 商家 ${row.merchantMemberId} 的应付净额 ${this.formatMoney(row.amountTotal)} 已核对无误？确认后金额锁定，方可记录打款。`)
        .then(() => confirmPayableBatch(row.id))
        .then(() => {
          this.$modal.msgSuccess('已对账确认')
          return this.getList()
        })
        .catch(() => {})
    },
    openPaid(row) {
      this.dialog = {
        visible: true, loading: false, row, action: 'paid',
        title: '记录打款', label: '打款凭证号', value: '',
        placeholder: '对公打款的流水号或凭证号',
        hint: '这里记录的是“钱已经从对公账户打出”这一事实，不会触发任何转账。'
      }
    },
    openInvoice(row) {
      this.dialog = {
        visible: true, loading: false, row, action: 'invoice',
        title: '记录开票', label: '蓝票号', value: '', placeholder: '商家开来的增值税发票号', hint: ''
      }
    },
    openRed(row) {
      this.dialog = {
        visible: true, loading: false, row, action: 'red',
        title: '记录红冲', label: '红票号', value: '', placeholder: '红字发票号',
        hint: '已开票批次中的核销被撤销时，税务上需要对应的红字发票；这里仅回填票号，不代开票。'
      }
    },
    submitDialog() {
      const dialog = this.dialog
      if (!dialog.value) {
        this.$modal.msgWarning(`请填写${dialog.label}`)
        return
      }
      const request = {
        paid: () => markPayableBatchPaid(dialog.row.id, dialog.value),
        invoice: () => markPayableBatchInvoice(dialog.row.id, dialog.value),
        red: () => markPayableBatchInvoiceRed(dialog.row.id, dialog.value)
      }[dialog.action]
      dialog.loading = true
      request()
        .then(() => {
          this.$modal.msgSuccess('已记录')
          dialog.visible = false
          return this.getList()
        })
        .catch(() => {})
        .finally(() => { dialog.loading = false })
    }
  }
}
</script>

<style lang="scss" scoped>
.payable-batch__filters, .payable-batch__table-panel { margin-bottom: 20px; }
.payable-batch__summary { flex: none; text-align: right; }
.payable-batch__summary strong, .payable-batch__summary span, .payable-batch__merchant strong, .payable-batch__merchant span, .payable-batch__references span, .payable-batch__operators span { display: block; }
.payable-batch__summary strong { color: var(--cy-color-text-primary); font-size: 18px; font-variant-numeric: tabular-nums; }
.payable-batch__summary span, .payable-batch__merchant span, .payable-batch__references span, .payable-batch__operators span { margin-top: 4px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.payable-batch__merchant strong { color: var(--cy-color-text-primary); }
.payable-batch__table-wrap { min-height: 140px; overflow-x: auto; }
.payable-batch__table { width: 100%; }
.payable-batch__table ::v-deep::before { display: none; }
.payable-batch__table ::v-deep th { height: 40px; background: var(--cy-color-bg-subtle); color: var(--cy-color-text-secondary); font-size: 12px; font-weight: 700; }
.payable-batch__table ::v-deep td { height: 56px; border-bottom-color: var(--cy-color-border-subtle); color: var(--cy-color-text-primary); }
.payable-batch__amount { color: var(--cy-color-text-primary); font-variant-numeric: tabular-nums; }
.payable-batch__amount--negative { color: var(--cy-color-status-danger); }
.payable-batch__dialog-hint { font-weight: 400; line-height: 1.65; }

@media (max-width: 720px) {
  .payable-batch__summary { text-align: left; }
}
</style>

<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="资金与售后"
      title="提现回补队列"
      description="核查微信提现结果与回补；只有扣款和渠道资金事实符合条件，才能登记并由另一人复核。"
      :updated-at="updatedAt"
    >
      <el-button slot="primary" type="warning" plain icon="el-icon-warning-outline" :disabled="loading" @click="showManualQueue">只看人工队列</el-button>
    </cy-page-header>

    <section class="cy-panel cashwithdraw-filter" aria-label="提现队列筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>队列定位</h2>
          <p>按用户或处理状态缩小当前待处理范围。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="58px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="用户" prop="memberId">
            <el-input v-model="queryForm.memberId" placeholder="输入用户 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryForm.status" placeholder="全部状态" clearable style="width: 150px">
              <el-option label="处理中" :value="0" />
              <el-option label="成功" :value="1" />
              <el-option label="失败已退款" :value="2" />
              <el-option label="待人工核查" :value="3" />
              <el-option label="人工回补待复核" :value="4" />
              <el-option label="未实际扣款" :value="5" />
            </el-select>
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-data-table
      :data="list"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page="queryParams.pageNum"
      :limit="queryParams.pageSize"
      empty-text="当前筛选下没有提现记录"
      default-density="comfortable"
      @refresh="getList"
      @pagination="handlePagination"
    >
      <template #withdrawal="{ row }">
        <div class="cashwithdraw-object">
          <strong>提现 #{{ row.id }}</strong>
          <span>用户 #{{ row.memberId }}</span>
        </div>
      </template>
      <template #amount="{ row }"><strong class="cashwithdraw-amount">¥{{ Number(row.amount || 0).toFixed(2) }}</strong></template>
      <template #status="{ row }"><cy-status-tag :label="statusText(row.status)" :tone="statusTone(row.status)" /></template>
      <template #actions="{ row }">
        <el-button size="mini" type="text" icon="el-icon-refresh" @click="handleRequery(row)"
          v-hasPermi="['business:cashwithdraw:manage']" v-if="canRequery(row)">重查</el-button>
        <el-button size="mini" type="text" icon="el-icon-money" @click="handleManual(row)"
          v-hasPermi="['business:cashwithdraw:manage']" v-if="row.status === 3">登记回补</el-button>
        <el-button size="mini" type="text" icon="el-icon-check" @click="handleManual(row)"
          v-hasPermi="['business:cashwithdraw:manage']" v-if="row.status === 4">复核回补</el-button>
      </template>
    </cy-data-table>

    <cy-confirm-action
      :visible.sync="manualOpen"
      :title="manualForm.status === 4 ? '复核人工回补' : '登记人工回补'"
      :impact="manualImpact"
      severity="danger"
      :confirm-text="manualForm.status === 4 ? '复核通过并退回余额' : '提交另一人复核'"
      reason-label="处理说明"
      reason-placeholder="填写人工核实或补单说明"
      :loading="manualSaving"
      @confirm="submitManual"
    />
  </div>
</template>

<script>
import { listCashwithdraw, requeryCashwithdraw, manualRefundCashwithdraw } from "@/api/business/cashwithdraw"
import CyConfirmAction from '@/components/CyConfirmAction'
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyStatusTag from '@/components/CyStatusTag'

const createQueryParams = () => ({ pageNum: 1, pageSize: 10, memberId: null, status: null })

export default {
  name: "Cashwithdraw",
  components: { CyConfirmAction, CyDataTable, CyFilterBar, CyPageHeader, CyStatusTag },
  data() {
    return {
      loading: false,
      total: 0,
      list: [],
      updatedAt: '',
      manualOpen: false,
      manualSaving: false,
      manualForm: {},
      queryForm: createQueryParams(),
      queryParams: createQueryParams(),
      listRequestId: 0,
      tableColumns: [
        { key: 'withdrawal', label: '提现单', minWidth: 150, locked: true },
        { key: 'amount', label: '金额', width: 110, align: 'right' },
        { key: 'status', label: '处理状态', width: 132 },
        { key: 'wechatBatchStatus', label: '微信批次状态', width: 128, prop: 'wechatBatchStatus' },
        { key: 'transferBatchId', label: '微信批次号', minWidth: 160, prop: 'transferBatchId', showOverflowTooltip: true },
        { key: 'failReason', label: '失败原因', minWidth: 180, prop: 'failReason', showOverflowTooltip: true },
        { key: 'submitTime', label: '提交时间', width: 158, prop: 'submitTime' },
        { key: 'lastQueryTime', label: '最后查询', width: 158, prop: 'lastQueryTime' },
        { key: 'manualOperator', label: '处理人', width: 100, prop: 'manualOperator' },
        { key: 'manualVerifier', label: '复核人', width: 100, prop: 'manualVerifier' },
        { key: 'actions', label: '操作', width: 170, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.memberId && { key: 'memberId', label: '用户 ID', value: this.queryParams.memberId },
        this.queryParams.status !== null && this.queryParams.status !== undefined && { key: 'status', label: '处理状态', value: this.statusText(this.queryParams.status) }
      ].filter(Boolean)
    },
    manualImpact() {
      if (!this.manualForm.id) return ''
      const action = this.manualForm.status === 4
        ? '资金事实校验通过且由另一人复核后，才会退回用户余额；结果未知时继续核查。'
        : '资金事实校验通过后转入另一名财务复核，当前操作不会直接退回余额。'
      return `提现单 #${this.manualForm.id} / 用户 ${this.manualForm.memberId} / ¥${this.manualForm.amount}。${action}`
    }
  },
  created() {
    this.ensureCanonicalRoute()
  },
  watch: {
    '$route'() {
      this.ensureCanonicalRoute()
    }
  },
  methods: {
    ensureCanonicalRoute() {
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/finance/withdraw/wechat') {
        this.$router.replace({ name: 'financeWechatWithdraw', query: Object.assign({}, this.$route.query) })
        return
      }
      this.getList()
    },
    getList() {
      const requestId = ++this.listRequestId
      const requestParams = Object.assign({}, this.queryParams)
      this.loading = true
      return listCashwithdraw(requestParams)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.list = response.rows || []
          this.total = response.total || 0
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.$modal.msgError('提现队列读取失败，请检查网络后重试。')
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    statusText(s) {
      return { 0: "处理中", 1: "成功", 2: "失败已退款", 3: "待人工核查", 4: "人工回补待复核", 5: "未实际扣款" }[s] || "-"
    },
    statusTone(s) {
      return { 0: 'warning', 1: 'success', 2: 'neutral', 3: 'danger', 4: 'warning' }[s] || 'neutral'
    },
    handleQuery() {
      this.queryParams = Object.assign(createQueryParams(), {
        pageSize: this.queryParams.pageSize,
        memberId: this.queryForm.memberId || null,
        status: this.queryForm.status
      })
      return this.getList()
    },
    resetQuery() {
      this.queryForm = createQueryParams()
      this.queryParams = createQueryParams()
      return this.getList()
    },
    clearFilter(key) {
      this.queryForm[key] = null
      this.queryParams[key] = null
      this.queryParams.pageNum = 1
      return this.getList()
    },
    handlePagination({ page, limit }) {
      this.queryParams.pageNum = page
      this.queryParams.pageSize = limit
      return this.getList()
    },
    showManualQueue() {
      this.queryForm.status = 3
      this.queryParams.status = 3
      this.queryParams.pageNum = 1
      return this.getList()
    },
    canRequery(row) {
      return !!row && [0, 3, 4].includes(row.status)
    },
    handleRequery(row) {
      if (!this.canRequery(row)) return
      return requeryCashwithdraw(row.id).then(() => {
        this.$modal.msgSuccess("已重新查询微信状态，请按资金事实继续核查")
        this.getList()
      })
    },
    handleManual(row) {
      this.manualForm = { id: row.id, memberId: row.memberId, amount: row.amount, status: row.status }
      this.manualOpen = true
    },
    submitManual(reason) {
      this.manualSaving = true
      return manualRefundCashwithdraw({ id: this.manualForm.id, status: this.manualForm.status, manualNote: reason })
        .then(response => {
          this.manualOpen = false
          this.$modal.msgSuccess(response.msg)
          this.getList()
        })
        .catch(() => {})
        .finally(() => { this.manualSaving = false })
    }
  }
}
</script>

<style lang="scss" scoped>
.cashwithdraw-filter { margin-bottom: 16px; }
.cashwithdraw-object strong, .cashwithdraw-object span { display: block; }
.cashwithdraw-object strong { color: var(--cy-color-text-primary); }
.cashwithdraw-object span { margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); font-size: 12px; }
.cashwithdraw-amount { color: var(--cy-color-text-primary); font-variant-numeric: tabular-nums; }
</style>

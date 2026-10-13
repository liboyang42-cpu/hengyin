<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="资金与售后"
      title="订单查询"
      description="定位订单、查看订单状态与售后事实，并记录平台备注。"
    >
      <el-button v-if="canExport" icon="el-icon-download" @click="handleExport">导出当前筛选</el-button>
    </cy-page-header>

    <section class="cy-panel order-filter">
      <div class="cy-panel__heading">
        <div>
          <h2>订单队列</h2>
          <p>以订单号、状态和用户定位；敏感收货信息不在本页展示。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="74px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="订单状态" prop="status">
            <el-select v-model="queryParams.status" clearable placeholder="全部状态">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="订单编号" prop="orderSn">
            <el-input v-model="queryParams.orderSn" placeholder="精确订单号" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <template #more>
            <el-form-item label="用户 ID" prop="memberId">
              <el-input v-model="queryParams.memberId" placeholder="下单用户" clearable @keyup.enter.native="handleQuery" />
            </el-form-item>
          </template>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="订单列表暂时无法读取"
      description="读取失败不会伪装成空订单；请检查权限或网络后重试。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <cy-data-table
      v-else
      :data="orderList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前筛选下没有订单"
      @pagination="getList"
      @refresh="getList"
    >
      <template slot="order" slot-scope="scope">
        <cy-entity-link
          v-if="canQuery"
          :name="scope.row.orderSn || `订单 #${scope.row.id}`"
          entity-type="ORDER"
          type-label="订单"
          :entity-id="scope.row.id"
          :meta="`下单 ${scope.row.createTime || '未记录'}`"
          @open="goDetail(scope.row)"
        />
        <div v-else class="order-identity">
          <span>{{ scope.row.orderSn || `订单 #${scope.row.id}` }}</span>
          <small>下单 {{ scope.row.createTime || '未记录' }}</small>
        </div>
      </template>
      <template slot="member" slot-scope="scope">
        <div class="table-stack">
          <span>用户 #{{ scope.row.memberId || '—' }}</span>
          <small>{{ scope.row.nickname || '未记录昵称' }}</small>
        </div>
      </template>
      <template slot="amount" slot-scope="scope">
        <div class="table-stack table-stack--amount">
          <span>实付 ¥{{ money(scope.row.payAmount) }}</span>
          <small>合计 ¥{{ money(scope.row.orderTotalAmount) }}</small>
        </div>
      </template>
      <template slot="status" slot-scope="scope">
        <div class="table-stack">
          <cy-status-tag :label="statusLabel(scope.row.status)" :tone="statusTone(scope.row.status)" />
          <cy-status-tag v-if="hasAftersale(scope.row.aftersaleStatus)" :label="aftersaleLabel(scope.row.aftersaleStatus)" :tone="aftersaleTone(scope.row.aftersaleStatus)" />
        </div>
      </template>
      <template slot="note" slot-scope="scope">
        <div class="order-note">
          <span>{{ scope.row.merchantNote || '暂无平台备注' }}</span>
          <small v-if="scope.row.orderNote">用户：{{ scope.row.orderNote }}</small>
        </div>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button v-if="canQuery" size="mini" type="text" @click="goDetail(scope.row)">详情</el-button>
        <el-button v-if="canEditNote" size="mini" type="text" @click="openNote(scope.row)">记录备注</el-button>
      </template>
    </cy-data-table>

    <el-dialog title="平台备注" :visible.sync="noteOpen" width="520px" append-to-body>
      <el-form label-width="78px">
        <el-form-item label="订单">
          <cy-copy-value :value="noteForm.orderSn" label="订单编号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="noteForm.merchantNote" type="textarea" :rows="4" placeholder="记录处理结论或后续跟进" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="noteSubmitting" @click="submitNote">保存备注</el-button>
        <el-button :disabled="noteSubmitting" @click="noteOpen = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listOrder, updateOrder } from '@/api/business/order'
import auth from '@/plugins/auth'
import CyCopyValue from '@/components/CyCopyValue'
import CyDataTable from '@/components/CyDataTable'
import CyEntityLink from '@/components/CyEntityLink'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const STATUS_OPTIONS = [
  { value: 0, label: '待付款', tone: 'neutral' },
  { value: 1, label: '待发货', tone: 'warning' },
  { value: 2, label: '已发货', tone: 'primary' },
  { value: 3, label: '待评价', tone: 'primary' },
  { value: 4, label: '已完成', tone: 'success' },
  { value: 5, label: '已关闭', tone: 'neutral' },
  { value: 6, label: '无效订单', tone: 'danger' }
]

const AFTERSALE_OPTIONS = {
  2: { label: '售后处理中', tone: 'warning' },
  3: { label: '退款中', tone: 'danger' },
  4: { label: '退款成功', tone: 'success' }
}

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

function orderStatus(value) {
  const parsed = Number(firstQueryValue(value))
  return STATUS_OPTIONS.some(item => item.value === parsed) ? parsed : null
}

function createQueryParams(query = {}) {
  return {
    pageNum: positiveInteger(query.pageNum, 1),
    pageSize: positiveInteger(query.pageSize, 10),
    status: orderStatus(query.status),
    orderSn: optionalQueryValue(query.orderSn),
    memberId: optionalQueryValue(query.memberId)
  }
}

export default {
  name: 'FinanceOrder',
  components: { CyCopyValue, CyDataTable, CyEntityLink, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      listError: false,
      listRequestId: 0,
      total: 0,
      orderList: [],
      queryParams: createQueryParams(),
      noteOpen: false,
      noteSubmitting: false,
      noteForm: { id: null, orderSn: '', merchantNote: '' },
      statusOptions: STATUS_OPTIONS,
      tableColumns: [
        { key: 'order', label: '订单', minWidth: 220 },
        { key: 'member', label: '下单用户', minWidth: 150 },
        { key: 'amount', label: '金额', minWidth: 145 },
        { key: 'status', label: '履约状态', minWidth: 150 },
        { key: 'note', label: '备注', minWidth: 240, showOverflowTooltip: true },
        { key: 'actions', label: '操作', width: 150, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    canQuery() {
      return auth.hasPermi('business:order:query')
    },
    canExport() {
      return auth.hasPermi('business:order:export')
    },
    canEditNote() {
      return auth.hasPermi('business:order:edit')
    },
    activeFilters() {
      const filters = []
      if (this.queryParams.status !== null) filters.push({ key: 'status', label: '状态', value: this.statusLabel(this.queryParams.status) })
      if (this.queryParams.orderSn) filters.push({ key: 'orderSn', label: '订单号', value: this.queryParams.orderSn })
      if (this.queryParams.memberId) filters.push({ key: 'memberId', label: '用户', value: this.queryParams.memberId })
      return filters
    }
  },
  created() {
    this.loadRoute(this.$route.query)
  },
  watch: {
    '$route.query'(query) {
      this.loadRoute(query)
    }
  },
  methods: {
    loadRoute(query = {}) {
      if (!auth.hasPermi('business:order:list')) {
        this.$router.replace('/401')
        return
      }
      this.queryParams = createQueryParams(query)
      this.getList()
    },
    getList() {
      const requestId = ++this.listRequestId
      this.loading = true
      this.listError = false
      return listOrder(Object.assign({}, this.queryParams)).then(response => {
        if (requestId !== this.listRequestId) return
        this.orderList = response.rows || []
        this.total = response.total || 0
      }).catch(() => {
        if (requestId !== this.listRequestId) return
        this.orderList = []
        this.total = 0
        this.listError = true
      }).finally(() => {
        if (requestId === this.listRequestId) this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.queryParams = createQueryParams()
      this.getList()
    },
    clearFilter(key) {
      this.queryParams[key] = null
      this.queryParams.pageNum = 1
      this.getList()
    },
    statusLabel(status) {
      const item = STATUS_OPTIONS.find(option => option.value === Number(status))
      return item ? item.label : '状态未记录'
    },
    statusTone(status) {
      const item = STATUS_OPTIONS.find(option => option.value === Number(status))
      return item ? item.tone : 'neutral'
    },
    hasAftersale(status) {
      return Boolean(AFTERSALE_OPTIONS[Number(status)])
    },
    aftersaleLabel(status) {
      const item = AFTERSALE_OPTIONS[Number(status)]
      return item ? item.label : '无售后'
    },
    aftersaleTone(status) {
      const item = AFTERSALE_OPTIONS[Number(status)]
      return item ? item.tone : 'neutral'
    },
    money(value) {
      return Number(value || 0).toFixed(2)
    },
    goDetail(row) {
      if (!auth.hasPermi('business:order:query')) return
      this.$router.push({
        name: 'financeOrderDetail',
        params: { id: String(row.id) },
        query: this.listRouteQuery()
      })
    },
    listRouteQuery() {
      return Object.keys(this.queryParams).reduce((query, key) => {
        const value = this.queryParams[key]
        if (value !== null && value !== undefined && value !== '') query[key] = String(value)
        return query
      }, {})
    },
    openNote(row) {
      this.noteForm = { id: row.id, orderSn: row.orderSn || String(row.id), merchantNote: row.merchantNote || '' }
      this.noteOpen = true
    },
    submitNote() {
      if (!this.noteForm.id) return Promise.resolve()
      this.noteSubmitting = true
      return updateOrder({ id: this.noteForm.id, merchantNote: this.noteForm.merchantNote.trim() }).then(() => {
        this.$modal.msgSuccess('平台备注已保存')
        this.noteOpen = false
        this.getList()
      }).catch(() => {
        this.$modal.msgError('平台备注保存失败，请检查权限或网络后重试')
      }).finally(() => {
        this.noteSubmitting = false
      })
    },
    handleExport() {
      this.download('business/order/export', Object.assign({}, this.queryParams), `order_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style lang="scss" scoped>
.order-filter { margin-bottom: 20px; }
.table-stack span, .table-stack small, .order-note span, .order-note small, .order-identity span, .order-identity small { display: block; }
.table-stack small, .order-note small, .order-identity small { margin-top: 4px; color: var(--cy-color-text-tertiary); }
.table-stack--amount span { color: var(--cy-color-text-primary); font-weight: 700; }
.order-note { max-width: 100%; }
.order-note span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>

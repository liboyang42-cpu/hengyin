<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="资金与结算"
      title="资金流水"
      description="按用户、原始事件类型与收支方向读取余额台账。这里用于调证，不能新增、修改或删除余额事实。"
    />

    <section class="cy-panel finance-ledger__filters" aria-label="资金流水筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>流水定位</h2>
          <p>流水以变更原因和原始事件类型共同调证；“收入/支出”描述余额变化方向，不代表人工可改的状态。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="72px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="用户 ID" prop="memberId">
            <el-input v-model="queryForm.memberId" placeholder="按用户定位" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="业务事件" prop="eventType">
            <el-input v-model="queryForm.eventType" placeholder="输入原始 event_type" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="收支方向" prop="changeType">
            <el-select v-model="queryForm.changeType" placeholder="全部方向" clearable style="width: 132px">
              <el-option label="收入" :value="1" />
              <el-option label="支出" :value="2" />
            </el-select>
          </el-form-item>
          <template slot="more">
            <el-form-item label="事件 ID" prop="eventId">
              <el-input v-model="queryForm.eventId" placeholder="报名、提现等事件 ID" clearable @keyup.enter.native="handleQuery" />
            </el-form-item>
          </template>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="ledgerError"
      kind="error"
      title="资金流水暂时无法读取"
      description="读取失败不会被显示成空流水；请在权限或网络恢复后重试。"
      retry-text="重新读取"
      :retrying="ledgerLoading"
      @retry="getList"
    />
    <cy-data-table
      v-else
      :data="ledgerList"
      :columns="tableColumns"
      :loading="ledgerLoading"
      :total="total"
      :page="queryParams.pageNum"
      :limit="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前筛选下没有余额流水"
      @pagination="handlePagination"
      @refresh="getList"
    >
      <template slot="ledger" slot-scope="scope">
        <button type="button" class="finance-ledger__record" @click="openRecord(scope.row)">
          <strong>{{ scope.row.changeReason || `余额流水 #${scope.row.id}` }}</strong>
          <span>{{ changeTypeLabel(scope.row.changeType) }} · {{ formatDate(scope.row.createTime) }}</span>
        </button>
      </template>
      <template slot="member" slot-scope="scope">
        <span>用户 #{{ scope.row.memberId || '—' }}</span>
      </template>
      <template slot="amount" slot-scope="scope">
        <strong :class="['finance-ledger__amount', `finance-ledger__amount--${changeTypeTone(scope.row.changeType)}`]">{{ formatAmount(scope.row) }}</strong>
      </template>
      <template slot="balance" slot-scope="scope">
        <span>{{ money(scope.row.currentBalance) }} → {{ money(scope.row.afterBalance) }}</span>
      </template>
      <template slot="event" slot-scope="scope">
        <div class="finance-ledger__event">
          <span>{{ eventTypeLabel(scope.row.eventType) }}</span>
          <small>{{ scope.row.eventId ? `事件 #${scope.row.eventId}` : '未关联事件' }}</small>
        </div>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button size="mini" type="text" icon="el-icon-view" @click="openRecord(scope.row)">调证</el-button>
      </template>
    </cy-data-table>

    <el-drawer title="余额流水事实" :visible.sync="recordOpen" size="560px" append-to-body>
      <div v-if="selectedRecord" class="finance-ledger__drawer">
        <cy-entity-header
          eyebrow="余额流水"
          :title="selectedRecord.changeReason || `余额流水 #${selectedRecord.id}`"
          :facts="recordFacts"
        />
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="变化时间">{{ formatDate(selectedRecord.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="收支方向">{{ changeTypeLabel(selectedRecord.changeType) }}</el-descriptions-item>
          <el-descriptions-item label="变化金额">{{ formatAmount(selectedRecord) }}</el-descriptions-item>
          <el-descriptions-item label="余额变化">{{ money(selectedRecord.currentBalance) }} → {{ money(selectedRecord.afterBalance) }}</el-descriptions-item>
          <el-descriptions-item label="业务事件">{{ eventTypeLabel(selectedRecord.eventType) }}{{ selectedRecord.eventId ? ` #${selectedRecord.eventId}` : '' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { listMemberbalancedetail } from '@/api/business/memberbalancedetail'
import CyDataTable from '@/components/CyDataTable'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

const createQueryParams = () => ({ pageNum: 1, pageSize: 10, memberId: null, eventType: null, eventId: null, changeType: null })
const nullableText = value => value === '' || value === undefined || value === null ? null : value
const nullableNumber = value => {
  const normalized = nullableText(value)
  if (normalized === null) return null
  const number = Number(normalized)
  return Number.isFinite(number) ? number : null
}

export default {
  name: 'FinanceLedger',
  components: { CyDataTable, CyEntityHeader, CyFilterBar, CyPageHeader, CyResourceState },
  data() {
    return {
      ledgerLoading: false,
      ledgerError: false,
      ledgerRequestId: 0,
      ledgerList: [],
      total: 0,
      recordOpen: false,
      selectedRecord: null,
      queryForm: createQueryParams(),
      queryParams: createQueryParams(),
      tableColumns: [
        { key: 'ledger', label: '流水事实', minWidth: 220, locked: true },
        { key: 'member', label: '用户', width: 130 },
        { key: 'amount', label: '变化金额', width: 122, align: 'right' },
        { key: 'balance', label: '余额变化', minWidth: 170, align: 'right' },
        { key: 'event', label: '业务事件', minWidth: 180 },
        { key: 'actions', label: '调证', width: 100, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.memberId && { key: 'memberId', label: '用户 ID', value: this.queryParams.memberId },
        this.queryParams.eventType !== null && { key: 'eventType', label: '事件类型', value: this.eventTypeLabel(this.queryParams.eventType) },
        this.queryParams.eventId && { key: 'eventId', label: '事件 ID', value: this.queryParams.eventId },
        this.queryParams.changeType !== null && { key: 'changeType', label: '收支方向', value: this.changeTypeLabel(this.queryParams.changeType) }
      ].filter(Boolean)
    },
    recordFacts() {
      if (!this.selectedRecord) return []
      return [
        { label: '用户', value: this.selectedRecord.memberId ? `#${this.selectedRecord.memberId}` : '未记录' },
        { label: '收支方向', value: this.changeTypeLabel(this.selectedRecord.changeType) },
        { label: '变化金额', value: this.formatAmount(this.selectedRecord) },
        { label: '业务事件', value: this.eventTypeLabel(this.selectedRecord.eventType) }
      ]
    }
  },
  created() {
    this.hydrateFromRoute(this.$route.query)
    this.getList()
  },
  beforeRouteUpdate(to, from, next) {
    let request = Promise.resolve()
    if (this.routeSignature(to.query) !== this.routeSignature(from.query)) {
      this.hydrateFromRoute(to.query)
      request = this.getList()
    }
    next()
    return request
  },
  methods: {
    routeSignature(query) {
      return ['memberId', 'eventType', 'eventId', 'changeType'].map(key => query[key] || '').join('|')
    },
    hydrateFromRoute(query) {
      const nextQuery = createQueryParams()
      nextQuery.memberId = nullableText(query.memberId)
      nextQuery.eventType = nullableNumber(query.eventType)
      nextQuery.eventId = nullableText(query.eventId)
      nextQuery.changeType = nullableNumber(query.changeType)
      this.queryForm = Object.assign({}, nextQuery)
      this.queryParams = Object.assign({}, nextQuery)
    },
    getList() {
      const requestId = ++this.ledgerRequestId
      const requestParams = Object.assign({}, this.queryParams)
      this.ledgerLoading = true
      this.ledgerError = false
      return listMemberbalancedetail(requestParams).then(response => {
        if (requestId !== this.ledgerRequestId) return
        this.ledgerList = response.rows || []
        this.total = response.total || 0
      }).catch(() => {
        if (requestId !== this.ledgerRequestId) return
        this.ledgerList = []
        this.total = 0
        this.ledgerError = true
      }).finally(() => {
        if (requestId === this.ledgerRequestId) this.ledgerLoading = false
      })
    },
    handleQuery() {
      this.queryParams = {
        pageNum: 1,
        pageSize: this.queryParams.pageSize,
        memberId: nullableText(this.queryForm.memberId),
        eventType: nullableNumber(this.queryForm.eventType),
        eventId: nullableText(this.queryForm.eventId),
        changeType: this.queryForm.changeType
      }
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
    openRecord(row) {
      this.selectedRecord = row
      this.recordOpen = true
    },
    changeTypeLabel(value) {
      return { 1: '收入', 2: '支出', '1': '收入', '2': '支出' }[value] || '方向未记录'
    },
    changeTypeTone(value) {
      return { 1: 'income', 2: 'expense', '1': 'income', '2': 'expense' }[value] || 'neutral'
    },
    eventTypeLabel(value) {
      return value === null || value === undefined || value === '' ? '未关联事件' : `事件类型 #${value}`
    },
    money(value) {
      if (value === null || value === undefined || value === '') return '未记录'
      const amount = Number(value)
      return Number.isFinite(amount) ? `¥${amount.toFixed(2)}` : '未记录'
    },
    formatAmount(row) {
      if (row.changeBalance === null || row.changeBalance === undefined || row.changeBalance === '') return '未记录'
      const amount = Number(row.changeBalance)
      if (!Number.isFinite(amount)) return '未记录'
      const prefix = row.changeType === 1 || row.changeType === '1' ? '+' : row.changeType === 2 || row.changeType === '2' ? '-' : ''
      return `${prefix}¥${Math.abs(amount).toFixed(2)}`
    },
    formatDate(value) {
      return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '—'
    }
  }
}
</script>

<style lang="scss" scoped>
.finance-ledger__filters { margin-bottom: 20px; }
.finance-ledger__record { display: block; max-width: 100%; padding: 0; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.finance-ledger__record strong, .finance-ledger__record span, .finance-ledger__event span, .finance-ledger__event small { display: block; }
.finance-ledger__record strong { overflow: hidden; color: var(--cy-color-text-primary); font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.finance-ledger__record span, .finance-ledger__event small { margin-top: 4px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.finance-ledger__record:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 3px; }
.finance-ledger__amount { font-variant-numeric: tabular-nums; }
.finance-ledger__amount--income { color: var(--cy-color-status-success); }
.finance-ledger__amount--expense { color: var(--cy-color-status-danger); }
.finance-ledger__amount--neutral { color: var(--cy-color-text-primary); }
.finance-ledger__drawer { min-height: 280px; padding: 0 24px var(--cy-space-7); }
</style>

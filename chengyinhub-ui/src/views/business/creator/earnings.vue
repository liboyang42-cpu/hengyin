<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="审核与风控"
      title="创作者收益"
      description="模板与工坊作品的创作者收益台账：被领用、被安装、有效游玩发积分；售价分成只记录应得意向，真钱开关打开前不付任何钱。"
    >
      <el-button icon="el-icon-refresh" @click="reload">刷新</el-button>
    </cy-page-header>

    <section class="cy-panel">
      <div class="cy-panel__heading">
        <div>
          <h2>当前规则</h2>
          <p v-if="rules">
            领用 {{ rules.adoptPoints }} 分 · 安装 {{ rules.installPoints }} 分 · 每次有效游玩 {{ rules.playPoints }} 分；
            每位创作者每天最多 {{ rules.dailyPlayCap }} 次游玩计分，同一玩家同一作品每天最多 {{ rules.dailyPlayPerPlayer }} 次；
            售价分成 {{ rules.saleShareBps / 100 }}%（{{ rules.moneyOn ? '真钱已开：记为待打款，本页不打款' : '真钱关闭：只记录，不付款' }}）。
          </p>
          <p v-else>规则读取中或暂不可读。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="72px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="会员 ID" prop="creatorId">
            <el-input v-model="queryParams.creatorId" placeholder="创作者会员 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="日期" prop="range">
            <el-date-picker v-model="queryParams.range" type="daterange" value-format="yyyy-MM-dd" range-separator="至"
              start-placeholder="开始" end-placeholder="结束" />
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="创作者收益暂时无法读取"
      description="请稍后重试；读取失败不会改动任何收益。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <cy-data-table
      v-else
      :data="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前筛选下还没有创作者收益"
      @pagination="getList"
      @refresh="getList"
    >
      <template slot="creator" slot-scope="scope">
        <div class="table-stack">
          <strong>{{ scope.row.nickname || '未命名会员' }}</strong>
          <small>会员 #{{ scope.row.creatorId }}</small>
        </div>
      </template>
      <template slot="plays" slot-scope="scope">
        <span>{{ scope.row.plays || 0 }}（超上限 {{ scope.row.cappedPlays || 0 }}）</span>
      </template>
      <template slot="held" slot-scope="scope">
        <span>{{ yuan(scope.row.heldCreatorMinor) }}</span>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button size="mini" type="text" @click="openLedger(scope.row)">明细</el-button>
      </template>
    </cy-data-table>

    <section class="cy-panel accrue-panel">
      <div class="cy-panel__heading">
        <div>
          <h2>按天重算</h2>
          <p>系统每小时自动记账。这里可补记某一天：只追加缺失的事件，已记过的不会重复发积分。</p>
        </div>
      </div>
      <div class="accrue-row">
        <el-date-picker v-model="accrueDay" type="date" value-format="yyyy-MM-dd" placeholder="选择日期" size="small" />
        <el-button v-hasPermi="['business:creator:review']" size="small" type="primary" :disabled="!accrueDay || accruing"
          :loading="accruing" @click="accrue">补记这一天</el-button>
        <span v-if="accrueResult" class="accrue-result">{{ accrueResult }}</span>
      </div>
    </section>

    <el-dialog :title="ledgerTitle" :visible.sync="ledgerOpen" width="880px" append-to-body>
      <el-table v-loading="ledgerLoading" :data="ledgerRows" size="small">
        <el-table-column label="类型" width="110">
          <template slot-scope="scope">{{ typeLabel(scope.row.eventType) }}</template>
        </el-table-column>
        <el-table-column label="作品" min-width="180" show-overflow-tooltip>
          <template slot-scope="scope">{{ scope.row.itemTitle || scope.row.itemRef }}</template>
        </el-table-column>
        <el-table-column label="积分" width="80">
          <template slot-scope="scope">{{ scope.row.capped ? '超上限' : scope.row.points }}</template>
        </el-table-column>
        <el-table-column label="分成意向" width="140">
          <template slot-scope="scope">{{ scope.row.creatorMinor ? yuan(scope.row.creatorMinor) + ' ' + (scope.row.currency || '') : '—' }}</template>
        </el-table-column>
        <el-table-column label="付款状态" width="130">
          <template slot-scope="scope">{{ moneyLabel(scope.row.moneyState) }}</template>
        </el-table-column>
        <el-table-column label="发生时间" prop="occurTime" min-width="160" />
      </el-table>
      <pagination v-show="ledgerTotal > 0" :total="ledgerTotal" :page.sync="ledgerQuery.pageNum" :limit.sync="ledgerQuery.pageSize"
        @pagination="loadLedger" />
    </el-dialog>
  </div>
</template>

<script>
import { accrueCreatorEarnings, getCreatorEarningsLedger, getCreatorEarningsReport, getCreatorEarningsRules } from '@/api/business/creatorEarnings'
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

const emptyQuery = () => ({ pageNum: 1, pageSize: 10, creatorId: null, range: null })
const TYPE_LABELS = { ADOPT: '模板被领用', INSTALL: '作品被安装', PLAY: '有效游玩', SALE: '售出', SALE_REVERSAL: '退款冲正' }
const MONEY_LABELS = { NONE: '—', HELD_MONEY_OFF: '未开放，不付款', PENDING_PAYOUT: '待打款（未付）' }

export default {
  name: 'CreatorEarnings',
  components: { CyDataTable, CyFilterBar, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      loadError: false,
      requestId: 0,
      rows: [],
      total: 0,
      rules: null,
      queryParams: emptyQuery(),
      accrueDay: null,
      accruing: false,
      accrueResult: '',
      ledgerOpen: false,
      ledgerLoading: false,
      ledgerTarget: null,
      ledgerRows: [],
      ledgerTotal: 0,
      ledgerQuery: { pageNum: 1, pageSize: 10 },
      columns: [
        { key: 'creator', label: '创作者', minWidth: 180 },
        { key: 'items', label: '作品数', width: 90, prop: 'items' },
        { key: 'uses', label: '领用/安装', width: 100, prop: 'uses' },
        { key: 'plays', label: '有效游玩', minWidth: 140 },
        { key: 'points', label: '积分', width: 90, prop: 'points' },
        { key: 'sales', label: '售出', width: 80, prop: 'sales' },
        { key: 'held', label: '分成意向（元）', width: 140 },
        { key: 'lastAt', label: '最近发生', minWidth: 160, prop: 'lastAt' },
        { key: 'actions', label: '操作', width: 90, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.creatorId && { key: 'creatorId', label: '会员 ID', value: this.queryParams.creatorId },
        this.queryParams.range && { key: 'range', label: '日期', value: this.queryParams.range.join(' 至 ') }
      ].filter(Boolean)
    },
    ledgerTitle() {
      return this.ledgerTarget ? `收益明细 · 会员 #${this.ledgerTarget.creatorId}` : '收益明细'
    }
  },
  created() {
    this.reload()
  },
  methods: {
    reload() {
      getCreatorEarningsRules().then(res => { this.rules = res.data || null }).catch(() => { this.rules = null })
      return this.getList()
    },
    getList() {
      const id = ++this.requestId
      const q = { pageNum: this.queryParams.pageNum, pageSize: this.queryParams.pageSize, creatorId: this.queryParams.creatorId || undefined }
      if (this.queryParams.range) { q.from = this.queryParams.range[0]; q.to = this.queryParams.range[1] }
      this.loading = true
      this.loadError = false
      return getCreatorEarningsReport(q).then(res => {
        if (id !== this.requestId) return
        this.rows = res.rows || []
        this.total = res.total || 0
      }).catch(() => {
        if (id === this.requestId) this.loadError = true
      }).finally(() => {
        if (id === this.requestId) this.loading = false
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
    openLedger(row) {
      this.ledgerTarget = row
      this.ledgerQuery = { pageNum: 1, pageSize: 10 }
      this.ledgerOpen = true
      this.loadLedger()
    },
    loadLedger() {
      if (!this.ledgerTarget) return
      this.ledgerLoading = true
      return getCreatorEarningsLedger(Object.assign({ creatorId: this.ledgerTarget.creatorId }, this.ledgerQuery)).then(res => {
        this.ledgerRows = res.rows || []
        this.ledgerTotal = res.total || 0
      }).finally(() => {
        this.ledgerLoading = false
      })
    },
    accrue() {
      if (!this.accrueDay || this.accruing) return
      this.accruing = true
      this.accrueResult = ''
      return accrueCreatorEarnings(this.accrueDay).then(res => {
        const d = res.data || {}
        const added = ['adopt', 'install', 'templatePlay', 'workshopPlay', 'sale', 'reversal'].reduce((n, k) => n + (d[k] || 0), 0)
        this.accrueResult = `${d.day}：新增 ${added} 条，同步到创作者明细 ${d.mirrored || 0} 条`
        return this.getList()
      }).finally(() => {
        this.accruing = false
      })
    },
    yuan(minor) {
      const n = Number(minor || 0)
      return (n / 100).toFixed(2)
    },
    typeLabel(type) {
      return TYPE_LABELS[type] || type
    },
    moneyLabel(state) {
      return MONEY_LABELS[state] || state
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
.table-stack small { color: var(--cy-color-text-tertiary); }
.accrue-panel { margin-top: 20px; }
.accrue-row { display: flex; gap: 12px; align-items: center; padding: 0 var(--cy-space-page) 20px; }
.accrue-result { color: var(--cy-color-text-secondary); font-size: 13px; }
</style>

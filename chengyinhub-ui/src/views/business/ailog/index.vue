<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="运行调证"
      title="AI 调用审计"
      description="用 traceId、用户和场景定位一次模型调用；输入与输出摘要仅在详情中按需查看。"
      :updated-at="updatedAt"
    />

    <section class="cy-panel ailog-filter" aria-label="AI 调用审计筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>调用定位</h2>
          <p>优先用 traceId 精确回溯，再按用户或场景缩小范围。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="62px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="traceId" prop="traceId">
            <el-input v-model="queryForm.traceId" placeholder="精确匹配" clearable style="width: 260px" @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="用户" prop="memberId">
            <el-input v-model="queryForm.memberId" placeholder="用户 ID" clearable style="width: 130px" @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="场景" prop="bizType">
            <el-select v-model="queryForm.bizType" placeholder="全部场景" clearable style="width: 150px">
              <el-option label="商家组题" value="merchant_plan" />
              <el-option label="主题草稿" value="theme_draft" />
              <el-option label="节点生成" value="node_generate" />
              <el-option label="安全预检" value="safety_precheck" />
              <el-option label="NPC 聊天" value="npc_chat" />
            </el-select>
          </el-form-item>
          <template #more>
            <el-form-item label="安全" prop="safetyFlag">
              <el-select v-model="queryForm.safetyFlag" placeholder="全部状态" clearable style="width: 140px">
                <el-option label="正常" :value="0" />
                <el-option label="输入被拦" :value="1" />
                <el-option label="输出被拦" :value="2" />
              </el-select>
            </el-form-item>
            <el-form-item label="调用时间">
              <el-date-picker
                v-model="dateRange"
                style="width: 240px"
                value-format="yyyy-MM-dd"
                type="daterange"
                range-separator="-"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
              />
            </el-form-item>
          </template>
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
      empty-text="当前筛选下没有 AI 调用记录"
      default-density="comfortable"
      @refresh="getList"
      @pagination="handlePagination"
    >
      <template #record="{ row }">
        <div class="ailog-object">
          <strong>调用 #{{ row.id }}</strong>
          <span>用户 #{{ row.memberId }} · {{ row.role || '未标注角色' }}</span>
        </div>
      </template>
      <template #tokens="{ row }">
        <div class="ailog-tokens">
          <strong>{{ tokenText(row.tokenEst) }}</strong>
          <span>入 {{ tokenText(row.tokenIn) }} / 出 {{ tokenText(row.tokenOut) }}</span>
        </div>
      </template>
      <template #safety="{ row }"><cy-status-tag :label="safetyText(row.safetyFlag)" :tone="safetyTone(row.safetyFlag)" /></template>
      <template #traceId="{ row }"><cy-copy-value :value="row.traceId" label="traceId" /></template>
      <template #actions="{ row }">
        <el-button size="mini" type="text" icon="el-icon-view" v-hasPermi="['business:ailog:query']" @click="handleRowClick(row)">查看详情</el-button>
      </template>
    </cy-data-table>

    <el-dialog title="AI 调用详情" :visible.sync="detailOpen" width="820px" append-to-body>
      <dl class="ailog-detail">
        <div class="ailog-detail__item">
          <dt>traceId</dt>
          <dd><cy-copy-value :value="detail.traceId" label="traceId" /></dd>
        </div>
        <div class="ailog-detail__item">
          <dt>用户 / 角色</dt>
          <dd>#{{ detail.memberId }} · {{ detail.role }}</dd>
        </div>
        <div class="ailog-detail__item">
          <dt>场景 / 模型</dt>
          <dd>{{ detail.bizType }} · {{ detail.provider }}</dd>
        </div>
        <div class="ailog-detail__item">
          <dt>耗时 / Token（估算）</dt>
          <dd>{{ detail.latencyMs }}ms · 入 {{ tokenText(detail.tokenIn) }} / 出 {{ tokenText(detail.tokenOut) }} / 合计 {{ tokenText(detail.tokenEst) }}</dd>
        </div>
        <div class="ailog-detail__item">
          <dt>安全</dt>
          <dd><cy-status-tag :label="safetyText(detail.safetyFlag)" :tone="safetyTone(detail.safetyFlag)" /></dd>
        </div>
        <div class="ailog-detail__item ailog-detail__item--wide">
          <dt>输入摘要</dt>
          <dd><pre class="brief">{{ detail.inputBrief || '-' }}</pre></dd>
        </div>
        <div class="ailog-detail__item ailog-detail__item--wide">
          <dt>输出摘要</dt>
          <dd><pre class="brief">{{ detail.outputBrief || '-' }}</pre></dd>
        </div>
        <div class="ailog-detail__item ailog-detail__item--wide">
          <dt>组题快照</dt>
          <dd>
            <div v-if="snapshotLoading">加载中...</div>
            <div v-else-if="snapshotError" class="ailog-detail__error" role="alert">{{ snapshotError }}</div>
            <div v-else-if="!snapshot">该 traceId 无组题快照（仅商家组题场景才有）</div>
            <div v-else class="ailog-snapshot">
              <p>想法：{{ snapshot.idea }}</p>
              <pre class="brief">条件 {{ snapshot.conditionsJson }}</pre>
              <pre class="brief">候选 {{ snapshot.candidatesJson }}</pre>
              <pre class="brief">选择 {{ snapshot.choiceJson }}</pre>
            </div>
          </dd>
        </div>
      </dl>
      <div slot="footer">
        <el-button @click="detailOpen = false">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listAiLog, getAiLogSnapshot } from "@/api/business/ailog"
import CyCopyValue from '@/components/CyCopyValue'
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyStatusTag from '@/components/CyStatusTag'

const createQueryParams = () => ({
  pageNum: 1,
  pageSize: 10,
  traceId: null,
  memberId: null,
  bizType: null,
  safetyFlag: null
})

export default {
  name: "RiskAiLog",
  components: { CyCopyValue, CyDataTable, CyFilterBar, CyPageHeader, CyStatusTag },
  data() {
    return {
      loading: false,
      total: 0,
      list: [],
      updatedAt: '',
      dateRange: [],
      queryDateRange: [],
      queryForm: createQueryParams(),
      queryParams: createQueryParams(),
      listRequestId: 0,
      detailOpen: false,
      detail: {},
      snapshot: null,
      snapshotError: '',
      snapshotLoading: false,
      snapshotRequestId: 0,
      tableColumns: [
        { key: 'record', label: '调用', minWidth: 146, locked: true },
        { key: 'bizType', label: '场景', width: 128, prop: 'bizType', showOverflowTooltip: true },
        { key: 'provider', label: '模型', width: 110, prop: 'provider', showOverflowTooltip: true },
        { key: 'latencyMs', label: '耗时', width: 96, prop: 'latencyMs', formatter: row => `${row.latencyMs || 0}ms` },
        { key: 'tokens', label: 'Token（估算）', width: 136 },
        { key: 'safety', label: '安全', width: 110 },
        { key: 'traceId', label: 'traceId', minWidth: 210, prop: 'traceId', showOverflowTooltip: true },
        { key: 'createTime', label: '调用时间', width: 160, prop: 'createTime' },
        { key: 'actions', label: '操作', width: 104, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      const bizType = {
        merchant_plan: '商家组题',
        theme_draft: '主题草稿',
        node_generate: '节点生成',
        safety_precheck: '安全预检',
        npc_chat: 'NPC 聊天'
      }
      const dateRange = this.queryDateRange || []
      return [
        this.queryParams.traceId && { key: 'traceId', label: 'traceId', value: this.queryParams.traceId },
        this.queryParams.memberId && { key: 'memberId', label: '用户 ID', value: this.queryParams.memberId },
        this.queryParams.bizType && { key: 'bizType', label: '场景', value: bizType[this.queryParams.bizType] || this.queryParams.bizType },
        this.queryParams.safetyFlag !== null && this.queryParams.safetyFlag !== undefined && { key: 'safetyFlag', label: '安全', value: this.safetyText(this.queryParams.safetyFlag) },
        dateRange[0] && dateRange[1] && { key: 'dateRange', label: '调用时间', value: `${dateRange[0]} 至 ${dateRange[1]}` }
      ].filter(Boolean)
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
    tokenText(value) {
      return value === null || value === undefined ? '未知' : String(value)
    },
    ensureCanonicalRoute() {
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/risk/ai-log') {
        this.$router.replace({ name: 'riskAiLog', query: Object.assign({}, this.$route.query) })
        return
      }
      this.getList()
    },
    getList() {
      const requestId = ++this.listRequestId
      const requestParams = Object.assign({}, this.queryParams, {
        params: { beginTime: this.queryDateRange[0], endTime: this.queryDateRange[1] }
      })
      this.loading = true
      return listAiLog(requestParams)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.list = response.rows || []
          this.total = response.total || 0
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.$modal.msgError('AI 调用审计读取失败，请检查网络后重试。')
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    handleQuery() {
      this.queryParams = Object.assign(createQueryParams(), {
        pageSize: this.queryParams.pageSize,
        traceId: this.queryForm.traceId || null,
        memberId: this.queryForm.memberId || null,
        bizType: this.queryForm.bizType || null,
        safetyFlag: this.queryForm.safetyFlag
      })
      this.queryDateRange = Array.isArray(this.dateRange) ? this.dateRange.slice(0, 2) : []
      return this.getList()
    },
    resetQuery() {
      this.dateRange = []
      this.queryDateRange = []
      this.queryForm = createQueryParams()
      this.queryParams = createQueryParams()
      return this.getList()
    },
    clearFilter(key) {
      if (key === 'dateRange') {
        this.dateRange = []
        this.queryDateRange = []
      } else {
        this.queryForm[key] = null
        this.queryParams[key] = null
      }
      this.queryParams.pageNum = 1
      return this.getList()
    },
    handlePagination({ page, limit }) {
      this.queryParams.pageNum = page
      this.queryParams.pageSize = limit
      return this.getList()
    },
    handleRowClick(row) {
      const requestId = ++this.snapshotRequestId
      this.detail = row
      this.snapshot = null
      this.snapshotError = ''
      this.snapshotLoading = false
      this.detailOpen = true
      if (!row.traceId) {
        return
      }
      this.snapshotLoading = true
      getAiLogSnapshot(row.traceId)
        .then(response => {
          if (requestId !== this.snapshotRequestId) return
          this.snapshot = response.data
        })
        .catch(() => {
          if (requestId !== this.snapshotRequestId) return
          this.snapshotError = '组题快照读取失败，请稍后重试。'
        })
        .finally(() => {
          if (requestId === this.snapshotRequestId) this.snapshotLoading = false
        })
    },
    safetyText(flag) {
      return { 0: "正常", 1: "输入被拦", 2: "输出被拦" }[flag] || "-"
    },
    safetyTone(flag) {
      return { 0: 'success', 1: 'danger', 2: 'warning' }[flag] || 'neutral'
    }
  }
}
</script>

<style lang="scss" scoped>
.ailog-filter { margin-bottom: 16px; }
.ailog-object strong, .ailog-object span, .ailog-tokens strong, .ailog-tokens span { display: block; }
.ailog-object strong, .ailog-tokens strong { color: var(--cy-color-text-primary); }
.ailog-object span, .ailog-tokens span { margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); font-size: 12px; }
.ailog-tokens strong { font-variant-numeric: tabular-nums; }
.ailog-detail { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin: 0; }
.ailog-detail__item { min-width: 0; }
.ailog-detail__item--wide { grid-column: 1 / -1; }
.ailog-detail dt { margin-bottom: var(--cy-space-0-5); color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 700; }
.ailog-detail dd { margin: 0; color: var(--cy-color-text-primary); line-height: 1.6; }
.ailog-detail__error { color: var(--cy-color-status-danger); }
.ailog-snapshot p { margin: 0 0 8px; }
.brief { margin: 0; max-height: 160px; overflow: auto; white-space: pre-wrap; word-break: break-all; background: var(--cy-color-bg-subtle); padding: 8px var(--cy-space-1-5); border-radius: var(--cy-radius-control); line-height: 1.5; }

@media (max-width: 720px) {
  .ailog-detail { grid-template-columns: 1fr; }
  .ailog-detail__item--wide { grid-column: auto; }
}
</style>

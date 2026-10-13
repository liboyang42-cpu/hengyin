<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="客户服务"
      title="合作客诉案件"
      description="按案件处理先行垫付、责任认定与扣划关闭；资金动作仍以服务端状态机和当前处理权限为准。"
    />

    <section class="cy-panel customer-case__filter" aria-label="客诉案件筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>案件定位</h2>
          <p>按主题或处理阶段查看当前客诉，不把主题结算冻结事实藏在资金动作之后。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="query" size="small" :inline="true" label-width="58px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="主题" prop="topicId">
            <el-input v-model="query.topicId" placeholder="输入主题 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="阶段" prop="status">
            <el-select v-model="query.status" placeholder="全部阶段" clearable style="width: 150px">
              <el-option label="受理中" :value="0" />
              <el-option label="已垫付" :value="1" />
              <el-option label="追偿中" :value="2" />
              <el-option label="已关闭" :value="3" />
            </el-select>
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="客诉案件暂时无法读取"
      description="读取失败不会伪装成空案件；请检查权限或网络后重新读取。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <cy-data-table
      v-else
      :data="list"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page="query.pageNum"
      :limit="query.pageSize"
      empty-text="当前筛选下没有客诉案件"
      default-density="comfortable"
      @refresh="getList"
      @pagination="handlePagination"
    >
      <template #case="{ row }">
        <div class="customer-case__object">
          <button v-if="row.id && canQueryCase" type="button" @click="openCase(row)">
            案件 #{{ row.id }}
          </button>
          <strong v-else>客诉案件</strong>
          <span>主题 #{{ row.topicId || '—' }} · 报名 #{{ row.registrationId || '—' }}</span>
        </div>
      </template>
      <template #reporter="{ row }">
        <div class="customer-case__person">
          <strong>投诉玩家 #{{ row.reporterMemberId || '—' }}</strong>
          <span v-if="row.respondentMemberId">{{ respondentLabel(row.respondentType) }} #{{ row.respondentMemberId }}</span>
          <span v-else>暂未认定过错方</span>
        </div>
      </template>
      <template #reason="{ row }"><span>{{ row.reason || '未记录投诉事由' }}</span></template>
      <template #funds="{ row }">
        <div class="customer-case__funds">
          <span>垫付 {{ amountText(row.advanceAmount) }}</span>
          <span>扣划 {{ amountText(row.deductAmount) }}</span>
        </div>
      </template>
      <template #status="{ row }"><cy-status-tag :label="statusLabel(row.status)" :tone="statusTone(row.status)" /></template>
      <template #actions="{ row }">
        <el-button v-if="canQueryCase" size="mini" type="text" @click="openCase(row)" v-hasPermi="['business:coopcomplaint:query']">查看案件</el-button>
        <el-button v-if="row.status === 0" size="mini" type="text" @click="openAdvance(row)" v-hasPermi="['business:coopcomplaint:handle']">先行垫付</el-button>
        <el-button v-if="row.status < 3" size="mini" type="text" @click="openClose(row)" v-hasPermi="['business:coopcomplaint:handle']">关闭案件</el-button>
      </template>
    </cy-data-table>

    <el-drawer title="客诉案件详情" :visible.sync="detailOpen" size="640px" append-to-body>
      <div v-loading="detailLoading" class="customer-case__detail">
        <el-alert v-if="detailError" type="error" :title="detailError" :closable="false" show-icon />
        <template v-else-if="detail">
          <cy-entity-header
            eyebrow="合作客诉案件"
            :title="'案件 #' + detail.id"
            :status="{ label: statusLabel(detail.status), tone: statusTone(detail.status) }"
            :facts="detailFacts"
          />
          <section class="customer-case__detail-copy">
            <h3>投诉事由</h3>
            <p>{{ detail.reason || '未记录投诉事由' }}</p>
          </section>
          <cy-detail-grid title="责任与资金事实" :items="detailItems" :columns="2" />
          <section v-if="detail.handleNote" class="customer-case__detail-copy">
            <h3>处理说明</h3>
            <p>{{ detail.handleNote }}</p>
          </section>
        </template>
      </div>
    </el-drawer>

    <el-dialog title="确认先行垫付" :visible.sync="advanceOpen" width="440px" append-to-body :close-on-click-modal="false">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="垫付会向投诉玩家增加余额并写入余额明细；服务端仅允许受理中的案件执行一次。"
      />
      <el-form :model="advanceForm" label-width="88px" class="customer-case__dialog-form">
        <el-form-item label="案件">#{{ advanceForm.id }} · 主题 #{{ advanceForm.topicId }}</el-form-item>
        <el-form-item label="垫付金额">
          <el-input v-model="advanceForm.amount" placeholder="输入大于 0 的金额" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button :disabled="advanceSaving" @click="advanceOpen = false">取消</el-button>
        <el-button type="warning" :loading="advanceSaving" @click="submitAdvance">确认垫付</el-button>
      </span>
    </el-dialog>

    <el-dialog title="关闭客诉案件" :visible.sync="closeOpen" width="520px" append-to-body :close-on-click-modal="false">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="关闭会按服务端事实扣划可用余额；服务端将根据当前未结案件状态裁定是否恢复结算，余额不足部分仍需人工追偿。"
      />
      <el-form :model="closeForm" label-width="110px" class="customer-case__dialog-form">
        <el-form-item label="案件">#{{ closeForm.id }} · 主题 #{{ closeForm.topicId }}</el-form-item>
        <el-form-item label="过错方类型">
          <el-select v-model="closeForm.respondentType" placeholder="选择过错方">
            <el-option label="发起人" value="initiator" />
            <el-option label="承接俱乐部" value="club" />
            <el-option label="商家" value="merchant" />
          </el-select>
        </el-form-item>
        <el-form-item label="过错方成员">
          <el-input v-model="closeForm.respondentMemberId" placeholder="俱乐部填写主理人 member ID" />
        </el-form-item>
        <el-form-item label="过错比例">
          <el-input v-model="closeForm.faultRatio" placeholder="0-100" />
        </el-form-item>
        <el-form-item label="扣划金额">
          <el-input v-model="closeForm.deductAmount" placeholder="余额不足时服务端只扣可扣部分" />
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="closeForm.note" type="textarea" :rows="3" placeholder="记录事故认定或责任判定" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button :disabled="closeSaving" @click="closeOpen = false">取消</el-button>
        <el-button type="warning" :loading="closeSaving" @click="submitClose">确认关闭案件</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { listCoopComplaint, getCoopComplaint, advanceCoopComplaint, closeCoopComplaint } from "@/api/business/coopcomplaint"
import CyDataTable from '@/components/CyDataTable'
import CyDetailGrid from '@/components/CyDetailGrid'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'
import { checkPermi } from '@/utils/permission'

const createQuery = () => ({ topicId: null, status: null, pageNum: 1, pageSize: 10 })

export default {
  name: 'CoopComplaint',
  components: { CyDataTable, CyDetailGrid, CyEntityHeader, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      listError: false,
      total: 0,
      list: [],
      query: createQuery(),
      listRequestId: 0,
      detailOpen: false,
      detailLoading: false,
      detailError: '',
      detail: null,
      detailRequestId: 0,
      advanceOpen: false,
      advanceSaving: false,
      advanceForm: {},
      closeOpen: false,
      closeSaving: false,
      closeForm: {}
    }
  },
  computed: {
    canQueryCase() {
      return checkPermi(['business:coopcomplaint:query'])
    },
    activeFilters() {
      const filters = []
      if (this.query.topicId !== null && this.query.topicId !== '') filters.push({ key: 'topicId', label: '主题', value: this.query.topicId })
      if ([0, 1, 2, 3].indexOf(this.query.status) > -1) filters.push({ key: 'status', label: '阶段', value: this.statusLabel(this.query.status) })
      return filters
    },
    tableColumns() {
      return [
        { key: 'case', label: '案件', minWidth: 170, locked: true },
        { key: 'reporter', label: '责任主体', minWidth: 190 },
        { key: 'reason', label: '投诉事由', minWidth: 240, showOverflowTooltip: true },
        { key: 'funds', label: '资金事实', minWidth: 160 },
        { key: 'status', label: '阶段', width: 110 },
        { key: 'actions', label: '处理', width: 220, fixed: 'right', locked: true }
      ]
    },
    detailFacts() {
      if (!this.detail) return []
      return [
        { label: '主题', value: this.detail.topicId ? '#' + this.detail.topicId : '—' },
        { label: '报名', value: this.detail.registrationId ? '#' + this.detail.registrationId : '—' },
        { label: '投诉玩家', value: this.detail.reporterMemberId ? '#' + this.detail.reporterMemberId : '—' },
        { label: '处理人', value: this.detail.handlerId ? '#' + this.detail.handlerId : '未处理' }
      ]
    },
    detailItems() {
      if (!this.detail) return []
      return [
        { key: 'respondent', label: '过错方', value: this.detail.respondentMemberId ? this.respondentLabel(this.detail.respondentType) + ' #' + this.detail.respondentMemberId : '暂未认定' },
        { key: 'faultRatio', label: '过错比例', value: this.detail.faultRatio === null || this.detail.faultRatio === undefined ? '—' : this.detail.faultRatio + '%' },
        { key: 'advanceAmount', label: '垫付金额', value: this.amountText(this.detail.advanceAmount) },
        { key: 'deductAmount', label: '实际扣划', value: this.amountText(this.detail.deductAmount) },
        { key: 'createTime', label: '创建时间', value: this.detail.createTime || '—' },
        { key: 'updateTime', label: '处理时间', value: this.detail.updateTime || '—' }
      ]
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
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/customer/case') {
        this.$router.replace({ name: 'customerCase', query: Object.assign({}, this.$route.query) })
        return
      }
      this.applyRouteQuery()
      this.getList()
    },
    applyRouteQuery() {
      const routeQuery = this.$route.query || {}
      const status = Number(routeQuery.status)
      const pageNum = Number(routeQuery.pageNum)
      const pageSize = Number(routeQuery.pageSize)
      this.query.topicId = routeQuery.topicId !== undefined && routeQuery.topicId !== '' ? routeQuery.topicId : null
      this.query.status = routeQuery.status !== undefined && [0, 1, 2, 3].indexOf(status) > -1 ? status : null
      this.query.pageNum = Number.isInteger(pageNum) && pageNum > 0 ? pageNum : 1
      this.query.pageSize = Number.isInteger(pageSize) && pageSize > 0 ? pageSize : 10
    },
    syncRoute() {
      const query = {}
      if (this.query.topicId !== null && this.query.topicId !== '') query.topicId = String(this.query.topicId)
      if ([0, 1, 2, 3].indexOf(this.query.status) > -1) query.status = String(this.query.status)
      if (this.query.pageNum !== 1) query.pageNum = String(this.query.pageNum)
      if (this.query.pageSize !== 10) query.pageSize = String(this.query.pageSize)
      const current = this.$route.query || {}
      const sameQuery = Object.keys(query).length === Object.keys(current).length && Object.keys(query).every(key => query[key] === current[key])
      if (String(this.$route.path || '').replace(/\/+$/, '') === '/customer/case' && sameQuery) return this.getList()
      return this.$router.replace({ name: 'customerCase', query })
    },
    handleQuery() {
      this.query.pageNum = 1
      this.syncRoute()
    },
    resetQuery() {
      this.query = createQuery()
      this.syncRoute()
    },
    clearFilter(key) {
      if (key === 'topicId' || key === 'status') this.query[key] = null
      this.query.pageNum = 1
      this.syncRoute()
    },
    handlePagination({ page, limit }) {
      this.query.pageNum = page
      this.query.pageSize = limit
      this.syncRoute()
    },
    getList() {
      const requestId = ++this.listRequestId
      const params = Object.assign({}, this.query)
      this.loading = true
      this.listError = false
      return listCoopComplaint(params)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.list = response.rows || []
          this.total = Number(response.total || 0)
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.list = []
          this.total = 0
          this.listError = true
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    openCase(row) {
      const requestId = ++this.detailRequestId
      this.detailOpen = true
      this.detailLoading = true
      this.detailError = ''
      this.detail = null
      getCoopComplaint(row.id).then(response => {
        if (requestId !== this.detailRequestId) return
        this.detail = response.data || null
        this.detailError = this.detail ? '' : '未读取到案件详情'
        this.detailLoading = false
      }, () => {
        if (requestId !== this.detailRequestId) return
        this.detailError = '案件详情读取失败'
        this.detailLoading = false
      })
    },
    openAdvance(row) {
      this.advanceForm = { id: row.id, topicId: row.topicId, amount: null }
      this.advanceOpen = true
    },
    submitAdvance() {
      const amount = Number(this.advanceForm.amount)
      if (!Number.isFinite(amount) || amount <= 0) {
        this.$modal.msgWarning('请输入大于 0 的垫付金额')
        return
      }
      this.advanceSaving = true
      advanceCoopComplaint({ id: this.advanceForm.id, amount: this.advanceForm.amount }).then(() => {
        this.$modal.msgSuccess('已登记先行垫付')
        this.advanceOpen = false
        this.advanceSaving = false
        this.getList()
      }, () => {
        this.advanceSaving = false
      })
    },
    openClose(row) {
      this.closeForm = {
        id: row.id,
        topicId: row.topicId,
        respondentType: row.respondentType || 'club',
        respondentMemberId: row.respondentMemberId || null,
        faultRatio: null,
        deductAmount: null,
        note: null
      }
      this.closeOpen = true
    },
    submitClose() {
      this.closeSaving = true
      closeCoopComplaint({
        id: this.closeForm.id,
        respondentType: this.closeForm.respondentType,
        respondentMemberId: this.closeForm.respondentMemberId,
        faultRatio: this.closeForm.faultRatio,
        deductAmount: this.closeForm.deductAmount,
        note: this.closeForm.note
      }).then(() => {
        this.$modal.msgSuccess('案件已关闭，结算恢复状态以服务端裁定为准')
        this.closeOpen = false
        this.closeSaving = false
        this.getList()
      }, () => {
        this.closeSaving = false
      })
    },
    statusLabel(status) {
      return ['受理中', '已垫付', '追偿中', '已关闭'][Number(status)] || '未知状态'
    },
    statusTone(status) {
      return ['warning', 'brand', 'info', 'success'][Number(status)] || 'neutral'
    },
    respondentLabel(type) {
      return { initiator: '发起人', club: '承接俱乐部', merchant: '商家' }[type] || '待认定主体'
    },
    amountText(amount) {
      if (amount === null || amount === undefined || amount === '') return '—'
      const value = Number(amount)
      return Number.isFinite(value) ? '¥' + value.toFixed(2) : '—'
    }
  }
}
</script>

<style lang="scss" scoped>
.customer-case__filter { margin-bottom: 20px; }
.customer-case__object, .customer-case__person, .customer-case__funds { display: flex; flex-direction: column; gap: 4px; }
.customer-case__object button { width: fit-content; padding: 0; border: 0; background: transparent; color: var(--cy-color-brand); font: inherit; font-weight: 700; cursor: pointer; }
.customer-case__object button:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 3px; }
.customer-case__object span, .customer-case__person span, .customer-case__funds span { color: var(--cy-color-text-tertiary); font-size: 12px; }
.customer-case__person strong { color: var(--cy-color-text-primary); font-size: 13px; }
.customer-case__detail { min-height: 180px; padding: 0 20px 24px; }
.customer-case__detail-copy { margin: 20px 0; padding: 16px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.customer-case__detail-copy h3 { margin: 0 0 8px; color: var(--cy-color-text-primary); font-size: 14px; }
.customer-case__detail-copy p { margin: 0; color: var(--cy-color-text-secondary); line-height: 1.7; white-space: pre-wrap; }
.customer-case__dialog-form { margin-top: var(--cy-space-3-5); }
</style>

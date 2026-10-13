<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="供给与权益"
      title="优惠券目录"
      description="管理可发放的优惠券配置，查看领取与核销事实。发行上限、领取和核销计数不会在这里手工改写。"
      :updated-at="updatedAt"
    >
      <el-button
        v-hasPermi="['business:coupon:add']"
        type="primary"
        icon="el-icon-plus"
        @click="handleAdd"
      >新建优惠券</el-button>
    </cy-page-header>

    <section class="cy-panel coupon-directory__filters">
      <div class="cy-panel__heading">
        <div>
          <h2>按业务事实筛选</h2>
          <p>名称、券类型和状态为常用条件；商家编号只在需要精确定位既有商家券时使用。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="draft" size="small" :inline="true" label-width="76px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="优惠券名称" prop="name">
            <el-input v-model="draft.name" placeholder="按名称搜索" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="券类型" prop="couponType">
            <el-select v-model="draft.couponType" placeholder="全部类型" clearable>
              <el-option label="礼品券" :value="0" />
              <el-option label="9 折券" :value="1" />
              <el-option label="8 折券" :value="2" />
              <el-option label="体验卡" :value="3" />
            </el-select>
          </el-form-item>
          <el-form-item label="当前状态" prop="status">
            <el-select v-model="draft.status" placeholder="全部状态" clearable>
              <el-option label="未开始" :value="0" />
              <el-option label="进行中" :value="1" />
              <el-option label="已结束" :value="2" />
              <el-option label="手动失效" :value="3" />
              <el-option label="已停发" :value="4" />
            </el-select>
          </el-form-item>
          <template slot="more">
            <el-form-item label="适用范围" prop="scopeType">
              <el-select v-model="draft.scopeType" placeholder="全部范围" clearable>
                <el-option label="平台优惠券" :value="0" />
                <el-option label="商家优惠券" :value="1" />
              </el-select>
            </el-form-item>
            <el-form-item label="商家编号" prop="merchantId">
              <el-input v-model="draft.merchantId" placeholder="精确商家 ID" clearable @keyup.enter.native="handleQuery" />
            </el-form-item>
          </template>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="优惠券目录暂时无法读取"
      description="请重新读取；系统不会把读取失败伪装成没有优惠券。"
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
      default-density="comfortable"
      empty-text="当前条件下没有优惠券"
      @pagination="handlePagination"
      @refresh="getList"
    >
      <template slot="toolbar-actions">
        <el-button
          v-hasPermi="['business:coupon:export']"
          plain
          size="mini"
          icon="el-icon-download"
          @click="handleExport"
        >导出当前结果</el-button>
      </template>
      <template slot="coupon" slot-scope="scope">
        <button type="button" class="coupon-directory__identity" @click="openDetail(scope.row)">
          <strong>{{ scope.row.name || `优惠券 #${scope.row.id}` }}</strong>
          <span>编号 #{{ scope.row.id }}{{ scope.row.code ? ` · ${scope.row.code}` : '' }}</span>
        </button>
      </template>
      <template slot="type" slot-scope="scope">
        <cy-status-tag :label="couponTypeLabel(scope.row.couponType)" :tone="couponTypeTone(scope.row.couponType)" />
      </template>
      <template slot="scope" slot-scope="scope">
        <span>{{ scopeLabel(scope.row) }}</span>
      </template>
      <template slot="validity" slot-scope="scope">
        <span>{{ formatDate(scope.row.startTime) }} 至 {{ formatDate(scope.row.endTime) }}</span>
      </template>
      <template slot="issuance" slot-scope="scope">
        <span>{{ issuanceLabel(scope.row) }}</span>
      </template>
      <template slot="status" slot-scope="scope">
        <cy-status-tag :label="statusLabel(scope.row.status)" :tone="statusTone(scope.row.status)" />
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button
          v-if="canQueryCoupon && canEditCoupon"
          v-hasPermi="['business:coupon:query']"
          size="mini"
          type="text"
          icon="el-icon-edit-outline"
          @click="handleEdit(scope.row)"
        >编辑配置</el-button>
        <el-button
          v-hasPermi="['business:couponhistory:list']"
          size="mini"
          type="text"
          icon="el-icon-tickets"
          @click="openLedger(scope.row)"
        >查看流水</el-button>
        <el-button
          v-if="Number(scope.row.status) !== 3"
          v-hasPermi="['business:coupon:edit']"
          size="mini"
          type="text"
          class="coupon-directory__invalidate"
          @click="deactivateCoupon(scope.row)"
        >手动失效</el-button>
      </template>
    </cy-data-table>

    <el-drawer title="优惠券详情" :visible.sync="detailOpen" size="560px" append-to-body>
      <div v-if="selectedCoupon" class="coupon-directory__drawer">
        <cy-entity-header
          eyebrow="优惠券配置"
          :title="selectedCoupon.name || `优惠券 #${selectedCoupon.id}`"
          :status="{ label: statusLabel(selectedCoupon.status), tone: statusTone(selectedCoupon.status) }"
          :facts="detailFacts"
        />
        <cy-detail-grid title="配置与统计事实" :items="detailItems" class="coupon-directory__detail-grid" />
        <p class="coupon-directory__drawer-note">领取与核销由业务流水原子维护；查看明细可进入该券的流水子页。</p>
        <el-button
          v-hasPermi="['business:couponhistory:list']"
          plain
          icon="el-icon-tickets"
          @click="openLedger(selectedCoupon)"
        >查看领取与核销流水</el-button>
      </div>
    </el-drawer>

    <el-dialog :title="form.id ? '编辑优惠券配置' : '新建优惠券'" :visible.sync="formOpen" width="680px" append-to-body :close-on-click-modal="false">
      <el-form ref="couponForm" :model="form" :rules="rules" label-width="108px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="优惠券名称" prop="name">
              <el-input v-model="form.name" maxlength="80" show-word-limit placeholder="例如：夏日限定礼品券" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="券类型" prop="couponType">
              <el-select v-model="form.couponType" placeholder="请选择券类型" class="coupon-directory__full-width">
                <el-option label="礼品券" :value="0" />
                <el-option label="9 折券" :value="1" />
                <el-option label="8 折券" :value="2" />
                <el-option label="体验卡" :value="3" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="开始时间" prop="startTime">
              <el-date-picker v-model="form.startTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" placeholder="选择开始时间" class="coupon-directory__full-width" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="结束时间" prop="endTime">
              <el-date-picker v-model="form.endTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" placeholder="选择结束时间" class="coupon-directory__full-width" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="使用平台" prop="platform">
              <el-select v-model="form.platform" class="coupon-directory__full-width">
                <el-option label="全部平台" :value="0" />
                <el-option label="移动端" :value="1" />
                <el-option label="PC 端" :value="2" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item v-if="!form.id" label="发放上限" prop="publishCount">
              <el-input-number v-model="form.publishCount" :min="0" :precision="0" controls-position="right" class="coupon-directory__full-width" />
              <span class="coupon-directory__field-hint">0 表示不限量</span>
            </el-form-item>
            <el-form-item v-else label="发放统计">
              <span>已领取 {{ selectedCount(form.receiveCount) }} · 已核销 {{ selectedCount(form.useCount) }}</span>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="适用范围" prop="scopeType">
              <el-select v-model="form.scopeType" :disabled="Boolean(form.id)" class="coupon-directory__full-width">
                <el-option label="平台优惠券" :value="0" />
                <el-option label="商家优惠券" :value="1" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item v-if="Number(form.scopeType) === 1" label="所属商家" prop="merchantId">
              <el-input-number v-model="form.merchantId" :min="1" :precision="0" :controls="false" :disabled="Boolean(form.id)" class="coupon-directory__full-width" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="24">
            <el-form-item label="金额 / 折扣" prop="amount">
              <el-input-number v-model="form.amount" :min="0" :precision="2" :controls="false" class="coupon-directory__full-width" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="使用类型" prop="useType">
              <el-select v-model="form.useType" class="coupon-directory__full-width">
                <el-option label="全场通用" :value="0" />
                <el-option label="指定分类" :value="1" />
                <el-option label="指定商品" :value="2" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="可领取日期" prop="enableTime">
              <el-date-picker v-model="form.enableTime" type="date" value-format="yyyy-MM-dd" placeholder="留空按有效期" class="coupon-directory__full-width" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="优惠码" prop="code">
          <el-input v-model="form.code" placeholder="可选；留空由发放记录生成券码" />
        </el-form-item>
        <el-form-item label="说明" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="说明适用场景、权益或使用限制" />
        </el-form-item>
        <el-alert
          v-if="form.id"
          title="发行上限、领取数、核销数、适用范围和当前状态由既有业务事实维护，编辑配置不会提交或改变它们。"
          type="info"
          :closable="false"
          show-icon
        />
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存配置</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listCoupon, getCoupon, addCoupon, updateCoupon } from '@/api/business/coupon'
import { checkPermi } from '@/utils/permission'
import CyDataTable from '@/components/CyDataTable'
import CyDetailGrid from '@/components/CyDetailGrid'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const createFilters = () => ({
  name: null,
  couponType: null,
  scopeType: null,
  merchantId: null,
  status: null
})

const createQuery = (filters = {}) => Object.assign({
  pageNum: 1,
  pageSize: 10
}, createFilters(), filters)

const createCouponForm = () => ({
  id: null,
  name: '',
  couponType: null,
  platform: 0,
  amount: null,
  startTime: null,
  endTime: null,
  useType: 0,
  publishCount: 0,
  enableTime: null,
  code: '',
  scopeType: 0,
  merchantId: null,
  status: 1,
  description: ''
})

function optionalEnum(value, allowed) {
  const number = Number(value)
  return allowed.indexOf(number) !== -1 ? number : null
}

function positiveInteger(value, fallback) {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : fallback
}

function validatePublishCount(rule, value, callback) {
  const number = Number(value)
  if (!Number.isInteger(number) || number < 0) {
    callback(new Error('发放上限必须是大于或等于 0 的整数'))
    return
  }
  callback()
}

export default {
  name: 'CouponDirectory',
  components: { CyDataTable, CyDetailGrid, CyEntityHeader, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      listError: false,
      listRequestId: 0,
      editRequestId: 0,
      total: 0,
      list: [],
      updatedAt: '',
      draft: createFilters(),
      query: createQuery(),
      detailOpen: false,
      selectedCoupon: null,
      formOpen: false,
      saving: false,
      form: createCouponForm(),
      rules: {
        name: [{ required: true, message: '请填写优惠券名称', trigger: 'blur' }],
        couponType: [{ required: true, message: '请选择券类型', trigger: 'change' }],
        startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
        endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
        publishCount: [{ validator: validatePublishCount, trigger: 'change' }],
        merchantId: [{ required: true, message: '商家券必须填写所属商家', trigger: 'change' }]
      },
      tableColumns: [
        { key: 'coupon', label: '优惠券', minWidth: 220, locked: true },
        { key: 'type', label: '券类型', width: 112 },
        { key: 'scope', label: '适用范围', minWidth: 136 },
        { key: 'validity', label: '有效期', minWidth: 216 },
        { key: 'issuance', label: '发放与核销', minWidth: 168 },
        { key: 'status', label: '状态', width: 108 },
        { key: 'actions', label: '操作', width: 268, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    canQueryCoupon() {
      return checkPermi(['business:coupon:query'])
    },
    canEditCoupon() {
      return checkPermi(['business:coupon:edit'])
    },
    activeFilters() {
      return [
        this.query.name && { key: 'name', label: '优惠券名称', value: this.query.name },
        this.query.couponType !== null && { key: 'couponType', label: '券类型', value: this.couponTypeLabel(this.query.couponType) },
        this.query.status !== null && { key: 'status', label: '当前状态', value: this.statusLabel(this.query.status) },
        this.query.scopeType !== null && { key: 'scopeType', label: '适用范围', value: this.scopeTypeLabel(this.query.scopeType) },
        this.query.merchantId && { key: 'merchantId', label: '商家编号', value: `#${this.query.merchantId}` }
      ].filter(Boolean)
    },
    detailFacts() {
      if (!this.selectedCoupon) return []
      return [
        { label: '优惠券编号', value: `#${this.selectedCoupon.id}` },
        { label: '发放上限', value: this.publishLimitLabel(this.selectedCoupon) },
        { label: '已领取', value: this.selectedCount(this.selectedCoupon.receiveCount) },
        { label: '已核销', value: this.selectedCount(this.selectedCoupon.useCount) }
      ]
    },
    detailItems() {
      if (!this.selectedCoupon) return []
      const row = this.selectedCoupon
      return [
        { key: 'type', label: '券类型', value: this.couponTypeLabel(row.couponType) },
        { key: 'scope', label: '适用范围', value: this.scopeLabel(row) },
        { key: 'validity', label: '有效期', value: `${this.formatDate(row.startTime)} 至 ${this.formatDate(row.endTime)}`, span: 2 },
        { key: 'platform', label: '使用平台', value: this.platformLabel(row.platform) },
        { key: 'useType', label: '使用类型', value: this.useTypeLabel(row.useType) },
        { key: 'amount', label: '金额 / 折扣', value: row.amount === null || row.amount === undefined ? '未配置' : row.amount },
        { key: 'enableTime', label: '可领取日期', value: this.formatDate(row.enableTime) },
        { key: 'code', label: '优惠码', value: row.code || '由领取记录生成' },
        { key: 'description', label: '说明', value: row.description || '未填写', span: 2 }
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
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/supply/coupon') {
        this.$router.replace({ name: 'supplyCoupon', query: Object.assign({}, this.$route.query) })
        return
      }
      this.applyRouteQuery()
      this.getList()
    },
    applyRouteQuery() {
      const routeQuery = this.$route.query || {}
      this.query = createQuery({
        name: routeQuery.name ? String(routeQuery.name) : null,
        couponType: optionalEnum(routeQuery.couponType, [0, 1, 2]),
        scopeType: optionalEnum(routeQuery.scopeType, [0, 1]),
        merchantId: routeQuery.merchantId ? String(routeQuery.merchantId) : null,
        status: optionalEnum(routeQuery.status, [0, 1, 2, 3, 4]),
        pageNum: positiveInteger(routeQuery.pageNum, 1),
        pageSize: positiveInteger(routeQuery.pageSize, 10)
      })
      this.draft = createFilters()
      Object.keys(this.draft).forEach(key => {
        this.draft[key] = this.query[key]
      })
    },
    routeQuery() {
      const next = {}
      if (this.query.name) next.name = this.query.name
      if (this.query.couponType !== null) next.couponType = String(this.query.couponType)
      if (this.query.scopeType !== null) next.scopeType = String(this.query.scopeType)
      if (this.query.merchantId) next.merchantId = String(this.query.merchantId)
      if (this.query.status !== null) next.status = String(this.query.status)
      if (this.query.pageNum > 1) next.pageNum = String(this.query.pageNum)
      if (this.query.pageSize !== 10) next.pageSize = String(this.query.pageSize)
      return next
    },
    sameRouteQuery(next) {
      const current = this.$route.query || {}
      const currentKeys = Object.keys(current)
      const nextKeys = Object.keys(next)
      return currentKeys.length === nextKeys.length && nextKeys.every(key => String(current[key]) === String(next[key]))
    },
    syncRoute() {
      const next = this.routeQuery()
      if (this.sameRouteQuery(next)) return this.getList()
      return this.$router.replace({ name: 'supplyCoupon', query: next })
    },
    getList() {
      const requestId = ++this.listRequestId
      const requestQuery = Object.assign({}, this.query)
      this.loading = true
      this.listError = false
      return listCoupon(requestQuery).then(response => {
        if (requestId !== this.listRequestId) return
        this.list = response.rows || []
        this.total = response.total || 0
        this.updatedAt = new Date().toLocaleString('zh-CN', { hour12: false })
      }).catch(() => {
        if (requestId !== this.listRequestId) return
        this.list = []
        this.total = 0
        this.listError = true
      }).finally(() => {
        if (requestId === this.listRequestId) this.loading = false
      })
    },
    handleQuery() {
      this.query = createQuery(Object.assign({}, this.draft, {
        pageNum: 1,
        pageSize: this.query.pageSize
      }))
      return this.syncRoute()
    },
    resetQuery() {
      this.draft = createFilters()
      this.query = createQuery({ pageSize: this.query.pageSize })
      return this.syncRoute()
    },
    clearFilter(key) {
      this.draft[key] = null
      this.query[key] = null
      this.query.pageNum = 1
      return this.syncRoute()
    },
    handlePagination({ page, limit }) {
      this.query.pageNum = positiveInteger(page, 1)
      this.query.pageSize = positiveInteger(limit, 10)
      return this.syncRoute()
    },
    couponTypeLabel(type) {
      return ({ 0: '礼品券', 1: '9 折券', 2: '8 折券', 3: '体验卡' })[Number(type)] || '未定义券类型'
    },
    couponTypeTone(type) {
      return ({ 0: 'brand', 1: 'success', 2: 'info', 3: 'warning' })[Number(type)] || 'neutral'
    },
    statusLabel(status) {
      return ({ 0: '未开始', 1: '进行中', 2: '已结束', 3: '手动失效', 4: '已停发' })[Number(status)] || '状态未知'
    },
    statusTone(status) {
      return ({ 0: 'info', 1: 'success', 2: 'neutral', 3: 'danger', 4: 'danger' })[Number(status)] || 'neutral'
    },
    scopeTypeLabel(scopeType) {
      return Number(scopeType) === 1 ? '商家优惠券' : '平台优惠券'
    },
    scopeLabel(row) {
      if (Number(row.scopeType) !== 1) return '平台优惠券'
      return row.merchantId ? `商家优惠券 · #${row.merchantId}` : '商家优惠券 · 未记录商家'
    },
    platformLabel(platform) {
      return ({ 0: '全部平台', 1: '移动端', 2: 'PC 端' })[Number(platform)] || '未定义平台'
    },
    useTypeLabel(useType) {
      return ({ 0: '全场通用', 1: '指定分类', 2: '指定商品' })[Number(useType)] || '未定义使用类型'
    },
    selectedCount(value) {
      return value === null || value === undefined ? 0 : value
    },
    publishLimitLabel(row) {
      const count = Number(row.publishCount)
      return Number.isFinite(count) && count > 0 ? count : '不限量'
    },
    issuanceLabel(row) {
      const limit = Number(row.publishCount)
      const received = Number(row.receiveCount) || 0
      const used = Number(row.useCount) || 0
      if (Number.isFinite(limit) && limit > 0) {
        return `剩余 ${Math.max(0, limit - received)}/${limit} · 已核销 ${used}`
      }
      return `不限量 · 已领 ${received} · 已核销 ${used}`
    },
    formatDate(value) {
      if (!value) return '未设置'
      return String(value).replace('T', ' ').slice(0, 19)
    },
    openDetail(row) {
      this.selectedCoupon = row || null
      this.detailOpen = Boolean(row)
    },
    openLedger(row) {
      if (!row || !row.id) return
      this.$router.push({ name: 'couponLedger', params: { couponId: row.id } })
    },
    handleAdd() {
      this.form = createCouponForm()
      this.formOpen = true
      this.$nextTick(() => {
        if (this.$refs.couponForm) this.$refs.couponForm.clearValidate()
      })
    },
    handleEdit(row) {
      if (!row || !row.id) return
      const requestId = ++this.editRequestId
      return getCoupon(row.id).then(response => {
        if (requestId !== this.editRequestId) return
        this.form = Object.assign(createCouponForm(), response.data || {})
        this.formOpen = true
        this.$nextTick(() => {
          if (this.$refs.couponForm) this.$refs.couponForm.clearValidate()
        })
      }).catch(() => {
        if (requestId !== this.editRequestId) return
        this.$modal.msgError('优惠券详情读取失败，请稍后重试。')
      })
    },
    payloadForForm() {
      const payload = {
        name: this.form.name,
        couponType: this.form.couponType,
        platform: this.form.platform,
        amount: this.form.amount,
        startTime: this.form.startTime,
        endTime: this.form.endTime,
        useType: this.form.useType,
        enableTime: this.form.enableTime,
        code: this.form.code,
        scopeType: this.form.scopeType,
        merchantId: Number(this.form.scopeType) === 1 ? this.form.merchantId : null,
        description: this.form.description
      }
      if (this.form.id) {
        payload.id = this.form.id
      } else {
        payload.publishCount = this.form.publishCount
        payload.status = 1
        payload.delFlag = 0
      }
      return payload
    },
    submitForm() {
      this.$refs.couponForm.validate(valid => {
        if (!valid || this.saving) return
        const payload = this.payloadForForm()
        this.saving = true
        const request = payload.id ? updateCoupon(payload) : addCoupon(payload)
        request.then(() => {
          this.$modal.msgSuccess(payload.id ? '优惠券配置已更新' : '优惠券已创建')
          this.formOpen = false
          this.getList()
        }).catch(() => {}).finally(() => {
          this.saving = false
        })
      })
    },
    deactivateCoupon(row) {
      if (!row || !row.id) return Promise.resolve()
      return this.$modal.confirm(`确认将“${row.name || `优惠券 #${row.id}`}”设为手动失效？已有领取和核销流水会保留。`)
        .then(() => updateCoupon({ id: row.id, status: 3 }))
        .then(() => {
          this.$modal.msgSuccess('优惠券已设为手动失效')
          return this.getList()
        }).catch(() => {})
    },
    handleExport() {
      this.download('business/coupon/export', Object.assign({}, this.query), `coupon_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style lang="scss" scoped>
.coupon-directory__filters { margin-bottom: 20px; }
.coupon-directory__identity { display: block; max-width: 100%; padding: 0; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.coupon-directory__identity strong, .coupon-directory__identity span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.coupon-directory__identity strong { color: var(--cy-color-text-primary); font-weight: 700; }
.coupon-directory__identity span { margin-top: 4px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.coupon-directory__identity:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 3px; }
.coupon-directory__invalidate { color: var(--cy-color-status-danger); }
.coupon-directory__drawer { min-height: 320px; padding: 0 24px var(--cy-space-7); }
.coupon-directory__detail-grid { margin-top: var(--cy-space-4-5); }
.coupon-directory__drawer-note { margin: var(--cy-space-3-5) 0; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.7; }
.coupon-directory__full-width { width: 100%; }
.coupon-directory__field-hint { display: block; margin-top: var(--cy-legacy-5); color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.4; }
</style>

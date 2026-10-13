<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="俱乐部与探索"
      title="漫游 POI 治理"
      description="审核商家据点投放与认领，并核对已生效据点的玩家可见性。"
      :updated-at="updatedAt"
    >
      <el-button slot="primary" size="small" icon="el-icon-refresh" :loading="loading" @click="getList">刷新列表</el-button>
    </cy-page-header>

    <section class="cy-panel roam-poi-application-panel" aria-label="商家据点申请审核">
      <div class="cy-panel__heading">
        <div>
          <h2>商家投放与认领申请</h2>
          <p>申请状态与据点资产记录在同一份据点档案中；通过后申请人才成为正式归属商家。</p>
        </div>
        <el-button size="small" icon="el-icon-refresh" :loading="applicationLoading" @click="getApplications">刷新申请</el-button>
      </div>
      <cy-resource-state
        v-if="applicationError"
        kind="error"
        title="据点申请暂时无法读取"
        description="未用旧列表冒充当前审核状态，请重新读取。"
        retry-text="重新读取"
        :retrying="applicationLoading"
        @retry="getApplications"
      />
      <cy-empty-state
        v-else-if="!applicationLoading && !applications.length"
        title="暂无待审核据点申请"
        description="商家提交投放或认领后会出现在这里。"
      />
      <el-table v-else v-loading="applicationLoading" :data="applications" size="small" border>
        <el-table-column label="申请" min-width="180">
          <template slot-scope="s">
            <div class="roam-poi-object__name">{{ applicationTypeText(s.row.applicationType) }}</div>
            <div class="roam-poi-object__meta">#{{ s.row.id }} · {{ s.row.name || '节点信息待核对' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="申请商家" min-width="160">
          <template slot-scope="s">{{ s.row.merchantName || `merchant #${s.row.applicantMerchantId}` }}</template>
        </el-table-column>
        <el-table-column label="目标/位置" min-width="180">
          <template slot-scope="s">
            <span v-if="s.row.applicationType === 2">{{ s.row.name || `POI #${s.row.id}` }}</span>
            <span v-else>{{ s.row.address || `${s.row.lat || '—'}, ${s.row.lng || '—'}` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="审核材料" min-width="260">
          <template slot-scope="s">
            <template v-if="Number(s.row.applicationType) === 1">
              <!-- 审核要判的是「这个玩法跑不跑得起来、题目合不合规」,只给模板 id 判不了 -->
              <div v-if="s.row.reviewMaterial && s.row.reviewMaterial.merchant" class="roam-poi-review__shop">
                {{ s.row.reviewMaterial.merchant.name || '—' }}
                <span class="roam-poi-object__meta">
                  {{ s.row.reviewMaterial.merchant.phone || '未留电话' }} ·
                  {{ s.row.reviewMaterial.merchant.address || '无地址' }} ·
                  入驻{{ merchantStatusText(s.row.reviewMaterial.merchant) }}
                </span>
              </div>
              <div v-if="s.row.reviewMaterial && s.row.reviewMaterial.template" class="roam-poi-review__play">
                <div>
                  <b>{{ s.row.reviewMaterial.template.title || '未命名玩法' }}</b>
                  <span class="roam-poi-object__meta">
                    {{ interactionTypeText(s.row.reviewMaterial.template.interactionType) }} ·
                    {{ validationMethodText(s.row.reviewMaterial.template.validationMethod) }}
                  </span>
                </div>
                <div v-if="s.row.reviewMaterial.template.description" class="roam-poi-application__description">{{ s.row.reviewMaterial.template.description }}</div>
                <div v-if="s.row.reviewMaterial.template.questionName">题:{{ s.row.reviewMaterial.template.questionName }}</div>
                <div v-if="Number(s.row.reviewMaterial.template.validationMethod) === 1">答案:{{ s.row.reviewMaterial.template.questionAnswer || '（未填，玩家永远答不对）' }}</div>
                <div v-if="Number(s.row.reviewMaterial.template.validationMethod) === 3">
                  A {{ s.row.reviewMaterial.template.questionA || '—' }} ／
                  B {{ s.row.reviewMaterial.template.questionB || '—' }} ／
                  C {{ s.row.reviewMaterial.template.questionC || '—' }} ／
                  D {{ s.row.reviewMaterial.template.questionD || '—' }}
                  · 正解 {{ s.row.reviewMaterial.template.correctAnswer || '（未标，玩家永远答不对）' }}
                </div>
                <div v-if="Number(s.row.reviewMaterial.template.validationMethod) === 2">拍照要求:{{ s.row.reviewMaterial.template.photoRequireDesc || '未填' }}</div>
                <div v-if="s.row.reviewMaterial.template.hint1" class="roam-poi-object__meta">提示:{{ s.row.reviewMaterial.template.hint1 }}</div>
                <div class="roam-poi-object__meta">
                  奖励:{{ s.row.reviewMaterial.template.couponId ? ('券 #' + s.row.reviewMaterial.template.couponId) : (s.row.reviewMaterial.template.feedbackText || '无') }}
                </div>
              </div>
              <div v-else class="roam-poi-muted">模板 #{{ s.row.templateId || '—' }} 读不到内容,先别通过</div>
              <div class="roam-poi-object__meta">半径 {{ s.row.radiusM || 80 }}m · 城市 {{ s.row.cityCode || '—' }} · 标签 {{ s.row.tags || '—' }}</div>
              <div class="roam-poi-application__description">{{ s.row.description || '无据点描述' }}</div>
              <el-image
                v-if="s.row.coverImg"
                class="roam-poi-application__cover"
                :src="s.row.coverImg"
                :preview-src-list="[s.row.coverImg]"
                fit="cover"
              />
            </template>
            <span v-else class="roam-poi-muted">认领仅变更节点归属，不改动现有内容</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" prop="createTime" width="150" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template slot-scope="s">{{ applicationStatusText(s.row.auditStatus) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center">
          <template slot-scope="s">
            <template v-if="Number(s.row.auditStatus) === 0">
              <el-button v-hasPermi="['business:roamPoi:edit']" type="text" size="mini" :loading="pendingApplicationId === s.row.id" @click="approveApplication(s.row)">通过</el-button>
              <el-button v-hasPermi="['business:roamPoi:edit']" type="text" size="mini" :loading="pendingApplicationId === s.row.id" @click="rejectApplication(s.row)">驳回</el-button>
            </template>
            <span v-else class="roam-poi-muted">{{ s.row.auditReason || '已处置' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="cy-panel roam-poi-filter" aria-label="漫游 POI 筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>定位治理范围</h2>
          <p>筛选只作用于本次读取结果；接口不提供分页，不把当前数量当作全量统计。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="72px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="玩家可见性" prop="status">
            <el-select v-model="queryForm.status" placeholder="全部状态" clearable style="width: 160px">
              <el-option label="上架（玩家可见）" :value="1" />
              <el-option label="下架（玩家不可见）" :value="0" />
            </el-select>
          </el-form-item>
          <el-form-item label="城市码" prop="cityCode">
            <el-input v-model="queryForm.cityCode" placeholder="如 HZ" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="商家 ID" prop="merchantId">
            <el-input v-model="queryForm.merchantId" placeholder="mms_merchant ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <section class="roam-poi-visibility" aria-label="当前筛选结果的玩家可见性">
      <div v-for="item in visibilitySummary" :key="item.key" class="roam-poi-visibility__item">
        <span class="roam-poi-visibility__label">{{ item.label }}</span>
        <strong>{{ item.count }}</strong>
        <p>{{ item.description }}</p>
      </div>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="漫游 POI 列表暂时无法读取"
      description="没有保留旧列表作为当前状态。请检查网络或权限后重新读取。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <section v-else class="cy-panel roam-poi-table-panel" aria-label="漫游 POI 治理列表">
      <div class="cy-panel__heading">
        <div>
          <h2>当前筛选结果</h2>
          <p>{{ list.length }} 个据点；状态以这次接口回读为准。</p>
        </div>
      </div>
      <cy-empty-state
        v-if="!loading && !list.length"
        title="当前筛选下没有漫游 POI"
        description="可以清除筛选后重新读取，或确认目标商家与城市码。"
        action-text="清除筛选"
        @action="resetQuery"
      />
      <el-table v-else v-loading="loading" :data="list" size="small" border>
      <el-table-column label="据点" min-width="180">
        <template slot-scope="s">
          <div class="roam-poi-object__name">{{ s.row.name || '未命名据点' }}</div>
          <div class="roam-poi-object__meta">#{{ s.row.id }} · {{ s.row.cityCode || '城市码待核对' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="90" align="center">
        <template slot-scope="s">{{ poiTypeText(s.row.type) }}</template>
      </el-table-column>
      <el-table-column label="商家" width="160">
        <template slot-scope="s">
          <span v-if="hasValue(s.row.merchantId)">{{ s.row.merchantName || '商家名称待核对' }} <span class="roam-poi-muted">(#{{ s.row.merchantId }})</span></span>
          <span v-else class="roam-poi-muted">无关联商家</span>
        </template>
      </el-table-column>
      <el-table-column label="奖励" width="140">
        <template slot-scope="s">
          {{ formatXp(s.row.xp) }}<span v-if="hasValue(s.row.couponTemplateId)" class="roam-poi-muted"> · 券模板 #{{ s.row.couponTemplateId }}</span>
          <span v-else class="roam-poi-muted"> · 无券模板</span>
        </template>
      </el-table-column>
      <el-table-column label="简介" min-width="180" show-overflow-tooltip>
        <template slot-scope="s">{{ s.row.description || '—' }}</template>
      </el-table-column>
      <el-table-column label="玩家可见性" width="128" align="center">
        <template slot-scope="s">
          <span class="roam-poi-status" :class="`roam-poi-status--${statusInfo(s.row.status).key}`">{{ statusInfo(s.row.status).label }}</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="150" align="center" />
      <el-table-column label="操作" width="142" align="center">
        <template slot-scope="s">
          <el-button
            v-if="canTransition(s.row, 0)"
            v-hasPermi="['business:roamPoi:edit']"
            type="text"
            size="mini"
            :loading="pendingActionId === s.row.id"
            @click="onOffline(s.row)"
          >下架</el-button>
          <el-button
            v-else-if="canTransition(s.row, 1)"
            v-hasPermi="['business:roamPoi:edit']"
            type="text"
            size="mini"
            :loading="pendingActionId === s.row.id"
            @click="onOnline(s.row)"
          >上架</el-button>
          <span v-else class="roam-poi-muted">仅可核对</span>
        </template>
      </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script>
import { adminPoiList, poiOnline, poiOffline, roamPoiApplications, approveRoamPoiApplication, rejectRoamPoiApplication } from '@/api/business/roamPoi'
import CyEmptyState from '@/components/CyEmptyState'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

const CANONICAL_PATH = '/club/roam-poi'

const createQueryParams = () => ({ status: null, cityCode: '', merchantId: '' })

function normalizeStatus(value) {
  if (value === 0 || value === '0') return 0
  if (value === 1 || value === '1') return 1
  return null
}

function normalizeText(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return ''
  return String(value).trim()
}

function normalizeMerchantId(value) {
  const merchantId = normalizeText(value)
  return /^[1-9]\d*$/.test(merchantId) ? merchantId : ''
}

function normalizeFilters(filters) {
  return {
    status: normalizeStatus(filters.status),
    cityCode: normalizeText(filters.cityCode),
    merchantId: normalizeMerchantId(filters.merchantId)
  }
}

function routeQuery(filters) {
  const normalized = normalizeFilters(filters)
  const query = {}
  if (normalized.status !== null) query.status = String(normalized.status)
  if (normalized.cityCode) query.cityCode = normalized.cityCode
  if (normalized.merchantId) query.merchantId = normalized.merchantId
  return query
}

export default {
  name: 'RoamPoiAdmin',
  components: { CyEmptyState, CyFilterBar, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      list: [],
      listError: false,
      updatedAt: '',
      pendingActionId: null,
      applicationLoading: false,
      applicationError: false,
      applications: [],
      pendingApplicationId: null,
      queryForm: createQueryParams(),
      queryParams: createQueryParams(),
      listRequestId: 0
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.status !== null && {
          key: 'status',
          label: '玩家可见性',
          value: this.queryParams.status === 1 ? '上架（玩家可见）' : '下架（玩家不可见）'
        },
        this.queryParams.cityCode && { key: 'cityCode', label: '城市码', value: this.queryParams.cityCode },
        this.queryParams.merchantId && { key: 'merchantId', label: '商家 ID', value: this.queryParams.merchantId }
      ].filter(Boolean)
    },
    visibilitySummary() {
      return [
        { key: 'online', label: '上架', count: this.countBy(1), description: '玩家侧可见' },
        { key: 'offline', label: '下架', count: this.countBy(0), description: '玩家侧不可见，发现流水保留' },
        { key: 'unknown', label: '待核对', count: this.list.length - this.countBy(1) - this.countBy(0), description: '不提供状态变更' }
      ]
    }
  },
  created() {
    this.initializePage()
  },
  watch: {
    '$route.fullPath'() {
      this.initializePage()
    }
  },
  methods: {
    // 审核材料的字典。★ 未配的档位一律显示成「未配」而不是留空 ——
    // 空白会被审核员当成「这项不适用」,而它其实是「商家没配,玩家会卡死在这一关」。
    interactionTypeText(value) {
      return { city_story_card: '城市故事卡', hidden_menu: '隐藏菜单', photo_spot: '拍照点', qr_checkin: '到店扫码' }[value] || '未配玩法形态'
    },
    validationMethodText(value) {
      return { 0: '无需验证', 1: '文字暗号', 2: '拍照打卡', 3: '选项问答', 4: '到店扫码', 5: 'GPS 到达' }[Number(value)] || '未配完成方式'
    },
    merchantStatusText(merchant) {
      if (!merchant) return '未知'
      if (Number(merchant.accountStatus) === 2) return '已停用'
      return { 0: '待审核', 1: '已通过', 2: '已驳回' }[Number(merchant.status)] || '未知'
    },
    initializePage() {
      const query = routeQuery(this.$route.query || {})
      if (String(this.$route.path || '').replace(/\/+$/, '') !== CANONICAL_PATH) {
        this.$router.replace({ name: 'clubRoamPoi', query })
        return
      }
      this.applyRouteQuery(query)
      return Promise.all([this.getList(), this.getApplications()])
    },
    getApplications() {
      this.applicationLoading = true
      this.applicationError = false
      return roamPoiApplications({ auditStatus: 0 })
        .then(response => { this.applications = response.data || [] })
        .catch(() => {
          this.applications = []
          this.applicationError = true
        })
        .finally(() => { this.applicationLoading = false })
    },
    applicationTypeText(type) {
      return Number(type) === 2 ? '节点认领' : '据点投放'
    },
    applicationStatusText(status) {
      if (Number(status) === 0) return '待审核'
      if (Number(status) === 1) return '已通过'
      if (Number(status) === 2) return '已驳回'
      return '处理中'
    },
    approveApplication(row) {
      if (this.pendingApplicationId !== null) return Promise.resolve()
      return this.$modal.confirm(`确认通过「${row.name || row.id}」？`)
        .then(() => {
          this.pendingApplicationId = row.id
          return approveRoamPoiApplication(row.id)
        })
        .then(() => {
          this.$modal.msgSuccess('申请已通过，据点归属已写入。')
          return Promise.all([this.getApplications(), this.getList()])
        })
        .finally(() => { this.pendingApplicationId = null })
    },
    rejectApplication(row) {
      if (this.pendingApplicationId !== null) return Promise.resolve()
      return this.$prompt('请填写驳回原因', '驳回据点申请', {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputPattern: /\S+/,
        inputErrorMessage: '驳回原因不能为空'
      }).then(({ value }) => {
        this.pendingApplicationId = row.id
        return rejectRoamPoiApplication(row.id, value.trim())
      }).then(() => {
        this.$modal.msgSuccess('申请已驳回。')
        return this.getApplications()
      }).finally(() => { this.pendingApplicationId = null })
    },
    applyRouteQuery(query) {
      const normalized = normalizeFilters(query)
      this.queryForm = Object.assign(createQueryParams(), normalized)
      this.queryParams = Object.assign(createQueryParams(), normalized)
    },
    getList() {
      const requestId = ++this.listRequestId
      const params = this.requestParams()
      this.loading = true;
      this.listError = false
      return adminPoiList(params)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.list = response.data || []
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.list = []
          this.updatedAt = ''
          this.listError = true
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    requestParams() {
      const params = {}
      if (this.queryParams.status !== null) params.status = this.queryParams.status
      if (this.queryParams.cityCode) params.cityCode = this.queryParams.cityCode
      if (this.queryParams.merchantId) params.merchantId = this.queryParams.merchantId
      return params
    },
    handleQuery() {
      const normalized = normalizeFilters(this.queryForm)
      this.queryForm = Object.assign(createQueryParams(), normalized)
      this.queryParams = Object.assign(createQueryParams(), normalized)
      return this.getList()
    },
    resetQuery() {
      this.queryForm = createQueryParams()
      this.queryParams = createQueryParams()
      return this.getList()
    },
    clearFilter(key) {
      const emptyValue = key === 'status' ? null : ''
      this.queryForm[key] = emptyValue
      this.queryParams[key] = emptyValue
      return this.getList()
    },
    countBy(status) {
      return this.list.filter(poi => normalizeStatus(poi.status) === status).length
    },
    statusInfo(status) {
      const normalizedStatus = normalizeStatus(status)
      if (normalizedStatus === 1) return { key: 'online', label: '上架 · 玩家可见' }
      if (normalizedStatus === 0) return { key: 'offline', label: '下架 · 玩家不可见' }
      return { key: 'unknown', label: '状态待核对' }
    },
    poiTypeText(type) {
      if (Number(type) === 2) return '商家据点'
      if (Number(type) === 1) return '官方据点'
      return '类型待核对'
    },
    formatXp(xp) {
      return xp === null || xp === undefined || xp === '' ? '未配置' : `XP ${xp}`
    },
    hasValue(value) {
      return value !== null && value !== undefined && value !== ''
    },
    canTransition(row, nextStatus) {
      const currentStatus = normalizeStatus(row.status)
      if (nextStatus === 1 && this.hasValue(row.auditStatus) && Number(row.auditStatus) !== 1) return false
      return (currentStatus === 0 || currentStatus === 1) && currentStatus !== nextStatus
    },
    onOffline(row) {
      return this.changeVisibility(row, 0)
    },
    onOnline(row) {
      return this.changeVisibility(row, 1)
    },
    changeVisibility(row, nextStatus) {
      if (!this.canTransition(row, nextStatus) || this.pendingActionId !== null) return Promise.resolve()
      const online = nextStatus === 1
      const confirmation = online
        ? `上架后玩家侧立即可见。确认上架「${row.name || row.id}」？`
        : `下架后玩家侧立即不可见，已产生的发现流水会保留。确认下架「${row.name || row.id}」？`
      return this.$modal.confirm(confirmation)
        .then(() => {
          this.pendingActionId = row.id
          return online ? poiOnline(row.id) : poiOffline(row.id)
        })
        .then(() => {
          this.$modal.msgSuccess(online ? '已上架，玩家侧将可见。' : '已下架，玩家侧将不可见。')
          return this.getList()
        })
        .catch(() => {
          if (this.pendingActionId === row.id) this.$modal.msgError('状态变更失败，请重新读取后再试。')
        })
        .finally(() => {
          if (this.pendingActionId === row.id) this.pendingActionId = null
        })
    }
  }
}
</script>

<style lang="scss" scoped>
.roam-poi-filter, .roam-poi-table-panel, .roam-poi-application-panel { margin-bottom: 16px; }
.roam-poi-visibility { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.roam-poi-visibility__item { min-width: 0; padding: 16px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-subtle); }
.roam-poi-visibility__item strong { display: block; margin-top: var(--cy-space-0-5); color: var(--cy-color-text-primary); font-size: 24px; font-variant-numeric: tabular-nums; }
.roam-poi-visibility__label, .roam-poi-visibility__item p { color: var(--cy-color-text-secondary); font-size: 12px; }
.roam-poi-visibility__item p { margin: var(--cy-legacy-5) 0 0; line-height: 1.55; }
.roam-poi-object__name { color: var(--cy-color-text-primary); font-weight: 700; }
.roam-poi-object__meta, .roam-poi-muted { color: var(--cy-color-text-tertiary); font-size: 12px; }
.roam-poi-object__meta { margin-top: 4px; }
.roam-poi-application__description { margin-top: 4px; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.5; }
.roam-poi-review__shop { font-size: 13px; line-height: 1.6; }
.roam-poi-review__play { margin-top: 6px; padding-top: 6px; border-top: 1px solid var(--cy-color-border-subtle); font-size: 13px; line-height: 1.6; }
.roam-poi-application__cover { width: 48px; height: 48px; margin-top: 6px; border-radius: 6px; }
.roam-poi-status { display: inline-flex; align-items: center; min-height: 24px; padding: 2px 8px; border: 1px solid var(--cy-color-border-strong); border-radius: 999px; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.4; }
.roam-poi-status--offline { border-style: dashed; }
.roam-poi-status--unknown { border-style: dotted; }

@media (max-width: 900px) {
  .roam-poi-visibility { grid-template-columns: 1fr; }
}
</style>

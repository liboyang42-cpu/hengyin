<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="漫游"
      title="漫游陪伴内容审核"
      description="路线故事与地点意义都是独立审核后的短内容；玩家侧没有通过件时会静默，不会回退到 POI 简介。"
      :updated-at="updatedAt"
    >
      <el-button
        slot="primary"
        v-hasPermi="['business:roamAiContent:add']"
        size="small"
        type="primary"
        icon="el-icon-plus"
        @click="openCreate"
      >新增内容</el-button>
      <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="getList">刷新列表</el-button>
    </cy-page-header>

    <section class="cy-panel roam-ai-filter" aria-label="漫游陪伴内容筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>定位审核范围</h2>
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
          <el-form-item label="内容类型" prop="contentType">
            <el-select v-model="queryForm.contentType" placeholder="全部类型" clearable style="width: 160px">
              <el-option label="路线故事" value="ROUTE_STORY" />
              <el-option label="地点意义" value="POI_MEANING" />
            </el-select>
          </el-form-item>
          <el-form-item label="审核状态" prop="auditStatus">
            <el-select v-model="queryForm.auditStatus" placeholder="全部状态" clearable style="width: 160px">
              <el-option label="待审" :value="0" />
              <el-option label="通过" :value="1" />
              <el-option label="驳回" :value="2" />
            </el-select>
          </el-form-item>
          <el-form-item label="POI ID" prop="poiId">
            <el-input v-model="queryForm.poiId" placeholder="roam_poi ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <section class="roam-ai-summary" aria-label="当前筛选结果的审核分布">
      <div v-for="item in auditSummary" :key="item.key" class="roam-ai-summary__item">
        <span class="roam-ai-summary__label">{{ item.label }}</span>
        <strong>{{ item.count }}</strong>
        <p>{{ item.description }}</p>
      </div>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="漫游陪伴内容列表暂时无法读取"
      description="没有保留旧列表作为当前状态。请检查网络或权限后重新读取。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <section v-else class="cy-panel roam-ai-table-panel" aria-label="漫游陪伴内容列表">
      <div class="cy-panel__heading">
        <div>
          <h2>当前筛选结果</h2>
          <p>{{ list.length }} 条内容；状态以这次接口回读为准。</p>
        </div>
      </div>
      <cy-empty-state
        v-if="!loading && !list.length"
        title="当前筛选下没有漫游陪伴内容"
        description="可以清除筛选后重新读取，或先新增一条待审内容。"
        action-text="清除筛选"
        @action="resetQuery"
      />
      <el-table v-else v-loading="loading" :data="list" size="small" border>
        <el-table-column label="内容" min-width="240">
          <template slot-scope="s">
            <div class="roam-ai-object__text">{{ s.row.contentText || '—' }}</div>
            <div class="roam-ai-object__meta">#{{ s.row.id }} · {{ s.row.contentKey || 'key 待核对' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="104" align="center">
          <template slot-scope="s">{{ contentTypeText(s.row.contentType) }}</template>
        </el-table-column>
        <el-table-column label="绑定 POI" width="120" align="center">
          <template slot-scope="s">
            <span v-if="hasValue(s.row.poiId)">#{{ s.row.poiId }}</span>
            <span v-else class="roam-ai-muted">不绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" width="120" align="center">
          <template slot-scope="s">
            <cy-status-tag :label="auditInfo(s.row.auditStatus).label" :tone="auditInfo(s.row.auditStatus).tone" />
          </template>
        </el-table-column>
        <el-table-column label="启用" width="96" align="center">
          <template slot-scope="s">{{ enabledText(s.row.enabled) }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="150" align="center">
          <template slot-scope="s">{{ s.row.updateTime || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="248" align="center">
          <template slot-scope="s">
            <el-button
              v-hasPermi="['business:roamAiContent:query']"
              type="text"
              size="mini"
              @click="openDetail(s.row)"
            >详情</el-button>
            <el-button
              v-hasPermi="['business:roamAiContent:edit']"
              type="text"
              size="mini"
              @click="openEdit(s.row)"
            >编辑</el-button>
            <el-button
              v-if="canAudit(s.row, 1)"
              v-hasPermi="['business:roamAiContent:audit']"
              type="text"
              size="mini"
              :loading="pendingActionId === s.row.id"
              @click="onPass(s.row)"
            >通过</el-button>
            <el-button
              v-if="canAudit(s.row, 2)"
              v-hasPermi="['business:roamAiContent:audit']"
              type="text"
              size="mini"
              :loading="pendingActionId === s.row.id"
              @click="onReject(s.row)"
            >驳回</el-button>
            <el-button
              v-hasPermi="['business:roamAiContent:remove']"
              type="text"
              size="mini"
              :loading="pendingActionId === s.row.id"
              @click="onRemove(s.row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog :title="dialogTitle" :visible.sync="dialogVisible" width="560px" append-to-body>
      <el-form ref="contentForm" :model="form" size="small" label-width="96px">
        <template v-if="dialogMode === 'create'">
          <el-form-item label="内容类型" prop="contentType">
            <el-select v-model="form.contentType" placeholder="选择内容类型" @change="onContentTypeChange">
              <el-option label="路线故事" value="ROUTE_STORY" />
              <el-option label="地点意义" value="POI_MEANING" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.contentType === 'ROUTE_STORY'" label="内容 key" prop="contentKey">
            <el-input v-model="form.contentKey" maxlength="64" placeholder="1-64 字，路线故事的业务 key" />
          </el-form-item>
          <el-form-item v-if="form.contentType === 'POI_MEANING'" label="绑定 POI" prop="poiId">
            <el-input v-model="form.poiId" placeholder="非商家 POI 的 ID；key 由后端按 poi-<id> 生成" />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="内容类型">
            <span class="roam-ai-readonly">{{ contentTypeText(form.contentType) }}</span>
          </el-form-item>
          <el-form-item label="内容 key">
            <span class="roam-ai-readonly">{{ form.contentKey || '—' }}</span>
          </el-form-item>
          <el-form-item label="绑定 POI">
            <span class="roam-ai-readonly">{{ hasValue(form.poiId) ? '#' + form.poiId : '不绑定' }}</span>
          </el-form-item>
          <el-form-item label="审核状态">
            <span class="roam-ai-readonly">{{ auditInfo(form.auditStatus).label }} · 只能在列表用审核动作改</span>
          </el-form-item>
        </template>
        <el-form-item label="内容文案" prop="contentText">
          <el-input
            v-model="form.contentText"
            type="textarea"
            :rows="3"
            maxlength="80"
            show-word-limit
            :placeholder="contentTextPlaceholder"
          />
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-select v-model="form.enabled" placeholder="选择启用状态">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
      </el-form>
      <p v-if="dialogMode === 'edit'" class="roam-ai-dialog__note">改动文案后该条会被后端重置为待审，需要重新走审核动作。</p>
      <div slot="footer">
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="submitForm">确定</el-button>
      </div>
    </el-dialog>

    <el-dialog title="漫游陪伴内容详情" :visible.sync="detailVisible" width="520px" append-to-body>
      <div v-if="detailLoading" class="roam-ai-detail-loading" v-loading="true" aria-label="正在读取详情"></div>
      <cy-detail-grid v-else-if="detail" :items="detailItems" />
      <cy-resource-state
        v-else
        kind="error"
        title="详情暂时无法读取"
        description="没有用列表里的旧值伪装成详情。请重试或检查权限。"
      />
    </el-dialog>
  </div>
</template>

<script>
import { listRoamAiContent, getRoamAiContent, addRoamAiContent, updateRoamAiContent, auditRoamAiContent, delRoamAiContent } from '@/api/business/roamAiContent'
import CyDetailGrid from '@/components/CyDetailGrid'
import CyEmptyState from '@/components/CyEmptyState'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const TYPE_ROUTE_STORY = 'ROUTE_STORY'
const TYPE_POI_MEANING = 'POI_MEANING'
const MAX_TEXT_LENGTH = 80
const MAX_KEY_LENGTH = 64

const createQueryParams = () => ({ contentType: null, auditStatus: null, poiId: '' })
const createForm = () => ({
  id: null,
  contentType: null,
  contentKey: '',
  poiId: '',
  contentText: '',
  originalContentText: '',
  enabled: 1,
  auditStatus: null
})

function normalizeContentType(value) {
  return value === TYPE_ROUTE_STORY || value === TYPE_POI_MEANING ? value : null
}

function normalizeAuditStatus(value) {
  if (value === 0 || value === '0') return 0
  if (value === 1 || value === '1') return 1
  if (value === 2 || value === '2') return 2
  return null
}

function normalizeEnabled(value) {
  if (value === 0 || value === '0') return 0
  if (value === 1 || value === '1') return 1
  return null
}

function normalizeText(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return ''
  return String(value).trim()
}

function normalizeId(value) {
  const id = normalizeText(value)
  return /^[1-9]\d*$/.test(id) ? id : ''
}

function normalizeFilters(filters) {
  return {
    contentType: normalizeContentType(filters.contentType),
    auditStatus: normalizeAuditStatus(filters.auditStatus),
    poiId: normalizeId(filters.poiId)
  }
}

export default {
  name: 'RoamAiContentAdmin',
  components: { CyDetailGrid, CyEmptyState, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      list: [],
      listError: false,
      updatedAt: '',
      pendingActionId: null,
      queryForm: createQueryParams(),
      queryParams: createQueryParams(),
      listRequestId: 0,
      dialogVisible: false,
      dialogMode: 'create',
      submitting: false,
      form: createForm(),
      detailVisible: false,
      detail: null,
      detailLoading: false,
      detailRequestId: 0
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.contentType && {
          key: 'contentType',
          label: '内容类型',
          value: this.contentTypeText(this.queryParams.contentType)
        },
        this.queryParams.auditStatus !== null && {
          key: 'auditStatus',
          label: '审核状态',
          value: this.auditInfo(this.queryParams.auditStatus).label
        },
        this.queryParams.poiId && { key: 'poiId', label: 'POI ID', value: this.queryParams.poiId }
      ].filter(Boolean)
    },
    auditSummary() {
      return [
        { key: 'pending', label: '待审', count: this.countBy(0), description: '玩家侧不下发' },
        { key: 'passed', label: '通过', count: this.countBy(1), description: '启用后玩家侧可见' },
        { key: 'rejected', label: '驳回', count: this.countBy(2), description: '玩家侧不下发，可改文案再审' },
        { key: 'unknown', label: '待核对', count: this.list.length - this.countBy(0) - this.countBy(1) - this.countBy(2), description: '不提供审核动作' }
      ]
    },
    dialogTitle() {
      return this.dialogMode === 'create' ? '新增漫游陪伴内容' : '编辑漫游陪伴内容'
    },
    // 占位符写法带 {{ }},放在模板属性里会被当插值解析(vue-loader 直接报错),只能绑定字符串。
    contentTextPlaceholder() {
      return `1-${MAX_TEXT_LENGTH} 字；路线故事的指标由服务端审核规则校验`
    },
    detailItems() {
      const detail = this.detail || {}
      return [
        { key: 'id', label: '内容 ID', value: this.hasValue(detail.id) ? `#${detail.id}` : '—' },
        { key: 'contentType', label: '内容类型', value: this.contentTypeText(detail.contentType) },
        { key: 'contentKey', label: '内容 key', value: detail.contentKey || '—' },
        { key: 'poiId', label: '绑定 POI', value: this.hasValue(detail.poiId) ? `#${detail.poiId}` : '不绑定' },
        { key: 'auditStatus', label: '审核状态', value: this.auditInfo(detail.auditStatus).label },
        { key: 'enabled', label: '启用', value: this.enabledText(detail.enabled) },
        { key: 'contentText', label: '内容文案', value: detail.contentText || '—', span: 2 },
        { key: 'createTime', label: '创建时间', value: detail.createTime || '—' },
        { key: 'updateTime', label: '更新时间', value: detail.updateTime || '—' }
      ]
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      const requestId = ++this.listRequestId
      const params = this.requestParams()
      this.loading = true
      this.listError = false
      return listRoamAiContent(params)
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
      if (this.queryParams.contentType) params.contentType = this.queryParams.contentType
      if (this.queryParams.auditStatus !== null) params.auditStatus = this.queryParams.auditStatus
      if (this.queryParams.poiId) params.poiId = this.queryParams.poiId
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
      const emptyValue = key === 'poiId' ? '' : null
      this.queryForm[key] = emptyValue
      this.queryParams[key] = emptyValue
      return this.getList()
    },
    countBy(auditStatus) {
      return this.list.filter(row => normalizeAuditStatus(row.auditStatus) === auditStatus).length
    },
    auditInfo(auditStatus) {
      const normalized = normalizeAuditStatus(auditStatus)
      if (normalized === 0) return { key: 'pending', label: '待审', tone: 'warning' }
      if (normalized === 1) return { key: 'passed', label: '通过', tone: 'success' }
      if (normalized === 2) return { key: 'rejected', label: '驳回', tone: 'danger' }
      return { key: 'unknown', label: '状态待核对', tone: 'neutral' }
    },
    contentTypeText(contentType) {
      if (contentType === TYPE_ROUTE_STORY) return '路线故事'
      if (contentType === TYPE_POI_MEANING) return '地点意义'
      return '类型待核对'
    },
    enabledText(enabled) {
      const normalized = normalizeEnabled(enabled)
      if (normalized === 1) return '启用'
      if (normalized === 0) return '停用'
      return '待核对'
    },
    hasValue(value) {
      return value !== null && value !== undefined && value !== ''
    },
    openCreate() {
      this.form = createForm()
      this.dialogMode = 'create'
      this.dialogVisible = true
    },
    openEdit(row) {
      const contentText = normalizeText(row.contentText || '')
      this.form = Object.assign(createForm(), {
        id: row.id,
        contentType: row.contentType,
        contentKey: row.contentKey,
        poiId: this.hasValue(row.poiId) ? row.poiId : '',
        contentText,
        originalContentText: contentText,
        enabled: normalizeEnabled(row.enabled),
        auditStatus: row.auditStatus
      })
      this.dialogMode = 'edit'
      this.dialogVisible = true
    },
    onContentTypeChange() {
      this.form.contentKey = ''
      this.form.poiId = ''
    },
    openDetail(row) {
      const requestId = ++this.detailRequestId
      this.detail = null
      this.detailLoading = true
      this.detailVisible = true
      return getRoamAiContent(row.id)
        .then(response => {
          if (requestId !== this.detailRequestId) return
          this.detail = response.data || null
        })
        .catch(() => {
          if (requestId !== this.detailRequestId) return
          this.detail = null
        })
        .finally(() => {
          if (requestId === this.detailRequestId) this.detailLoading = false
        })
    },
    // 只送后端允许编辑的列;审核态/类型/key/poiId 一律不进请求体。
    editPayload() {
      const payload = { id: this.form.id }
      const text = normalizeText(this.form.contentText)
      // 后端把 contentText 非空视为文案发生变化并重置审核态;只改启用状态时不要重复提交原文案。
      if (text && text !== this.form.originalContentText) payload.contentText = text
      const enabled = normalizeEnabled(this.form.enabled)
      if (enabled !== null) payload.enabled = enabled
      return payload
    },
    createPayload() {
      const payload = {
        contentType: normalizeContentType(this.form.contentType),
        contentText: normalizeText(this.form.contentText),
        enabled: normalizeEnabled(this.form.enabled)
      }
      if (payload.contentType === TYPE_POI_MEANING) {
        payload.poiId = Number(normalizeId(this.form.poiId))
      } else {
        payload.contentKey = normalizeText(this.form.contentKey)
      }
      return payload
    },
    validateForm() {
      const text = normalizeText(this.form.contentText)
      if (this.dialogMode === 'create') {
        if (!normalizeContentType(this.form.contentType)) return '请选择内容类型。'
      }
      if (!text || text.length > MAX_TEXT_LENGTH) return `内容文案需为 1-${MAX_TEXT_LENGTH} 字。`
      if (normalizeEnabled(this.form.enabled) === null) return '请选择启用状态。'
      const contentType = normalizeContentType(this.form.contentType)
      if (this.dialogMode !== 'create') return null
      if (contentType === TYPE_POI_MEANING) {
        if (!normalizeId(this.form.poiId)) return '地点意义必须绑定有效的非商家 POI ID。'
        return null
      }
      const key = normalizeText(this.form.contentKey)
      if (!key || key.length > MAX_KEY_LENGTH) return `路线故事的内容 key 需为 1-${MAX_KEY_LENGTH} 字。`
      return null
    },
    submitForm() {
      if (this.submitting) return Promise.resolve()
      const invalid = this.validateForm()
      if (invalid) {
        this.$modal.msgError(invalid)
        return Promise.resolve()
      }
      const creating = this.dialogMode === 'create'
      this.submitting = true
      const request = creating ? addRoamAiContent(this.createPayload()) : updateRoamAiContent(this.editPayload())
      return request
        .then(() => {
          this.$modal.msgSuccess(creating ? '已新增，条目处于待审状态。' : '已保存，改动文案的条目会回到待审。')
          this.dialogVisible = false
          return this.getList()
        })
        // request.js 已展示后端具体错误或网络错误；这里不再重复弹一个无信息量的通用错误。
        .catch(() => {})
        .finally(() => {
          this.submitting = false
        })
    },
    canAudit(row, nextStatus) {
      const current = normalizeAuditStatus(row.auditStatus)
      return current !== null && current !== nextStatus
    },
    onPass(row) {
      return this.changeAudit(row, 1)
    },
    onReject(row) {
      return this.changeAudit(row, 2)
    },
    changeAudit(row, nextStatus) {
      if (!this.canAudit(row, nextStatus) || this.pendingActionId !== null) return Promise.resolve()
      const passing = nextStatus === 1
      const confirmation = passing
        ? `通过后该条内容在启用状态下会下发给玩家。确认通过 #${row.id}？`
        : `驳回后玩家侧不再下发该条内容。确认驳回 #${row.id}？`
      return this.$modal.confirm(confirmation)
        .then(() => {
          this.pendingActionId = row.id
          return auditRoamAiContent(row.id, nextStatus)
        })
        .then(() => {
          this.$modal.msgSuccess(passing ? '已通过。' : '已驳回。')
          return this.getList()
        })
        // request.js 已展示后端具体错误或网络错误；这里不再重复弹一个无信息量的通用错误。
        .catch(() => {})
        .finally(() => {
          if (this.pendingActionId === row.id) this.pendingActionId = null
        })
    },
    onRemove(row) {
      if (this.pendingActionId !== null) return Promise.resolve()
      return this.$modal.confirm(`删除后该条内容不可恢复，玩家侧将不再有这条陪伴文案。确认删除 #${row.id}？`)
        .then(() => {
          this.pendingActionId = row.id
          return delRoamAiContent(row.id)
        })
        .then(() => {
          this.$modal.msgSuccess('已删除。')
          return this.getList()
        })
        // request.js 已展示后端具体错误或网络错误；这里不再重复弹一个无信息量的通用错误。
        .catch(() => {})
        .finally(() => {
          if (this.pendingActionId === row.id) this.pendingActionId = null
        })
    }
  }
}
</script>

<style lang="scss" scoped>
.roam-ai-filter, .roam-ai-table-panel { margin-bottom: 16px; }
.roam-ai-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.roam-ai-summary__item { min-width: 0; padding: 16px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-subtle); }
.roam-ai-summary__item strong { display: block; margin-top: var(--cy-space-0-5); color: var(--cy-color-text-primary); font-size: 24px; font-variant-numeric: tabular-nums; }
.roam-ai-summary__label, .roam-ai-summary__item p { color: var(--cy-color-text-secondary); font-size: 12px; }
.roam-ai-summary__item p { margin: var(--cy-legacy-5) 0 0; line-height: 1.55; }
.roam-ai-object__text { color: var(--cy-color-text-primary); font-weight: 700; }
.roam-ai-object__meta, .roam-ai-muted { color: var(--cy-color-text-tertiary); font-size: 12px; }
.roam-ai-object__meta { margin-top: 4px; }
.roam-ai-readonly { color: var(--cy-color-text-secondary); font-size: 13px; }
.roam-ai-dialog__note { margin: 0; color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.55; }
.roam-ai-detail-loading { min-height: 96px; }

@media (max-width: 900px) {
  .roam-ai-summary { grid-template-columns: 1fr; }
}
</style>

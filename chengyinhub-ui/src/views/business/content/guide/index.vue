<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="内容配置"
      title="玩法说明"
      description="维护小程序“玩法说明”入口的标题、摘要、排序和正文。保存后会影响后续展示；已发布说明仍被小程序读取，因此当前不提供物理删除。"
      :updated-at="updatedAt"
    >
      <el-button slot="primary" type="primary" icon="el-icon-plus" v-hasPermi="['business:infomation:add']" @click="handleAdd">新增说明</el-button>
    </cy-page-header>

    <section class="cy-panel guide-page__filters" aria-label="玩法说明筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>内容定位</h2>
          <p>按标题或副标题定位说明；排序值越大，越优先出现在小程序列表中。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="68px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="标题" prop="title">
            <el-input v-model.trim="queryForm.title" placeholder="输入精确标题" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="副标题" prop="subtitle">
            <el-input v-model.trim="queryForm.subtitle" placeholder="输入精确副标题" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <template #more>
            <el-form-item label="排序" prop="sortId">
              <el-input-number v-model="queryForm.sortId" :min="0" :max="2147483647" :precision="0" controls-position="right" />
            </el-form-item>
          </template>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="玩法说明暂时无法读取"
      description="请重新读取；系统不会把读取失败伪装成空列表。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <cy-data-table
      v-else
      :data="guideList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page="queryParams.pageNum"
      :limit="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前筛选下没有玩法说明"
      @refresh="getList"
      @pagination="handlePagination"
    >
      <template #guide="{ row }">
        <div class="guide-page__object">
          <strong>{{ row.title || `未命名说明 #${row.id}` }}</strong>
          <span>{{ row.subtitle || '未填写副标题' }}</span>
        </div>
      </template>
      <template #contents="{ row }">
        <span>{{ contentSummary(row.contents) }}</span>
      </template>
      <template #updatedAt="{ row }">
        <span>{{ row.updateTime || row.createTime || '未记录' }}</span>
      </template>
      <template #actions="{ row }">
        <el-button size="mini" type="text" icon="el-icon-edit" v-hasPermi="['business:infomation:edit']" @click="handleEdit(row)">编辑</el-button>
      </template>
    </cy-data-table>

    <el-dialog
      :title="form.id ? '编辑玩法说明' : '新增玩法说明'"
      :visible.sync="formOpen"
      width="780px"
      append-to-body
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form ref="guideForm" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model.trim="form.title" maxlength="100" show-word-limit placeholder="例如：城市定向怎么玩" />
        </el-form-item>
        <el-form-item label="副标题" prop="subtitle">
          <el-input v-model.trim="form.subtitle" maxlength="160" show-word-limit placeholder="帮助用户判断这条说明是否适用" />
        </el-form-item>
        <el-form-item label="排序" prop="sortId">
          <el-input-number v-model="form.sortId" :min="0" :max="2147483647" :precision="0" controls-position="right" />
          <p class="guide-page__field-note">数值越大，越优先显示。</p>
        </el-form-item>
        <el-form-item label="详细内容" prop="contents">
          <editor v-model="form.contents" :min-height="192" />
          <p class="guide-page__field-note">保存后，新的内容会被小程序的玩法说明列表和详情读取。</p>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="prepareSave">保存变更</el-button>
      </div>
    </el-dialog>

    <cy-confirm-action
      :visible.sync="confirmOpen"
      :title="form.id ? '确认保存玩法说明' : '确认新增玩法说明'"
      :impact="confirmImpact"
      severity="warning"
      :confirm-text="form.id ? '确认保存' : '确认新增'"
      reason-label="变更说明"
      reason-placeholder="说明本次内容变更的原因"
      :loading="saving"
      @confirm="submitConfirmed"
    />
  </div>
</template>

<script>
import { addInfomation, getInfomation, listInfomation, updateInfomation } from '@/api/business/infomation'
import CyConfirmAction from '@/components/CyConfirmAction'
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

const createQuery = () => ({ pageNum: 1, pageSize: 10, title: null, subtitle: null, sortId: null })
const createForm = source => Object.assign({ id: null, title: '', subtitle: '', sortId: 0, contents: '' }, source || {})

export default {
  name: 'ContentGuide',
  components: { CyConfirmAction, CyDataTable, CyFilterBar, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      saving: false,
      listError: false,
      total: 0,
      guideList: [],
      updatedAt: '',
      listRequestId: 0,
      detailRequestId: 0,
      queryForm: createQuery(),
      queryParams: createQuery(),
      formOpen: false,
      confirmOpen: false,
      pendingAction: '',
      form: createForm(),
      rules: {
        title: [{ required: true, message: '请填写标题', trigger: 'blur' }],
        sortId: [{ required: true, message: '请填写排序', trigger: 'change' }]
      },
      tableColumns: [
        { key: 'guide', label: '玩法说明', minWidth: 220, locked: true },
        { key: 'contents', label: '内容摘要', minWidth: 260, showOverflowTooltip: true },
        { key: 'sortId', label: '排序', width: 90, prop: 'sortId', align: 'right' },
        { key: 'updatedAt', label: '最后变更', width: 170, prop: 'updateTime' },
        { key: 'actions', label: '操作', width: 100, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.title && { key: 'title', label: '标题', value: this.queryParams.title },
        this.queryParams.subtitle && { key: 'subtitle', label: '副标题', value: this.queryParams.subtitle },
        this.queryParams.sortId !== null && this.queryParams.sortId !== undefined && { key: 'sortId', label: '排序', value: this.queryParams.sortId }
      ].filter(Boolean)
    },
    confirmImpact() {
      const title = this.form.title || '这条玩法说明'
      return this.form.id
        ? `保存“${title}”后，小程序后续读取会展示新内容；请确认本次变更范围与展示影响。`
        : `新增“${title}”后，它将进入小程序可读取的玩法说明列表；请确认本次展示影响。`
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      const requestId = ++this.listRequestId
      const params = Object.assign({}, this.queryParams)
      this.loading = true
      this.listError = false
      return listInfomation(params)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.guideList = response.rows || []
          this.total = response.total || 0
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.listError = true
          this.$modal.msgError('玩法说明读取失败，请稍后重试。')
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    handleQuery() {
      this.queryParams = Object.assign(createQuery(), {
        pageSize: this.queryParams.pageSize,
        title: this.queryForm.title || null,
        subtitle: this.queryForm.subtitle || null,
        sortId: this.queryForm.sortId === '' ? null : this.queryForm.sortId
      })
      return this.getList()
    },
    resetQuery() {
      this.queryForm = createQuery()
      this.queryParams = createQuery()
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
    handleAdd() {
      this.invalidateDetailRequest()
      this.resetForm()
      this.formOpen = true
    },
    handleEdit(row) {
      const requestId = ++this.detailRequestId
      return getInfomation(row.id)
        .then(response => {
          if (requestId !== this.detailRequestId) return
          this.form = createForm(response.data)
          this.formOpen = true
        })
        .catch(() => {
          if (requestId !== this.detailRequestId) return
          this.$modal.msgError('玩法说明详情读取失败，请重试。')
        })
    },
    prepareSave() {
      this.$refs.guideForm.validate(valid => {
        if (!valid) return
        this.pendingAction = 'save'
        this.confirmOpen = true
      })
    },
    submitConfirmed(reason) {
      const payload = {
        id: this.form.id,
        title: this.form.title,
        subtitle: this.form.subtitle,
        sortId: this.form.sortId,
        contents: this.form.contents,
        auditReason: reason
      }
      const request = payload.id ? updateInfomation(payload) : addInfomation(payload)
      this.saving = true
      return request
        .then(() => {
          this.$modal.msgSuccess(payload.id ? '玩法说明已更新' : '玩法说明已新增')
          this.formOpen = false
          this.confirmOpen = false
          return this.getList()
        })
        .catch(() => {})
        .finally(() => {
          this.saving = false
        })
    },
    resetForm() {
      this.invalidateDetailRequest()
      this.form = createForm()
      this.pendingAction = ''
      if (this.$refs.guideForm) this.$refs.guideForm.resetFields()
    },
    invalidateDetailRequest() {
      this.detailRequestId += 1
    },
    contentSummary(contents) {
      const plain = String(contents || '').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim()
      return plain || '未填写详细内容'
    }
  }
}
</script>

<style lang="scss" scoped>
.guide-page__filters { margin-bottom: 16px; }
.guide-page__object strong, .guide-page__object span { display: block; }
.guide-page__object strong { color: var(--cy-color-text-primary); }
.guide-page__object span, .guide-page__field-note { color: var(--cy-color-text-tertiary); }
.guide-page__field-note { margin: var(--cy-space-0-5) 0 0; font-size: 12px; line-height: 1.6; }
</style>

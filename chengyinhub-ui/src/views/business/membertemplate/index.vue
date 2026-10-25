<template>
  <div class="app-container cy-domain-page template-center">
    <cy-page-header
      eyebrow="供给与发布"
      title="模板中心"
      description="以用户模板为唯一真源管理内容、审核与上架状态；模板库只保留同步投影，不在这里形成第二套事实或审核入口。"
      :updated-at="updatedAt"
    />

    <section class="cy-panel template-center__filter" aria-label="模板筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>模板定位</h2>
          <p>按标题或发布者定位完整游戏内容；基础验证、高级配置与素材属于同一条用户模板，审核、上架和同步状态也以它为准。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="72px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="模板标题" prop="title">
            <el-input v-model="queryForm.title" placeholder="按标题检索" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="发布者" prop="memberId">
            <el-input v-model="queryForm.memberId" placeholder="精确用户 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <template #more>
            <el-form-item label="审核状态" prop="status">
              <el-select v-model="queryForm.status" clearable placeholder="全部审核状态">
                <el-option label="待审核" :value="0" />
                <el-option label="审核通过" :value="1" />
                <el-option label="审核驳回" :value="2" />
              </el-select>
            </el-form-item>
            <el-form-item label="同步状态" prop="isSync">
              <el-select v-model="queryForm.isSync" clearable placeholder="全部同步状态">
                <el-option label="已同步模板库" :value="1" />
                <el-option label="仅用户模板" :value="0" />
              </el-select>
            </el-form-item>
          </template>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="模板中心暂时无法读取"
      description="读取失败不会伪装成空列表；请检查权限或网络后重试。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />
    <cy-data-table
      v-else
      :data="templateList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page="queryParams.pageNum"
      :limit="queryParams.pageSize"
      :selectable="true"
      default-density="comfortable"
      empty-text="当前筛选下没有可管理的模板"
      @refresh="getList"
      @pagination="handlePagination"
      @selection-change="handleSelectionChange"
    >
      <template #toolbar-actions>
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete({})"
          v-hasPermi="['business:membertemplate:remove']"
        >删除选中</el-button>
      </template>
      <template #template="{ row }">
        <cy-entity-link
          v-if="canViewDetail"
          :name="row.title || '未命名模板'"
          entity-type="template"
          type-label="用户模板"
          :entity-id="row.id"
          :meta="templateMeta(row)"
          @open="goDetail(row)"
        />
        <div v-else class="template-center__identity">
          <strong>{{ row.title || '未命名模板' }}</strong>
          <span>{{ templateMeta(row) }}</span>
        </div>
      </template>
      <template #publisher="{ row }">
        <span>{{ row.memberId == null ? '未记录' : `用户 #${row.memberId}` }}</span>
      </template>
      <template #audit="{ row }">
        <div class="template-center__status">
          <cy-status-tag :label="auditLabel(row.status)" :tone="auditTone(row.status)" />
          <span v-if="Number(row.status) === 2 && row.reason">原因：{{ row.reason }}</span>
        </div>
      </template>
      <template #publish="{ row }">
        <cy-status-tag :label="publishLabel(row.publishStatus)" :tone="publishTone(row.publishStatus)" />
      </template>
      <template #projection="{ row }">
        <cy-status-tag :label="syncLabel(row.isSync)" :tone="syncTone(row.isSync)" />
      </template>
      <template #duration="{ row }">
        <span>{{ row.duration == null ? '未记录' : `${row.duration} 分钟` }}</span>
      </template>
      <template #gameplay="{ row }">
        <span>{{ contentSummary(row) }}</span>
      </template>
      <template #updated="{ row }">
        <span>{{ row.updateTime || row.createTime || '未记录' }}</span>
      </template>
      <template #actions="{ row }">
        <el-button size="mini" type="text" icon="el-icon-view" @click="goDetail(row)" v-hasPermi="['business:membertemplate:query']">详情</el-button>
        <el-button size="mini" type="text" icon="el-icon-edit" @click="handleEdit(row)" v-hasPermi="['business:membertemplate:edit']">编辑</el-button>
        <el-button size="mini" type="text" icon="el-icon-s-check" @click="submitReview(row)" v-hasPermi="['business:membertemplate:edit']">提交审核</el-button>
        <el-button size="mini" type="text" icon="el-icon-delete" class="template-center__danger-action" @click="handleDelete(row)" v-hasPermi="['business:membertemplate:remove']">删除</el-button>
      </template>
    </cy-data-table>

    <el-dialog title="编辑完整游戏模板" :visible.sync="editOpen" width="860px" append-to-body :close-on-click-modal="false" :close-on-press-escape="!editSaving" :show-close="!editSaving" :before-close="closeEditDialog">
      <el-alert
        class="template-center__edit-tip"
        type="info"
        :closable="false"
        title="保存会回到用户模板真源，并将关联的模板库投影与审核任务同步为待处理状态。"
      />
      <el-alert v-if="editError" type="error" :closable="false" :title="editError" class="template-center__edit-tip" />
      <el-form ref="editForm" :model="editForm" :rules="rules" :disabled="editSaving" label-width="124px" size="small">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="发布用户" prop="memberId"><el-input v-model="editForm.memberId" placeholder="用户 ID" :disabled="Boolean(editForm.id)" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模板标题" prop="title"><el-input v-model="editForm.title" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="难度" prop="difficulty"><el-input v-model="editForm.difficulty" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="时长" prop="duration"><el-input-number v-model="editForm.duration" :min="0" controls-position="right" class="template-center__full-width" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="参与人数" prop="players"><el-input v-model="editForm.players" placeholder="如：2-6 人" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="同步模板库" prop="isSync">
              <el-select v-model="editForm.isSync" class="template-center__full-width">
                <el-option label="仅用户模板" :value="0" />
                <el-option label="同步模板库投影" :value="1" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="基础完成方式" prop="validationMethod">
              <el-select v-model="editForm.validationMethod" clearable class="template-center__full-width">
                <el-option v-for="method in validationMethods" :key="method.value" :value="method.value" :label="method.label" />
                <el-option v-if="hasUnknownValidation" :value="editForm.validationMethod" :label="validationLabel(editForm.validationMethod)" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模板标签" prop="activityCategoryids">
              <el-select v-model="editCategoryIds" multiple filterable collapse-tags placeholder="选择玩法标签" class="template-center__full-width">
                <el-option v-for="category in categoryOptions" :key="category.id" :label="category.categoryName" :value="String(category.id)" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="评分" prop="rating"><el-input v-model="editForm.rating" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="使用人数" prop="useNum"><el-input-number v-model="editForm.useNum" :min="0" controls-position="right" class="template-center__full-width" /></el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="封面地址" prop="imgUrl"><el-input v-model="editForm.imgUrl" placeholder="图片 URL" /></el-form-item>
        <el-form-item label="适用场景" prop="usageLocation"><el-input v-model="editForm.usageLocation" /></el-form-item>
        <el-form-item label="所需物料" prop="requiredMaterials"><el-input v-model="editForm.requiredMaterials" /></el-form-item>
        <el-form-item label="模板介绍" prop="description"><el-input v-model="editForm.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="规则说明" prop="ruleInstructions"><el-input v-model="editForm.ruleInstructions" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="验证问题" prop="questionName"><el-input v-model="editForm.questionName" /></el-form-item>
        <el-form-item label="验证答案" prop="questionAnswer"><el-input v-model="editForm.questionAnswer" /></el-form-item>
        <template-content-fields v-model="editForm" :disabled="editSaving" />
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="editSaving" :disabled="editSaving" @click="submitEdit">保存并重新送审</el-button>
        <el-button :disabled="editSaving" @click="closeEdit">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { delMembertemplate, getMembertemplate, listMembertemplate, submitMembertemplateReview, updateMembertemplate } from '@/api/business/membertemplate'
import { listCategory } from '@/api/system/category'
import { parseTime } from '@/utils/ruoyi'
import { checkPermi } from '@/utils/permission'
import CyDataTable from '@/components/CyDataTable'
import CyEntityLink from '@/components/CyEntityLink'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'
import TemplateContentFields from './TemplateContentFields.vue'
import { validationMethods, validationLabel, contentSummary, buildTemplatePayload } from './template-content'

const TEMPLATE_CATEGORY_TYPE = 4

const createFilters = () => ({
  title: null,
  memberId: null,
  status: null,
  isSync: null
})

const createQueryParams = (filters = {}) => Object.assign({
  pageNum: 1,
  pageSize: 10,
  delFlag: 0
}, createFilters(), filters)

const createTemplateForm = () => ({
  id: null,
  memberId: null,
  title: '',
  difficulty: '',
  duration: null,
  players: '',
  isSync: 0,
  validationMethod: null,
  activityCategoryids: '',
  imgUrl: '',
  usageLocation: '',
  requiredMaterials: '',
  description: '',
  ruleInstructions: '',
  questionName: '',
  questionAnswer: '',
  rating: '',
  useNum: null
})

function optionalStatus(value, allowed) {
  const number = Number(value)
  return allowed.includes(number) ? number : null
}

function positiveInteger(value, fallback) {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : fallback
}

export default {
  name: 'Membertemplate',
  components: { CyDataTable, CyEntityLink, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag, TemplateContentFields },
  data() {
    return {
      loading: false,
      listError: false,
      listRequestId: 0,
      total: 0,
      templateList: [],
      ids: [],
      multiple: true,
      updatedAt: '',
      queryForm: createFilters(),
      queryParams: createQueryParams(),
      editOpen: false,
      editRequestId: 0,
      editSaving: false,
      editError: '',
      validationMethods,
      editForm: createTemplateForm(),
      editCategoryIds: [],
      categoryOptions: [],
      rules: {
        memberId: [{ required: true, message: '发布用户不能为空', trigger: 'blur' }],
        title: [{ required: true, message: '模板标题不能为空', trigger: 'blur' }]
      },
      tableColumns: [
        { key: 'template', label: '模板', minWidth: 210, locked: true },
        { key: 'publisher', label: '发布者', width: 120 },
        { key: 'gameplay', label: '玩法与内容', minWidth: 230 },
        { key: 'audit', label: '审核状态', minWidth: 146 },
        { key: 'publish', label: '上架状态', width: 112 },
        { key: 'projection', label: '模板库投影', minWidth: 136 },
        { key: 'duration', label: '时长', width: 106, align: 'right' },
        { key: 'difficulty', label: '难度', width: 112, prop: 'difficulty', showOverflowTooltip: true },
        { key: 'updated', label: '最近更新', minWidth: 164 },
        { key: 'actions', label: '操作', width: 268, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    hasUnknownValidation() {
      const value = this.editForm.validationMethod
      return value !== null && value !== undefined && value !== ''
        && !validationMethods.some(method => String(method.value) === String(value))
    },
    canViewDetail() {
      return checkPermi(['business:membertemplate:query'])
    },
    activeFilters() {
      return [
        this.queryParams.title && { key: 'title', label: '模板标题', value: this.queryParams.title },
        this.queryParams.memberId && { key: 'memberId', label: '发布者', value: `用户 #${this.queryParams.memberId}` },
        this.queryParams.status !== null && { key: 'status', label: '审核状态', value: this.auditLabel(this.queryParams.status) },
        this.queryParams.isSync !== null && { key: 'isSync', label: '同步状态', value: this.syncLabel(this.queryParams.isSync) }
      ].filter(Boolean)
    }
  },
  created() {
    this.hydrateFromRoute(this.$route.query)
    this.getList()
  },
  beforeRouteUpdate(to, from, next) {
    this.closeEdit()
    this.hydrateFromRoute(to.query)
    this.getList()
    next()
  },
  beforeDestroy() {
    this.listRequestId += 1
    this.closeEdit()
  },
  methods: {
    contentSummary,
    validationLabel,
    closeEditDialog(done) {
      if (this.editSaving) return
      this.closeEdit()
      done()
    },
    closeEdit() {
      this.editRequestId += 1
      this.editOpen = false
      this.editSaving = false
      this.editError = ''
    },
    hydrateFromRoute(query = {}) {
      const status = optionalStatus(query.status, [0, 1, 2])
      const isSync = optionalStatus(query.isSync, [0, 1])
      this.queryForm = {
        title: query.title || null,
        memberId: query.memberId || null,
        status,
        isSync
      }
      this.queryParams = createQueryParams(Object.assign({}, this.queryForm, {
        pageNum: positiveInteger(query.pageNum, 1),
        pageSize: positiveInteger(query.pageSize, 10)
      }))
    },
    getList() {
      const requestId = ++this.listRequestId
      const requestParams = Object.assign({}, this.queryParams)
      this.loading = true
      this.listError = false
      return listMembertemplate(requestParams)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.templateList = response.rows || []
          this.total = response.total || 0
          this.updatedAt = parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.templateList = []
          this.total = 0
          this.listError = true
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    handleQuery() {
      this.queryParams = createQueryParams(Object.assign({}, this.queryForm, {
        pageNum: 1,
        pageSize: this.queryParams.pageSize
      }))
      return this.getList()
    },
    resetQuery() {
      this.queryForm = createFilters()
      this.queryParams = createQueryParams({ pageSize: this.queryParams.pageSize })
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
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.multiple = !selection.length
    },
    templateMeta(row) {
      const details = []
      if (row.duration != null) details.push(`${row.duration} 分钟`)
      if (row.difficulty) details.push(row.difficulty)
      return details.length ? details.join(' · ') : '查看模板详情'
    },
    auditLabel(status) {
      if (Number(status) === 1) return '审核通过'
      if (Number(status) === 2) return '审核驳回'
      return '待审核'
    },
    auditTone(status) {
      if (Number(status) === 1) return 'success'
      if (Number(status) === 2) return 'danger'
      return 'warning'
    },
    publishLabel(status) {
      return Number(status) === 1 ? '已上架' : '未上架'
    },
    publishTone(status) {
      return Number(status) === 1 ? 'success' : 'neutral'
    },
    syncLabel(isSync) {
      return Number(isSync) === 1 ? '已同步模板库' : '仅用户模板'
    },
    syncTone(isSync) {
      return Number(isSync) === 1 ? 'brand' : 'neutral'
    },
    goDetail(row) {
      this.$router.push({ name: 'membertemplateDetail', query: { id: row.id } })
    },
    loadCategoryOptions() {
      return listCategory({ type: TEMPLATE_CATEGORY_TYPE, pageSize: 1000 })
        .then(response => {
          this.categoryOptions = response.rows || response.data || []
        })
        .catch(() => {
          this.categoryOptions = []
        })
    },
    handleEdit(row) {
      if (this.editSaving) return Promise.resolve()
      const id = row.id
      const requestId = ++this.editRequestId
      this.editError = ''
      return getMembertemplate(id)
        .then(response => {
          if (requestId !== this.editRequestId) return
          if (!response.data || String(response.data.id) !== String(id)) throw new Error('模板身份不匹配')
          this.editForm = Object.assign(createTemplateForm(), response.data)
          this.editCategoryIds = String(this.editForm.activityCategoryids || '')
            .split(',')
            .filter(id => id.trim())
            .map(id => id.trim())
          this.editSaving = false
          this.editOpen = true
          return this.loadCategoryOptions()
        })
        .catch(() => {
          if (requestId !== this.editRequestId) return
          this.$modal.msgError('模板详情读取失败，请稍后重试。')
        })
    },
    submitEdit() {
      if (this.editSaving || !this.editOpen) return
      return this.$refs.editForm.validate(valid => {
        if (!valid || this.editSaving) return
        let payload
        try { payload = buildTemplatePayload(this.editForm, this.editCategoryIds) }
        catch (error) { this.editError = error.message; return }
        const requestId = this.editRequestId
        this.editSaving = true
        this.editError = ''
        return updateMembertemplate(payload).then(() => {
          if (requestId !== this.editRequestId) return
          this.$modal.msgSuccess('模板已更新并重新进入审核')
          this.editOpen = false
          this.getList()
        }).catch(() => {
          if (requestId !== this.editRequestId) return
          this.editError = '未取得保存成功结果，输入已保留。请先重新读取模板核对结果，不要连续重复提交。'
        }).finally(() => {
          if (requestId === this.editRequestId) this.editSaving = false
        })
      })
    },
    submitReview(row) {
      const id = row && row.id
      if (!id) return
      this.$modal.confirm(`确认将模板 #${id} 提交统一审核？`).then(() => {
        return submitMembertemplateReview(id)
      }).then(() => {
        this.$modal.msgSuccess('已提交统一审核')
        this.$router.push({ name: 'Audit', query: { bizType: 'TEMPLATE', bizId: id } })
      }).catch(() => {})
    },
    handleDelete(row) {
      const ids = row && row.id ? row.id : this.ids
      if (!ids || (Array.isArray(ids) && !ids.length)) return
      this.$modal.confirm(`是否确认删除模板 ${Array.isArray(ids) ? ids.join(', ') : ids}？`).then(() => {
        return delMembertemplate(ids)
      }).then(() => {
        this.$modal.msgSuccess('模板已删除')
        this.getList()
      }).catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
.template-center__filter { margin-bottom: var(--cy-space-4); }
.template-center__status { display: grid; gap: var(--cy-space-1); }
.template-center__status span { color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.5; }
.template-center__identity { display: grid; gap: var(--cy-space-1); }
.template-center__identity strong { color: var(--cy-color-text-primary); }
.template-center__identity span { color: var(--cy-color-text-tertiary); font-size: 12px; }
.template-center__danger-action { color: var(--cy-color-status-danger); }
.template-center__edit-tip { margin-bottom: var(--cy-space-4); }
.template-center__full-width { width: 100%; }
</style>

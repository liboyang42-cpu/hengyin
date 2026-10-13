<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="配置中心"
      title="礼物目录与定价"
      description="维护 C 端可赠送的礼物、积分价格与可用状态。停用会立即阻止后续赠礼，已经发生的赠礼记录和积分明细不会被改写。"
      :updated-at="updatedAt"
    >
      <el-button slot="primary" type="primary" icon="el-icon-plus" v-hasPermi="['business:gift:add']" @click="handleAdd">新增礼物</el-button>
    </cy-page-header>

    <section class="cy-panel gift-catalog__filters" aria-label="礼物目录筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>目录定位</h2>
          <p>按礼物名称或可用状态缩小范围；积分价格仅在编辑时变更，并写入后台操作日志。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="68px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="礼物名称" prop="giftName">
            <el-input v-model="queryForm.giftName" placeholder="输入礼物名称" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="可用状态" prop="status">
            <el-select v-model="queryForm.status" placeholder="全部状态" clearable style="width: 136px">
              <el-option label="可赠送" :value="1" />
              <el-option label="已停用" :value="2" />
            </el-select>
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="礼物目录暂时无法读取"
      description="请刷新重试；系统不会把读取失败伪装成空目录。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <cy-data-table
      v-else
      :data="giftList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page="queryParams.pageNum"
      :limit="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前筛选下没有礼物目录项"
      @refresh="getList"
      @pagination="handlePagination"
    >
      <template #gift="{ row }">
        <div class="gift-catalog__object">
          <el-image v-if="row.picUrl" :src="row.picUrl" fit="cover" :preview-src-list="[row.picUrl]" class="gift-catalog__image" />
          <div>
            <strong>{{ row.giftName || `未命名礼物 #${row.id}` }}</strong>
            <span>{{ row.contents || '未填写礼物说明' }}</span>
          </div>
        </div>
      </template>
      <template #price="{ row }">
        <strong class="gift-catalog__points">{{ formatPoints(row.point) }}</strong>
      </template>
      <template #status="{ row }">
        <cy-status-tag :label="statusLabel(row)" :tone="statusTone(row)" />
      </template>
      <template #updatedAt="{ row }">
        <span>{{ row.updateTime || row.createTime || '未记录' }}</span>
      </template>
      <template #actions="{ row }">
        <el-button size="mini" type="text" icon="el-icon-edit" v-hasPermi="['business:gift:edit']" @click="handleEdit(row)">编辑</el-button>
        <el-button
          v-if="canToggle(row)"
          size="mini"
          type="text"
          :icon="isAvailable(row) ? 'el-icon-video-pause' : 'el-icon-video-play'"
          v-hasPermi="['business:gift:edit']"
          @click="handleToggleStatus(row)"
        >{{ isAvailable(row) ? '停用' : '启用' }}</el-button>
      </template>
    </cy-data-table>

    <el-dialog :title="form.id ? '编辑礼物目录项' : '新增礼物目录项'" :visible.sync="formOpen" width="640px" append-to-body :close-on-click-modal="false" @closed="resetForm">
      <el-form ref="giftForm" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="礼物名称" prop="giftName">
          <el-input v-model.trim="form.giftName" maxlength="50" show-word-limit placeholder="例如：城市探索徽章" />
        </el-form-item>
        <el-form-item label="礼物图片" prop="picUrl">
          <el-upload
            class="gift-catalog__upload"
            :action="uploadFileUrl"
            :headers="headers"
            accept="image/jpeg,image/gif,image/png"
            :show-file-list="false"
            :before-upload="beforeUpload"
            :on-success="handleUploadSuccess"
            :on-error="handleUploadError"
          >
            <img v-if="form.picUrl" :src="form.picUrl" class="gift-catalog__upload-image">
            <i v-else class="el-icon-plus gift-catalog__upload-icon" />
          </el-upload>
          <p class="gift-catalog__field-note">可选；支持 JPG、PNG、GIF，单张不超过 5MB。</p>
        </el-form-item>
        <el-form-item label="礼物说明" prop="contents">
          <el-input v-model.trim="form.contents" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="说明赠送给活动或主办方时展示的礼物含义" />
        </el-form-item>
        <el-form-item label="所需积分" prop="point">
          <el-input-number v-model="form.point" :min="1" :max="2147483647" :precision="0" controls-position="right" />
          <p class="gift-catalog__field-note">仅影响后续赠礼扣减；改价前后积分和变更说明会写入不可缺失的礼物目录审计记录。</p>
        </el-form-item>
        <el-form-item label="可用状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">可赠送</el-radio>
            <el-radio :label="2">已停用</el-radio>
          </el-radio-group>
          <p class="gift-catalog__field-note">停用后，C 端列表和直接赠礼均会被阻止。</p>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="prepareSave">保存变更</el-button>
      </div>
    </el-dialog>

    <cy-confirm-action
      :visible.sync="confirmOpen"
      :title="confirmTitle"
      :impact="confirmImpact"
      severity="warning"
      :confirm-text="confirmText"
      reason-label="变更说明"
      reason-placeholder="说明为什么新增、改价、启用或停用此礼物"
      :loading="saving"
      @confirm="submitConfirmed"
    />
  </div>
</template>

<script>
import { addGift, getGift, listGift, updateGift } from '@/api/business/gift'
import { getToken } from '@/utils/auth'
import CyConfirmAction from '@/components/CyConfirmAction'
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const createQuery = () => ({ pageNum: 1, pageSize: 10, giftName: null, status: null })
const createForm = source => Object.assign({
  id: null,
  giftName: '',
  picUrl: '',
  contents: '',
  point: null,
  status: 2,
  delFlag: 0
}, source || {}, {
  status: source && source.status === 1 ? 1 : 2,
  delFlag: source && source.delFlag !== undefined && source.delFlag !== null ? source.delFlag : 0
})

export default {
  name: 'GiftCatalog',
  components: { CyConfirmAction, CyDataTable, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      listError: false,
      total: 0,
      giftList: [],
      updatedAt: '',
      listRequestId: 0,
      detailRequestId: 0,
      queryForm: createQuery(),
      queryParams: createQuery(),
      formOpen: false,
      confirmOpen: false,
      saving: false,
      pendingAction: '',
      form: createForm(),
      originalForm: null,
      uploadFileUrl: process.env.VUE_APP_BASE_API + '/common/upload',
      headers: { Authorization: 'Bearer ' + getToken() },
      rules: {
        giftName: [{ required: true, message: '请填写礼物名称', trigger: 'blur' }],
        point: [{ required: true, message: '请填写所需积分', trigger: 'change' }],
        status: [{ required: true, message: '请选择可用状态', trigger: 'change' }]
      },
      tableColumns: [
        { key: 'gift', label: '礼物', minWidth: 240, locked: true },
        { key: 'price', label: '所需积分', width: 130, align: 'right' },
        { key: 'status', label: '可用状态', width: 120 },
        { key: 'updatedAt', label: '最后变更', width: 170, prop: 'updateTime' },
        { key: 'actions', label: '操作', width: 150, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.giftName && { key: 'giftName', label: '礼物名称', value: this.queryParams.giftName },
        this.queryParams.status !== null && this.queryParams.status !== undefined && { key: 'status', label: '可用状态', value: this.queryParams.status === 1 ? '可赠送' : '已停用' }
      ].filter(Boolean)
    },
    confirmTitle() {
      if (this.pendingAction === 'status') return this.form.status === 1 ? '确认启用礼物' : '确认停用礼物'
      return this.form.id ? '确认保存礼物变更' : '确认新增礼物'
    },
    confirmText() {
      if (this.pendingAction === 'status') return this.form.status === 1 ? '确认启用' : '确认停用'
      return this.form.id ? '确认保存' : '确认新增'
    },
    confirmImpact() {
      const name = this.form.giftName || `礼物 #${this.form.id}`
      if (this.pendingAction === 'status') {
        return this.form.status === 1
          ? `启用“${name}”后，C 端将可再次展示并接受后续赠礼。`
          : `停用“${name}”后，C 端列表和直接赠礼都会被阻止；已发生的赠礼记录与积分明细不会被删除。`
      }
      if (!this.form.id) {
        return `新增“${name}”。当前选择为${this.form.status === 1 ? '可赠送' : '已停用'}，保存操作会写入礼物目录审计记录。`
      }
      const priceChanged = this.originalForm && Number(this.originalForm.point) !== Number(this.form.point)
      const statusChanged = this.originalForm && Number(this.originalForm.status) !== Number(this.form.status)
      const parts = []
      if (priceChanged) parts.push(`积分价格将从 ${this.formatPoints(this.originalForm.point)} 调整为 ${this.formatPoints(this.form.point)}`)
      if (statusChanged) parts.push(this.form.status === 1 ? '礼物将恢复可赠送' : '礼物将停止后续赠礼')
      return `${parts.length ? parts.join('；') : '将保存礼物资料变更'}。操作人、变更说明以及调价前积分会写入不可缺失的礼物目录审计记录。`
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
      return listGift(params)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.giftList = response.rows || []
          this.total = response.total || 0
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.listError = true
          this.$modal.msgError('礼物目录读取失败，请稍后重试。')
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    handleQuery() {
      this.queryParams = Object.assign(createQuery(), {
        pageSize: this.queryParams.pageSize,
        giftName: this.queryForm.giftName || null,
        status: this.queryForm.status
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
      return getGift(row.id).then(response => {
        if (requestId !== this.detailRequestId) return
        this.form = createForm(response.data)
        this.originalForm = Object.assign({}, this.form)
        this.formOpen = true
      }).catch(() => {
        if (requestId !== this.detailRequestId) return
        this.$modal.msgError('礼物详情读取失败，请重试。')
      })
    },
    handleToggleStatus(row) {
      this.invalidateDetailRequest()
      this.form = createForm(row)
      this.originalForm = Object.assign({}, this.form)
      this.form.status = this.isAvailable(row) ? 2 : 1
      this.pendingAction = 'status'
      this.confirmOpen = true
    },
    prepareSave() {
      this.$refs.giftForm.validate(valid => {
        if (!valid) return
        this.pendingAction = 'save'
        this.confirmOpen = true
      })
    },
    submitConfirmed(reason) {
      let payload
      if (this.pendingAction === 'status') {
        payload = { id: this.form.id, status: this.form.status, auditReason: reason }
      } else {
        payload = Object.assign({}, this.form, { auditReason: reason })
      }
      const request = payload.id ? updateGift(payload) : addGift(payload)
      this.saving = true
      return request.then(() => {
        this.$modal.msgSuccess(payload.id ? '礼物目录已更新' : '礼物目录已新增')
        this.formOpen = false
        this.confirmOpen = false
        return this.getList()
      }).catch(() => {}).finally(() => {
        this.saving = false
      })
    },
    resetForm() {
      this.invalidateDetailRequest()
      this.form = createForm()
      this.originalForm = null
      this.pendingAction = ''
      if (this.$refs.giftForm) this.resetFormFields('giftForm')
    },
    resetFormFields(ref) {
      this.$refs[ref].resetFields()
    },
    invalidateDetailRequest() {
      this.detailRequestId += 1
    },
    isAvailable(row) {
      return row && row.status === 1 && row.delFlag === 0
    },
    canToggle(row) {
      return row && row.delFlag === 0
    },
    statusLabel(row) {
      if (row.delFlag !== 0) return '已删除'
      if (row.status === 1) return '可赠送'
      if (row.status === 2) return '已停用'
      return '不可用'
    },
    statusTone(row) {
      if (this.isAvailable(row)) return 'success'
      if (row && row.delFlag !== 0) return 'danger'
      return 'neutral'
    },
    formatPoints(point) {
      const value = Number(point)
      return Number.isFinite(value) && value > 0 ? `${value.toLocaleString()} 积分` : '未配置'
    },
    beforeUpload(file) {
      const allowed = ['image/jpeg', 'image/png', 'image/gif'].includes(file.type)
      const withinSize = file.size / 1024 / 1024 <= 5
      if (!allowed) this.$modal.msgError('仅支持 JPG、PNG、GIF 图片')
      if (!withinSize) this.$modal.msgError('图片不能超过 5MB')
      return allowed && withinSize
    },
    handleUploadSuccess(response) {
      if (response.code === 200 && response.url) {
        this.form.picUrl = response.url
        return
      }
      this.$modal.msgError(response.msg || '图片上传失败')
    },
    handleUploadError() {
      this.$modal.msgError('图片上传失败，请重试。')
    }
  }
}
</script>

<style lang="scss" scoped>
.gift-catalog__filters { margin-bottom: 16px; }
.gift-catalog__object { display: flex; align-items: center; gap: 12px; min-width: 0; }
.gift-catalog__image { flex: none; width: 44px; height: 44px; border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.gift-catalog__object > div { min-width: 0; }
.gift-catalog__object strong, .gift-catalog__object span { display: block; }
.gift-catalog__object strong { overflow: hidden; color: var(--cy-color-text-primary); font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
.gift-catalog__object span { overflow: hidden; margin-top: 4px; color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.gift-catalog__points { color: var(--cy-color-text-primary); font-variant-numeric: tabular-nums; }
.gift-catalog__field-note { margin: 7px 0 0; color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.55; }
.gift-catalog__upload ::v-deep .el-upload { display: flex; align-items: center; justify-content: center; width: 104px; height: 104px; overflow: hidden; border: 1px dashed var(--cy-color-border-subtle); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.gift-catalog__upload ::v-deep .el-upload:hover { border-color: var(--cy-color-brand); }
.gift-catalog__upload-image { display: block; width: 100%; height: 100%; object-fit: cover; }
.gift-catalog__upload-icon { color: var(--cy-color-text-tertiary); font-size: 24px; }

@media (max-width: 720px) {
  .gift-catalog__object span { white-space: normal; }
}
</style>

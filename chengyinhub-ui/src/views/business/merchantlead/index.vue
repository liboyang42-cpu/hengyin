<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="伙伴与准入"
      title="商家目录"
      description="管理商家公开目录，控制对外展示与协作开关；新增与编辑仍在弹窗中完成。"
      :updated-at="updatedAt"
    />

    <section class="cy-panel merchantlead-filter" aria-label="商家目录筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>目录定位</h2>
          <p>按商家名称、电话或展示状态定位目录记录。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="68px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="商家名称" prop="name">
            <el-input v-model="queryForm.name" placeholder="输入商家名称" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="电话" prop="phone">
            <el-input v-model="queryForm.phone" placeholder="输入电话" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="queryForm.status" placeholder="全部状态" clearable>
              <el-option label="可展示" :value="1" />
              <el-option label="隐藏" :value="0" />
            </el-select>
          </el-form-item>
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
      :selectable="true"
      empty-text="当前筛选下没有商家目录记录"
      default-density="comfortable"
      @refresh="getList"
      @pagination="handlePagination"
      @selection-change="handleSelectionChange"
    >
      <template #toolbar-actions>
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['business:merchantlead:add']"
        >新增</el-button>
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete({})"
          v-hasPermi="['business:merchantlead:remove']"
        >删除选中</el-button>
        <el-button
          type="info"
          plain
          icon="el-icon-upload2"
          size="mini"
          @click="handleImport"
          v-hasPermi="['business:merchantlead:add']"
        >批量导入</el-button>
      </template>
      <template #merchant="{ row }">
        <div class="merchantlead-object">
          <strong>{{ row.name || '未命名商家' }}</strong>
          <span>ID #{{ row.id }} · 会员 #{{ row.memberId || '未注册' }}</span>
        </div>
      </template>
      <template #contactInfo="{ row }">
        <span>{{ row.contactOpen === 1 ? '是' : '否' }}</span>
      </template>
      <template #coopInfo="{ row }">
        <span>{{ row.coopOpen === 1 ? '是' : '否' }}</span>
      </template>
      <template #status="{ row }">
        <span>{{ row.status === 1 ? '可展示' : '隐藏' }}</span>
      </template>
      <template #actions="{ row }">
        <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(row)" v-hasPermi="['business:merchantlead:edit']">修改</el-button>
        <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(row)" v-hasPermi="['business:merchantlead:remove']">删除</el-button>
      </template>
    </cy-data-table>

    <el-dialog :title="title" :visible.sync="open" width="640px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px" size="small">
        <el-form-item label="商家名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入商家名称" />
        </el-form-item>
        <el-form-item label="电话" prop="phone">
          <el-input v-model="form.phone" placeholder="联系电话(未注册商家凭此可拨号)" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" placeholder="请输入地址" />
        </el-form-item>
        <el-form-item label="经度" prop="longitude">
          <el-input v-model="form.longitude" placeholder="经度(附近查询需要)" />
        </el-form-item>
        <el-form-item label="纬度" prop="latitude">
          <el-input v-model="form.latitude" placeholder="纬度(附近查询需要)" />
        </el-form-item>
        <el-form-item label="类型" prop="category">
          <el-input v-model="form.category" placeholder="如:咖啡/书店/餐饮" />
        </el-form-item>
        <el-form-item label="标签" prop="tags">
          <el-input v-model="form.tags" placeholder="逗号分隔标签" />
        </el-form-item>
        <el-form-item label="来源" prop="source">
          <el-input v-model="form.source" placeholder="registered/offline/bd_import" />
        </el-form-item>
        <el-form-item label="绑定账号" prop="memberId">
          <el-input v-model="form.memberId" placeholder="已注册商家的 member_id,未注册留空" />
        </el-form-item>
        <el-form-item label="开放联系">
          <el-switch v-model="form.contactOpen" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="开放协作">
          <el-switch v-model="form.coopOpen" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">可展示</el-radio>
            <el-radio :label="0">隐藏</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="批量导入线下商家" :visible.sync="upload.open" width="480px" append-to-body :close-on-click-modal="false">
      <el-upload
        ref="upload"
        drag
        :limit="1"
        accept=".xlsx, .xls"
        :headers="upload.headers"
        :action="upload.url"
        :disabled="upload.isUploading"
        :on-progress="handleFileUploadProgress"
        :on-success="handleFileSuccess"
        :on-error="handleFileError"
        :auto-upload="false"
      >
        <i class="el-icon-upload"></i>
        <div class="el-upload__text">将 Excel 拖到此处，或<em>点击选择</em></div>
        <div class="el-upload__tip" slot="tip">
          仅支持 xls、xlsx；导入后会逐行显示失败行号和原因。
          <el-link type="primary" :underline="false" @click.stop="importTemplate">下载模板</el-link>
        </div>
      </el-upload>
      <div slot="footer" class="dialog-footer">
        <el-button @click="upload.open = false">取 消</el-button>
        <el-button type="primary" :loading="upload.isUploading" @click="submitFileForm">开始导入</el-button>
      </div>
    </el-dialog>

    <el-dialog title="导入结果" :visible.sync="importResultOpen" width="680px" append-to-body>
      <p>共 {{ importResult.totalCount }} 行，成功 {{ importResult.successCount }} 行，失败 {{ importResult.failureCount }} 行。</p>
      <el-alert
        v-if="importResult.failureCount > 0"
        title="以下行未导入，请修正后重新上传"
        type="warning"
        :closable="false"
        show-icon
      />
      <cy-data-table
        v-if="importResult.failures.length"
        class="merchantlead-import-result"
        :data="pagedImportFailures"
        :columns="importResultColumns"
        :total="importResult.failureCount"
        :page="importResultPage"
        :limit="importResultLimit"
        :show-refresh="false"
        empty-text="没有失败行"
        @pagination="handleImportResultPagination"
      />
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="importResultOpen = false">我知道了</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listMerchantLead, getMerchantLead, addMerchantLead, updateMerchantLead, delMerchantLead } from "@/api/business/merchantlead"
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import { getToken } from '@/utils/auth'

const createQueryParams = () => ({ pageNum: 1, pageSize: 10, name: null, phone: null, status: null })

export default {
  name: "MerchantLead",
  components: { CyDataTable, CyFilterBar, CyPageHeader },
  data() {
    return {
      loading: false,
      ids: [],
      multiple: true,
      total: 0,
      list: [],
      updatedAt: '',
      queryForm: createQueryParams(),
      queryParams: createQueryParams(),
      listRequestId: 0,
      title: "",
      open: false,
      form: {},
      upload: {
        open: false,
        isUploading: false,
        headers: { Authorization: 'Bearer ' + getToken() },
        url: process.env.VUE_APP_BASE_API + '/business/merchantlead/importData'
      },
      importResultOpen: false,
      importResult: { totalCount: 0, successCount: 0, failureCount: 0, failures: [] },
      importResultPage: 1,
      importResultLimit: 10,
      importResultColumns: [
        { key: 'rowNumber', prop: 'rowNumber', label: 'Excel 行号', width: 120, align: 'center', locked: true },
        { key: 'reason', prop: 'reason', label: '失败原因', minWidth: 360, locked: true }
      ],
      rules: {
        name: [{ required: true, message: "商家名称不能为空", trigger: "blur" }]
      },
      tableColumns: [
        { key: 'merchant', label: '商家', minWidth: 180, locked: true },
        { key: 'phone', label: '电话', width: 130 },
        { key: 'address', label: '地址', minWidth: 180, prop: 'address', showOverflowTooltip: true },
        { key: 'category', label: '类型', width: 100, prop: 'category' },
        { key: 'source', label: '来源', width: 110, prop: 'source' },
        { key: 'contactInfo', label: '开放联系', width: 90, align: 'center' },
        { key: 'coopInfo', label: '开放协作', width: 90, align: 'center' },
        { key: 'status', label: '状态', width: 90, align: 'center' },
        { key: 'actions', label: '操作', width: 148, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.name && { key: 'name', label: '商家名称', value: this.queryParams.name },
        this.queryParams.phone && { key: 'phone', label: '电话', value: this.queryParams.phone },
        this.queryParams.status != null && { key: 'status', label: '状态', value: this.queryParams.status === 1 ? '可展示' : '隐藏' }
      ].filter(Boolean)
    },
    pagedImportFailures() {
      const start = (this.importResultPage - 1) * this.importResultLimit
      return this.importResult.failures.slice(start, start + this.importResultLimit)
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      const requestId = ++this.listRequestId
      const requestParams = Object.assign({}, this.queryParams)
      this.loading = true
      return listMerchantLead(requestParams)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.list = response.rows || []
          this.total = response.total || 0
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.$modal.msgError('商家目录读取失败，请检查网络后重试。')
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {
        id: null, memberId: null, name: null, phone: null, address: null,
        longitude: null, latitude: null, category: null, tags: null,
        source: "offline", contactOpen: 1, coopOpen: 1, status: 1
      }
      this.resetForm("form")
    },
    handleQuery() {
      this.queryParams = Object.assign(createQueryParams(), {
        pageSize: this.queryParams.pageSize,
        name: this.queryForm.name || null,
        phone: this.queryForm.phone || null,
        status: this.queryForm.status != null ? this.queryForm.status : null
      })
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
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.multiple = !selection.length
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "新增商家目录"
    },
    handleImport() {
      this.upload.open = true
      this.upload.isUploading = false
      this.importResult = { totalCount: 0, successCount: 0, failureCount: 0, failures: [] }
      this.$nextTick(() => this.$refs.upload && this.$refs.upload.clearFiles())
    },
    importTemplate() {
      this.download('business/merchantlead/importTemplate', {}, `merchant_lead_template_${new Date().getTime()}.xlsx`)
    },
    handleFileUploadProgress() {
      this.upload.isUploading = true
    },
    handleFileSuccess(response) {
      this.upload.isUploading = false
      this.$refs.upload.clearFiles()
      if (!response || response.code !== 200) {
        this.$modal.msgError((response && response.msg) || '导入失败，请检查文件后重试')
        return
      }
      this.upload.open = false
      this.importResult = Object.assign({ totalCount: 0, successCount: 0, failureCount: 0, failures: [] }, response.data || {})
      this.importResultPage = 1
      this.importResultOpen = true
      if (this.importResult.successCount > 0) this.getList()
      if (this.importResult.failureCount > 0) {
        this.$modal.msgWarning(`成功 ${this.importResult.successCount} 行，失败 ${this.importResult.failureCount} 行`)
      } else {
        this.$modal.msgSuccess(`成功导入 ${this.importResult.successCount} 行`)
      }
    },
    handleFileError() {
      this.upload.isUploading = false
      this.$refs.upload.clearFiles()
      this.$modal.msgError('文件上传失败，请检查网络后重试')
    },
    submitFileForm() {
      this.$refs.upload.submit()
    },
    handleImportResultPagination({ page, limit }) {
      this.importResultPage = page
      this.importResultLimit = limit
    },
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getMerchantLead(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改商家目录"
      })
    },
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (!valid) return
        if (this.form.id != null) {
          updateMerchantLead(this.form).then(() => {
            this.$modal.msgSuccess("修改成功")
            this.open = false
            this.getList()
          })
        } else {
          addMerchantLead(this.form).then(() => {
            this.$modal.msgSuccess("新增成功")
            this.open = false
            this.getList()
          })
        }
      })
    },
    handleDelete(row) {
      const ids = row.id || this.ids
      this.$modal.confirm('确认删除商家目录编号为"' + ids + '"的数据项?').then(() => {
        return delMerchantLead(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
.merchantlead-filter { margin-bottom: 16px; }
.merchantlead-object strong, .merchantlead-object span { display: block; }
.merchantlead-object strong { color: var(--cy-color-text-primary); }
.merchantlead-object span { margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); font-size: 12px; }
.merchantlead-import-result { margin-top: 16px; }
</style>

<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="伙伴与准入"
      title="商家准入"
      description="定位商家入驻申请并完成审核或资料维护；批量删除仍仅在选中记录后可用。"
      :updated-at="updatedAt"
    />

    <section class="cy-panel merchant-filter" aria-label="商家准入筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>申请定位</h2>
          <p>按会员 ID 或商家名称定位当前入驻申请。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="68px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="会员 ID" prop="memberId">
            <el-input v-model="queryForm.memberId" placeholder="输入会员 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="商家名称" prop="name">
            <el-input v-model="queryForm.name" placeholder="输入商家名称" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-data-table
      :data="merchantList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page="queryParams.pageNum"
      :limit="queryParams.pageSize"
      :selectable="true"
      empty-text="当前筛选下没有商家申请"
      default-density="comfortable"
      @refresh="getList"
      @pagination="handlePagination"
      @selection-change="handleSelectionChange"
    >
      <template #toolbar-actions>
        <el-button type="primary" plain icon="el-icon-s-check" size="mini" @click="claimAuditOpen = true" v-hasPermi="['business:merchant:edit']">商家认领待审队列</el-button>
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete({})"
          v-hasPermi="['business:merchant:remove']"
        >删除选中</el-button>
      </template>
      <template #merchant="{ row }">
        <div class="merchant-object">
          <strong>{{ row.name || '未命名商家' }}</strong>
          <span>商家 #{{ row.id }} · 会员 #{{ row.memberId }}</span>
        </div>
      </template>
      <template #logo="{ row }"><cy-media-preview :sources="row.logo ? [row.logo] : []" label="商家 LOGO" :width="52" :height="52" /></template>
      <template #status="{ row }">
        <div class="merchant-status">
          <dict-tag :options="dict.type.sys_merchant_status" :value="row.status" />
          <span v-if="row.status == 2 && row.reson">原因：{{ row.reson }}</span>
        </div>
      </template>
      <template #account="{ row }">
        <div class="merchant-status">
          <span>{{ Number(row.accountStatus) === 2 ? '已停用' : '正常' }}</span>
          <span v-if="Number(row.accountStatus) === 2 && row.disableReason">停用原因：{{ row.disableReason }}</span>
        </div>
      </template>
      <template #actions="{ row }">
        <el-button size="mini" type="text" icon="el-icon-edit" @click="handleEdit(row)" v-hasPermi="['business:merchant:edit']">编辑</el-button>
        <el-button v-if="row.status != '1'" size="mini" type="text" icon="el-icon-s-check" @click="handleUpdate(row)" v-hasPermi="['business:merchant:edit']">审核</el-button>
        <el-button v-if="Number(row.accountStatus) !== 2" size="mini" type="text" icon="el-icon-circle-close" @click="openDisable(row)" v-hasPermi="['business:merchant:edit']">停用</el-button>
        <el-button v-else size="mini" type="text" icon="el-icon-circle-check" @click="handleEnable(row)" v-hasPermi="['business:merchant:edit']">启用</el-button>
      </template>
    </cy-data-table>


    <merchant-claim-audit-dialog :visible.sync="claimAuditOpen" @changed="getList" />

    <!-- 添加或修改商家入住申请对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="处理类型" prop="status">
          <DictRadio v-model="form.status" :radioData="dict.type.sys_merchant_status" size="small" :filter="['0','3']"></DictRadio>
        </el-form-item>

        <el-form-item label="原因" prop="reson"  v-if="form.status == 2">
          <el-input v-model="form.reson" type="textarea" placeholder="请输入驳回原因" :rows="5" />
        </el-form-item>
      
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
    

    <!-- 编辑商家资料弹窗 -->
    <el-dialog title="编辑商家资料" :visible.sync="editOpen" width="600px" append-to-body>
      <el-form ref="editForm" :model="editForm" label-width="100px" size="small">
        <el-form-item label="商家名称" prop="name">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="editForm.phone" />
        </el-form-item>
        <el-form-item label="店铺地址" prop="address">
          <el-input v-model="editForm.address" />
        </el-form-item>
        <el-form-item label="营业时间" prop="businessTime">
          <el-input v-model="editForm.businessTime" placeholder="如：10:00-22:00" />
        </el-form-item>
        <el-form-item label="经营类目" prop="preference">
          <el-input v-model="editForm.preference" placeholder="逗号分隔" />
        </el-form-item>
        <el-form-item label="LOGO" prop="logo">
          <el-input v-model="editForm.logo" placeholder="图片URL" />
        </el-form-item>
        <el-form-item label="营业执照" prop="businessLicense">
          <el-input v-model="editForm.businessLicense" placeholder="图片URL" />
        </el-form-item>
        <el-form-item label="介绍" prop="description">
          <el-input v-model="editForm.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="衍生品" prop="derivatives">
          <el-input v-model="editForm.derivatives" type="textarea" placeholder="图片URL(逗号分隔)" />
        </el-form-item>
        <el-form-item label="品牌网址" prop="website">
          <el-input v-model="editForm.website" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitEdit">保 存</el-button>
        <el-button @click="editOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 平台停用商家账号(R9-35):原因必填,写入独立列 disable_reason;启用时清空 -->
    <el-dialog title="停用商家账号" :visible.sync="disableOpen" width="480px" append-to-body :close-on-click-modal="false">
      <el-form ref="disableForm" :model="disableForm" :rules="disableRules" label-width="90px">
        <el-form-item label="停用原因" prop="disableReason">
          <el-input v-model="disableForm.disableReason" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="会展示给被停用的商家,请写明原因" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="disableSubmitting" @click="submitDisable">确认停用</el-button>
        <el-button @click="disableOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listMerchant, getMerchant, delMerchant, addMerchant, updateMerchant, changeMerchantStatus } from "@/api/business/merchant"
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyMediaPreview from '@/components/CyMediaPreview'
import CyPageHeader from '@/components/CyPageHeader'
import MerchantClaimAuditDialog from './components/MerchantClaimAuditDialog.vue'

const createQueryParams = () => ({ pageNum: 1, pageSize: 10, memberId: null, name: null, status: null })

export default {
  name: "Merchant",
  components: { CyDataTable, CyFilterBar, CyMediaPreview, CyPageHeader, MerchantClaimAuditDialog },
  dicts: ['sys_normal_disable',  'sys_show_hide','sys_merchant_status','sys_user_status'],
  data() {
    return {
      loading: false,
      claimAuditOpen: false,
      ids: [],
      multiple: true,
      total: 0,
      merchantList: [],
      updatedAt: '',
      queryForm: createQueryParams(),
      queryParams: createQueryParams(),
      listRequestId: 0,
      title: "",
      open: false,
      editOpen: false,
      editForm: {},
      form: {},
      rules: {},
      disableOpen: false,
      disableSubmitting: false,
      disableForm: {},
      disableRules: {
        disableReason: [{ required: true, message: '请填写停用原因', trigger: 'blur' }]
      },
      tableColumns: [
        { key: 'merchant', label: '商家', minWidth: 180, locked: true },
        { key: 'logo', label: 'LOGO', width: 92 },
        { key: 'derivatives', label: '衍生品', minWidth: 130, prop: 'derivatives', showOverflowTooltip: true },
        { key: 'website', label: '品牌网址', minWidth: 160, prop: 'website', showOverflowTooltip: true },
        { key: 'description', label: '商家介绍', minWidth: 210, prop: 'description', showOverflowTooltip: true },
        { key: 'status', label: '审核状态', minWidth: 150 },
        { key: 'account', label: '账号状态', minWidth: 150 },
        { key: 'actions', label: '操作', width: 208, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.memberId && { key: 'memberId', label: '会员 ID', value: this.queryParams.memberId },
        this.queryParams.name && { key: 'name', label: '商家名称', value: this.queryParams.name },
        this.queryParams.status !== null && this.queryParams.status !== undefined &&
          { key: 'status', label: '审核状态', value: this.statusLabel(this.queryParams.status) }
      ].filter(Boolean)
    }
  },
  created() {
    this.applyRouteStatus()
    this.getList()
  },
  methods: {
    // 首页「商家准入」卡片带 ?status=0 进来表示「只看待审」。
    // 字段名按 mms_merchant 的实体字段给:该表没有 check_status 列，
    // 待审计数用的是 status=0(UmsMemberMapper.xml: del_flag=0 AND `status`=0)，
    // MmsMerchantMapper.xml 的列表查询条件也只认 status。
    applyRouteStatus() {
      const raw = this.$route && this.$route.query ? this.$route.query.status : undefined
      if (raw === undefined || raw === null || raw === '') return
      const status = Number(raw)
      if (!Number.isInteger(status)) return
      this.queryForm.status = status
      this.queryParams.status = status
    },
    statusLabel(status) {
      const options = (this.dict && this.dict.type && this.dict.type.sys_merchant_status) || []
      const hit = options.find(item => String(item.value) === String(status))
      return hit ? hit.label : String(status)
    },
    getList() {
      const requestId = ++this.listRequestId
      const requestParams = Object.assign({}, this.queryParams)
      this.loading = true
      return listMerchant(requestParams)
        .then(response => {
          if (requestId !== this.listRequestId) return
          this.merchantList = response.rows || []
          this.total = response.total || 0
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          if (requestId !== this.listRequestId) return
          this.$modal.msgError('商家申请读取失败，请检查网络后重试。')
        })
        .finally(() => {
          if (requestId === this.listRequestId) this.loading = false
        })
    },
    // 编辑商家资料
    handleEdit(row) {
      getMerchant(row.id).then(res => {
        this.editForm = { ...res.data };
        this.editOpen = true;
      });
    },
    submitEdit() {
      updateMerchant(this.editForm).then(() => {
        this.$modal.msgSuccess("保存成功");
        this.editOpen = false;
        this.getList();
      });
    },
    // 平台停用(R9-35):原因必填,写入独立列 disable_reason,与审核驳回原因 reson 分离
    openDisable(row) {
      this.disableForm = { id: row.id, accountStatus: 2, disableReason: '' }
      this.disableOpen = true
    },
    submitDisable() {
      this.$refs.disableForm.validate(valid => {
        if (!valid) return
        this.disableSubmitting = true
        changeMerchantStatus(this.disableForm).then(() => {
          this.$modal.msgSuccess('已停用')
          this.disableOpen = false
          this.getList()
        }).finally(() => {
          this.disableSubmitting = false
        })
      })
    },
    handleEnable(row) {
      const name = row.name || ('商家 #' + row.id)
      this.$modal.confirm('确认启用“' + name + '”？启用后停用原因会被清空。').then(() => {
        return changeMerchantStatus({ id: row.id, accountStatus: 1 })
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess('已启用')
      }).catch(() => {})
    },
    // 取消按钮
    cancel() {
      this.open = false
      this.reset()
    },
    // 表单重置
    reset() {
      this.form = {
        id: null,
        memberId: null,
        name: null,
        logo: null,
        description: null,
        derivatives: null,
        website: null,
        preference: null,
        status: null,
        reson: null,
        accountStatus: null,
        delFlag: null,
        createBy: null,
        createTime: null,
        updateBy: null,
        updateTime: null
      }
      this.resetForm("form")
    },
    handleQuery() {
      this.queryParams = Object.assign(createQueryParams(), {
        pageSize: this.queryParams.pageSize,
        memberId: this.queryForm.memberId || null,
        name: this.queryForm.name || null,
        status: this.queryForm.status
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
    /** 新增按钮操作 */
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加商家"
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getMerchant(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改商家"
      })
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateMerchant(this.form).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addMerchant(this.form).then(response => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getList()
            })
          }
        }
      })
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids
      this.$modal.confirm('是否确认删除商家编号为"' + ids + '"的数据项？').then(function() {
        return delMerchant(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('business/merchant/export', {
        ...this.queryParams
      }, `merchant_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style lang="scss" scoped>
.merchant-filter { margin-bottom: 16px; }
.merchant-object strong, .merchant-object span { display: block; }
.merchant-object strong { color: var(--cy-color-text-primary); }
.merchant-object span, .merchant-status span { margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); font-size: 12px; }
.merchant-status span { display: block; line-height: 1.5; }
</style>

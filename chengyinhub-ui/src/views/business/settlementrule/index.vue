<template>
  <div class="app-container">
    <el-card class="platform-account-card" shadow="never">
      <div slot="header" class="platform-account-card__header">
        <div>
          <strong>平台活动分润收款账户</strong>
          <p>仅用于活动结算的平台分润；不改变平台自营主题的收款账户。</p>
        </div>
        <el-button
          type="primary"
          size="mini"
          icon="el-icon-edit"
          :disabled="platformAccountLoading"
          v-hasPermi="['business:settlementrule:edit']"
          @click="openPlatformAccount"
        >更换账户</el-button>
      </div>

      <div v-loading="platformAccountLoading" class="platform-account-card__body">
        <template v-if="platformAccountError">
          <el-alert title="读取平台收款账户失败" type="error" :closable="false" show-icon />
          <el-button type="text" size="mini" @click="loadPlatformAccount">重新读取</el-button>
        </template>
        <template v-else-if="!platformAccount.configured">
          <el-alert title="尚未配置平台收款账户，活动结算将被阻断" type="warning" :closable="false" show-icon />
        </template>
        <template v-else>
          <div class="platform-account-card__value">当前收款账户：#{{ platformAccount.memberId }}</div>
          <el-alert
            v-if="!platformAccount.usable"
            title="当前收款账户不可用，活动结算将被阻断；请更换为启用且未删除的会员账户"
            type="error"
            :closable="false"
            show-icon
          />
          <el-tag v-else type="success">账户可用</el-tag>
        </template>
      </div>
    </el-card>

    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" label-width="80px">
      <el-form-item label="商家ID" prop="merchantId">
        <el-input v-model="queryParams.merchantId" placeholder="0 为平台默认" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="业务类型" prop="bizType">
        <el-select v-model="queryParams.bizType" placeholder="请选择" clearable>
          <el-option label="活动" value="activity" />
          <el-option label="主题" value="topic" />
          <el-option label="优惠券" value="coupon" />
          <el-option label="订单" value="order" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd" v-hasPermi="['business:settlementrule:edit']">新增</el-button>
      </el-col>
      <right-toolbar @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="ruleList">
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="商家ID" align="center" prop="merchantId" width="100" />
      <el-table-column label="规则名称" align="center" prop="ruleName" min-width="160" />
      <el-table-column label="业务类型" align="center" prop="bizType" width="100" />
      <el-table-column label="周期" align="center" prop="settlementCycle" width="100" />
      <el-table-column label="平台抽成" align="center" prop="platformCommissionRate" width="110" />
      <el-table-column label="商家比例" align="center" prop="merchantRate" width="110" />
      <el-table-column label="创作者比例" align="center" prop="creatorRate" width="110" />
      <el-table-column label="邀请人比例" align="center" prop="inviterRate" width="110" />
      <el-table-column label="退款截止小时" align="center" prop="refundDeadlineHours" width="120" />
      <el-table-column label="已核销可退" align="center" prop="allowRefundAfterVerified" width="100">
        <template slot-scope="scope">{{ scope.row.allowRefundAfterVerified === 1 ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="状态" align="center" prop="status" width="80">
        <template slot-scope="scope">
          <el-tag :type="scope.row.status === 1 ? 'success' : 'info'">{{ scope.row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" fixed="right" width="120">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)" v-hasPermi="['business:settlementrule:edit']">修改</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="title" :visible.sync="open" width="680px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="商家ID" prop="merchantId">
          <el-input-number v-model="form.merchantId" :min="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="业务类型" prop="bizType">
          <el-select v-model="form.bizType" placeholder="请选择">
            <el-option label="活动" value="activity" />
            <el-option label="主题" value="topic" />
            <el-option label="优惠券" value="coupon" />
            <el-option label="订单" value="order" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="form.ruleName" />
        </el-form-item>
        <el-form-item label="结算周期" prop="settlementCycle">
          <el-select v-model="form.settlementCycle">
            <el-option label="日结" value="daily" />
            <el-option label="周结" value="weekly" />
            <el-option label="月结" value="monthly" />
            <el-option label="手动" value="manual" />
          </el-select>
        </el-form-item>
        <el-form-item label="平台抽成" prop="platformCommissionRate"><el-input-number v-model="form.platformCommissionRate" :min="0" :max="1" :step="0.01" /></el-form-item>
        <el-form-item label="商家比例" prop="merchantRate"><el-input-number v-model="form.merchantRate" :min="0" :max="1" :step="0.01" /></el-form-item>
        <el-form-item label="创作者比例" prop="creatorRate"><el-input-number v-model="form.creatorRate" :min="0" :max="1" :step="0.01" /></el-form-item>
        <el-form-item label="邀请人比例" prop="inviterRate"><el-input-number v-model="form.inviterRate" :min="0" :max="1" :step="0.01" /></el-form-item>
        <el-form-item label="最低结算金额" prop="minSettlementAmount"><el-input-number v-model="form.minSettlementAmount" :min="0" :step="10" /></el-form-item>
        <el-form-item label="退款截止小时" prop="refundDeadlineHours"><el-input-number v-model="form.refundDeadlineHours" :min="0" :step="1" /></el-form-item>
        <el-form-item label="已核销可退" prop="allowRefundAfterVerified"><el-switch v-model="form.allowRefundAfterVerified" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="自动通过退款" prop="autoApproveRefund"><el-switch v-model="form.autoApproveRefund" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="状态" prop="status"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="更换平台活动分润收款账户" :visible.sync="platformAccountOpen" width="460px" append-to-body>
      <el-alert
        title="仅可选择启用且未删除的会员账户；未配置或账户失效时，活动结算会被阻断。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-form ref="platformAccountForm" :model="platformAccountForm" :rules="platformAccountRules" label-width="120px" class="platform-account-form">
        <el-form-item label="会员账户 ID" prop="memberId">
          <el-input-number v-model="platformAccountForm.memberId" :min="1" :precision="0" controls-position="right" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="platformAccountSaving" @click="submitPlatformAccount">确 定</el-button>
        <el-button :disabled="platformAccountSaving" @click="platformAccountOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listSettlementRule,
  addSettlementRule,
  updateSettlementRule,
  getPlatformSettlementAccount,
  updatePlatformSettlementAccount
} from '@/api/business/settlementRule'

export default {
  name: 'SettlementRule',
  data() {
    return {
      loading: false,
      ruleList: [],
      total: 0,
      platformAccountLoading: false,
      platformAccountError: false,
      platformAccountOpen: false,
      platformAccountSaving: false,
      platformAccount: { memberId: null, configured: false, usable: false },
      platformAccountForm: { memberId: null },
      platformAccountRules: {
        memberId: [{ required: true, type: 'number', min: 1, message: '请输入有效的会员账户 ID', trigger: 'change' }]
      },
      open: false,
      title: '',
      queryParams: { pageNum: 1, pageSize: 10, merchantId: null, bizType: null },
      form: {},
      rules: {
        bizType: [{ required: true, message: '业务类型不能为空', trigger: 'change' }],
        platformCommissionRate: [{ required: true, message: '平台抽成不能为空', trigger: 'blur' }],
        merchantRate: [{ required: true, message: '商家比例不能为空', trigger: 'blur' }],
        creatorRate: [{ required: true, message: '创作者比例不能为空', trigger: 'blur' }],
        inviterRate: [{ required: true, message: '邀请人比例不能为空', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.getList()
    this.loadPlatformAccount()
  },
  methods: {
    getList() {
      this.loading = true
      listSettlementRule(this.queryParams).then(res => {
        this.ruleList = res.rows || []
        this.total = res.total || 0
      }).catch(() => {
        this.ruleList = []
        this.total = 0
        this.$modal.msgError('结算规则读取失败，请稍后重试')
      }).finally(() => { this.loading = false })
    },
    loadPlatformAccount() {
      this.platformAccountLoading = true
      this.platformAccountError = false
      return getPlatformSettlementAccount().then(res => {
        this.platformAccount = Object.assign({ memberId: null, configured: false, usable: false }, res.data || {})
        return true
      }).catch(() => {
        this.platformAccount = { memberId: null, configured: false, usable: false }
        this.platformAccountError = true
        return false
      }).finally(() => {
        this.platformAccountLoading = false
      })
    },
    openPlatformAccount() {
      this.platformAccountForm = { memberId: this.platformAccount.memberId || null }
      this.platformAccountOpen = true
      this.$nextTick(() => this.$refs.platformAccountForm && this.$refs.platformAccountForm.clearValidate())
    },
    submitPlatformAccount() {
      this.$refs.platformAccountForm.validate(valid => {
        if (!valid || this.platformAccountSaving) return
        this.platformAccountSaving = true
        updatePlatformSettlementAccount({ memberId: this.platformAccountForm.memberId })
          .then(() => {
            this.$modal.msgSuccess('平台收款账户已更新')
            this.platformAccountOpen = false
            return this.loadPlatformAccount()
          }).then(reloaded => {
            if (reloaded === false) {
              this.$modal.msgWarning('平台收款账户已更新，但状态回读失败，请稍后重新读取')
            }
          })
          .catch(() => {
            this.$modal.msgError('平台收款账户保存失败，请核对会员状态后重试')
          })
          .finally(() => {
            this.platformAccountSaving = false
          })
      })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    reset() {
      this.form = {
        id: null, merchantId: 0, bizType: 'activity', ruleName: '', settlementCycle: 'monthly',
        platformCommissionRate: 0.1, merchantRate: 0.9, creatorRate: 0, inviterRate: 0,
        minSettlementAmount: 100, refundDeadlineHours: 24, allowRefundAfterVerified: 0,
        autoApproveRefund: 0, status: 1
      }
      this.resetForm('form')
    },
    handleAdd() { this.reset(); this.title = '新增结算规则'; this.open = true },
    handleUpdate(row) { this.form = { ...row }; this.title = '修改结算规则'; this.open = true },
    cancel() { this.open = false; this.reset() },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        const request = this.form.id ? updateSettlementRule(this.form) : addSettlementRule(this.form)
        request.then(() => {
          this.$modal.msgSuccess('保存成功')
          this.open = false
          this.getList()
        }).catch(() => {
          this.$modal.msgError('结算规则保存失败，请核对字段后重试')
        })
      })
    }
  }
}
</script>

<style scoped>
.platform-account-card { margin-bottom: 16px; }
.platform-account-card__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.platform-account-card__header strong { color: var(--cy-color-text-primary); }
.platform-account-card__header p { margin: var(--cy-space-0-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; }
.platform-account-card__body { min-height: 36px; }
.platform-account-card__value { margin-bottom: var(--cy-space-1-5); color: var(--cy-color-text-primary); font-weight: 600; }
.platform-account-form { margin-top: var(--cy-space-3-5); }
</style>

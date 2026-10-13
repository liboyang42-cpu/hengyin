<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" label-width="80px">
      <el-form-item label="主体用户ID" prop="merchantId">
        <el-input v-model="queryParams.merchantId" placeholder="mms_merchant.member_id" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="用户ID" prop="memberId">
        <el-input v-model="queryParams.memberId" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="客户阶段" prop="customerStage">
        <el-select v-model="queryParams.customerStage" clearable>
          <el-option label="新客" value="new" />
          <el-option label="活跃" value="active" />
          <el-option label="流失" value="lost" />
          <el-option label="VIP" value="vip" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
        <el-button type="success" icon="el-icon-refresh-right" size="mini" @click="handleAggregate" v-hasPermi="['business:crm:aggregate']">增量对账</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="12" class="mb8" v-if="summary">
      <el-col :span="6"><el-card shadow="never">客户数：{{ summary.customerCount || 0 }}</el-card></el-col>
      <el-col :span="6"><el-card shadow="never">活跃客户：{{ summary.activeCustomerCount || 0 }}</el-card></el-col>
      <el-col :span="6"><el-card shadow="never">领券客户：{{ summary.couponCustomerCount || 0 }}</el-card></el-col>
      <el-col :span="6"><el-card shadow="never">订单金额：{{ summary.orderAmount || 0 }}</el-card></el-col>
    </el-row>

    <el-table v-loading="loading" :data="customerList">
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="主体用户ID" align="center" prop="merchantId" width="110" />
      <el-table-column label="用户ID" align="center" prop="memberId" width="100" />
      <el-table-column label="昵称" align="center" prop="memberNickname" min-width="140" />
      <el-table-column label="手机号" align="center" prop="memberPhone" min-width="120" />
      <el-table-column label="报名数" align="center" prop="registrationCount" width="90" />
      <el-table-column label="订单数" align="center" prop="orderCount" width="90" />
      <el-table-column label="订单金额" align="center" prop="orderAmount" width="110" />
      <el-table-column label="领券数" align="center" prop="couponReceiveCount" width="90" />
      <el-table-column label="核销数" align="center" prop="couponVerifyCount" width="90" />
      <el-table-column label="阶段" align="center" prop="customerStage" width="100" />
      <el-table-column label="最近互动" align="center" prop="lastInteractionTime" width="180">
        <template slot-scope="scope">{{ parseTime(scope.row.lastInteractionTime) }}</template>
      </el-table-column>
    </el-table>

    <pagination v-show="total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
  </div>
</template>

<script>
import { listCrmCustomer, getCrmSummary, aggregateCrm } from '@/api/business/crm'

export default {
  name: 'MerchantCrm',
  data() {
    return {
      loading: false,
      customerList: [],
      total: 0,
      summary: null,
      queryParams: { pageNum: 1, pageSize: 10, merchantId: null, memberId: null, customerStage: null }
    }
  },
  created() { this.getList() },
  methods: {
    getList() {
      if (!this.queryParams.merchantId) {
        this.customerList = []
        this.total = 0
        this.summary = null
        return
      }
      this.loading = true
      listCrmCustomer(this.queryParams).then(res => {
        this.customerList = res.rows || []
        this.total = res.total || 0
        return getCrmSummary({ merchantId: this.queryParams.merchantId })
      }).then(res => {
        this.summary = res.data || null
      }).finally(() => { this.loading = false })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    handleAggregate() {
      if (!this.queryParams.merchantId) {
        this.$modal.msgWarning('请先输入 mms_merchant.member_id（商家主体用户ID），不要填写商家行ID')
        return
      }
      aggregateCrm(this.queryParams.merchantId).then(() => {
        this.$modal.msgSuccess('增量对账完成')
        this.getList()
      })
    }
  }
}
</script>

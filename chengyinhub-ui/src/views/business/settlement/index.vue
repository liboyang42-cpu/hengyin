<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" label-width="80px">
      <el-form-item label="商家ID" prop="merchantId">
        <el-input v-model="queryParams.merchantId" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" clearable>
          <el-option label="待审核" :value="0" />
          <el-option label="已通过" :value="1" />
          <el-option label="失败" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="12" class="mb8" v-if="summary">
      <el-col :span="6"><el-card shadow="never">批次数：{{ summary.batchCount || 0 }}</el-card></el-col>
      <el-col :span="6"><el-card shadow="never">明细数：{{ summary.detailCount || 0 }}</el-card></el-col>
      <el-col :span="6"><el-card shadow="never">总额：{{ summary.totalAmount || 0 }}</el-card></el-col>
      <el-col :span="6"><el-card shadow="never">待结算：{{ summary.pendingAmount || 0 }}</el-card></el-col>
    </el-row>

    <el-row :gutter="12" class="mb8" v-if="reconciliation">
      <el-col :span="8"><el-card shadow="never">已结算：{{ reconciliation.settledAmount || 0 }}</el-card></el-col>
      <el-col :span="8"><el-card shadow="never">退款冲销：{{ reconciliation.reversalAmount || 0 }}</el-card></el-col>
      <el-col :span="8"><el-card shadow="never">净额：{{ reconciliation.netAmount || 0 }}</el-card></el-col>
    </el-row>

    <el-table v-loading="loading" :data="batchList" @row-click="handleRowClick">
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="批次号" align="center" prop="batchNo" min-width="150" />
      <el-table-column label="商家ID" align="center" prop="merchantId" width="100" />
      <el-table-column label="总额" align="center" prop="totalAmount" width="120" />
      <el-table-column label="明细数" align="center" prop="detailCount" width="100" />
      <el-table-column label="成功" align="center" prop="successCount" width="90" />
      <el-table-column label="失败" align="center" prop="failedCount" width="90" />
      <el-table-column label="状态" align="center" prop="status" width="100">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.status === 0">待审核</el-tag>
          <el-tag v-else-if="scope.row.status === 1" type="success">已通过</el-tag>
          <el-tag v-else type="danger">失败</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="幂等键" align="center" prop="idempotencyKey" min-width="180" />
      <el-table-column label="操作" align="center" width="120" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <!-- ⚠️ 文案必须是「对账」不是「打款」:confirmBatch 只流转账本状态,不动任何余额
               (系统2 的打款能力已拆除防双花,见 SettlementController.confirm / SettlementPayoutDecommissionedTest)。
               写成「打款」会让运营以为钱打出去了 —— 这正是 P0-1 单边扣款的诱因。 -->
          <el-button
            v-if="scope.row.status === 0"
            size="mini"
            type="text"
            icon="el-icon-check"
            v-hasPermi="['business:settlement:confirm']"
            @click.stop="handleConfirm(scope.row)"
          >确认对账</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-divider content-position="left">结算明细</el-divider>
    <el-table v-loading="detailLoading" :data="detailList">
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="来源" align="center" prop="sourceType" width="100" />
      <el-table-column label="来源ID" align="center" prop="sourceId" width="100" />
      <el-table-column label="收款方" align="center" prop="receiverType" width="100" />
      <el-table-column label="收款方ID" align="center" prop="receiverId" width="100" />
      <el-table-column label="原始金额" align="center" prop="grossAmount" />
      <el-table-column label="佣金" align="center" prop="commissionAmount" />
      <el-table-column label="结算金额" align="center" prop="settlementAmount" />
      <el-table-column label="状态" align="center" prop="status" />
      <el-table-column label="说明" align="center" prop="failReason" />
    </el-table>
  </div>
</template>

<script>
import { listSettlementBatch, listSettlementDetail, getSettlementSummary, confirmSettlement, getSettlementReconciliation } from '@/api/business/settlement'

export default {
  name: 'Settlement',
  data() {
    return {
      loading: false,
      detailLoading: false,
      batchList: [],
      detailList: [],
      total: 0,
      summary: null,
      reconciliation: null,
      queryParams: { pageNum: 1, pageSize: 10, merchantId: null, status: null }
    }
  },
  created() { this.getList() },
  methods: {
    getList() {
      this.loading = true
      listSettlementBatch(this.queryParams).then(res => {
        this.batchList = res.rows || []
        this.total = res.total || 0
        if (this.queryParams.merchantId) {
          this.loadMerchantStats(this.queryParams.merchantId)
        } else {
          this.summary = null
          this.reconciliation = null
        }
      }).finally(() => { this.loading = false })
    },
    loadMerchantStats(merchantId) {
      getSettlementSummary({ merchantId }).then(res => { this.summary = res.data || null })
      getSettlementReconciliation(merchantId).then(res => { this.reconciliation = res.data || null })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.detailList = []; this.handleQuery() },
    handleRowClick(row) {
      this.detailLoading = true
      listSettlementDetail({ batchId: row.id }).then(res => {
        this.detailList = res.rows || []
      }).finally(() => { this.detailLoading = false })
    },
    handleConfirm(row) {
      // ⚠️ 原文案「打款后将把结算金额计入各收款方余额」是假的:confirmBatch 不加余额(贷方已拆除防双花)。
      //    照它承诺去点,收款方一分钱没多,退款时却按「已结算」冲销 = P0-1 单边扣款。
      this.$modal.confirm('确认批次「' + row.batchNo + '」对账无误？此操作仅将明细置为已结算（账本状态），' +
        '不会打款，也不会变动任何收款方余额。').then(() => {
        return confirmSettlement(row.id)
      }).then(() => {
        this.$modal.msgSuccess('对账已确认')
        this.getList()
      }).catch(() => {})
    }
  }
}
</script>

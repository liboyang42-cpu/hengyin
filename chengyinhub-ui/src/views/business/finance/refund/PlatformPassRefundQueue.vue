<template>
  <section class="platform-pass-refund-queue">
    <cy-page-header eyebrow="资金与售后 / 退款处理台" title="通行证退款复核">
      <el-button icon="el-icon-back" @click="$emit('back')">返回退款处理台</el-button>
    </cy-page-header>

    <el-form :inline="true" size="small">
      <el-form-item label="复核状态">
        <el-select v-model="reviewStatus" clearable placeholder="全部" @change="load">
          <el-option label="待复核" :value="1" />
          <el-option label="已通过" :value="2" />
          <el-option label="已驳回" :value="3" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="orderNo" label="订单号" min-width="220" />
      <el-table-column prop="memberId" label="会员" width="90" />
      <el-table-column prop="status" label="订单状态" width="100" />
      <el-table-column label="金额(元)" width="90">
        <template slot-scope="{ row }">{{ (row.priceFen / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="复核" width="90">
        <template slot-scope="{ row }">{{ reviewLabel(row.refund && row.refund.reviewStatus) }}</template>
      </el-table-column>
      <el-table-column label="申请原因" min-width="140">
        <template slot-scope="{ row }">{{ row.refund && row.refund.applyReason }}</template>
      </el-table-column>
      <el-table-column prop="usageFacts" label="使用事实" min-width="160" />
      <el-table-column label="处理决定" min-width="140">
        <template slot-scope="{ row }">{{ row.refund && row.refund.decision }}</template>
      </el-table-column>
      <el-table-column prop="reviewHandler" label="处理人" width="100" />
      <el-table-column prop="reviewChannelReceipt" label="渠道回执" min-width="200" />
      <el-table-column label="渠道退款(分)" width="110">
        <template slot-scope="{ row }">{{ row.refund && row.refund.refundedFen }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template slot-scope="{ row }">
          <template v-if="row.refund && row.refund.reviewStatus === 'PENDING'">
            <el-button type="text" size="mini" v-hasPermi="['business:platformPass:refund']" @click="decide(row, true)">通过并退款</el-button>
            <el-button type="text" size="mini" v-hasPermi="['business:platformPass:refund']" @click="decide(row, false)">驳回</el-button>
          </template>
          <el-button v-else-if="row.status === 'REFUNDING'" type="text" size="mini" v-hasPermi="['business:platformPass:refund']" @click="refresh(row)">回查退款</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script>
import { listPassOrders, reviewPassRefund, refreshPassRefund } from '@/api/business/platformPass'
import CyPageHeader from '@/components/CyPageHeader'

const LABELS = { NONE: '无', PENDING: '待复核', APPROVED: '已通过', REJECTED: '已驳回' }

export default {
  name: 'PlatformPassRefundQueue',
  components: { CyPageHeader },
  data() {
    return { loading: false, rows: [], reviewStatus: 1 }
  },
  created() { this.load() },
  methods: {
    reviewLabel(code) { return LABELS[code] || '无' },
    load() {
      this.loading = true
      listPassOrders({ reviewStatus: this.reviewStatus || undefined, limit: 100 })
        .then(res => { this.rows = res.data || [] })
        .finally(() => { this.loading = false })
    },
    decide(row, approve) {
      this.$prompt(approve ? '处理意见（通过后立即向渠道发起整单退款）' : '驳回原因', approve ? '通过并退款' : '驳回', { inputValue: '' })
        .then(({ value }) => reviewPassRefund(row.orderNo, { approve, note: value || '' }))
        .then(() => { this.$modal.msgSuccess('已处理'); this.load() })
        .catch(() => {})
    },
    refresh(row) {
      refreshPassRefund(row.orderNo).then(() => { this.$modal.msgSuccess('已回查'); this.load() })
    }
  }
}
</script>

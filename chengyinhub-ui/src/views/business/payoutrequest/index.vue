<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="资金与售后"
      title="提现审核"
      description="会员在 App 或小程序提交的提现申请。通过后经既有微信商家转账打到会员的微信零钱；驳回不动钱。每次处理都要填写说明，并留下记录。"
    />

    <el-alert
      v-if="!gate.withdrawalOpen || !gate.transferEnabled"
      type="info"
      :closable="false"
      show-icon
      :title="gateText"
      style="margin-bottom: 16px"
    />

    <section class="cy-panel" aria-label="提现申请">
      <div class="cy-panel__heading">
        <div><h2>申请列表</h2><p>最近 {{ limit }} 条，按提交时间倒序。</p></div>
        <el-select v-model="status" size="small" clearable placeholder="全部状态" @change="load">
          <el-option v-for="(label, key) in STATUS" :key="key" :label="label" :value="key" />
        </el-select>
      </div>
      <el-table v-loading="loading" :data="rows" size="small" empty-text="没有提现申请">
        <el-table-column label="编号" prop="id" min-width="80" />
        <el-table-column label="会员" min-width="140">
          <template slot-scope="scope">{{ scope.row.nickname || '-' }}（{{ scope.row.memberId }}）</template>
        </el-table-column>
        <el-table-column label="金额（元）" prop="amount" min-width="100" />
        <el-table-column label="状态" min-width="100">
          <template slot-scope="scope">{{ STATUS[scope.row.status] || scope.row.status }}</template>
        </el-table-column>
        <el-table-column label="提交时间" prop="createTime" min-width="160" />
        <el-table-column label="审核人" prop="reviewBy" min-width="100" />
        <el-table-column label="说明" min-width="200">
          <template slot-scope="scope">{{ scope.row.failReason || scope.row.reviewNote || '' }}</template>
        </el-table-column>
        <el-table-column label="转账记录" min-width="120">
          <template slot-scope="scope">
            <span v-if="scope.row.cashWithdrawId">#{{ scope.row.cashWithdrawId }} {{ scope.row.transferStatus || '' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="170">
          <template slot-scope="scope">
            <template v-if="scope.row.status === 'PENDING'">
              <el-button
                v-hasPermi="['business:payoutRequest:review']"
                size="mini"
                type="warning"
                plain
                :disabled="!gate.withdrawalOpen || !gate.transferEnabled"
                @click="decide(scope.row, true)"
              >通过并打款</el-button>
              <el-button
                v-hasPermi="['business:payoutRequest:review']"
                size="mini"
                type="danger"
                plain
                @click="decide(scope.row, false)"
              >驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script>
import { listPayoutRequests, approvePayoutRequest, rejectPayoutRequest } from '@/api/business/payoutRequest'

const STATUS = {
  PENDING: '待审核',
  PAYING: '打款中',
  SENT: '已发起转账',
  FAILED: '打款失败',
  REJECTED: '已驳回',
  CANCELLED: '会员已撤回'
}

export default {
  name: 'PayoutRequest',
  data() {
    return { STATUS, loading: false, status: 'PENDING', limit: 50, rows: [], gate: { withdrawalOpen: false, transferEnabled: false } }
  },
  computed: {
    gateText() {
      if (!this.gate.withdrawalOpen) return '资金开关「用户提现」关闭：可以驳回，但不能通过并打款。'
      return '微信转账通道未开通（withdrawal.fast-withdraw.enabled）：可以驳回，但不能通过并打款。'
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      listPayoutRequests({ status: this.status || undefined, limit: this.limit }).then(res => {
        const view = res.data || {}
        this.gate = view.gate || { withdrawalOpen: false, transferEnabled: false }
        this.rows = view.rows || []
      }).finally(() => { this.loading = false })
    },
    decide(row, approve) {
      const verb = approve ? '通过并打款' : '驳回'
      const tip = approve
        ? `向会员 ${row.memberId} 打款 ¥${row.amount}。请填写审核说明（会记入记录）`
        : `驳回会员 ${row.memberId} 的 ¥${row.amount} 提现申请。请填写原因（会员可以看到）`
      this.$prompt(tip, verb, {
        inputValidator: value => (value && value.trim().length > 0) || '请填写说明',
        confirmButtonText: verb,
        cancelButtonText: '取消'
      }).then(({ value }) => (approve ? approvePayoutRequest(row.id, value) : rejectPayoutRequest(row.id, value)))
        .then(res => {
          if (res && res.code === 200) {
            const s = res.data && res.data.status
            this.$modal.msgSuccess(s === 'FAILED' ? '打款未成功，见说明列' : '已处理')
          }
          this.load()
        })
        .catch(() => {})
    }
  }
}
</script>

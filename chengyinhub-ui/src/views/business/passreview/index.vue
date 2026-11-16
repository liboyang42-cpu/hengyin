<template>
  <div class="app-container">
    <cy-page-header eyebrow="资金与售后" title="通行证撤回退款复核" description="Apple 撤回了一笔通行证退款时，订单停在这里等人工处理：重新开通（从现在或会员已有有效期结束时起算），或关闭（维持退款）。两种处理都要写备注，并记入平台审计。">
      <el-button slot="primary" size="small" icon="el-icon-refresh" :loading="loading" @click="getList">刷新</el-button>
    </cy-page-header>

    <el-form size="small" :inline="true">
      <el-form-item label="状态">
        <el-select v-model="state" style="width: 140px" @change="getList">
          <el-option v-for="s in states" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
    </el-form>

    <cy-resource-state v-if="loadError" kind="error" title="复核列表暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="getList" />
    <cy-empty-state v-else-if="!loading && !rows.length" title="没有需要复核的订单" />
    <el-table v-else v-loading="loading" :data="rows" size="small" border>
      <el-table-column label="订单" min-width="170">
        <template slot-scope="s">{{ s.row.order_no }}<div class="muted">会员 #{{ s.row.member_id }} · {{ s.row.plan_code }}</div></template>
      </el-table-column>
      <el-table-column label="App Store 交易" min-width="170">
        <template slot-scope="s">{{ s.row.transaction_id || '—' }}<div class="muted">{{ s.row.environment }} · {{ s.row.product_id }}</div></template>
      </el-table-column>
      <el-table-column label="订单状态" width="100" align="center">
        <template slot-scope="s">{{ orderStatusText(s.row.order_status) }}</template>
      </el-table-column>
      <el-table-column label="天数" prop="valid_days" width="70" align="center" />
      <el-table-column label="Apple 通知" min-width="260">
        <template slot-scope="s">
          <div v-for="n in s.row.notifications" :key="n.notification_uuid">{{ n.create_time }} · {{ n.notification_type }}<span v-if="n.subtype"> / {{ n.subtype }}</span> · {{ inboxText(n.state) }}</div>
          <span v-if="!s.row.notifications || !s.row.notifications.length">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center">
        <template slot-scope="s">
          <template v-if="s.row.state === 'REVIEW'">
            <el-button v-hasPermi="['business:passReview:resolve']" type="text" size="mini" @click="open(s.row, 'regrant')">重新开通</el-button>
            <el-button v-hasPermi="['business:passReview:resolve']" type="text" size="mini" @click="open(s.row, 'close')">关闭</el-button>
          </template>
          <span v-else>已处理</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="action.kind === 'regrant' ? '重新开通' : '关闭复核（维持退款）'" :visible.sync="dialogOpen" width="480px" append-to-body>
      <el-form size="small" label-width="80px">
        <el-form-item label="订单">{{ action.orderNo }}</el-form-item>
        <el-form-item label="备注">
          <el-input v-model="action.note" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="必填" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="dialogOpen = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submit">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { passReviewOrders, regrantPassOrder, closePassReview } from '@/api/business/passReview'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

const STATE_TEXT = { REVIEW: '待复核', GRANTED: '已重新开通', REVOKED: '已关闭' }
const ORDER_STATUS_TEXT = { 0: '待支付', 1: '已支付', 2: '已取消', 3: '已退款', 4: '退款中' }
const INBOX_TEXT = { RECEIVED: '已收到', PENDING: '待重投', APPLIED: '已处理', IGNORED: '已忽略' }

export default {
  name: 'PassReversalReview',
  components: { CyEmptyState, CyPageHeader, CyResourceState },
  data() {
    return {
      state: 'REVIEW',
      states: Object.keys(STATE_TEXT).map(k => ({ value: k, label: STATE_TEXT[k] })),
      rows: [],
      loading: false,
      loadError: false,
      saving: false,
      dialogOpen: false,
      action: {}
    }
  },
  created() {
    this.getList()
  },
  methods: {
    orderStatusText(v) {
      return ORDER_STATUS_TEXT[v] || '—'
    },
    inboxText(v) {
      return INBOX_TEXT[v] || v
    },
    getList() {
      this.loading = true
      this.loadError = false
      passReviewOrders({ state: this.state }).then(res => {
        this.rows = res.data || []
      }).catch(() => {
        this.loadError = true
      }).finally(() => {
        this.loading = false
      })
    },
    open(row, kind) {
      this.action = { kind, orderNo: row.order_no, note: '' }
      this.dialogOpen = true
    },
    submit() {
      if (!this.action.note || !this.action.note.trim()) {
        this.$modal.msgWarning('请填写备注')
        return
      }
      const call = this.action.kind === 'regrant' ? regrantPassOrder : closePassReview
      this.saving = true
      call(this.action.orderNo, { note: this.action.note.trim() }).then(() => {
        this.$modal.msgSuccess('已处理')
        this.dialogOpen = false
        this.getList()
      }).finally(() => {
        this.saving = false
      })
    }
  }
}
</script>

<style scoped>
.muted { color: var(--cy-color-text-tertiary); font-size: 12px; }
</style>

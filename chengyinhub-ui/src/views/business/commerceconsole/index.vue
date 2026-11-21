<template>
  <div class="app-container">
    <cy-page-header eyebrow="资金与售后" title="商品与会费" description="营销商品（含城市包）、探索套票和俱乐部会费的只读总览。这里只能停售商品、停用会费方案：已下的单和已付的会员期照常按下单时的约定继续。会费退款申请在「会费退款」里通过或驳回。奖励承诺在「奖励承诺审核」页处理。">
      <el-button slot="primary" size="small" icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button>
    </cy-page-header>

    <el-tabs v-model="tab" @tab-click="load">
      <el-tab-pane label="营销商品" name="products">
        <el-form size="small" :inline="true">
          <el-form-item label="状态">
            <el-select v-model="filters.productStatus" clearable style="width: 120px" @change="load">
              <el-option v-for="(t, k) in productStatusText" :key="k" :label="t" :value="Number(k)" />
            </el-select>
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="filters.kind" clearable style="width: 140px" @change="load">
              <el-option v-for="(t, k) in kindText" :key="k" :label="t" :value="Number(k)" />
            </el-select>
          </el-form-item>
        </el-form>
        <cy-resource-state v-if="loadError" kind="error" title="暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="load" />
        <el-table v-else v-loading="loading" :data="products" size="small" border empty-text="没有商品">
          <el-table-column label="商品" min-width="180">
            <template slot-scope="s">{{ s.row.product_name }}<div class="muted">{{ s.row.product_code }} · 第 {{ s.row.revision }} 版</div></template>
          </el-table-column>
          <el-table-column label="类型" width="110"><template slot-scope="s">{{ kindText[s.row.product_kind] || s.row.product_kind }}</template></el-table-column>
          <el-table-column label="城市" prop="sale_city" width="90" />
          <el-table-column label="价格" width="130"><template slot-scope="s">{{ price(s.row) }}</template></el-table-column>
          <el-table-column label="售卖期" min-width="200"><template slot-scope="s">{{ s.row.sale_start_at || '—' }} 至 {{ s.row.sale_end_at || '—' }}</template></el-table-column>
          <el-table-column label="状态" width="80" align="center"><template slot-scope="s">{{ productStatusText[s.row.status] }}</template></el-table-column>
          <el-table-column label="操作" width="140" align="center">
            <template slot-scope="s">
              <el-button type="text" size="mini" @click="showOrders(s.row)">订单</el-button>
              <el-button v-if="s.row.status === 1" v-hasPermi="['business:commerceConsole:edit']" type="text" size="mini" @click="openStop('product', s.row)">停售</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="探索套票" name="bundles">
        <cy-resource-state v-if="loadError" kind="error" title="暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="load" />
        <el-table v-else v-loading="loading" :data="bundles" size="small" border empty-text="没有套票订单">
          <el-table-column label="套票单" prop="id" width="90" />
          <el-table-column label="报名单" prop="registration_id" width="100" />
          <el-table-column label="持有人" width="110"><template slot-scope="s">会员 #{{ s.row.holder_member_id }}</template></el-table-column>
          <el-table-column label="主题" prop="topic_id" width="90" />
          <el-table-column label="金额" prop="paid_amount" width="100" />
          <el-table-column label="已用 / 总数" width="110" align="center"><template slot-scope="s">{{ s.row.unit_redeemed }} / {{ s.row.unit_total }}</template></el-table-column>
          <el-table-column label="有效期" min-width="200"><template slot-scope="s">{{ s.row.valid_from }} 至 {{ s.row.valid_to }}</template></el-table-column>
          <el-table-column label="退改政策" prop="policy_version" width="110" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="俱乐部会费方案" name="duesPlans">
        <el-form size="small" :inline="true">
          <el-form-item label="俱乐部编号"><el-input v-model="filters.clubId" clearable style="width: 140px" /></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <cy-resource-state v-if="loadError" kind="error" title="暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="load" />
        <el-table v-else v-loading="loading" :data="duesPlans" size="small" border empty-text="没有会费方案">
          <el-table-column label="方案" min-width="150"><template slot-scope="s">{{ s.row.name }}<div class="muted">俱乐部 #{{ s.row.club_id }}</div></template></el-table-column>
          <el-table-column label="天数" prop="duration_days" width="70" align="center" />
          <el-table-column label="价格" width="100"><template slot-scope="s">¥{{ yuan(s.row.price_fen) }}</template></el-table-column>
          <el-table-column label="宽限 / 逾期保留" width="130"><template slot-scope="s">{{ s.row.grace_days }} 天 / {{ s.row.past_due_days }} 天</template></el-table-column>
          <el-table-column label="退款" width="110"><template slot-scope="s">{{ s.row.refund_policy === 'MANUAL_REVIEW' ? '人工审核' : '不可退' }}</template></el-table-column>
          <el-table-column label="俱乐部会费" width="100" align="center"><template slot-scope="s">{{ Number(s.row.club_dues_enabled) === 1 ? '已开启' : '未开启' }}</template></el-table-column>
          <el-table-column label="状态" width="80" align="center"><template slot-scope="s">{{ s.row.status === 'ACTIVE' ? '启用' : '停用' }}</template></el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template slot-scope="s">
              <el-button v-if="s.row.status === 'ACTIVE'" v-hasPermi="['business:commerceConsole:edit']" type="text" size="mini" @click="openStop('plan', s.row)">停用</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="俱乐部会费账单" name="duesInvoices">
        <el-form size="small" :inline="true">
          <el-form-item label="俱乐部编号"><el-input v-model="filters.clubId" clearable style="width: 140px" /></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <cy-resource-state v-if="loadError" kind="error" title="暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="load" />
        <el-table v-else v-loading="loading" :data="duesInvoices" size="small" border empty-text="没有账单">
          <el-table-column label="账单" prop="invoice_no" min-width="160" />
          <el-table-column label="俱乐部 / 会员" min-width="150"><template slot-scope="s">#{{ s.row.club_id }} / 会员 #{{ s.row.member_id }}</template></el-table-column>
          <el-table-column label="方案" min-width="120"><template slot-scope="s">{{ s.row.plan_name_snapshot }} · {{ s.row.duration_days_snapshot }} 天</template></el-table-column>
          <el-table-column label="金额" width="100"><template slot-scope="s">¥{{ yuan(s.row.amount_fen) }}</template></el-table-column>
          <el-table-column label="状态" width="90" align="center"><template slot-scope="s">{{ invoiceStatusText[s.row.status] || s.row.status }}</template></el-table-column>
          <el-table-column label="创建时间" prop="create_time" width="170" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="会费退款" name="duesRefunds">
        <el-form size="small" :inline="true">
          <el-form-item><el-checkbox v-model="filters.undecided" @change="load">只看待处理</el-checkbox></el-form-item>
        </el-form>
        <cy-resource-state v-if="loadError" kind="error" title="暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="load" />
        <el-table v-else v-loading="loading" :data="duesRefunds" size="small" border empty-text="没有退款申请">
          <el-table-column label="申请" width="80"><template slot-scope="s">#{{ s.row.reviewId }}</template></el-table-column>
          <el-table-column label="账单" prop="invoiceNo" min-width="150" />
          <el-table-column label="俱乐部 / 会员" min-width="140"><template slot-scope="s">#{{ s.row.clubId }} / 会员 #{{ s.row.memberId }}</template></el-table-column>
          <el-table-column label="金额" width="90"><template slot-scope="s">¥{{ yuan(s.row.amountFen) }}</template></el-table-column>
          <el-table-column label="申请原因" prop="reason" min-width="160" />
          <el-table-column label="处理" min-width="170"><template slot-scope="s">{{ refundText(s.row) }}<div v-if="s.row.decidedBy" class="muted">{{ s.row.decidedBy }} · {{ s.row.decidedAt }}</div></template></el-table-column>
          <el-table-column label="操作" width="120" align="center">
            <template slot-scope="s">
              <el-button v-if="canApprove(s.row)" v-hasPermi="['business:commerceConsole:refund']" type="text" size="mini" @click="openRefund('approve', s.row)">{{ s.row.decision ? '重新发送' : '通过' }}</el-button>
              <el-button v-if="!s.row.decision" v-hasPermi="['business:commerceConsole:refund']" type="text" size="mini" @click="openRefund('reject', s.row)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="refund.kind === 'approve' ? '通过并原路退款' : '驳回退款申请'" :visible.sync="refundOpen" width="480px" append-to-body>
      <el-form size="small" label-width="80px">
        <el-form-item label="申请">#{{ refund.reviewId }} · ¥{{ yuan(refund.amountFen) }}</el-form-item>
        <el-form-item v-if="refund.kind === 'approve'" label="说明">全额原路退回会员的付款账户。资金开关「退款」关闭时不会执行。</el-form-item>
        <el-form-item label="原因">
          <el-input v-model="refund.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="必填，会记入审计" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="refundOpen = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitRefund">确定</el-button>
      </div>
    </el-dialog>

    <el-dialog title="商品订单" :visible.sync="ordersOpen" width="760px" append-to-body>
      <el-table :data="orders" size="small" border empty-text="没有订单">
        <el-table-column label="订单号" prop="order_no" min-width="170" />
        <el-table-column label="会员" width="100"><template slot-scope="s">#{{ s.row.member_id }}</template></el-table-column>
        <el-table-column label="数量" prop="quantity" width="60" align="center" />
        <el-table-column label="金额" width="120"><template slot-scope="s">{{ s.row.pay_mode === 2 ? s.row.total_points_amount + ' 积分' : '¥' + s.row.total_cash_amount }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template slot-scope="s">{{ orderStatusText[s.row.status] }}</template></el-table-column>
        <el-table-column label="时间" prop="create_time" width="160" />
      </el-table>
    </el-dialog>

    <el-dialog :title="stop.kind === 'product' ? '停售商品' : '停用会费方案'" :visible.sync="stopOpen" width="480px" append-to-body>
      <el-form size="small" label-width="80px">
        <el-form-item label="对象">{{ stop.label }}</el-form-item>
        <el-form-item label="原因">
          <el-input v-model="stop.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="必填" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="stopOpen = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitStop">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { commerceProducts, commerceProductOrders, commerceBundleOrders, commerceDuesPlans, commerceDuesInvoices, stopProductSale, deactivateDuesPlan, commerceDuesRefunds, approveDuesRefund, rejectDuesRefund } from '@/api/business/commerceConsole'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'CommerceConsole',
  components: { CyPageHeader, CyResourceState },
  data() {
    return {
      tab: 'products',
      loading: false,
      loadError: false,
      saving: false,
      filters: { productStatus: undefined, kind: undefined, clubId: '', undecided: true },
      duesRefunds: [],
      refundOpen: false,
      refund: {},
      refundStateText: { NOT_SENT: '未退款', SUBMITTING: '退款发送中', ACCEPTED: '微信已受理退款', FAILED: '退款失败，请人工核对', UNKNOWN: '结果未知，可重新发送' },
      products: [],
      bundles: [],
      duesPlans: [],
      duesInvoices: [],
      orders: [],
      ordersOpen: false,
      stopOpen: false,
      stop: {},
      kindText: { 1: '单关', 2: '城市包', 3: '限时 3/7 日', 4: '年卡', 5: '团体', 6: '学校', 7: '礼品', 8: '加座' },
      productStatusText: { 0: '草稿', 1: '在售', 2: '停售' },
      orderStatusText: { 0: '待支付', 1: '已支付', 2: '已取消', 3: '已退款' },
      invoiceStatusText: { CREATED: '已创建', PAYING: '支付中', PAID: '已支付', CLOSED: '已关闭' }
    }
  },
  created() {
    this.load()
  },
  methods: {
    yuan(fen) {
      return fen == null ? '—' : (Number(fen) / 100).toFixed(2)
    },
    price(row) {
      const parts = []
      if (row.cash_price != null) parts.push('¥' + row.cash_price)
      if (row.points_price != null) parts.push(row.points_price + ' 积分')
      return parts.join(' / ') || '—'
    },
    clubId() {
      return this.filters.clubId ? this.filters.clubId : undefined
    },
    request() {
      if (this.tab === 'products') {
        return commerceProducts({ status: this.filters.productStatus, kind: this.filters.kind }).then(res => { this.products = res.data || [] })
      }
      if (this.tab === 'bundles') {
        return commerceBundleOrders({}).then(res => { this.bundles = res.data || [] })
      }
      if (this.tab === 'duesRefunds') {
        return commerceDuesRefunds({ undecided: this.filters.undecided }).then(res => { this.duesRefunds = res.data || [] })
      }
      if (this.tab === 'duesPlans') {
        return commerceDuesPlans({ clubId: this.clubId() }).then(res => { this.duesPlans = res.data || [] })
      }
      return commerceDuesInvoices({ clubId: this.clubId() }).then(res => { this.duesInvoices = res.data || [] })
    },
    load() {
      this.loading = true
      this.loadError = false
      this.request().catch(() => {
        this.loadError = true
      }).finally(() => {
        this.loading = false
      })
    },
    showOrders(row) {
      commerceProductOrders({ productId: row.id }).then(res => {
        this.orders = res.data || []
        this.ordersOpen = true
      })
    },
    refundText(row) {
      if (!row.decision) return '待处理'
      if (row.decision === 'REJECTED') return '已驳回'
      return '已通过 · ' + (this.refundStateText[row.refundState] || row.refundState)
    },
    canApprove(row) {
      return !row.decision || (row.decision === 'APPROVED' && (row.refundState === 'SUBMITTING' || row.refundState === 'UNKNOWN'))
    },
    openRefund(kind, row) {
      this.refund = { kind, reviewId: row.reviewId, amountFen: row.amountFen, reason: '' }
      this.refundOpen = true
    },
    submitRefund() {
      if (!this.refund.reason || !this.refund.reason.trim()) {
        this.$modal.msgWarning('请填写原因')
        return
      }
      const call = this.refund.kind === 'approve' ? approveDuesRefund : rejectDuesRefund
      this.saving = true
      call(this.refund.reviewId, { reason: this.refund.reason.trim() }).then(() => {
        this.$modal.msgSuccess('已保存')
        this.refundOpen = false
        this.load()
      }).finally(() => {
        this.saving = false
      })
    },
    openStop(kind, row) {
      this.stop = { kind, id: row.id, label: kind === 'product' ? row.product_name : row.name, reason: '' }
      this.stopOpen = true
    },
    submitStop() {
      if (!this.stop.reason || !this.stop.reason.trim()) {
        this.$modal.msgWarning('请填写原因')
        return
      }
      const call = this.stop.kind === 'product' ? stopProductSale : deactivateDuesPlan
      this.saving = true
      call(this.stop.id, { reason: this.stop.reason.trim() }).then(() => {
        this.$modal.msgSuccess('已保存')
        this.stopOpen = false
        this.load()
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

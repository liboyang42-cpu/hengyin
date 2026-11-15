<template>
  <div class="app-container">
    <el-alert v-if="!salesOpen" type="info" :closable="false" show-icon
      title="资金开关「商家收款」未打开:商家看到的套餐为「即将开放」。开通付费套餐、确认收款、到店计费入账与月结都不可用;改成免费版、查看和设置不受影响。" />
    <el-tabs v-model="tab" class="mt8" @tab-click="load">
      <el-tab-pane label="平台抽成" name="take">
        <el-alert type="info" :closable="false" show-icon
          title="未设置的产品沿用现行值,不会有任何变化。设置只影响之后新生成的订单;已定价的主题和已报名的订单保留原比例。与商家单独约定的结算规则不受影响。每次修改都要填写原因并留下记录。" />
        <el-table v-loading="loading" :data="takeRows" class="mt8">
          <el-table-column label="产品" min-width="160">
            <template slot-scope="s">{{ productText(s.row.product) }}{{ s.row.source ? ' · ' + sourceText(s.row.source) : '' }}</template>
          </el-table-column>
          <el-table-column label="现行值" min-width="160">
            <template slot-scope="s">{{ s.row.todayBps == null ? basisText(s.row.todayBasis) : percent(s.row.todayBps) }}</template>
          </el-table-column>
          <el-table-column label="已设置" width="110">
            <template slot-scope="s">{{ s.row.configuredBps == null ? '未设置' : percent(s.row.configuredBps) }}</template>
          </el-table-column>
          <el-table-column label="生效值" width="160">
            <template slot-scope="s">{{ s.row.effectiveBps == null ? basisText(s.row.todayBasis) : percent(s.row.effectiveBps) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:merchantplans:take']" type="text" @click="editTake(s.row)">设置</el-button>
              <el-button v-if="s.row.configuredBps != null" v-hasPermi="['business:merchantplans:take']" type="text"
                @click="clearTake(s.row)">恢复现行值</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="套餐设置" name="plans">
        <el-alert type="info" :closable="false" show-icon
          title="免费版就是商家现在拥有的功能,始终免费、始终可选,功能不能少于现在。套餐不影响迷雾地图、点位排序和搜索顺序。" />
        <el-table v-loading="loading" :data="plans" class="mt8">
          <el-table-column label="套餐" min-width="120">
            <template slot-scope="s">{{ s.row.nameZh }} / {{ s.row.nameEn }}</template>
          </el-table-column>
          <el-table-column label="价格" min-width="140">
            <template slot-scope="s">{{ priceText(s.row) }}</template>
          </el-table-column>
          <el-table-column label="每周报告明细" width="110">
            <template slot-scope="s">{{ s.row.features.weeklyProofDetail ? '完整明细' : '汇总' }}</template>
          </el-table-column>
          <el-table-column label="额外点位游戏" width="110">
            <template slot-scope="s">{{ s.row.features.extraNodeGames }}</template>
          </el-table-column>
          <el-table-column label="每月优惠图层名额" width="140">
            <template slot-scope="s">{{ s.row.features.dealsOffersPerMonth }}</template>
          </el-table-column>
          <el-table-column label="在售" width="70">
            <template slot-scope="s">{{ s.row.offered ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:merchantplans:edit']" type="text" @click="openPlan(s.row)">修改</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="订单与开通" name="orders">
        <el-form :inline="true" size="small">
          <el-form-item label="商家编号">
            <el-input v-model="filter.merchantId" clearable style="width: 140px" />
          </el-form-item>
          <el-form-item label="订单状态">
            <el-select v-model="filter.status" clearable style="width: 130px">
              <el-option label="待确认收款" value="PENDING" />
              <el-option label="已收款" value="PAID" />
              <el-option label="已取消" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadOrders">查询</el-button>
            <el-button v-hasPermi="['business:merchantplans:sell']" icon="el-icon-plus" @click="openGrant">开通或改为免费版</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="orders">
          <el-table-column label="订单号" prop="order_no" min-width="230" />
          <el-table-column label="商家编号" prop="merchant_id" width="100" />
          <el-table-column label="套餐" width="90">
            <template slot-scope="s">{{ planName(s.row.plan_code) }}</template>
          </el-table-column>
          <el-table-column label="月数" prop="months" width="70" />
          <el-table-column label="金额(元)" width="100">
            <template slot-scope="s">{{ yuan(s.row.amount_fen) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template slot-scope="s">{{ orderStatusText(s.row.status) }}</template>
          </el-table-column>
          <el-table-column label="下单时间" prop="create_time" width="170" />
          <el-table-column label="操作" width="110">
            <template slot-scope="s">
              <el-button v-if="s.row.status === 'PENDING'" v-hasPermi="['business:merchantplans:sell']" type="text"
                :disabled="!salesOpen" @click="confirmPaid(s.row)">确认已收款</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4 class="mt8">套餐期间</h4>
        <el-table v-loading="loading" :data="subscriptions">
          <el-table-column label="商家编号" prop="merchant_id" width="100" />
          <el-table-column label="套餐" width="90">
            <template slot-scope="s">{{ planName(s.row.plan_code) }}</template>
          </el-table-column>
          <el-table-column label="开始" prop="period_start" width="170" />
          <el-table-column label="结束" prop="period_end" width="170" />
          <el-table-column label="来源" width="100">
            <template slot-scope="s">{{ s.row.source === 'PURCHASE' ? '商家购买' : '运营开通' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template slot-scope="s">{{ s.row.status === 'ACTIVE' ? '有效' : '已结束' }}</template>
          </el-table-column>
          <el-table-column label="操作人" prop="create_by" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="到店计费" name="billing">
        <el-alert type="info" :closable="false" show-icon
          title="只对按到店付费套餐的商家计费:同一位玩家同一天在店里有店员核销(票、券或点位兑换)记一次,与每周到店报告的「核验到店」一致。资金开关关闭期间的到店不会补记。" />
        <el-form :inline="true" size="small" class="mt8">
          <el-form-item label="商家编号">
            <el-input v-model="filter.merchantId" clearable style="width: 140px" />
          </el-form-item>
          <el-form-item label="月份">
            <el-date-picker v-model="filter.month" type="month" value-format="yyyy-MM" style="width: 140px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadBilling">查询</el-button>
            <el-button v-hasPermi="['business:merchantplans:billing']" :disabled="!salesOpen" @click="accrue">补记某一天</el-button>
            <el-button v-hasPermi="['business:merchantplans:billing']" :disabled="!salesOpen" @click="issue">开具月度账单</el-button>
          </el-form-item>
        </el-form>
        <h4>月度账单</h4>
        <el-table v-loading="loading" :data="invoices">
          <el-table-column label="商家编号" prop="merchant_id" width="100" />
          <el-table-column label="月份" prop="invoice_month" width="100" />
          <el-table-column label="到店次数" prop="visit_count" width="100" />
          <el-table-column label="金额(元)" width="100">
            <template slot-scope="s">{{ yuan(s.row.amount_fen) }}</template>
          </el-table-column>
          <el-table-column label="开具人" prop="issued_by" />
          <el-table-column label="开具时间" prop="issued_time" width="170" />
        </el-table>
        <h4 class="mt8">到店记录</h4>
        <el-table v-loading="loading" :data="charges">
          <el-table-column label="商家编号" prop="merchant_id" width="100" />
          <el-table-column label="日期" prop="visit_day" width="120" />
          <el-table-column label="单价(元)" width="100">
            <template slot-scope="s">{{ yuan(s.row.fee_fen) }}</template>
          </el-table-column>
          <el-table-column label="账单月份" prop="invoice_month" width="100" />
          <el-table-column label="已出账" width="80">
            <template slot-scope="s">{{ s.row.invoice_id ? '是' : '否' }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog title="修改套餐" :visible.sync="planDialog" width="520px" append-to-body>
      <el-form v-if="planForm" label-width="140px" size="small">
        <el-form-item label="中文名"><el-input v-model="planForm.nameZh" maxlength="32" /></el-form-item>
        <el-form-item label="英文名"><el-input v-model="planForm.nameEn" maxlength="32" /></el-form-item>
        <el-form-item label="月费(元)">
          <el-input-number v-model="planForm.monthlyYuan" :min="0" :precision="2" :disabled="planForm.code === 'FREE'" />
        </el-form-item>
        <el-form-item label="每次到店(元)">
          <el-input-number v-model="planForm.visitYuan" :min="0" :precision="2" :disabled="planForm.code === 'FREE'" />
          <div class="el-form-item__description">填 0 表示不按到店计费;月费和到店计费只能二选一。</div>
        </el-form-item>
        <el-form-item label="每周报告完整明细"><el-switch v-model="planForm.features.weeklyProofDetail" /></el-form-item>
        <el-form-item label="额外点位游戏">
          <el-input-number v-model="planForm.features.extraNodeGames" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="每月优惠图层名额">
          <el-input-number v-model="planForm.features.dealsOffersPerMonth" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="在售"><el-switch v-model="planForm.offered" :disabled="planForm.code === 'FREE'" /></el-form-item>
        <el-form-item label="修改原因"><el-input v-model="planForm.reason" maxlength="255" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" @click="savePlan">保存</el-button>
      </div>
    </el-dialog>

    <el-dialog title="开通或改为免费版" :visible.sync="grantDialog" width="460px" append-to-body>
      <el-form label-width="100px" size="small">
        <el-form-item label="商家编号"><el-input v-model="grantForm.merchantId" /></el-form-item>
        <el-form-item label="套餐">
          <el-select v-model="grantForm.planCode">
            <el-option v-for="p in plans" :key="p.code" :label="p.nameZh" :value="p.code"
              :disabled="p.code !== 'FREE' && !salesOpen" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="grantForm.planCode !== 'FREE'" label="月数">
          <el-input-number v-model="grantForm.months" :min="1" :max="12" />
        </el-form-item>
        <el-form-item label="原因"><el-input v-model="grantForm.reason" maxlength="255" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="grantDialog = false">取消</el-button>
        <el-button type="primary" @click="grant">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listTakeRates, setTakeRate, getMerchantPlans, updateMerchantPlan, listPlanOrders, listPlanSubscriptions,
  confirmPlanOrderPaid, grantMerchantPlan, listVisitCharges, listVisitInvoices, accrueVisitCharges, issueVisitInvoices
} from '@/api/business/merchantPlans'

const PRODUCTS = { official_ticket: '官方活动票', third_party_ticket: '第三方活动票', bundle: '组合套票', template: '玩法模板' }
const SOURCES = { club: '俱乐部', organiser: '独立主办方' }
const BASIS = { THEME_FROZEN_RATE: '按各主题定价时的比例', EACH_SOURCE: '按各来源的现行值', SETTLEMENT_RULE: '未找到结算规则' }
const ORDER_STATUS = { PENDING: '待确认收款', PAID: '已收款', CANCELLED: '已取消' }

export default {
  name: 'MerchantPlans',
  data() {
    return {
      tab: 'take',
      loading: false,
      salesOpen: false,
      takeRows: [],
      plans: [],
      orders: [],
      subscriptions: [],
      charges: [],
      invoices: [],
      filter: { merchantId: '', status: '', month: '' },
      planDialog: false,
      planForm: null,
      grantDialog: false,
      grantForm: { merchantId: '', planCode: 'FREE', months: 1, reason: '' }
    }
  },
  created() {
    this.loadPlans()
    this.load()
  },
  methods: {
    load() {
      if (this.tab === 'plans') return this.loadPlans()
      if (this.tab === 'orders') return this.loadOrders()
      if (this.tab === 'billing') return this.loadBilling()
      return this.loadTake()
    },
    run(promise, assign) {
      this.loading = true
      return promise.then(res => { assign(res.data) }).finally(() => { this.loading = false })
    },
    merchantQuery() {
      const id = Number(String(this.filter.merchantId || '').trim())
      return Number.isInteger(id) && id > 0 ? { merchantId: id } : {}
    },
    loadTake() { return this.run(listTakeRates(), rows => { this.takeRows = rows || [] }) },
    loadPlans() {
      return this.run(getMerchantPlans(), view => {
        this.plans = (view && view.plans) || []
        this.salesOpen = !!(view && view.salesOpen)
      })
    },
    loadOrders() {
      const q = this.merchantQuery()
      listPlanSubscriptions(q).then(res => { this.subscriptions = res.data || [] })
      return this.run(listPlanOrders({ ...q, status: this.filter.status || undefined }), rows => { this.orders = rows || [] })
    },
    loadBilling() {
      const q = { ...this.merchantQuery(), month: this.filter.month || undefined }
      listVisitInvoices(q).then(res => { this.invoices = res.data || [] })
      return this.run(listVisitCharges(q), rows => { this.charges = rows || [] })
    },
    askReason(title) {
      return this.$prompt('原因(会记入操作记录)', title, {
        inputValidator: v => (v && v.trim().length > 0) || '请填写原因',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      }).then(({ value }) => value.trim())
    },
    done(res, reload) {
      if (res && res.code === 200) this.$modal.msgSuccess('已完成')
      return reload()
    },
    editTake(row) {
      this.$prompt('新的平台抽成(百分比,0 到 100,最多两位小数)', '设置 ' + this.productText(row.product), {
        inputValidator: v => /^\d{1,3}(\.\d{1,2})?$/.test(String(v).trim()) && Number(v) <= 100 || '请填写 0 到 100',
        confirmButtonText: '下一步',
        cancelButtonText: '取消'
      }).then(({ value }) => {
        const rateBps = Math.round(Number(value) * 100)
        return this.askReason('修改原因').then(reason =>
          setTakeRate({ product: row.product, source: row.source, rateBps, reason }))
      }).then(res => this.done(res, this.loadTake)).catch(() => {})
    },
    clearTake(row) {
      this.askReason('恢复现行值').then(reason =>
        setTakeRate({ product: row.product, source: row.source, rateBps: null, reason }))
        .then(res => this.done(res, this.loadTake)).catch(() => {})
    },
    openPlan(row) {
      this.planForm = {
        code: row.code,
        nameZh: row.nameZh,
        nameEn: row.nameEn,
        monthlyYuan: row.monthlyPriceFen / 100,
        visitYuan: (row.visitFeeFen || 0) / 100,
        features: { ...row.features },
        offered: row.offered,
        reason: ''
      }
      this.planDialog = true
    },
    savePlan() {
      const f = this.planForm
      if (!f.reason || !f.reason.trim()) { this.$modal.msgError('请填写修改原因'); return }
      const visit = Math.round(f.visitYuan * 100)
      const patch = {
        nameZh: f.nameZh,
        nameEn: f.nameEn,
        monthlyPriceFen: Math.round(f.monthlyYuan * 100),
        visitFeeFen: visit > 0 ? visit : null,
        features: f.features,
        offered: f.offered
      }
      updateMerchantPlan(f.code, { patch, reason: f.reason.trim() }).then(res => {
        this.planDialog = false
        return this.done(res, this.loadPlans)
      })
    },
    openGrant() {
      this.grantForm = { merchantId: this.filter.merchantId || '', planCode: 'FREE', months: 1, reason: '' }
      this.grantDialog = true
    },
    grant() {
      const g = this.grantForm
      const merchantId = Number(String(g.merchantId).trim())
      if (!Number.isInteger(merchantId) || merchantId <= 0) { this.$modal.msgError('请填写商家编号'); return }
      if (!g.reason || !g.reason.trim()) { this.$modal.msgError('请填写原因'); return }
      grantMerchantPlan({ merchantId, planCode: g.planCode, months: g.months, reason: g.reason.trim() }).then(res => {
        this.grantDialog = false
        return this.done(res, this.loadOrders)
      })
    },
    confirmPaid(row) {
      this.askReason('确认已收到 ' + this.yuan(row.amount_fen) + ' 元').then(reason =>
        confirmPlanOrderPaid(row.order_no, { reason }))
        .then(res => this.done(res, this.loadOrders)).catch(() => {})
    },
    accrue() {
      this.$prompt('日期(年-月-日)', '补记某一天的到店', {
        inputValidator: v => /^\d{4}-\d{2}-\d{2}$/.test(String(v).trim()) || '格式:2026-11-01',
        confirmButtonText: '补记',
        cancelButtonText: '取消'
      }).then(({ value }) => accrueVisitCharges(value.trim()))
        .then(res => this.done(res, this.loadBilling)).catch(() => {})
    },
    issue() {
      if (!this.filter.month) { this.$modal.msgError('请先选择已结束的月份'); return }
      this.askReason('开具 ' + this.filter.month + ' 账单').then(reason =>
        issueVisitInvoices({ month: this.filter.month, reason }))
        .then(res => this.done(res, this.loadBilling)).catch(() => {})
    },
    productText(p) { return PRODUCTS[p] || p },
    sourceText(s) { return SOURCES[s] || s },
    basisText(b) { return BASIS[b] || '—' },
    percent(bps) { return (bps / 100).toFixed(2).replace(/\.?0+$/, '') + '%' },
    yuan(fen) { return fen == null ? '—' : (Number(fen) / 100).toFixed(2) },
    planName(code) {
      const plan = this.plans.find(p => p.code === code)
      return plan ? plan.nameZh : code
    },
    orderStatusText(s) { return ORDER_STATUS[s] || s },
    priceText(row) {
      if (row.visitFeeFen) return '每次核验到店 ' + this.yuan(row.visitFeeFen) + ' 元'
      if (!row.monthlyPriceFen) return '免费'
      return this.yuan(row.monthlyPriceFen) + ' 元 / 月'
    }
  }
}
</script>

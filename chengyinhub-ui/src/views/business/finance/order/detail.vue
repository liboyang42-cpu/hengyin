<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="资金与售后 / 订单查询"
      :title="order.orderSn || '订单详情'"
      description="订单、售后、下单时间和备注均以服务端回读的当前事实为准。"
    >
      <el-button v-if="canList" icon="el-icon-back" @click="goOrderList">返回订单查询</el-button>
    </cy-page-header>

    <cy-resource-state
      v-if="detailError"
      kind="error"
      title="订单详情暂时无法读取"
      description="请检查链接、权限或网络后重试；系统不会把读取失败显示为一张空订单。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="loadOrder($route.params.id)"
    />

    <template v-else-if="loaded">
      <section class="order-detail__summary">
        <div>
          <span>{{ isPointsSource ? '积分计价' : '实付金额（本地记录）' }}</span>
          <strong>{{ isPointsSource ? `${money(order.payAmount)} 积分` : `¥${money(order.payAmount)}` }}</strong>
        </div>
        <div>
          <span>本地保存状态（未经渠道核验）</span>
          <strong>{{ statusLabel(order.status) }}</strong>
        </div>
        <div>
          <span>下单时间</span>
          <strong>{{ order.createTime || '未记录' }}</strong>
        </div>
      </section>

      <section class="cy-panel order-detail__payment">
        <div class="cy-panel__heading">
          <div>
            <h2>支付事实核对</h2>
            <p>积分与现金分开核对。现金只以原支付渠道的查询回执为准；本地保存的订单状态不等于渠道已确认。</p>
          </div>
          <el-button
            v-if="paymentFacts && paymentFacts.source === 'CASH'"
            v-hasPermi="['business:order:query']"
            size="small"
            icon="el-icon-refresh"
            :loading="paymentQuerying"
            @click="queryPaymentChannel"
          >向渠道查询</el-button>
        </div>
        <el-alert v-if="paymentFactsError" :title="paymentFactsError" type="error" :closable="false" show-icon />
        <div v-else-if="paymentFacts" class="order-detail__payment-body">
          <template v-if="paymentFacts.source === 'POINTS'">
            <p class="order-detail__payment-row"><strong>积分来源</strong>：{{ money(paymentFacts.points && paymentFacts.points.pointsPrice) }} 积分（{{ paymentFacts.points && paymentFacts.points.note }}）</p>
            <p class="order-detail__payment-row"><strong>现金来源</strong>：无渠道交易</p>
          </template>
          <template v-else>
            <p class="order-detail__payment-row"><strong>积分来源</strong>：无</p>
            <p class="order-detail__payment-row">
              <strong>现金来源</strong>：{{ channelLabel(paymentFacts.cash.channel) }} · 商户单号 {{ paymentFacts.cash.outTradeNo || '未记录' }}
              · 本地交易号 {{ paymentFacts.cash.savedTransactionId || '未记录' }}
            </p>
            <p class="order-detail__payment-row">
              <strong>核对结论</strong>：
              <el-tag size="mini" :type="verdictTone(paymentFacts.cash.verdict)">{{ verdictLabel(paymentFacts.cash.verdict) }}</el-tag>
              {{ paymentFacts.cash.verdictDetail }} · 最后查询 {{ paymentFacts.cash.lastQueryTime || '从未查询' }}
            </p>
            <p v-if="paymentFacts.cash.receipt" class="order-detail__payment-row">
              <strong>渠道回执</strong>：{{ receiptLabel(paymentFacts.cash.receipt) }}
            </p>
          </template>
        </div>
        <p v-else class="order-detail__payment-row">正在读取支付事实…</p>
      </section>

      <section class="cy-panel">
        <cy-detail-grid title="订单事实" :items="orderItems" :columns="3">
          <template #orderSn>
            <cy-copy-value :value="order.orderSn" label="订单编号" />
          </template>
        </cy-detail-grid>
      </section>
      <section class="cy-panel">
        <cy-detail-grid title="订单时间" :items="orderTimelineItems" :columns="2" />
      </section>
      <section class="cy-panel">
        <cy-detail-grid title="状态与备注" :items="statusAndNoteItems" :columns="3" />
      </section>
      <section class="cy-panel">
        <cy-detail-grid title="退款与售后" :items="refundItems" :columns="1" />
      </section>
      <section class="cy-panel">
        <div class="cy-panel__heading">
          <div>
            <h2>商品清单</h2>
            <p>订单项是订单事实的一部分，不因页面迁移删除或重建。</p>
          </div>
        </div>
        <el-table :data="products" v-loading="loading" size="small">
          <el-table-column label="商品" prop="productName" min-width="180" />
          <el-table-column label="规格" prop="spData" min-width="180" />
          <el-table-column label="单价" min-width="110">
            <template slot-scope="scope">¥{{ money(scope.row.price) }}</template>
          </el-table-column>
          <el-table-column label="数量" prop="quantity" min-width="90" />
          <el-table-column label="售后关联" min-width="150">
            <template slot-scope="scope">
              <span class="item-aftersale">{{ itemAftersaleLabel(scope.row) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>
    <section v-else class="order-detail__loading" v-loading="loading">正在读取订单详情…</section>
  </div>
</template>

<script>
import { getOrder } from '@/api/business/order'
import { getOrderPaymentFacts, queryOrderPaymentFacts } from '@/api/business/orderPaymentFacts'
import auth from '@/plugins/auth'
import CyCopyValue from '@/components/CyCopyValue'
import CyDetailGrid from '@/components/CyDetailGrid'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

const STATUS_LABELS = {
  0: '待付款',
  1: '待发货',
  2: '已发货',
  3: '待评价',
  4: '已完成',
  5: '已关闭',
  6: '无效订单'
}

const AFTERSALE_LABELS = {
  1: '无售后 / 售后已关闭',
  2: '售后处理中',
  3: '退款中',
  4: '退款成功'
}

function hasAftersaleRecord(item) {
  if (!item) return false
  return (
    (item.aftersaleOrderId !== null && item.aftersaleOrderId !== undefined && item.aftersaleOrderId !== '')
    || Number(item.aftersaleStatus) > 0
  )
}

export default {
  name: 'FinanceOrderDetail',
  components: { CyCopyValue, CyDetailGrid, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      loaded: false,
      detailError: false,
      detailRequestId: 0,
      order: {},
      products: [],
      paymentFacts: null,
      paymentFactsError: '',
      paymentFactsRequestId: 0,
      paymentQuerying: false
    }
  },
  computed: {
    canList() {
      return auth.hasPermi('business:order:list')
    },
    isPointsSource() {
      return Boolean(this.paymentFacts && this.paymentFacts.source === 'POINTS')
    },
    orderItems() {
      return [
        { key: 'orderSn', label: '订单编号', value: this.order.orderSn },
        { key: 'memberId', label: '下单用户', value: this.order.memberId },
        { key: 'nickname', label: '用户昵称', value: this.order.nickname },
        { key: 'productTotalAmount', label: '商品金额', value: this.money(this.order.productTotalAmount) },
        { key: 'deliveryFee', label: '配送金额', value: this.money(this.order.deliveryFee) },
        { key: 'orderTotalAmount', label: '订单合计', value: this.money(this.order.orderTotalAmount) }
      ]
    },
    orderTimelineItems() {
      return [
        { key: 'createTime', label: '下单时间', value: this.order.createTime },
        { key: 'paymentTime', label: '支付时间', value: this.order.paymentTime }
      ]
    },
    statusAndNoteItems() {
      return [
        { key: 'status', label: '订单状态（本地保存）', value: this.statusLabel(this.order.status) },
        { key: 'merchantNote', label: '平台备注', value: this.order.merchantNote },
        { key: 'orderNote', label: '用户备注', value: this.order.orderNote, span: 3 }
      ]
    },
    refundItems() {
      const aftersaleItemCount = this.products.filter(hasAftersaleRecord).length
      return [
        { key: 'aftersaleStatus', label: '订单级售后状态', value: this.aftersaleLabel(this.order.aftersaleStatus) },
        { key: 'aftersaleItemCount', label: '售后记录订单项', value: aftersaleItemCount ? `${aftersaleItemCount} 个` : '无售后记录订单项' }
      ]
    }
  },
  created() {
    this.loadOrder(this.$route.params.id)
    this.loadPaymentFacts(this.$route.params.id)
  },
  watch: {
    '$route.params.id'(id) {
      this.loadOrder(id)
      this.loadPaymentFacts(id)
    }
  },
  methods: {
    loadOrder(id) {
      const requestId = ++this.detailRequestId
      this.loading = true
      this.loaded = false
      this.detailError = false
      return getOrder(id).then(response => {
        if (requestId !== this.detailRequestId) return
        this.order = response.data || {}
        this.products = this.order.products || []
        this.loaded = true
      }).catch(() => {
        if (requestId !== this.detailRequestId) return
        this.detailError = true
        this.$modal.msgError('订单详情读取失败')
      }).finally(() => {
        if (requestId === this.detailRequestId) this.loading = false
      })
    },
    loadPaymentFacts(id) {
      const requestId = ++this.paymentFactsRequestId
      this.paymentFacts = null
      this.paymentFactsError = ''
      return getOrderPaymentFacts(id).then(response => {
        if (requestId !== this.paymentFactsRequestId) return
        this.paymentFacts = response.data || null
        if (!this.paymentFacts) this.paymentFactsError = '支付事实暂时无法读取，请稍后重试。'
      }).catch(() => {
        if (requestId === this.paymentFactsRequestId) this.paymentFactsError = '支付事实暂时无法读取，请稍后重试。'
      })
    },
    queryPaymentChannel() {
      if (this.paymentQuerying || !this.paymentFacts) return Promise.resolve()
      const id = this.paymentFacts.orderId
      const requestId = ++this.paymentFactsRequestId
      this.paymentQuerying = true
      return queryOrderPaymentFacts(id).then(response => {
        if (requestId !== this.paymentFactsRequestId) return
        if (response.data) this.paymentFacts = response.data
      }).catch(() => {}).finally(() => {
        this.paymentQuerying = false
      })
    },
    verdictLabel(verdict) {
      return ({ UNCONFIRMED: '未确认', CONFIRMED: '已确认', CONFLICT: '冲突' })[verdict] || '未确认'
    },
    verdictTone(verdict) {
      return ({ CONFIRMED: 'success', CONFLICT: 'danger' })[verdict] || 'warning'
    },
    channelLabel(channel) {
      return channel === 'WECHAT' ? '微信支付' : (channel || '未知渠道')
    },
    receiptLabel(receipt) {
      if (!receipt) return '无'
      if (receipt.queryStatus === 'FAILED') return `查询失败（${receipt.errorMessage || '未返回原因'}）`
      if (receipt.queryStatus === 'NOT_EXIST') return '渠道无此交易'
      const total = receipt.channelTotalFen === null || receipt.channelTotalFen === undefined
        ? '未返回' : `¥${(Number(receipt.channelTotalFen) / 100).toFixed(2)}`
      return `渠道状态 ${receipt.channelTradeState || '未返回'} · 渠道交易号 ${receipt.channelTransactionId || '未返回'} · 渠道金额 ${total}`
    },
    goOrderList() {
      if (!auth.hasPermi('business:order:list')) return
      this.$router.push({ name: 'financeOrder', query: Object.assign({}, this.$route.query) })
    },
    statusLabel(status) {
      return STATUS_LABELS[status] || '状态未记录'
    },
    aftersaleLabel(status) {
      return AFTERSALE_LABELS[status] || '未记录'
    },
    itemAftersaleLabel(item) {
      if (item && item.aftersaleOrderId !== null && item.aftersaleOrderId !== undefined && item.aftersaleOrderId !== '') {
        return `关联售后单 #${item.aftersaleOrderId}`
      }
      if (Number(item && item.aftersaleStatus) === 0) return '未申请售后'
      return item && item.aftersaleStatus !== null && item.aftersaleStatus !== undefined ? '已记录售后状态' : '售后信息未记录'
    },
    money(value) {
      return Number(value || 0).toFixed(2)
    }
  }
}
</script>

<style lang="scss" scoped>
.order-detail__summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }
.order-detail__summary > div { padding: 16px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); }
.order-detail__summary span, .order-detail__summary strong { display: block; }
.order-detail__summary span { color: var(--cy-color-text-tertiary); font-size: 13px; }
.order-detail__summary strong { margin-top: 8px; color: var(--cy-color-text-primary); font-size: 18px; }
.item-aftersale { color: var(--cy-color-text-secondary); }
.cy-panel + .cy-panel { margin-top: 16px; }
.order-detail__payment-body { padding: 0 var(--cy-space-page) 16px; }
.order-detail__payment-row { margin: 0 0 8px; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.7; }
.order-detail__payment-row strong { color: var(--cy-color-text-primary); }
.order-detail__loading { min-height: 180px; padding: 48px 0; color: var(--cy-color-text-secondary); text-align: center; }
@media (max-width: 768px) { .order-detail__summary { grid-template-columns: 1fr; } }
</style>

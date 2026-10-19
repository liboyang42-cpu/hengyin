<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="供给与权益"
      title="平台兑换记录"
      description="优惠券领取/核销、积分商城订单、礼物消费分开列出，每行保留原始业务编号、持有人、数量和履约状态；不同来源、同名对象不合并汇总。"
    />

    <el-tabs v-model="activeSource" @tab-click="handleSourceChange">
      <el-tab-pane v-for="source in visibleSources" :key="source.key" :label="source.label" :name="source.key" />
    </el-tabs>

    <cy-resource-state
      v-if="!visibleSources.length"
      kind="forbidden"
      title="没有可查看的兑换来源"
      description="当前账号没有优惠券流水、订单或礼物消费的列表权限。"
    />

    <template v-else>
      <section class="cy-panel redemption-records__filters">
        <el-form :model="query" size="small" :inline="true" label-width="84px">
          <el-form-item label="持有人">
            <el-input v-model="query.memberId" placeholder="精确用户 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item v-if="activeSource === 'coupon'" label="优惠券编号">
            <el-input v-model="query.couponId" placeholder="精确券 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item v-if="activeSource === 'coupon'" label="使用状态">
            <el-select v-model="query.useStatus" placeholder="全部状态" clearable>
              <el-option label="未使用" :value="0" />
              <el-option label="已核销" :value="1" />
              <el-option label="已过期" :value="2" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="activeSource === 'points'" label="订单编号">
            <el-input v-model="query.orderSn" placeholder="精确订单编号" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item v-if="activeSource === 'gift'" label="礼物编号">
            <el-input v-model="query.giftId" placeholder="精确礼物 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="handleQuery">查询</el-button>
            <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </section>

      <cy-resource-state
        v-if="loadError"
        kind="error"
        title="兑换记录暂时无法读取"
        description="请重新读取；系统不会把读取失败显示成没有记录。"
        retry-text="重新读取"
        :retrying="loading"
        @retry="getList"
      />
      <el-table v-else v-loading="loading" :data="rows" size="small" :empty-text="emptyText">
        <el-table-column label="原始业务编号" min-width="170">
          <template slot-scope="scope">
            <strong>{{ businessIdLabel(scope.row) }}</strong>
            <div class="redemption-records__sub">{{ subjectLabel(scope.row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="持有人" width="120">
          <template slot-scope="scope">{{ scope.row.memberId ? `用户 #${scope.row.memberId}` : '未记录' }}</template>
        </el-table-column>
        <el-table-column label="数量" width="80">
          <template slot-scope="scope">{{ quantityOf(scope.row) }}</template>
        </el-table-column>
        <el-table-column v-if="activeSource !== 'coupon'" label="积分" width="100">
          <template slot-scope="scope">{{ pointsOf(scope.row) }}</template>
        </el-table-column>
        <el-table-column label="履约状态" width="150">
          <template slot-scope="scope">
            <cy-status-tag :label="fulfilment(scope.row).label" :tone="fulfilment(scope.row).tone" />
          </template>
        </el-table-column>
        <el-table-column label="发生时间" width="170">
          <template slot-scope="scope">{{ occurredAt(scope.row) }}</template>
        </el-table-column>
      </el-table>
      <pagination
        v-show="total > 0"
        :total="total"
        :page.sync="query.pageNum"
        :limit.sync="query.pageSize"
        @pagination="getList"
      />
    </template>
  </div>
</template>

<script>
import { listCouponRedemptions, listPointsMallRedemptions, listGiftRedemptions } from '@/api/business/redemption'
import { checkPermi } from '@/utils/permission'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const SOURCES = [
  { key: 'coupon', label: '优惠券领取 / 核销', permission: 'business:couponhistory:list' },
  { key: 'points', label: '积分商城订单', permission: 'business:order:list' },
  { key: 'gift', label: '礼物消费', permission: 'business:crowdfunding:list' }
]

const ORDER_STATUS = {
  0: { label: '待付款', tone: 'warning' },
  1: { label: '待发货', tone: 'warning' },
  2: { label: '已发货', tone: 'info' },
  3: { label: '待评价', tone: 'success' },
  4: { label: '已完成', tone: 'success' },
  5: { label: '已关闭', tone: 'neutral' },
  6: { label: '无效订单', tone: 'danger' }
}

const createQuery = () => ({
  pageNum: 1,
  pageSize: 10,
  memberId: null,
  couponId: null,
  useStatus: null,
  orderSn: null,
  giftId: null
})

export default {
  name: 'PlatformRedemptionRecords',
  components: { CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      activeSource: 'coupon',
      loading: false,
      loadError: false,
      requestId: 0,
      rows: [],
      total: 0,
      query: createQuery()
    }
  },
  computed: {
    visibleSources() {
      return SOURCES.filter(source => checkPermi([source.permission]))
    },
    emptyText() {
      const source = SOURCES.find(item => item.key === this.activeSource)
      return `当前条件下没有${source ? source.label : ''}记录`
    }
  },
  created() {
    const requested = this.$route && this.$route.query && this.$route.query.source
    const allowed = this.visibleSources.map(source => source.key)
    this.activeSource = allowed.indexOf(requested) !== -1 ? requested : (allowed[0] || 'coupon')
    if (allowed.length) this.getList()
  },
  methods: {
    requestFor(source) {
      return { coupon: listCouponRedemptions, points: listPointsMallRedemptions, gift: listGiftRedemptions }[source]
    },
    paramsFor(source) {
      const base = { pageNum: this.query.pageNum, pageSize: this.query.pageSize, memberId: this.query.memberId || null }
      if (source === 'coupon') return Object.assign(base, { couponId: this.query.couponId || null, useStatus: this.query.useStatus })
      if (source === 'points') return Object.assign(base, { orderSn: this.query.orderSn || null })
      return Object.assign(base, { giftId: this.query.giftId || null })
    },
    getList() {
      const source = this.activeSource
      const requestId = ++this.requestId
      this.loading = true
      this.loadError = false
      return this.requestFor(source)(this.paramsFor(source)).then(response => {
        if (requestId !== this.requestId) return
        this.rows = (response.rows || []).map(row => Object.assign({ source }, row))
        this.total = response.total || 0
      }).catch(() => {
        if (requestId !== this.requestId) return
        this.rows = []
        this.total = 0
        this.loadError = true
      }).finally(() => {
        if (requestId === this.requestId) this.loading = false
      })
    },
    handleSourceChange() {
      this.query = createQuery()
      this.rows = []
      this.total = 0
      return this.getList()
    },
    handleQuery() {
      this.query.pageNum = 1
      return this.getList()
    },
    resetQuery() {
      this.query = createQuery()
      return this.getList()
    },
    businessIdLabel(row) {
      if (row.source === 'coupon') return `领取记录 #${row.historyId}`
      if (row.source === 'points') return row.orderSn ? `订单 ${row.orderSn}` : `订单 #${row.orderId}`
      return `礼物消费 #${row.consumptionId}`
    },
    subjectLabel(row) {
      if (row.source === 'coupon') return `${row.couponName || '未命名券'} · 券 #${row.couponId}${row.couponCode ? ` · ${row.couponCode}` : ''}`
      if (row.source === 'points') return `订单 #${row.orderId} · ${row.itemCount || 0} 个订单项`
      return `${row.giftName || '未命名礼物'} · 礼物 #${row.giftId} · 活动 #${row.activityId || '—'}`
    },
    quantityOf(row) {
      if (row.source === 'points') return row.quantity === null || row.quantity === undefined ? '未记录' : row.quantity
      return 1
    },
    pointsOf(row) {
      if (row.source === 'points') return row.pointsPrice === null || row.pointsPrice === undefined ? '未记录' : row.pointsPrice
      if (row.source === 'gift') return row.points === null || row.points === undefined ? '未记录' : row.points
      return '—'
    },
    fulfilment(row) {
      if (row.source === 'coupon') {
        if (Number(row.useStatus) === 1) return { label: '已核销', tone: 'success' }
        if (Number(row.couponStatus) === 3) return { label: '已平台失效', tone: 'danger' }
        if (Number(row.useStatus) === 2) return { label: '已过期', tone: 'neutral' }
        if (Number(row.useStatus) === 0) return { label: '已领取 · 未使用', tone: 'info' }
        return { label: '状态未记录', tone: 'neutral' }
      }
      if (row.source === 'points') {
        if (Number(row.aftersaleStatus) === 4) return { label: '已退款', tone: 'neutral' }
        if (Number(row.aftersaleStatus) === 2 || Number(row.aftersaleStatus) === 3) return { label: '售后处理中', tone: 'warning' }
        return ORDER_STATUS[row.status] || { label: '状态未记录', tone: 'neutral' }
      }
      if (row.delFlag !== null && row.delFlag !== undefined && Number(row.delFlag) !== 0) return { label: '已撤销', tone: 'neutral' }
      return { label: '已赠出', tone: 'success' }
    },
    occurredAt(row) {
      const value = row.source === 'coupon' ? (row.useTime || row.getTime)
        : row.source === 'points' ? (row.paymentTime || row.createTime) : row.createTime
      return value ? String(value).replace('T', ' ').slice(0, 19) : '未记录'
    }
  }
}
</script>

<style lang="scss" scoped>
.redemption-records__filters { margin-bottom: 16px; padding: 16px 20px 0; }
.redemption-records__sub { margin-top: 4px; color: var(--cy-color-text-tertiary); font-size: 12px; }
</style>

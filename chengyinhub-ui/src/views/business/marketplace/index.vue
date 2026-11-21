<template>
  <div class="app-container">
    <el-tabs v-model="tab" @tab-click="load">
      <el-tab-pane label="地图优惠" name="deals">
        <el-alert type="info" :closable="false" show-icon
          title="玩家在漫游地图上自行打开「优惠」图层才会看到。只有在这里开启的商家优惠券会出现;付费不影响迷雾地图和点位排序。" />
        <el-form :inline="true" size="small" class="mt8">
          <el-form-item label="优惠券编号">
            <el-input v-model="newCouponId" clearable style="width: 160px" placeholder="商家券 ID" />
          </el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['business:marketplace:edit']" type="primary" icon="el-icon-plus" @click="addDeal">开启</el-button>
            <el-button icon="el-icon-refresh" @click="loadDeals">刷新</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="deals">
          <el-table-column label="券编号" prop="couponId" width="90" />
          <el-table-column label="优惠" prop="couponName" />
          <el-table-column label="商家" prop="merchantName" />
          <el-table-column label="领取 / 发行" width="120">
            <template slot-scope="s">{{ s.row.receiveCount || 0 }} / {{ s.row.publishCount > 0 ? s.row.publishCount : '不限' }}</template>
          </el-table-column>
          <el-table-column label="地图领取" prop="claimCount" width="90" />
          <el-table-column label="截止" prop="endTime" width="170" />
          <el-table-column label="状态" width="80">
            <template slot-scope="s">{{ s.row.enabled === 1 ? '展示中' : '已暂停' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:marketplace:edit']" type="text" @click="toggleDeal(s.row)">
                {{ s.row.enabled === 1 ? '暂停' : '恢复' }}
              </el-button>
              <el-button v-hasPermi="['business:marketplace:edit']" type="text" @click="removeDeal(s.row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="推广曝光位订单" name="promotion">
        <el-alert type="warning" :closable="false" show-icon
          title="推广曝光位已下架,新订单不再受理。下列为已付款订单:该权益从未在地图、搜索或活动中生效,请业主决定是否退款(退款走商家原路退款)。" />
        <el-table v-loading="loading" :data="purchases" class="mt8">
          <el-table-column label="订单号" prop="orderSn" width="230" />
          <el-table-column label="商家" prop="merchantName" />
          <el-table-column label="金额(元)" prop="payAmount" width="100" />
          <el-table-column label="付款时间" prop="paymentTime" width="170" />
          <el-table-column label="权益天数" prop="entitlementDays" width="90" />
          <el-table-column label="退款" width="110">
            <template slot-scope="s">{{ refundText(s.row.refundState) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="销售线索" name="leads">
        <el-alert type="info" :closable="false" show-icon
          title="玩家在未入驻门店点亮的地点,按店名和约 1 公里范围汇总;至少 2 位不同玩家到过才列出,不含任何玩家信息。" />
        <el-form :inline="true" size="small" class="mt8">
          <el-form-item label="最近天数">
            <el-input-number v-model="leadQuery.days" :min="1" :max="365" />
          </el-form-item>
          <el-form-item label="至少到店人数">
            <el-input-number v-model="leadQuery.minVisitors" :min="2" :max="1000" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadLeads">查询</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="leads">
          <el-table-column label="店名" prop="name" />
          <el-table-column label="大致位置" width="200">
            <template slot-scope="s">{{ s.row.areaLat }}, {{ s.row.areaLng }}</template>
          </el-table-column>
          <el-table-column label="到店人数" prop="checkinCount" width="100" />
          <el-table-column label="最近一次" prop="lastSeen" width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import {
  listMarketplaceDeals, setMarketplaceDeal, removeMarketplaceDeal,
  listPromotionSlotPurchases, listMarketplaceLeads
} from '@/api/business/marketplace'

export default {
  name: 'Marketplace',
  data() {
    return {
      tab: 'deals',
      loading: false,
      deals: [],
      purchases: [],
      leads: [],
      newCouponId: '',
      leadQuery: { days: 90, minVisitors: 2 }
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      if (this.tab === 'promotion') return this.loadPurchases()
      if (this.tab === 'leads') return this.loadLeads()
      return this.loadDeals()
    },
    run(promise, assign) {
      this.loading = true
      return promise.then(res => { assign(res.data || []) }).finally(() => { this.loading = false })
    },
    loadDeals() { return this.run(listMarketplaceDeals(), rows => { this.deals = rows }) },
    loadPurchases() { return this.run(listPromotionSlotPurchases(), rows => { this.purchases = rows }) },
    loadLeads() { return this.run(listMarketplaceLeads(this.leadQuery), rows => { this.leads = rows }) },
    addDeal() {
      const id = Number(String(this.newCouponId).trim())
      if (!Number.isInteger(id) || id <= 0) { this.$modal.msgError('请填写优惠券编号'); return }
      setMarketplaceDeal(id, true).then(() => {
        this.$modal.msgSuccess('已开启')
        this.newCouponId = ''
        this.loadDeals()
      })
    },
    toggleDeal(row) {
      setMarketplaceDeal(row.couponId, row.enabled !== 1).then(() => this.loadDeals())
    },
    removeDeal(row) {
      this.$modal.confirm('移除后玩家地图上不再显示这张优惠,已领取的券不受影响。').then(() =>
        removeMarketplaceDeal(row.couponId)).then(() => this.loadDeals()).catch(() => {})
    },
    refundText(state) {
      return { none: '未申请', requested: '退款处理中', settled: '已退款' }[state] || '未知'
    }
  }
}
</script>

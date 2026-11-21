<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="资金与售后"
      title="分账收款"
      description="平台只为自己的官方票收款；商家和俱乐部卖的票由他们自己的子商户号收款，平台的服务费通过分账拿到，不经过平台账户再转给他们。"
    />

    <el-alert
      v-if="!overview.modelActive"
      type="info"
      :closable="false"
      show-icon
      title="收款模式未生效：资金开关的服务端上限关闭，下单和结算与现在完全一样。只在测试环境打开上限后才会按下面的方式收款。"
      style="margin-bottom: 16px"
    />
    <el-alert
      v-else-if="!overview.splitRailsReady"
      type="warning"
      :closable="false"
      show-icon
      title="收款模式已生效，但收付通的下单路由或分账开关没开：第三方卖家的付费票会被拒绝（不会改由平台收款）。"
      style="margin-bottom: 16px"
    />

    <section class="cy-panel" aria-label="收款方式">
      <div class="cy-panel__heading"><div><h2>收款方式</h2><p>按卖家类型，来自服务器配置。</p></div></div>
      <el-descriptions :column="3" size="small" border>
        <el-descriptions-item v-for="(policy, type) in overview.policies || {}" :key="type" :label="sellerLabel(type)">
          {{ policyLabel(policy) }}
        </el-descriptions-item>
        <el-descriptions-item label="收付通总开关">{{ onOff(overview.ecommerceEnabled) }}</el-descriptions-item>
        <el-descriptions-item label="卖家下单路由">{{ onOff(overview.sellerRoutingEnabled) }}</el-descriptions-item>
        <el-descriptions-item label="分账">{{ onOff(overview.profitSharingEnabled) }}</el-descriptions-item>
      </el-descriptions>
    </section>

    <el-tabs v-model="tab" style="margin-top: 16px" @tab-click="loadTab">
      <el-tab-pane label="卖家收款状态" name="sellers">
        <el-form :inline="true" size="small">
          <el-form-item label="卖家类型">
            <el-select v-model="sellerQuery.sellerType" clearable placeholder="全部" @change="loadSellers">
              <el-option label="商家" value="MERCHANT" />
              <el-option label="俱乐部" value="CLUB" />
            </el-select>
          </el-form-item>
          <el-form-item label="收款账户">
            <el-select v-model="sellerQuery.bound" clearable placeholder="全部" @change="loadSellers">
              <el-option label="已开通" :value="true" />
              <el-option label="未开通" :value="false" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="sellers" size="small" empty-text="没有卖家">
          <el-table-column label="类型" min-width="80">
            <template slot-scope="scope">{{ sellerLabel(scope.row.sellerType) }}</template>
          </el-table-column>
          <el-table-column label="ID" prop="sellerId" min-width="80" />
          <el-table-column label="名称" prop="sellerName" min-width="160" />
          <el-table-column label="收款账户" min-width="100">
            <template slot-scope="scope">{{ scope.row.bound ? '已开通' : '未开通' }}</template>
          </el-table-column>
          <el-table-column label="进件状态" prop="applymentState" min-width="120" />
          <el-table-column label="被拒购买次数" prop="refusedCount" min-width="110" />
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top: 12px"
                  title="未开通的卖家由运营在「电商收付通」页面代为登记并提交进件；商家可在小程序「收款账户」页看到进度。" />
      </el-tab-pane>

      <el-tab-pane label="分账台账" name="ledger">
        <el-form :inline="true" size="small">
          <el-form-item label="状态">
            <el-select v-model="ledgerQuery.state" clearable placeholder="全部" @change="loadLedger">
              <el-option label="待分账" value="OPEN" />
              <el-option label="已计划" value="PLANNED" />
              <el-option label="已全额退款" value="REFUNDED" />
              <el-option label="由合作账本分账" value="COOP" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['business:collection:edit']" type="primary" plain @click="planDue">计划到期分账</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="ledger" size="small" empty-text="还没有分账收款订单">
          <el-table-column label="订单号" prop="registrationNo" min-width="170" />
          <el-table-column label="卖家" min-width="120">
            <template slot-scope="scope">{{ sellerLabel(scope.row.sellerType) }} {{ scope.row.sellerId }}</template>
          </el-table-column>
          <el-table-column label="实付" min-width="80"><template slot-scope="scope">{{ yuan(scope.row.totalFen) }}</template></el-table-column>
          <el-table-column label="平台费率" prop="takeRate" min-width="80" />
          <el-table-column label="平台应得" min-width="80"><template slot-scope="scope">{{ yuan(scope.row.platformPlannedFen) }}</template></el-table-column>
          <el-table-column label="平台已得" min-width="80"><template slot-scope="scope">{{ yuan(scope.row.platformReceivedFen) }}</template></el-table-column>
          <el-table-column label="已回退" min-width="80"><template slot-scope="scope">{{ yuan(scope.row.platformReturnedFen) }}</template></el-table-column>
          <el-table-column label="卖家留存" min-width="90"><template slot-scope="scope">{{ yuan(sellerKept(scope.row)) }}</template></el-table-column>
          <el-table-column label="已退款" min-width="80"><template slot-scope="scope">{{ yuan(scope.row.refundedFen) }}</template></el-table-column>
          <el-table-column label="台账状态" prop="collectionState" min-width="90" />
          <el-table-column label="分账单状态" prop="shareState" min-width="100" />
          <el-table-column label="说明" min-width="140">
            <template slot-scope="scope">{{ scope.row.manualReason || scope.row.note || '' }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="失败队列" name="failures">
        <el-table v-loading="loading" :data="failures" size="small" empty-text="没有失败">
          <el-table-column label="类型" min-width="90">
            <template slot-scope="scope">{{ scope.row.kind === 'SHARE' ? '分账转人工' : '分账回退失败' }}</template>
          </el-table-column>
          <el-table-column label="订单号" prop="registrationNo" min-width="170" />
          <el-table-column label="卖家" min-width="120">
            <template slot-scope="scope">{{ sellerLabel(scope.row.sellerType) }} {{ scope.row.sellerId }}</template>
          </el-table-column>
          <el-table-column label="金额" min-width="80"><template slot-scope="scope">{{ yuan(scope.row.amountFen) }}</template></el-table-column>
          <el-table-column label="原因" prop="reason" min-width="180" />
          <el-table-column label="操作" min-width="120">
            <template slot-scope="scope">
              <el-button v-if="scope.row.kind === 'SHARE'" v-hasPermi="['business:collection:edit']" size="mini" plain
                         @click="replan(scope.row)">重新计划</el-button>
              <el-button v-else v-hasPermi="['business:collection:edit']" size="mini" plain
                         @click="retry(scope.row)">重试回退</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="被拒购买" name="refusals">
        <el-table v-loading="loading" :data="refusals" size="small" empty-text="没有被拒的购买">
          <el-table-column label="卖家" min-width="120">
            <template slot-scope="scope">{{ sellerLabel(scope.row.sellerType) }} {{ scope.row.sellerId }}</template>
          </el-table-column>
          <el-table-column label="原因" min-width="160"><template slot-scope="scope">{{ reasonLabel(scope.row.reason) }}</template></el-table-column>
          <el-table-column label="次数" prop="refusedCount" min-width="70" />
          <el-table-column label="首次" prop="firstAt" min-width="150" />
          <el-table-column label="最近" prop="lastAt" min-width="150" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import {
  getCollectionOverview, listCollectionSellers, listCollectionLedger, listCollectionFailures,
  listCollectionRefusals, replanCollectionOrder, retryCollectionReturn, planDueCollection
} from '@/api/business/collection'

const SELLERS = { PLATFORM: '平台(官方票)', MERCHANT: '商家', CLUB: '俱乐部', MEMBER: '个人', CLUB_DUES: '俱乐部会费', DUES: '俱乐部会费', UNKNOWN: '未知' }
const POLICIES = { PLATFORM: '平台收款', SPLIT: '卖家子商户收款 + 分账', REFUSE: '暂停在线购买' }
const REASONS = {
  SELLER_NOT_BOUND: '卖家没有开通收款账户',
  SELLER_UNKNOWN: '查不到卖家',
  POLICY_REFUSE: '配置为暂停在线购买',
  SPLIT_RAILS_OFF: '收付通开关未开',
  SUB_MCHID_IS_PLATFORM: '卖家子商户号等于平台商户号',
  DUES_SPLIT_NOT_BUILT: '会费还没有分账收款通道',
  APP_CHANNEL_SPLIT_NOT_BUILT: 'App 支付还没有分账收款通道'
}

export default {
  name: 'CollectionModel',
  data() {
    return {
      loading: false,
      tab: 'sellers',
      overview: {},
      sellers: [],
      ledger: [],
      failures: [],
      refusals: [],
      sellerQuery: { sellerType: '', bound: '' },
      ledgerQuery: { state: '' }
    }
  },
  created() {
    getCollectionOverview().then(res => { this.overview = res.data || {} })
    this.loadSellers()
  },
  methods: {
    onOff(v) { return v ? '开' : '关' },
    sellerLabel(t) { return SELLERS[t] || t },
    policyLabel(p) { return POLICIES[p] || p },
    reasonLabel(r) { return REASONS[r] || r },
    yuan(fen) { return fen === null || fen === undefined ? '' : (Number(fen) / 100).toFixed(2) },
    sellerKept(row) {
      return Number(row.totalFen || 0) - Number(row.refundedFen || 0) - Number(row.platformReceivedFen || 0) + Number(row.platformReturnedFen || 0)
    },
    loadTab() {
      if (this.tab === 'sellers') this.loadSellers()
      if (this.tab === 'ledger') this.loadLedger()
      if (this.tab === 'failures') this.loadFailures()
      if (this.tab === 'refusals') this.loadRefusals()
    },
    run(promise, target) {
      this.loading = true
      promise.then(res => { this[target] = res.data || [] }).finally(() => { this.loading = false })
    },
    loadSellers() {
      const params = { limit: 200 }
      if (this.sellerQuery.sellerType) params.sellerType = this.sellerQuery.sellerType
      if (this.sellerQuery.bound !== '') params.bound = this.sellerQuery.bound
      this.run(listCollectionSellers(params), 'sellers')
    },
    loadLedger() {
      const params = { limit: 200 }
      if (this.ledgerQuery.state) params.state = this.ledgerQuery.state
      this.run(listCollectionLedger(params), 'ledger')
    },
    loadFailures() { this.run(listCollectionFailures(100), 'failures') },
    loadRefusals() { this.run(listCollectionRefusals(100), 'refusals') },
    replan(row) {
      replanCollectionOrder(row.registrationNo).then(res => {
        this.$modal.msgSuccess('结果：' + res.data)
        this.loadFailures()
      })
    },
    retry(row) {
      retryCollectionReturn(row.id).then(res => {
        this.$modal.msgSuccess('结果：' + res.data)
        this.loadFailures()
      })
    },
    planDue() {
      planDueCollection().then(res => {
        this.$modal.msgSuccess('已计划 ' + res.data + ' 笔')
        this.loadLedger()
      })
    }
  }
}
</script>

<template>
  <div class="app-container ecom-payout">
    <cy-page-header eyebrow="资金与售后" title="电商收付通">
      <cy-status-tag :label="switchLabel('ecommerceEnabled', '总开关')" :tone="switches.ecommerceEnabled ? 'success' : 'neutral'" />
      <cy-status-tag :label="switchLabel('sellerRoutingEnabled', '按卖家收款')" :tone="switches.sellerRoutingEnabled ? 'success' : 'neutral'" />
      <cy-status-tag :label="switchLabel('profitSharingEnabled', '分账')" :tone="switches.profitSharingEnabled ? 'success' : 'neutral'" />
    </cy-page-header>

    <el-tabs v-model="tab" @tab-click="load">
      <el-tab-pane label="进件审核" name="onboarding">
        <div class="ecom-payout__bar">
          <el-select v-model="filters.onboarding" clearable placeholder="全部状态" size="small" @change="load">
            <el-option v-for="(label, key) in applymentStates" :key="key" :label="label" :value="key" />
          </el-select>
          <el-select v-model="register.ownerType" size="small" style="width: 110px">
            <el-option label="商家" value="MERCHANT" />
            <el-option label="俱乐部" value="CLUB" />
          </el-select>
          <el-input v-model="register.ownerId" size="small" placeholder="商家 / 俱乐部 ID" style="width: 160px" />
          <el-button size="small" type="primary" v-hasPermi="['business:ecommercepayout:edit']" @click="doRegister">登记</el-button>
          <el-button size="small" v-hasPermi="['business:ecommercepayout:edit']" @click="run(syncOnboarding)">同步审核中</el-button>
        </div>
        <el-table v-loading="loading" :data="subMerchants" empty-text="暂无进件">
          <el-table-column label="主体" min-width="140">
            <template slot-scope="{ row }">{{ row.ownerType === 'CLUB' ? '俱乐部' : '商家' }} #{{ row.ownerId }}</template>
          </el-table-column>
          <el-table-column label="申请编号" prop="outRequestNo" min-width="150" />
          <el-table-column label="状态" min-width="120">
            <template slot-scope="{ row }">
              <cy-status-tag :label="applymentStates[row.applymentState] || row.applymentState" :tone="applymentTone(row.applymentState)" />
            </template>
          </el-table-column>
          <el-table-column label="微信说明" prop="applymentStateDesc" min-width="200" show-overflow-tooltip />
          <el-table-column label="二级商户号" prop="subMchid" min-width="130" />
          <el-table-column label="更新时间" min-width="160">
            <template slot-scope="{ row }">{{ parseTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template slot-scope="{ row }">
              <el-button v-if="row.applymentState !== 'DRAFT'" type="text" size="mini" v-hasPermi="['business:ecommercepayout:edit']" @click="run(() => refreshSubMerchant(row.id))">刷新状态</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="分账记录" name="shares">
        <div class="ecom-payout__bar">
          <el-select v-model="filters.share" clearable placeholder="全部状态" size="small" @change="load">
            <el-option v-for="(label, key) in shareStates" :key="key" :label="label" :value="key" />
          </el-select>
          <el-input v-model="filters.topicId" size="small" clearable placeholder="主题 ID" style="width: 140px" @change="load" />
          <el-button size="small" type="primary" v-hasPermi="['business:ecommercepayout:edit']" @click="run(submitDueShares)">提交到期分账</el-button>
          <el-button size="small" v-hasPermi="['business:ecommercepayout:edit']" @click="run(syncShares)">查询处理中</el-button>
        </div>
        <el-table v-loading="loading" :data="shareOrders" empty-text="暂无分账" @expand-change="expand">
          <el-table-column type="expand">
            <template slot-scope="{ row }">
              <el-table :data="lines[row.id] || []" size="mini" empty-text="无明细">
                <el-table-column label="结算行" prop="settlementId" />
                <el-table-column label="类型" prop="lineType" />
                <el-table-column label="接收方">
                  <template slot-scope="scope">{{ receiverLabels[scope.row.receiverKind] || scope.row.receiverKind }}</template>
                </el-table-column>
                <el-table-column label="接收商户号" prop="receiverMchid" />
                <el-table-column label="金额">
                  <template slot-scope="scope">¥{{ yuan(scope.row.amountFen) }}</template>
                </el-table-column>
              </el-table>
            </template>
          </el-table-column>
          <el-table-column label="分账单号" prop="outOrderNo" min-width="170" />
          <el-table-column label="支付单" prop="outTradeNo" min-width="150" />
          <el-table-column label="主题" prop="topicId" width="80" />
          <el-table-column label="实付" width="100">
            <template slot-scope="{ row }">¥{{ yuan(row.totalFen) }}</template>
          </el-table-column>
          <el-table-column label="分出" width="100">
            <template slot-scope="{ row }">¥{{ yuan(row.sharedFen) }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="150">
            <template slot-scope="{ row }">
              <cy-status-tag :label="shareStates[row.state] || row.state" :tone="shareTone(row.state)" />
              <div v-if="row.manualReason" class="ecom-payout__reason">{{ reasonLabels[row.manualReason] || row.manualReason }}</div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="回退与退款" name="refunds">
        <h4 class="ecom-payout__title">分账回退</h4>
        <el-table v-loading="loading" :data="returns" empty-text="暂无回退">
          <el-table-column label="回退单号" prop="outReturnNo" min-width="170" />
          <el-table-column label="退款单号" prop="outRefundNo" min-width="130" />
          <el-table-column label="回退商户号" prop="returnMchid" min-width="130" />
          <el-table-column label="金额" width="100">
            <template slot-scope="{ row }">¥{{ yuan(row.amountFen) }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="120">
            <template slot-scope="{ row }">
              <cy-status-tag :label="returnStates[row.state] || row.state" :tone="returnTone(row.state)" />
            </template>
          </el-table-column>
          <el-table-column label="失败原因" prop="failReason" min-width="140" />
        </el-table>
        <h4 class="ecom-payout__title">卖家收款退款</h4>
        <el-table v-loading="loading" :data="refunds" empty-text="暂无退款">
          <el-table-column label="退款单号" prop="outRefundNo" min-width="130" />
          <el-table-column label="支付单" prop="outTradeNo" min-width="150" />
          <el-table-column label="二级商户号" prop="subMchid" min-width="130" />
          <el-table-column label="退款 / 实付" min-width="140">
            <template slot-scope="{ row }">¥{{ yuan(row.refundFen) }} / ¥{{ yuan(row.totalFen) }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="140">
            <template slot-scope="{ row }">
              <cy-status-tag :label="refundStates[row.state] || row.state" :tone="refundTone(row.state)" />
            </template>
          </el-table-column>
          <el-table-column label="微信退款单号" prop="refundId" min-width="160" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="失败重试" name="failures">
        <h4 class="ecom-payout__title">转人工的分账</h4>
        <el-table v-loading="loading" :data="manualOrders" empty-text="没有转人工的分账">
          <el-table-column label="分账单号" prop="outOrderNo" min-width="170" />
          <el-table-column label="支付单" prop="outTradeNo" min-width="150" />
          <el-table-column label="主题" prop="topicId" width="80" />
          <el-table-column label="原因" min-width="180">
            <template slot-scope="{ row }">{{ reasonLabels[row.manualReason] || row.manualReason }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template slot-scope="{ row }">
              <el-button v-if="replannable(row)" type="text" size="mini" v-hasPermi="['business:ecommercepayout:edit']" @click="run(() => replanShareOrder(row.id))">重新计划</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4 class="ecom-payout__title">失败的回退</h4>
        <el-table v-loading="loading" :data="failedReturns" empty-text="没有失败的回退">
          <el-table-column label="回退单号" prop="outReturnNo" min-width="170" />
          <el-table-column label="退款单号" prop="outRefundNo" min-width="130" />
          <el-table-column label="金额" width="100">
            <template slot-scope="{ row }">¥{{ yuan(row.amountFen) }}</template>
          </el-table-column>
          <el-table-column label="失败原因" prop="failReason" min-width="160" />
          <el-table-column label="操作" width="110">
            <template slot-scope="{ row }">
              <el-button type="text" size="mini" v-hasPermi="['business:ecommercepayout:edit']" @click="run(() => retryReturn(row.id))">原单号重试</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4 class="ecom-payout__title">退款待补偿</h4>
        <el-table v-loading="loading" :data="compensation" empty-text="没有待补偿的退款">
          <el-table-column label="退款单号" prop="outRefundNo" min-width="130" />
          <el-table-column label="支付单" prop="outTradeNo" min-width="150" />
          <el-table-column label="金额" width="100">
            <template slot-scope="{ row }">¥{{ yuan(row.refundFen) }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="140">
            <template slot-scope="{ row }">
              <cy-status-tag :label="refundStates[row.state] || row.state" tone="danger" />
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import {
  getSwitches, listSubMerchants, registerSubMerchant, refreshSubMerchant, syncOnboarding,
  listShareOrders, listShareLines, replanShareOrder, submitDueShares, syncShares,
  listReturns, retryReturn, listEcomRefunds
} from '@/api/business/ecommercePayout'

const REPLANNABLE = ['RECEIVER_NOT_ONBOARDED', 'SHARE_RATIO_EXCEEDED', 'PLATFORM_RECEIVER_MISSING', 'NO_LEDGER_COMPONENTS', 'LEDGER_CONSERVATION', 'SUB_MERCHANT_SHARE_UNSUPPORTED']

export default {
  name: 'EcommercePayout',
  data() {
    return {
      tab: 'onboarding',
      loading: false,
      switches: {},
      filters: { onboarding: '', share: '', topicId: '' },
      register: { ownerType: 'MERCHANT', ownerId: '' },
      subMerchants: [],
      shareOrders: [],
      lines: {},
      returns: [],
      refunds: [],
      manualOrders: [],
      failedReturns: [],
      compensation: [],
      applymentStates: {
        DRAFT: '未提交', CHECKING: '资料校验中', ACCOUNT_NEED_VERIFY: '待账户验证', AUDITING: '审核中',
        REJECTED: '已驳回', NEED_SIGN: '待签约', FINISH: '已开通', FROZEN: '已冻结', CANCELED: '已作废'
      },
      shareStates: { PLANNED: '待到期', PROCESSING: '处理中', SUCCESS: '已分账', MANUAL: '转人工' },
      returnStates: { PROCESSING: '处理中', SUCCESS: '已回退', FAILED: '失败' },
      refundStates: {
        RETURNING: '回退分账中', RETURN_FAILED: '回退失败', SUBMITTING: '退款提交中', SUBMITTED: '退款处理中',
        SUCCESS: '已退款', CLOSED: '退款关闭', ABNORMAL: '退款异常'
      },
      receiverLabels: { PLATFORM: '平台', SUB_MERCHANT: '二级商户', RETAINED: '卖家留存' },
      reasonLabels: {
        RECEIVER_NOT_ONBOARDED: '接收方未进件', SHARE_RATIO_EXCEEDED: '超过分账比例上限',
        PLATFORM_RECEIVER_MISSING: '平台接收方未配置', NO_LEDGER_COMPONENTS: '账本无份额',
        LEDGER_CONSERVATION: '账本份额与实付不等', REFUNDED_BEFORE_SHARE: '分账前已退款',
        WECHAT_SHARE_CLOSED: '微信分账关闭', ORDER_FACTS_MISSING: '支付事实缺失',
        SUB_MERCHANT_SHARE_UNSUPPORTED: '不支持商户间分账'
      }
    }
  },
  created() {
    getSwitches().then(res => { this.switches = res.data || {} })
    this.load()
  },
  methods: {
    refreshSubMerchant, syncOnboarding, replanShareOrder, submitDueShares, syncShares, retryReturn,
    switchLabel(key, name) { return name + (this.switches[key] ? '：开' : '：关') },
    yuan(fen) { return fen == null ? '—' : (Number(fen) / 100).toFixed(2) },
    replannable(row) { return !row.wxOrderId && REPLANNABLE.includes(row.manualReason) },
    applymentTone(s) {
      return { FINISH: 'success', REJECTED: 'danger', FROZEN: 'danger', CANCELED: 'neutral', NEED_SIGN: 'warning', ACCOUNT_NEED_VERIFY: 'warning' }[s] || 'info'
    },
    shareTone(s) { return { SUCCESS: 'success', MANUAL: 'danger', PROCESSING: 'info', PLANNED: 'neutral' }[s] || 'neutral' },
    returnTone(s) { return { SUCCESS: 'success', FAILED: 'danger' }[s] || 'info' },
    refundTone(s) {
      return { SUCCESS: 'success', RETURN_FAILED: 'danger', CLOSED: 'danger', ABNORMAL: 'danger' }[s] || 'info'
    },
    load() {
      this.loading = true
      let jobs
      if (this.tab === 'onboarding') {
        jobs = [listSubMerchants({ state: this.filters.onboarding }).then(r => { this.subMerchants = r.data || [] })]
      } else if (this.tab === 'shares') {
        const topicId = /^\d+$/.test(String(this.filters.topicId)) ? this.filters.topicId : undefined
        jobs = [listShareOrders({ state: this.filters.share, topicId }).then(r => { this.shareOrders = r.data || []; this.lines = {} })]
      } else if (this.tab === 'refunds') {
        jobs = [
          listReturns({}).then(r => { this.returns = r.data || [] }),
          listEcomRefunds({}).then(r => { this.refunds = r.data || [] })
        ]
      } else {
        jobs = [
          listShareOrders({ state: 'MANUAL' }).then(r => { this.manualOrders = r.data || [] }),
          listReturns({ state: 'FAILED' }).then(r => { this.failedReturns = r.data || [] }),
          listEcomRefunds({}).then(r => {
            this.compensation = (r.data || []).filter(x => ['RETURN_FAILED', 'CLOSED', 'ABNORMAL'].includes(x.state))
          })
        ]
      }
      Promise.all(jobs).finally(() => { this.loading = false })
    },
    expand(row) {
      if (this.lines[row.id]) return
      listShareLines(row.id).then(r => { this.$set(this.lines, row.id, r.data || []) })
    },
    doRegister() {
      if (!/^\d+$/.test(String(this.register.ownerId))) return
      this.run(() => registerSubMerchant({ ownerType: this.register.ownerType, ownerId: this.register.ownerId }))
    },
    run(action) {
      this.loading = true
      Promise.resolve(action()).then(res => {
        this.$modal.msgSuccess('结果：' + (res && res.data != null ? res.data : '完成'))
        this.load()
      }).catch(() => { this.loading = false })
    }
  }
}
</script>

<style scoped>
.ecom-payout__bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.ecom-payout__title { margin: 16px 0 8px; font-size: 14px; font-weight: 600; }
.ecom-payout__reason { margin-top: 4px; font-size: 12px; color: var(--el-color-danger, #f56c6c); }
</style>

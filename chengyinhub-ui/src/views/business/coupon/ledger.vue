<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="供给与权益"
      title="优惠券流水"
      description="查看这张优惠券的领取、使用与过期事实;唯一的写入口是平台侧「撤销核销」(留痕:操作人/原因/时间),其余仅供调证,不支持后台手工改写。"
    >
      <el-button icon="el-icon-back" @click="backToCoupons">返回优惠券管理</el-button>
      <el-button icon="el-icon-tickets" @click="openRedemptionRecords">平台兑换记录</el-button>
    </cy-page-header>

    <cy-resource-state
      v-if="couponError"
      kind="error"
      title="优惠券详情暂时无法读取"
      description="流水仍可继续查询；请在权限恢复后重试读取优惠券摘要。"
      retry-text="重试摘要"
      :retrying="couponLoading"
      @retry="loadCoupon"
    />
    <cy-entity-header
      v-else-if="coupon"
      eyebrow="优惠券详情"
      :title="coupon.name || `优惠券 #${couponId}`"
      :facts="couponFacts"
      class="coupon-ledger__header"
    />

    <section class="cy-panel coupon-ledger__filters">
      <div class="cy-panel__heading">
        <div>
          <h2>领取与核销流水</h2>
          <p>默认只展示当前优惠券，不把不同优惠券的分页结果混在一起。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="84px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="领取用户" prop="memberId">
            <el-input v-model="queryParams.memberId" placeholder="精确用户 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="优惠券码" prop="couponCode">
            <el-input v-model="queryParams.couponCode" placeholder="精确券码" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="使用状态" prop="useStatus">
            <el-select v-model="queryParams.useStatus" placeholder="全部状态" clearable>
              <el-option label="未使用" value="0" />
              <el-option label="已使用" value="1" />
              <el-option label="已过期" value="2" />
            </el-select>
          </el-form-item>
          <template slot="more">
            <el-form-item label="关联订单" prop="orderSn">
              <el-input v-model="queryParams.orderSn" placeholder="精确订单编号" clearable @keyup.enter.native="handleQuery" />
            </el-form-item>
          </template>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="ledgerError"
      kind="error"
      title="流水暂时无法读取"
      description="请刷新重试；系统不会将读取失败伪装成空流水。"
      retry-text="重新读取"
      :retrying="ledgerLoading"
      @retry="getList"
    />
    <cy-data-table
      v-else
      :data="ledgerList"
      :columns="tableColumns"
      :loading="ledgerLoading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      empty-text="这张优惠券暂时没有领取或核销流水"
      @pagination="getList"
      @refresh="getList"
    >
      <template slot="record" slot-scope="scope">
        <button type="button" class="coupon-ledger__record" @click="openRecord(scope.row)">
          <strong>{{ scope.row.couponCode || `领取记录 #${scope.row.id}` }}</strong>
          <span>{{ getTypeLabel(scope.row.getType) }} · {{ formatDate(scope.row.getTime) }}</span>
        </button>
      </template>
      <template slot="member" slot-scope="scope">
        <span>{{ scope.row.memberId ? `用户 #${scope.row.memberId}` : '未记录用户' }}</span>
      </template>
      <template slot="status" slot-scope="scope">
        <el-tag size="mini" :type="useStatusTone(scope.row.useStatus)">{{ useStatusLabel(scope.row.useStatus) }}</el-tag>
      </template>
      <template slot="usedAt" slot-scope="scope">
        <span>{{ formatDate(scope.row.useTime) }}</span>
      </template>
      <template slot="order" slot-scope="scope">
        <span>{{ scope.row.orderSn || (scope.row.orderId ? `订单 #${scope.row.orderId}` : '未关联订单') }}</span>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button size="mini" type="text" icon="el-icon-view" @click="openRecord(scope.row)">查看记录</el-button>
        <el-button
          v-if="isUsed(scope.row)"
          size="mini"
          type="text"
          class="danger-action"
          :disabled="reverseSubmitting"
          @click="handleReverse(scope.row)"
          v-hasPermi="['business:couponhistory:reverseRedeem']"
        >撤销核销</el-button>
      </template>
    </cy-data-table>

    <el-drawer title="领取记录" :visible.sync="recordOpen" size="560px" append-to-body>
      <div v-if="selectedRecord" class="coupon-ledger__drawer">
        <cy-entity-header
          eyebrow="优惠券流水"
          :title="selectedRecord.couponCode || `领取记录 #${selectedRecord.id}`"
          :facts="recordFacts"
        />
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="领取方式">{{ getTypeLabel(selectedRecord.getType) }}</el-descriptions-item>
          <el-descriptions-item label="领取时间">{{ formatDate(selectedRecord.getTime) }}</el-descriptions-item>
          <el-descriptions-item label="使用时间">{{ formatDate(selectedRecord.useTime) }}</el-descriptions-item>
          <el-descriptions-item label="有效期">{{ formatDate(selectedRecord.startTime) }} 至 {{ formatDate(selectedRecord.endTime) }}</el-descriptions-item>
          <el-descriptions-item label="关联订单">{{ selectedRecord.orderSn || (selectedRecord.orderId ? `订单 #${selectedRecord.orderId}` : '未关联订单') }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ selectedRecord.note || '无备注' }}</el-descriptions-item>
        </el-descriptions>

        <section class="coupon-ledger__trail">
          <h3>核销与撤销记录</h3>
          <p>不可变审计,只追加不改写:这里读得到「谁在何时因何核销/撤销了这张券」。</p>
          <div v-loading="trailLoading">
            <el-empty v-if="!trailLoading && !trailList.length" description="该记录暂无核销或撤销审计" :image-size="60" />
            <el-timeline v-else>
              <el-timeline-item
                v-for="event in trailList"
                :key="event.id"
                :timestamp="parseTime(event.createTime, '{y}-{m}-{d} {h}:{i}:{s}')"
                :type="event.action === 'COUPON_REDEEM_REVERSE' ? 'warning' : 'success'"
              >
                <div class="coupon-ledger__trail-title">{{ actionLabel(event.action) }}</div>
                <div class="coupon-ledger__trail-meta">
                  操作者:{{ actorLabel(event) }}
                  <template v-if="event.reason"> · 原因:{{ event.reason }}</template>
                </div>
              </el-timeline-item>
            </el-timeline>
          </div>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { getCoupon } from '@/api/business/coupon'
import { listCouponhistory, reverseCouponRedemption, getCouponAuditTrail } from '@/api/business/couponhistory'
import CyDataTable from '@/components/CyDataTable'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'CouponLedger',
  components: { CyDataTable, CyEntityHeader, CyFilterBar, CyPageHeader, CyResourceState },
  data() {
    return {
      couponId: null,
      coupon: null,
      couponLoading: false,
      couponError: false,
      couponRequestId: 0,
      ledgerLoading: false,
      ledgerError: false,
      ledgerRequestId: 0,
      total: 0,
      ledgerList: [],
      recordOpen: false,
      selectedRecord: null,
      // 审计流水(核销/撤销,读回操作者与原因)
      trailLoading: false,
      trailList: [],
      // 撤销核销提交中
      reverseSubmitting: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        memberId: null,
        couponCode: null,
        useStatus: null,
        orderSn: null
      },
      tableColumns: [
        { key: 'record', label: '领取记录', minWidth: 190 },
        { key: 'member', label: '领取用户', width: 130 },
        { key: 'status', label: '使用状态', width: 110 },
        { key: 'usedAt', label: '使用时间', width: 150 },
        { key: 'order', label: '关联订单', minWidth: 170 },
        { key: 'actions', label: '操作', width: 170, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    couponFacts() {
      return [
        { label: '优惠券编号', value: `#${this.couponId}` },
        { label: '发行数量', value: this.coupon.publishCount === null || this.coupon.publishCount === undefined ? '未限制' : this.coupon.publishCount },
        { label: '已领取', value: this.coupon.receiveCount || 0 },
        { label: '已核销', value: this.coupon.useCount || 0 }
      ]
    },
    activeFilters() {
      return [
        this.queryParams.memberId && { key: 'memberId', label: '领取用户', value: `#${this.queryParams.memberId}` },
        this.queryParams.couponCode && { key: 'couponCode', label: '优惠券码', value: this.queryParams.couponCode },
        this.queryParams.useStatus !== null && { key: 'useStatus', label: '使用状态', value: this.useStatusLabel(this.queryParams.useStatus) },
        this.queryParams.orderSn && { key: 'orderSn', label: '关联订单', value: this.queryParams.orderSn }
      ].filter(Boolean)
    },
    recordFacts() {
      if (!this.selectedRecord) return []
      return [
        { label: '领取用户', value: this.selectedRecord.memberId ? `#${this.selectedRecord.memberId}` : '未记录' },
        { label: '使用状态', value: this.useStatusLabel(this.selectedRecord.useStatus) },
        { label: '优惠券编号', value: `#${this.couponId}` }
      ]
    }
  },
  created() {
    this.loadCouponContext(this.$route.params.couponId)
  },
  beforeRouteUpdate(to, from, next) {
    let contextRequest = Promise.resolve()
    if (to.params.couponId !== this.couponId) {
      contextRequest = this.loadCouponContext(to.params.couponId)
    }
    next()
    return contextRequest
  },
  methods: {
    loadCouponContext(couponId) {
      this.couponId = couponId
      this.coupon = null
      this.couponError = false
      this.ledgerList = []
      this.total = 0
      this.ledgerError = false
      this.selectedRecord = null
      this.recordOpen = false
      this.trailList = []
      this.trailLoading = false
      this.queryParams.pageNum = 1
      return Promise.all([this.loadCoupon(), this.getList()])
    },
    loadCoupon() {
      const requestId = ++this.couponRequestId
      this.couponLoading = true
      this.couponError = false
      return getCoupon(this.couponId).then(response => {
        if (requestId !== this.couponRequestId) return
        this.coupon = response.data || null
      }).catch(() => {
        if (requestId !== this.couponRequestId) return
        this.coupon = null
        this.couponError = true
      }).finally(() => {
        if (requestId === this.couponRequestId) this.couponLoading = false
      })
    },
    queryForLedger() {
      return {
        pageNum: this.queryParams.pageNum,
        pageSize: this.queryParams.pageSize,
        couponId: this.couponId,
        memberId: this.queryParams.memberId,
        couponCode: this.queryParams.couponCode,
        useStatus: this.queryParams.useStatus,
        orderSn: this.queryParams.orderSn
      }
    },
    getList() {
      const requestId = ++this.ledgerRequestId
      const query = this.queryForLedger()
      this.ledgerLoading = true
      this.ledgerError = false
      return listCouponhistory(query).then(response => {
        if (requestId !== this.ledgerRequestId) return
        this.ledgerList = response.rows || []
        this.total = response.total || 0
      }).catch(() => {
        if (requestId !== this.ledgerRequestId) return
        this.ledgerList = []
        this.total = 0
        this.ledgerError = true
      }).finally(() => {
        if (requestId === this.ledgerRequestId) this.ledgerLoading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.queryParams.memberId = null
      this.queryParams.couponCode = null
      this.queryParams.useStatus = null
      this.queryParams.orderSn = null
      this.queryParams.pageNum = 1
      this.getList()
    },
    clearFilter(key) {
      this.queryParams[key] = null
      this.handleQuery()
    },
    openRecord(row) {
      this.selectedRecord = row
      this.recordOpen = true
      this.loadTrail(row.id)
    },
    loadTrail(historyId) {
      this.trailLoading = true
      this.trailList = []
      return getCouponAuditTrail(historyId).then(response => {
        this.trailList = response.data || []
      }).catch(() => {
        this.trailList = []
      }).finally(() => {
        this.trailLoading = false
      })
    },
    isUsed(row) {
      return row && (row.useStatus === 1 || row.useStatus === '1')
    },
    /**
     * 撤销核销(平台侧唯一纠错入口;商家端不给)。
     * 原因由操作人现填,前端不预填默认值 —— 服务端会把它连同操作者落进不可变审计。
     */
    handleReverse(row) {
      if (this.reverseSubmitting) return
      this.$prompt('请输入撤销原因(会留痕:操作人/原因/时间)', '撤销核销', {
        confirmButtonText: '确定撤销',
        cancelButtonText: '取消',
        inputPlaceholder: '如:店员扫错券 / 拿错券',
        inputValidator: value => !!(value && value.trim()),
        inputErrorMessage: '撤销原因不能为空'
      }).then(({ value }) => {
        this.reverseSubmitting = true
        return reverseCouponRedemption(row.id, { reason: value.trim() })
      }).then(() => {
        this.$modal.msgSuccess('已撤销,该券恢复可用;商家待结算分成已撤回')
        this.loadCoupon()
        this.getList()
        if (this.selectedRecord && this.selectedRecord.id === row.id) {
          this.loadTrail(row.id)
        }
      }).catch(() => {}).finally(() => {
        this.reverseSubmitting = false
      })
    },
    actionLabel(action) {
      return { COUPON_REDEEM: '核销', COUPON_REDEEM_REVERSE: '撤销核销' }[action] || action || '未知动作'
    },
    actorLabel(event) {
      if (!event) return '未记录'
      if (event.actorType === 'SYS_USER') return `平台账号 #${event.actorMemberId}`
      return `用户 #${event.actorMemberId}`
    },
    openRedemptionRecords() {
      this.$router.push({ name: 'platformRedemptionRecords', query: { source: 'coupon' } })
    },
    backToCoupons() {
      this.$router.push('/business/coupon')
    },
    getTypeLabel(value) {
      return { 0: '后台赠送', 1: '用户领取', '0': '后台赠送', '1': '用户领取' }[value] || '来源未记录'
    },
    useStatusLabel(value) {
      return { 0: '未使用', 1: '已使用', 2: '已过期', '0': '未使用', '1': '已使用', '2': '已过期' }[value] || '状态未记录'
    },
    useStatusTone(value) {
      return { 0: 'info', 1: 'success', 2: 'warning', '0': 'info', '1': 'success', '2': 'warning' }[value] || 'info'
    },
    formatDate(value) {
      return value ? this.parseTime(value, '{y}-{m}-{d}') : '—'
    }
  }
}
</script>

<style lang="scss" scoped>
.coupon-ledger__header, .coupon-ledger__filters { margin-bottom: 20px; }
.coupon-ledger__trail { margin-top: 20px; }
.coupon-ledger__trail h3 { margin: 0 0 4px; font-size: 14px; color: var(--cy-color-text-primary); }
.coupon-ledger__trail > p { margin: 0 0 14px; color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.6; }
.coupon-ledger__trail-title { color: var(--cy-color-text-primary); font-weight: 600; }
.coupon-ledger__trail-meta { margin-top: 4px; color: var(--cy-color-text-secondary); font-size: 12px; }
.coupon-ledger__record { display: block; max-width: 100%; padding: 0; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.coupon-ledger__record strong, .coupon-ledger__record span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.coupon-ledger__record strong { color: var(--cy-color-text-primary); font-weight: 600; }
.coupon-ledger__record span { margin-top: 4px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.coupon-ledger__record:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 3px; }
.coupon-ledger__drawer { min-height: 280px; padding: 0 24px var(--cy-space-7); }
</style>

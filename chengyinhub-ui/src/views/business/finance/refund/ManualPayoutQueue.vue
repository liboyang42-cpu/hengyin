<template>
  <section class="manual-payout-queue">
    <cy-page-header
      eyebrow="资金与售后 / 退款处理台"
      title="人工打款子队列"
      description="仅承接原路退款失败后的人工处理与二次复核；审核通过不等于款项已到账。"
    >
      <el-button icon="el-icon-back" @click="$emit('back')">返回退款处理台</el-button>
    </cy-page-header>

    <section class="manual-payout-queue__notice" role="note">
      <i class="el-icon-warning-outline" />
      <div>
        <strong>原路退款失败不自动转平台余额。</strong>
        <p>处理人与复核人必须是不同的两个人；处理前会由服务端回读微信真实状态，避免原路退款与人工退款双退。</p>
      </div>
    </section>

    <cy-data-table
      :data="list"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前没有需要人工处理或二次复核的退款"
      @pagination="getList"
      @refresh="getList"
    >
      <template slot="refund" slot-scope="scope">
        <div class="manual-payout-queue__refund">
          <strong>{{ scope.row.refundNo || `退款 #${scope.row.id}` }}</strong>
          <small>{{ scope.row.sourceType || '未知来源' }} #{{ scope.row.sourceId || '—' }}</small>
        </div>
      </template>
      <template slot="amount" slot-scope="scope">
        <span class="manual-payout-queue__amount">¥{{ money(scope.row.refundAmount) }}</span>
      </template>
      <template slot="payout" slot-scope="scope">
        <cy-status-tag :label="payoutLabel(scope.row.payoutStatus)" :tone="payoutTone(scope.row.payoutStatus)" />
      </template>
      <template slot="payoutMethod" slot-scope="scope">
        <span>{{ methodLabel(scope.row.payoutMethod) }}</span>
      </template>
      <template slot="handlers" slot-scope="scope">
        <div class="manual-payout-queue__handlers">
          <span>处理 #{{ scope.row.payoutHandlerId || '—' }}</span>
          <small>复核 #{{ scope.row.payoutReviewer2Id || '—' }}</small>
        </div>
      </template>
      <template slot="receipt" slot-scope="scope">
        <!-- 闸在后端(handleManualPayout 落库前校验);这里只防「本次修复之前落库的存量脏值」被点开 -->
        <el-link v-if="safeReceiptHref(scope.row.payoutReceiptUrl)" type="primary" :href="safeReceiptHref(scope.row.payoutReceiptUrl)" target="_blank" rel="noopener noreferrer">查看回执</el-link>
        <el-tooltip v-else-if="scope.row.payoutReceiptUrl" content="回执链接不符合安全格式（非站内路径或非 HTTPS），已禁用跳转，请人工核对原始值" placement="top">
          <span class="manual-payout-queue__receipt-blocked">链接不可信</span>
        </el-tooltip>
        <span v-else>—</span>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button size="mini" type="text" icon="el-icon-refresh" @click="handleRefresh(scope.row)" v-hasPermi="['business:refund:review']">查微信状态</el-button>
        <el-button v-if="scope.row.payoutStatus === 2" size="mini" type="text" icon="el-icon-edit" @click="openHandle(scope.row)" v-hasPermi="['business:refund:review']">人工处理</el-button>
        <el-button v-if="scope.row.payoutStatus === 2 && leaseExpired(scope.row)" size="mini" type="text" icon="el-icon-refresh-left" @click="handleTakeover(scope.row)" v-hasPermi="['business:refund:review']">接管过期处理权</el-button>
        <el-button v-if="scope.row.payoutStatus === 3" size="mini" type="text" icon="el-icon-check" @click="handleVerify(scope.row)" v-hasPermi="['business:refund:review']">复核确认</el-button>
      </template>
    </cy-data-table>

    <el-dialog title="人工退款处理" :visible.sync="open" width="520px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="退款方式" prop="method">
          <el-select v-model="form.method" placeholder="请选择" style="width: 100%">
            <el-option label="原路重试(同 out_refund_no)" value="original_retry" />
            <el-option label="线下转账" value="offline_transfer" />
            <el-option label="微信异常退款到零钱" value="wx_abnormal_change" />
            <el-option label="微信异常退款到银行卡" value="wx_abnormal_bankcard" />
          </el-select>
        </el-form-item>
        <el-form-item label="回执凭证 URL" prop="receiptUrl">
          <el-input v-model="form.receiptUrl" placeholder="站内路径（/profile/upload/...）或已报备域名的 https 链接" />
          <small class="manual-payout-queue__hint">服务端按白名单校验：只接受站内单斜杠路径或已配置域名的 HTTPS 链接，其余一律拒绝登记。</small>
        </el-form-item>
        <el-form-item label="处理备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" placeholder="处理说明，便于复核人核对" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="handleSubmitting" @click="submitHandle">提交待复核</el-button>
        <el-button :disabled="handleSubmitting" @click="cancelHandle">取消并释放处理权</el-button>
      </div>
    </el-dialog>
  </section>
</template>

<script>
import { listManualPayout, claimManualPayout, releaseManualPayout, takeoverManualPayout, handleManualPayout, verifyManualPayout, refreshPayout } from '@/api/business/refund'
import { toTimestamp } from '@/utils/ruoyi'
import CyDataTable from '@/components/CyDataTable'
import CyPageHeader from '@/components/CyPageHeader'
import CyStatusTag from '@/components/CyStatusTag'

const METHOD_LABELS = {
  original_retry: '原路重试',
  offline_transfer: '线下转账',
  wx_abnormal_change: '异常退款·零钱',
  wx_abnormal_bankcard: '异常退款·银行卡'
}
const PAYOUT_TEXT = { 2: '待人工处理', 3: '待二次复核' }
const PAYOUT_TONE = { 2: 'danger', 3: 'warning' }

// 枚举只接受合同规定的标量键: 字符串枚举只认非空字符串, 数字枚举只认整数或纯整数字符串。
// 数组/布尔/对象/小数/空白一律失败关闭; 只认自有键, 避免原型键渲染 native function。
function strictInt(value) {
  if (typeof value === 'number') return Number.isInteger(value) ? value : null
  if (typeof value === 'string' && /^-?\d+$/.test(value)) return Number(value)
  return null
}
function enumKey(value, numeric) {
  if (numeric) {
    const parsed = strictInt(value)
    return parsed === null ? null : String(parsed)
  }
  return typeof value === 'string' && value !== '' ? value : null
}
function enumLookup(map, value, fallback, numeric) {
  const key = enumKey(value, numeric)
  if (key === null) return fallback
  return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : fallback
}

// 必须与后端 chengyin.refund.payout-receipt.allowed-hosts 的默认值同步(RefundServiceImpl)。
// 这里只管"要不要给一个可点的 href"这层前端展示防御 —— 真正的写入合同在服务端;
// 若运维通过 CHENGYIN_REFUND_RECEIPT_ALLOWED_HOSTS 额外扩展了白名单,那些域名在这里会
// 保守地显示"链接不可信"(安全方向的误伤,不是漏洞),而不是反过来放行未知域名。
const SAFE_RECEIPT_HOSTS = ['www.chengyinhub.com']

function firstQueryValue(value) {
  return Array.isArray(value) ? value[0] : value
}

function positiveInteger(value, fallback) {
  const parsed = Number(firstQueryValue(value))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

function createQueryParams(query = {}) {
  return {
    pageNum: positiveInteger(query.pageNum, 1),
    pageSize: positiveInteger(query.pageSize, 10)
  }
}

export default {
  name: 'ManualPayoutQueue',
  components: { CyDataTable, CyPageHeader, CyStatusTag },
  props: {
    routeQuery: {
      type: Object,
      default: () => ({})
    }
  },
  data() {
    return {
      loading: false,
      listRequestId: 0,
      list: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10 },
      open: false,
      handleSubmitting: false,
      form: { refundId: null, method: null, receiptUrl: '', remark: '', leaseToken: null },
      rules: {
        method: [{ required: true, message: '请选择退款方式', trigger: 'change' }]
      },
      tableColumns: [
        { key: 'refund', label: '退款单', minWidth: 210 },
        { key: 'memberId', prop: 'memberId', label: '用户 ID', minWidth: 100 },
        { key: 'amount', label: '退款金额', minWidth: 120 },
        { key: 'payout', label: '打款状态', minWidth: 130 },
        { key: 'payoutMethod', label: '退款方式', minWidth: 145 },
        { key: 'handlers', label: '处理 / 复核', minWidth: 150 },
        { key: 'receipt', label: '回执', minWidth: 100 },
        { key: 'payoutRemark', prop: 'payoutRemark', label: '备注 / 失败原因', minWidth: 200, showOverflowTooltip: true },
        { key: 'actions', label: '处理', width: 245, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  watch: {
    routeQuery: {
      immediate: true,
      handler(query) {
        this.queryParams = createQueryParams(query)
        this.getList()
      }
    }
  },
  methods: {
    methodLabel(method) {
      const key = enumKey(method, false)
      if (key === null) return '—'
      return Object.prototype.hasOwnProperty.call(METHOD_LABELS, key) ? METHOD_LABELS[key] : key
    },
    money(value) { return Number(value || 0).toFixed(2) },
    payoutLabel(value) { return enumLookup(PAYOUT_TEXT, value, '状态已变化', true) },
    payoutTone(value) { return enumLookup(PAYOUT_TONE, value, 'neutral', true) },
    leaseExpired(row) {
      // 只在"有人持有且已过期"时才露出接管入口;无人持有直接走普通领取。
      if (!row || !row.payoutLeaseOwner || !row.payoutLeaseExpiresAt) return false
      const ts = toTimestamp(row.payoutLeaseExpiresAt)
      return Number.isFinite(ts) && ts < Date.now()
    },
    // 仅前端防御:后端已在落库前挡住新写入,存量脏值(本次修复前登记的任意 scheme/host)仍可能在库里,
    // 点开就是钓鱼/协议注入。这里不重算业务、不伪造成功,只决定"能不能给它一个可点的 href"。
    safeReceiptHref(url) {
      if (typeof url !== 'string') return ''
      const value = url.trim()
      // eslint-disable-next-line no-control-regex
      if (!value || /[\u0000-\u001f\u007f\\]/.test(value)) return ''
      if (value.startsWith('/') && !value.startsWith('//') && !value.includes('..')) return value
      if (!/^https:\/\//i.test(value)) return ''
      const authority = value.slice('https://'.length).split(/[/?#]/)[0]
      if (!authority || authority.includes('@')) return ''
      // host 精确匹配白名单,不认通配符/后缀 —— 挡 www.chengyinhub.com.evil.com 这类仿冒
      const host = authority.toLowerCase()
      return SAFE_RECEIPT_HOSTS.indexOf(host) !== -1 ? value : ''
    },
    getList() {
      const requestId = ++this.listRequestId
      this.loading = true
      return listManualPayout(this.queryParams).then(response => {
        if (requestId !== this.listRequestId) return
        this.list = response.rows || []
        this.total = response.total || 0
      }).finally(() => {
        if (requestId === this.listRequestId) this.loading = false
      })
    },
    handleRefresh(row) {
      refreshPayout(row.refundNo).then(response => {
        this.$modal.msgSuccess('微信当前退款状态：' + (response.msg || response.data || '未知'))
        this.getList()
      })
    },
    openHandle(row) {
      // 先领取人工处理权拿到 leaseToken —— 后端 handle 用它做 CAS,不带 token 必被拒。
      // 领取还会先问微信真实终态(SUCCESS/PROCESSING 会拒发 token),挡住"钱已退还想再退"。
      claimManualPayout({ refundId: row.id }).then(response => {
        this.form = { refundId: row.id, method: null, receiptUrl: '', remark: '', leaseToken: response.data }
        this.handleSubmitting = false
        this.open = true
      })
    },
    cancelHandle() {
      // 关弹窗必须把租约还回去:光关窗口 token 还挂在这个人身上,过期后谁都领不到这单。
      const { refundId, leaseToken } = this.form
      this.open = false
      if (!refundId || !leaseToken) return
      return releaseManualPayout({ refundId, leaseToken }).then(() => {
        this.form = { refundId: null, method: null, receiptUrl: '', remark: '', leaseToken: null }
        this.getList()
      })
    },
    handleTakeover(row) {
      // 跨人接管只对已过期租约生效,服务端会把原处理人写进备注;普通 claim 仍然抢不走他人租约。
      return this.$modal.confirm('原处理人 #' + (row.payoutLeaseOwner || '—')
        + ' 的处理权已过期。接管会作废他的 token,并在备注留痕。确认接管？').then(() => {
        return takeoverManualPayout({ refundId: row.id })
      }).then(response => {
        this.form = { refundId: row.id, method: null, receiptUrl: '', remark: '', leaseToken: response.data }
        this.open = true
      }).catch(() => {})
    },
    submitHandle() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        this.handleSubmitting = true
        handleManualPayout(this.form).then(() => {
          this.$modal.msgSuccess('已登记，待复核')
          this.open = false
          this.getList()
        }).finally(() => { this.handleSubmitting = false })
      })
    },
    handleVerify(row) {
      this.$modal.confirm('确认该人工退款凭证与处理记录一致？你须与处理人为不同的人；本操作不代表用户已确认到账。').then(() => {
        return verifyManualPayout({ refundId: row.id })
      }).then(() => {
        this.$modal.msgSuccess('凭证复核完成；不代表用户已确认到账')
        this.getList()
      }).catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
.manual-payout-queue__notice { display: flex; gap: 12px; margin-bottom: 20px; padding: 16px var(--cy-space-3-5); border: 1px solid #f1d1a6; border-radius: var(--cy-radius-card); background: var(--cy-color-status-warning-soft); color: var(--cy-color-status-warning); }
.manual-payout-queue__notice > i { margin-top: 2px; font-size: 18px; }
.manual-payout-queue__notice strong, .manual-payout-queue__notice p { display: block; }
.manual-payout-queue__notice p { margin: var(--cy-space-0-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.65; }
.manual-payout-queue__refund strong, .manual-payout-queue__refund small, .manual-payout-queue__handlers span, .manual-payout-queue__handlers small { display: block; }
.manual-payout-queue__refund small, .manual-payout-queue__handlers small { margin-top: 4px; color: var(--cy-color-text-tertiary); }
.manual-payout-queue__amount { color: var(--cy-color-text-primary); font-weight: 700; }
.manual-payout-queue__receipt-blocked { color: var(--cy-color-status-danger); font-size: 13px; }
.manual-payout-queue__hint { display: block; margin-top: var(--cy-space-0-5); color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.6; }
</style>

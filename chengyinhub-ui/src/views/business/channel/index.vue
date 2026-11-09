<template>
  <div class="app-container">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="渠道主体">
        <el-select v-model="accountId" placeholder="选择主体" style="width: 220px" @change="load">
          <el-option v-for="a in accounts" :key="a.id" :label="`${a.displayName || a.channelKey} · ${accountStatusLabel(a.status)}`" :value="a.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="tab !== 'accounts'" label="期间">
        <el-date-picker v-model="range" type="daterange" value-format="yyyy-MM-dd" range-separator="至" style="width: 240px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="load">查询</el-button>
      </el-form-item>
    </el-form>
    <p v-if="pageError" class="qa-error">{{ pageError }}</p>

    <el-tabs v-model="tab" @tab-click="load">
      <el-tab-pane label="渠道主体" name="accounts">
        <div class="qa-toolbar">
          <el-button v-hasPermi="['business:marketing:channel:manage']" size="small" icon="el-icon-plus" @click="openAccount">登记主体</el-button>
        </div>
        <el-table v-loading="loading" :data="accounts" empty-text="暂无渠道主体">
          <el-table-column label="主体" min-width="180">
            <template slot-scope="s">
              <div class="qa-stack"><span>{{ s.row.displayName }}</span><small>{{ s.row.channelKey }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="合同" prop="contractRef" min-width="140" />
          <el-table-column label="状态" width="80">
            <template slot-scope="s">{{ accountStatusLabel(s.row.status) }}</template>
          </el-table-column>
          <el-table-column label="生效期" min-width="200">
            <template slot-scope="s">{{ dateText(s.row.effectiveFrom) }} – {{ dateText(s.row.effectiveTo) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:marketing:channel:manage']" type="text" :class="{ 'qa-danger': s.row.status === 1 }" @click="toggleAccount(s.row)">{{ s.row.status === 1 ? '停用' : '启用' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="渠道订单" name="verifications">
        <div class="qa-toolbar">
          <el-button v-hasPermi="['business:marketing:channel:verify']" size="small" icon="el-icon-plus" :disabled="!accountId" @click="openVerify">验证订单</el-button>
        </div>
        <el-table v-loading="loading" :data="verifications" :empty-text="accountId ? '该期间没有渠道订单' : '先选择渠道主体'">
          <el-table-column label="渠道单号" prop="channelOrderNo" min-width="160" />
          <el-table-column label="外部引用" prop="externalReference" min-width="140" />
          <el-table-column label="验证金额" width="120">
            <template slot-scope="s">{{ moneyText(s.row.amount, s.row.currency) }}</template>
          </el-table-column>
          <el-table-column label="商品 / 数量" width="120">
            <template slot-scope="s">{{ s.row.productId ? '#' + s.row.productId : '—' }} × {{ countText(s.row.quantity) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template slot-scope="s">
              <div class="qa-stack"><span>{{ verificationStatusLabel(s.row.status) }}</span><small v-if="s.row.reasonCode">{{ s.row.reasonCode }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="验证到期" width="150">
            <template slot-scope="s">{{ timeText(s.row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template slot-scope="s">
              <el-button v-if="s.row.status === 1" v-hasPermi="['business:marketing:channel:issue']" type="text" @click="openIssue(s.row)">发码</el-button>
              <el-button v-if="s.row.status === 1 || s.row.status === 0" v-hasPermi="['business:marketing:channel:revoke']" type="text" class="qa-danger" @click="openRevoke('verification', s.row)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="兑换码" name="vouchers">
        <el-table v-loading="loading" :data="vouchers" :empty-text="accountId ? '该期间没有兑换码' : '先选择渠道主体'">
          <el-table-column label="码标识" prop="publicId" min-width="160" />
          <el-table-column label="渠道单号" prop="channelOrderNo" min-width="150" />
          <el-table-column label="商品 / 席位" width="130">
            <template slot-scope="s">{{ s.row.productId ? '#' + s.row.productId : '—' }} · {{ countText(s.row.seatCount) }} 席</template>
          </el-table-column>
          <el-table-column label="对象" width="120">
            <template slot-scope="s">{{ subjectModeLabel(s.row.subjectMode) }}<span v-if="s.row.boundMemberId"> #{{ s.row.boundMemberId }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="140">
            <template slot-scope="s">{{ voucherStatusLabel(s.row) }}</template>
          </el-table-column>
          <el-table-column label="有效期至" width="150">
            <template slot-scope="s">{{ timeText(s.row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template slot-scope="s">
              <el-button v-if="(s.row.status === 0 || s.row.status === 1) && s.row.revokePending !== true" v-hasPermi="['business:marketing:channel:revoke']" type="text" class="qa-danger" @click="openRevoke('voucher', s.row)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="对账" name="reconciliation">
        <p v-if="!accountId" class="qa-muted">先选择渠道主体</p>
        <template v-else-if="recon">
          <dl class="qa-facts">
            <div><dt>已验证订单</dt><dd>{{ countText(recon.verifiedOrderCount) }}</dd></div>
            <div><dt>渠道验证金额</dt><dd>{{ moneyText(recon.verifiedAmount) }}</dd></div>
            <div><dt>待验证</dt><dd>{{ countText(recon.pendingVerificationCount) }}</dd></div>
            <div><dt>已发码</dt><dd>{{ countText(recon.voucherCount) }}</dd></div>
            <div><dt>已兑换</dt><dd>{{ countText(recon.redeemedCount) }}</dd></div>
            <div><dt>未兑换</dt><dd>{{ countText(recon.unredeemedCount) }}</dd></div>
            <div><dt>可兑换</dt><dd>{{ countText(recon.activeVoucherCount) }}</dd></div>
            <div><dt>已撤销</dt><dd>{{ countText(recon.revokedVoucherCount) }}</dd></div>
          </dl>
          <el-table :data="recon.issues || []" empty-text="没有对账差异">
            <el-table-column label="差异" prop="issueType" min-width="180" />
            <el-table-column label="渠道单号" prop="channelOrderNo" min-width="150" />
            <el-table-column label="码标识" prop="voucherPublicId" min-width="150" />
            <el-table-column label="金额" width="110">
              <template slot-scope="s">{{ moneyText(s.row.amount) }}</template>
            </el-table-column>
            <el-table-column label="发生时间" width="150">
              <template slot-scope="s">{{ timeText(s.row.occurredAt) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>

    <el-dialog title="登记渠道主体" :visible.sync="accountOpen" width="520px" append-to-body>
      <el-form :model="accountForm" label-width="90px" size="small">
        <el-form-item label="渠道键"><el-input v-model="accountForm.channelKey" maxlength="64" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="accountForm.displayName" maxlength="100" /></el-form-item>
        <el-form-item label="合同编号"><el-input v-model="accountForm.contractRef" maxlength="100" /></el-form-item>
        <el-form-item label="结算方式">
          <el-radio-group v-model="accountForm.settlementMode">
            <el-radio :label="0">不结算</el-radio>
            <el-radio :label="1">预付</el-radio>
            <el-radio :label="2">账期开票</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="生效期">
          <el-date-picker v-model="accountForm.range" type="datetimerange" value-format="timestamp" range-separator="至" />
        </el-form-item>
        <p v-if="formError" class="qa-error">{{ formError }}</p>
      </el-form>
      <div slot="footer">
        <el-button type="primary" :loading="saving" @click="submitAccount">保存</el-button>
        <el-button @click="accountOpen = false">取消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="验证渠道订单" :visible.sync="verifyOpen" width="520px" append-to-body>
      <el-form :model="verifyForm" label-width="90px" size="small">
        <el-form-item label="渠道单号">
          <el-input v-model="verifyForm.channelOrderNo" maxlength="100" />
          <p v-if="fieldErrors.channelOrderNo" class="qa-error">{{ fieldErrors.channelOrderNo }}</p>
        </el-form-item>
        <el-form-item label="外部引用"><el-input v-model="verifyForm.externalReference" maxlength="128" /></el-form-item>
        <el-form-item label="渠道商"><el-input v-model="verifyForm.providerKey" maxlength="64" /></el-form-item>
        <p v-if="formError" class="qa-error">{{ formError }}</p>
      </el-form>
      <div slot="footer">
        <el-button type="primary" :loading="saving" @click="submitVerify">验证</el-button>
        <el-button @click="verifyOpen = false">取消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="发行兑换码" :visible.sync="issueOpen" width="520px" append-to-body>
      <el-form v-if="!issued" :model="issueForm" label-width="90px" size="small">
        <el-form-item label="渠道单号">{{ issueRow && issueRow.channelOrderNo }}</el-form-item>
        <el-form-item label="对象">
          <el-radio-group v-model="issueForm.subjectMode">
            <el-radio :label="2">不记名</el-radio>
            <el-radio :label="1">绑定会员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="issueForm.subjectMode === 1" label="会员 ID">
          <el-input v-model="issueForm.boundMemberId" />
          <p v-if="fieldErrors.boundMemberId" class="qa-error">{{ fieldErrors.boundMemberId }}</p>
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker v-model="issueForm.expiresAt" type="datetime" value-format="timestamp" />
        </el-form-item>
        <p v-if="formError" class="qa-error">{{ formError }}</p>
      </el-form>
      <div v-else class="qa-issued">
        <template v-if="issuedCode">
          <span>兑换码（只显示这一次）</span>
          <strong>{{ issuedCode }}</strong>
        </template>
        <span v-else>已恢复发行记录 {{ issued.publicId }}</span>
      </div>
      <div slot="footer">
        <el-button v-if="!issued" type="primary" :loading="saving" @click="submitIssue">发码</el-button>
        <el-button @click="closeIssue">{{ issued ? '完成' : '取消' }}</el-button>
      </div>
    </el-dialog>

    <el-dialog :title="revokeTarget === 'voucher' ? '撤销兑换码' : '撤销渠道订单'" :visible.sync="revokeOpen" width="480px" append-to-body>
      <el-form label-width="60px" size="small">
        <el-form-item label="理由">
          <el-input v-model="revokeForm.reason" type="textarea" :rows="3" maxlength="200" />
          <p v-if="fieldErrors.reason" class="qa-error">{{ fieldErrors.reason }}</p>
        </el-form-item>
        <p v-if="formError" class="qa-error">{{ formError }}</p>
      </el-form>
      <div slot="footer">
        <el-button type="danger" :loading="saving" @click="submitRevoke">撤销</el-button>
        <el-button @click="revokeOpen = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listAccounts, registerAccount, updateAccountStatus, verifyOrder, listVerifications, revokeVerification,
  issueVoucher, listVouchers, revokeVoucher, getReconciliation
} from '@/api/business/channelVoucher'
import {
  businessError, countText, moneyText, accountStatusLabel, verificationStatusLabel, voucherStatusLabel,
  subjectModeLabel, issuedCode
} from '@/utils/questo-admin'

const REASON_TEXT = {
  PROVIDER_NOT_CONFIGURED: '渠道未配置，暂不可验证',
  VERIFICATION_EXPIRED: '验证已过期，请重新验证',
  CHANNEL_ORDER_AMBIGUOUS: '渠道单号在多个主体下重复，请核对',
  VOUCHER_SNAPSHOT_UNAVAILABLE: '旧码缺少发行快照，请联系发行方核对'
}

function newRequestId() {
  return 'adm-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}
function blank(value) { return value === null || value === undefined || String(value).trim() === '' }

export default {
  name: 'BusinessChannelVoucher',
  data() {
    return {
      tab: 'accounts',
      loading: false,
      saving: false,
      pageError: '',
      accountId: null,
      range: null,
      accounts: [],
      verifications: [],
      vouchers: [],
      recon: null,
      formError: '',
      fieldErrors: {},
      accountOpen: false,
      accountForm: {},
      verifyOpen: false,
      verifyForm: {},
      issueOpen: false,
      issueRow: null,
      issueForm: {},
      issued: null,
      revokeOpen: false,
      revokeTarget: null,
      revokeRow: null,
      revokeForm: {}
    }
  },
  computed: {
    issuedCode() { return issuedCode(this.issued) }
  },
  created() {
    this.loadAccounts()
  },
  methods: {
    countText, moneyText, accountStatusLabel, verificationStatusLabel, voucherStatusLabel, subjectModeLabel,
    timeText(value) { return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '—' },
    dateText(value) { return value ? this.parseTime(value, '{y}-{m}-{d}') : '—' },
    message(err) {
      const e = businessError(err)
      if (Number(e.code) === 403) return '该功能尚未对当前角色开放'
      return (e.reasonCode && REASON_TEXT[e.reasonCode]) || e.message
    },
    period() {
      return { accountId: this.accountId, from: this.range ? this.range[0] : null, to: this.range ? this.range[1] : null }
    },
    load() {
      this.pageError = ''
      if (this.tab === 'accounts') return this.loadAccounts()
      if (!this.accountId) return
      this.loading = true
      let call
      if (this.tab === 'verifications') call = listVerifications(this.period()).then(res => { this.verifications = res.data || [] })
      if (this.tab === 'vouchers') call = listVouchers(this.period()).then(res => { this.vouchers = res.data || [] })
      if (this.tab === 'reconciliation') call = getReconciliation(this.period()).then(res => { this.recon = res.data || null })
      return call.catch(err => {
        this.verifications = []
        this.vouchers = []
        this.recon = null
        this.pageError = this.message(err)
      }).finally(() => { this.loading = false })
    },
    loadAccounts() {
      this.loading = true
      return listAccounts({ limit: 50 }).then(res => {
        this.accounts = res.data || []
      }).catch(err => {
        this.accounts = []
        this.pageError = this.message(err)
      }).finally(() => { this.loading = false })
    },
    resetErrors() { this.formError = ''; this.fieldErrors = {} },
    openAccount() {
      this.resetErrors()
      this.accountForm = { channelKey: '', displayName: '', contractRef: '', settlementMode: 0, range: null, requestId: newRequestId() }
      this.accountOpen = true
    },
    submitAccount() {
      const f = this.accountForm
      this.resetErrors()
      this.saving = true
      registerAccount({
        channelKey: f.channelKey,
        displayName: f.displayName,
        contractRef: f.contractRef,
        settlementMode: f.settlementMode,
        effectiveFrom: f.range ? f.range[0] : null,
        effectiveTo: f.range ? f.range[1] : null,
        requestId: f.requestId
      }).then(() => {
        this.accountOpen = false
        this.loadAccounts()
      }).catch(err => { this.formError = this.message(err) }).finally(() => { this.saving = false })
    },
    toggleAccount(row) {
      updateAccountStatus(row.id, row.status === 1 ? 0 : 1).then(() => this.loadAccounts()).catch(err => {
        this.pageError = this.message(err)
      })
    },
    openVerify() {
      this.resetErrors()
      this.verifyForm = { channelOrderNo: '', externalReference: '', providerKey: '', requestId: newRequestId() }
      this.verifyOpen = true
    },
    submitVerify() {
      const f = this.verifyForm
      this.resetErrors()
      if (blank(f.channelOrderNo)) {
        this.fieldErrors = { channelOrderNo: '请填写渠道单号' }
        return
      }
      this.saving = true
      // 金额与商品由渠道验证事实给出，后台不填。
      verifyOrder({
        accountId: this.accountId,
        providerKey: f.providerKey || null,
        channelOrderNo: f.channelOrderNo,
        externalReference: f.externalReference || null,
        requestId: f.requestId
      }).then(() => {
        this.verifyOpen = false
        this.load()
      }).catch(err => { this.formError = this.message(err) }).finally(() => { this.saving = false })
    },
    openIssue(row) {
      this.resetErrors()
      this.issueRow = row
      this.issued = null
      // 同一次发码保留 requestId，失败后重试只重放原发行。
      this.issueForm = { subjectMode: 2, boundMemberId: '', expiresAt: null, requestId: newRequestId() }
      this.issueOpen = true
    },
    submitIssue() {
      const f = this.issueForm
      this.resetErrors()
      if (f.subjectMode === 1 && !/^\d+$/.test(String(f.boundMemberId).trim())) {
        this.fieldErrors = { boundMemberId: '请填写会员 ID' }
        return
      }
      this.saving = true
      issueVoucher({
        verificationId: this.issueRow.id,
        subjectMode: f.subjectMode,
        boundMemberId: f.subjectMode === 1 ? Number(f.boundMemberId) : null,
        expiresAt: f.expiresAt || null,
        requestId: f.requestId
      }).then(res => {
        this.issued = res.data || {}
      }).catch(err => { this.formError = this.message(err) }).finally(() => { this.saving = false })
    },
    closeIssue() {
      // 关闭即丢弃明文码，不在页面或存储中保留。
      const reload = !!this.issued
      this.issued = null
      this.issueOpen = false
      if (reload) this.load()
    },
    openRevoke(target, row) {
      this.resetErrors()
      this.revokeTarget = target
      this.revokeRow = row
      this.revokeForm = { reason: '', requestId: newRequestId() }
      this.revokeOpen = true
    },
    submitRevoke() {
      this.resetErrors()
      if (blank(this.revokeForm.reason)) {
        this.fieldErrors = { reason: '请填写撤销理由' }
        return
      }
      const body = { reason: this.revokeForm.reason, requestId: this.revokeForm.requestId }
      const call = this.revokeTarget === 'voucher'
        ? revokeVoucher(this.revokeRow.id, body)
        : revokeVerification(this.revokeRow.id, body)
      this.saving = true
      call.then(() => {
        this.revokeOpen = false
        this.load()
      }).catch(err => { this.formError = this.message(err) }).finally(() => { this.saving = false })
    }
  }
}
</script>

<style scoped>
.qa-stack { display: flex; flex-direction: column; line-height: 1.4; }
.qa-stack small { color: #909399; }
.qa-error { color: #F56C6C; font-size: 12px; margin: 4px 0 8px; }
.qa-muted { color: #909399; }
.qa-danger { color: #F56C6C; }
.qa-toolbar { margin-bottom: 8px; }
.qa-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px 24px; margin: 0 0 16px; }
.qa-facts dt { color: #909399; font-size: 12px; }
.qa-facts dd { margin: 4px 0 0; font-size: 18px; }
.qa-issued { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.qa-issued strong { font-family: monospace; font-size: 20px; letter-spacing: 2px; user-select: all; }
</style>

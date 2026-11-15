<template>
  <div class="app-container">
    <cy-page-header eyebrow="审核与风控" title="奖励承诺审核" description="商家承诺的券类奖励（无现金价值）。通过后奖励按名额发放，驳回后商家可修改重提。">
      <el-button slot="primary" size="small" icon="el-icon-refresh" :loading="loading" @click="refresh">刷新</el-button>
    </cy-page-header>

    <el-alert v-if="!platformReviewOpen" type="warning" :closable="false" show-icon
      title="平台审核尚未开启：在下方「奖励政策」把「平台审核」设为开启后，才能通过或驳回。" />
    <el-alert v-if="!cashPayoutOpen" type="info" :closable="false" show-icon
      title="现金奖励处于关闭状态：本页只处理券类奖励，不涉及任何付款。" />

    <el-tabs v-model="tab">
      <el-tab-pane label="审核队列" name="queue">
        <el-form size="small" :inline="true">
          <el-form-item label="状态">
            <el-select v-model="queueState" style="width: 140px" @change="getQueue">
              <el-option v-for="s in states" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="商家编号">
            <el-input v-model="storeId" clearable placeholder="商家编号" style="width: 140px" />
          </el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="getQueue">筛选</el-button></el-form-item>
        </el-form>
        <cy-resource-state v-if="queueError" kind="error" title="审核队列暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="getQueue" />
        <cy-empty-state v-else-if="!loading && !queue.length" title="没有待处理的承诺" />
        <el-table v-else v-loading="loading" :data="queue" size="small" border>
          <el-table-column label="承诺" min-width="150">
            <template slot-scope="s">{{ s.row.pledgeId }} · 第 {{ s.row.versionNo }} 版</template>
          </el-table-column>
          <el-table-column label="商家 / 优惠券" min-width="140">
            <template slot-scope="s">商家 #{{ s.row.storeId }} · 券 #{{ s.row.couponId }}</template>
          </el-table-column>
          <el-table-column label="名额" prop="quantity" width="70" align="center" />
          <el-table-column label="服务期" min-width="200">
            <template slot-scope="s">{{ day(s.row.serviceStart) }} 至 {{ day(s.row.serviceEnd) }}</template>
          </el-table-column>
          <el-table-column label="兑换期" min-width="200">
            <template slot-scope="s">{{ day(s.row.redeemStart) }} 至 {{ day(s.row.redeemEnd) }}</template>
          </el-table-column>
          <el-table-column label="条款" prop="terms" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="90" align="center">
            <template slot-scope="s">{{ stateText(s.row.state) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="190" align="center">
            <template slot-scope="s">
              <template v-if="s.row.state === 'CONFIRMED'">
                <el-button v-hasPermi="['business:rewardPledge:review']" type="text" size="mini" :disabled="!platformReviewOpen" @click="openDecision(s.row, 'approve')">通过</el-button>
                <el-button v-hasPermi="['business:rewardPledge:review']" type="text" size="mini" :disabled="!platformReviewOpen" @click="openDecision(s.row, 'reject')">驳回</el-button>
              </template>
              <el-button v-hasPermi="['business:rewardPledge:review']" type="text" size="mini" @click="openDecision(s.row, 'note')">备注</el-button>
              <el-button type="text" size="mini" @click="showNotes(s.row)">记录</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="奖励政策" name="policy">
        <el-table v-loading="loading" :data="policies" size="small" border>
          <el-table-column label="政策" min-width="160">
            <template slot-scope="s">{{ policyText(s.row.policyCode) }}</template>
          </el-table-column>
          <el-table-column label="规则版本" prop="ruleVersion" min-width="140">
            <template slot-scope="s">{{ s.row.ruleVersion || '不分版本' }}</template>
          </el-table-column>
          <el-table-column label="决定" width="90" align="center">
            <template slot-scope="s"><cy-status-tag :label="s.row.decision === 'OPEN' ? '开启' : '关闭'" :tone="s.row.decision === 'OPEN' ? 'success' : 'neutral'" /></template>
          </el-table-column>
          <el-table-column label="App 内审核人" width="130">
            <template slot-scope="s">{{ s.row.actorMemberId ? '会员 #' + s.row.actorMemberId : '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:rewardPledge:policy']" type="text" size="mini" @click="openPolicy(s.row)">修改</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button v-hasPermi="['business:rewardPledge:policy']" size="small" type="primary" style="margin-top: 12px" @click="openPolicy(null)">新增政策</el-button>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="decisionTitle" :visible.sync="decisionOpen" width="480px" append-to-body>
      <el-form size="small" label-width="80px">
        <el-form-item v-if="decision.kind === 'approve'" label="规则版本">
          <el-select v-model="decision.ruleVersion" filterable allow-create placeholder="选择已开启的规则版本">
            <el-option v-for="v in openRuleVersions" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="decision.note" type="textarea" :rows="3" maxlength="500" show-word-limit
            :placeholder="decision.kind === 'approve' ? '可选' : '必填'" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="decisionOpen = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitDecision">确定</el-button>
      </div>
    </el-dialog>

    <el-dialog title="政策" :visible.sync="policyOpen" width="480px" append-to-body>
      <el-form size="small" label-width="100px">
        <el-form-item label="政策">
          <el-select v-model="policyForm.policyCode" :disabled="policyForm.locked">
            <el-option v-for="c in codes" :key="c" :label="policyText(c)" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="versionedCodes.includes(policyForm.policyCode)" label="规则版本">
          <el-input v-model="policyForm.ruleVersion" :disabled="policyForm.locked" placeholder="如 2026-11-v1" />
        </el-form-item>
        <el-form-item label="决定">
          <el-radio-group v-model="policyForm.decision">
            <el-radio label="OPEN">开启</el-radio>
            <el-radio label="CLOSED">关闭</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="policyForm.policyCode === 'REVIEWER'" label="审核人会员">
          <el-input v-model="policyForm.actorMemberId" placeholder="会员编号" />
        </el-form-item>
        <el-form-item label="变更说明">
          <el-input v-model="policyForm.note" type="textarea" :rows="2" maxlength="500" placeholder="必填" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="policyOpen = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitPolicy">保存</el-button>
      </div>
    </el-dialog>

    <el-dialog title="审核记录" :visible.sync="notesOpen" width="560px" append-to-body>
      <cy-empty-state v-if="!notes.length" title="还没有审核记录" />
      <el-timeline v-else>
        <el-timeline-item v-for="n in notes" :key="n.id" :timestamp="n.createTime">
          {{ decisionText(n.decision) }} · 后台账号 #{{ n.sysUserId }}<span v-if="n.note">：{{ n.note }}</span>
        </el-timeline-item>
      </el-timeline>
    </el-dialog>
  </div>
</template>

<script>
import { rewardPolicies, saveRewardPolicy, rewardPledgeQueue, rewardPledgeNotes, approveRewardPledge, rejectRewardPledge, noteRewardPledge } from '@/api/business/rewardPledge'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const POLICY_TEXT = {
  PLATFORM_REVIEW: '平台审核（本页通过/驳回）',
  REVIEWER: 'App 内审核人',
  RULE: '规则版本可用',
  CLAIM: '玩家可领取',
  MINOR: '未成年限定承诺可领取',
  STORE_HOLD: '商家暂停发放',
  RETURN_TO_POOL: '过期退回名额',
  COMPENSATION: '补偿发放',
  REVERSE: '平台撤回奖励'
}
const STATE_TEXT = { CONFIRMED: '待审核', FROZEN: '已通过', REJECTED: '已驳回', WITHDRAWN: '已撤回' }
const DECISION_TEXT = { APPROVE: '通过', REJECT: '驳回', NOTE: '备注' }

export default {
  name: 'RewardPledgeReview',
  components: { CyEmptyState, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      tab: 'queue',
      loading: false,
      saving: false,
      queueError: false,
      queue: [],
      queueState: 'CONFIRMED',
      storeId: '',
      states: Object.keys(STATE_TEXT).map(k => ({ value: k, label: STATE_TEXT[k] })),
      policies: [],
      codes: [],
      versionedCodes: [],
      platformReviewOpen: false,
      cashPayoutOpen: false,
      decisionOpen: false,
      decision: {},
      policyOpen: false,
      policyForm: {},
      notesOpen: false,
      notes: []
    }
  },
  computed: {
    decisionTitle() {
      return { approve: '通过承诺', reject: '驳回承诺', note: '添加备注' }[this.decision.kind] || ''
    },
    openRuleVersions() {
      return this.policies.filter(p => p.policyCode === 'RULE' && p.decision === 'OPEN').map(p => p.ruleVersion)
    }
  },
  created() {
    this.refresh()
  },
  methods: {
    refresh() {
      this.getPolicies()
      this.getQueue()
    },
    getPolicies() {
      return rewardPolicies().then(res => {
        const d = res.data || {}
        this.policies = d.items || []
        this.codes = d.codes || []
        this.versionedCodes = d.versionedCodes || []
        this.platformReviewOpen = !!d.platformReviewOpen
        this.cashPayoutOpen = !!d.cashPayoutOpen
      })
    },
    getQueue() {
      this.loading = true
      this.queueError = false
      rewardPledgeQueue({ state: this.queueState, storeId: this.storeId || undefined }).then(res => {
        const d = res.data || {}
        this.queue = d.items || []
        this.platformReviewOpen = !!d.platformReviewOpen
      }).catch(() => {
        this.queueError = true
      }).finally(() => {
        this.loading = false
      })
    },
    day(ms) {
      return ms ? this.parseTime(new Date(Number(ms)), '{y}-{m}-{d}') : '—'
    },
    stateText(s) { return STATE_TEXT[s] || '未知状态' },
    policyText(c) { return POLICY_TEXT[c] || c },
    decisionText(d) { return DECISION_TEXT[d] || d },
    openDecision(row, kind) {
      this.decision = { kind, row, note: '', ruleVersion: this.openRuleVersions[0] || '' }
      this.decisionOpen = true
    },
    submitDecision() {
      const d = this.decision
      const body = { note: d.note, ruleVersion: d.ruleVersion }
      const call = d.kind === 'approve' ? approveRewardPledge(d.row.versionId, body)
        : d.kind === 'reject' ? rejectRewardPledge(d.row.versionId, body) : noteRewardPledge(d.row.versionId, body)
      this.saving = true
      call.then(() => {
        this.$modal.msgSuccess('已记录')
        this.decisionOpen = false
        this.getQueue()
      }).finally(() => {
        this.saving = false
      })
    },
    openPolicy(row) {
      this.policyForm = row
        ? { locked: true, policyCode: row.policyCode, ruleVersion: row.ruleVersion, decision: row.decision, actorMemberId: row.actorMemberId ? String(row.actorMemberId) : '', note: '' }
        : { locked: false, policyCode: 'PLATFORM_REVIEW', ruleVersion: '', decision: 'CLOSED', actorMemberId: '', note: '' }
      this.policyOpen = true
    },
    submitPolicy() {
      const f = this.policyForm
      this.saving = true
      saveRewardPolicy({
        policyCode: f.policyCode,
        ruleVersion: this.versionedCodes.includes(f.policyCode) ? f.ruleVersion : '',
        decision: f.decision,
        actorMemberId: f.actorMemberId || null,
        note: f.note
      }).then(() => {
        this.$modal.msgSuccess('政策已保存')
        this.policyOpen = false
        this.refresh()
      }).finally(() => {
        this.saving = false
      })
    },
    showNotes(row) {
      rewardPledgeNotes(row.pledgeId).then(res => {
        this.notes = res.data || []
        this.notesOpen = true
      })
    }
  }
}
</script>

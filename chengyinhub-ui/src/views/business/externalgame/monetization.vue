<template>
  <div class="app-container egm-page">
    <el-alert
      :title="moneyGateBanner"
      :type="moneyGateOpen && payoutGateOpen ? 'warning' : 'info'"
      :closable="false"
      show-icon
      class="mb12"
    />

    <el-tabs v-model="activeSection" @tab-click="onSectionSwitch">
      <el-tab-pane label="变现与分成配置" name="configs">
        <el-row :gutter="10" class="mb8">
          <el-col :span="6">
            <el-radio-group v-model="statusFilter" size="mini" @change="loadConfigs">
              <el-radio-button label="PENDING">待审</el-radio-button>
              <el-radio-button label="APPROVED">已通过</el-radio-button>
              <el-radio-button label="REJECTED">已驳回</el-radio-button>
              <el-radio-button label="ALL">全部</el-radio-button>
            </el-radio-group>
          </el-col>
          <el-col :span="1.5">
            <el-button plain size="mini" icon="el-icon-refresh" @click="loadConfigs">刷新</el-button>
          </el-col>
        </el-row>

        <el-table v-loading="loadingConfigs" :data="configRows" empty-text="暂无配置记录">
          <el-table-column prop="gameId" label="游戏 ID" min-width="140" />
          <el-table-column prop="gameName" label="名称" min-width="140" />
          <el-table-column label="定价模式" width="120">
            <template slot-scope="s">
              <el-tag size="mini" :type="s.row.pricingMode === 'PAID' ? 'warning' : 'info'">{{ s.row.pricingMode }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="售价 (元)" width="100">
            <template slot-scope="s">{{ formatYuan(s.row.priceFen) }}</template>
          </el-table-column>
          <el-table-column label="创作者分成" width="110">
            <template slot-scope="s">{{ (s.row.creatorShareBps / 100).toFixed(2) }}%</template>
          </el-table-column>
          <el-table-column label="通道手续费" width="120">
            <template slot-scope="s">{{ s.row.channelFeeBearer === 1 ? '平台承担' : '创作者承担' }}</template>
          </el-table-column>
          <el-table-column label="流量激励" min-width="150">
            <template slot-scope="s">
              <span v-if="s.row.trafficRewardMode === 'POINTS'">积分 {{ s.row.rewardPerPlayPoints }}/次</span>
              <span v-else-if="s.row.trafficRewardMode === 'TEST_MONEY'">TEST {{ formatYuan(s.row.rewardPerPlayFen) }}元/次</span>
              <span v-else>关闭</span>
            </template>
          </el-table-column>
          <el-table-column label="日上限(人/戏)" width="130">
            <template slot-scope="s">{{ s.row.dailyUserCap }} / {{ s.row.dailyGameCap }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template slot-scope="s">
              <el-tag size="mini" :type="statusTagType(s.row.reviewStatus)">{{ s.row.reviewStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template slot-scope="s">
              <el-button
                v-hasPermi="['business:audit:review']"
                type="text"
                size="mini"
                @click="openReviewDialog(s.row)"
              >审核/配置</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="流量激励日结台账" name="settlements">
        <el-row :gutter="10" class="mb8">
          <el-col :span="4">
            <el-input v-model="settlementGameId" size="mini" clearable placeholder="按 gameId 过滤" />
          </el-col>
          <el-col :span="4">
            <el-date-picker
              v-model="settleDate"
              type="date"
              size="mini"
              value-format="yyyy-MM-dd"
              placeholder="结算日期(默认昨日)"
              style="width: 100%"
            />
          </el-col>
          <el-col :span="1.5">
            <el-button plain size="mini" icon="el-icon-refresh" @click="loadSettlements">查询</el-button>
          </el-col>
          <el-col :span="2">
            <el-button
              v-hasPermi="['business:audit:review']"
              type="primary"
              size="mini"
              :loading="settling"
              @click="runManualSettle"
            >立即日结</el-button>
          </el-col>
        </el-row>

        <el-table v-loading="loadingSettlements" :data="settlementRows" empty-text="暂无日结台账">
          <el-table-column prop="settlementDate" label="结算日期" width="110" />
          <el-table-column prop="gameId" label="游戏 ID" min-width="140" />
          <el-table-column prop="creatorMemberId" label="创作者 ID" width="100" />
          <el-table-column prop="totalPlays" label="总游玩" width="80" />
          <el-table-column prop="validPlays" label="有效游玩" width="90" />
          <el-table-column prop="cappedPlays" label="拦截/超限" width="90" />
          <el-table-column prop="rewardMode" label="激励方式" width="110" />
          <el-table-column prop="settledPoints" label="结算积分" width="90" />
          <el-table-column label="结算 TEST 金额" width="130">
            <template slot-scope="s">{{ formatYuan(s.row.settledFen) }} 元</template>
          </el-table-column>
          <el-table-column label="状态" width="180">
            <template slot-scope="s">
              <el-tag size="mini" :type="s.row.status === 'SETTLED' ? 'success' : s.row.status === 'DEFERRED_MONEY_CLOSED' ? 'warning' : 'info'">{{ s.row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="说明" min-width="140" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="'变现审核与分账配置 · ' + (editForm.gameId || '')" :visible.sync="dialogVisible" width="560px">
      <el-form :model="editForm" label-width="150px" size="small">
        <el-form-item label="创作者分成(bps)">
          <el-input-number v-model="editForm.creatorShareBps" :min="0" :max="10000" :step="100" />
          <span class="eg-sub"> ({{ (editForm.creatorShareBps / 100).toFixed(2) }}%)</span>
        </el-form-item>
        <el-form-item label="通道手续费承担方">
          <el-radio-group v-model="editForm.channelFeeBearer">
            <el-radio :label="0">创作者承担</el-radio>
            <el-radio :label="1">平台承担</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="流量激励模式">
          <el-select v-model="editForm.trafficRewardMode">
            <el-option label="积分 (POINTS)" value="POINTS" />
            <el-option label="TEST 现金 (TEST_MONEY)" value="TEST_MONEY" />
            <el-option label="关闭 (NONE)" value="NONE" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editForm.trafficRewardMode === 'POINTS'" label="每次有效游玩积分">
          <el-input-number v-model="editForm.rewardPerPlayPoints" :min="0" :max="1000" />
        </el-form-item>
        <el-form-item v-if="editForm.trafficRewardMode === 'TEST_MONEY'" label="每次有效游玩(分)">
          <el-input-number v-model="editForm.rewardPerPlayFen" :min="0" :max="100000" />
        </el-form-item>
        <el-form-item label="单玩家每日上限">
          <el-input-number v-model="editForm.dailyUserCap" :min="0" :max="500" />
        </el-form-item>
        <el-form-item label="单游戏每日上限">
          <el-input-number v-model="editForm.dailyGameCap" :min="0" :max="50000" />
        </el-form-item>
        <el-form-item label="最小驻留毫秒">
          <el-input-number v-model="editForm.minDurationMs" :min="0" :max="300000" :step="1000" />
        </el-form-item>
        <el-form-item label="审核备注/驳回原因">
          <el-input v-model="editForm.reviewNote" type="textarea" :rows="2" placeholder="驳回时必填" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" size="small" :loading="submitting" @click="submitDecision('REJECTED')">驳回</el-button>
        <el-button type="primary" size="small" :loading="submitting" @click="submitDecision('APPROVED')">通过并保存配置</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listExternalGameMonetization,
  reviewExternalGameMonetization,
  listExternalGameSettlements,
  triggerExternalGameSettlement
} from '@/api/business/reviewQueues'

export default {
  name: 'ExternalGameMonetization',
  data() {
    return {
      activeSection: 'configs',
      statusFilter: 'PENDING',
      loadingConfigs: false,
      configRows: [],
      moneyGateOpen: false,
      payoutGateOpen: false,
      loadingSettlements: false,
      settlementRows: [],
      settlementGameId: '',
      settleDate: '',
      settling: false,
      dialogVisible: false,
      submitting: false,
      editForm: {
        gameId: '',
        creatorShareBps: 7000,
        channelFeeBearer: 0,
        trafficRewardMode: 'POINTS',
        rewardPerPlayPoints: 5,
        rewardPerPlayFen: 0,
        dailyUserCap: 3,
        dailyGameCap: 200,
        minDurationMs: 5000,
        reviewNote: ''
      }
    }
  },
  computed: {
    moneyGateBanner() {
      const payText = this.moneyGateOpen ? '收款门控(TEST): 开启' : '收款门控: 关闭（真实购买已拦截）'
      const payoutText = this.payoutGateOpen ? '现金结算门控(TEST): 开启' : '现金结算门控: 关闭（仅积分正常结算）'
      return `${payText} ｜ ${payoutText}`
    }
  },
  created() {
    this.loadConfigs()
  },
  methods: {
    formatYuan(fen) {
      const n = Number(fen || 0)
      return (n / 100).toFixed(2)
    },
    statusTagType(st) {
      if (st === 'APPROVED') return 'success'
      if (st === 'REJECTED') return 'danger'
      if (st === 'PENDING') return 'warning'
      return 'info'
    },
    onSectionSwitch(tab) {
      if (tab && tab.name === 'settlements') {
        this.loadSettlements()
      } else {
        this.loadConfigs()
      }
    },
    showError(err) {
      const text = typeof err === 'string' ? err : (err && (err.msg || err.message))
      this.$message.error(text || '请求失败，请稍后重试')
    },
    loadConfigs() {
      this.loadingConfigs = true
      listExternalGameMonetization({ reviewStatus: this.statusFilter, pageNum: 1, pageSize: 50 })
        .then(res => {
          const data = res.data || {}
          this.configRows = data.rows || []
          this.moneyGateOpen = Boolean(data.moneyGateOpen)
          this.payoutGateOpen = Boolean(data.payoutGateOpen)
        })
        .catch(err => this.showError(err))
        .finally(() => { this.loadingConfigs = false })
    },
    openReviewDialog(row) {
      this.editForm = {
        gameId: row.gameId,
        creatorShareBps: row.creatorShareBps != null ? row.creatorShareBps : 7000,
        channelFeeBearer: row.channelFeeBearer != null ? row.channelFeeBearer : 0,
        trafficRewardMode: row.trafficRewardMode || 'POINTS',
        rewardPerPlayPoints: row.rewardPerPlayPoints != null ? row.rewardPerPlayPoints : 5,
        rewardPerPlayFen: row.rewardPerPlayFen != null ? row.rewardPerPlayFen : 0,
        dailyUserCap: row.dailyUserCap != null ? row.dailyUserCap : 3,
        dailyGameCap: row.dailyGameCap != null ? row.dailyGameCap : 200,
        minDurationMs: row.minDurationMs != null ? row.minDurationMs : 5000,
        reviewNote: row.reviewNote || ''
      }
      this.dialogVisible = true
    },
    submitDecision(decision) {
      if (decision === 'REJECTED' && !String(this.editForm.reviewNote || '').trim()) {
        this.$message.error('驳回时请填写原因')
        return
      }
      this.submitting = true
      reviewExternalGameMonetization(this.editForm.gameId, {
        decision,
        creatorShareBps: this.editForm.creatorShareBps,
        channelFeeBearer: this.editForm.channelFeeBearer,
        trafficRewardMode: this.editForm.trafficRewardMode,
        rewardPerPlayPoints: this.editForm.rewardPerPlayPoints,
        rewardPerPlayFen: this.editForm.rewardPerPlayFen,
        dailyUserCap: this.editForm.dailyUserCap,
        dailyGameCap: this.editForm.dailyGameCap,
        minDurationMs: this.editForm.minDurationMs,
        reviewNote: this.editForm.reviewNote
      })
        .then(() => {
          this.$message.success(decision === 'APPROVED' ? '已审核通过并生效' : '已驳回')
          this.dialogVisible = false
          this.loadConfigs()
        })
        .catch(err => this.showError(err))
        .finally(() => { this.submitting = false })
    },
    loadSettlements() {
      this.loadingSettlements = true
      listExternalGameSettlements({
        settlementDate: this.settleDate || undefined,
        gameId: this.settlementGameId || undefined,
        pageNum: 1,
        pageSize: 50
      })
        .then(res => {
          const data = res.data || {}
          this.settlementRows = data.rows || []
          this.payoutGateOpen = Boolean(data.payoutGateOpen)
        })
        .catch(err => this.showError(err))
        .finally(() => { this.loadingSettlements = false })
    },
    runManualSettle() {
      this.settling = true
      triggerExternalGameSettlement({ date: this.settleDate || undefined })
        .then(res => {
          const d = res.data || {}
          this.$message.success(`日结完成：成功 ${d.settledCount || 0} 笔，门控延迟 ${d.deferredCount || 0} 笔`)
          this.loadSettlements()
        })
        .catch(err => this.showError(err))
        .finally(() => { this.settling = false })
    }
  }
}
</script>

<style scoped>
.mb12 { margin-bottom: 12px; }
.eg-sub { color: #909399; font-size: 12px; }
</style>

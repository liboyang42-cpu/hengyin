<template>
  <div class="app-container cy-domain-page points-gov">
    <cy-page-header
      eyebrow="运营配置"
      title="积分治理"
      description="积分可抵现金,这里控制每人每日上限和每月预算,并显示未兑积分对应的负债。城市币不可提现、不可兑换,不计负债。"
    >
      <el-date-picker
        slot="primary"
        v-model="period"
        type="month"
        value-format="yyyy-MM"
        placeholder="选择月份"
        :clearable="false"
        @change="load"
      />
    </cy-page-header>

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="积分治理数据暂时无法读取"
      description="没有改动任何配置。请重试;若仍失败,请确认迁移 migration_20261115_points_governance.sql 已执行。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="load"
    />

    <template v-else>
      <div v-loading="loading" class="points-gov__metrics">
        <cy-metric-card label="本月发放" :value="pointsAndYuan(board.issuedPoints, board.issuedYuan)" description="收入流水合计" connected />
        <cy-metric-card label="本月兑换/扣减" :value="pointsAndYuan(board.redeemedPoints, board.redeemedYuan)" description="支出流水合计" connected />
        <cy-metric-card label="未兑积分负债" :value="yuan(board.outstandingLiabilityYuan)" :description="`${num(board.outstandingPoints)} 积分 × ${board.yuanPerPoint || '-'} 元`" connected />
        <cy-metric-card label="城市币(不可兑换)" :value="num(cityCoin.outstanding)" :description="cityCoinDescription" :connected="cityCoin.available !== false" />
      </div>
      <p class="points-gov__mode">执行模式:{{ modeLabel }}</p>

      <h3 class="points-gov__section">月度预算</h3>
      <cy-data-table
        :data="board.programmes || []"
        :columns="programmeColumns"
        :loading="loading"
        :total="(board.programmes || []).length"
        :limit="50"
        empty-text="尚未配置奖励项目"
        @refresh="load"
      >
        <template slot="budget" slot-scope="scope">
          <span v-if="scope.row.budgetSet">{{ pointsAndYuan(scope.row.monthlyBudgetPoints, scope.row.budgetYuan) }}</span>
          <cy-status-tag v-else tone="warning" label="未设预算" />
        </template>
        <template slot="issued" slot-scope="scope">
          {{ pointsAndYuan(scope.row.issuedPoints, scope.row.issuedYuan) }}
        </template>
        <template slot="remaining" slot-scope="scope">
          {{ scope.row.budgetSet ? `${num(scope.row.remainingPoints)} 积分` : '-' }}
        </template>
        <template slot="status" slot-scope="scope">
          <cy-status-tag :tone="scope.row.status === '0' ? 'success' : 'neutral'" :label="scope.row.status === '0' ? '执行' : '停用'" />
        </template>
        <template slot="actions" slot-scope="scope">
          <el-button type="text" size="mini" icon="el-icon-edit" v-hasPermi="['business:pointsgov:edit']" @click="openBudget(scope.row)">调整预算</el-button>
        </template>
      </cy-data-table>

      <h3 class="points-gov__section">每人每日上限</h3>
      <cy-data-table
        :data="board.caps || []"
        :columns="capColumns"
        :loading="loading"
        :total="(board.caps || []).length"
        :limit="50"
        empty-text="尚未配置积分来源"
        @refresh="load"
      >
        <template slot="cap" slot-scope="scope">
          {{ scope.row.dailyCapPoints == null ? '不设上限' : `${num(scope.row.dailyCapPoints)} 积分` }}
        </template>
        <template slot="status" slot-scope="scope">
          <cy-status-tag :tone="scope.row.status === '0' ? 'success' : 'neutral'" :label="scope.row.status === '0' ? '执行' : '停用'" />
        </template>
        <template slot="actions" slot-scope="scope">
          <el-button type="text" size="mini" icon="el-icon-edit" v-hasPermi="['business:pointsgov:edit']" @click="openCap(scope.row)">调整上限</el-button>
        </template>
      </cy-data-table>
    </template>

    <el-dialog :title="form.kind === 'cap' ? '调整每日上限' : '调整月度预算'" :visible.sync="formOpen" width="480px" append-to-body :close-on-click-modal="false">
      <el-form label-width="96px">
        <el-form-item :label="form.kind === 'cap' ? '积分来源' : '奖励项目'">
          <span>{{ form.name }}</span>
        </el-form-item>
        <el-form-item :label="form.kind === 'cap' ? '每日上限' : '月度预算'">
          <el-input-number v-model="form.amount" :min="0" :max="form.kind === 'cap' ? 1000000 : 10000000000" :precision="0" controls-position="right" />
          <el-checkbox v-model="form.unlimited" class="points-gov__unlimited">{{ form.kind === 'cap' ? '不设上限' : '暂不设预算' }}</el-checkbox>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio label="0">执行</el-radio>
            <el-radio label="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { getPointsGovDashboard, updatePointsBudget, updatePointsCap } from '@/api/business/pointsgov'
import CyDataTable from '@/components/CyDataTable'
import CyMetricCard from '@/components/CyMetricCard'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const MODE_LABELS = { ENFORCE: '执行上限与预算', SHADOW: '只记录不拦截', OFF: '不检查' }

export default {
  name: 'PointsGovernance',
  components: { CyDataTable, CyMetricCard, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      period: '',
      loading: false,
      loadError: false,
      saving: false,
      formOpen: false,
      requestToken: 0,
      board: {},
      form: { kind: 'cap', key: null, name: '', amount: 0, unlimited: false, status: '0' },
      programmeColumns: [
        { key: 'programmeName', label: '奖励项目', prop: 'programmeName', minWidth: 140, locked: true },
        { key: 'budget', label: '月度预算', prop: 'monthlyBudgetPoints', minWidth: 170 },
        { key: 'issued', label: '本月已发', prop: 'issuedPoints', minWidth: 170 },
        { key: 'remaining', label: '剩余', prop: 'remainingPoints', width: 130 },
        { key: 'status', label: '状态', prop: 'status', width: 90, align: 'center' },
        { key: 'actions', label: '操作', width: 100, align: 'right', fixed: 'right', locked: true }
      ],
      capColumns: [
        { key: 'sourceName', label: '积分来源', prop: 'sourceName', minWidth: 140, locked: true },
        { key: 'eventType', label: '事件类型', prop: 'eventType', width: 100, align: 'center' },
        { key: 'programme', label: '计入项目', prop: 'programme', width: 110 },
        { key: 'cap', label: '每人每日上限', prop: 'dailyCapPoints', minWidth: 140 },
        { key: 'status', label: '状态', prop: 'status', width: 90, align: 'center' },
        { key: 'actions', label: '操作', width: 100, align: 'right', fixed: 'right', locked: true }
      ]
    }
  },
  computed: {
    cityCoin() {
      return this.board.cityCoin || {}
    },
    cityCoinDescription() {
      return `本月发放 ${this.num(this.cityCoin.creditedInPeriod)},负债 0 元`
    },
    modeLabel() {
      return MODE_LABELS[this.board.mode] || '-'
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      const requestToken = ++this.requestToken
      this.loading = true
      this.loadError = false
      return getPointsGovDashboard(this.period || undefined).then(response => {
        if (requestToken !== this.requestToken) return
        this.board = response.data || {}
        if (!this.period && this.board.period) this.period = this.board.period
      }).catch(() => {
        if (requestToken !== this.requestToken) return
        this.board = {}
        this.loadError = true
      }).then(() => {
        if (requestToken === this.requestToken) this.loading = false
      })
    },
    openBudget(row) {
      const budget = row.monthlyBudgetPoints
      this.form = { kind: 'budget', key: row.programme, name: row.programmeName, amount: budget == null ? 0 : Number(budget), unlimited: budget == null, status: row.status || '0' }
      this.formOpen = true
    },
    openCap(row) {
      const cap = row.dailyCapPoints
      this.form = { kind: 'cap', key: row.eventType, name: row.sourceName, amount: cap == null ? 0 : Number(cap), unlimited: cap == null, status: row.status || '0' }
      this.formOpen = true
    },
    submit() {
      if (this.saving) return
      this.saving = true
      const amount = this.form.unlimited ? null : this.form.amount
      const call = this.form.kind === 'cap'
        ? updatePointsCap({ eventType: this.form.key, dailyCapPoints: amount, status: this.form.status })
        : updatePointsBudget({ programme: this.form.key, monthlyBudgetPoints: amount, status: this.form.status })
      call.then(() => {
        this.$modal.msgSuccess('已保存')
        this.formOpen = false
        return this.load()
      }).catch(() => {
        this.$modal.msgError('保存失败,请核对数值后重试')
      }).then(() => {
        this.saving = false
      })
    },
    num(value) {
      return value == null ? '-' : Number(value).toLocaleString('zh-CN')
    },
    yuan(value) {
      return value == null ? '-' : `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
    },
    pointsAndYuan(points, yuan) {
      return points == null ? '-' : `${this.num(points)} 积分 / ${this.yuan(yuan)}`
    }
  }
}
</script>

<style lang="scss" scoped>
.points-gov__metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cy-space-3); margin: var(--cy-space-3-5) 0; }
.points-gov__mode { margin: 0 0 var(--cy-space-3-5); color: var(--cy-color-text-secondary); font-size: 12px; }
.points-gov__section { margin: var(--cy-space-3-5) 0 var(--cy-space-3); color: var(--cy-color-text-primary); font-size: 15px; }
.points-gov__unlimited { margin-left: 12px; }
</style>

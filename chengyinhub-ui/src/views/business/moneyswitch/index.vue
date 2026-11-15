<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="资金与售后"
      title="资金开关"
      description="App 六个资金区域的服务端开关。App 只有在编译上限和这里同时打开时才会收付款；默认全部关闭，每次改动都会留下记录。"
    />

    <el-alert
      v-if="!serverCeiling"
      type="info"
      :closable="false"
      show-icon
      title="服务端上限关闭：所有区域对 App 都是关闭的，也不能在这里打开。只有在业主确认后，由运维在测试环境打开上限。"
      style="margin-bottom: 16px"
    />

    <section class="cy-panel" aria-label="资金区域">
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column label="区域" prop="label" min-width="140" />
        <el-table-column label="键" prop="key" min-width="120" />
        <el-table-column label="存储值" min-width="100">
          <template slot-scope="scope">{{ scope.row.stored ? '开' : '关' }}</template>
        </el-table-column>
        <el-table-column label="App 实际" min-width="100">
          <template slot-scope="scope">{{ scope.row.effective ? '开' : '关' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="160">
          <template slot-scope="scope">
            <el-button
              v-if="scope.row.stored"
              v-hasPermi="['business:moneyswitch:edit']"
              size="mini"
              type="danger"
              plain
              @click="change(scope.row, false)"
            >关闭</el-button>
            <el-button
              v-else
              v-hasPermi="['business:moneyswitch:edit']"
              size="mini"
              type="warning"
              plain
              :disabled="!serverCeiling"
              @click="change(scope.row, true)"
            >打开</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="cy-panel" aria-label="改动记录" style="margin-top: 16px">
      <div class="cy-panel__heading"><div><h2>改动记录</h2><p>最近 50 条，只增不改。</p></div></div>
      <el-table :data="audit" size="small" empty-text="还没有改动">
        <el-table-column label="时间" prop="changed_at" min-width="160" />
        <el-table-column label="区域" prop="area" min-width="120" />
        <el-table-column label="旧值" min-width="70">
          <template slot-scope="scope">{{ scope.row.old_enabled ? '开' : '关' }}</template>
        </el-table-column>
        <el-table-column label="新值" min-width="70">
          <template slot-scope="scope">{{ scope.row.new_enabled ? '开' : '关' }}</template>
        </el-table-column>
        <el-table-column label="操作人" prop="changed_by" min-width="110" />
        <el-table-column label="原因" prop="reason" min-width="220" />
      </el-table>
    </section>
  </div>
</template>

<script>
import { getMoneySwitch, changeMoneySwitch, listMoneySwitchAudit } from '@/api/business/moneyswitch'

const LABELS = {
  tickets: '活动票务',
  pass: '通行证',
  merchantMoney: '商家收款',
  payout: '俱乐部结算出款',
  withdrawal: '用户提现',
  refunds: '退款'
}

export default {
  name: 'MoneySwitch',
  data() {
    return { loading: false, serverCeiling: false, rows: [], audit: [] }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      getMoneySwitch().then(res => {
        const view = res.data || {}
        this.serverCeiling = view.serverCeiling === true
        const areas = view.areas || {}
        this.rows = Object.keys(LABELS).map(key => ({
          key,
          label: LABELS[key],
          stored: !!(areas[key] && areas[key].stored),
          effective: !!(areas[key] && areas[key].effective)
        }))
      }).finally(() => { this.loading = false })
      listMoneySwitchAudit(50).then(res => { this.audit = res.data || [] })
    },
    change(row, enabled) {
      const verb = enabled ? '打开' : '关闭'
      this.$prompt(`${verb}「${row.label}」的原因（会记入改动记录）`, `${verb}资金区域`, {
        inputValidator: value => (value && value.trim().length > 0) || '请填写原因',
        confirmButtonText: verb,
        cancelButtonText: '取消'
      }).then(({ value }) => changeMoneySwitch({ area: row.key, enabled, reason: value }))
        .then(res => {
          if (res && res.code === 200) {
            this.$modal.msgSuccess(res.changed ? '已更新' : '没有变化')
          }
          this.load()
        })
        .catch(() => {})
    }
  }
}
</script>

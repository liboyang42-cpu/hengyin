<template>
  <div class="cy-monopoly-ops">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="收租结算每天 03:03、夜间维护每天 03:23、赛季结算每天 09:07 检查 自动运行（sys_job cityMonopolyTask）。这里的按钮用于补跑，每一步都按天 / 事件 / 赛季幂等。"
    />

    <el-row :gutter="16" class="cy-monopoly-row">
      <el-col :span="12">
        <h4>立即补跑</h4>
        <el-button size="small" :loading="running === 'settle'" v-hasPermi="['business:citygame:edit']" @click="confirmRun('settle')">收租结算</el-button>
        <el-button size="small" :loading="running === 'nightly'" v-hasPermi="['business:citygame:edit']" @click="confirmRun('nightly')">夜间维护</el-button>
        <div class="cy-monopoly-season">
          <el-input v-model="seasonId" size="small" placeholder="赛季 ID，例如 s2026w46" aria-label="赛季 ID" style="width:200px" />
          <el-button size="small" type="warning" :loading="running === 'season'" v-hasPermi="['business:citygame:edit']" @click="confirmRun('season')">结算赛季</el-button>
        </div>
        <pre v-if="lastResult" class="cy-monopoly-result">{{ lastResult }}</pre>
      </el-col>
      <el-col :span="12">
        <h4>规则配置（只读，规则哈希 {{ shortHash }}）</h4>
        <el-table :data="diffRows" size="mini" empty-text="与默认值一致" max-height="260">
          <el-table-column prop="key" label="配置项" min-width="200" />
          <el-table-column prop="def" label="默认" width="90" />
          <el-table-column prop="eff" label="生效" width="90" />
        </el-table>
      </el-col>
    </el-row>

    <h4>风控队列</h4>
    <el-radio-group v-model="abuseStatus" size="mini" @change="loadAbuse">
      <el-radio-button label="OPEN">待处理</el-radio-button>
      <el-radio-button label="CONFIRMED">已确认</el-radio-button>
      <el-radio-button label="DISMISSED">已驳回</el-radio-button>
    </el-radio-group>
    <el-table v-loading="abuseLoading" :data="abuse" size="mini" class="cy-monopoly-row" empty-text="没有记录">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="member_id" label="会员" width="90" />
      <el-table-column prop="kind" label="类型" width="110" />
      <el-table-column prop="action" label="处置" width="100" />
      <el-table-column prop="score" label="分值" width="70" />
      <el-table-column prop="detail_json" label="详情" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="150">
        <template slot-scope="s">
          <template v-if="s.row.status === 'OPEN'">
            <el-button type="text" size="mini" v-hasPermi="['business:citygame:edit']" @click="review(s.row, true)">确认</el-button>
            <el-button type="text" size="mini" v-hasPermi="['business:citygame:edit']" @click="review(s.row, false)">驳回</el-button>
          </template>
          <span v-else>{{ s.row.status }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import {
  getMonopolyConfig,
  runMonopolySettle,
  runMonopolyNightly,
  settleMonopolySeason,
  listMonopolyAbuse,
  reviewMonopolyAbuse
} from '@/api/business/citygame'

const RUN_TEXT = {
  settle: '把前一天及更早的待核验租金入账（或作废被风控标记的）？',
  nightly: '执行今天的旧领地转换、维护费、衰退与风控检测？',
  season: '结算这个赛季？结算后该赛季所有地产回到无主状态。'
}

export default {
  name: 'CityMonopolyOps',
  data() {
    return {
      config: null,
      running: '',
      lastResult: '',
      seasonId: '',
      abuseStatus: 'OPEN',
      abuse: [],
      abuseLoading: false
    }
  },
  computed: {
    shortHash() {
      return this.config && this.config.rulesHash ? String(this.config.rulesHash).slice(0, 8) : '—'
    },
    diffRows() {
      const diff = (this.config && this.config.diff) || {}
      return Object.keys(diff).map(key => ({ key, def: diff[key].default, eff: diff[key].effective }))
    }
  },
  created() {
    this.loadConfig()
    this.loadAbuse()
  },
  methods: {
    loadConfig() {
      getMonopolyConfig().then(res => { this.config = res.data || null }).catch(() => { this.config = null })
    },
    loadAbuse() {
      this.abuseLoading = true
      listMonopolyAbuse({ status: this.abuseStatus, limit: 100 })
        .then(res => { this.abuse = res.data || [] })
        .finally(() => { this.abuseLoading = false })
    },
    confirmRun(kind) {
      if (kind === 'season' && !this.seasonId) {
        this.$modal.msgWarning('请先填写赛季 ID')
        return
      }
      this.$modal.confirm(RUN_TEXT[kind]).then(() => {
        this.running = kind
        const call = kind === 'settle' ? runMonopolySettle() : kind === 'nightly' ? runMonopolyNightly() : settleMonopolySeason(this.seasonId)
        return call.then(res => {
          this.lastResult = JSON.stringify(res.data, null, 2)
          this.$modal.msgSuccess('已完成')
        })
      }).catch(() => {}).finally(() => { this.running = '' })
    },
    review(row, confirm) {
      const text = confirm ? '确认作弊？将作废待核验租金并追回本赛季已入账的租金。' : '驳回这条风控信号？'
      this.$modal.confirm(text).then(() => reviewMonopolyAbuse(row.id, confirm)).then(() => {
        this.$modal.msgSuccess('已处理')
        this.loadAbuse()
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.cy-monopoly-row { margin-top: 12px; }
.cy-monopoly-season { margin-top: 12px; }
.cy-monopoly-result { max-height: 200px; overflow: auto; font-size: 12px; }
</style>

<template>
  <div class="app-container">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="首报名日">
        <el-date-picker v-model="query.cohortDate" type="date" value-format="yyyy-MM-dd" :clearable="false" />
      </el-form-item>
      <el-form-item label="观察窗">
        <el-radio-group v-model="query.windowDays">
          <el-radio-button :label="7">7 日</el-radio-button>
          <el-radio-button :label="30">30 日</el-radio-button>
          <el-radio-button :label="90">90 日</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" :loading="loading" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <p v-if="error" class="qa-error">{{ error }} <el-button type="text" @click="load">重试</el-button></p>
    <template v-else-if="report">
      <dl class="qa-facts">
        <div><dt>观察窗</dt><dd>{{ timeText(report.windowStart) }} – {{ timeText(report.windowEnd) }}</dd></div>
        <div><dt>成熟</dt><dd>{{ report.mature ? '已成熟' : '未成熟' }}</dd></div>
        <div><dt>计算时间</dt><dd>{{ timeText(report.computedAt) }}</dd></div>
        <div><dt>口径版本</dt><dd>{{ report.basisVersion }}</dd></div>
        <div><dt>回放</dt><dd>{{ report.historicalReplayable === false ? '即时计算，不可历史回放' : '可回放' }}</dd></div>
        <div><dt>测试数据</dt><dd>{{ report.testExclusionBasis && report.testExclusionBasis.indexOf('NO_SERVER_MARKER') === 0 ? '未排除（无服务端标记）' : report.testExclusionBasis }}</dd></div>
      </dl>

      <section v-if="v1" class="qa-section">
        <h3>有效复报 · {{ v1.definitionVersion }}</h3>
        <p v-if="v1.available === false" class="qa-error">{{ v1.unknownReason || '暂不可用' }}</p>
        <el-table v-else :data="v1Rows" size="small">
          <el-table-column label="口径" prop="label" width="90" />
          <el-table-column label="定义" prop="basis" min-width="140" show-overflow-tooltip />
          <el-table-column label="首报名会员" prop="eligible" width="96" />
          <el-table-column label="成熟（分母）" prop="mature" width="104" />
          <el-table-column label="未成熟" prop="notMature" width="72" />
          <el-table-column label="再次报名（分子）" prop="retained" width="126" />
          <el-table-column label="比例" prop="rate" width="140" />
          <el-table-column label="部分退 / 待审退" prop="refunds" width="120" />
          <el-table-column label="迟到事实" prop="late" width="80" />
        </el-table>
        <dl class="qa-facts qa-facts--small">
          <div><dt>时间基准</dt><dd>{{ v1.timeBasis || '—' }}</dd></div>
          <div><dt>身份</dt><dd>{{ v1.identityBasis || '—' }}</dd></div>
          <div><dt>重算</dt><dd>{{ v1.recomputeBasis || '—' }}</dd></div>
        </dl>
      </section>

      <section class="qa-section">
        <h3>复报（{{ report.basisVersion }}）</h3>
        <el-table :data="v2Rows" size="small">
          <el-table-column label="口径" prop="label" width="120" />
          <el-table-column label="首报名会员" prop="eligible" width="110" />
          <el-table-column label="再次报名" prop="retained" width="110" />
          <el-table-column label="比例" prop="rate" width="110" />
          <el-table-column label="未成熟会员" prop="notMature" width="110" />
          <el-table-column label="说明" prop="note" min-width="200" />
        </el-table>
        <dl v-if="report.cohort" class="qa-facts qa-facts--small">
          <div><dt>全退排除</dt><dd>{{ countText(report.cohort.fullRefundExcluded) }}</dd></div>
          <div><dt>取消/过期排除</dt><dd>{{ countText(report.cohort.cancelledOrExpiredExcluded) }}</dd></div>
          <div><dt>同单重复合并</dt><dd>{{ countText(report.cohort.duplicateOrdersCollapsed) }}</dd></div>
          <div><dt>同单多席合并</dt><dd>{{ countText(report.cohort.multiSeatCollapsed) }}</dd></div>
          <div><dt>部分退 / 待审退</dt><dd>{{ countText(report.cohort.partialRefundMembers) }} / {{ countText(report.cohort.pendingRefundMembers) }}</dd></div>
          <div><dt>迟到事实</dt><dd>{{ countText(report.cohort.lateFactCount) }}</dd></div>
        </dl>
      </section>

      <section class="qa-section">
        <h3>结果项</h3>
        <el-table :data="metricRows" size="small" empty-text="没有结果项">
          <el-table-column label="结果" min-width="200">
            <template slot-scope="s">{{ metricLabel(s.row.metricCode) }}</template>
          </el-table-column>
          <el-table-column label="会员" width="90">
            <template slot-scope="s">{{ countText(s.row.members) }}</template>
          </el-table-column>
          <el-table-column label="事实" width="90">
            <template slot-scope="s">{{ countText(s.row.facts) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="200">
            <template slot-scope="s">{{ s.row.serverFinal ? '服务端终局事实' : '客户端观测，非最终结果' }}</template>
          </el-table-column>
          <el-table-column label="水位" width="160">
            <template slot-scope="s">{{ timeText(s.row.watermarkAt) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section class="qa-section">
        <h3>来源水位</h3>
        <dl class="qa-facts qa-facts--small">
          <div v-for="(at, source) in report.sourceWatermarks" :key="source"><dt>{{ source }}</dt><dd>{{ timeText(at) }}</dd></div>
        </dl>
        <ul v-if="report.unavailableSources" class="qa-unavailable">
          <li v-for="(why, source) in report.unavailableSources" :key="source"><strong>{{ source }}</strong>：{{ why }}</li>
        </ul>
      </section>
    </template>
  </div>
</template>

<script>
import { getPostGameOutcome } from '@/api/business/analytics'
import { businessError, countText, repeatSignupRateText, v2RetentionText } from '@/utils/questo-admin'

const METRIC_TEXT = {
  OFFICIAL_COMPLETION: '正式完局',
  REWARD_ISSUANCE: '奖励发放',
  PRIVATE_PHOTO: '私人照片',
  PUBLIC_SHARE: '公开分享',
  RECOMMENDATION_CLICK: '推荐点击',
  SECOND_VALID_REGISTRATION: '二次有效报名',
  CLIENT_OBSERVED_EVENT: '客户端观测事件'
}

function today() {
  const d = new Date()
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export default {
  name: 'BusinessPostGameOutcome',
  data() {
    return {
      loading: false,
      error: '',
      report: null,
      requestSeq: 0,
      query: { cohortDate: today(), windowDays: 30 }
    }
  },
  computed: {
    v1() { return this.report ? this.report.repeatSignupV1 : null },
    v1Rows() {
      if (!this.v1) return []
      return [['paid', '付费口径'], ['all', '全部报名']].filter(([key]) => this.v1[key]).map(([key, label]) => {
        const g = this.v1[key]
        return {
          label,
          basis: g.eligibilityBasis || '—',
          eligible: countText(g.eligibleMembers),
          mature: countText(g.matureMembers),
          notMature: countText(g.notMatureMembers),
          retained: countText(g.retainedMembers),
          rate: repeatSignupRateText(g),
          refunds: `${countText(g.partialRefundMembers)} / ${countText(g.pendingRefundMembers)}`,
          late: countText(g.lateFactCount)
        }
      })
    },
    v2Rows() {
      const r = this.report
      if (!r || !r.cohort) return []
      const c = r.cohort
      const note = r.mature ? '' : '观察窗未满，未满不算未复报'
      return [
        { label: '付费', eligible: countText(c.eligibleMembers), retained: countText(c.retainedMembers), rate: v2RetentionText(r, 'retentionRate'), notMature: countText(c.notMatureMembers), note },
        { label: '免费', eligible: countText(c.freeMembers), retained: countText(c.freeRetainedMembers), rate: v2RetentionText(r, 'freeRetentionRate'), notMature: '—', note }
      ]
    },
    metricRows() {
      const metrics = (this.report && this.report.metrics) || {}
      return Object.keys(metrics).map(code => Object.assign({ metricCode: code }, metrics[code]))
    }
  },
  created() {
    this.load()
  },
  methods: {
    countText,
    metricLabel(code) { return Object.prototype.hasOwnProperty.call(METRIC_TEXT, code) ? METRIC_TEXT[code] : code },
    timeText(value) { return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '—' },
    load() {
      const seq = ++this.requestSeq
      this.loading = true
      this.error = ''
      getPostGameOutcome(this.query).then(res => {
        if (seq !== this.requestSeq) return
        this.report = res.data || null
      }).catch(err => {
        if (seq !== this.requestSeq) return
        // 读取失败不显示 0%。
        this.report = null
        this.error = businessError(err).message
      }).finally(() => {
        if (seq === this.requestSeq) this.loading = false
      })
    }
  }
}
</script>

<style scoped>
.qa-error { color: #F56C6C; font-size: 13px; margin: 8px 0; }
.qa-section { margin-top: 20px; }
.qa-section h3 { font-size: 15px; margin: 0 0 10px; }
.qa-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 24px; margin: 8px 0 0; }
.qa-facts dt { color: #909399; font-size: 12px; }
.qa-facts dd { margin: 2px 0 0; word-break: break-word; }
.qa-facts--small dd { font-size: 12px; }
.qa-unavailable { color: #909399; font-size: 12px; padding-left: 16px; }
</style>

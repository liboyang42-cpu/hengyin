<template>
  <div class="app-container cy-domain-page verification-review">
    <cy-page-header
      eyebrow="审核与风控"
      title="核销异常复核"
      description="核销率与频次异常的人工复核队列。处置只记录人审结论，不改核销事实、不退款、不触发任何资金动作。"
    />

    <section class="cy-panel" aria-label="口径说明">
      <div class="cy-panel__heading">
        <div>
          <h2>两种入队原因</h2>
          <p>
            连扫过密：同一位核销负责人在一分钟内连续核销超过阈值。
            核销率偏高：某个主题的核销率超过设定阈值。
            探店日（自由探索）只按「连扫过密」入队：这类活动的核销率本来就接近到店转化率，
            用核销率判断会把正常经营误报成异常。
          </p>
        </div>
        <span class="verification-review__read-only">只做人审</span>
      </div>
    </section>

    <section class="cy-panel" aria-label="复核队列筛选与结果">
      <el-form :model="query" size="small" :inline="true" label-width="90px">
        <cy-filter-bar :active-filters="activeFilters" @submit="load" @reset="reset" @remove-filter="clearFilter">
          <el-form-item label="处置状态">
            <el-select v-model="query.status" style="width: 168px" clearable placeholder="全部">
              <el-option label="待复核" :value="0" />
              <el-option label="已确认正常" :value="1" />
              <el-option label="确认异常" :value="2" />
            </el-select>
          </el-form-item>
          <el-form-item label="主题">
            <el-input v-model="query.topicId" placeholder="主题编号" clearable />
          </el-form-item>
          <el-form-item label="核销负责人">
            <el-input v-model="query.verifierMemberId" placeholder="核销负责人编号" clearable />
          </el-form-item>
        </cy-filter-bar>
      </el-form>

      <cy-resource-state
        v-if="error"
        kind="error"
        title="复核队列暂时无法读取"
        :description="error"
        retry-text="重新读取"
        :retrying="loading"
        @retry="load"
      />

      <div v-else v-loading="loading">
        <el-table v-if="rows.length" :data="rows" border class="verification-review__table">
          <el-table-column label="工单" prop="id" width="90" align="center" />
          <el-table-column label="主题" prop="topicId" width="100" align="center" />
          <el-table-column label="核销负责人" prop="verifierMemberId" width="120" align="center" />
          <el-table-column label="入队原因" width="170" align="center">
            <template slot-scope="{ row }">
              <el-tag :type="riskTagType(row.riskType)" size="mini">{{ riskLabel(row.riskType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="核销率" width="110" align="center">
            <template slot-scope="{ row }">{{ ratePercent(row.verificationRate) }}</template>
          </el-table-column>
          <el-table-column label="已支付 / 已核销" width="140" align="center">
            <template slot-scope="{ row }">{{ row.paidCount }} / {{ row.verifiedCount }}</template>
          </el-table-column>
          <el-table-column label="一分钟内" prop="oneMinuteCount" width="100" align="center" />
          <el-table-column label="入队时间" prop="createTime" width="168" align="center" />
          <el-table-column label="处置" width="200" align="center">
            <template slot-scope="{ row }">
              <el-tag v-if="row.status !== 0" :type="row.status === 1 ? 'success' : 'danger'" size="mini">
                {{ statusLabel(row.status) }}
              </el-tag>
              <template v-else>
                <el-button
                  v-hasPermi="['business:verificationReview:handle']"
                  type="text"
                  size="mini"
                  @click="handle(row, 1)"
                >已确认正常</el-button>
                <el-button
                  v-hasPermi="['business:verificationReview:handle']"
                  type="text"
                  size="mini"
                  class="verification-review__abnormal"
                  @click="handle(row, 2)"
                >确认异常</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <cy-empty-state
          v-else-if="!loading"
          title="当前没有需要复核的核销异常"
          description="空队列是正常业务事实：探店日不按核销率入队，只有一分钟内连扫超阈值才会出现工单。"
          icon="el-icon-document-remove"
        />
      </div>
    </section>
  </div>
</template>

<script>
import CyEmptyState from '@/components/CyEmptyState'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import { listVerificationReview, handleVerificationReview } from '@/api/business/verificationReview'

export default {
  name: 'RiskVerificationReview',
  components: { CyEmptyState, CyFilterBar, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      error: '',
      rows: [],
      query: { status: 0, topicId: '', verifierMemberId: '' }
    }
  },
  computed: {
    activeFilters() {
      const filters = []
      if (this.query.status !== '' && this.query.status !== null && this.query.status !== undefined) {
        filters.push({ key: 'status', label: '处置状态', value: this.statusLabel(this.query.status) })
      }
      if (this.query.topicId) filters.push({ key: 'topicId', label: '主题', value: this.query.topicId })
      if (this.query.verifierMemberId) {
        filters.push({ key: 'verifierMemberId', label: '核销负责人', value: this.query.verifierMemberId })
      }
      return filters
    }
  },
  created() {
    this.load()
  },
  methods: {
    statusLabel(status) {
      if (status === 1) return '已确认正常'
      if (status === 2) return '确认异常'
      return '待复核'
    },
    riskLabel(riskType) {
      if (riskType === 'BURST') return '一分钟连扫'
      if (riskType === 'HIGH_RATE') return '核销率异常'
      if (riskType === 'HIGH_RATE_AND_BURST') return '核销率异常 + 连扫'
      return riskType || '未记录'
    },
    riskTagType(riskType) {
      return riskType === 'BURST' ? 'warning' : 'danger'
    },
    // 后端存的是 0-1 的小数（DECIMAL(10,4)）。⚠️ 不要在这里补默认 0：
    // 没有值时显示 0% 会被读成「一张都没核销」，而真相是这条工单没带率。
    ratePercent(rate) {
      if (rate === null || rate === undefined || rate === '') return '未记录'
      return (Number(rate) * 100).toFixed(2) + '%'
    },
    clearFilter(key) {
      // 摘掉状态筛选 = 看全部（含已处置），不是回到「待复核」——
      // 回默认值那条路是「重置」，两个动作分开才对得上运营的意图。
      this.query[key] = ''
      return this.load()
    },
    reset() {
      this.query = { status: 0, topicId: '', verifierMemberId: '' }
      return this.load()
    },
    load() {
      this.loading = true
      this.error = ''
      listVerificationReview(this.query).then(res => {
        this.rows = (res && res.data) || []
      }).catch(err => {
        this.error = (err && err.message) || '读取失败'
      }).finally(() => {
        this.loading = false
      })
    },
    handle(row, status) {
      const conclusion = status === 1 ? '已确认正常' : '确认异常'
      return this.$modal.confirm(
        '将工单 #' + row.id + ' 标记为「' + conclusion + '」。'
        + '本操作只记录人审结论，不会改动核销记录、不退款、不产生任何资金动作。'
      ).then(() => handleVerificationReview(row.id, status)).then(() => {
        this.$modal.msgSuccess('已处置')
        this.load()
      }).catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
.verification-review__read-only {
  font-size: 12px;
  color: #909399;
}

.verification-review__table {
  margin-top: 12px;
}

.verification-review__abnormal {
  color: #f56c6c;
}
</style>

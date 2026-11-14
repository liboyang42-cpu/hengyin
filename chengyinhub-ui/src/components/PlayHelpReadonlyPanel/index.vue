<template>
  <section class="play-help-readonly">
    <div class="play-help-readonly__head">
      <div>
        <h3>玩法帮助配置摘要与对局明细（只读）</h3>
        <p>展示提示阶梯、免费额度、跳过策略与单局回执明细；不暴露答案原文，不可改写对局或导出。</p>
      </div>
      <el-button v-if="topicId" size="mini" icon="el-icon-refresh" :loading="configLoading" @click="loadConfig">刷新配置</el-button>
    </div>

    <el-alert v-if="configError" :title="configError" type="warning" :closable="false" show-icon class="mb10" />
    <div v-else-if="config" class="play-help-readonly__summary">
      <el-descriptions :column="3" border size="mini" class="mb10">
        <el-descriptions-item label="发布修订">{{ config.revisionNo != null ? `v${config.revisionNo}` : '—' }}</el-descriptions-item>
        <el-descriptions-item label="整局免费次数">
          <span v-if="config.freeUsesState === 'CONFLICT'">声明冲突（CONFLICT）</span>
          <span v-else>{{ config.freeUsesPerRun == null ? '未配置（逐层口径）' : config.freeUsesPerRun }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态口径">只读摘要 · 秘密字段已隔离</el-descriptions-item>
      </el-descriptions>
      <el-table v-if="config.nodes && config.nodes.length" :data="config.nodes" size="mini" border empty-text="该修订未配置提示阶梯">
        <el-table-column label="节点 ID" prop="nodeId" width="100" />
        <el-table-column label="层数" prop="layerCount" width="80" />
        <el-table-column label="阶梯与跳过策略（层 / 类型 / 积分代价 / 免费额度）" min-width="300">
          <template slot-scope="{ row }">
            <div class="play-help-readonly__tiers">
              <el-tag v-for="lv in (row.levels || [])" :key="lv.level" size="mini" class="mr5">
                L{{ lv.level }} · {{ levelTypeLabel(lv.type) }} · {{ lv.costPoints != null ? `${lv.costPoints}分` : '0分' }} · 免费额度 {{ lv.freeQuota != null ? lv.freeQuota : '按局' }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="play-help-readonly__run">
      <el-form :inline="true" size="mini" @submit.native.prevent>
        <el-form-item label="对局 Run ID">
          <el-input v-model="runIdInput" placeholder="输入对局编号查回执明细" clearable style="width: 200px" @keyup.enter.native="loadRun" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="runLoading" @click="loadRun">查看单局只读明细</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="runError" :title="runError" type="warning" :closable="false" show-icon class="mb10" />
      <div v-if="runDetail" class="play-help-readonly__run-body">
        <el-descriptions :column="3" border size="mini" class="mb10">
          <el-descriptions-item label="对局">#{{ runDetail.runId }}</el-descriptions-item>
          <el-descriptions-item label="会员">#{{ runDetail.memberId }}</el-descriptions-item>
          <el-descriptions-item label="冻结版本">{{ runDetail.configVersion != null ? `v${runDetail.configVersion}` : '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="runDetail.nodes || []" size="mini" border empty-text="该局无节点明细">
          <el-table-column type="expand">
            <template slot-scope="{ row }">
              <el-table v-if="row.helpLines && row.helpLines.length" :data="row.helpLines" size="mini" border>
                <el-table-column label="回执号（申诉核对）" prop="receiptId" width="150" />
                <el-table-column label="层级" prop="level" width="80" />
                <el-table-column label="动作类型" min-width="110">
                  <template slot-scope="{ row: line }">{{ levelTypeLabel(line.type) }}</template>
                </el-table-column>
                <el-table-column label="免费/扣分" width="120">
                  <template slot-scope="{ row: line }">{{ line.free ? '免费额度' : (line.pointsCharged != null ? `-${line.pointsCharged} 积分` : '—') }}</template>
                </el-table-column>
                <el-table-column label="发生时间" prop="createTime" min-width="160" />
              </el-table>
              <p v-else class="play-help-readonly__muted">该节点无提示或跳过消费流水。</p>
            </template>
          </el-table-column>
          <el-table-column label="节点 ID" prop="nodeId" width="100" />
          <el-table-column label="状态" width="120" prop="state" />
          <el-table-column label="跳过状态" width="110">
            <template slot-scope="{ row }">{{ row.receipt && row.receipt.skipped ? '已跳过(0分)' : '未跳过' }}</template>
          </el-table-column>
          <el-table-column label="累计扣分" width="100">
            <template slot-scope="{ row }">{{ row.receipt && row.receipt.totalPointsCharged != null ? row.receipt.totalPointsCharged : '—' }}</template>
          </el-table-column>
          <el-table-column label="净分" width="100">
            <template slot-scope="{ row }">{{ row.receipt && row.receipt.netScore != null ? row.receipt.netScore : '—' }}</template>
          </el-table-column>
          <el-table-column label="回执条数" min-width="100">
            <template slot-scope="{ row }">{{ Array.isArray(row.helpLines) ? row.helpLines.length : 0 }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </section>
</template>

<script>
import { getTopicHelpConfig, getPlayHelpRunLedger } from '@/api/business/playhelp'
import { businessError } from '@/utils/questo-admin'

export default {
  name: 'PlayHelpReadonlyPanel',
  props: {
    topicId: { type: [Number, String], default: null }
  },
  data() {
    return {
      configLoading: false,
      configError: '',
      config: null,
      runIdInput: '',
      runLoading: false,
      runError: '',
      runDetail: null
    }
  },
  watch: {
    topicId: {
      immediate: true,
      handler(val) {
        if (val) this.loadConfig()
      }
    }
  },
  methods: {
    levelTypeLabel(type) {
      return ({ TEXT: '文本提示', ANSWER: '答案揭晓', SKIP: '跳过本题' })[type] || (type || '未知')
    },
    loadConfig() {
      if (!this.topicId) return
      this.configLoading = true
      this.configError = ''
      getTopicHelpConfig(this.topicId).then(res => {
        this.config = res && res.data ? res.data : res
      }).catch(err => {
        this.config = null
        this.configError = businessError(err).message || '暂无玩法帮助配置摘要或无读取权限（business:playhelp:query）'
      }).finally(() => { this.configLoading = false })
    },
    loadRun() {
      const id = String(this.runIdInput || '').trim()
      if (!/^\d+$/.test(id)) {
        this.runError = '请输入正整数对局编号'
        return
      }
      this.runLoading = true
      this.runError = ''
      getPlayHelpRunLedger(id).then(res => {
        this.runDetail = res && res.data ? res.data : res
      }).catch(err => {
        this.runDetail = null
        this.runError = businessError(err).message || '单局明细读取失败或无权限（business:playhelp:query）'
      }).finally(() => { this.runLoading = false })
    }
  }
}
</script>

<style scoped>
.play-help-readonly { padding: 14px 16px; border: 1px solid #ebeef5; border-radius: 6px; background: #fafafa; margin-top: 12px; }
.play-help-readonly__head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 10px; }
.play-help-readonly__head h3 { margin: 0 0 4px; font-size: 14px; }
.play-help-readonly__head p { margin: 0; font-size: 12px; color: #606266; }
.play-help-readonly__run { margin-top: 12px; padding-top: 10px; border-top: 1px dashed #dcdfe6; }
.play-help-readonly__muted { margin: 0; font-size: 12px; color: #909399; }
.mr5 { margin-right: 6px; margin-bottom: 4px; }
.mb10 { margin-bottom: 10px; }
</style>

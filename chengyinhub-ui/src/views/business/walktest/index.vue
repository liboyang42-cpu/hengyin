<template>
  <div class="app-container">
    <el-alert type="info" :closable="false" show-icon :title="modeText" />

    <el-form :inline="true" size="small" class="mt8">
      <el-form-item label="路线编号">
        <el-input v-model="lookupId" placeholder="查看任一路线的试走状态" style="width: 200px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="lookup">查看</el-button>
        <el-button icon="el-icon-refresh" @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="路线编号" prop="topicId" width="90" />
      <el-table-column label="路线" prop="topicName" />
      <el-table-column label="作者" prop="ownerId" width="90" />
      <el-table-column label="桌面排练" width="90"><template slot-scope="s">{{ s.row.deskCount > 0 ? '已记录' : '未记录' }}</template></el-table-column>
      <el-table-column label="实地试走" width="90"><template slot-scope="s">{{ s.row.onsiteCount > 0 ? '已记录' : '未记录' }}</template></el-table-column>
      <el-table-column label="最近记录" prop="lastRecordedAt" width="160" />
      <el-table-column label="豁免" prop="waiverNote" />
      <el-table-column label="操作" width="160">
        <template slot-scope="s">
          <el-button type="text" @click="openStatus(s.row.topicId)">详情</el-button>
          <el-button v-if="!s.row.waiverNote" v-hasPermi="['business:walkTest:waive']" type="text" @click="waive(s.row.topicId)">豁免</el-button>
          <el-button v-else v-hasPermi="['business:walkTest:waive']" type="text" @click="unwaive(s.row.topicId)">撤销豁免</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog title="试走状态" :visible.sync="statusOpen" width="640px" append-to-body>
      <div v-if="current">
        <p>桌面排练:{{ current.deskRecorded ? '已记录' : '未记录' }};实地试走:{{ current.onsiteRecorded ? '已记录' : '未记录' }}</p>
        <p v-if="current.waiver">已豁免:{{ current.waiver.note }}({{ current.waiver.waivedBy }})</p>
        <p>{{ current.blocksPublishing ? '现在不能发布。' : '不影响发布。' }}</p>
        <el-table :data="current.records || []" size="mini">
          <el-table-column label="类型" width="90"><template slot-scope="s">{{ s.row.kind === 'DESK' ? '桌面排练' : '实地试走' }}</template></el-table-column>
          <el-table-column label="时间" prop="recordedAt" width="160" />
          <el-table-column label="地点数" width="80"><template slot-scope="s">{{ s.row.nodesChecked }} / {{ s.row.nodesTotal }}</template></el-table-column>
          <el-table-column label="定位" prop="fixDecision" width="140" />
          <el-table-column label="备注" prop="note" />
        </el-table>
        <div class="mt8">
          <el-button v-if="!current.waiver" v-hasPermi="['business:walkTest:waive']" size="small" @click="waive(currentId)">豁免</el-button>
          <el-button v-else v-hasPermi="['business:walkTest:waive']" size="small" @click="unwaive(currentId)">撤销豁免</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listWalkTests, getWalkTest, waiveWalkTest, unwaiveWalkTest } from '@/api/business/gameMechanics'

export default {
  name: 'WalkTest',
  data() {
    return { loading: false, rows: [], mode: 'ADVISE', lookupId: '', statusOpen: false, current: null, currentId: null }
  },
  computed: {
    modeText() {
      const words = {
        OFF: '试走门槛已关闭:发布前不检查桌面排练和实地试走。',
        ADVISE: '试走门槛为「提醒」:发布前的检查会提示缺少的排练或实地试走,但不拦发布。',
        ENFORCE: '试走门槛为「必须」:缺少桌面排练或实地试走的路线不能发布,除非在这里豁免并写明理由。'
      }
      return (words[this.mode] || words.ADVISE) + '模式在参数设置 creator.walktest.gate 中修改。'
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      listWalkTests().then(res => {
        const d = res.data || {}
        this.mode = d.mode || 'ADVISE'
        this.rows = d.rows || []
      }).finally(() => { this.loading = false })
    },
    lookup() {
      const id = Number(String(this.lookupId).trim())
      if (!Number.isInteger(id) || id <= 0) { this.$modal.msgError('路线编号无效'); return }
      this.openStatus(id)
    },
    openStatus(topicId) {
      getWalkTest(topicId).then(res => {
        this.current = res.data || null
        this.currentId = topicId
        this.statusOpen = true
      })
    },
    waive(topicId) {
      this.$prompt('请写明豁免理由,会记录在案。', '豁免试走', { inputValidator: v => !!(v && v.trim()) || '请填写理由' })
        .then(({ value }) => waiveWalkTest(topicId, value))
        .then(() => { this.load(); if (this.statusOpen) this.openStatus(topicId) })
        .catch(() => {})
    },
    unwaive(topicId) {
      this.$modal.confirm('撤销后,这条路线需要重新满足试走要求。').then(() => unwaiveWalkTest(topicId))
        .then(() => { this.load(); if (this.statusOpen) this.openStatus(topicId) })
        .catch(() => {})
    }
  }
}
</script>

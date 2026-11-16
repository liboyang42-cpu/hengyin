<template>
  <div class="app-container">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="游戏标识">
        <el-input v-model="query.gameId" clearable style="width: 200px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable style="width: 120px" @change="search">
          <el-option label="待处理" value="OPEN" />
          <el-option label="已处理" value="RESOLVED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <p v-if="listError" class="eg-error">{{ listError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" empty-text="没有举报">
      <el-table-column label="编号" width="80">
        <template slot-scope="s">#{{ s.row.reportId }}</template>
      </el-table-column>
      <el-table-column label="游戏" min-width="170">
        <template slot-scope="s">
          <el-button type="text" @click="filterGame(s.row.gameId)">{{ s.row.gameId }}</el-button>
          <span class="eg-sub"> v{{ s.row.version }}</span>
          <el-tag v-if="!s.row.knownToCatalog" size="mini" type="info">非目录</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="原因" prop="reasonCode" width="140" />
      <el-table-column label="说明" prop="detail" min-width="220" show-overflow-tooltip />
      <el-table-column label="已屏蔽" width="80">
        <template slot-scope="s">{{ s.row.blocked ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="举报人" width="100">
        <template slot-scope="s">{{ s.row.reporterMemberId || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template slot-scope="s">
          <el-tag size="small" :type="s.row.status === 'OPEN' ? 'warning' : 'success'">{{ statusText(s.row) }}</el-tag>
          <el-tag v-if="s.row.takenDown" size="mini" type="danger">已下架</el-tag>
          <div v-if="s.row.resolutionNote" class="eg-sub">{{ s.row.resolutionNote }}</div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="140">
        <template slot-scope="s">{{ timeText(s.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230">
        <template slot-scope="s">
          <el-button v-if="s.row.submissionId" type="text" @click="openSubmission(s.row)">看提交</el-button>
          <el-button v-if="s.row.status === 'OPEN'" v-hasPermi="['business:externalgame:report']" type="text" @click="openResolve(s.row, 'ACTIONED')">已处理</el-button>
          <el-button v-if="s.row.status === 'OPEN'" v-hasPermi="['business:externalgame:report']" type="text" @click="openResolve(s.row, 'DISMISSED')">驳回</el-button>
          <el-button v-if="!s.row.takenDown" v-hasPermi="['business:externalgame:takedown']" type="text" @click="openTakedown(s.row)">下架</el-button>
          <el-button v-else v-hasPermi="['business:externalgame:takedown']" type="text" @click="openRestore(s.row)">恢复上架</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />

    <el-dialog :title="dialogTitle" :visible.sync="dialog.open" width="520px" append-to-body>
      <p class="eg-sub">游戏 {{ dialog.gameId }}<span v-if="dialog.reportId">，举报 #{{ dialog.reportId }}</span></p>
      <el-form label-width="90px" size="small" @submit.native.prevent>
        <el-form-item v-if="dialog.kind === 'takedown'" label="原因类别">
          <el-select v-model="dialog.reasonCode" style="width: 100%">
            <el-option v-for="r in reasonCodes" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="dialog.kind === 'resolve' ? '说明' : '原因'">
          <el-input v-model="dialog.text" type="textarea" :rows="3" :maxlength="500" show-word-limit
                    :placeholder="textPlaceholder" />
        </el-form-item>
        <el-form-item v-if="dialog.kind === 'takedown'">
          <el-checkbox v-model="dialog.resolveOpenReports">同时把该游戏所有待处理举报标为已处理</el-checkbox>
        </el-form-item>
      </el-form>
      <p v-if="dialog.kind === 'takedown'" class="eg-sub">下架后目录、下载、节点入口和新开局都会关闭，进行中的对局作废；原因只留在后台，不对玩家展示。</p>
      <p v-if="dialog.error" class="eg-error">{{ dialog.error }}</p>
      <div slot="footer">
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dialog.busy" :disabled="!canSubmit" @click="submitDialog">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listExternalGameOpsReports, resolveExternalGameReport, takedownExternalGame, restoreExternalGame } from '@/api/business/externalGameOps'
import { unwrap, reviewErrorText, timeText } from '@/utils/review-queue'

const REASON_CODES = [
  { value: 'INAPPROPRIATE_CONTENT', label: '不当内容' },
  { value: 'MALICIOUS_CODE', label: '恶意代码' },
  { value: 'PAYMENT_OR_SCAM', label: '诱导付费或诈骗' },
  { value: 'COPYRIGHT', label: '侵权' },
  { value: 'BROKEN', label: '无法运行' },
  { value: 'OTHER', label: '其他' }
]

export default {
  name: 'BusinessExternalGameReports',
  data() {
    return {
      loading: false,
      rows: [],
      total: 0,
      listError: '',
      query: { gameId: '', status: 'OPEN', pageNum: 1, pageSize: 20 },
      reasonCodes: REASON_CODES,
      dialog: { open: false, kind: '', resolution: '', gameId: '', reportId: null, reasonCode: 'OTHER', text: '', resolveOpenReports: true, busy: false, error: '' }
    }
  },
  computed: {
    dialogTitle() {
      if (this.dialog.kind === 'takedown') return '下架外部游戏'
      if (this.dialog.kind === 'restore') return '恢复上架'
      return this.dialog.resolution === 'DISMISSED' ? '驳回举报' : '标记举报已处理'
    },
    textPlaceholder() {
      if (this.dialog.kind === 'resolve') return this.dialog.resolution === 'DISMISSED' ? '必填：为什么不处理' : '可选：做了什么'
      return '必填'
    },
    canSubmit() {
      const needsText = this.dialog.kind !== 'resolve' || this.dialog.resolution === 'DISMISSED'
      return !this.dialog.busy && (!needsText || this.dialog.text.trim().length > 0)
    }
  },
  created() {
    if (this.$route.query.gameId) this.query.gameId = String(this.$route.query.gameId)
    this.load()
  },
  methods: {
    timeText,
    search() { this.query.pageNum = 1; this.load() },
    filterGame(gameId) { this.query.gameId = gameId; this.search() },
    load() {
      const params = { ...this.query }
      if (!params.gameId) delete params.gameId
      if (!params.status) delete params.status
      this.loading = true
      this.listError = ''
      listExternalGameOpsReports(params).then(unwrap).then(page => {
        this.rows = page.rows || []
        this.total = page.total || 0
      }).catch(err => {
        this.rows = []
        this.listError = reviewErrorText(err)
      }).finally(() => { this.loading = false })
    },
    statusText(row) {
      if (row.status === 'OPEN') return '待处理'
      if (row.resolution === 'DISMISSED') return '已驳回'
      return '已处理'
    },
    openDialog(kind, row, resolution) {
      this.dialog = { open: true, kind, resolution: resolution || '', gameId: row.gameId, reportId: kind === 'resolve' ? row.reportId : null,
        reasonCode: kind === 'takedown' && row.reasonCode ? row.reasonCode : 'OTHER', text: '', resolveOpenReports: true, busy: false, error: '' }
    },
    openResolve(row, resolution) { this.openDialog('resolve', row, resolution) },
    openTakedown(row) { this.openDialog('takedown', row) },
    openRestore(row) { this.openDialog('restore', row) },
    submitDialog() {
      const d = this.dialog
      const text = d.text.trim()
      let call
      if (d.kind === 'resolve') call = resolveExternalGameReport(d.reportId, { resolution: d.resolution, note: text || undefined })
      else if (d.kind === 'takedown') call = takedownExternalGame(d.gameId, { reasonCode: d.reasonCode, reason: text, resolveOpenReports: d.resolveOpenReports })
      else call = restoreExternalGame(d.gameId, { reason: text })
      d.busy = true
      d.error = ''
      call.then(unwrap).then(() => {
        d.open = false
        this.$modal.msgSuccess('已保存')
        this.load()
      }).catch(err => {
        d.error = reviewErrorText(err)
      }).finally(() => { d.busy = false })
    },
    openSubmission(row) {
      this.$router.push({ path: '/audit-center/external-game', query: { submissionId: row.submissionId } }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.eg-error { color: #f56c6c; }
.eg-sub { color: #909399; font-size: 12px; }
</style>

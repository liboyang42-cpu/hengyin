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
      <el-table-column label="状态" width="90">
        <template slot-scope="s">
          <el-tag size="small" :type="s.row.status === 'OPEN' ? 'warning' : 'success'">{{ s.row.status === 'OPEN' ? '待处理' : '已处理' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="140">
        <template slot-scope="s">{{ timeText(s.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template slot-scope="s">
          <el-button v-if="s.row.submissionId" type="text" @click="openSubmission(s.row)">看提交</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />
  </div>
</template>

<script>
import { listExternalGameReports } from '@/api/business/reviewQueues'
import { unwrap, reviewErrorText, timeText } from '@/utils/review-queue'

export default {
  name: 'BusinessExternalGameReports',
  data() {
    return {
      loading: false,
      rows: [],
      total: 0,
      listError: '',
      query: { gameId: '', status: 'OPEN', pageNum: 1, pageSize: 20 }
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
      listExternalGameReports(params).then(unwrap).then(page => {
        this.rows = page.rows || []
        this.total = page.total || 0
      }).catch(err => {
        this.rows = []
        this.listError = reviewErrorText(err)
      }).finally(() => { this.loading = false })
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

<template>
  <div class="app-container">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="游戏标识">
        <el-input v-model="query.gameId" clearable style="width: 200px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item label="主题 ID">
        <el-input v-model="query.topicId" clearable style="width: 120px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
        <el-button v-hasPermi="['business:externalgame:bind']" icon="el-icon-plus" @click="openBind()">绑定节点</el-button>
      </el-form-item>
    </el-form>

    <p v-if="listError" class="eg-error">{{ listError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" empty-text="还没有节点绑定外部游戏">
      <el-table-column label="节点" min-width="160">
        <template slot-scope="s">#{{ s.row.nodeId }} {{ s.row.nodeName || '' }}<div class="eg-sub">主题 #{{ s.row.topicId }}</div></template>
      </el-table-column>
      <el-table-column label="游戏" min-width="180">
        <template slot-scope="s">
          {{ s.row.gameName }} <span class="eg-sub">{{ s.row.gameId }}<span v-if="s.row.version"> v{{ s.row.version }}</span></span>
          <el-tag v-if="!s.row.listed" size="mini" type="danger">未上架，玩家打不开</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="通过条件" width="160">
        <template slot-scope="s">{{ s.row.requirePass ? '需通关' : '不要求通关' }}<span v-if="s.row.minScore > 0">，≥{{ s.row.minScore }} 分</span></template>
      </el-table-column>
      <el-table-column label="创作者" width="100">
        <template slot-scope="s">{{ s.row.ownerMemberId || '—' }}</template>
      </el-table-column>
      <el-table-column label="更新" width="140">
        <template slot-scope="s">{{ timeText(s.row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template slot-scope="s">
          <el-button v-hasPermi="['business:externalgame:bind']" type="text" @click="openBind(s.row)">修改</el-button>
          <el-button v-hasPermi="['business:externalgame:bind']" type="text" @click="openUnbind(s.row)">解除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />

    <el-dialog :title="form.kind === 'unbind' ? '解除节点绑定' : '绑定外部游戏到节点'" :visible.sync="form.open" width="500px" append-to-body>
      <el-form label-width="90px" size="small" @submit.native.prevent>
        <el-form-item label="节点 ID">
          <el-input v-model="form.nodeId" :disabled="form.kind === 'unbind' || form.editing" placeholder="cms_topic_node.id" />
        </el-form-item>
        <template v-if="form.kind === 'bind'">
          <el-form-item label="游戏标识">
            <el-input v-model="form.gameId" placeholder="已上架的游戏，例如 maze.one" />
          </el-form-item>
          <el-form-item label="需要通关">
            <el-switch v-model="form.requirePass" />
          </el-form-item>
          <el-form-item label="最低分">
            <el-input-number v-model="form.minScore" :min="0" :max="1000000" />
          </el-form-item>
        </template>
        <el-form-item label="说明">
          <el-input v-model="form.reason" type="textarea" :rows="2" :maxlength="500" show-word-limit placeholder="可选，写进操作留痕" />
        </el-form-item>
      </el-form>
      <p class="eg-sub">节点完成只认游戏服务端签名的结果；未签名的结果只记录，不推进节点。</p>
      <p v-if="form.error" class="eg-error">{{ form.error }}</p>
      <div slot="footer">
        <el-button @click="form.open = false">取消</el-button>
        <el-button type="primary" :loading="form.busy" :disabled="!canSubmit" @click="submit">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listExternalGameBindings, bindExternalGameNode, unbindExternalGameNode } from '@/api/business/externalGameOps'
import { unwrap, reviewErrorText, timeText } from '@/utils/review-queue'

export default {
  name: 'BusinessExternalGameBindings',
  data() {
    return {
      loading: false,
      rows: [],
      total: 0,
      listError: '',
      query: { gameId: '', topicId: '', pageNum: 1, pageSize: 20 },
      form: { open: false, kind: 'bind', editing: false, nodeId: '', gameId: '', requirePass: true, minScore: 0, reason: '', busy: false, error: '' }
    }
  },
  computed: {
    canSubmit() {
      if (this.form.busy || !/^[1-9][0-9]{0,18}$/.test(String(this.form.nodeId).trim())) return false
      return this.form.kind === 'unbind' || /^[a-z0-9][a-z0-9._-]{2,63}$/.test(this.form.gameId.trim())
    }
  },
  created() {
    if (this.$route.query.gameId) this.query.gameId = String(this.$route.query.gameId)
    this.load()
  },
  methods: {
    timeText,
    search() { this.query.pageNum = 1; this.load() },
    load() {
      const params = { ...this.query }
      if (!params.gameId) delete params.gameId
      if (!params.topicId) delete params.topicId
      this.loading = true
      this.listError = ''
      listExternalGameBindings(params).then(unwrap).then(page => {
        this.rows = page.rows || []
        this.total = page.total || 0
      }).catch(err => {
        this.rows = []
        this.listError = reviewErrorText(err)
      }).finally(() => { this.loading = false })
    },
    openBind(row) {
      this.form = { open: true, kind: 'bind', editing: !!row, nodeId: row ? String(row.nodeId) : '', gameId: row ? row.gameId : '',
        requirePass: row ? row.requirePass : true, minScore: row ? row.minScore : 0, reason: '', busy: false, error: '' }
    },
    openUnbind(row) {
      this.form = { open: true, kind: 'unbind', editing: true, nodeId: String(row.nodeId), gameId: row.gameId,
        requirePass: true, minScore: 0, reason: '', busy: false, error: '' }
    },
    submit() {
      const f = this.form
      const nodeId = String(f.nodeId).trim()
      const reason = f.reason.trim() || undefined
      const call = f.kind === 'unbind'
        ? unbindExternalGameNode(nodeId, { reason })
        : bindExternalGameNode(nodeId, { gameId: f.gameId.trim(), requirePass: f.requirePass, minScore: f.minScore, reason })
      f.busy = true
      f.error = ''
      call.then(unwrap).then(() => {
        f.open = false
        this.$modal.msgSuccess('已保存')
        this.load()
      }).catch(err => {
        f.error = reviewErrorText(err)
      }).finally(() => { f.busy = false })
    }
  }
}
</script>

<style scoped>
.eg-error { color: #f56c6c; }
.eg-sub { color: #909399; font-size: 12px; }
</style>

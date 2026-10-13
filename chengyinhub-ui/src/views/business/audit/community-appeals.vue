<template>
  <section class="cy-panel" v-hasPermi="['business:audit:list']">
    <cy-page-header title="帖文申诉复核" description="本人申诉与原处置分别读取；复核不会改写历史说明。">
      <el-button :loading="loading" @click="load()">重新读取</el-button>
    </cy-page-header>
    <el-alert v-if="errorText" :title="errorText" type="warning" :closable="false" show-icon />
    <el-table :data="rows" v-loading="loading" empty-text="暂无申诉记录">
      <el-table-column type="expand"><template slot-scope="scope">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="原处置类型">{{ scope.row.enforcement_type || '待确认' }}</el-descriptions-item>
          <el-descriptions-item label="原处置理由">{{ scope.row.reason_code || '待确认' }}</el-descriptions-item>
          <el-descriptions-item label="规则及版本">{{ scope.row.rule_code || '待确认' }} · {{ scope.row.guideline_version_id || '待确认' }}</el-descriptions-item>
          <el-descriptions-item label="适用规范">{{ scope.row.guideline_content_json || '未提供' }}</el-descriptions-item>
          <el-descriptions-item label="冻结证据">{{ scope.row.evidence_snapshot_json || '未提供' }}</el-descriptions-item>
        </el-descriptions>
      </template></el-table-column>
      <el-table-column label="申诉编号" prop="id" />
      <el-table-column label="原处置编号" prop="enforcement_id" />
      <el-table-column label="申诉说明" prop="reason" min-width="180" />
      <el-table-column label="当前结果"><template slot-scope="scope">{{ statusLabel(scope.row.status) }}</template></el-table-column>
      <el-table-column label="处理说明" prop="decision_reason" min-width="180" />
      <el-table-column label="操作"><template slot-scope="scope">
        <el-button v-hasPermi="['business:audit:review']" type="text" :disabled="!!pending || !canReview(scope.row)" @click="openReview(scope.row)">复核原案</el-button>
      </template></el-table-column>
    </el-table>
    <el-button v-if="nextCursor" :disabled="loading" @click="load(nextCursor)">读取更早申诉</el-button>
    <el-button v-if="pending && !sending" :loading="loading" @click="readOriginal">查询原处理结果</el-button>
    <el-dialog title="申诉复核" :visible.sync="open" width="520px" append-to-body :close-on-click-modal="false">
      <el-alert v-if="pending" title="处理已提交或结果未知，请查询原案，暂不要重复处理。" type="warning" :closable="false" />
      <el-form label-position="top" :disabled="!!pending">
        <el-form-item label="处理结果"><el-radio-group v-model="decision"><el-radio label="UPHELD">维持原处置</el-radio><el-radio label="REVERSED">撤销原处置</el-radio></el-radio-group></el-form-item>
        <el-form-item label="公开处理说明"><el-input v-model="reason" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="open = false">关闭</el-button><el-button v-if="pending" :disabled="sending" @click="readOriginal">查询原结果</el-button><el-button v-hasPermi="['business:audit:review']" type="primary" :disabled="!!pending" :loading="sending" @click="submit">提交复核</el-button></div>
    </el-dialog>
  </section>
</template>
<script>
import CyPageHeader from '@/components/CyPageHeader'
import { listCommunityAppeals, reviewCommunityAppeal } from '@/api/business/communityAppeals'
import auth from '@/plugins/auth'
import cache from '@/plugins/cache'
export default {
  components: { CyPageHeader },
  data() {
    return { rows: [], nextCursor: null, loading: false, errorText: '', open: false, selected: null, decision: 'UPHELD', reason: '', pending: null, sending: false, sequence: 0 }
  },
  created() { this.restorePending(); this.load() },
  methods: {
    pendingKey() {
      const id = this.$store && this.$store.state.user.id
      return id ? 'community-appeal-review-pending:' + id : ''
    },
    persistPending(pending) {
      const key = this.pendingKey()
      if (!key) return false
      try { if (pending) cache.local.setJSON(key, pending); else cache.local.remove(key); return true } catch (_) { return false }
    },
    restorePending() {
      const key = this.pendingKey()
      if (!key) return
      try {
        const pending = cache.local.getJSON(key)
        if (pending && /^[1-9][0-9]*$/.test(pending.id) && Number.isInteger(pending.version)) this.pending = pending
      } catch (_) { this.errorText = '申诉处理恢复记录暂不可读，请稍后查询。' }
    },
    statusLabel(value) { const labels = { SUBMITTED: '申诉已提交', REVIEWING: '复核处理中', UPHELD: '维持原处置', REVERSED: '原处置已撤销' }; return Object.prototype.hasOwnProperty.call(labels, value) ? labels[value] : '状态待确认' },
    canReview(row) { return row && ['SUBMITTED', 'REVIEWING'].includes(row.status) && Number.isInteger(row.version) && row.version >= 0 },
    load(cursor) {
      const sequence = ++this.sequence
      this.loading = true; this.errorText = ''
      return listCommunityAppeals({ cursor: cursor || undefined, limit: 20 }).then(res => {
        if (sequence !== this.sequence) return
        if (!res.data || !Array.isArray(res.data.items) || res.data.items.some(row => !row || !/^[1-9][0-9]*$/.test(String(row.id)))) throw Error('invalid')
        this.rows = res.data.items; this.nextCursor = res.data.nextCursor || null
      }).catch(() => { if (sequence === this.sequence) { this.rows = []; this.errorText = '申诉暂未读到，请重新查询。' } }).finally(() => { if (sequence === this.sequence) this.loading = false })
    },
    openReview(row) {
      if (!auth.hasPermi('business:audit:review') || this.pending || !this.canReview(row)) return
      this.selected = row; this.decision = 'UPHELD'; this.reason = ''; this.open = true
    },
    submit() {
      if (!auth.hasPermi('business:audit:review') || this.pending || !this.canReview(this.selected) || !this.reason.trim()) return
      const pending = { id: String(this.selected.id), version: this.selected.version }
      if (!this.persistPending(pending)) { this.errorText = '无法保存恢复记录，复核尚未提交。'; return }
      this.pending = pending
      this.sending = true
      return reviewCommunityAppeal(this.selected.id, { decision: this.decision, reason: this.reason.trim(), expectedVersion: this.selected.version })
        .then(() => { this.errorText = '处理提交已返回，正在读取原案结果。' })
        .catch(() => { this.errorText = '处理结果待确认，请查询原案，暂不要重复处理。' })
        .finally(() => { this.sending = false; return this.readOriginal() })
    },
    async readOriginal() {
      if (!this.pending || this.sending || this.loading) return
      const sequence = ++this.sequence
      const pending = this.pending
      this.loading = true
      try {
        let cursor
        const visited = new Set()
        while (true) {
          const res = await listCommunityAppeals({ cursor, limit: 20 })
          if (sequence !== this.sequence) return
          if (!res.data || !Array.isArray(res.data.items) || res.data.items.some(row => !row || !/^[1-9][0-9]*$/.test(String(row.id)))) throw Error('invalid')
          const row = res.data.items.find(item => String(item.id) === pending.id)
          if (row) {
            this.rows = res.data.items; this.nextCursor = res.data.nextCursor || null
            if (['UPHELD', 'REVERSED'].includes(row.status) && row.version > pending.version) {
              this.persistPending(null); this.pending = null; this.open = false; this.errorText = '已读取原案处理结果：' + this.statusLabel(row.status)
            } else this.errorText = '原案尚无终态处理结果，请稍后查询。'
            return
          }
          cursor = res.data.nextCursor
          if (!cursor || visited.has(String(cursor))) break
          visited.add(String(cursor))
        }
        this.errorText = '尚未找到原案结果，待确认状态保留。'
      } catch (_) { if (sequence === this.sequence) this.errorText = '原案结果暂未读到，待确认状态保留。' }
      finally { if (sequence === this.sequence) this.loading = false }
    }
  }
}
</script>

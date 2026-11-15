<template>
  <el-dialog :title="kind === 'routes' ? '批量导入官方路线' : '批量导入官方地点'" :visible="visible" width="960px" :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy" @close="$emit('close')">
    <el-alert :title="intro" type="info" :closable="false" />
    <el-form size="small" :inline="true" class="seed-import-form" @submit.native.prevent>
      <el-form-item label="表格文件">
        <input ref="file" type="file" accept=".csv,.xlsx" aria-label="选择 CSV 或 XLSX 文件" @change="pick">
      </el-form-item>
      <template v-if="kind === 'places'">
        <el-form-item label="城市码"><el-input v-model="cityCode" placeholder="行内未填时使用，如 330100" maxlength="20" clearable /></el-form-item>
        <el-form-item label="同名去重距离"><el-input-number v-model="dedupeMeters" :min="10" :max="1000" :step="10" /> 米</el-form-item>
      </template>
      <el-form-item>
        <el-button @click="downloadTemplate">下载模板</el-button>
        <el-button type="primary" :loading="busy && !committing" :disabled="!file || busy" @click="preview">预检</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <div class="seed-import-summary">
        <span>共 {{ report.total }} 行</span>
        <span>可导入 {{ report.newCount }}</span>
        <span>重复跳过 {{ report.duplicateCount }}</span>
        <span :class="{ 'seed-import-summary__error': report.errorCount }">有错误 {{ report.errorCount }}</span>
        <strong v-if="report.committed">已导入 {{ report.newCount }} 条，均为待配置的草稿</strong>
      </div>
      <el-table :data="report.rows" size="small" border max-height="420">
        <el-table-column label="行" prop="rowNumber" width="60" align="center" />
        <el-table-column :label="kind === 'routes' ? '路线' : '地点'" min-width="180">
          <template slot-scope="s">
            <div>{{ (kind === 'routes' ? s.row.values.title : s.row.values.name) || '（未填）' }}</div>
            <div class="seed-import-meta">{{ rowMeta(s.row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="120" align="center">
          <template slot-scope="s">
            <span>{{ outcomeText(s.row) }}</span>
            <div v-if="s.row.createdId" class="seed-import-meta">#{{ s.row.createdId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="320">
          <template slot-scope="s">
            <div v-for="(message, index) in s.row.errors" :key="'e' + index" class="seed-import-error">{{ message }}</div>
            <div v-if="s.row.outcome === 'DUPLICATE'">与 {{ s.row.duplicateOfName || '已有内容' }}<span v-if="s.row.duplicateOfId"> (#{{ s.row.duplicateOfId }})</span> 重复，跳过</div>
            <div v-for="(message, index) in s.row.warnings" :key="'w' + index" class="seed-import-meta">{{ message }}</div>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <span slot="footer">
      <el-button :disabled="busy" @click="$emit('close')">{{ report && report.committed ? '完成' : '取消' }}</el-button>
      <el-button v-hasPermi="['business:roamPoi:edit']" type="primary" :loading="committing" :disabled="!canCommit" @click="commit">确认导入 {{ report && !report.committed ? report.newCount : 0 }} 条</el-button>
    </span>
  </el-dialog>
</template>

<script>
import { importOfficialPlaces, importOfficialRoutes } from '@/api/business/seedContent'

export default {
  props: { visible: Boolean, kind: { type: String, default: 'places' } },
  data: () => ({ file: null, cityCode: '', dedupeMeters: 100, report: null, previewedFile: null, busy: false, committing: false, error: '' }),
  computed: {
    intro() {
      return this.kind === 'routes'
        ? '每行一条路线：标题、难度（轻松/标准/硬核）和按顺序排列的官方地点编号。先预检，确认后一次性写入；路线只建为草稿，需在主题编辑里完善后提交审核。'
        : '每行一个地点，坐标用高德/腾讯（GCJ-02）经纬度。先预检，确认后一次性写入；导入的地点不上地图，配置地点游戏并审核通过后才对玩家可见。'
    },
    canCommit() {
      return !!(this.report && !this.report.committed && this.report.errorCount === 0 && this.report.newCount > 0 && this.file === this.previewedFile && !this.busy)
    }
  },
  watch: { visible(value) { if (value) this.reset() } },
  methods: {
    reset() {
      this.file = null; this.report = null; this.previewedFile = null; this.error = ''; this.busy = false; this.committing = false
      if (this.$refs.file) this.$refs.file.value = ''
    },
    pick(event) {
      this.file = (event.target.files && event.target.files[0]) || null
      this.report = null; this.previewedFile = null; this.error = ''
    },
    params(dryRun) {
      return this.kind === 'routes' ? { dryRun } : { dryRun, cityCode: this.cityCode.trim(), dedupeMeters: this.dedupeMeters }
    },
    send(dryRun) {
      return (this.kind === 'routes' ? importOfficialRoutes : importOfficialPlaces)(this.file, this.params(dryRun))
    },
    async preview() {
      if (!this.file || this.busy) return
      this.busy = true; this.error = ''; this.report = null
      const file = this.file
      try {
        const response = await this.send(true)
        if (file !== this.file) return
        this.report = response.data; this.previewedFile = file
      } catch (error) { this.error = error.message || '预检失败，请检查文件后重试' }
      finally { this.busy = false }
    },
    async commit() {
      if (!this.canCommit) return
      this.busy = true; this.committing = true; this.error = ''
      try {
        const response = await this.send(false)
        this.report = response.data
        this.$modal.msgSuccess('已导入 ' + this.report.newCount + ' 条草稿')
        this.$emit('imported')
      } catch (error) { this.error = (error.message || '导入失败') + '；没有写入任何数据' }
      finally { this.busy = false; this.committing = false }
    },
    downloadTemplate() {
      const routes = this.kind === 'routes'
      this.download(routes ? 'business/seed/routes/template' : 'business/seed/places/template', {}, routes ? '官方路线导入模板.csv' : '官方地点导入模板.csv')
    },
    outcomeText(row) {
      if (row.outcome === 'ERROR') return '有错误'
      if (row.outcome === 'DUPLICATE') return '重复跳过'
      return row.createdId ? '已导入' : '可导入'
    },
    rowMeta(row) {
      const v = row.values || {}
      if (this.kind === 'routes') return [v.difficulty, v.place_ids && ('地点 ' + v.place_ids)].filter(Boolean).join(' · ')
      return [v.category, v.address, v.lat && v.lng ? v.lat + ', ' + v.lng : ''].filter(Boolean).join(' · ')
    }
  }
}
</script>

<style scoped>
.seed-import-form { margin-top: 16px; }
.seed-import-summary { display: flex; flex-wrap: wrap; gap: 16px; margin: 8px 0 12px; color: var(--cy-color-text-secondary); font-size: 13px; }
.seed-import-summary strong { color: var(--cy-color-text-primary); }
.seed-import-summary__error, .seed-import-error { color: var(--cy-color-status-danger); }
.seed-import-meta { color: var(--cy-color-text-tertiary); font-size: 12px; }
</style>

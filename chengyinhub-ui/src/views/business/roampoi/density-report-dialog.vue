<template>
  <el-dialog title="片区内容密度" :visible="visible" width="860px" :close-on-click-modal="false" @close="$emit('close')">
    <el-alert title="按首个开城片区的目标核对：约 200 个地点、5 条路线、20–30 个棋盘地点、30 家各带一个游戏的商家、3 个每日任务、1 场开城活动。" type="info" :closable="false" />
    <el-form size="small" label-width="96px" class="density-form" @submit.native.prevent>
      <el-form-item label="片区">
        <el-radio-group v-model="mode">
          <el-radio-button label="board">CITY 棋盘片区</el-radio-button>
          <el-radio-button label="box">矩形范围</el-radio-button>
          <el-radio-button label="polygon">多边形</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="mode === 'board'" label="棋盘">
        <el-select v-model="boardId" placeholder="选择未关闭的棋盘" filterable style="width: 320px">
          <el-option v-for="area in areas" :key="area.boardId" :label="area.title + '（' + area.boardId + '）'" :value="area.boardId" />
        </el-select>
        <span v-if="!areasLoading && !areas.length" class="density-meta">暂无可选棋盘，可改用矩形或多边形</span>
      </el-form-item>
      <el-form-item v-if="mode === 'box'" label="边界">
        <el-input v-model="box.south" placeholder="南纬度" class="density-edge" />
        <el-input v-model="box.west" placeholder="西经度" class="density-edge" />
        <el-input v-model="box.north" placeholder="北纬度" class="density-edge" />
        <el-input v-model="box.east" placeholder="东经度" class="density-edge" />
      </el-form-item>
      <el-form-item v-if="mode === 'polygon'" label="顶点">
        <el-input v-model="polygonText" type="textarea" :rows="3" placeholder="每行一个顶点：纬度,经度（GCJ-02，可从地图选点工具复制）" />
      </el-form-item>
      <el-form-item label="活动城市"><el-input v-model="cityKeyword" placeholder="如 杭州；留空则统计全国活动" maxlength="32" style="width: 200px" /></el-form-item>
      <el-form-item><el-button type="primary" :loading="loading" @click="run">生成报告</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <p class="density-meta">{{ report.area.source }} · 南 {{ report.area.south }} 西 {{ report.area.west }} 北 {{ report.area.north }} 东 {{ report.area.east }}</p>
      <el-table :data="report.metrics" size="small" border>
        <el-table-column label="内容" prop="label" min-width="140" />
        <el-table-column label="现有" width="90" align="center">
          <template slot-scope="s">{{ s.row.count === null || s.row.count === undefined ? '读不到' : s.row.count }}</template>
        </el-table-column>
        <el-table-column label="目标" width="90" align="center">
          <template slot-scope="s">{{ targetText(s.row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template slot-scope="s"><span :class="'density-state density-state--' + String(s.row.state).toLowerCase()">{{ stateText(s.row) }}</span></template>
        </el-table-column>
        <el-table-column label="说明" min-width="260">
          <template slot-scope="s">{{ s.row.detail }}<span v-if="s.row.scope === 'CITY'" class="density-meta">（全城口径）</span></template>
        </el-table-column>
      </el-table>
    </template>
  </el-dialog>
</template>

<script>
import { densityAreas, densityReport } from '@/api/business/seedContent'

function parsePolygon(text) {
  return String(text || '').split(/\n|;/).map(line => line.trim()).filter(Boolean).map(line => {
    const parts = line.split(/[,，\s]+/).filter(Boolean)
    if (parts.length !== 2 || parts.some(part => !/^-?\d+(\.\d+)?$/.test(part))) throw new Error('顶点「' + line + '」格式应为 纬度,经度')
    return [Number(parts[0]), Number(parts[1])]
  })
}

export default {
  props: { visible: Boolean },
  data: () => ({ mode: 'board', boardId: '', areas: [], areasLoading: false, box: { south: '', west: '', north: '', east: '' }, polygonText: '', cityKeyword: '', report: null, loading: false, error: '' }),
  watch: { visible: { immediate: true, handler(value) { if (value) this.loadAreas() } } },
  methods: {
    async loadAreas() {
      this.areasLoading = true
      try {
        const response = await densityAreas()
        this.areas = response.data || []
        if (!this.areas.length && this.mode === 'board') this.mode = 'box'
      } catch (_) { this.areas = [] } finally { this.areasLoading = false }
    },
    query() {
      const query = { cityKeyword: this.cityKeyword.trim() || null }
      if (this.mode === 'board') {
        if (!this.boardId) throw new Error('请选择棋盘片区')
        query.boardId = this.boardId
      } else if (this.mode === 'polygon') {
        query.polygon = parsePolygon(this.polygonText)
      } else {
        Object.assign(query, this.box)
      }
      return query
    },
    async run() {
      if (this.loading) return
      this.error = ''
      let query
      try { query = this.query() } catch (error) { this.error = error.message; return }
      this.loading = true
      try { this.report = (await densityReport(query)).data } catch (error) { this.report = null; this.error = error.message || '报告生成失败，请重试' } finally { this.loading = false }
    },
    targetText(row) {
      if (row.target === null || row.target === undefined) return '—'
      return row.targetMax ? row.target + '–' + row.targetMax : String(row.target)
    },
    stateText(row) {
      return { MET: '达标', BELOW: '还差 ' + (row.target - (row.count || 0)), ABOVE: '超出上限', UNAVAILABLE: '读不到', INFO: '参考' }[row.state] || '—'
    }
  }
}
</script>

<style scoped>
.density-form { margin-top: 16px; }
.density-edge { width: 120px; margin-right: 8px; }
.density-meta { color: var(--cy-color-text-tertiary); font-size: 12px; margin-left: 8px; }
.density-state--below, .density-state--unavailable { color: var(--cy-color-status-danger); }
.density-state--met { color: var(--cy-color-text-primary); font-weight: 700; }
</style>

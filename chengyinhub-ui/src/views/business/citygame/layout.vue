<template>
  <div class="cy-board-layout">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="地点等级决定占领价格和租金（T1 街角 60 / T2 小店 120 / T3 名店 240 / T4 地标 400 积分）。已被占领的地点保持买入时的等级，直到释放或赛季结束；街区调整会立即影响同街区加成。"
    />

    <div class="cy-layout-row">
      <el-select v-model="boardId" size="small" filterable placeholder="选择棋盘" aria-label="棋盘" style="width:260px" @change="load">
        <el-option v-for="b in boards" :key="b.boardId" :label="(b.title || b.boardId) + ' · ' + b.boardId" :value="b.boardId" />
      </el-select>
      <el-button size="small" :disabled="!boardId" @click="load">刷新</el-button>
    </div>

    <template v-if="layout">
      <el-alert
        v-if="issueTexts.length"
        type="warning"
        :closable="false"
        class="cy-layout-row"
        :title="'还不在批准范围内：' + issueTexts.join('；')"
      />
      <el-alert v-else type="success" :closable="false" class="cy-layout-row" title="等级与街区都在批准范围内" />

      <el-row :gutter="16" class="cy-layout-row">
        <el-col :span="9">
          <h4>街区（{{ layout.ranges.districtsMin }}–{{ layout.ranges.districtsMax }} 个，每个 {{ layout.ranges.placesPerDistrictMin }}–{{ layout.ranges.placesPerDistrictMax }} 个地点）</h4>
          <el-table :data="layout.districts" size="mini" empty-text="还没有街区">
            <el-table-column prop="districtId" label="编号" width="80" />
            <el-table-column label="名称" min-width="110">
              <template slot-scope="s">
                <el-input v-model="s.row.name" size="mini" aria-label="街区名称" @change="saveDistrict(s.row)" />
              </template>
            </el-table-column>
            <el-table-column label="颜色" width="110">
              <template slot-scope="s">
                <el-select v-model="s.row.colorToken" size="mini" aria-label="街区颜色" @change="saveDistrict(s.row)">
                  <el-option v-for="c in colors" :key="c" :label="c" :value="c" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column prop="ownableCount" label="可占领" width="70" />
          </el-table>
          <div class="cy-layout-row">
            <el-input v-model="draft.districtId" size="mini" placeholder="编号，如 d2" aria-label="新街区编号" style="width:100px" />
            <el-input v-model="draft.name" size="mini" placeholder="名称" aria-label="新街区名称" style="width:120px" />
            <el-button size="mini" type="primary" v-hasPermi="['business:citygame:edit']" :disabled="!draft.districtId || !draft.name" @click="addDistrict">新增街区</el-button>
          </div>
        </el-col>
        <el-col :span="15">
          <h4>地点</h4>
          <el-table :data="layout.points" size="mini" max-height="480" empty-text="棋盘没有地点">
            <el-table-column prop="title" label="地点" min-width="140" show-overflow-tooltip />
            <el-table-column label="类型" width="70">
              <template slot-scope="s">{{ s.row.role === 'NEUTRAL' ? '中立' : '可占领' }}</template>
            </el-table-column>
            <el-table-column label="等级" width="130">
              <template slot-scope="s">
                <el-select v-model="s.row.tier" size="mini" aria-label="地点等级" :disabled="!canEdit" @change="saveTier(s.row)">
                  <el-option v-for="t in tiers" :key="t.value" :label="t.label" :value="t.value" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="街区" width="150">
              <template slot-scope="s">
                <el-select v-model="s.row.districtId" size="mini" clearable placeholder="未分区" aria-label="所属街区" :disabled="!canEdit" @change="saveDistrictOf(s.row)">
                  <el-option v-for="d in layout.districts" :key="d.districtId" :label="d.name + '（' + d.districtId + '）'" :value="d.districtId" />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script>
import { getBoardLayout, updatePointTier, upsertDistrict, assignPointDistrict } from '@/api/business/citygame'
import { checkPermi } from '@/utils/permission'

const ISSUE_TEXT = {
  DISTRICTS_TOO_FEW: '街区太少',
  DISTRICTS_TOO_MANY: '街区太多',
  NEUTRAL_SHARE_OUT_OF_RANGE: '中立地点占比不在 10%–20%',
  PLACES_WITHOUT_DISTRICT: '有可占领地点还没有街区'
}

export default {
  name: 'CityBoardLayout',
  props: {
    boards: { type: Array, default: () => [] }
  },
  data() {
    return {
      boardId: '',
      layout: null,
      draft: { districtId: '', name: '' },
      tiers: [
        { value: 1, label: 'T1 街角' },
        { value: 2, label: 'T2 小店' },
        { value: 3, label: 'T3 名店' },
        { value: 4, label: 'T4 地标' }
      ],
      colors: Array.from({ length: 10 }, (_, i) => 'district.c' + (i + 1))
    }
  },
  computed: {
    canEdit() {
      return checkPermi(['business:citygame:edit'])
    },
    issueTexts() {
      const issues = (this.layout && this.layout.issues) || []
      return issues.map(code => {
        const [key, id] = String(code).split(':')
        if (key === 'DISTRICT_TOO_SMALL') return '街区 ' + id + ' 地点太少'
        if (key === 'DISTRICT_TOO_LARGE') return '街区 ' + id + ' 地点太多'
        return ISSUE_TEXT[key] || key
      })
    }
  },
  watch: {
    boards: {
      immediate: true,
      handler(list) {
        if (!this.boardId && list && list.length) {
          this.boardId = list[0].boardId
          this.load()
        }
      }
    }
  },
  methods: {
    load() {
      if (!this.boardId) return
      getBoardLayout(this.boardId).then(res => { this.layout = res.data || null }).catch(() => { this.layout = null })
    },
    applied(res) {
      this.layout = res.data || this.layout
      this.$modal.msgSuccess('已保存')
    },
    saveTier(row) {
      updatePointTier(this.boardId, row.pointId, row.tier).then(this.applied).catch(() => this.load())
    },
    saveDistrictOf(row) {
      assignPointDistrict(this.boardId, row.pointId, row.districtId).then(this.applied).catch(() => this.load())
    },
    saveDistrict(row) {
      upsertDistrict(this.boardId, row.districtId, { name: row.name, colorToken: row.colorToken }).then(this.applied).catch(() => this.load())
    },
    addDistrict() {
      upsertDistrict(this.boardId, this.draft.districtId.trim(), { name: this.draft.name.trim(), colorToken: 'district.c1' })
        .then(res => {
          this.draft = { districtId: '', name: '' }
          this.applied(res)
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.cy-layout-row { margin-top: 12px; }
</style>

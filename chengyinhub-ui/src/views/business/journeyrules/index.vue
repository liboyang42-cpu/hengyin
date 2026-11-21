<template>
  <div class="app-container">
    <cy-page-header eyebrow="供给与发布" title="旅程规则" description="主题的数值规则（体力、幸运、难度）与状态键。校验与创作者端相同，保存前先预览。" />

    <el-form size="small" :inline="true">
      <el-form-item label="主题">
        <el-input v-model="topicQuery.keyword" clearable placeholder="主题名称" style="width: 180px" />
      </el-form-item>
      <el-form-item label="主题编号">
        <el-input v-model="topicQuery.topicId" clearable placeholder="主题编号" style="width: 120px" />
      </el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="searchTopics">查找</el-button></el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="topics" size="small" border highlight-current-row max-height="260" @current-change="pick">
      <el-table-column label="主题" min-width="200">
        <template slot-scope="s">{{ s.row.name }} #{{ s.row.id }}</template>
      </el-table-column>
      <el-table-column label="数值规则" width="100" align="center">
        <template slot-scope="s">{{ s.row.hasRules ? '已配置' : '未配置' }}</template>
      </el-table-column>
      <el-table-column label="状态键" prop="stateKeyCount" width="80" align="center" />
      <el-table-column label="更新时间" prop="updateTime" width="160" />
    </el-table>

    <template v-if="current">
      <el-divider content-position="left">{{ current.topic.name }} #{{ current.topic.id }} · {{ scopeText(current.editScope) }}</el-divider>
      <el-alert v-if="current.problem" type="error" :closable="false" :title="'当前保存的规则无法通过校验：' + current.problem" />
      <el-alert v-if="current.editScope !== 'FULL'" type="warning" :closable="false"
        title="主题已过审或在售：数值规则只能查看与预览，保存请让创作者在草稿里修改。" />

      <el-tabs v-model="tab">
        <el-tab-pane label="数值规则" name="rules">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form size="small" label-width="120px">
                <el-form-item label="启用检定"><el-switch v-model="doc.checkEnabled" /></el-form-item>
                <el-form-item label="启用状态"><el-switch v-model="doc.stateEnabled" /></el-form-item>
                <el-form-item label="难度档">
                  <el-select v-model="doc.difficulty" clearable placeholder="未选择">
                    <el-option label="轻松" value="easy" />
                    <el-option label="标准" value="standard" />
                    <el-option label="硬核" value="hard" />
                  </el-select>
                </el-form-item>
                <el-form-item label="自然 20/1 必定成败"><el-switch v-model="doc.natAuto" /></el-form-item>
                <el-form-item label="体力">
                  <el-switch v-model="doc.hp.enabled" />
                  上限 <el-input-number v-model="doc.hp.max" :min="1" :max="20" size="mini" />
                  初值 <el-input-number v-model="doc.hp.init" :min="1" :max="doc.hp.max" size="mini" />
                </el-form-item>
                <el-form-item label="幸运">
                  <el-switch v-model="doc.luck.enabled" />
                  上限 <el-input-number v-model="doc.luck.max" :min="0" :max="5" size="mini" />
                  初值 <el-input-number v-model="doc.luck.init" :min="0" :max="doc.luck.max" size="mini" />
                </el-form-item>
                <el-form-item v-for="t in tierNames" :key="t.key" :label="t.label">
                  难度值 <el-input-number v-model="doc.tiers[t.key].dc" :min="5" :max="25" size="mini" />
                  失败扣体力 <el-input-number v-model="doc.tiers[t.key].damage" :min="0" :max="5" size="mini" />
                </el-form-item>
                <el-form-item label="大失败额外扣"><el-input-number v-model="doc.fumbleExtraDamage" :min="0" :max="3" size="mini" /></el-form-item>
                <el-form-item label="加值上限"><el-input-number v-model="doc.modCap" :min="0" :max="10" size="mini" /></el-form-item>
                <el-form-item label="站点结束回体力"><el-input-number v-model="doc.recovery.stationEndIfHurt" :min="0" :max="doc.hp.max" size="mini" /></el-form-item>
                <el-form-item label="休整体力保底"><el-input-number v-model="doc.recovery.stageRestHpFloor" :min="0" :max="doc.hp.max" size="mini" /></el-form-item>
                <el-form-item label="休整幸运保底"><el-input-number v-model="doc.recovery.stageRestLuckFloor" :min="0" :max="doc.luck.max" size="mini" /></el-form-item>
                <el-form-item label="力竭休整回体力"><el-input-number v-model="doc.recovery.exhaustRestHp" :min="0" :max="doc.hp.max" size="mini" /></el-form-item>
                <el-form-item label="力竭标签">
                  <el-select v-model="doc.recovery.exhaustTag" clearable filterable placeholder="不设置">
                    <el-option v-for="k in tagKeys" :key="k" :label="k" :value="k" />
                  </el-select>
                </el-form-item>
              </el-form>
            </el-col>
            <el-col :span="12">
              <div>
                <el-button size="small" @click="preview">预览</el-button>
                <el-button size="small" @click="rawOpen = !rawOpen">{{ rawOpen ? '收起完整 JSON' : '编辑完整 JSON' }}</el-button>
                <el-button v-hasPermi="['business:journeyRules:edit']" size="small" type="primary" :disabled="current.editScope !== 'FULL'" @click="save">保存</el-button>
              </div>
              <el-input v-if="rawOpen" v-model="raw" type="textarea" :rows="16" style="margin-top: 8px" @change="applyRaw" />
              <template v-if="previewResult">
                <el-alert v-if="!previewResult.valid" type="error" :closable="false" :title="previewResult.problem" style="margin-top: 8px" />
                <template v-else>
                  <el-alert v-if="previewResult.unregisteredKeys.length" type="warning" :closable="false" style="margin-top: 8px"
                    :title="'引用了尚未注册的状态键，发布时会被拦下：' + previewResult.unregisteredKeys.join('、')" />
                  <p>{{ summaryText(previewResult.summary) }}</p>
                  <pre class="journey-json">{{ previewResult.pretty || '（清空规则）' }}</pre>
                </template>
              </template>
            </el-col>
          </el-row>
        </el-tab-pane>

        <el-tab-pane label="状态键" name="keys">
          <el-button v-hasPermi="['business:journeyRules:edit']" size="small" type="primary" :disabled="current.editScope === 'SELLING_FROZEN'" @click="openKey(null)">新增状态键</el-button>
          <el-table :data="current.stateKeys" size="small" border style="margin-top: 8px">
            <el-table-column label="键名" prop="stateKey" min-width="180" />
            <el-table-column label="名称" prop="label" min-width="120" />
            <el-table-column label="类型" width="90">
              <template slot-scope="s">{{ keyTypeText(s.row.keyType) }}</template>
            </el-table-column>
            <el-table-column label="初值" prop="initialValue" width="70" align="center" />
            <el-table-column label="范围" width="100" align="center">
              <template slot-scope="s">{{ s.row.minValue == null ? 0 : s.row.minValue }} – {{ s.row.maxValue == null ? 100 : s.row.maxValue }}</template>
            </el-table-column>
            <el-table-column label="保留" width="110">
              <template slot-scope="s">{{ s.row.persistScope === 'topic' ? '跨局保留' : '每局重置' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="130" align="center">
              <template slot-scope="s">
                <el-button v-hasPermi="['business:journeyRules:edit']" type="text" size="mini" :disabled="current.editScope === 'SELLING_FROZEN'" @click="openKey(s.row)">编辑</el-button>
                <el-button v-hasPermi="['business:journeyRules:edit']" type="text" size="mini" :disabled="current.editScope === 'SELLING_FROZEN'" @click="removeKey(s.row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>
    <cy-empty-state v-else title="选择一个主题开始编辑" />

    <el-dialog :title="keyForm.editing ? '编辑状态键' : '新增状态键'" :visible.sync="keyOpen" width="520px" append-to-body>
      <el-form size="small" label-width="90px">
        <el-form-item label="键名"><el-input v-model="keyForm.stateKey" :disabled="keyForm.editing" placeholder="如 counter.courage" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="keyForm.label" maxlength="32" /></el-form-item>
        <el-form-item label="初值"><el-input-number v-model="keyForm.initialValue" /></el-form-item>
        <el-form-item label="下限"><el-input-number v-model="keyForm.minValue" /></el-form-item>
        <el-form-item label="上限"><el-input-number v-model="keyForm.maxValue" /></el-form-item>
        <el-form-item label="角色卡展示"><el-switch v-model="keyForm.visible" /></el-form-item>
        <el-form-item label="保留">
          <el-radio-group v-model="keyForm.persistScope">
            <el-radio label="run">每局重置</el-radio>
            <el-radio label="topic">跨局保留</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert v-if="keyProblems.length" type="error" :closable="false" :title="keyProblems.join('；')" />
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="keyOpen = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitKey">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { journeyTopics, readJourneyRules, previewJourneyRules, saveJourneyRules, checkStateKeys, createStateKey, updateStateKey, deleteStateKey } from '@/api/business/journeyRules'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'

const DEFAULTS = {
  schemaVersion: 1,
  checkEnabled: false,
  stateEnabled: false,
  natAuto: true,
  difficulty: null,
  hp: { enabled: true, max: 10, init: 10 },
  luck: { enabled: true, max: 3, init: 3 },
  tiers: { easy: { dc: 10, damage: 1 }, medium: { dc: 13, damage: 2 }, hard: { dc: 16, damage: 3 } },
  recovery: { stationEndIfHurt: 2, stageRestHpFloor: 6, stageRestLuckFloor: 1, exhaustRestHp: 5, exhaustTag: null },
  fumbleExtraDamage: 1,
  modCap: 6
}

function merge(base, over) {
  const out = JSON.parse(JSON.stringify(base))
  Object.keys(over || {}).forEach(k => {
    const v = over[k]
    out[k] = v && typeof v === 'object' && !Array.isArray(v) && out[k] && typeof out[k] === 'object' ? merge(out[k], v) : v
  })
  return out
}

export default {
  name: 'JourneyRules',
  components: { CyEmptyState, CyPageHeader },
  data() {
    return {
      loading: false,
      saving: false,
      topics: [],
      topicQuery: { keyword: '', topicId: '' },
      current: null,
      tab: 'rules',
      doc: merge(DEFAULTS, {}),
      raw: '',
      rawOpen: false,
      previewResult: null,
      tierNames: [{ key: 'easy', label: '简单档' }, { key: 'medium', label: '中等档' }, { key: 'hard', label: '困难档' }],
      keyOpen: false,
      keyForm: {},
      keyProblems: []
    }
  },
  computed: {
    tagKeys() {
      return ((this.current && this.current.stateKeys) || []).map(k => k.stateKey).filter(k => k && k.indexOf('tag.') === 0)
    }
  },
  created() {
    this.searchTopics()
  },
  methods: {
    searchTopics() {
      this.loading = true
      journeyTopics({ keyword: this.topicQuery.keyword || undefined, topicId: this.topicQuery.topicId || undefined }).then(r => {
        this.topics = r.data || []
      }).finally(() => {
        this.loading = false
      })
    },
    pick(row) {
      if (row) this.open(row.id)
    },
    open(topicId) {
      readJourneyRules(topicId).then(r => {
        this.current = r.data
        let stored = {}
        try { stored = JSON.parse(r.data.normalized || r.data.rulesJson || '{}') } catch (e) { stored = {} }
        this.doc = merge(DEFAULTS, stored)
        this.raw = JSON.stringify(this.doc, null, 2)
        this.previewResult = null
      })
    },
    applyRaw() {
      try {
        this.doc = merge(DEFAULTS, JSON.parse(this.raw))
      } catch (e) {
        this.$modal.msgError('完整 JSON 格式不对，未应用')
      }
    },
    body() {
      return { rulesJson: this.rawOpen ? this.raw : JSON.stringify(this.doc) }
    },
    preview() {
      return previewJourneyRules(this.current.topic.id, this.body()).then(r => {
        this.previewResult = r.data
        if (r.data.valid) this.raw = r.data.pretty || ''
        return r.data
      })
    },
    save() {
      this.preview().then(result => {
        if (!result.valid) return
        return this.$prompt('请填写修改说明（会写入平台审计）', '保存数值规则', { inputValidator: v => !!(v && v.trim()) || '说明必填' })
          .then(p => saveJourneyRules(this.current.topic.id, Object.assign(this.body(), { note: p.value })))
          .then(() => {
            this.$modal.msgSuccess('已保存')
            this.open(this.current.topic.id)
          })
      }).catch(() => {})
    },
    scopeText(scope) {
      return { FULL: '可编辑', WHITELIST: '已过审', SELLING_FROZEN: '在售' }[scope] || ''
    },
    summaryText(s) {
      if (!s || !s.enabled) return '规则未启用：主题按无检定方式运行。'
      return '检定' + (s.checkEnabled ? '开' : '关') + '，状态' + (s.stateEnabled ? '开' : '关') + '，属性 ' + s.attributes + ' 个，念头 ' + s.thoughts + ' 个。'
    },
    keyTypeText(t) {
      return { flag: '线索', counter: '计数', relation: '关系', tag: '标签' }[t] || '—'
    },
    openKey(row) {
      this.keyProblems = []
      this.keyForm = row
        ? { editing: true, stateKey: row.stateKey, label: row.label, initialValue: row.initialValue, minValue: row.minValue, maxValue: row.maxValue, visible: row.visible !== false, persistScope: row.persistScope || 'run' }
        : { editing: false, stateKey: '', label: '', initialValue: 0, minValue: undefined, maxValue: undefined, visible: true, persistScope: 'run' }
      this.keyOpen = true
    },
    keyBody() {
      const f = this.keyForm
      const prefix = (f.stateKey || '').split('.')[0]
      const type = { clue: 'flag', counter: 'counter', relation: 'relation', tag: 'tag' }[prefix]
      return { stateKey: f.stateKey, keyType: type, scope: 'run', label: f.label, initialValue: f.initialValue, minValue: f.minValue, maxValue: f.maxValue, visible: f.visible, persistScope: f.persistScope }
    },
    submitKey() {
      const body = this.keyBody()
      this.saving = true
      checkStateKeys([body]).then(r => {
        this.keyProblems = r.data || []
        if (this.keyProblems.length) return
        const topicId = this.current.topic.id
        return (this.keyForm.editing ? updateStateKey(topicId, body.stateKey, body) : createStateKey(topicId, body)).then(() => {
          this.$modal.msgSuccess('已保存')
          this.keyOpen = false
          this.open(topicId)
        })
      }).finally(() => {
        this.saving = false
      })
    },
    removeKey(row) {
      this.$prompt('请填写删除说明（会写入平台审计）', '删除状态键 ' + row.stateKey, { inputValidator: v => !!(v && v.trim()) || '说明必填' })
        .then(p => deleteStateKey(this.current.topic.id, row.stateKey, p.value))
        .then(() => {
          this.$modal.msgSuccess('已删除')
          this.open(this.current.topic.id)
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.journey-json { max-height: 420px; overflow: auto; font-size: 12px; }
</style>

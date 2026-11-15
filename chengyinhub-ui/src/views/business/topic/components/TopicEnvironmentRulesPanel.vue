<template>
  <section class="env-rules-panel" v-loading="loading">
    <div class="env-rules-panel__head">
      <div>
        <h3>环境条件与天气/时段彩蛋触发规则</h3>
        <p>启用后，对局检定 <code>ENV_IN</code> 将读取主题城市（{{ cityLabel }}）实时天气与服务器时钟；下方 WHEN / THEN 规则可配置天气或时段门控点位与支线提示。</p>
      </div>
      <div class="env-rules-panel__actions">
        <el-button v-if="topicId" size="mini" icon="el-icon-refresh" :loading="loading" @click="loadConfig">刷新</el-button>
        <el-button
          v-if="topicId && canEdit"
          type="primary"
          size="mini"
          icon="el-icon-check"
          :loading="saving"
          @click="submit"
        >保存环境配置</el-button>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="mb10" />

    <div class="env-rules-panel__preview mb15">
      <span class="preview-label">当前城市实况预览（{{ cityLabel }}）：</span>
      <el-tag
        v-for="item in previewTags"
        :key="item.key"
        size="small"
        :type="item.value === 'unknown' ? 'warning' : 'info'"
        class="mr5"
      >{{ item.label }}：{{ formatValue(item.key, item.value) }}</el-tag>
    </div>

    <el-form label-width="130px" size="small" class="env-rules-panel__form">
      <el-form-item label="环境感知开关">
        <el-switch v-model="form.enabled" :disabled="!canEdit" />
        <span class="field-hint">对应 <code>journeyRules.rpg.environment.enabled</code></span>
      </el-form-item>
      <el-form-item label="写入状态键">
        <el-checkbox-group v-model="form.keys" :disabled="!canEdit || !form.enabled">
          <el-checkbox v-for="key in allKeys" :key="key" :label="key">{{ keyLabel(key) }}（{{ key }}）</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
    </el-form>

    <div class="env-rules-panel__toolbar">
      <span><strong>WHEN / THEN 触发规则</strong>（{{ form.rules.length }} / 20）</span>
      <el-button v-if="canEdit" size="mini" type="primary" plain icon="el-icon-plus" :disabled="form.rules.length >= 20" @click="addRule">
        新增触发规则
      </el-button>
    </div>

    <el-table :data="form.rules" border size="mini" empty-text="暂未配置天气或时段彩蛋规则">
      <el-table-column label="规则名称" min-width="140">
        <template slot-scope="{ row }">
          <el-input v-model="row.ruleName" size="mini" maxlength="64" placeholder="如：雨夜茶馆支线" :disabled="!canEdit" />
        </template>
      </el-table-column>

      <el-table-column label="WHEN 条件维度" width="130">
        <template slot-scope="{ row }">
          <el-select v-model="row.conditionKey" size="mini" style="width: 100%" :disabled="!canEdit" @change="onKeyChange(row)">
            <el-option v-for="key in allKeys" :key="key" :label="keyLabel(key)" :value="key" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="WHEN 匹配取值" min-width="200">
        <template slot-scope="{ row }">
          <el-select
            v-model="row.conditionValues"
            multiple
            collapse-tags
            size="mini"
            style="width: 100%"
            placeholder="选择至少 1 个值"
            :disabled="!canEdit"
          >
            <el-option
              v-for="val in valueOptions[row.conditionKey] || []"
              :key="val"
              :label="`${formatValue(row.conditionKey, val)} (${val})`"
              :value="val"
            />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="时段窗口 (选填 HH:mm)" min-width="175">
        <template slot-scope="{ row }">
          <div class="window-pair">
            <el-input v-model="row.windowStart" size="mini" placeholder="18:00" maxlength="5" :disabled="!canEdit" />
            <span>-</span>
            <el-input v-model="row.windowEnd" size="mini" placeholder="23:00" maxlength="5" :disabled="!canEdit" />
          </div>
        </template>
      </el-table-column>

      <el-table-column label="THEN 解锁节点" min-width="180">
        <template slot-scope="{ row }">
          <el-select
            v-if="nodeOptions.length"
            v-model="row.unlockNodeId"
            clearable
            filterable
            allow-create
            default-first-option
            size="mini"
            style="width: 100%"
            placeholder="选择或输入节点 ID"
            :disabled="!canEdit"
          >
            <el-option v-for="n in nodeOptions" :key="n.id" :label="n.label" :value="n.id" />
          </el-select>
          <el-input-number v-else v-model="row.unlockNodeId" size="mini" :min="1" :controls="false" placeholder="节点 ID" style="width: 100%" :disabled="!canEdit" />
        </template>
      </el-table-column>

      <el-table-column label="THEN 彩蛋/任务文案" min-width="160">
        <template slot-scope="{ row }">
          <el-input v-model="row.taskLabel" size="mini" maxlength="64" placeholder="如：解锁雨天隐藏支线" :disabled="!canEdit" />
        </template>
      </el-table-column>

      <el-table-column label="启用" width="70" align="center">
        <template slot-scope="{ row }">
          <el-switch v-model="row.enabled" :disabled="!canEdit" />
        </template>
      </el-table-column>

      <el-table-column v-if="canEdit" label="操作" width="75" align="center">
        <template slot-scope="{ $index }">
          <el-button type="text" size="mini" style="color: #f56c6c" @click="removeRule($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script>
import { getTopicEnvironment, saveTopicEnvironment } from '@/api/business/topic'

const KEY_LABELS = {
  weather: '天气',
  daypart: '时段',
  weekpart: '周内/周末',
  season: '季节'
}

const VALUE_LABELS = {
  clear: '晴朗',
  cloudy: '多云/阴',
  rain: '降雨',
  snow: '降雪',
  fog: '雾霾',
  wind: '大风',
  storm: '雷暴',
  dawn: '清晨',
  morning: '上午',
  afternoon: '午后',
  evening: '傍晚',
  night: '夜晚',
  weekday: '工作日',
  weekend: '周末',
  spring: '春季',
  summer: '夏季',
  autumn: '秋季',
  winter: '冬季',
  unknown: '暂不可用(未知)'
}

export default {
  name: 'TopicEnvironmentRulesPanel',
  props: {
    topicId: {
      type: [Number, String],
      default: null
    },
    chapters: {
      type: Array,
      default: () => []
    },
    canEdit: {
      type: Boolean,
      default: true
    }
  },
  data() {
    return {
      loading: false,
      saving: false,
      error: '',
      city: '',
      currentEnvironment: {},
      allKeys: ['weather', 'daypart', 'weekpart', 'season'],
      valueOptions: {
        weather: ['clear', 'cloudy', 'rain', 'snow', 'fog', 'wind', 'storm'],
        daypart: ['dawn', 'morning', 'afternoon', 'evening', 'night'],
        weekpart: ['weekday', 'weekend'],
        season: ['spring', 'summer', 'autumn', 'winter']
      },
      form: {
        enabled: false,
        keys: ['weather', 'daypart', 'weekpart', 'season'],
        rules: []
      }
    }
  },
  computed: {
    cityLabel() {
      return this.city && String(this.city).trim() ? this.city : '未配置城市'
    },
    previewTags() {
      return this.allKeys.map(key => ({
        key,
        label: this.keyLabel(key),
        value: (this.currentEnvironment && this.currentEnvironment[key]) || 'unknown'
      }))
    },
    nodeOptions() {
      const items = []
      const seen = new Set()
      ;(this.chapters || []).forEach(chapter => {
        const list = chapter.nodes || chapter.cmsTopicNodeList || []
        list.forEach(node => {
          const id = Number(node.id)
          if (!Number.isFinite(id) || id <= 0 || seen.has(id)) return
          seen.add(id)
          items.push({
            id,
            label: `${node.name || '未命名节点'} (#${id})`
          })
        })
      })
      return items
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
    keyLabel(key) {
      return KEY_LABELS[key] || key
    },
    formatValue(key, val) {
      return VALUE_LABELS[val] || val || '—'
    },
    applyPayload(payload) {
      const data = payload || {}
      this.city = data.city || ''
      this.currentEnvironment = data.currentEnvironment || {}
      if (Array.isArray(data.envKeys) && data.envKeys.length) {
        this.allKeys = data.envKeys
      }
      if (data.valueOptions && typeof data.valueOptions === 'object') {
        this.valueOptions = Object.assign({}, this.valueOptions, data.valueOptions)
      }
      const envCfg = data.environmentConfig || {}
      this.form.enabled = Boolean(envCfg.enabled)
      this.form.keys = Array.isArray(envCfg.keys) && envCfg.keys.length
        ? envCfg.keys.slice()
        : this.allKeys.slice()
      this.form.rules = (Array.isArray(data.rules) ? data.rules : []).map(r => ({
        id: r.id || null,
        ruleName: r.ruleName || '',
        conditionKey: r.conditionKey || 'weather',
        conditionValues: Array.isArray(r.conditionValues) ? r.conditionValues.slice() : [],
        unlockNodeId: r.unlockNodeId != null ? Number(r.unlockNodeId) : undefined,
        taskLabel: r.taskLabel || '',
        windowStart: r.windowStart || '',
        windowEnd: r.windowEnd || '',
        enabled: r.enabled !== false
      }))
    },
    loadConfig() {
      if (!this.topicId) return
      this.loading = true
      this.error = ''
      getTopicEnvironment(this.topicId)
        .then(res => {
          this.applyPayload(res && res.data ? res.data : res)
        })
        .catch(() => {
          this.error = '环境条件配置读取失败，请确认权限或稍后重试。'
        })
        .finally(() => {
          this.loading = false
        })
    },
    addRule() {
      this.form.rules.push({
        id: null,
        ruleName: '',
        conditionKey: 'weather',
        conditionValues: ['rain'],
        unlockNodeId: undefined,
        taskLabel: '',
        windowStart: '',
        windowEnd: '',
        enabled: true
      })
    },
    removeRule(index) {
      this.form.rules.splice(index, 1)
    },
    onKeyChange(row) {
      const allowed = this.valueOptions[row.conditionKey] || []
      row.conditionValues = allowed.length ? [allowed[0]] : []
    },
    submit() {
      if (!this.topicId || !this.canEdit) return
      const payload = {
        enabled: Boolean(this.form.enabled),
        keys: this.form.keys && this.form.keys.length ? this.form.keys : this.allKeys.slice(),
        rules: this.form.rules.map(r => ({
          ruleName: r.ruleName ? r.ruleName.trim() : '',
          conditionKey: r.conditionKey,
          conditionValues: Array.isArray(r.conditionValues) ? r.conditionValues : [],
          unlockNodeId: r.unlockNodeId ? Number(r.unlockNodeId) : null,
          taskLabel: r.taskLabel ? r.taskLabel.trim() : '',
          windowStart: r.windowStart ? r.windowStart.trim() : null,
          windowEnd: r.windowEnd ? r.windowEnd.trim() : null,
          enabled: r.enabled !== false
        }))
      }
      this.saving = true
      this.error = ''
      saveTopicEnvironment(this.topicId, payload)
        .then(res => {
          this.applyPayload(res && res.data ? res.data : res)
          this.$message.success('环境条件与触发规则已保存')
        })
        .catch(err => {
          const msg = (err && (err.msg || err.message)) || '保存环境配置失败'
          this.error = msg
        })
        .finally(() => {
          this.saving = false
        })
    }
  }
}
</script>

<style scoped>
.env-rules-panel__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.env-rules-panel__head h3 {
  margin: 0 0 4px;
  font-size: 15px;
}
.env-rules-panel__head p {
  margin: 0;
  color: #606266;
  font-size: 12px;
}
.env-rules-panel__actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.env-rules-panel__preview {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.preview-label {
  color: #606266;
  font-size: 12px;
}
.field-hint {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
.env-rules-panel__toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 12px 0 8px;
}
.window-pair {
  display: flex;
  align-items: center;
  gap: 4px;
}
</style>

<template>
  <div class="app-container theme-npc-page">
    <el-tabs v-model="activeTab" @tab-click="onTabChange">
      <el-tab-pane label="剧本人物表 (E1)" name="characters">
        <el-form :inline="true" :model="query" size="small" style="margin-bottom: 12px">
          <el-form-item label="所属主题">
            <el-select v-model="query.topicId" clearable filterable placeholder="全部主题" style="width: 220px">
              <el-option v-for="t in topicOptions" :key="t.id" :label="t.title + ' (#' + t.id + ')'" :value="t.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="角色名称">
            <el-input v-model.trim="query.name" clearable placeholder="按角色名搜索" style="width: 180px" @keyup.enter.native="loadCharacters" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadCharacters">查询</el-button>
            <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
            <el-button v-hasPermi="['business:themeNpc:add']" type="primary" plain icon="el-icon-plus" @click="openCreate">新增人物</el-button>
          </el-form-item>
        </el-form>

        <el-table v-loading="loading" :data="characterRows" border stripe>
          <el-table-column label="ID" prop="id" width="76" />
          <el-table-column label="所属主题" min-width="180">
            <template slot-scope="s">
              <span>{{ s.row.topicTitle || '未命名主题' }}</span>
              <el-tag size="mini" type="info" style="margin-left: 6px">#{{ s.row.scopeId }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="角色名" prop="name" min-width="120" />
          <el-table-column label="关系状态键" min-width="160">
            <template slot-scope="s">
              <el-tag v-if="s.row.relationKey" size="mini" type="success">{{ s.row.relationKey }}</el-tag>
              <span v-else class="muted">未绑定</span>
            </template>
          </el-table-column>
          <el-table-column label="初始关系" width="110" align="center">
            <template slot-scope="s">
              <span v-if="s.row.initialRelation !== null && s.row.initialRelation !== undefined">{{ s.row.initialRelation }}</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="人设摘要" prop="persona" min-width="220" show-overflow-tooltip />
          <el-table-column label="排序" prop="sort" width="76" align="center" />
          <el-table-column label="状态" width="90" align="center">
            <template slot-scope="s">
              <el-tag :type="s.row.enabled === 1 ? 'success' : 'info'" size="mini">{{ s.row.enabled === 1 ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="center">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:themeNpc:edit']" type="text" size="mini" @click="openEdit(s.row)">编辑</el-button>
              <el-button v-hasPermi="['business:themeNpc:remove']" type="text" size="mini" @click="onRemove(s.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="3D形象生成任务审核" name="avatarJobs">
        <el-table v-loading="avatarLoading" :data="avatarJobs" border stripe>
          <el-table-column label="任务ID" prop="id" width="80" />
          <el-table-column label="商家" min-width="140">
            <template slot-scope="s">{{ s.row.merchant_name || ('#' + s.row.merchant_id) }}</template>
          </el-table-column>
          <el-table-column label="风格" prop="style" width="100" />
          <el-table-column label="提供方" prop="provider" width="120" />
          <el-table-column label="生成状态" prop="status" width="110" />
          <el-table-column label="审核状态" width="120">
            <template slot-scope="s">
              <el-tag :type="s.row.review_status === 'APPROVED' ? 'success' : (s.row.review_status === 'REJECTED' ? 'danger' : 'warning')" size="mini">
                {{ s.row.review_status || 'PENDING' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="审核说明" prop="review_reason" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="160" align="center">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:themeNpc:edit']" type="text" size="mini" @click="onReviewAvatar(s.row, 'APPROVED')">通过</el-button>
              <el-button v-hasPermi="['business:themeNpc:edit']" type="text" size="mini" @click="onReviewAvatar(s.row, 'REJECTED')">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="声音克隆授权审计" name="voiceAudits">
        <el-table v-loading="voiceLoading" :data="voiceAudits" border stripe>
          <el-table-column label="NPC ID" prop="id" width="84" />
          <el-table-column label="作用域" width="100">
            <template slot-scope="s">{{ s.row.scope_type === 1 ? '剧本' : (s.row.scope_type === 2 ? '门店分身' : '节点NPC') }}</template>
          </el-table-column>
          <el-table-column label="名称" prop="name" min-width="120" />
          <el-table-column label="音色ID" prop="voice_id" min-width="150" show-overflow-tooltip />
          <el-table-column label="复刻状态" prop="voice_status" width="90" />
          <el-table-column label="授权Intent" prop="intent_state" width="110" />
          <el-table-column label="音频凭证" prop="sample_reference" min-width="160" show-overflow-tooltip />
          <el-table-column label="操作" width="110" align="center">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:themeNpc:edit']" type="text" size="mini" @click="onRevokeVoice(s.row)">撤回音色</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="AI提供方环境变量状态" name="providers">
        <el-alert title="所有 AI 提供方密钥均通过服务端环境变量注入，不入库也不在 Git 保存。" type="info" show-icon :closable="false" style="margin-bottom: 12px" />
        <el-table v-loading="providerLoading" :data="providerRows" border stripe>
          <el-table-column label="能力" prop="label" min-width="200" />
          <el-table-column label="Provider" prop="provider" width="120" />
          <el-table-column label="模型" prop="model" width="150" />
          <el-table-column label="Endpoint Host" prop="baseUrlHost" min-width="180" />
          <el-table-column label="环境变量名" prop="envVarHint" min-width="200" />
          <el-table-column label="开关" width="90" align="center">
            <template slot-scope="s">
              <el-tag :type="s.row.enabled ? 'success' : 'info'" size="mini">{{ s.row.enabled ? '已开启' : '未开启' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="密钥配置" width="100" align="center">
            <template slot-scope="s">
              <el-tag :type="s.row.keyConfigured ? 'success' : 'warning'" size="mini">{{ s.row.keyConfigured ? '已注入' : '未配置' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="form.id ? '编辑剧本人物' : '新增剧本人物'" :visible.sync="dialogVisible" width="580px" append-to-body :close-on-click-modal="false">
      <el-form ref="charForm" :model="form" :rules="rules" size="small" label-width="104px">
        <el-form-item v-if="!form.id" label="所属主题" prop="scopeId">
          <el-select v-model="form.scopeId" filterable placeholder="选择主题" style="width: 100%" @change="onTopicChange">
            <el-option v-for="t in topicOptions" :key="t.id" :label="t.title + ' (#' + t.id + ')'" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="所属主题">
          <span>{{ form.topicTitle || '主题' }} #{{ form.scopeId }}</span>
        </el-form-item>
        <el-form-item label="角色名称" prop="name">
          <el-input v-model.trim="form.name" maxlength="32" show-word-limit placeholder="例如：掌柜林婶" />
        </el-form-item>
        <el-form-item label="关系状态键" prop="relationKey">
          <el-select v-model="form.relationKey" filterable allow-create clearable default-first-option placeholder="选择或输入 relation.xxx" style="width: 100%">
            <el-option v-for="k in relationKeyOptions" :key="k.stateKey" :label="k.stateKey + (k.label ? ' (' + k.label + ')' : '')" :value="k.stateKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="初始关系值" prop="initialRelation">
          <el-input-number v-model="form.initialRelation" :min="-10" :max="10" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="人物人设" prop="persona">
          <el-input v-model.trim="form.persona" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="性格、立场、说话方式及禁止剧透边界" />
        </el-form-item>
        <el-form-item label="开场白" prop="greeting">
          <el-input v-model.trim="form.greeting" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="头像地址" prop="avatar">
          <el-input v-model.trim="form.avatar" maxlength="255" placeholder="可选头像 URL" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :max="999" :precision="0" />
        </el-form-item>
        <el-form-item label="状态" prop="enabled">
          <el-radio-group v-model="form.enabled">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  addThemeNpc, delThemeNpc, listAiProviderStatuses, listAvatarJobs, listThemeNpc,
  listThemeNpcTopics, listThemeRelationKeys, listVoiceAudits, reviewAvatarJob,
  revokeVoiceConsent, updateThemeNpc
} from '@/api/business/themeNpc'

export default {
  name: 'ThemeNpc',
  data() {
    return {
      activeTab: 'characters',
      loading: false,
      avatarLoading: false,
      voiceLoading: false,
      providerLoading: false,
      saving: false,
      dialogVisible: false,
      query: { topicId: undefined, name: '' },
      topicOptions: [],
      relationKeyOptions: [],
      characterRows: [],
      avatarJobs: [],
      voiceAudits: [],
      providerRows: [],
      form: { id: undefined, scopeId: undefined, topicTitle: '', name: '', relationKey: '', initialRelation: 0, persona: '', greeting: '', avatar: '', sort: 0, enabled: 1 },
      rules: {
        scopeId: [{ required: true, message: '请选择所属主题', trigger: 'change' }],
        name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.loadTopics()
    this.loadCharacters()
  },
  methods: {
    onTabChange(tab) {
      if (tab.name === 'avatarJobs') this.loadAvatarJobs()
      else if (tab.name === 'voiceAudits') this.loadVoiceAudits()
      else if (tab.name === 'providers') this.loadProviders()
    },
    async loadTopics() {
      const res = await listThemeNpcTopics()
      this.topicOptions = (res && res.data) || []
    },
    async loadCharacters() {
      this.loading = true
      try {
        const res = await listThemeNpc(this.query)
        this.characterRows = (res && res.data) || []
      } finally { this.loading = false }
    },
    resetQuery() {
      this.query = { topicId: undefined, name: '' }
      this.loadCharacters()
    },
    async onTopicChange(topicId) {
      if (!topicId) { this.relationKeyOptions = []; return }
      const res = await listThemeRelationKeys(topicId)
      this.relationKeyOptions = (res && res.data) || []
    },
    openCreate() {
      this.form = { id: undefined, scopeId: this.query.topicId, topicTitle: '', name: '', relationKey: '', initialRelation: 0, persona: '', greeting: '', avatar: '', sort: 0, enabled: 1 }
      if (this.form.scopeId) this.onTopicChange(this.form.scopeId)
      this.dialogVisible = true
    },
    openEdit(row) {
      this.form = { ...row, initialRelation: row.initialRelation != null ? row.initialRelation : 0 }
      if (this.form.scopeId) this.onTopicChange(this.form.scopeId)
      this.dialogVisible = true
    },
    onSubmit() {
      this.$refs.charForm.validate(async valid => {
        if (!valid) return
        this.saving = true
        try {
          if (this.form.id) await updateThemeNpc(this.form)
          else await addThemeNpc(this.form)
          this.$modal.msgSuccess('保存成功')
          this.dialogVisible = false
          this.loadCharacters()
        } finally { this.saving = false }
      })
    },
    onRemove(row) {
      this.$modal.confirm('确认删除剧本人物「' + row.name + '」？').then(() => delThemeNpc(row.id)).then(() => {
        this.$modal.msgSuccess('已删除')
        this.loadCharacters()
      }).catch(() => {})
    },
    async loadAvatarJobs() {
      this.avatarLoading = true
      try {
        const res = await listAvatarJobs()
        this.avatarJobs = (res && res.data) || []
      } finally { this.avatarLoading = false }
    },
    onReviewAvatar(row, decision) {
      if (decision === 'REJECTED') {
        this.$prompt('请输入驳回原因', '驳回形象生成', { inputValidator: v => !!(v && v.trim()) || '请填写原因' })
          .then(({ value }) => reviewAvatarJob(row.id, { decision, reason: value.trim() }))
          .then(() => { this.$modal.msgSuccess('已驳回'); this.loadAvatarJobs() })
          .catch(() => {})
      } else {
        reviewAvatarJob(row.id, { decision }).then(() => { this.$modal.msgSuccess('已通过'); this.loadAvatarJobs() })
      }
    },
    async loadVoiceAudits() {
      this.voiceLoading = true
      try {
        const res = await listVoiceAudits()
        this.voiceAudits = (res && res.data) || []
      } finally { this.voiceLoading = false }
    },
    onRevokeVoice(row) {
      this.$modal.confirm('确认撤回 NPC「' + row.name + '」的声音音色与样本凭证？')
        .then(() => revokeVoiceConsent(row.id, row.voice_version))
        .then(() => { this.$modal.msgSuccess('已撤回'); this.loadVoiceAudits() })
        .catch(() => {})
    },
    async loadProviders() {
      this.providerLoading = true
      try {
        const res = await listAiProviderStatuses()
        this.providerRows = (res && res.data) || []
      } finally { this.providerLoading = false }
    }
  }
}
</script>

<style scoped>
.muted { color: #909399; font-size: 12px; }
</style>

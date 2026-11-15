<template>
  <div class="app-container">
    <cy-page-header eyebrow="数据与配置" title="掉落规则" description="玩家首次完成节点或到访商家据点时的随机奖励。新建规则默认停用，核对后再启用。">
      <el-button slot="primary" v-hasPermi="['business:rewardDrop:add']" type="primary" size="small" @click="openEditor(null)">新建规则</el-button>
      <el-button slot="primary" size="small" icon="el-icon-refresh" :loading="loading" @click="getList">刷新</el-button>
    </cy-page-header>

    <el-form :model="query" size="small" :inline="true">
      <el-form-item label="适用范围">
        <el-select v-model="query.scope" clearable placeholder="全部" style="width: 140px">
          <el-option v-for="s in scopes" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="奖励类型">
        <el-select v-model="query.dropType" clearable placeholder="全部" style="width: 160px">
          <el-option v-for="t in dropTypes" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item label="主题编号">
        <el-input v-model="query.topicId" clearable placeholder="主题编号" style="width: 140px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="getList">筛选</el-button>
      </el-form-item>
    </el-form>

    <cy-resource-state v-if="error" kind="error" title="掉落规则暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="getList" />
    <cy-empty-state v-else-if="!loading && !rules.length" title="还没有掉落规则" description="没有启用的规则时，玩家不会获得随机奖励。" />
    <el-table v-else v-loading="loading" :data="rules" size="small" border>
      <el-table-column label="编号" prop="id" width="70" />
      <el-table-column label="适用范围" min-width="160">
        <template slot-scope="s">{{ scopeText(s.row) }}</template>
      </el-table-column>
      <el-table-column label="奖励" min-width="180">
        <template slot-scope="s">{{ rewardText(s.row) }}</template>
      </el-table-column>
      <el-table-column label="权重" prop="weight" width="80" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="s"><cy-status-tag :label="s.row.status === 1 ? '启用' : '停用'" :tone="s.row.status === 1 ? 'success' : 'neutral'" /></template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="160" />
      <el-table-column label="操作" width="150" align="center">
        <template slot-scope="s">
          <el-button v-hasPermi="['business:rewardDrop:edit']" type="text" size="mini" @click="openEditor(s.row)">编辑</el-button>
          <el-button v-hasPermi="['business:rewardDrop:edit']" type="text" size="mini" @click="toggle(s.row)">{{ s.row.status === 1 ? '停用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="form.id ? '编辑掉落规则' : '新建掉落规则'" :visible.sync="editorOpen" width="560px" append-to-body>
      <el-form ref="form" :model="form" label-width="96px" size="small">
        <el-form-item label="适用范围">
          <el-radio-group v-model="form.scope">
            <el-radio v-for="s in scopes" :key="s.value" :label="s.value">{{ s.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scope === 1" label="主题编号">
          <el-input v-model="form.topicId" placeholder="主题编号" />
        </el-form-item>
        <el-form-item v-if="form.scope === 2" label="场景">
          <el-select v-model="form.sceneKey" placeholder="选择场景">
            <el-option v-for="sc in scenes" :key="sc.value" :label="sc.label" :value="sc.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="奖励类型">
          <el-select v-model="form.dropType">
            <el-option v-for="t in dropTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.dropType === 1" label="倍率">
          <el-input-number v-model="form.mult" :min="2" :max="10" />
        </el-form-item>
        <el-form-item v-if="form.dropType === 2" label="徽章编码">
          <el-input v-model="form.badgeCode" placeholder="如 FRAGMENT" />
        </el-form-item>
        <el-form-item v-if="form.dropType === 3" label="优惠券编号">
          <el-input v-model="form.couponTemplateId" placeholder="优惠券编号" />
        </el-form-item>
        <el-form-item label="权重">
          <el-input-number v-model="form.weight" :min="1" :max="10000" />
        </el-form-item>
        <el-form-item label="保存内容">
          <code>{{ paramPreview }}</code>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="editorOpen = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listRewardDropRules, addRewardDropRule, updateRewardDropRule, setRewardDropRuleStatus } from '@/api/business/rewardDrop'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const SCENES = [
  { value: 'topic_node', label: '主题节点首次完成' },
  { value: 'roam_merchant_poi', label: '漫游商家据点' }
]

export default {
  name: 'RewardDropRules',
  components: { CyEmptyState, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      error: false,
      saving: false,
      rules: [],
      query: { scope: null, dropType: null, status: null, topicId: '' },
      editorOpen: false,
      form: {},
      scopes: [{ value: 0, label: '全局' }, { value: 1, label: '指定主题' }, { value: 2, label: '指定场景' }],
      dropTypes: [{ value: 1, label: '探索值倍率' }, { value: 2, label: '徽章碎片' }, { value: 3, label: '商家彩蛋券' }],
      scenes: SCENES
    }
  },
  computed: {
    paramPreview() {
      return JSON.stringify(this.paramOf(this.form))
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      this.error = false
      const params = Object.assign({}, this.query, { topicId: this.query.topicId || undefined })
      listRewardDropRules(params).then(res => {
        this.rules = res.data || []
      }).catch(() => {
        this.error = true
      }).finally(() => {
        this.loading = false
      })
    },
    parse(json) {
      try { return JSON.parse(json || '{}') } catch (e) { return {} }
    },
    scopeText(row) {
      if (row.scope === 1) return '主题 #' + row.topicId
      if (row.scope === 2) {
        const scene = SCENES.find(s => s.value === row.sceneKey)
        return scene ? scene.label : '未知场景'
      }
      return '全局'
    },
    rewardText(row) {
      const p = this.parse(row.paramJson)
      if (row.dropType === 1) return '探索值 ×' + (p.mult || '?')
      if (row.dropType === 2) return '徽章碎片 ' + (p.badgeCode || '')
      if (row.dropType === 3) return '商家彩蛋券 #' + (p.couponTemplateId || '?')
      return '未知奖励'
    },
    paramOf(f) {
      if (f.dropType === 1) return { mult: f.mult }
      if (f.dropType === 2) return { badgeCode: f.badgeCode }
      if (f.dropType === 3) return { couponTemplateId: Number(f.couponTemplateId) || null }
      return {}
    },
    openEditor(row) {
      const p = row ? this.parse(row.paramJson) : {}
      this.form = {
        id: row ? row.id : null,
        scope: row ? row.scope : 0,
        topicId: row && row.topicId ? String(row.topicId) : '',
        sceneKey: row ? row.sceneKey : 'topic_node',
        dropType: row ? row.dropType : 1,
        weight: row ? row.weight : 10,
        mult: p.mult || 2,
        badgeCode: p.badgeCode || '',
        couponTemplateId: p.couponTemplateId ? String(p.couponTemplateId) : ''
      }
      this.editorOpen = true
    },
    save() {
      const f = this.form
      const body = {
        scope: f.scope,
        topicId: f.scope === 1 ? Number(f.topicId) || null : null,
        sceneKey: f.scope === 2 ? f.sceneKey : null,
        dropType: f.dropType,
        weight: f.weight,
        paramJson: JSON.stringify(this.paramOf(f))
      }
      this.saving = true
      const call = f.id ? updateRewardDropRule(f.id, body) : addRewardDropRule(body)
      call.then(() => {
        this.$modal.msgSuccess(f.id ? '已保存' : '已新建，当前为停用状态')
        this.editorOpen = false
        this.getList()
      }).finally(() => {
        this.saving = false
      })
    },
    toggle(row) {
      const next = row.status === 1 ? 0 : 1
      this.$modal.confirm(next === 1 ? '启用后玩家立即可能获得这条奖励，确认启用？' : '确认停用这条规则？').then(() => {
        return setRewardDropRuleStatus(row.id, next)
      }).then(() => {
        this.$modal.msgSuccess(next === 1 ? '已启用' : '已停用')
        this.getList()
      }).catch(() => {})
    }
  }
}
</script>

<template>
  <div class="app-container">
    <!-- 全局基础掉落概率设置 -->
    <el-card shadow="never" style="margin-bottom: 16px;">
      <div slot="header" class="clearfix">
        <span>全局掉落触发基础概率与预期预览</span>
      </div>
      <el-form :inline="true" size="small">
        <el-form-item label="基础概率 (万分比 0-10000)">
          <el-input-number v-model="baseProbBp" :min="0" :max="10000" :step="100" />
          <span style="margin-left: 8px; color: #606266;">= {{ (baseProbBp / 100).toFixed(2) }}%</span>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="small"
            icon="el-icon-check"
            v-hasPermi="['business:topicnode:edit']"
            @click="handleSaveBaseProb"
          >保存基础概率</el-button>
        </el-form-item>
        <el-form-item label="预览场景">
          <el-select v-model="previewParams.sceneKey" style="width: 170px;" @change="loadPreview">
            <el-option label="topic_node (路线节点)" value="topic_node" />
            <el-option label="roam_merchant_poi (漫游门店)" value="roam_merchant_poi" />
          </el-select>
        </el-form-item>
        <el-form-item label="主题ID">
          <el-input v-model.number="previewParams.topicId" placeholder="可选" clearable style="width: 120px;" />
        </el-form-item>
        <el-form-item>
          <el-button size="small" icon="el-icon-refresh" @click="loadPreview">刷新预期</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="preview"
        type="info"
        :closable="false"
        :title="`当前生效权重和: ${preview.totalWeight || 0} ｜ 期望额外探索值加成: +${preview.expectedExtraXpPercent || 0}% ｜ 触发几率: ${preview.baseProbPercent || 0}%`"
      />
      <el-table v-if="preview && preview.rules && preview.rules.length" :data="preview.rules" size="mini" style="margin-top: 10px;">
        <el-table-column label="规则ID" prop="id" width="80" />
        <el-table-column label="作用域" width="110">
          <template slot-scope="scope">{{ scopeLabel(scope.row.scope) }}</template>
        </el-table-column>
        <el-table-column label="掉落类型" width="120">
          <template slot-scope="scope">{{ dropTypeLabel(scope.row.dropType) }}</template>
        </el-table-column>
        <el-table-column label="权重" prop="weight" width="90" />
        <el-table-column label="池内占比" width="100">
          <template slot-scope="scope">{{ scope.row.sharePercent }}%</template>
        </el-table-column>
        <el-table-column label="单次实际掉落率" width="130">
          <template slot-scope="scope">{{ (scope.row.effectiveProbBp / 100).toFixed(2) }}%</template>
        </el-table-column>
        <el-table-column label="参数 JSON" prop="paramJson" show-overflow-tooltip />
      </el-table>
    </el-card>

    <!-- 规则筛选与增删改 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" size="small">
      <el-form-item label="作用域" prop="scope">
        <el-select v-model="queryParams.scope" placeholder="全部" clearable style="width: 130px;">
          <el-option label="0-全局" :value="0" />
          <el-option label="1-指定主题" :value="1" />
          <el-option label="2-指定场景" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item label="掉落类型" prop="dropType">
        <el-select v-model="queryParams.dropType" placeholder="全部" clearable style="width: 140px;">
          <el-option label="1-XP倍率" :value="1" />
          <el-option label="2-徽章碎片" :value="2" />
          <el-option label="3-商家彩蛋券" :value="3" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 110px;">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="getList">搜索</el-button>
        <el-button icon="el-icon-plus" type="primary" plain size="mini" v-hasPermi="['business:topicnode:add']" @click="handleAdd">新增规则</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="ruleList">
      <el-table-column label="ID" prop="id" width="70" />
      <el-table-column label="作用域" width="110">
        <template slot-scope="scope">{{ scopeLabel(scope.row.scope) }}</template>
      </el-table-column>
      <el-table-column label="主题ID" prop="topicId" width="90" />
      <el-table-column label="场景Key" prop="sceneKey" width="150" />
      <el-table-column label="掉落类型" width="120">
        <template slot-scope="scope">{{ dropTypeLabel(scope.row.dropType) }}</template>
      </el-table-column>
      <el-table-column label="权重" prop="weight" width="90" />
      <el-table-column label="参数 JSON" prop="paramJson" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template slot-scope="scope">
          <el-tag :type="scope.row.status === 1 ? 'success' : 'info'">{{ scope.row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" align="center">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-edit" v-hasPermi="['business:topicnode:edit']" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" v-hasPermi="['business:topicnode:remove']" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 新增/编辑规则对话框 -->
    <el-dialog :title="dialogTitle" :visible.sync="dialogOpen" width="520px" append-to-body>
      <el-form ref="ruleForm" :model="form" label-width="110px" size="small">
        <el-form-item label="作用域">
          <el-select v-model="form.scope" style="width: 100%;">
            <el-option label="0 - 全局" :value="0" />
            <el-option label="1 - 指定主题" :value="1" />
            <el-option label="2 - 指定场景" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="主题ID" v-if="form.scope === 1">
          <el-input-number v-model="form.topicId" :min="1" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="场景Key" v-if="form.scope === 2">
          <el-input v-model="form.sceneKey" placeholder="例如 roam_merchant_poi 或 topic_node" />
        </el-form-item>
        <el-form-item label="掉落类型">
          <el-select v-model="form.dropType" style="width: 100%;">
            <el-option label="1 - XP倍率 ({\"mult\":2})" :value="1" />
            <el-option label="2 - 徽章碎片 ({\"badgeCode\":\"FRAGMENT\"})" :value="2" />
            <el-option label="3 - 商家彩蛋券 ({\"couponTemplateId\":1})" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="抽取权重">
          <el-input-number v-model="form.weight" :min="1" :max="100000" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="参数 JSON">
          <el-input type="textarea" :rows="3" v-model="form.paramJson" placeholder='例如 {"mult":2}' />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" @click="submitForm">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listDropRules,
  addDropRule,
  updateDropRule,
  delDropRule,
  getBaseDropProb,
  updateBaseDropProb,
  previewDropProbabilities
} from '@/api/business/rewardDrop'

export default {
  name: 'RewardDropAdmin',
  data() {
    return {
      loading: false,
      total: 0,
      ruleList: [],
      baseProbBp: 1500,
      preview: null,
      previewParams: {
        sceneKey: 'topic_node',
        topicId: undefined
      },
      queryParams: {
        pageNum: 1,
        pageSize: 20,
        scope: undefined,
        dropType: undefined,
        status: undefined
      },
      dialogOpen: false,
      dialogTitle: '新增掉落规则',
      form: {}
    }
  },
  created() {
    this.loadBaseProb()
    this.loadPreview()
    this.getList()
  },
  methods: {
    scopeLabel(scope) {
      if (scope === 0) return '全局'
      if (scope === 1) return '指定主题'
      if (scope === 2) return '指定场景'
      return String(scope)
    },
    dropTypeLabel(dropType) {
      if (dropType === 1) return 'XP倍率'
      if (dropType === 2) return '徽章碎片'
      if (dropType === 3) return '商家彩蛋券'
      return String(dropType)
    },
    getList() {
      this.loading = true
      listDropRules(this.queryParams).then(res => {
        this.ruleList = res.rows || []
        this.total = res.total || 0
      }).catch(() => {}).finally(() => {
        this.loading = false
      })
    },
    loadBaseProb() {
      getBaseDropProb().then(res => {
        if (res.data && typeof res.data.baseProbBp === 'number') {
          this.baseProbBp = res.data.baseProbBp
        }
      }).catch(() => {})
    },
    loadPreview() {
      previewDropProbabilities(this.previewParams).then(res => {
        this.preview = res.data || null
      }).catch(() => {})
    },
    handleSaveBaseProb() {
      updateBaseDropProb(this.baseProbBp).then(() => {
        this.$modal.msgSuccess('基础概率已保存')
        this.loadPreview()
      }).catch(() => {})
    },
    handleAdd() {
      this.dialogTitle = '新增掉落规则'
      this.form = {
        scope: 0,
        topicId: undefined,
        sceneKey: '',
        dropType: 1,
        weight: 100,
        paramJson: '{"mult":2}',
        status: 1
      }
      this.dialogOpen = true
    },
    handleEdit(row) {
      this.dialogTitle = '编辑掉落规则'
      this.form = { ...row }
      this.dialogOpen = true
    },
    submitForm() {
      const action = this.form.id ? updateDropRule(this.form) : addDropRule(this.form)
      action.then(() => {
        this.$modal.msgSuccess('保存成功')
        this.dialogOpen = false
        this.getList()
        this.loadPreview()
      }).catch(() => {})
    },
    handleDelete(row) {
      this.$modal.confirm(`确认删除规则 #${row.id} 吗？`).then(() => delDropRule(row.id)).then(() => {
        this.$modal.msgSuccess('已删除')
        this.getList()
        this.loadPreview()
      }).catch(() => {})
    }
  }
}
</script>

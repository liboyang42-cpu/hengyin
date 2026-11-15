<template>
  <div class="app-container">
    <el-form :model="queryParams" :inline="true" size="small">
      <el-form-item label="任务池名称">
        <el-input v-model="queryParams.poolName" placeholder="模糊查询" clearable style="width: 180px;" />
      </el-form-item>
      <el-form-item label="入口节点ID">
        <el-input v-model.number="queryParams.entryNodeId" placeholder="可选" clearable style="width: 130px;" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 110px;">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="getList">搜索</el-button>
        <el-button type="primary" plain icon="el-icon-plus" size="mini" v-hasPermi="['business:taskpool:add']" @click="handleAddPool">新建任务池</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <div slot="header"><span>盲盒任务池列表 (M3)</span></div>
          <el-table v-loading="loading" :data="poolList" highlight-current-row @current-change="handleSelectPool">
            <el-table-column label="ID" prop="id" width="60" />
            <el-table-column label="池名称" prop="poolName" show-overflow-tooltip />
            <el-table-column label="入口节点" prop="entryNodeId" width="90" />
            <el-table-column label="商家会员ID" prop="merchantMemberId" width="100" />
            <el-table-column label="状态" width="80">
              <template slot-scope="scope">
                <el-tag :type="scope.row.status === 1 ? 'success' : 'info'">{{ scope.row.status === 1 ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center">
              <template slot-scope="scope">
                <el-button size="mini" type="text" v-hasPermi="['business:taskpool:edit']" @click.stop="handleEditPool(scope.row)">编辑</el-button>
                <el-button size="mini" type="text" v-hasPermi="['business:taskpool:remove']" @click.stop="handleDeletePool(scope.row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <div slot="header" class="clearfix">
            <span>{{ activePool ? `任务池条目：${activePool.poolName} (#${activePool.id})` : '请选择左侧任务池' }}</span>
            <el-button
              v-if="activePool"
              style="float: right; padding: 3px 0;"
              type="text"
              icon="el-icon-plus"
              v-hasPermi="['business:taskpool:edit']"
              @click="handleAddItem"
            >添加任务节点</el-button>
          </div>
          <el-table :data="itemList" empty-text="暂无任务条目">
            <el-table-column label="条目ID" prop="id" width="70" />
            <el-table-column label="节点ID" prop="nodeId" width="90" />
            <el-table-column label="节点名称" prop="nodeName" show-overflow-tooltip />
            <el-table-column label="权重" prop="weight" width="80" />
            <el-table-column label="状态" width="80">
              <template slot-scope="scope">
                <el-tag :type="scope.row.status === 1 ? 'success' : 'info'">{{ scope.row.status === 1 ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center">
              <template slot-scope="scope">
                <el-button size="mini" type="text" v-hasPermi="['business:taskpool:edit']" @click="handleEditItem(scope.row)">编辑</el-button>
                <el-button size="mini" type="text" v-hasPermi="['business:taskpool:remove']" @click="handleDeleteItem(scope.row)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 任务池新增/编辑 -->
    <el-dialog :title="poolDialogTitle" :visible.sync="poolDialogOpen" width="480px" append-to-body>
      <el-form :model="poolForm" label-width="110px" size="small">
        <el-form-item label="任务池名称">
          <el-input v-model="poolForm.poolName" placeholder="例如 咖啡馆每日盲盒任务" />
        </el-form-item>
        <el-form-item label="入口节点ID">
          <el-input-number v-model="poolForm.entryNodeId" :min="1" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="商家会员ID">
          <el-input-number v-model="poolForm.merchantMemberId" :min="1" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="主题ID">
          <el-input-number v-model="poolForm.topicId" :min="1" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="poolForm.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="poolDialogOpen = false">取消</el-button>
        <el-button type="primary" @click="submitPool">保存</el-button>
      </div>
    </el-dialog>

    <!-- 条目新增/编辑 -->
    <el-dialog :title="itemDialogTitle" :visible.sync="itemDialogOpen" width="440px" append-to-body>
      <el-form :model="itemForm" label-width="100px" size="small">
        <el-form-item label="任务节点ID">
          <el-input-number v-model="itemForm.nodeId" :min="1" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="抽取权重">
          <el-input-number v-model="itemForm.weight" :min="1" :max="100000" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="itemForm.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="itemDialogOpen = false">取消</el-button>
        <el-button type="primary" @click="submitItem">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listTaskPools,
  addTaskPool,
  updateTaskPool,
  delTaskPool,
  listTaskPoolItems,
  saveTaskPoolItem,
  delTaskPoolItem
} from '@/api/business/taskPool'

export default {
  name: 'NodeTaskPoolAdmin',
  data() {
    return {
      loading: false,
      total: 0,
      poolList: [],
      activePool: null,
      itemList: [],
      queryParams: {
        pageNum: 1,
        pageSize: 20,
        poolName: undefined,
        entryNodeId: undefined,
        status: undefined
      },
      poolDialogOpen: false,
      poolDialogTitle: '新建任务池',
      poolForm: {},
      itemDialogOpen: false,
      itemDialogTitle: '添加任务节点',
      itemForm: {}
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listTaskPools(this.queryParams).then(res => {
        this.poolList = res.rows || []
        this.total = res.total || 0
        if (!this.activePool && this.poolList.length > 0) {
          this.handleSelectPool(this.poolList[0])
        }
      }).catch(() => {}).finally(() => {
        this.loading = false
      })
    },
    handleSelectPool(row) {
      this.activePool = row || null
      if (!row || !row.id) {
        this.itemList = []
        return
      }
      listTaskPoolItems(row.id).then(res => {
        this.itemList = res.data || []
      }).catch(() => {})
    },
    handleAddPool() {
      this.poolDialogTitle = '新建任务池'
      this.poolForm = { poolName: '', entryNodeId: undefined, merchantMemberId: undefined, topicId: undefined, status: 1 }
      this.poolDialogOpen = true
    },
    handleEditPool(row) {
      this.poolDialogTitle = '编辑任务池'
      this.poolForm = { ...row }
      this.poolDialogOpen = true
    },
    submitPool() {
      const action = this.poolForm.id ? updateTaskPool(this.poolForm) : addTaskPool(this.poolForm)
      action.then(res => {
        this.$modal.msgSuccess('保存成功')
        this.poolDialogOpen = false
        if (res.data && res.data.id) {
          this.handleSelectPool(res.data)
        }
        this.getList()
      }).catch(() => {})
    },
    handleDeletePool(row) {
      this.$modal.confirm(`确认删除任务池「${row.poolName}」吗？`).then(() => delTaskPool(row.id)).then(() => {
        this.$modal.msgSuccess('已删除')
        if (this.activePool && this.activePool.id === row.id) {
          this.activePool = null
          this.itemList = []
        }
        this.getList()
      }).catch(() => {})
    },
    handleAddItem() {
      if (!this.activePool) return
      this.itemDialogTitle = '添加任务节点'
      this.itemForm = { poolId: this.activePool.id, nodeId: undefined, weight: 100, status: 1 }
      this.itemDialogOpen = true
    },
    handleEditItem(row) {
      this.itemDialogTitle = '编辑任务节点'
      this.itemForm = { ...row }
      this.itemDialogOpen = true
    },
    submitItem() {
      saveTaskPoolItem(this.itemForm).then(() => {
        this.$modal.msgSuccess('已保存')
        this.itemDialogOpen = false
        this.handleSelectPool(this.activePool)
      }).catch(() => {})
    },
    handleDeleteItem(row) {
      this.$modal.confirm(`确认从池中移除节点 #${row.nodeId} 吗？`).then(() => delTaskPoolItem(row.id)).then(() => {
        this.$modal.msgSuccess('已移除')
        this.handleSelectPool(this.activePool)
      }).catch(() => {})
    }
  }
}
</script>

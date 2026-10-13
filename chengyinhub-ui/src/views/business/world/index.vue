<template>
  <div class="app-container">
    <el-alert title="世界与城市内容目录使用范围" description="本页维护公开目录，按城市、公开状态和删除状态筛选。任务引用与发布版本冻结尚未接通，不能据此确认城市季或奖励生效。" type="info" :closable="false" show-icon />
    <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="80px">
      <el-form-item label="世界名称" prop="worldName">
        <el-input v-model="queryParams.worldName" placeholder="输入世界名称" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="城市编码" prop="cityCode">
        <el-input v-model="queryParams.cityCode" placeholder="输入城市编码" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          v-hasPermi="['business:world:edit']"
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="openDialog"
        >新增世界</el-button>
      </el-col>
    </el-row>

    <cy-resource-state v-if="loadError" kind="error" title="世界与城市内容目录暂时无法读取" description="请重新读取；若仍失败，请核对查看权限。" retry-text="重新读取" :retrying="loading" @retry="getList" />
    <el-table v-else v-loading="loading" :data="worldList" empty-text="暂无IP世界">
      <el-table-column label="世界编码" align="center" prop="worldCode" min-width="140" />
      <el-table-column label="世界名称" align="center" prop="worldName" min-width="160" />
      <el-table-column label="副标题" align="left" prop="subtitle" min-width="180" show-overflow-tooltip />
      <el-table-column label="城市" align="center" prop="cityCode" width="120" />
      <el-table-column label="公开状态" align="center" width="110">
        <template slot-scope="scope">{{ scope.row.status === '0' ? '公开' : scope.row.status === '1' ? '停用' : '待确认' }}</template>
      </el-table-column>
      <el-table-column label="目录更新时间" prop="updateTime" min-width="160" />
      <el-table-column label="排序" align="center" prop="sortNo" width="90" />
    </el-table>

    <el-dialog title="IP世界" :visible.sync="open" width="560px" append-to-body>
      <el-form :model="form" label-width="90px">
        <el-form-item label="世界编码">
          <el-input v-model="form.worldCode" />
        </el-form-item>
        <el-form-item label="世界名称">
          <el-input v-model="form.worldName" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" />
        </el-form-item>
        <el-form-item label="城市编码">
          <el-input v-model="form.cityCode" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.intro" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确定</el-button>
        <el-button @click="open = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import CyResourceState from '@/components/CyResourceState'
import { listWorlds, saveWorld } from '@/api/business/ipworld'

export default {
  components: { CyResourceState },
  name: 'BusinessWorld',
  data() {
    return {
      loading: false,
      loadError: false,
      requestToken: 0,
      open: false,
      worldList: [],
      queryParams: {
        worldName: null,
        cityCode: null
      },
      form: {
        worldCode: '',
        worldName: '',
        subtitle: '',
        cityCode: '',
        intro: '',
        status: '0',
        deleted: '0',
        sortNo: 0
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      const requestToken = ++this.requestToken
      this.loading = true
      this.loadError = false
      return listWorlds(this.queryParams).then(response => {
        if (requestToken !== this.requestToken) return
        if (!Array.isArray(response.rows)) throw new Error('列表数据不完整')
        this.worldList = response.rows
      }).catch(() => {
        if (requestToken !== this.requestToken) return
        this.worldList = []
        this.loadError = true
      }).finally(() => {
        if (requestToken === this.requestToken) this.loading = false
      })
    },
    handleQuery() {
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    },
    openDialog() {
      this.open = true
      this.form = {
        worldCode: '',
        worldName: '',
        subtitle: '',
        cityCode: '',
        intro: '',
        status: '0',
        deleted: '0',
        sortNo: 0
      }
    },
    submitForm() {
      saveWorld(this.form).then(() => {
        this.$modal.msgSuccess('保存成功')
        this.open = false
        this.getList()
      })
    }
  }
}
</script>

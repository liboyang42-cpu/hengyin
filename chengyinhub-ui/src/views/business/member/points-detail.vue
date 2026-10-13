
<template>
 <el-drawer :title="`${memberId} -- 积分明细`"  size="75%" :visible.sync="visible">
   <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <!-- <el-form-item label="用户ID" prop="memberId">
        <el-input
          v-model="queryParams.memberId"
          placeholder="请输入用户ID"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item> -->
      <el-form-item label="事件ID" prop="eventId">
        <el-input
          v-model="queryParams.eventId"
          placeholder="请输入对应事件ID如：订单ID等"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <!-- <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['business:pointsdetail:add']"
        >新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['business:pointsdetail:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['business:pointsdetail:remove']"
        >删除</el-button>
      </el-col> -->
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['business:pointsdetail:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="detailList" @selection-change="handleSelectionChange">
      <!-- <el-table-column type="selection" width="55" align="center" /> -->
      <el-table-column label="ID" align="center" prop="id" />
      <!-- <el-table-column label="用户ID" align="center" prop="memberId" /> -->
      <el-table-column label="用户当前积分" align="center" prop="currentPoints" />
      <el-table-column label="用户变化后积分" align="center" prop="afterPoints" />
      <el-table-column label="变化积分" align="center" prop="changePoints" />
      <el-table-column label="变化原因" align="center" prop="changeReason" />
      <!-- <el-table-column label="事件类型" align="center" prop="eventType" /> -->
      <el-table-column label="对应事件ID" align="center" prop="eventId" />
      <el-table-column label="变化类型" align="center">
        <template slot-scope="scope">
          <span 
            :style="getChangeTypeStyle(scope.row.changeType)"
          >
            {{ getChangeTypeName(scope.row.changeType) }}
          </span>
        </template>
     </el-table-column>
     <el-table-column label="变化时间" align="center" prop="createTime" width="180">
        <template slot-scope="scope">
          <span>{{ scope.row.createTime }}</span>
        </template>
      </el-table-column>
      <!-- <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:detail:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:detail:remove']"
          >删除</el-button>
        </template>
      </el-table-column> -->
    </el-table>
    
    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <!-- 添加或修改用户积分明细对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户ID" prop="memberId">
          <el-input v-model="form.memberId" placeholder="请输入用户ID" />
        </el-form-item>
        <el-form-item label="用户当前积分" prop="currentPoints">
          <el-input v-model="form.currentPoints" placeholder="请输入用户当前积分" />
        </el-form-item>
        <el-form-item label="用户变化后积分" prop="afterPoints">
          <el-input v-model="form.afterPoints" placeholder="请输入用户变化后积分" />
        </el-form-item>
        <el-form-item label="变化积分" prop="changePoints">
          <el-input v-model="form.changePoints" placeholder="请输入变化积分" />
        </el-form-item>
        <el-form-item label="变化原因" prop="changeReason">
          <el-input v-model="form.changeReason" type="textarea" placeholder="请输入内容" />
        </el-form-item>
        <el-form-item label="对应事件ID如：订单ID等" prop="eventId">
          <el-input v-model="form.eventId" placeholder="请输入对应事件ID如：订单ID等" />
        </el-form-item>
       
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
  </el-drawer>
</template>

<script>
import { listDetail, getDetail, delDetail, addDetail, updateDetail } from "@/api/business/pointsdetail"

export default {
  name: "pointsDetail",
  data() {
    return {
      memberId:0,
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 用户积分明细表格数据
      detailList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      visible: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        memberId: null,
        currentPoints: null,
        afterPoints: null,
        changePoints: null,
        changeReason: null,
        eventType: null,
        eventId: null,
        changeType: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    init(id) {
      this.queryParams.memberId = id || 0
      this.form.memberId = id
      this.memberId = id
      this.visible = true
      this.getList()
    },
    // 获取变化类型文本
    getChangeTypeName(type) {
      const typeMap = {
        1: '收入',
        2: '支出'
      }
      return typeMap[type] || '未知类型'
    },
    // 获取变化类型样式
    getChangeTypeStyle(type) {
      const styleMap = {
        1: { color: '#27ae60', fontWeight: 'bold' }, // 收入：绿色加粗
        2: { color: '#e74c3c' }// 支出：红色
      }
      return styleMap[type] || { color: '#999' } // 默认灰色
    },
    /** 查询用户积分明细列表 */
    getList() {
      this.loading = true
      listDetail(this.queryParams).then(response => {
        this.detailList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    // 取消按钮
    cancel() {
      console.log('cancel');
      this.open = false
      this.reset()
    },
    // 表单重置
    reset() {
      this.form = {
        id: null,
        memberId: null,
        currentPoints: null,
        afterPoints: null,
        changePoints: null,
        changeReason: null,
        eventType: null,
        eventId: null,
        changeType: null,
        delFlag: null,
        createBy: null,
        createTime: null,
        updateBy: null,
        updateTime: null
      }
      this.resetForm("form")
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length!==1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加用户积分明细"
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getDetail(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改用户积分明细"
      })
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateDetail(this.form).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addDetail(this.form).then(response => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getList()
            })
          }
        }
      })
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids
      this.$modal.confirm('是否确认删除用户积分明细编号为"' + ids + '"的数据项？').then(function() {
        return delDetail(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('business/pointsdetail/export', {
        ...this.queryParams
      }, `points_detail_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

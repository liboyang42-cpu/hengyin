<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="查阅状态" prop="checkStatus">
        <DictRadio v-model="queryParams.checkStatus" @change="handleQuery" size="small"
                   :radioData="dict.type.sys_merchant_status" :showAll="'all'"/>
      </el-form-item>
      <el-form-item label="用户ID" prop="memberId">
        <el-input
          v-model="queryParams.memberId"
          placeholder="请输入用户ID"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="活动名称" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入活动名称"
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
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['business:activity:remove']"
        >删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="activityList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="用户ID" align="center" prop="memberId" />
      <el-table-column label="活动名称" align="center" prop="name" />

      <el-table-column label="活动时间" prop="startDate" width="220">
        <template v-slot="scope">
          <div>开始时间：{{ parseTime(scope.row.startDate, '{yy}-{mm}-{dd} {hh}:{ii}') }}</div>
          <div>结束时间：{{ parseTime(scope.row.endDate, '{yy}-{mm}-{dd} {hh}:{ii}') }}</div>
         </template>
      </el-table-column>
      <el-table-column label="活动描述" align="center" prop="description" width="200" />
        <el-table-column label="活动封面图" align="center" prop="imgUrl" >
          <template slot-scope="scope">
            <el-image class="small-img " :src="scope.row.imgUrl" :preview-src-list="[scope.row.imgUrl]" style="width: 50px;" />
            </template>
        </el-table-column>
         <el-table-column label="轮播图" align="center" prop="imgArr" width="100">
        <template slot-scope="scope">
          <cy-media-preview v-if="scope.row.imgArr" :sources="getImageList(scope.row.imgArr)" label="活动轮播图" />
          <span v-else>无图片</span>
        </template>
      </el-table-column>
      <el-table-column label="场馆名称" align="center" prop="addressName" width="120"/>
      <el-table-column label="地址" align="center" prop="address" width="150"/>
     <el-table-column label="查阅状态" align="center" prop="checkStatus"  width="120">
      <template slot-scope="scope">
        <el-link v-hasPermi="['business:audit:list']" icon="el-icon-view" @click="handleStatus(scope.row)"><dict-tag :options="dict.type.sys_merchant_status" :value="scope.row.checkStatus" /></el-link>
        <div v-if="scope.row.checkStatus == 2">原因：{{ scope.row.reason }}</div>
        </template>
      </el-table-column>

       <el-table-column label="发布状态" align="center" prop="publishStatus"  width="120">
      <template slot-scope="scope">
        <dict-tag :options="dict.type.sys_activity_publish_status" :value="scope.row.publishStatus" />
        </template>
      </el-table-column>

      <el-table-column label="操作" align="center"  fixed="right"  width="150" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="goDetail(scope.row)"
            v-hasPermi="['business:activity:query']"
          >详情</el-button>

          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['business:activity:remove']"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    
    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

      <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="处理类型" prop="status">
          <DictRadio v-model="form.checkStatus" :radioData="dict.type.sys_merchant_status" size="small" :filter="['0','3']"></DictRadio>
        </el-form-item>
        <el-form-item label="原因" prop="reason"  v-if="form.checkStatus == 2">
          <el-input v-model="form.reason" type="textarea" placeholder="请输入驳回原因" :rows="5" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listActivity, getActivity, delActivity, addActivity, updateActivity } from "@/api/business/activity"
import {parseTime} from "../../../utils/ruoyi";
import CyMediaPreview from '@/components/CyMediaPreview'

export default {
  name: "Activity",
  dicts: ['sys_normal_disable',  'sys_show_hide','sys_topic_status','sys_activity_publish_status','sys_merchant_status'],
  components: { CyMediaPreview },
  data() {
    return {
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
      // 活动表格数据
      activityList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        memberId: null,
        name: null,
        description: null,
        imgUrl: null,
        imgArr: null,
        categoryId: null,
        addressName: null,
        longitude: null,
        latitude: null,
        address: null,
        status: null,
        reason: null,
        checkStatus: null, // 新增：查阅状态参数
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
      }
    }
  },
  created() {
    // 修改：在组件创建时解析URL参数
    this.parseUrlParams();
    this.getList();
  },
  methods: {
    /** 解析URL参数 */
    parseUrlParams() {
      // 获取当前URL的查询参数
      const urlParams = new URLSearchParams(window.location.search);
      const checkStatus = urlParams.get('check_status');
      
      if (checkStatus !== null) {
        // 将URL参数设置到查询参数中
        this.queryParams.checkStatus = checkStatus;
      }
    },
    
    parseTime,
    handleStatus(row) {
      this.$router.push({ name: 'Audit', query: { bizType: 'ACTIVITY', bizId: row.id } })
    },
    // 将图片字符串转换为数组
    getImageList(pics) {
      if (!pics) return [];
      // 假设图片用分号;分隔
      return pics.split(';').filter(img => img.trim() !== '');
    },
    
    /** 查询活动列表 */
    getList() {
      this.loading = true;
      
      // 构建查询参数，将checkStatus映射到后端需要的字段
      const params = {
        ...this.queryParams
      };
      
      // 如果checkStatus有值，可能需要映射到后端对应的字段
      if (this.queryParams.checkStatus) {
        // 根据实际后端接口需求，可能需要将checkStatus映射到具体的查询字段
        // 例如：params.status = this.queryParams.checkStatus;
        // 这里假设后端直接接受checkStatus参数
        params.checkStatus = this.queryParams.checkStatus;
      }
      
      listActivity(params).then(response => {
        this.activityList = response.rows;
        this.total = response.total;
        this.loading = false;
      }).catch(() => {
        this.loading = false;
      });
    },
    
    goDetail(row) {
      const id = row.id
      this.$router.push({path: '/activity/detail', query: {id}})
    },
    
    // 取消按钮
    cancel() {
      this.open = false
      this.reset()
    },
    
    // 表单重置
    reset() {
      this.form = {
        id: null,
        memberId: null,
        name: null,
        description: null,
        imgUrl: null,
        imgArr: null,
        categoryId: null,
        addressName: null,
        longitude: null,
        latitude: null,
        address: null,
        status: null,
        reason: null,
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
      // 修改：重置时保留URL传入的checkStatus参数
      // 只重置其他查询条件，保留checkStatus
      this.queryParams.pageNum = 1;
      this.queryParams.memberId = null;
      this.queryParams.name = null;
      // this.queryParams.checkStatus = null; // 注释掉这行，不清除checkStatus
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
      this.title = "添加活动"
    },
    
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getActivity(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改活动"
      })
    },
    
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateActivity(this.form).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addActivity(this.form).then(response => {
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
      this.$modal.confirm('是否确认删除活动编号为"' + ids + '"的数据项？').then(function() {
        return delActivity(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    
    /** 导出按钮操作 */
    handleExport() {
      this.download('business/activity/export', {
        ...this.queryParams
      }, `activity_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style scoped>
/* 方案二的样式 */
.image-preview {
  position: relative;
  display: inline-block;
}

.image-count {
  position: absolute;
  bottom: 0;
  right: 0;
}

/* 方案三的样式 */
.avatar-group {
  display: flex;
  align-items: center;
}

.avatar-group .el-avatar {
  cursor: pointer;
  border: 2px solid #fff;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

/* 图片容器的样式 */
.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cy-legacy-5);
  justify-content: center;
}

.small-img.circle-img {
  border-radius: 50%;
}
</style>

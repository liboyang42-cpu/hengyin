<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="游戏名称" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入游戏名称"
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
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['business:betagame:add']"
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
          v-hasPermi="['business:betagame:edit']"
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
          v-hasPermi="['business:betagame:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['business:betagame:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="betagameList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="游戏名称" align="center" prop="name" />
      <el-table-column label="游戏封面图" align="center" prop="imgUrl">
      <template slot-scope="scope">
        <el-image 
          class="small-img circle-img" 
          :src="getImageUrl(scope.row.imgUrl)" 
          :preview-src-list="[getImageUrl(scope.row.imgUrl)]"
          style="width: 50px;" 
        />
      </template>
    </el-table-column>
      <el-table-column label="类型" align="center" prop="typeName" />
      <el-table-column label="标签" align="center" prop="tags" />
      <el-table-column label="状态" prop="status" width="150" align="center">
      <template slot-scope="{ row }">
        <dict-tag :value="row.status" :options="dict.type.sys_product_publish_status"/>
      </template>
    </el-table-column>
      <el-table-column label="投票开始日期" align="center" prop="startDate" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.startDate, '{yy}-{mm}-{dd} {hh}:{ii}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="投票结束日期" align="center" prop="endDate" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.startDate, '{yy}-{mm}-{dd} {hh}:{ii}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="goVoteList(scope.row.id,scope.row.name)"
            v-hasPermi="['business:betagamevote:list']"
          >查看投票</el-button>

          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['business:betagame:edit']"
          >修改</el-button>


          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['business:betagame:remove']"
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

    <!-- 添加或修改活动对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="游戏名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入游戏名称" />
        </el-form-item>
        <el-form-item label="游戏描述" prop="description">
          <el-input v-model="form.description" type="textarea" placeholder="请输入内容" />
        </el-form-item>
        <el-form-item label="游戏封面图" prop="imgUrl">
          <el-input v-model="form.imgUrl" placeholder="请输入游戏封面图" />
        </el-form-item>
        <el-form-item label="游戏轮播图" prop="imgArr">
          <el-input v-model="form.imgArr" type="textarea" placeholder="请输入内容" />
        </el-form-item>
        <el-form-item label="主题/活动" prop="typeName">
          <el-input v-model="form.typeName" placeholder="请输入主题/活动" />
        </el-form-item>
        <el-form-item label="游戏标签,逗号隔开" prop="tags">
          <el-input v-model="form.tags" placeholder="请输入游戏标签,逗号隔开" />
        </el-form-item>
        <el-form-item label="删除标记 0 代表存在  2删除" prop="delFlag">
          <el-input v-model="form.delFlag" placeholder="请输入删除标记 0 代表存在  2删除" />
        </el-form-item>
        <el-form-item label="投票开始日期" prop="startDate">
          <el-date-picker clearable
            v-model="form.startDate"
            type="date"
            value-format="yyyy-MM-dd"
            placeholder="请选择投票开始日期">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="投票结束日期" prop="endDate">
          <el-date-picker clearable
            v-model="form.endDate"
            type="date"
            value-format="yyyy-MM-dd"
            placeholder="请选择投票结束日期">
          </el-date-picker>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    
    <game-votelist v-if="gameVotelistVisible" ref="gameVotelist" @refreshDataList="getList"></game-votelist>

  </div>
</template>

<script>
import { listBetagame, getBetagame, delBetagame, addBetagame, updateBetagame,updateDelStatus } from "@/api/business/betagame"
import GameVotelist from "@/views/business/betagame/game-votelist.vue";
//import GameVotelist from './game-votelist.vue';
export default {
  components: { GameVotelist },
  name: "Betagame",
  dicts: ['sys_product_publish_status'],
  data() {
    return {
      gameVotelistVisible: false,
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
      betagameList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        name: null,
        description: null,
        imgUrl: null,
        imgArr: null,
        typeName: null,
        tags: null,
        status: null,
        startDate: null,
        endDate: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
      }
    }
  },
  created() {
    this.getList();
    // 监听刷新事件
    this.setupEventListeners();
  },
  // 组件激活时也重新监听（确保从编辑页返回时能收到事件）
  activated() {
    this.setupEventListeners();
  },
 
 // 组件销毁前移除事件监听，避免内存泄漏
  beforeDestroy() {
    this.removeEventListeners();
  },
  
  // 组件停用时也移除监听
  deactivated() {
    this.removeEventListeners();
  },

  methods: {
    // 设置事件监听
    setupEventListeners() {
      console.log('设置事件总线监听');
      this.$bus.$on('betagame-refresh', this.handleRefresh);
      this.$bus.$on('betagame-data-changed', this.handleRefresh);
    },
    
    // 移除事件监听
    removeEventListeners() {
      console.log('移除事件总线监听');
      this.$bus.$off('betagame-refresh', this.handleRefresh);
      this.$bus.$off('betagame-data-changed', this.handleRefresh);
    },
    
    // 处理刷新事件
    handleRefresh() {
      console.log('收到刷新事件，重新加载数据');
      this.getList();
    },
    // 处理图片路径，兼容OSS远程路径和本地相对路径
    getImageUrl(path) {
      // 1. 基础判空：路径为空/undefined/null时，返回默认占位图（避免页面显示破碎图片）
      if (!path || path.trim() === '') {
        console.warn('图片路径为空，使用默认占位图');
        // 可替换为你的项目默认占位图路径（如本地静态资源）
        return process.env.VUE_APP_BASE_API + '/static/images/default-img-placeholder.png';
      }

      // 2. 处理OSS远程路径（http/https开头）
      if (path.startsWith('http://') || path.startsWith('https://')) {
       // console.log('识别为OSS远程路径:', path);
        return path;
      }

      // 3. 处理本地路径：拼接基础API地址，避免双斜杠
      // 安全获取BASE_API（防止环境变量未配置导致的undefined）
      const baseApi = process.env.VUE_APP_BASE_API || '';
      // 统一处理路径前缀：确保baseApi结尾无斜杠时，拼接的路径正确
      const normalizedBaseApi = baseApi.endsWith('/') ? baseApi.slice(0, -1) : baseApi;
      // 拼接最终路径（处理path是否以/开头的两种情况）
      const localImageUrl = `${normalizedBaseApi}/${path.startsWith('/') ? path.slice(1) : path}`;

      // 4. 调试日志：清晰打印路径处理过程（方便定位问题）
      // console.log('图片路径处理详情:', {
      //   原始路径: path,
      //   基础API地址: baseApi,
      //   最终本地路径: localImageUrl
      // });

      return localImageUrl;
    },
    goVoteList(id,name) {
      this.gameVotelistVisible = true

      this.$nextTick(() => {
        this.$refs.gameVotelist.init(id,name)
      })
    },

    /** 查询活动列表 */
    getList() {
      this.loading = true
      listBetagame(this.queryParams).then(response => {
        this.betagameList = response.rows
        this.total = response.total
        this.loading = false
      })
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
        name: null,
        description: null,
        imgUrl: null,
        imgArr: null,
        typeName: null,
        tags: null,
        status: null,
        delFlag: null,
        startDate: null,
        endDate: null,
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
    // /** 新增按钮操作 */
    // handleAdd() {
    //   this.reset()
    //   this.open = true
    //   this.title = "添加活动"
    // },
    // /** 修改按钮操作 */
    // handleUpdate(row) {
    //   this.reset()
    //   const id = row.id || this.ids
    //   getBetagame(id).then(response => {
    //     this.form = response.data
    //     this.open = true
    //     this.title = "修改活动"
    //   })
    // },

      /** 新增按钮操作 */
    handleAdd() {
      this.$router.push({path: '/betagame/edit'});
      
    },
    /** 修改按钮操作 */
    handleUpdate(row,action) {
      this.$router.push({path: '/betagame/edit', query: {id: row.id,action:'edit'}});
    },

    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateBetagame(this.form).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addBetagame(this.form).then(response => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getList()
            })
          }
        }
      })
    },
    /** 删除按钮操作 */
    // handleDelete(row) {
    //   const ids = row.id || this.ids
    //   this.$modal.confirm('是否确认删除活动编号为"' + ids + '"的数据项？').then(function() {
    //     return delBetagame(ids)
    //   }).then(() => {
    //     this.getList()
    //     this.$modal.msgSuccess("删除成功")
    //   }).catch(() => {})
    // },
     /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids;
      // 确保 ids 是数组格式（后端接收的是 Long[] ids）
      const idArray = Array.isArray(ids) ? ids : [ids];
      
      this.$modal.confirm('是否确认删除游戏编号为"' + idArray.join(', ') + '"的数据项？').then(() => {
        return updateDelStatus(idArray); // 调用新的逻辑删除方法
      }).then(() => {
        this.getList(); // 重新获取列表，刷新页面
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('business/betagame/export', {
        ...this.queryParams
      }, `betagame_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

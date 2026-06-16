<template>
  <div class="kb-manager">
    <!-- 头部 -->
    <div class="page-header">
      <h1>
        <DatabaseOutlined />
        知识库管理
      </h1>
      <p>管理 AI 知识库，每个知识库可包含多个文档，文档解析后生成向量分片</p>
    </div>

    <!-- 搜索 + 操作栏 -->
    <div class="toolbar">
      <a-space>
        <a-input-search
          v-model:value="searchText"
          placeholder="搜索知识库名称"
          style="width: 280px"
          allow-clear
          @search="loadData"
          @pressEnter="loadData"
        />
        <a-select
          v-model:value="statusFilter"
          placeholder="状态"
          style="width: 120px"
          allow-clear
          @change="loadData"
        >
          <a-select-option value="active">启用</a-select-option>
          <a-select-option value="inactive">停用</a-select-option>
        </a-select>
      </a-space>
      <a-button type="primary" @click="handleAdd">
        <PlusOutlined /> 新建知识库
      </a-button>
    </div>

    <!-- 知识库列表 -->
    <a-table
      :columns="columns"
      :data-source="dataList"
      :loading="loading"
      :pagination="pagination"
      row-key="id"
      size="middle"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <a @click="handleEdit(record)">{{ record.name }}</a>
        </template>
        <template v-if="column.key === 'status'">
          <a-badge
            :status="record.status === 'active' ? 'success' : 'default'"
            :text="record.status === 'active' ? '启用' : '停用'"
          />
        </template>
        <template v-if="column.key === 'docCount'">
          <a-tag color="blue">{{ record.docCount ?? 0 }} 篇</a-tag>
        </template>
        <template v-if="column.key === 'chunkCount'">
          <a-tag color="green">{{ record.chunkCount ?? 0 }} 片</a-tag>
        </template>
        <template v-if="column.key === 'createTime'">
          {{ record.createTime || '-' }}
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="handleEdit(record)">编辑</a-button>
            <a-popconfirm
              :title="`确定删除知识库「${record.name}」？\n将同时删除其下所有文档和分片，不可恢复！`"
              ok-text="删除"
              cancel-text="取消"
              ok-type="danger"
              @confirm="handleDelete(record.id)"
            >
              <a-button type="link" size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:visible="modalVisible"
      :title="isEdit ? '编辑知识库' : '新建知识库'"
      :confirm-loading="submitLoading"
      @ok="handleSubmit"
      @cancel="resetForm"
      :width="520"
    >
      <a-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        layout="vertical"
        style="margin-top: 16px"
      >
        <a-form-item label="知识库名称" name="name">
          <a-input v-model:value="formData.name" placeholder="请输入知识库名称" :maxlength="100" />
        </a-form-item>
        <a-form-item label="描述" name="description">
          <a-textarea
            v-model:value="formData.description"
            placeholder="请输入知识库描述（可选）"
            :rows="3"
            :maxlength="500"
            show-count
          />
        </a-form-item>
        <a-form-item label="状态" name="status">
          <a-radio-group v-model:value="formData.status">
            <a-radio value="active">启用</a-radio>
            <a-radio value="inactive">停用</a-radio>
          </a-radio-group>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { DatabaseOutlined, PlusOutlined } from '@ant-design/icons-vue';
import { message as antMessage } from 'ant-design-vue';
import type { FormInstance } from 'ant-design-vue';
import { defHttp } from '/@/utils/http/axios';

// ==================== 类型定义 ====================

interface KnowledgeBase {
  id: string;
  name: string;
  description: string;
  embedModelId: string;
  status: string;
  docCount: number;
  chunkCount: number;
  metadata: string;
  createTime: string;
  updateTime: string;
}

// ==================== 状态 ====================

const API_BASE = '/practice/kb';

const loading = ref(false);
const dataList = ref<KnowledgeBase[]>([]);
const searchText = ref('');
const statusFilter = ref<string | undefined>(undefined);

const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: (total: number) => `共 ${total} 条`,
});

// 弹窗
const modalVisible = ref(false);
const submitLoading = ref(false);
const isEdit = ref(false);
const formRef = ref<FormInstance>();

const formData = reactive({
  id: '',
  name: '',
  description: '',
  status: 'active',
});

const formRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
};

// ==================== 表格列 ====================

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 200 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '状态', key: 'status', width: 90 },
  { title: '文档数', key: 'docCount', width: 100, align: 'center' as const },
  { title: '分片数', key: 'chunkCount', width: 100, align: 'center' as const },
  { title: '创建时间', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 140, fixed: 'right' as const },
];

// ==================== 数据加载 ====================

async function loadData() {
  loading.value = true;
  try {
    const params: Record<string, any> = {
      pageNo: pagination.current,
      pageSize: pagination.pageSize,
    };
    if (searchText.value) {
      params['name'] = `*${searchText.value}*`;
    }
    if (statusFilter.value) {
      params['status'] = statusFilter.value;
    }
    const res = await defHttp.get({ url: `${API_BASE}/list`, params });
    dataList.value = res.records || [];
    pagination.total = res.total || 0;
  } catch (e) {
    console.error('加载知识库列表失败', e);
  } finally {
    loading.value = false;
  }
}

function handleTableChange(pag: any) {
  pagination.current = pag.current;
  pagination.pageSize = pag.pageSize;
  loadData();
}

// ==================== 新增 / 编辑 ====================

function handleAdd() {
  isEdit.value = false;
  resetForm();
  modalVisible.value = true;
}

function handleEdit(record: KnowledgeBase) {
  isEdit.value = true;
  formData.id = record.id;
  formData.name = record.name;
  formData.description = record.description || '';
  formData.status = record.status || 'active';
  modalVisible.value = true;
}

async function handleSubmit() {
  try {
    await formRef.value?.validateFields();
  } catch {
    return;
  }

  submitLoading.value = true;
  try {
    if (isEdit.value) {
      await defHttp.put({
        url: `${API_BASE}/edit`,
        params: { id: formData.id, name: formData.name, description: formData.description, status: formData.status },
      });
      antMessage.success('修改成功');
    } else {
      await defHttp.post({
        url: `${API_BASE}/add`,
        params: { name: formData.name, description: formData.description, status: formData.status },
      });
      antMessage.success('新建成功');
    }
    modalVisible.value = false;
    resetForm();
    loadData();
  } catch (e: any) {
    antMessage.error(e?.message || '操作失败');
  } finally {
    submitLoading.value = false;
  }
}

function resetForm() {
  formData.id = '';
  formData.name = '';
  formData.description = '';
  formData.status = 'active';
  formRef.value?.resetFields();
}

// ==================== 删除 ====================

async function handleDelete(id: string) {
  try {
    await defHttp.delete({ url: `${API_BASE}/delete`, params: { ids: id } }, { joinParamsToUrl: true });
    antMessage.success('删除成功');
    loadData();
  } catch (e: any) {
    antMessage.error(e?.message || '删除失败');
  }
}

// ==================== 初始化 ====================

onMounted(() => {
  loadData();
});
</script>

<style scoped lang="less">
.kb-manager {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;

  h1 {
    font-size: 22px;
    font-weight: 600;
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 0 6px 0;
  }

  p {
    color: #8c8c8c;
    font-size: 14px;
    margin: 0;
  }
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>

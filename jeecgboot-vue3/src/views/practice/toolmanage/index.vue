<template>
  <div class="tool-manage-page">
    <a-tabs v-model:activeKey="activeTab">
      <!-- ==================== Tab 1: 工具定义 ==================== -->
      <a-tab-pane key="definition" tab="工具定义">
        <a-card :bordered="false">
          <!-- 操作栏 -->
          <div class="table-toolbar">
            <a-space>
              <a-button type="primary" @click="handleAdd">
                <template #icon><PlusOutlined /></template>
                新增
              </a-button>
              <a-button @click="loadDefinitions">
                <template #icon><ReloadOutlined /></template>
                刷新
              </a-button>
              <a-tag color="blue">共 {{ defPagination.total }} 个工具</a-tag>
              <a-tag color="green">启用 {{ definitionData.filter(d => d.status === 'active').length }} 个</a-tag>
            </a-space>
          </div>

          <a-table
            :columns="defColumns"
            :data-source="definitionData"
            :loading="defLoading"
            :pagination="defPagination"
            row-key="id"
            size="middle"
            @change="onDefTableChange"
            :expandedRowKeys="expandedKeys"
            @expand="onExpand"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'status'">
                <a-tag :color="record.status === 'active' ? 'green' : 'default'">
                  {{ record.status === 'active' ? '启用' : '禁用' }}
                </a-tag>
              </template>
              <template v-if="column.dataIndex === 'category'">
                <a-tag :color="categoryColor(record.category)">{{ record.category }}</a-tag>
              </template>
              <template v-if="column.dataIndex === 'endpointType'">
                <a-tag>{{ record.endpointType }}</a-tag>
              </template>
              <template v-if="column.dataIndex === 'isReadOnly'">
                <a-tag :color="record.isReadOnly ? 'blue' : 'orange'">
                  {{ record.isReadOnly ? '只读' : '写操作' }}
                </a-tag>
              </template>
              <template v-if="column.dataIndex === 'requireConfirm'">
                <a-tag v-if="record.requireConfirm" color="red">需确认</a-tag>
                <span v-else style="color: #999">—</span>
              </template>
              <template v-if="column.dataIndex === 'action'">
                <a-space>
                  <a-button type="link" size="small" @click="handleEdit(record)">编辑</a-button>
                  <a-popconfirm
                    title="确定删除这个工具定义？"
                    ok-text="确定"
                    cancel-text="取消"
                    @confirm="handleDelete(record.id)"
                  >
                    <a-button type="link" size="small" danger>删除</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </template>

            <!-- 展开行 -->
            <template #expandedRowRender="{ record }">
              <div class="expand-detail">
                <p><strong>工具描述：</strong>{{ record.description }}</p>
                <p><strong>处理器引用：</strong><code>{{ record.handlerRef }}</code></p>
                <p v-if="record.parametersSchema">
                  <strong>参数 Schema：</strong>
                </p>
                <pre v-if="record.parametersSchema" class="schema-pre">{{ formatJson(record.parametersSchema) }}</pre>
              </div>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>

      <!-- ==================== Tab 2: 调用日志 ==================== -->
      <a-tab-pane key="log" tab="调用日志">
        <a-card :bordered="false">
          <div class="table-toolbar">
            <a-space>
              <a-select
                v-model:value="logFilter.toolCode"
                placeholder="按工具筛选"
                allow-clear
                style="width: 200px"
                @change="loadLogs"
              >
                <a-select-option v-for="d in definitionData" :key="d.toolCode" :value="d.toolCode">
                  {{ d.toolName }} ({{ d.toolCode }})
                </a-select-option>
              </a-select>
              <a-select
                v-model:value="logFilter.status"
                placeholder="按状态筛选"
                allow-clear
                style="width: 140px"
                @change="loadLogs"
              >
                <a-select-option value="success">成功</a-select-option>
                <a-select-option value="error">失败</a-select-option>
                <a-select-option value="timeout">超时</a-select-option>
              </a-select>
              <a-button type="primary" @click="loadLogs">
                <template #icon><SearchOutlined /></template>
                查询
              </a-button>
              <a-button @click="resetLogFilter">重置</a-button>
            </a-space>
          </div>

          <a-table
            :columns="logColumns"
            :data-source="logData"
            :loading="logLoading"
            :pagination="logPagination"
            row-key="id"
            size="middle"
            @change="onLogTableChange"
            :expandedRowKeys="logExpandedKeys"
            @expand="onLogExpand"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'status'">
                <a-tag :color="logStatusColor(record.status)">{{ logStatusText(record.status) }}</a-tag>
              </template>
              <template v-if="column.dataIndex === 'durationMs'">
                {{ record.durationMs }}ms
              </template>
            </template>

            <template #expandedRowRender="{ record }">
              <div class="expand-detail">
                <div class="detail-section">
                  <strong>调用入参：</strong>
                  <pre class="schema-pre">{{ formatJson(record.inputParams) }}</pre>
                </div>
                <div class="detail-section">
                  <strong>执行结果：</strong>
                  <pre class="schema-pre">{{ formatJson(record.outputResult) }}</pre>
                </div>
                <div v-if="record.errorMsg" class="detail-section">
                  <strong style="color: #ff4d4f">错误信息：</strong>
                  <span style="color: #ff4d4f">{{ record.errorMsg }}</span>
                </div>
              </div>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>
    </a-tabs>

    <!-- ==================== 新增 / 编辑弹窗 ==================== -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEdit ? '编辑工具定义' : '新增工具定义'"
      width="680px"
      :confirm-loading="modalSubmitting"
      @ok="handleSubmit"
      @cancel="handleCancel"
      ok-text="保存"
      cancel-text="取消"
    >
      <a-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        layout="vertical"
        style="margin-top: 16px"
      >
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="工具编码" name="toolCode">
              <a-input v-model:value="formData.toolCode" placeholder="如 queryOrder" :disabled="isEdit" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="工具名称" name="toolName">
              <a-input v-model:value="formData.toolName" placeholder="如 查询订单" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="工具描述" name="description">
          <a-textarea v-model:value="formData.description" :rows="3" placeholder="发送给模型的描述，帮助它决定何时调用此工具" />
        </a-form-item>

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="分类" name="category">
              <a-select v-model:value="formData.category" placeholder="选择分类">
                <a-select-option value="query">query（查询）</a-select-option>
                <a-select-option value="write">write（写入）</a-select-option>
                <a-select-option value="notify">notify（通知）</a-select-option>
                <a-select-option value="system">system（系统）</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="端点类型" name="endpointType">
              <a-select v-model:value="formData.endpointType" placeholder="选择类型">
                <a-select-option value="JAVA_BEAN">JAVA_BEAN</a-select-option>
                <a-select-option value="REST_API">REST_API</a-select-option>
                <a-select-option value="SQL_QUERY">SQL_QUERY</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="处理器引用" name="handlerRef">
              <a-input v-model:value="formData.handlerRef" placeholder="Bean 名，如 orderToolHandler" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="状态" name="status">
              <a-select v-model:value="formData.status">
                <a-select-option value="active">启用</a-select-option>
                <a-select-option value="inactive">禁用</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="超时(ms)" name="timeoutMs">
              <a-input-number v-model:value="formData.timeoutMs" :min="100" :max="60000" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="排序号" name="sortOrder">
              <a-input-number v-model:value="formData.sortOrder" :min="0" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="只读操作">
              <a-switch v-model:checked="formReadOnly" checked-children="是" un-checked-children="否" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="需用户确认">
              <a-switch v-model:checked="formRequireConfirm" checked-children="是" un-checked-children="否" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="参数 Schema（JSON）" name="parametersSchema">
          <a-textarea
            v-model:value="formData.parametersSchema"
            :rows="6"
            placeholder='如 {"type":"object","properties":{"orderCode":{"type":"string","description":"订单编号"}},"required":["orderCode"]}'
            style="font-family: monospace; font-size: 12px"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { message } from 'ant-design-vue';
import { ReloadOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons-vue';
import { defHttp } from '/@/utils/http/axios';

const API_BASE = '/practice/tool';

// ==================== Tab 控制 ====================
const activeTab = ref('definition');

// ==================== 工具定义 ====================
const defLoading = ref(false);
const definitionData = ref<any[]>([]);
const expandedKeys = ref<string[]>([]);
const defPagination = reactive({ current: 1, pageSize: 10, total: 0 });

const defColumns = [
  { title: '工具编码', dataIndex: 'toolCode', width: 140 },
  { title: '工具名称', dataIndex: 'toolName', width: 120 },
  { title: '分类', dataIndex: 'category', width: 80 },
  { title: '端点类型', dataIndex: 'endpointType', width: 110 },
  { title: '读写', dataIndex: 'isReadOnly', width: 70 },
  { title: '需确认', dataIndex: 'requireConfirm', width: 80 },
  { title: '状态', dataIndex: 'status', width: 70 },
  { title: '超时(ms)', dataIndex: 'timeoutMs', width: 90 },
  { title: '操作', dataIndex: 'action', width: 130, fixed: 'right' as const },
];

async function loadDefinitions() {
  defLoading.value = true;
  try {
    const res = await defHttp.get({
      url: `${API_BASE}/definition/list`,
      params: { pageNo: defPagination.current, pageSize: defPagination.pageSize },
    });
    definitionData.value = res.records || [];
    defPagination.total = res.total || 0;
  } catch (e) {
    console.error('加载工具定义失败', e);
  } finally {
    defLoading.value = false;
  }
}

function onDefTableChange(pag: any) {
  defPagination.current = pag.current;
  defPagination.pageSize = pag.pageSize;
  loadDefinitions();
}

function onExpand(expanded: boolean, record: any) {
  expandedKeys.value = expanded ? [record.id] : [];
}

function categoryColor(cat: string) {
  const map: Record<string, string> = { query: 'cyan', write: 'orange', notify: 'purple', system: 'default' };
  return map[cat] || 'default';
}

// ==================== 弹窗：新增 / 编辑 ====================
const modalVisible = ref(false);
const modalSubmitting = ref(false);
const isEdit = ref(false);
const formRef = ref();

const defaultFormData = {
  id: '',
  toolCode: '',
  toolName: '',
  description: '',
  parametersSchema: '',
  endpointType: 'JAVA_BEAN',
  handlerRef: '',
  category: 'query',
  status: 'active',
  isReadOnly: 1,
  timeoutMs: 5000,
  requireConfirm: 0,
  sortOrder: 0,
};

const formData = reactive({ ...defaultFormData });

// Switch 组件需要 boolean，用 computed 做双向转换
const formReadOnly = computed({
  get: () => formData.isReadOnly === 1,
  set: (v: boolean) => { formData.isReadOnly = v ? 1 : 0; },
});
const formRequireConfirm = computed({
  get: () => formData.requireConfirm === 1,
  set: (v: boolean) => { formData.requireConfirm = v ? 1 : 0; },
});

const formRules = {
  toolCode: [{ required: true, message: '请输入工具编码', trigger: 'blur' }],
  toolName: [{ required: true, message: '请输入工具名称', trigger: 'blur' }],
  description: [{ required: true, message: '请输入工具描述', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
  endpointType: [{ required: true, message: '请选择端点类型', trigger: 'change' }],
};

function handleAdd() {
  isEdit.value = false;
  Object.assign(formData, { ...defaultFormData });
  modalVisible.value = true;
}

function handleEdit(record: any) {
  isEdit.value = true;
  Object.assign(formData, {
    id: record.id,
    toolCode: record.toolCode,
    toolName: record.toolName,
    description: record.description || '',
    parametersSchema: record.parametersSchema || '',
    endpointType: record.endpointType || 'JAVA_BEAN',
    handlerRef: record.handlerRef || '',
    category: record.category || 'query',
    status: record.status || 'active',
    isReadOnly: record.isReadOnly ?? 1,
    timeoutMs: record.timeoutMs ?? 5000,
    requireConfirm: record.requireConfirm ?? 0,
    sortOrder: record.sortOrder ?? 0,
  });
  modalVisible.value = true;
}

async function handleSubmit() {
  try {
    await formRef.value?.validateFields();
  } catch {
    return; // 校验不通过
  }

  modalSubmitting.value = true;
  try {
    if (isEdit.value) {
      await defHttp.put({ url: `${API_BASE}/definition/edit`, data: { ...formData } });
      message.success('修改成功');
    } else {
      await defHttp.post({ url: `${API_BASE}/definition/add`, data: { ...formData } });
      message.success('添加成功');
    }
    modalVisible.value = false;
    loadDefinitions();
  } catch (e) {
    message.error(isEdit.value ? '修改失败' : '添加失败');
    console.error(e);
  } finally {
    modalSubmitting.value = false;
  }
}

function handleCancel() {
  modalVisible.value = false;
}

async function handleDelete(id: string) {
  try {
    await defHttp.delete({ url: `${API_BASE}/definition/delete`, params: { id } });
    message.success('删除成功');
    loadDefinitions();
  } catch (e) {
    message.error('删除失败');
    console.error(e);
  }
}

// ==================== 调用日志 ====================
const logLoading = ref(false);
const logData = ref<any[]>([]);
const logExpandedKeys = ref<string[]>([]);
const logPagination = reactive({ current: 1, pageSize: 10, total: 0 });
const logFilter = reactive<Record<string, any>>({ toolCode: undefined, status: undefined });

const logColumns = [
  { title: '调用时间', dataIndex: 'createTime', width: 180 },
  { title: '工具编码', dataIndex: 'toolCode', width: 140 },
  { title: '工具名称', dataIndex: 'toolName', width: 130 },
  { title: '状态', dataIndex: 'status', width: 80 },
  { title: '耗时', dataIndex: 'durationMs', width: 100 },
  { title: '模型', dataIndex: 'modelName', width: 150 },
  { title: '会话ID', dataIndex: 'sessionId', width: 140, ellipsis: true },
];

async function loadLogs() {
  logLoading.value = true;
  try {
    const params: any = {
      pageNo: logPagination.current,
      pageSize: logPagination.pageSize,
    };
    if (logFilter.toolCode) params.toolCode = logFilter.toolCode;
    if (logFilter.status) params.status = logFilter.status;
    const res = await defHttp.get({ url: `${API_BASE}/log/list`, params });
    logData.value = res.records || [];
    logPagination.total = res.total || 0;
  } catch (e) {
    console.error('加载调用日志失败', e);
  } finally {
    logLoading.value = false;
  }
}

function onLogTableChange(pag: any) {
  logPagination.current = pag.current;
  logPagination.pageSize = pag.pageSize;
  loadLogs();
}

function onLogExpand(expanded: boolean, record: any) {
  logExpandedKeys.value = expanded ? [record.id] : [];
}

function resetLogFilter() {
  logFilter.toolCode = undefined;
  logFilter.status = undefined;
  logPagination.current = 1;
  loadLogs();
}

function logStatusColor(s: string) {
  return s === 'success' ? 'green' : s === 'error' ? 'red' : 'orange';
}
function logStatusText(s: string) {
  return s === 'success' ? '成功' : s === 'error' ? '失败' : s === 'timeout' ? '超时' : s;
}

// ==================== 工具函数 ====================
function formatJson(str: string | null | undefined): string {
  if (!str) return '（空）';
  try {
    return JSON.stringify(JSON.parse(str), null, 2);
  } catch {
    return str;
  }
}

onMounted(() => {
  loadDefinitions();
  loadLogs();
});
</script>

<style scoped lang="less">
.tool-manage-page {
  padding: 16px;
}

.table-toolbar {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16px;
}

.expand-detail {
  padding: 8px 16px;

  p {
    margin-bottom: 8px;
  }
}

.detail-section {
  margin-bottom: 12px;
}

.schema-pre {
  background: #f5f5f5;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 8px 12px;
  font-size: 12px;
  max-height: 300px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>

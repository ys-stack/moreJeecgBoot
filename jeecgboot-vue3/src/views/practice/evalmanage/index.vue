<template>
  <div class="eval-manage-page">
    <div class="page-header">
      <h1>
        <ExperimentOutlined />
        AI评测管理
      </h1>
      <p>维护 RAG / Agent 评测集，查看每次评测运行结果，为 Prompt 优化提供可对比数据</p>
    </div>

    <a-tabs v-model:activeKey="activeTab">
      <a-tab-pane key="dataset" tab="评测集">
        <div class="toolbar">
          <a-space wrap>
            <a-input
              v-model:value="datasetQuery.caseCode"
              placeholder="用例编码"
              allow-clear
              style="width: 160px"
              @pressEnter="loadDataset"
            />
            <a-select
              v-model:value="datasetQuery.evalType"
              placeholder="评测类型"
              allow-clear
              style="width: 140px"
              @change="loadDataset"
            >
              <a-select-option value="rag">RAG</a-select-option>
              <a-select-option value="agent">Agent</a-select-option>
            </a-select>
            <a-select
              v-model:value="datasetQuery.status"
              placeholder="状态"
              allow-clear
              style="width: 120px"
              @change="loadDataset"
            >
              <a-select-option :value="1">启用</a-select-option>
              <a-select-option :value="0">禁用</a-select-option>
            </a-select>
            <a-button type="primary" @click="loadDataset">
              <SearchOutlined />
              查询
            </a-button>
            <a-button @click="resetDatasetQuery">重置</a-button>
          </a-space>
          <a-space>
            <a-button @click="loadDataset">
              <ReloadOutlined />
              刷新
            </a-button>
            <a-button type="primary" @click="handleAddDataset">
              <PlusOutlined />
              新增用例
            </a-button>
          </a-space>
        </div>

        <a-table
          :columns="datasetColumns"
          :data-source="datasetList"
          :loading="datasetLoading"
          :pagination="datasetPagination"
          row-key="id"
          size="middle"
          @change="handleDatasetTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'caseCode'">
              <a @click="handleEditDataset(record)">{{ record.caseCode }}</a>
            </template>
            <template v-else-if="column.key === 'evalType'">
              <a-tag :color="record.evalType === 'rag' ? 'blue' : 'purple'">{{ typeText(record.evalType) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'scenario'">
              <a-tag v-if="record.scenario">{{ record.scenario }}</a-tag>
              <span v-else>-</span>
            </template>
            <template v-else-if="column.key === 'expectedReject'">
              <a-tag :color="record.expectedReject ? 'orange' : 'green'">{{ record.expectedReject ? '应拒答' : '应回答' }}</a-tag>
            </template>
            <template v-else-if="column.key === 'status'">
              <a-badge :status="record.status === 1 ? 'success' : 'default'" :text="record.status === 1 ? '启用' : '禁用'" />
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="handleEditDataset(record)">编辑</a-button>
                <a-popconfirm title="确定删除这个评测用例？" ok-text="删除" cancel-text="取消" @confirm="handleDeleteDataset(record.id)">
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="result" tab="评测结果">
        <div class="toolbar">
          <a-space wrap>
            <a-input
              v-model:value="resultQuery.runId"
              placeholder="runId"
              allow-clear
              style="width: 220px"
              @pressEnter="loadResults"
            />
            <a-input
              v-model:value="resultQuery.caseCode"
              placeholder="用例编码"
              allow-clear
              style="width: 160px"
              @pressEnter="loadResults"
            />
            <a-select
              v-model:value="resultQuery.evalType"
              placeholder="评测类型"
              allow-clear
              style="width: 140px"
              @change="loadResults"
            >
              <a-select-option value="rag">RAG</a-select-option>
              <a-select-option value="agent">Agent</a-select-option>
            </a-select>
            <a-select
              v-model:value="resultQuery.status"
              placeholder="执行状态"
              allow-clear
              style="width: 140px"
              @change="loadResults"
            >
              <a-select-option value="success">成功</a-select-option>
              <a-select-option value="fail">未通过</a-select-option>
              <a-select-option value="error">异常</a-select-option>
              <a-select-option value="skipped">跳过</a-select-option>
            </a-select>
            <a-button type="primary" @click="loadResults">
              <SearchOutlined />
              查询
            </a-button>
            <a-button @click="resetResultQuery">重置</a-button>
          </a-space>
          <a-space>
            <a-button @click="loadResults">
              <ReloadOutlined />
              刷新
            </a-button>
            <a-popconfirm
              v-if="resultQuery.runId"
              title="确定删除当前runId下的所有评测结果？"
              ok-text="删除"
              cancel-text="取消"
              @confirm="handleDeleteRun"
            >
              <a-button danger>
                <DeleteOutlined />
                清理本次运行
              </a-button>
            </a-popconfirm>
          </a-space>
        </div>

        <a-table
          :columns="resultColumns"
          :data-source="resultList"
          :loading="resultLoading"
          :pagination="resultPagination"
          row-key="id"
          size="middle"
          @change="handleResultTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'caseCode'">
              <a @click="handleViewResult(record)">{{ record.caseCode }}</a>
            </template>
            <template v-else-if="column.key === 'evalType'">
              <a-tag :color="record.evalType === 'rag' ? 'blue' : 'purple'">{{ typeText(record.evalType) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'totalScore'">
              <a-tag :color="scoreColor(record.totalScore)">{{ scoreText(record.totalScore) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'passed'">
              <a-tag :color="record.passed === 1 ? 'green' : 'red'">{{ record.passed === 1 ? '通过' : '未通过' }}</a-tag>
            </template>
            <template v-else-if="column.key === 'status'">
              <a-tag :color="statusColor(record.status)">{{ statusText(record.status) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'durationMs'">
              {{ record.durationMs ?? 0 }}ms
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="handleViewResult(record)">详情</a-button>
                <a-popconfirm title="确定删除这条评测结果？" ok-text="删除" cancel-text="取消" @confirm="handleDeleteResult(record.id)">
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      v-model:open="datasetModalOpen"
      :title="datasetEditing ? '编辑评测用例' : '新增评测用例'"
      width="860px"
      :confirm-loading="datasetSubmitting"
      ok-text="保存"
      cancel-text="取消"
      @ok="submitDataset"
      @cancel="closeDatasetModal"
    >
      <a-form ref="datasetFormRef" :model="datasetForm" :rules="datasetRules" layout="vertical">
        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="用例编码" name="caseCode">
              <a-input v-model:value="datasetForm.caseCode" placeholder="RAG_001 / AGENT_001" :disabled="datasetEditing" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="用例名称" name="caseName">
              <a-input v-model:value="datasetForm.caseName" placeholder="请输入用例名称" />
            </a-form-item>
          </a-col>
          <a-col :span="4">
            <a-form-item label="评测类型" name="evalType">
              <a-select v-model:value="datasetForm.evalType">
                <a-select-option value="rag">RAG</a-select-option>
                <a-select-option value="agent">Agent</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="4">
            <a-form-item label="状态" name="status">
              <a-select v-model:value="datasetForm.status">
                <a-select-option :value="1">启用</a-select-option>
                <a-select-option :value="0">禁用</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="业务场景" name="scenario">
              <a-input v-model:value="datasetForm.scenario" placeholder="qa / refusal / order / ticket" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="难度" name="difficulty">
              <a-select v-model:value="datasetForm.difficulty">
                <a-select-option value="easy">easy</a-select-option>
                <a-select-option value="normal">normal</a-select-option>
                <a-select-option value="hard">hard</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="权重" name="weight">
              <a-input-number v-model:value="datasetForm.weight" :min="0.1" :step="0.1" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="用户问题" name="question">
          <a-textarea v-model:value="datasetForm.question" :rows="3" placeholder="请输入评测时发送给模型的问题" />
        </a-form-item>

        <template v-if="datasetForm.evalType === 'rag'">
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="知识库ID" name="knowledgeBaseId">
                <a-input v-model:value="datasetForm.knowledgeBaseId" placeholder="可为空，表示使用可访问知识库" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="是否预期拒答" name="expectedReject">
                <a-switch v-model:checked="expectedRejectChecked" checked-children="应拒答" un-checked-children="应回答" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="预期答案要点" name="expectedAnswer">
            <a-textarea v-model:value="datasetForm.expectedAnswer" :rows="3" placeholder="人工标准答案或关键要点" />
          </a-form-item>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="预期关键词JSON" name="expectedKeywords">
                <a-textarea v-model:value="datasetForm.expectedKeywords" :rows="4" placeholder='["关键词1","关键词2"]' />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="预期引用JSON" name="expectedReferences">
                <a-textarea v-model:value="datasetForm.expectedReferences" :rows="4" placeholder='["chunkId或文件名"]' />
              </a-form-item>
            </a-col>
          </a-row>
        </template>

        <template v-else>
          <a-row :gutter="16">
            <a-col :span="8">
              <a-form-item label="预期工具编码" name="expectedToolName">
                <a-input v-model:value="datasetForm.expectedToolName" placeholder="queryOrder / queryUser / createTicket" />
              </a-form-item>
            </a-col>
            <a-col :span="16">
              <a-form-item label="预期任务结果" name="expectedTaskResult">
                <a-input v-model:value="datasetForm.expectedTaskResult" placeholder="例如：返回订单状态、创建工单成功" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="预期工具参数JSON" name="expectedToolParams">
            <a-textarea v-model:value="datasetForm.expectedToolParams" :rows="5" placeholder='{"orderCode":"B100"}' />
          </a-form-item>
        </template>

        <a-form-item label="备注" name="remark">
          <a-textarea v-model:value="datasetForm.remark" :rows="2" placeholder="可记录用例设计说明" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-drawer v-model:open="resultDrawerOpen" title="评测结果详情" width="720px">
      <template v-if="currentResult">
        <a-descriptions bordered size="small" :column="2">
          <a-descriptions-item label="runId" :span="2">{{ currentResult.runId }}</a-descriptions-item>
          <a-descriptions-item label="用例编码">{{ currentResult.caseCode }}</a-descriptions-item>
          <a-descriptions-item label="类型">{{ typeText(currentResult.evalType) }}</a-descriptions-item>
          <a-descriptions-item label="综合得分">{{ scoreText(currentResult.totalScore) }}</a-descriptions-item>
          <a-descriptions-item label="是否通过">{{ currentResult.passed === 1 ? '通过' : '未通过' }}</a-descriptions-item>
          <a-descriptions-item label="模型">{{ currentResult.modelName || '-' }}</a-descriptions-item>
          <a-descriptions-item label="耗时">{{ currentResult.durationMs ?? 0 }}ms</a-descriptions-item>
        </a-descriptions>

        <div class="detail-block">
          <div class="detail-title">问题</div>
          <pre>{{ currentResult.question || '-' }}</pre>
        </div>
        <div class="detail-block">
          <div class="detail-title">实际回答</div>
          <pre>{{ currentResult.actualAnswer || '-' }}</pre>
        </div>
        <div class="detail-block" v-if="currentResult.actualReferences">
          <div class="detail-title">实际引用</div>
          <pre>{{ formatJson(currentResult.actualReferences) }}</pre>
        </div>
        <div class="detail-block" v-if="currentResult.actualToolCalls">
          <div class="detail-title">工具调用</div>
          <pre>{{ formatJson(currentResult.actualToolCalls) }}</pre>
        </div>
        <div class="detail-block" v-if="currentResult.judgeDetail">
          <div class="detail-title">评分明细</div>
          <pre>{{ formatJson(currentResult.judgeDetail) }}</pre>
        </div>
        <div class="detail-block" v-if="currentResult.errorMsg">
          <div class="detail-title danger">错误信息</div>
          <pre>{{ currentResult.errorMsg }}</pre>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import type { FormInstance } from 'ant-design-vue';
import { message as antMessage } from 'ant-design-vue';
import {
  DeleteOutlined,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons-vue';
import { defHttp } from '/@/utils/http/axios';

interface EvalDataset {
  id?: string;
  caseCode: string;
  caseName: string;
  evalType: 'rag' | 'agent';
  scenario?: string;
  question: string;
  knowledgeBaseId?: string;
  expectedAnswer?: string;
  expectedKeywords?: string;
  expectedReferences?: string;
  expectedReject?: number;
  expectedToolName?: string;
  expectedToolParams?: string;
  expectedTaskResult?: string;
  difficulty?: string;
  weight?: number;
  status?: number;
  remark?: string;
}

interface EvalResult {
  id: string;
  runId: string;
  runName?: string;
  datasetId: string;
  caseCode: string;
  evalType: 'rag' | 'agent';
  promptCode?: string;
  promptVersion?: number;
  modelProvider?: string;
  modelName?: string;
  question?: string;
  actualAnswer?: string;
  actualReferences?: string;
  actualToolCalls?: string;
  rawResponse?: string;
  answerRelevanceScore?: number;
  referenceHitScore?: number;
  rejectScore?: number;
  toolSelectionScore?: number;
  paramAccuracyScore?: number;
  taskCompletionScore?: number;
  totalScore?: number;
  passed?: number;
  durationMs?: number;
  status?: string;
  errorMsg?: string;
  judgeDetail?: string;
  createTime?: string;
}

const API_BASE = '/practice/eval';
const activeTab = ref('dataset');

const datasetLoading = ref(false);
const datasetList = ref<EvalDataset[]>([]);
const datasetQuery = reactive({
  caseCode: '',
  evalType: undefined as string | undefined,
  status: undefined as number | undefined,
});
const datasetPagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: (total: number) => `共 ${total} 条`,
});

const resultLoading = ref(false);
const resultList = ref<EvalResult[]>([]);
const resultQuery = reactive({
  runId: '',
  caseCode: '',
  evalType: undefined as string | undefined,
  status: undefined as string | undefined,
});
const resultPagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: (total: number) => `共 ${total} 条`,
});

const datasetModalOpen = ref(false);
const datasetSubmitting = ref(false);
const datasetEditing = ref(false);
const datasetFormRef = ref<FormInstance>();
const datasetForm = reactive<EvalDataset>(defaultDatasetForm());
const resultDrawerOpen = ref(false);
const currentResult = ref<EvalResult | null>(null);

const expectedRejectChecked = computed({
  get: () => datasetForm.expectedReject === 1,
  set: (value: boolean) => {
    datasetForm.expectedReject = value ? 1 : 0;
  },
});

const datasetRules = {
  caseCode: [{ required: true, message: '请输入用例编码', trigger: 'blur' }],
  caseName: [{ required: true, message: '请输入用例名称', trigger: 'blur' }],
  evalType: [{ required: true, message: '请选择评测类型', trigger: 'change' }],
  question: [{ required: true, message: '请输入用户问题', trigger: 'blur' }],
};

const datasetColumns = [
  { title: '用例编码', dataIndex: 'caseCode', key: 'caseCode', width: 140 },
  { title: '名称', dataIndex: 'caseName', key: 'caseName', ellipsis: true },
  { title: '类型', dataIndex: 'evalType', key: 'evalType', width: 100 },
  { title: '场景', dataIndex: 'scenario', key: 'scenario', width: 120 },
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '拒答', dataIndex: 'expectedReject', key: 'expectedReject', width: 100 },
  { title: '权重', dataIndex: 'weight', key: 'weight', width: 80 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '操作', key: 'action', width: 130, fixed: 'right' as const },
];

const resultColumns = [
  { title: 'runId', dataIndex: 'runId', key: 'runId', width: 190, ellipsis: true },
  { title: '用例编码', dataIndex: 'caseCode', key: 'caseCode', width: 130 },
  { title: '类型', dataIndex: 'evalType', key: 'evalType', width: 90 },
  { title: '模型', dataIndex: 'modelName', key: 'modelName', width: 150, ellipsis: true },
  { title: '综合得分', dataIndex: 'totalScore', key: 'totalScore', width: 110 },
  { title: '通过', dataIndex: 'passed', key: 'passed', width: 90 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '耗时', dataIndex: 'durationMs', key: 'durationMs', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 130, fixed: 'right' as const },
];

onMounted(() => {
  loadDataset();
  loadResults();
});

function defaultDatasetForm(): EvalDataset {
  return {
    caseCode: '',
    caseName: '',
    evalType: 'rag',
    scenario: '',
    question: '',
    knowledgeBaseId: '',
    expectedAnswer: '',
    expectedKeywords: '',
    expectedReferences: '',
    expectedReject: 0,
    expectedToolName: '',
    expectedToolParams: '',
    expectedTaskResult: '',
    difficulty: 'normal',
    weight: 1,
    status: 1,
    remark: '',
  };
}

function assignDatasetForm(record?: EvalDataset) {
  Object.assign(datasetForm, defaultDatasetForm(), record || {});
}

async function loadDataset() {
  datasetLoading.value = true;
  try {
    const params: Record<string, any> = {
      pageNo: datasetPagination.current,
      pageSize: datasetPagination.pageSize,
    };
    if (datasetQuery.caseCode) params.caseCode = `*${datasetQuery.caseCode}*`;
    if (datasetQuery.evalType) params.evalType = datasetQuery.evalType;
    if (datasetQuery.status !== undefined) params.status = datasetQuery.status;

    const res = await defHttp.get({ url: `${API_BASE}/dataset/list`, params });
    datasetList.value = res.records || [];
    datasetPagination.total = res.total || 0;
  } catch (e: any) {
    antMessage.error(e.message || '加载评测集失败');
  } finally {
    datasetLoading.value = false;
  }
}

async function loadResults() {
  resultLoading.value = true;
  try {
    const params: Record<string, any> = {
      pageNo: resultPagination.current,
      pageSize: resultPagination.pageSize,
    };
    if (resultQuery.runId) params.runId = resultQuery.runId;
    if (resultQuery.caseCode) params.caseCode = `*${resultQuery.caseCode}*`;
    if (resultQuery.evalType) params.evalType = resultQuery.evalType;
    if (resultQuery.status) params.status = resultQuery.status;

    const res = await defHttp.get({ url: `${API_BASE}/result/list`, params });
    resultList.value = res.records || [];
    resultPagination.total = res.total || 0;
  } catch (e: any) {
    antMessage.error(e.message || '加载评测结果失败');
  } finally {
    resultLoading.value = false;
  }
}

function handleDatasetTableChange(pag: any) {
  datasetPagination.current = pag.current;
  datasetPagination.pageSize = pag.pageSize;
  loadDataset();
}

function handleResultTableChange(pag: any) {
  resultPagination.current = pag.current;
  resultPagination.pageSize = pag.pageSize;
  loadResults();
}

function resetDatasetQuery() {
  datasetQuery.caseCode = '';
  datasetQuery.evalType = undefined;
  datasetQuery.status = undefined;
  datasetPagination.current = 1;
  loadDataset();
}

function resetResultQuery() {
  resultQuery.runId = '';
  resultQuery.caseCode = '';
  resultQuery.evalType = undefined;
  resultQuery.status = undefined;
  resultPagination.current = 1;
  loadResults();
}

function handleAddDataset() {
  datasetEditing.value = false;
  assignDatasetForm();
  datasetModalOpen.value = true;
}

function handleEditDataset(record: EvalDataset) {
  datasetEditing.value = true;
  assignDatasetForm(record);
  datasetModalOpen.value = true;
}

function closeDatasetModal() {
  datasetModalOpen.value = false;
  datasetFormRef.value?.clearValidate();
}

async function submitDataset() {
  try {
    await datasetFormRef.value?.validateFields();
  } catch {
    return;
  }

  datasetSubmitting.value = true;
  try {
    const payload = { ...datasetForm };
    if (datasetEditing.value) {
      await defHttp.put({ url: `${API_BASE}/dataset/edit`, params: payload });
      antMessage.success('修改成功');
    } else {
      await defHttp.post({ url: `${API_BASE}/dataset/add`, params: payload });
      antMessage.success('新增成功');
    }
    datasetModalOpen.value = false;
    loadDataset();
  } catch (e: any) {
    antMessage.error(e.message || '保存失败');
  } finally {
    datasetSubmitting.value = false;
  }
}

async function handleDeleteDataset(id?: string) {
  if (!id) return;
  await defHttp.delete({ url: `${API_BASE}/dataset/delete`, params: { id } });
  antMessage.success('删除成功');
  loadDataset();
}

function handleViewResult(record: EvalResult) {
  currentResult.value = record;
  resultDrawerOpen.value = true;
}

async function handleDeleteResult(id: string) {
  await defHttp.delete({ url: `${API_BASE}/result/delete`, params: { id } });
  antMessage.success('删除成功');
  loadResults();
}

async function handleDeleteRun() {
  if (!resultQuery.runId) return;
  await defHttp.delete({ url: `${API_BASE}/result/deleteByRunId`, params: { runId: resultQuery.runId } });
  antMessage.success('清理成功');
  loadResults();
}

function typeText(type?: string) {
  if (type === 'rag') return 'RAG';
  if (type === 'agent') return 'Agent';
  return '-';
}

function statusText(status?: string) {
  const map: Record<string, string> = {
    success: '成功',
    fail: '未通过',
    error: '异常',
    skipped: '跳过',
  };
  return status ? map[status] || status : '-';
}

function statusColor(status?: string) {
  const map: Record<string, string> = {
    success: 'green',
    fail: 'orange',
    error: 'red',
    skipped: 'default',
  };
  return status ? map[status] || 'blue' : 'default';
}

function scoreText(score?: number) {
  if (score === null || score === undefined) return '-';
  return Number(score).toFixed(2);
}

function scoreColor(score?: number) {
  const value = Number(score || 0);
  if (value >= 85) return 'green';
  if (value >= 60) return 'orange';
  return 'red';
}

function formatJson(text?: string) {
  if (!text) return '-';
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}
</script>

<style scoped lang="less">
.eval-manage-page {
  padding: 20px;
}

.page-header {
  margin-bottom: 18px;

  h1 {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 0 6px 0;
    font-size: 22px;
    font-weight: 600;
  }

  p {
    margin: 0;
    color: #8c8c8c;
  }
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.detail-block {
  margin-top: 16px;

  .detail-title {
    margin-bottom: 6px;
    font-weight: 600;

    &.danger {
      color: #cf1322;
    }
  }

  pre {
    max-height: 260px;
    margin: 0;
    padding: 12px;
    overflow: auto;
    white-space: pre-wrap;
    word-break: break-word;
    background: #f7f8fa;
    border: 1px solid #edf0f5;
    border-radius: 4px;
  }
}

@media (max-width: 768px) {
  .toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

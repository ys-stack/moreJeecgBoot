<template>
  <div class="batch-parse">
    <!-- 头部 -->
    <div class="page-header">
      <h1>
        <FolderOpenOutlined />
        批量解析文档
      </h1>
      <p>选择知识库和文件夹，批量解析 Markdown / TXT / PDF / DOCX 文档并入库</p>
    </div>

    <!-- 操作区 -->
    <a-card title="选择文档" :bordered="false" style="margin-bottom: 16px">
      <div class="action-bar">
        <a-space>
          <!-- 知识库选择器 -->
          <a-select
            v-model:value="knowledgeBaseId"
            placeholder="选择目标知识库"
            style="width: 220px"
            :loading="kbLoading"
            show-search
            :filter-option="filterKbOption"
          >
            <a-select-option v-for="kb in kbList" :key="kb.id" :value="kb.id">
              {{ kb.name }}
            </a-select-option>
          </a-select>

          <a-button type="primary" @click="triggerFolderSelect">
            <FolderOpenOutlined /> 选择文件夹
          </a-button>
          <input
            ref="folderInputRef"
            type="file"
            webkitdirectory
            directory
            multiple
            accept=".md,.markdown,.txt,.pdf,.docx"
            style="display: none"
            @change="onFolderSelected"
          />
          <a-button
            v-if="selectedFiles.length > 0"
            type="primary"
            :loading="parsing"
            :disabled="!knowledgeBaseId || selectedFiles.length === 0"
            @click="startBatchParse"
          >
            <ThunderboltOutlined /> 开始解析入库（{{ selectedFiles.length }} 个文件）
          </a-button>
        </a-space>
        <a-tag v-if="selectedFiles.length > 0" color="blue">
          共 {{ selectedFiles.length }} 个文件，{{ formatFileSize(totalSize) }}
        </a-tag>
      </div>

      <!-- 文件列表 -->
      <a-table
        v-if="selectedFiles.length > 0"
        :columns="fileColumns"
        :data-source="fileList"
        :pagination="false"
        row-key="name"
        size="small"
        style="margin-top: 12px"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'size'">
            {{ formatFileSize(record.size) }}
          </template>
          <template v-if="column.key === 'type'">
            <a-tag :color="getTypeColor(record.type)">{{ record.type }}</a-tag>
          </template>
          <template v-if="column.key === 'status'">
            <a-badge :status="record.status" :text="record.statusText" />
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 进度条 -->
    <a-card v-if="parsing || parseDone" title="解析进度" :bordered="false" style="margin-bottom: 16px">
      <a-progress
        :percent="progressPercent"
        :status="parsing ? 'active' : parseDone ? 'success' : 'normal'"
        :stroke-width="20"
      >
        <template #format>
          <span v-if="parsing">正在解析并入库，请稍候...</span>
          <span v-else-if="parseDone">
            完成：成功 {{ successCount }} 个，失败 {{ failedCount }} 个
          </span>
        </template>
      </a-progress>
    </a-card>

    <!-- 解析结果 -->
    <a-card v-if="parseResults.length > 0" title="解析结果（已入库）" :bordered="false">
      <template #extra>
        <a-space>
          <a-tag color="green">成功 {{ successCount }} 个</a-tag>
          <a-tag v-if="failedCount > 0" color="red">失败 {{ failedCount }} 个</a-tag>
          <a-tag color="blue">共 {{ totalChunks }} 个分片</a-tag>
        </a-space>
      </template>

      <a-collapse v-model:activeKey="activeResultKeys">
        <a-collapse-panel
          v-for="result in parseResults"
          :key="result.fileName"
          :header="`${result.fileName} — ${result.chunkCount} 个分片 / ${result.totalChars} 字符`"
        >
          <template #extra>
            <a-tag :color="result.chunkCount > 0 ? 'green' : 'orange'">
              {{ result.chunkCount > 0 ? '成功' : '无内容' }}
            </a-tag>
          </template>

          <a-descriptions :column="3" size="small" bordered style="margin-bottom: 12px">
            <a-descriptions-item label="文件类型">{{ result.fileType }}</a-descriptions-item>
            <a-descriptions-item label="总字符">{{ result.totalChars }}</a-descriptions-item>
            <a-descriptions-item label="预估 Token">{{ result.totalTokens }}</a-descriptions-item>
          </a-descriptions>

          <div class="chunks-preview">
            <a-collapse size="small">
              <a-collapse-panel
                v-for="chunk in result.chunks.slice(0, 20)"
                :key="chunk.chunkIndex"
                :header="`#${chunk.chunkIndex} — ${chunk.heading || '（无标题）'}`"
              >
                <div class="chunk-meta">
                  <a-tag>{{ chunk.chunkType }}</a-tag>
                  <a-tag color="blue">{{ chunk.charCount }} 字</a-tag>
                  <a-tag color="green">≈{{ chunk.tokenCount }} tokens</a-tag>
                </div>
                <pre class="chunk-content">{{ chunk.content }}</pre>
              </a-collapse-panel>
            </a-collapse>
            <div v-if="result.chunks.length > 20" class="more-hint">
              仅展示前 20 个分片，共 {{ result.chunks.length }} 个
            </div>
          </div>
        </a-collapse-panel>
      </a-collapse>
    </a-card>

    <!-- 错误列表 -->
    <a-card v-if="parseErrors.length > 0" title="解析失败" :bordered="false" style="margin-top: 16px">
      <a-alert
        v-for="err in parseErrors"
        :key="err.fileName"
        type="error"
        :message="err.fileName"
        :description="err.error"
        show-icon
        style="margin-bottom: 8px"
      />
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { FolderOpenOutlined, ThunderboltOutlined } from '@ant-design/icons-vue';
import { message as antMessage } from 'ant-design-vue';
import { getToken } from '/@/utils/auth/index';

// ==================== 类型 ====================

interface FileItem {
  name: string;
  size: number;
  type: string;
  file: File;
  status: 'default' | 'processing' | 'success' | 'error';
  statusText: string;
}

interface ChunkData {
  chunkIndex: number;
  heading: string;
  content: string;
  charCount: number;
  tokenCount: number;
  chunkType: string;
}

interface ParseResult {
  documentId: string;
  fileName: string;
  fileType: string;
  totalChars: number;
  chunkCount: number;
  totalTokens: number;
  chunks: ChunkData[];
}

interface ParseError {
  fileName: string;
  error: string;
}

interface KnowledgeBase {
  id: string;
  name: string;
  status: string;
}

// ==================== 状态 ====================

const SUPPORTED_EXTS = ['.md', '.markdown', '.txt', '.pdf', '.docx'];

const folderInputRef = ref<HTMLInputElement>();
const selectedFiles = ref<File[]>([]);
const fileList = ref<FileItem[]>([]);
const parsing = ref(false);
const parseDone = ref(false);

const parseResults = ref<ParseResult[]>([]);
const parseErrors = ref<ParseError[]>([]);
const activeResultKeys = ref<string[]>([]);

// 知识库相关
const kbList = ref<KnowledgeBase[]>([]);
const kbLoading = ref(false);
const knowledgeBaseId = ref<string>('');

// ==================== 计算属性 ====================

const totalSize = computed(() => selectedFiles.value.reduce((sum, f) => sum + f.size, 0));
const progressPercent = computed(() => {
  if (!parseDone.value) return parsing.value ? 50 : 0;
  return 100;
});
const successCount = computed(() => parseResults.value.filter((r) => r.chunkCount > 0).length);
const failedCount = computed(() => parseErrors.value.length);
const totalChunks = computed(() => parseResults.value.reduce((sum, r) => sum + r.chunkCount, 0));

// ==================== 表格列 ====================

const fileColumns = [
  { title: '文件名', dataIndex: 'name', key: 'name' },
  { title: '类型', key: 'type', width: 80 },
  { title: '大小', key: 'size', width: 100 },
  { title: '状态', key: 'status', width: 100 },
];

// ==================== 知识库加载 ====================

onMounted(async () => {
  await loadKbList();
});

async function loadKbList() {
  kbLoading.value = true;
  try {
    const token = getToken();
    const res = await fetch('/jeecgboot/practice/kb/listAll', {
      headers: { 'X-Access-Token': token as string },
    });
    if (res.ok) {
      const data = await res.json();
      if (data.success && data.result) {
        kbList.value = (data.result as KnowledgeBase[]).filter((kb) => kb.status === 'active');
      }
    }
  } catch (e) {
    console.error('加载知识库列表失败', e);
  } finally {
    kbLoading.value = false;
  }
}

function filterKbOption(input: string, option: any) {
  const kb = kbList.value.find((k) => k.id === option.value);
  return kb ? kb.name.toLowerCase().includes(input.toLowerCase()) : false;
}

// ==================== 文件夹选择 ====================

function triggerFolderSelect() {
  folderInputRef.value?.click();
}

function onFolderSelected(e: Event) {
  const input = e.target as HTMLInputElement;
  if (!input.files) return;

  const files = Array.from(input.files).filter((f) => {
    const ext = '.' + f.name.split('.').pop()?.toLowerCase();
    return SUPPORTED_EXTS.includes(ext);
  });

  if (files.length === 0) {
    antMessage.warning('文件夹下没有支持的文档文件（.md / .txt / .pdf / .docx）');
    return;
  }

  selectedFiles.value = files;
  fileList.value = files.map((f) => ({
    name: f.name,
    size: f.size,
    type: f.name.split('.').pop()?.toUpperCase() || 'UNKNOWN',
    file: f,
    status: 'default' as const,
    statusText: '待解析',
  }));

  // 重置之前的结果
  parseResults.value = [];
  parseErrors.value = [];
  parseDone.value = false;

  antMessage.success(`已选择 ${files.length} 个文件`);
}

// ==================== 批量解析（调 Java 后端） ====================

async function startBatchParse() {
  if (!knowledgeBaseId.value) {
    antMessage.warning('请先选择目标知识库');
    return;
  }
  if (selectedFiles.value.length === 0) return;

  parsing.value = true;
  parseDone.value = false;
  parseResults.value = [];
  parseErrors.value = [];

  // 所有文件标记为解析中
  fileList.value.forEach((f) => {
    f.status = 'processing';
    f.statusText = '解析中';
  });

  try {
    // 构建 FormData：所有文件 + knowledgeBaseId
    const formData = new FormData();
    selectedFiles.value.forEach((f) => formData.append('files', f));
    formData.append('knowledgeBaseId', knowledgeBaseId.value);

    const token = getToken();
    const res = await fetch('/jeecgboot/practice/doc/batch/upload', {
      method: 'POST',
      headers: { 'X-Access-Token': token as string },
      body: formData,
    });

    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }

    const data = await res.json();
    if (!data.success) {
      throw new Error(data.message || '批量解析失败');
    }

    const result = data.result;

    // 映射成功结果
    parseResults.value = (result.results || []).map((r: any) => ({
      documentId: r.documentId,
      fileName: r.fileName,
      fileType: r.fileType,
      totalChars: r.totalChars,
      chunkCount: r.chunkCount,
      totalTokens: r.totalTokens,
      chunks: r.chunks || [],
    }));

    // 映射错误结果
    parseErrors.value = (result.errors || []).map((e: any) => ({
      fileName: e.fileName,
      error: e.error,
    }));

    // 更新文件状态
    (result.results || []).forEach((r: any) => updateFileStatus(r.fileName, 'success', `${r.chunkCount} 分片`));
    (result.errors || []).forEach((e: any) => updateFileStatus(e.fileName, 'error', '失败'));

  } catch (e: any) {
    antMessage.error(e?.message || '批量解析请求失败');
    // 所有文件标记失败
    fileList.value.forEach((f) => {
      if (f.status === 'processing') {
        f.status = 'error';
        f.statusText = '请求失败';
      }
    });
  } finally {
    parsing.value = false;
    parseDone.value = true;
  }

  // 默认展开前 3 个结果
  activeResultKeys.value = parseResults.value.slice(0, 3).map((r) => r.fileName);

  antMessage.success(`解析入库完成：成功 ${successCount.value}，失败 ${failedCount.value}`);
}

function updateFileStatus(name: string, status: FileItem['status'], text: string) {
  const item = fileList.value.find((f) => f.name === name);
  if (item) {
    item.status = status;
    item.statusText = text;
  }
}

// ==================== 工具函数 ====================

function formatFileSize(bytes: number): string {
  if (!bytes) return '-';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

function getTypeColor(type: string): string {
  switch (type.toUpperCase()) {
    case 'MD':
    case 'MARKDOWN':
      return 'blue';
    case 'TXT':
      return 'default';
    case 'PDF':
      return 'red';
    case 'DOCX':
      return 'green';
    default:
      return 'default';
  }
}
</script>

<style scoped lang="less">
.batch-parse {
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

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chunk-meta {
  margin-bottom: 8px;
  display: flex;
  gap: 6px;
}

.chunk-content {
  background: #f5f5f5;
  padding: 10px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
  margin: 0;
}

.more-hint {
  text-align: center;
  color: #8c8c8c;
  font-size: 13px;
  padding: 8px 0;
}
</style>

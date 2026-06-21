<template>
  <div class="batch-parse">
    <!-- 头部 -->
    <div class="page-header">
      <h1>
        <FolderOpenOutlined />
        批量解析文档
      </h1>
      <p>选择文件夹，批量解析 Markdown / TXT / PDF / DOCX 文档并查看切分结果</p>
    </div>

    <!-- 操作区 -->
    <a-card title="选择文档" :bordered="false" style="margin-bottom: 16px">
      <div class="action-bar">
        <a-space>
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
            :loading="parsing"
            :disabled="selectedFiles.length === 0"
            @click="startBatchParse"
          >
            <ThunderboltOutlined /> 开始解析（{{ selectedFiles.length }} 个文件）
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
        <template #format="{ percent }">
          <span>{{ parsedCount }} / {{ selectedFiles.length }} 文件 ({{ percent }}%)</span>
        </template>
      </a-progress>
    </a-card>

    <!-- 解析结果 -->
    <a-card v-if="parseResults.length > 0" title="解析结果" :bordered="false">
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
import { ref, computed } from 'vue';
import { FolderOpenOutlined, ThunderboltOutlined } from '@ant-design/icons-vue';
import { message as antMessage } from 'ant-design-vue';

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

// ==================== 状态 ====================

const PYTHON_API = 'http://localhost:8000';
const SUPPORTED_EXTS = ['.md', '.markdown', '.txt', '.pdf', '.docx'];

const folderInputRef = ref<HTMLInputElement>();
const selectedFiles = ref<File[]>([]);
const fileList = ref<FileItem[]>([]);
const parsing = ref(false);
const parseDone = ref(false);
const parsedCount = ref(0);

const parseResults = ref<ParseResult[]>([]);
const parseErrors = ref<ParseError[]>([]);
const activeResultKeys = ref<string[]>([]);

// ==================== 计算属性 ====================

const totalSize = computed(() => selectedFiles.value.reduce((sum, f) => sum + f.size, 0));
const progressPercent = computed(() =>
  selectedFiles.value.length > 0 ? Math.round((parsedCount.value / selectedFiles.value.length) * 100) : 0
);
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
  parsedCount.value = 0;

  antMessage.success(`已选择 ${files.length} 个文件`);
}

// ==================== 批量解析 ====================

async function startBatchParse() {
  if (selectedFiles.value.length === 0) return;

  parsing.value = true;
  parseDone.value = false;
  parseResults.value = [];
  parseErrors.value = [];
  parsedCount.value = 0;

  // 逐个文件请求 Python 服务
  for (let i = 0; i < selectedFiles.value.length; i++) {
    const file = selectedFiles.value[i];
    updateFileStatus(file.name, 'processing', '解析中');

    try {
      const formData = new FormData();
      formData.append('file', file);

      const res = await fetch(`${PYTHON_API}/parse/file`, {
        method: 'POST',
        body: formData,
      });

      if (!res.ok) {
        const errData = await res.json().catch(() => ({ detail: `HTTP ${res.status}` }));
        throw new Error(errData.detail || `HTTP ${res.status}`);
      }

      const data: ParseResult = await res.json();
      parseResults.value.push(data);
      updateFileStatus(file.name, 'success', `${data.chunkCount} 分片`);
    } catch (e: any) {
      parseErrors.value.push({ fileName: file.name, error: e.message || '未知错误' });
      updateFileStatus(file.name, 'error', '失败');
    }

    parsedCount.value = i + 1;
  }

  parsing.value = false;
  parseDone.value = true;

  // 默认展开前 3 个结果
  activeResultKeys.value = parseResults.value.slice(0, 3).map((r) => r.fileName);

  antMessage.success(`解析完成：成功 ${successCount.value}，失败 ${failedCount.value}`);
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

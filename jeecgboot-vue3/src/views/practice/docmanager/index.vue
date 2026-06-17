<template>
  <div class="doc-manager">
    <!-- 知识库选择器 + 头部 -->
    <div class="page-header">
      <h1>
        <FileSearchOutlined />
        文档管理
      </h1>
      <p>上传 Markdown 文档，自动解析切分为知识库分片</p>
      <div class="kb-selector">
        <span class="selector-label">当前知识库：</span>
        <a-select
          v-model:value="currentKbId"
          placeholder="请选择知识库"
          style="width: 260px"
          :loading="kbLoading"
          @change="onKbChange"
        >
          <a-select-option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">
            {{ kb.name }}
            <template v-if="kb.docCount">
              <span style="color: #999; margin-left: 4px">({{ kb.docCount }}篇)</span>
            </template>
          </a-select-option>
        </a-select>
      </div>
    </div>

    <!-- 上传区域 -->
    <div class="upload-section">
      <a-card title="上传文档" :bordered="false">
        <a-upload-dragger
          :before-upload="beforeUpload"
          :custom-request="handleUpload"
          :show-upload-list="false"
          accept=".md,.markdown,.txt"
        >
          <p class="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p class="ant-upload-text">点击或拖拽 Markdown 文件到此区域</p>
          <p class="ant-upload-hint">支持 .md、.markdown、.txt 格式，文件大小不超过 10MB</p>
        </a-upload-dragger>

        <!-- 上传结果 -->
        <a-alert
          v-if="uploadResult"
          type="success"
          show-icon
          closable
          @close="uploadResult = null"
          style="margin-top: 16px"
        >
          <template #message>
            <strong>{{ uploadResult.fileName }}</strong> 上传成功！
            共生成 <strong>{{ uploadResult.chunkCount }}</strong> 个分片，
            总计 <strong>{{ uploadResult.totalChars }}</strong> 字符，
            约 <strong>{{ uploadResult.totalTokens }}</strong> tokens
          </template>
          <template #description>
            <div style="margin-top: 8px">
              <span>文档ID: <code>{{ uploadResult.documentId }}</code></span>
            </div>
          </template>
        </a-alert>
      </a-card>
    </div>

    <!-- 文档列表 -->
    <div class="doc-list-section">
      <a-card title="已上传文档" :bordered="false">
        <template #extra>
          <a-button type="link" @click="loadDocuments" :loading="docLoading">
            <ReloadOutlined /> 刷新
          </a-button>
        </template>

        <a-table
          :columns="docColumns"
          :data-source="documents"
          :loading="docLoading"
          row-key="id"
          size="middle"
          :pagination="false"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'">
              <a-badge
                :status="getStatusType(record.status)"
                :text="getStatusText(record.status)"
              />
            </template>
            <template v-if="column.key === 'fileSize'">
              {{ formatFileSize(record.fileSize) }}
            </template>
            <template v-if="column.key === 'createTime'">
              {{ record.createTime || '-' }}
            </template>
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="viewChunks(record)">
                  查看分片
                </a-button>
                <a-popconfirm
                  title="确定删除此文档及其所有分片？"
                  @confirm="deleteDocument(record.id)"
                  ok-text="删除"
                  cancel-text="取消"
                >
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-card>
    </div>

    <!-- 向量语义搜索 -->
    <div class="vector-search-section">
      <a-card title="向量语义搜索" :bordered="false">
        <div class="search-bar">
          <a-input-search
            v-model:value="searchQuery"
            placeholder="输入自然语言查询，如：Redis 数据怎么持久化"
            enter-button="搜索"
            size="large"
            :loading="searchLoading"
            @search="doVectorSearch"
          />
          <div class="search-options">
            <span class="option-label">返回条数：</span>
            <a-slider v-model:value="topK" :min="1" :max="20" :marks="{ 1: '1', 5: '5', 10: '10', 20: '20' }" style="flex: 1" />
          </div>
        </div>

        <!-- 搜索结果 -->
        <div v-if="searchResults.length > 0" class="search-results">
          <div class="result-summary">
            找到 <strong>{{ searchResults.length }}</strong> 条相关分片
            <span v-if="searchTime">（耗时 {{ searchTime }}ms）</span>
          </div>
          <div v-for="(item, idx) in searchResults" :key="item.chunkId" class="result-item">
            <div class="result-header">
              <a-tag color="blue">#{{ idx + 1 }}</a-tag>
              <span class="result-heading">{{ item.headingPath || '（无标题）' }}</span>
              <a-tag color="orange">{{ (item.score * 100).toFixed(1) }}% 相似</a-tag>
              <span class="result-source">{{ item.sourceFileName }}</span>
            </div>
            <pre class="result-content">{{ item.content }}</pre>
          </div>
        </div>

        <a-empty v-else-if="searchDone && searchResults.length === 0" description="未找到相关内容，试试换个问法" style="margin-top: 20px" />
      </a-card>
    </div>

    <!-- 分片详情抽屉 -->
    <a-drawer
      v-model:visible="drawerVisible"
      title="文档分片详情"
      width="720"
      :destroyOnClose="true"
    >
      <template v-if="currentDoc">
        <a-descriptions :column="2" bordered size="small" style="margin-bottom: 16px">
          <a-descriptions-item label="文档标题">{{ currentDoc.title }}</a-descriptions-item>
          <a-descriptions-item label="文档类型">{{ currentDoc.docType }}</a-descriptions-item>
          <a-descriptions-item label="分片数">{{ currentDoc.chunkCount }}</a-descriptions-item>
          <a-descriptions-item label="总字符">{{ currentDoc.totalChars }}</a-descriptions-item>
        </a-descriptions>
      </template>

      <a-spin :spinning="chunkLoading">
        <div v-if="chunks.length === 0 && !chunkLoading" class="empty-chunks">
          <a-empty description="暂无分片数据" />
        </div>

        <a-collapse v-else v-model:activeKey="activeChunkKeys">
          <a-collapse-panel
            v-for="chunk in chunks"
            :key="chunk.id"
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
      </a-spin>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { InboxOutlined, ReloadOutlined, FileSearchOutlined } from '@ant-design/icons-vue';
import { message as antMessage } from 'ant-design-vue';
import { defHttp } from '/@/utils/http/axios';

// ==================== 类型定义 ====================

interface UploadResult {
  documentId: string;
  knowledgeBaseId: string;
  fileName: string;
  totalChars: number;
  chunkCount: number;
  totalTokens: number;
  filePath: string;
  chunks: any[];
}

interface DocRecord {
  id: string;
  title: string;
  docType: string;
  fileName: string;
  fileSize: number;
  status: string;
  chunkCount: number;
  totalChars: number;
  createTime: string;
}

interface ChunkRecord {
  id: string;
  chunkIndex: number;
  heading: string;
  content: string;
  charCount: number;
  tokenCount: number;
  chunkType: string;
}

interface SearchResult {
  chunkId: string;
  documentId: string;
  knowledgeBaseId: string;
  content: string;
  headingPath: string;
  score: number;
  sourceFileName: string;
  chunkIndex: number;
}

// ==================== 状态 ====================

const uploadResult = ref<UploadResult | null>(null);
const documents = ref<DocRecord[]>([]);
const docLoading = ref(false);
const drawerVisible = ref(false);
const currentDoc = ref<DocRecord | null>(null);
const chunks = ref<ChunkRecord[]>([]);
const chunkLoading = ref(false);
const activeChunkKeys = ref<string[]>([]);

// 知识库选择
const knowledgeBases = ref<{ id: string; name: string; docCount: number }[]>([]);
const currentKbId = ref<string>('');
const kbLoading = ref(false);

// 向量搜索
const searchQuery = ref('');
const topK = ref(5);
const searchLoading = ref(false);
const searchResults = ref<SearchResult[]>([]);
const searchDone = ref(false);
const searchTime = ref(0);

// ==================== 表格列定义 ====================

const docColumns = [
  { title: '标题', dataIndex: 'title', key: 'title', width: 200 },
  { title: '类型', dataIndex: 'docType', key: 'docType', width: 80 },
  { title: '状态', key: 'status', width: 100 },
  { title: '分片', dataIndex: 'chunkCount', key: 'chunkCount', width: 70 },
  { title: '字符数', dataIndex: 'totalChars', key: 'totalChars', width: 90 },
  { title: '大小', key: 'fileSize', width: 90 },
  { title: '上传时间', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 150, fixed: 'right' },
];

// ==================== API ====================

const API_BASE = '/practice/doc';

async function loadKnowledgeBases() {
  kbLoading.value = true;
  try {
    const kbs = await defHttp.get({ url: `${API_BASE}/kb/list` });
    knowledgeBases.value = kbs || [];
    // 如果没有选中的知识库，默认选第一个
    if (knowledgeBases.value.length > 0 && !currentKbId.value) {
      currentKbId.value = knowledgeBases.value[0].id;
      loadDocuments();
    }
  } catch (e) {
    console.error('加载知识库列表失败', e);
  } finally {
    kbLoading.value = false;
  }
}

function onKbChange() {
  documents.value = [];
  loadDocuments();
}

async function loadDocuments() {
  if (!currentKbId.value) {
    documents.value = [];
    return;
  }
  docLoading.value = true;
  try {
    const docs = await defHttp.get({ url: `${API_BASE}/kb/${currentKbId.value}/docs` });
    documents.value = docs || [];
  } catch (e) {
    console.error('加载文档列表失败', e);
  } finally {
    docLoading.value = false;
  }
}

async function handleUpload(options: any) {
  const formData = new FormData();
  formData.append('file', options.file);
  if (currentKbId.value) {
    formData.append('knowledgeBaseId', currentKbId.value);
  }

  try {
    // defHttp.post() 不支持 FormData（cloneDeep 会损坏 FormData，beforeRequestHook 会丢弃它）
    // 必须用 uploadMyFile()，它绕过 beforeRequestHook 直接调 axiosInstance.request()
    const result = await defHttp.uploadMyFile(`${API_BASE}/upload`, formData);
    uploadResult.value = result;
    antMessage.success('文档上传并解析成功！');
    loadDocuments();
    loadKnowledgeBases();
    options.onSuccess(result);
  } catch (e: any) {
    antMessage.error(e?.message || '上传失败');
    options.onError(e);
  }
}

function beforeUpload(file: File) {
  const isMd = /\.(md|markdown|txt)$/i.test(file.name);
  if (!isMd) {
    antMessage.error('仅支持 .md / .markdown / .txt 文件');
    return false;
  }
  const isLt10M = file.size / 1024 / 1024 < 10;
  if (!isLt10M) {
    antMessage.error('文件大小不能超过 10MB');
    return false;
  }
  return true;
}

async function viewChunks(doc: DocRecord) {
  currentDoc.value = doc;
  drawerVisible.value = true;
  chunkLoading.value = true;
  try {
    const data = await defHttp.get({ url: `${API_BASE}/chunks/${doc.id}` });
    chunks.value = data || [];
    // 默认展开前 3 个
    activeChunkKeys.value = chunks.value.slice(0, 3).map((c) => c.id);
  } catch (e) {
    antMessage.error('加载分片失败');
  } finally {
    chunkLoading.value = false;
  }
}

async function deleteDocument(docId: string) {
  try {
    await defHttp.delete({ url: `${API_BASE}/${docId}` });
    antMessage.success('删除成功');
    loadDocuments();
    loadKnowledgeBases();
  } catch (e) {
    antMessage.error('删除失败');
  }
}

// ==================== 向量搜索 ====================

const VECTOR_API = '/practice/vector';

async function doVectorSearch() {
  if (!searchQuery.value.trim()) {
    antMessage.warning('请输入查询内容');
    return;
  }
  searchLoading.value = true;
  searchResults.value = [];
  searchDone.value = false;
  const start = Date.now();
  try {
    const results = await defHttp.post({
      url: `${VECTOR_API}/search`,
      data: {
        query: searchQuery.value,
        topK: topK.value,
        knowledgeBaseId: currentKbId.value || undefined,
      },
    });
    searchResults.value = results || [];
    searchDone.value = true;
    searchTime.value = Date.now() - start;
  } catch (e: any) {
    antMessage.error(e?.message || '向量检索失败');
    searchDone.value = true;
  } finally {
    searchLoading.value = false;
  }
}

// ==================== 工具函数 ====================

function getStatusType(status: string) {
  switch (status) {
    case 'completed': return 'success';
    case 'vectorized': return 'success';
    case 'pending': return 'processing';
    case 'parsing': return 'processing';
    case 'failed': return 'error';
    default: return 'default';
  }
}

function getStatusText(status: string) {
  switch (status) {
    case 'completed': return '已完成';
    case 'vectorized': return '已向量化';
    case 'pending': return '待处理';
    case 'parsing': return '解析中';
    case 'failed': return '失败';
    default: return status;
  }
}

function formatFileSize(bytes: number): string {
  if (!bytes) return '-';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

// ==================== 初始化 ====================

onMounted(() => {
  loadKnowledgeBases();
});
</script>

<style scoped lang="less">
.doc-manager {
  padding: 20px;
  max-width: 1100px;
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

.kb-selector {
  margin-top: 12px;
  display: flex;
  align-items: center;

  .selector-label {
    font-size: 14px;
    color: #595959;
    margin-right: 8px;
    white-space: nowrap;
  }
}

.upload-section {
  margin-bottom: 24px;
}

.doc-list-section {
  margin-bottom: 24px;
}

.chunk-meta {
  margin-bottom: 12px;
  display: flex;
  gap: 6px;
}

.chunk-content {
  background: #f5f5f5;
  padding: 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
  margin: 0;
}

.empty-chunks {
  padding: 40px 0;
}

// ==================== 向量搜索样式 ====================

.vector-search-section {
  margin-bottom: 24px;
}

.search-bar {
  .search-options {
    display: flex;
    align-items: center;
    margin-top: 12px;
    padding: 0 4px;

    .option-label {
      font-size: 13px;
      color: #8c8c8c;
      white-space: nowrap;
      margin-right: 12px;
    }
  }
}

.search-results {
  margin-top: 20px;

  .result-summary {
    font-size: 14px;
    color: #595959;
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid #f0f0f0;
  }

  .result-item {
    margin-bottom: 16px;
    padding: 14px;
    background: #fafafa;
    border-radius: 8px;
    border: 1px solid #f0f0f0;

    &:hover {
      border-color: #d9d9d9;
      background: #f5f5f5;
    }

    .result-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 10px;

      .result-heading {
        font-weight: 500;
        font-size: 14px;
        color: #262626;
        flex: 1;
      }

      .result-source {
        font-size: 12px;
        color: #bfbfbf;
      }
    }

    .result-content {
      background: #fff;
      padding: 12px;
      border-radius: 6px;
      font-size: 13px;
      line-height: 1.7;
      white-space: pre-wrap;
      word-break: break-all;
      max-height: 200px;
      overflow-y: auto;
      margin: 0;
      border: 1px solid #f0f0f0;
    }
  }
}
</style>

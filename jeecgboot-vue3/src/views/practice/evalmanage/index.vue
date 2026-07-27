<template>
  <div class="eval-manage-page">
    <div class="page-header">
      <h1>
        <ExperimentOutlined />
        AI评测管理
      </h1>
      <p>维护 RAG / Agent 评测集，一键发起批量自动化评测，查看评测报告与指标对比</p>
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

            <!-- 核心功能：一键发起评测按钮 -->
            <a-button type="primary" style="background-color: #52c41a; border-color: #52c41a" @click="handleOpenRunModal">
              <PlayCircleOutlined />
              一键发起评测
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
            <a-button type="primary" ghost @click="handleOpenCompareModal">
              <SwapOutlined />
              运行对比 (Compare)
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
                <a-button type="link" size="small" @click="handleViewReport(record.runId)">报告</a-button>
                <a-popconfirm title="确定删除这条评测结果？" ok-text="删除" cancel-text="取消" @confirm="handleDeleteResult(record.id)">
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <!-- Modal 1: 一键发起评测配置弹窗 -->
    <a-modal
      v-model:open="runModalOpen"
      title="一键发起AI自动化评测"
      width="580px"
      ok-text="开始执行评测"
      cancel-text="取消"
      :confirm-loading="runStarting"
      @ok="submitRun"
    >
      <a-form :model="runForm" layout="vertical">
        <a-form-item label="评测运行名称">
          <a-input v-model:value="runForm.runName" placeholder="如：Prompt-V2效果测试 / 模型基线对比" />
        </a-form-item>
        <a-form-item label="评测用例类型">
          <a-select v-model:value="runForm.evalType" placeholder="请选择评测范围">
            <a-select-option value="">全部用例 (RAG + Agent)</a-select-option>
            <a-select-option value="rag">仅 RAG 知识库用例</a-select-option>
            <a-select-option value="agent">仅 Agent 工具调用用例</a-select-option>
          </a-select>
        </a-form-item>
        <a-row :gutter="16">
          <a-col :span="16">
            <a-form-item label="Prompt编码 (可选)">
              <a-input v-model:value="runForm.promptCode" placeholder="留空使用系统内置Prompt" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="Prompt版本">
              <a-input-number v-model:value="runForm.promptVersion" :min="1" style="width: 100%" placeholder="最新启用版" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="执行方式">
          <a-radio-group v-model:value="runForm.asyncMode">
            <a-radio :value="true">异步后台执行（推荐，实时进度条轮询）</a-radio>
            <a-radio :value="false">同步阻塞等待（适合小规模少量用例）</a-radio>
          </a-radio-group>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Modal 2: 评测实时进度与报告大盘弹窗 -->
    <a-modal
      v-model:open="reportModalOpen"
      :title="taskRunning ? 'AI评测任务执行中...' : 'AI评测报告大盘 (' + (reportData?.runId || '') + ')'"
      width="820px"
      :footer="null"
    >
      <!-- 运行进度展示 -->
      <div v-if="taskRunning" style="padding: 20px 10px; text-align: center">
        <a-progress type="circle" :percent="currentTask?.progressPercentage || 0" :status="taskStatusType" />
        <div style="margin-top: 16px; font-size: 16px; font-weight: 600">
          状态: {{ taskStatusText }} ({{ currentTask?.processedCases || 0 }} / {{ currentTask?.totalCases || 0 }})
        </div>
        <div style="margin-top: 8px; color: #8c8c8c" v-if="currentTask?.currentCaseCode">
          <SyncOutlined spin /> 正在评测用例: {{ currentTask.currentCaseCode }}
        </div>
      </div>

      <!-- 评测报告数据大盘 -->
      <div v-else-if="reportData" style="padding: 10px">
        <a-row :gutter="16" style="margin-bottom: 20px">
          <a-col :span="6">
            <a-card size="small" style="background: #f6ffed; border-color: #b7eb8f">
              <a-statistic title="总体通过率" :value="reportData.passRate || 0" suffix="%" :precision="2" :value-style="{ color: '#52c41a' }" />
            </a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small" style="background: #e6f7ff; border-color: #91caff">
              <a-statistic title="加权平均分" :value="reportData.avgScore || 0" suffix="分" :precision="2" :value-style="{ color: '#1677ff' }" />
            </a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small">
              <a-statistic title="总用例数" :value="reportData.totalCases || 0" />
            </a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small">
              <a-statistic title="通过用例数" :value="reportData.passedCases || 0" :value-style="{ color: '#389e0d' }" />
            </a-card>
          </a-col>
        </a-row>

        <a-card title="RAG 知识库评测维度" size="small" style="margin-bottom: 16px">
          <a-row :gutter="16">
            <a-col :span="6">
              <a-statistic title="回答相关性 (Relevance)" :value="reportData.ragAnswerRelevance || 0" suffix="分" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="引用命中率 (Ref Hit)" :value="reportData.ragReferenceHit || 0" suffix="分" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="片段关键词命中" :value="reportData.ragChunkHit || 0" suffix="分" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="防幻觉拒答得分 (Reject)" :value="reportData.ragReject || 0" suffix="分" />
            </a-col>
          </a-row>
        </a-card>

        <a-card title="Agent 工具调用评测维度" size="small" style="margin-bottom: 16px">
          <a-row :gutter="16">
            <a-col :span="6">
              <a-statistic title="工具选择正确率" :value="reportData.agentToolSelection || 0" suffix="分" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="参数提取准确率" :value="reportData.agentParamAccuracy || 0" suffix="分" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="二次确认正确率" :value="reportData.agentConfirmation || 0" suffix="分" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="任务最终完成度" :value="reportData.agentTaskCompletion || 0" suffix="分" />
            </a-col>
          </a-row>
        </a-card>

        <a-card title="性能与Token消耗" size="small" style="margin-bottom: 16px">
          <a-row :gutter="16">
            <a-col :span="6"><a-statistic title="平均耗时" :value="reportData.avgDurationMs || 0" suffix="ms" :precision="2" /></a-col>
            <a-col :span="6"><a-statistic title="P95耗时" :value="reportData.p95DurationMs || 0" suffix="ms" /></a-col>
            <a-col :span="6"><a-statistic title="输入Token" :value="reportData.totalPromptTokens || 0" /></a-col>
            <a-col :span="6"><a-statistic title="总Token" :value="reportData.totalTokens || 0" /></a-col>
          </a-row>
        </a-card>

        <div style="text-align: right">
          <a-button type="primary" @click="switchToResultTab(reportData.runId)">查看用例结果列表</a-button>
        </div>
      </div>
    </a-modal>

    <!-- Modal 3: 两次 Run 对比 (Compare) 弹窗 -->
    <a-modal
      v-model:open="compareModalOpen"
      title="评测结果 Diff 对比 (Compare)"
      width="780px"
      :footer="null"
    >
      <a-form layout="inline" style="margin-bottom: 20px">
        <a-form-item label="基线 RunId (Base)">
          <a-input v-model:value="compareForm.baseRunId" placeholder="请输入基线 runId" style="width: 220px" />
        </a-form-item>
        <a-form-item label="目标 RunId (Target)">
          <a-input v-model:value="compareForm.targetRunId" placeholder="请输入对比 runId" style="width: 220px" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" @click="submitCompare" :loading="compareLoading">对比</a-button>
        </a-form-item>
      </a-form>

      <div v-if="compareResult" style="padding: 10px">
        <a-descriptions title="核心指标 Deltas 变动" bordered size="small" :column="2">
          <a-descriptions-item label="通过率变动 (PassRate Delta)">
            <span :style="{ color: deltaColor(compareResult.deltas?.passRateDelta) }">
              {{ compareResult.deltas?.passRateDelta > 0 ? '+' : '' }}{{ compareResult.deltas?.passRateDelta }}%
            </span>
          </a-descriptions-item>
          <a-descriptions-item label="平均分变动 (AvgScore Delta)">
            <span :style="{ color: deltaColor(compareResult.deltas?.avgScoreDelta) }">
              {{ compareResult.deltas?.avgScoreDelta > 0 ? '+' : '' }}{{ compareResult.deltas?.avgScoreDelta }}分
            </span>
          </a-descriptions-item>
          <a-descriptions-item label="RAG 相关性变动">
            <span :style="{ color: deltaColor(compareResult.deltas?.ragAnswerRelevanceDelta) }">
              {{ compareResult.deltas?.ragAnswerRelevanceDelta > 0 ? '+' : '' }}{{ compareResult.deltas?.ragAnswerRelevanceDelta }}分
            </span>
          </a-descriptions-item>
          <a-descriptions-item label="RAG 引用命中变动">
            <span :style="{ color: deltaColor(compareResult.deltas?.ragReferenceHitDelta) }">
              {{ compareResult.deltas?.ragReferenceHitDelta > 0 ? '+' : '' }}{{ compareResult.deltas?.ragReferenceHitDelta }}分
            </span>
          </a-descriptions-item>
          <a-descriptions-item label="Agent 工具选择变动">
            <span :style="{ color: deltaColor(compareResult.deltas?.agentToolSelectionDelta) }">
              {{ compareResult.deltas?.agentToolSelectionDelta > 0 ? '+' : '' }}{{ compareResult.deltas?.agentToolSelectionDelta }}分
            </span>
          </a-descriptions-item>
          <a-descriptions-item label="Agent 参数准确变动">
            <span :style="{ color: deltaColor(compareResult.deltas?.agentParamAccuracyDelta) }">
              {{ compareResult.deltas?.agentParamAccuracyDelta > 0 ? '+' : '' }}{{ compareResult.deltas?.agentParamAccuracyDelta }}分
            </span>
          </a-descriptions-item>
        </a-descriptions>
      </div>
    </a-modal>

    <!-- 用例新增/编辑 Modal -->
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
          <a-form-item label="预期召回片段关键词JSON" name="expectedChunkKeywords">
            <a-textarea v-model:value="datasetForm.expectedChunkKeywords" :rows="3" placeholder='["片段关键词1","片段关键词2"]' />
          </a-form-item>
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
          <a-form-item label="是否必须二次确认" name="shouldRequireConfirm">
            <a-switch v-model:checked="shouldRequireConfirmChecked" checked-children="必须确认" un-checked-children="无需确认" />
          </a-form-item>
        </template>

        <a-form-item label="备注" name="remark">
          <a-textarea v-model:value="datasetForm.remark" :rows="2" placeholder="可记录用例设计说明" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 用例结果详情 Drawer -->
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
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SwapOutlined,
  SyncOutlined,
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
  expectedChunkKeywords?: string;
  expectedReject?: number;
  expectedToolName?: string;
  expectedToolParams?: string;
  expectedTaskResult?: string;
  shouldRequireConfirm?: number;
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
  chunkHitScore?: number;
  rejectScore?: number;
  toolSelectionScore?: number;
  paramAccuracyScore?: number;
  taskCompletionScore?: number;
  confirmationScore?: number;
  totalScore?: number;
  passed?: number;
  durationMs?: number;
  status?: string;
  errorMsg?: string;
  judgeDetail?: string;
  createTime?: string;
}

interface EvalReport {
  runId: string;
  runName?: string;
  totalCases: number;
  passedCases: number;
  passRate: number;
  avgScore: number;
  ragAnswerRelevance: number;
  ragReferenceHit: number;
  ragChunkHit: number;
  ragReject: number;
  agentToolSelection: number;
  agentParamAccuracy: number;
  agentTaskCompletion: number;
  agentConfirmation: number;
  avgDurationMs: number;
  p95DurationMs: number;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalTokens: number;
}

interface EvalRunTask {
  runId: string;
  runName?: string;
  status: string;
  totalCases: number;
  processedCases: number;
  passedCases: number;
  currentCaseCode?: string;
  errorMsg?: string;
  progressPercentage: number;
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

// 一键评测相关
const runModalOpen = ref(false);
const runStarting = ref(false);
const runForm = reactive({
  runName: '',
  evalType: '',
  promptCode: '',
  promptVersion: undefined as number | undefined,
  asyncMode: true,
});

// 报告/任务进度相关
const reportModalOpen = ref(false);
const taskRunning = ref(false);
const currentTask = ref<EvalRunTask | null>(null);
const reportData = ref<EvalReport | null>(null);
let pollTimer: any = null;

// 对比 (Compare) 相关
const compareModalOpen = ref(false);
const compareLoading = ref(false);
const compareForm = reactive({
  baseRunId: '',
  targetRunId: '',
});
const compareResult = ref<any>(null);

const expectedRejectChecked = computed({
  get: () => datasetForm.expectedReject === 1,
  set: (value: boolean) => {
    datasetForm.expectedReject = value ? 1 : 0;
  },
});

const shouldRequireConfirmChecked = computed({
  get: () => datasetForm.shouldRequireConfirm === 1,
  set: (value: boolean) => {
    datasetForm.shouldRequireConfirm = value ? 1 : 0;
  },
});

const taskStatusType = computed(() => {
  if (!currentTask.value) return 'active';
  if (currentTask.value.status === 'COMPLETED') return 'success';
  if (currentTask.value.status === 'FAILED') return 'exception';
  return 'active';
});

const taskStatusText = computed(() => {
  if (!currentTask.value) return '准备中';
  if (currentTask.value.status === 'RUNNING') return '运行中';
  if (currentTask.value.status === 'COMPLETED') return '已完成';
  if (currentTask.value.status === 'FAILED') return '失败: ' + (currentTask.value.errorMsg || '');
  return currentTask.value.status;
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
  { title: '操作', key: 'action', width: 160, fixed: 'right' as const },
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
    expectedChunkKeywords: '',
    expectedReject: 0,
    expectedToolName: '',
    expectedToolParams: '',
    expectedTaskResult: '',
    shouldRequireConfirm: 0,
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

// 一键评测打开 Modal
function handleOpenRunModal() {
  runForm.runName = 'AI评测-' + new Date().toISOString().slice(0, 10);
  runForm.evalType = '';
  runForm.promptCode = '';
  runForm.promptVersion = undefined;
  runForm.asyncMode = true;
  runModalOpen.value = true;
}

// 提交发起评测
async function submitRun() {
  runStarting.value = true;
  try {
    if (runForm.asyncMode) {
      // 异步触发
      const task = await defHttp.post({
        url: `${API_BASE}/run/async`,
        params: {
          runName: runForm.runName,
          evalType: runForm.evalType || undefined,
          promptCode: runForm.promptCode || undefined,
          promptVersion: runForm.promptVersion,
        },
      });
      runModalOpen.value = false;
      antMessage.success('评测任务已启动，正在后台进行评测...');
      startPollTask(task.runId);
    } else {
      // 同步触发
      runModalOpen.value = false;
      reportModalOpen.value = true;
      taskRunning.value = true;
      const report = await defHttp.post({
        url: `${API_BASE}/run`,
        params: {
          runName: runForm.runName,
          evalType: runForm.evalType || undefined,
          promptCode: runForm.promptCode || undefined,
          promptVersion: runForm.promptVersion,
        },
      });
      reportData.value = report;
      taskRunning.value = false;
      antMessage.success('评测完成！');
      loadResults();
    }
  } catch (e: any) {
    antMessage.error(e.message || '发起评测失败');
    taskRunning.value = false;
  } finally {
    runStarting.value = false;
  }
}

// 轮询异步任务进度
function startPollTask(runId: String) {
  reportModalOpen.value = true;
  taskRunning.value = true;
  reportData.value = null;

  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(async () => {
    try {
      const task = await defHttp.get({ url: `${API_BASE}/task/status/${runId}` });
      currentTask.value = task;
      if (task.status === 'COMPLETED') {
        clearInterval(pollTimer);
        taskRunning.value = false;
        antMessage.success('后台评测任务已完成！');
        loadReport(runId);
        loadResults();
      } else if (task.status === 'FAILED') {
        clearInterval(pollTimer);
        taskRunning.value = false;
        antMessage.error('评测失败: ' + (task.errorMsg || ''));
      }
    } catch (e) {
      logError(e);
    }
  }, 2000);
}

function logError(e: any) {
  console.warn('轮询评测进度异常', e);
}

async function loadReport(runId: string) {
  try {
    const report = await defHttp.get({ url: `${API_BASE}/report/${runId}` });
    reportData.value = report;
  } catch (e: any) {
    antMessage.error('加载报告失败: ' + e.message);
  }
}

function handleViewReport(runId: string) {
  taskRunning.value = false;
  reportModalOpen.value = true;
  loadReport(runId);
}

function switchToResultTab(runId: string) {
  reportModalOpen.value = false;
  activeTab.value = 'result';
  resultQuery.runId = runId;
  loadResults();
}

function handleOpenCompareModal() {
  compareForm.baseRunId = '';
  compareForm.targetRunId = '';
  compareResult.value = null;
  compareModalOpen.value = true;
}

async function submitCompare() {
  if (!compareForm.baseRunId || !compareForm.targetRunId) {
    antMessage.warning('请填写两个 RunId 进行对比');
    return;
  }
  compareLoading.value = true;
  try {
    const res = await defHttp.get({
      url: `${API_BASE}/compare`,
      params: { baseRunId: compareForm.baseRunId, targetRunId: compareForm.targetRunId },
    });
    compareResult.value = res;
    antMessage.success('对比完成！');
  } catch (e: any) {
    antMessage.error(e.message || '对比失败');
  } finally {
    compareLoading.value = false;
  }
}

function deltaColor(delta?: number) {
  const val = Number(delta || 0);
  if (val > 0) return '#52c41a';
  if (val < 0) return '#f5222d';
  return '#8c8c8c';
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


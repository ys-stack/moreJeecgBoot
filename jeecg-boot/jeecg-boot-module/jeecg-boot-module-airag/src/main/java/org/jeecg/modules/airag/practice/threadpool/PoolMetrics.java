package org.jeecg.modules.airag.practice.threadpool;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 线程池监控指标 VO
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-21
 */
@Data
@Builder
@Schema(description = "线程池监控指标")
public class PoolMetrics {

    @Schema(description = "线程池名称")
    private String poolName;

    @Schema(description = "核心线程数")
    private int corePoolSize;

    @Schema(description = "最大线程数")
    private int maxPoolSize;

    @Schema(description = "当前活跃线程数")
    private int activeCount;

    @Schema(description = "当前存活线程数")
    private int poolSize;

    @Schema(description = "历史最大线程数")
    private int largestPoolSize;

    @Schema(description = "队列中等待的任务数")
    private int queueSize;

    @Schema(description = "队列总容量")
    private int queueCapacity;

    @Schema(description = "累计提交任务数")
    private long totalSubmitted;

    @Schema(description = "累计完成任务数")
    private long totalCompleted;

    @Schema(description = "累计失败任务数")
    private long totalFailed;

    @Schema(description = "累计被拒绝任务数")
    private long totalRejected;

    @Schema(description = "是否已关闭")
    private boolean isShutdown;

    @Schema(description = "是否已终止")
    private boolean isTerminated;
}

package org.jeecg.modules.airag.practice.threadpool;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jeecg.common.api.vo.Result;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线程池监控接口
 *
 * 查看 practice 模块两个线程池的实时运行指标：
 *   GET /practice/threadpool/metrics — 查看所有线程池指标
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-21
 */
@RestController
@RequestMapping("/practice/threadpool")
@Tag(name = "线程池监控")
public class PracticeThreadPoolMonitor {

    private final PracticeThreadPool streamPool;
    private final PracticeThreadPool asyncPool;

    public PracticeThreadPoolMonitor(
            @Qualifier("practiceStreamPool") PracticeThreadPool streamPool,
            @Qualifier("practiceAsyncPool") PracticeThreadPool asyncPool) {
        this.streamPool = streamPool;
        this.asyncPool = asyncPool;
    }

    @GetMapping("/metrics")
    @Operation(summary = "查看线程池监控指标")
    public Result<Map<String, PoolMetrics>> metrics() {
        Map<String, PoolMetrics> result = new LinkedHashMap<>();
        result.put("stream", streamPool.getMetrics());
        result.put("async", asyncPool.getMetrics());
        return Result.OK(result);
    }
}

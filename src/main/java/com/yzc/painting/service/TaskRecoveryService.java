package com.yzc.painting.service;

import com.yzc.painting.domain.entity.GenerationTask;
import com.yzc.painting.domain.enums.TaskStatus;
import com.yzc.painting.mapper.GenerationImageMapper;
import com.yzc.painting.mapper.GenerationTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 僵死任务回收。
 *
 * <p><b>为什么需要</b>：任务推进完全依赖前端轮询触发。用户关掉页面、
 * 网络断开、或供应商那边永远不返回终态，任务就会永久停在 RUNNING，
 * 既占用额度也让用户看到一个转不完的进度条。
 *
 * <p><b>回收策略</b>：扫描超过阈值未被轮询过的 RUNNING 任务，
 * 主动推进一次；累计重试超过上限则判定失败，避免无限重试消耗资源。
 *
 * <p><b>索引</b>：扫描条件走 idx_status_polled(status, last_polled_at) 联合索引。
 * 没有这个索引时，MySQL 需要全表扫描来找符合条件的行，
 * 扫过的行都会加锁，锁范围远大于实际需要更新的几行，
 * 在线上会直接表现为其他写请求大面积锁等待。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRecoveryService {

    private final GenerationTaskMapper taskMapper;
    private final GenerationImageMapper imageMapper;
    private final TaskPollingService pollingService;

    @Value("${app.recovery.stale-seconds:120}")
    private int staleSeconds;

    @Value("${app.recovery.max-retry:3}")
    private int maxRetry;

    @Value("${app.recovery.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.recovery.interval-ms:60000}")
    public void recoverStaleTasks() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(staleSeconds);
        List<GenerationTask> staleTasks = taskMapper.selectStaleRunning(deadline, batchSize);
        if (staleTasks.isEmpty()) {
            return;
        }
        log.info("发现 {} 个疑似僵死任务，开始回收", staleTasks.size());

        for (GenerationTask task : staleTasks) {
            try {
                if (task.getRetryCount() >= maxRetry) {
                    failOut(task);
                    continue;
                }
                taskMapper.incrRetryCount(task.getId());
                // 复用正常轮询逻辑，四层幂等同样生效，不会因为回收产生重复数据
                pollingService.poll(task.getTaskNo());
            } catch (Exception e) {
                log.error("回收任务失败 taskNo={}", task.getTaskNo(), e);
            }
        }
    }

    /** 超过最大重试次数：有部分结果按部分成功结算，完全没有则判失败 */
    private void failOut(GenerationTask task) {
        int successCount = imageMapper.countByTaskId(task.getId());
        TaskStatus target = successCount > 0 ? TaskStatus.PARTIAL_SUCCESS : TaskStatus.FAILED;
        String reason = "超过最大重试次数 %d，成功 %d/%d"
                .formatted(maxRetry, successCount, task.getTotalCount());
        taskMapper.casUpdateStatus(task.getId(), task.getStatus().name(), target.name(), reason);
        log.warn("任务重试耗尽 taskNo={}, 判定为 {}", task.getTaskNo(), target);
    }
}

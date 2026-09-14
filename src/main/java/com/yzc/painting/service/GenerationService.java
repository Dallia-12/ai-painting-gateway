package com.yzc.painting.service;

import com.yzc.painting.domain.entity.GenerationTask;
import com.yzc.painting.domain.enums.TaskStatus;
import com.yzc.painting.dto.SubmitRequest;
import com.yzc.painting.mapper.GenerationTaskMapper;
import com.yzc.painting.provider.ImageProvider;
import com.yzc.painting.provider.ProviderFactory;
import com.yzc.painting.provider.ProviderSubmitRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 任务提交。
 *
 * <p>核心思路是"提交即返回"：接口只负责落一条任务记录并把内部任务号还给前端，
 * 真正耗时的供应商调用放到异步线程里做。否则一个 Web 线程会被外部服务占用数十秒，
 * 并发稍高就会把 Tomcat 线程池打满，进而拖垮整个应用的其他接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationService {

    private final GenerationTaskMapper taskMapper;
    private final ProviderFactory providerFactory;
    private final ProgressService progressService;
    private final ThreadPoolExecutor submitExecutor;

    /**
     * 创建任务并立即返回内部任务号。
     */
    public String submit(SubmitRequest request) {
        ImageProvider provider = providerFactory.current();

        GenerationTask task = new GenerationTask();
        task.setTaskNo("T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        task.setUserId(request.userId());
        task.setTaskType(request.taskType());
        task.setProvider(provider.name());
        task.setPrompt(request.prompt());
        task.setStatus(TaskStatus.PENDING);
        task.setTotalCount(request.count());
        task.setPendingRequestIds("");
        task.setRetryCount(0);
        task.setVersion(0);
        task.setLastPolledAt(LocalDateTime.now());
        taskMapper.insert(task);

        progressService.markStart(task.getTaskNo());

        // 多图请求拆成多个独立子请求，互不影响：单张失败不会拖垮整个任务
        submitExecutor.execute(() -> submitSubRequests(task, request));

        log.info("任务已创建 taskNo={}, count={}, provider={}",
                task.getTaskNo(), request.count(), provider.name());
        return task.getTaskNo();
    }

    private void submitSubRequests(GenerationTask task, SubmitRequest request) {
        ImageProvider provider = providerFactory.byName(task.getProvider());
        List<String> requestIds = new ArrayList<>();

        for (int slot = 0; slot < request.count(); slot++) {
            try {
                String requestId = provider.submit(ProviderSubmitRequest.builder()
                        .taskType(request.taskType())
                        .prompt(request.prompt())
                        .sourceImageUrl(request.sourceImageUrl())
                        .slotIndex(slot)
                        .build());
                requestIds.add(requestId);
            } catch (Exception e) {
                log.error("子请求提交失败 taskNo={}, slot={}", task.getTaskNo(), slot, e);
            }
        }

        if (requestIds.isEmpty()) {
            taskMapper.casUpdateStatus(task.getId(),
                    TaskStatus.PENDING.name(), TaskStatus.FAILED.name(), "全部子请求提交失败");
            return;
        }

        GenerationTask fresh = taskMapper.selectById(task.getId());
        taskMapper.casUpdatePending(fresh.getId(), GenerationTask.joinIds(requestIds), fresh.getVersion());
        taskMapper.casUpdateStatus(fresh.getId(),
                TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(), null);

        log.info("子请求全部受理 taskNo={}, requestIds={}", task.getTaskNo(), requestIds);
    }
}

package com.yzc.painting.controller;

import com.yzc.painting.dto.ApiResult;
import com.yzc.painting.dto.SubmitRequest;
import com.yzc.painting.dto.TaskDetail;
import com.yzc.painting.service.GenerationService;
import com.yzc.painting.service.TaskPollingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/generation")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationService generationService;
    private final TaskPollingService pollingService;

    /** 提交生成任务，立即返回内部任务号，不等待出图 */
    @PostMapping("/submit")
    public ApiResult<Map<String, String>> submit(@Valid @RequestBody SubmitRequest request) {
        String taskNo = generationService.submit(request);
        return ApiResult.ok(Map.of("taskNo", taskNo));
    }

    /** 轮询任务结果。可被高频并发调用，幂等安全 */
    @GetMapping("/{taskNo}")
    public ApiResult<TaskDetail> detail(@PathVariable String taskNo) {
        return ApiResult.ok(pollingService.poll(taskNo));
    }
}

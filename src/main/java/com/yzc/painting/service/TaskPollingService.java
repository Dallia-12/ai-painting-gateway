package com.yzc.painting.service;

import com.yzc.painting.domain.entity.GenerationImage;
import com.yzc.painting.domain.entity.GenerationTask;
import com.yzc.painting.domain.enums.TaskStatus;
import com.yzc.painting.dto.ImageView;
import com.yzc.painting.dto.TaskDetail;
import com.yzc.painting.exception.BizException;
import com.yzc.painting.mapper.GenerationImageMapper;
import com.yzc.painting.mapper.GenerationTaskMapper;
import com.yzc.painting.provider.ImageProvider;
import com.yzc.painting.provider.ProviderFactory;
import com.yzc.painting.provider.ProviderQueryResult;
import com.yzc.painting.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 结果轮询 —— 本项目的核心。
 *
 * <p><b>要解决的问题</b>：AI 出图需要数十秒，前端会每隔一两秒轮询一次结果接口。
 * 于是同一个任务在同一时刻会有多个请求并发进入处理逻辑，如果不加控制，
 * 会出现重复查询供应商、重复下载图片、同一张图重复写入数据库。
 *
 * <p><b>四层防护</b>，从概率上逐层收窄，最后一层保证绝对正确：
 * <pre>
 *   ① Redis 任务锁      拿不到锁直接返回快照，把并发收敛成单线程处理
 *   ② Redis 已消费集合   跳过本任务已处理过的子请求，避免重复下载与转存
 *   ③ 数据库 CAS 更新    带 version 条件更新待查列表，避免并发互相覆盖
 *   ④ 唯一索引兜底       (task_id, provider_request_id) 撞键即视为已入库
 * </pre>
 *
 * <p>为什么四层都要：① 在单机高并发下可能因锁过期失效；② 依赖 Redis，
 * Redis 重启或 key 被驱逐后就不可靠；③ 只能保护列表字段本身；
 * 只有 ④ 是数据库层的强约束，不依赖任何外部组件的可用性。
 *
 * <p><b>Redis 不可用时仍然正确</b>：任务终态一律以数据库中真实的成功图片数量
 * 重新推算，而不信任 Redis 里的中间计数。缓存丢失只会让流程多做一些重复工作，
 * 不会导致结果错误。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPollingService {

    private final GenerationTaskMapper taskMapper;
    private final GenerationImageMapper imageMapper;
    private final ProviderFactory providerFactory;
    private final ObjectStorage objectStorage;
    private final TaskLockService lockService;
    private final ProgressService progressService;
    private final StringRedisTemplate redisTemplate;

    public TaskDetail poll(String taskNo) {
        GenerationTask task = taskMapper.selectByTaskNo(taskNo);
        if (task == null) {
            throw new BizException("任务不存在: " + taskNo);
        }
        // 终态不再推进，直接返回快照
        if (task.getStatus().isTerminal()) {
            return buildDetail(task);
        }

        // ①【第一层】Redis 任务锁：拿不到锁说明已有线程在推进，返回当前快照即可
        String token = lockService.tryLock(taskNo);
        if (token == null) {
            log.debug("任务正在被其他请求处理，返回快照 taskNo={}", taskNo);
            return buildDetail(task);
        }
        try {
            advance(task);
            return buildDetail(taskMapper.selectByTaskNo(taskNo));
        } finally {
            lockService.unlock(taskNo, token);
        }
    }

    /** 推进一次任务：查询所有未完成子请求，落地已完成的结果，再收敛任务状态 */
    private void advance(GenerationTask task) {
        ImageProvider provider = providerFactory.byName(task.getProvider());
        List<String> pending = task.pendingIds();
        List<String> stillPending = new ArrayList<>();
        int slotBase = task.getTotalCount() - pending.size();

        for (int i = 0; i < pending.size(); i++) {
            String requestId = pending.get(i);

            // ②【第二层】已消费集合：本任务已处理过的子请求直接跳过
            Boolean consumed = redisTemplate.opsForSet()
                    .isMember(RedisKeys.consumedSet(task.getTaskNo()), requestId);
            if (Boolean.TRUE.equals(consumed)) {
                continue;
            }

            ProviderQueryResult result;
            try {
                result = provider.query(requestId);
            } catch (Exception e) {
                log.warn("查询供应商异常，保留待下次轮询 taskNo={}, requestId={}",
                        task.getTaskNo(), requestId, e);
                stillPending.add(requestId);
                continue;
            }

            switch (result.state()) {
                case RUNNING -> stillPending.add(requestId);
                case FAILED -> {
                    log.warn("子请求失败 taskNo={}, requestId={}, reason={}",
                            task.getTaskNo(), requestId, result.failReason());
                    markConsumed(task.getTaskNo(), requestId);
                }
                case SUCCESS -> persist(task, requestId, slotBase + i, result);
            }
        }

        // ③【第三层】CAS 更新待查列表：带 version 条件，避免并发覆盖彼此的处理结果
        int affected = taskMapper.casUpdatePending(
                task.getId(), GenerationTask.joinIds(stillPending), task.getVersion());
        if (affected == 0) {
            log.info("待查列表已被其他线程更新，本次不覆盖 taskNo={}", task.getTaskNo());
        }

        convergeStatus(task);
    }

    /** 下载转存并入库，撞唯一索引即视为已处理 */
    private void persist(GenerationTask task, String requestId, int slotIndex, ProviderQueryResult result) {
        String objectKey = "%s/%s.png".formatted(task.getTaskNo(), requestId);
        String storedUrl = result.imageBytes() != null
                ? objectStorage.save(result.imageBytes(), objectKey)
                : objectStorage.transfer(result.imageUrl(), objectKey);

        GenerationImage image = new GenerationImage();
        image.setTaskId(task.getId());
        image.setProviderRequestId(requestId);
        image.setSlotIndex(slotIndex);
        image.setImageUrl(storedUrl);
        try {
            imageMapper.insert(image);
            log.info("结果已落库 taskNo={}, requestId={}, url={}", task.getTaskNo(), requestId, storedUrl);
        } catch (DuplicateKeyException e) {
            // ④【第四层】唯一索引兜底：说明另一个线程已写入，属正常竞争，不是错误
            log.info("命中唯一索引，结果已由其他线程写入 taskNo={}, requestId={}",
                    task.getTaskNo(), requestId);
        }
        markConsumed(task.getTaskNo(), requestId);
    }

    private void markConsumed(String taskNo, String requestId) {
        redisTemplate.opsForSet().add(RedisKeys.consumedSet(taskNo), requestId);
        redisTemplate.expire(RedisKeys.consumedSet(taskNo), java.time.Duration.ofHours(6));
    }

    /**
     * 收敛任务状态。
     *
     * <p>关键点：成功数量取自数据库 COUNT，而不是 Redis 里的计数器。
     * Redis 只是加速手段，任何时候丢了都不影响最终判定的正确性。
     */
    private void convergeStatus(GenerationTask task) {
        GenerationTask fresh = taskMapper.selectById(task.getId());
        if (fresh.getStatus().isTerminal() || !fresh.pendingIds().isEmpty()) {
            return;
        }

        int successCount = imageMapper.countByTaskId(fresh.getId());
        TaskStatus target;
        String failReason = null;
        if (successCount >= fresh.getTotalCount()) {
            target = TaskStatus.SUCCESS;
        } else if (successCount > 0) {
            target = TaskStatus.PARTIAL_SUCCESS;
            failReason = "期望 %d 张，实际成功 %d 张".formatted(fresh.getTotalCount(), successCount);
        } else {
            target = TaskStatus.FAILED;
            failReason = "全部子请求失败";
        }

        if (!fresh.getStatus().canTransitionTo(target)) {
            log.warn("非法状态流转被拦截 taskNo={}, {} -> {}",
                    fresh.getTaskNo(), fresh.getStatus(), target);
            return;
        }
        taskMapper.casUpdateStatus(fresh.getId(), fresh.getStatus().name(), target.name(), failReason);
        log.info("任务状态收敛 taskNo={}, {} -> {}, 成功 {}/{}",
                fresh.getTaskNo(), fresh.getStatus(), target, successCount, fresh.getTotalCount());
    }

    private TaskDetail buildDetail(GenerationTask task) {
        List<GenerationImage> images = imageMapper.selectByTaskId(task.getId());
        // 按槽位排序：供应商完成顺序是乱的，前端展示顺序必须稳定，否则页面会跳动
        List<ImageView> views = images.stream()
                .sorted(Comparator.comparing(GenerationImage::getSlotIndex))
                .map(img -> new ImageView(img.getSlotIndex(), img.getImageUrl(), 100))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        // 未出图的槽位补上推算进度，让前端每个位置都有东西可展示
        for (int slot = views.size(); slot < task.getTotalCount(); slot++) {
            views.add(new ImageView(slot, null,
                    progressService.currentProgress(task.getTaskNo(), slot, false)));
        }

        return new TaskDetail(
                task.getTaskNo(),
                task.getStatus(),
                task.getTotalCount(),
                images.size(),
                task.getFailReason(),
                views);
    }
}

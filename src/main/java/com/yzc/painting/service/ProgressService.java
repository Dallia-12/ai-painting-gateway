package com.yzc.painting.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 展示进度推算。
 *
 * <p>多数 AI 供应商不提供真实进度，只有"排队中/已完成"两态。前端却需要一个
 * 持续变化的进度条，因此按「已耗时 / 预估总耗时」推算展示值，并遵守两条铁律：
 *
 * <ul>
 *   <li><b>单调递增</b>：推算值只增不减。预估耗时不准时，进度可能倒退，
 *       倒退的进度条比不动的进度条更让人怀疑系统坏了</li>
 *   <li><b>未完成时封顶 99</b>：100% 只能由数据库中的真实成功结果决定。
 *       展示层永远不允许宣布任务完成</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProgressService {

    private final StringRedisTemplate redisTemplate;

    private static final Duration STATE_TTL = Duration.ofHours(6);
    private static final int CAP_BEFORE_DONE = 99;

    /** 预估单张图耗时，用于推算进度分母 */
    private static final long ESTIMATED_SECONDS = 20L;

    public void markStart(String taskNo) {
        redisTemplate.opsForValue().set(
                RedisKeys.taskStartAt(taskNo),
                String.valueOf(Instant.now().getEpochSecond()),
                STATE_TTL);
    }

    /**
     * 计算某个槽位当前应展示的进度。
     *
     * @param done 该槽位是否已拿到真实结果
     */
    public int currentProgress(String taskNo, int slotIndex, boolean done) {
        if (done) {
            return 100;
        }
        int estimated = estimateByElapsed(taskNo);
        String field = String.valueOf(slotIndex);
        String recorded = (String) redisTemplate.opsForHash()
                .get(RedisKeys.slotProgress(taskNo), field);

        int last = recorded == null ? 0 : Integer.parseInt(recorded);
        int next = Math.min(Math.max(estimated, last), CAP_BEFORE_DONE);

        if (next > last) {
            redisTemplate.opsForHash().put(RedisKeys.slotProgress(taskNo), field, String.valueOf(next));
            redisTemplate.expire(RedisKeys.slotProgress(taskNo), STATE_TTL);
        }
        return next;
    }

    private int estimateByElapsed(String taskNo) {
        String startAt = redisTemplate.opsForValue().get(RedisKeys.taskStartAt(taskNo));
        if (startAt == null) {
            // Redis 状态丢失时退化为一个保守的起始值，不影响最终状态判定
            return 5;
        }
        long elapsed = Instant.now().getEpochSecond() - Long.parseLong(startAt);
        return (int) Math.min(elapsed * 100 / ESTIMATED_SECONDS, CAP_BEFORE_DONE);
    }
}

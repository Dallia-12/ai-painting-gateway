package com.yzc.painting.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 基于 Redis SETNX 的短时任务锁。
 *
 * <p>解决的问题：AI 出图耗时数十秒，前端持续轮询，多个轮询请求会并发进入
 * 同一任务的处理逻辑，造成重复查询供应商、重复下载、重复写库。
 *
 * <p>两个关键设计：
 * <ul>
 *   <li><b>持有者校验</b>：value 存随机 token，释放前比对，避免误删别人的锁
 *       （典型场景：自己执行超时锁已自动过期，此时直接 DEL 会删掉后来者的锁）</li>
 *   <li><b>拿不到锁不阻塞</b>：轮询接口的语义是"查当前进度"，
 *       拿不到锁说明已有线程在推进，直接返回数据库快照即可，不必等待</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskLockService {

    private final StringRedisTemplate redisTemplate;

    /** 锁的持有时长需大于单次轮询处理的最坏耗时（含下载转存） */
    private static final Duration LOCK_TTL = Duration.ofSeconds(90);

    public String tryLock(String taskNo) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(RedisKeys.taskLock(taskNo), token, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void unlock(String taskNo, String token) {
        String key = RedisKeys.taskLock(taskNo);
        String current = redisTemplate.opsForValue().get(key);
        if (token.equals(current)) {
            redisTemplate.delete(key);
        } else {
            log.warn("锁已易主或已过期，跳过释放 taskNo={}", taskNo);
        }
    }
}

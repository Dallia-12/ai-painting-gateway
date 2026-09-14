package com.yzc.painting.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 提交任务用的线程池。
 *
 * <p>三个刻意的选择：
 * <ul>
 *   <li><b>不用 Executors 工厂方法</b>：newFixedThreadPool 用的是无界
 *       LinkedBlockingQueue，请求堆积时队列会一直涨到 OOM，且无法感知。
 *       这里用有界队列，容量满了立刻能从拒绝策略上看出系统过载</li>
 *   <li><b>自定义线程名</b>：默认 pool-1-thread-N 看不出归属，
 *       线上 jstack 排查时无法定位是哪个业务的线程池打满了</li>
 *   <li><b>CallerRunsPolicy</b>：队列满时让调用线程自己执行，
 *       相当于给上游施加背压，比直接丢弃任务（AbortPolicy/DiscardPolicy）
 *       更符合"用户已经付费提交"的业务语义</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor submitExecutor() {
        AtomicInteger counter = new AtomicInteger(1);
        return new ThreadPoolExecutor(
                4,
                16,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "painting-submit-" + counter.getAndIncrement());
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}

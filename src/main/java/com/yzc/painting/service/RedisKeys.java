package com.yzc.painting.service;

/** Redis key 统一收口，避免散落在各处拼字符串 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 任务轮询锁：同一任务同一时刻只允许一个线程处理 */
    public static String taskLock(String taskNo) {
        return "painting:lock:task:" + taskNo;
    }

    /** 已消费的供应商子请求ID集合：跳过已处理结果，避免重复下载与重复入库 */
    public static String consumedSet(String taskNo) {
        return "painting:consumed:" + taskNo;
    }

    /** 任务创建时间，用于推算展示进度 */
    public static String taskStartAt(String taskNo) {
        return "painting:start:" + taskNo;
    }

    /** 各图片槽位的已展示进度，保证单调递增 */
    public static String slotProgress(String taskNo) {
        return "painting:progress:" + taskNo;
    }
}

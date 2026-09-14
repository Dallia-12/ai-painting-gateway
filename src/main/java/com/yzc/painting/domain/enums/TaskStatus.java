package com.yzc.painting.domain.enums;

import java.util.Set;

/**
 * 任务状态机。
 *
 * <p>只允许单向流转，禁止回退：
 * <pre>
 *   PENDING ──► RUNNING ──► SUCCESS
 *      │           ├──────► PARTIAL_SUCCESS
 *      └───────────┴──────► FAILED
 * </pre>
 * 状态更新一律走 CAS 条件更新（WHERE status = 当前值），
 * 并发轮询下即使两个线程同时算出结果，也只有一个能写入成功。
 */
public enum TaskStatus {

    /** 已创建，子请求尚未全部提交到供应商 */
    PENDING,
    /** 子请求已提交，等待供应商出图 */
    RUNNING,
    /** 全部子请求成功 */
    SUCCESS,
    /** 部分子请求成功，且已无未完成子请求 */
    PARTIAL_SUCCESS,
    /** 无任何成功结果，或超过最大重试次数 */
    FAILED;

    private static final Set<TaskStatus> TERMINAL = Set.of(SUCCESS, PARTIAL_SUCCESS, FAILED);

    /** 终态不再接受任何变更，轮询直接返回快照 */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 校验目标状态是否为合法的下一跳，拦截非法回退 */
    public boolean canTransitionTo(TaskStatus target) {
        if (this == target) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == RUNNING || target == FAILED;
            case RUNNING -> target.isTerminal();
            case SUCCESS, PARTIAL_SUCCESS, FAILED -> false;
        };
    }
}

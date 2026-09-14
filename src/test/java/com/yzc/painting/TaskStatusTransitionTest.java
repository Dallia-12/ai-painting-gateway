package com.yzc.painting;

import com.yzc.painting.domain.enums.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机流转规则测试。不依赖 MySQL/Redis，可独立运行。
 */
class TaskStatusTransitionTest {

    @Test
    @DisplayName("PENDING 只能进入 RUNNING 或 FAILED")
    void pendingTransitions() {
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.RUNNING));
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.FAILED));
        // 子请求还没提交就宣布成功是非法的
        assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.SUCCESS));
    }

    @Test
    @DisplayName("RUNNING 只能进入终态")
    void runningTransitions() {
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.SUCCESS));
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.PARTIAL_SUCCESS));
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.FAILED));
        // 不允许退回 PENDING
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.PENDING));
    }

    @Test
    @DisplayName("终态不可再流转，这是并发重复结算的第一道防线")
    void terminalIsFinal() {
        for (TaskStatus terminal : new TaskStatus[]{
                TaskStatus.SUCCESS, TaskStatus.PARTIAL_SUCCESS, TaskStatus.FAILED}) {
            assertTrue(terminal.isTerminal());
            for (TaskStatus target : TaskStatus.values()) {
                assertFalse(terminal.canTransitionTo(target),
                        terminal + " 不应能流转到 " + target);
            }
        }
    }

    @Test
    @DisplayName("同状态之间不算流转，避免重复写入同一状态")
    void sameStatusIsNotTransition() {
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.RUNNING));
        assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.PENDING));
    }
}

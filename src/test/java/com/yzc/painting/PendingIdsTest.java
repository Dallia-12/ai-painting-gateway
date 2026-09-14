package com.yzc.painting;

import com.yzc.painting.domain.entity.GenerationTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingIdsTest {

    @Test
    @DisplayName("空串与 null 都应解析为空列表，而不是抛异常或产生空字符串元素")
    void blankParsesToEmpty() {
        GenerationTask task = new GenerationTask();

        task.setPendingRequestIds(null);
        assertTrue(task.pendingIds().isEmpty());

        task.setPendingRequestIds("");
        assertTrue(task.pendingIds().isEmpty());

        task.setPendingRequestIds("  ");
        assertTrue(task.pendingIds().isEmpty());
    }

    @Test
    @DisplayName("解析应过滤空元素，兼容首尾逗号")
    void parseFiltersEmpty() {
        GenerationTask task = new GenerationTask();
        task.setPendingRequestIds("a,,b, c ,");
        assertEquals(List.of("a", "b", "c"), task.pendingIds());
    }

    @Test
    @DisplayName("序列化与解析可互逆")
    void joinAndParseRoundTrip() {
        GenerationTask task = new GenerationTask();
        List<String> ids = List.of("req-1", "req-2", "req-3");
        task.setPendingRequestIds(GenerationTask.joinIds(ids));
        assertEquals(ids, task.pendingIds());
    }

    @Test
    @DisplayName("空列表序列化为空串，对应任务已无待查子请求")
    void joinEmptyList() {
        assertEquals("", GenerationTask.joinIds(List.of()));
    }
}

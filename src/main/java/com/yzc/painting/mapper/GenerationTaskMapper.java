package com.yzc.painting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yzc.painting.domain.entity.GenerationTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface GenerationTaskMapper extends BaseMapper<GenerationTask> {

    @Select("SELECT * FROM t_generation_task WHERE task_no = #{taskNo}")
    GenerationTask selectByTaskNo(@Param("taskNo") String taskNo);

    /**
     * CAS 更新未完成子请求列表。
     *
     * <p>并发轮询时两个线程可能各自处理了不同子请求，若直接 UPDATE 覆盖，
     * 后写入者会把前者的处理结果抹掉。带上 version 条件后，
     * 失败方拿到 0 行影响，由调用方重新读取最新状态再决定是否重算。
     */
    @Update("""
            UPDATE t_generation_task
               SET pending_request_ids = #{pendingIds},
                   version = version + 1,
                   last_polled_at = NOW()
             WHERE id = #{id}
               AND version = #{version}
            """)
    int casUpdatePending(@Param("id") Long id,
                         @Param("pendingIds") String pendingIds,
                         @Param("version") Integer version);

    /**
     * CAS 推进状态。WHERE status = 期望前值，保证状态机不被并发覆盖、不会重复结算。
     */
    @Update("""
            UPDATE t_generation_task
               SET status = #{targetStatus},
                   fail_reason = #{failReason},
                   version = version + 1
             WHERE id = #{id}
               AND status = #{expectedStatus}
            """)
    int casUpdateStatus(@Param("id") Long id,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("targetStatus") String targetStatus,
                        @Param("failReason") String failReason);

    @Update("UPDATE t_generation_task SET retry_count = retry_count + 1, last_polled_at = NOW() WHERE id = #{id}")
    int incrRetryCount(@Param("id") Long id);

    /**
     * 扫描僵死任务：处于 RUNNING 且超过阈值未被轮询过。
     * 走 idx_status_polled 联合索引，避免全表扫描带来的锁范围扩大。
     */
    @Select("""
            SELECT * FROM t_generation_task
             WHERE status = 'RUNNING'
               AND last_polled_at < #{deadline}
             ORDER BY last_polled_at
             LIMIT #{limit}
            """)
    List<GenerationTask> selectStaleRunning(@Param("deadline") LocalDateTime deadline,
                                            @Param("limit") int limit);
}

package com.yzc.painting.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yzc.painting.domain.enums.TaskStatus;
import com.yzc.painting.domain.enums.TaskType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

@Data
@TableName("t_generation_task")
public class GenerationTask {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外暴露的任务号。前端只依赖这个，供应商切换/回填延迟都不影响上层 */
    private String taskNo;

    private Long userId;

    private TaskType taskType;

    private String provider;

    private String prompt;

    private TaskStatus status;

    private Integer totalCount;

    /** 未完成的供应商子请求ID，逗号分隔。空串表示已无待查子请求 */
    private String pendingRequestIds;

    private Integer retryCount;

    /** CAS 版本号 */
    private Integer version;

    private String failReason;

    private LocalDateTime lastPolledAt;

    @TableField(value = "gmt_create", fill = FieldFill.INSERT)
    private LocalDateTime gmtCreate;

    @TableField(value = "gmt_modified", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime gmtModified;

    public List<String> pendingIds() {
        if (pendingRequestIds == null || pendingRequestIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(pendingRequestIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static String joinIds(List<String> ids) {
        StringJoiner joiner = new StringJoiner(",");
        ids.forEach(joiner::add);
        return joiner.toString();
    }
}

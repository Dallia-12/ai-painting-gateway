package com.yzc.painting.dto;

import com.yzc.painting.domain.enums.TaskStatus;

import java.util.List;

public record TaskDetail(String taskNo,
                         TaskStatus status,
                         int totalCount,
                         int successCount,
                         String failReason,
                         List<ImageView> images) {
}

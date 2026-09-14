package com.yzc.painting.dto;

import com.yzc.painting.domain.enums.TaskType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitRequest(@NotNull Long userId,
                            @NotNull TaskType taskType,
                            @NotBlank String prompt,
                            String sourceImageUrl,
                            @Min(1) @Max(4) int count) {
}

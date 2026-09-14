package com.yzc.painting.provider;

import com.yzc.painting.domain.enums.TaskType;
import lombok.Builder;

@Builder
public record ProviderSubmitRequest(TaskType taskType,
                                    String prompt,
                                    String sourceImageUrl,
                                    int slotIndex) {
}

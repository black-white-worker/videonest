package com.example.demo.infrastructure.mq.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.infrastructure.mq.entity.DeadLetterRecord;

public interface DeadLetterRecordService {

    void record(
            String queueName,
            String messageType,
            String businessId,
            String payload,
            String failureReason
    );

    PageResult<DeadLetterRecord> list(long page, long size, String status);

    void retry(Long id);

    void ignore(Long id);
}

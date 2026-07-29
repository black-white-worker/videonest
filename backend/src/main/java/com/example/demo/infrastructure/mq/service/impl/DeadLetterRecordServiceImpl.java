package com.example.demo.infrastructure.mq.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.MessagePublishException;
import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.infrastructure.mq.entity.DeadLetterRecord;
import com.example.demo.infrastructure.mq.mapper.DeadLetterRecordMapper;
import com.example.demo.infrastructure.mq.service.DeadLetterRecordService;
import com.example.demo.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Slf4j
@Service
public class DeadLetterRecordServiceImpl implements DeadLetterRecordService {

    private final DeadLetterRecordMapper recordMapper;
    private final RabbitTemplate rabbitTemplate;

    public DeadLetterRecordServiceImpl(
            DeadLetterRecordMapper recordMapper,
            RabbitTemplate rabbitTemplate
    ) {
        this.recordMapper = recordMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void record(
            String queueName,
            String messageType,
            String businessId,
            String payload,
            String failureReason
    ) {
        DeadLetterRecord record = new DeadLetterRecord();
        record.setQueueName(queueName);
        record.setMessageType(messageType);
        record.setBusinessId(businessId);
        record.setPayload(payload);
        record.setFailureReason(truncate(failureReason, 500));
        record.setStatus("PENDING");
        recordMapper.insert(record);
        log.error(
                "死信已落库，recordId={}，messageType={}，businessId={}",
                record.getId(),
                messageType,
                businessId
        );
    }

    @Override
    public PageResult<DeadLetterRecord> list(
            long page,
            long size,
            String status
    ) {
        LambdaQueryWrapper<DeadLetterRecord> query =
                new LambdaQueryWrapper<DeadLetterRecord>()
                        .eq(
                                StringUtils.hasText(status),
                                DeadLetterRecord::getStatus,
                                status
                        )
                        .orderByDesc(DeadLetterRecord::getCreateTime)
                        .orderByDesc(DeadLetterRecord::getId);
        IPage<DeadLetterRecord> pageData = recordMapper.selectPage(
                new Page<>(page, size),
                query
        );
        return PageResult.of(pageData);
    }

    @Override
    @Transactional
    public void retry(Long id) {
        DeadLetterRecord record = requirePendingRecord(id);
        String messageId = UUID.randomUUID().toString();
        Route route = route(record.getMessageType());
        try {
            rabbitTemplate.convertAndSend(
                    route.exchange(),
                    route.routingKey(),
                    record.getPayload(),
                    message -> {
                        message.getMessageProperties().setMessageId(messageId);
                        if (route.delayed()) {
                            message.getMessageProperties().setHeader("x-delay", 0);
                        }
                        return message;
                    },
                    new CorrelationData(messageId)
            );
        } catch (AmqpException e) {
            throw new MessagePublishException(
                    record.getMessageType(),
                    "死信消息重新投递失败",
                    e
            );
        }

        Long operatorId = SecurityUtils.getCurrentUser().userId();
        if (recordMapper.markHandled(id, "RETRIED", operatorId) == 0) {
            throw new BusinessException(409, "死信记录状态已发生变化");
        }
        log.info(
                "管理员重新投递死信成功，recordId={}，messageId={}，operatorId={}",
                id,
                messageId,
                operatorId
        );
    }

    @Override
    public void ignore(Long id) {
        requirePendingRecord(id);
        Long operatorId = SecurityUtils.getCurrentUser().userId();
        if (recordMapper.markHandled(id, "IGNORED", operatorId) == 0) {
            throw new BusinessException(409, "死信记录状态已发生变化");
        }
        log.info("管理员忽略死信成功，recordId={}，operatorId={}", id, operatorId);
    }

    private DeadLetterRecord requirePendingRecord(Long id) {
        DeadLetterRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(404, "死信记录不存在");
        }
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(409, "死信记录已经处理");
        }
        return record;
    }

    private Route route(String messageType) {
        return switch (messageType) {
            case "VIDEO_PROCESS" -> new Route(
                    RabbitMqConfig.VIDEO_PROCESS_EXCHANGE,
                    RabbitMqConfig.VIDEO_PROCESS_ROUTING_KEY,
                    false
            );
            case "NOTIFICATION" -> new Route(
                    RabbitMqConfig.NOTIFICATION_EXCHANGE,
                    RabbitMqConfig.NOTIFICATION_ROUTING_KEY,
                    false
            );
            case "REVIEW_TIMEOUT" -> new Route(
                    RabbitMqConfig.DELAYED_EXCHANGE,
                    RabbitMqConfig.REVIEW_TIMEOUT_ROUTING_KEY,
                    true
            );
            case "RESOURCE_PURGE" -> new Route(
                    RabbitMqConfig.DELAYED_EXCHANGE,
                    RabbitMqConfig.RESOURCE_PURGE_ROUTING_KEY,
                    true
            );
            default -> throw new BusinessException(400, "不支持重投该类型的死信");
        };
    }

    private String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }

    private record Route(String exchange, String routingKey, boolean delayed) {
    }
}

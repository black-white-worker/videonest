package com.videonest.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videonest.infrastructure.outbox.entity.OutboxEvent;
import com.videonest.infrastructure.outbox.mapper.OutboxEventMapper;
import com.videonest.infrastructure.outbox.service.impl.TransactionalOutboxServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TransactionalOutboxServiceTest {

    @Test
    void appendPersistsPendingEventBeforeMqDelivery() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        TransactionalOutboxServiceImpl service = new TransactionalOutboxServiceImpl(
                mapper,
                new ObjectMapper()
        );

        service.append(
                "event-1",
                "NOTIFICATION",
                "notification.exchange",
                "notification.route",
                new TestPayload(42L)
        );

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();
        assertEquals("event-1", event.getEventId());
        assertEquals("PENDING", event.getStatus());
        assertEquals("{\"id\":42}", event.getPayload());
    }

    private record TestPayload(Long id) {
    }
}

package com.videonest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.flyway.enabled=false",
		"spring.rabbitmq.listener.simple.auto-startup=false",
		"spring.task.scheduling.enabled=false",
		"outbox.enabled=false"
})
class VideoNestApplicationTests {

	@Test
	void contextLoads() {
	}

}

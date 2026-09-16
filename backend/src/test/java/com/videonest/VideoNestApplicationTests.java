package com.videonest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 【集成测试】验证 Spring Boot 应用上下文能够正常启动。
 */
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

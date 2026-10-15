package com.videonest;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * VideoNest 后端应用启动类。
 */
@SpringBootApplication
@EnableScheduling
	@MapperScan({
		"com.videonest.module.*.mapper",
		"com.videonest.infrastructure.mq.mapper",
		"com.videonest.infrastructure.outbox.mapper"
	})
public class VideoNestApplication {

	public static void main(String[] args) {
		SpringApplication.run(VideoNestApplication.class, args);
	}

}

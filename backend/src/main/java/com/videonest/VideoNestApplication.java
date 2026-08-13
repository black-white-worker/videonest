package com.videonest;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

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

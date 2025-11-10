package com.mansereok.server;

import com.mansereok.server.global.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.Retryable;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
@Retryable
public class MansereokApplication {

	public static void main(String[] args) {
		SpringApplication.run(MansereokApplication.class, args);
	}

}

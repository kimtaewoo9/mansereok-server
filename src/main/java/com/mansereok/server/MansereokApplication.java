package com.mansereok.server;

import com.mansereok.server.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
@EnableAsync
public class MansereokApplication {

	public static void main(String[] args) {
		SpringApplication.run(MansereokApplication.class, args);
	}

}

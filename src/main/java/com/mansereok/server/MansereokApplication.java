package com.mansereok.server;

import com.mansereok.server.domain.payment.client.PortOneProperties;
import com.mansereok.server.global.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication // 여기에 ComponentScan 이 들어 있음 .
@EnableConfigurationProperties({JwtProperties.class, PortOneProperties.class})
@EnableRetry
@EnableScheduling
public class MansereokApplication {

	public static void main(String[] args) {
		SpringApplication.run(MansereokApplication.class, args);
	}

}

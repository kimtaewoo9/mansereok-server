package com.mansereok.server.domain.review.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.review.service.RejectionReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 리뷰 작성 자격 조회 응답 JSON 의 키와 값을 고정한다. 프런트는 eligible 키로 작성 버튼을 보일지 정한다.
 *
 * <p>예전 클래스는 필드 이름이 isEligible 이었지만 Lombok getter 때문에 키가 eligible 로 나갔다. record 는 구성 요소 이름이 곧
 * 키라, 구성 요소 이름이 바뀌면 이 테스트가 잡는다.
 */
class ReviewEligibilityResponseJsonTest {

	// 스프링 기본 빌더(Jackson2ObjectMapperBuilder)로 만든 ObjectMapper. 운영 응답은 Spring Boot 가 spring.jackson.* 설정을
	// 얹은 ObjectMapper 로 쓰지만, 여기서는 그 설정을 반영하지 않는다.
	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Test
	@DisplayName("쓸 수 있으면 키는 eligible·reason·message 세 개이고 eligible 은 true, reason 은 null 이다")
	void allowedResponseKeys() throws Exception {
		// when
		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(ReviewEligibilityResponse.allowed()));

		// then
		assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("eligible", "reason", "message");
		assertThat(json.get("eligible").asBoolean()).isTrue();
		assertThat(json.get("reason").isNull()).isTrue();
		assertThat(json.get("message").asText()).isEqualTo("리뷰 작성이 가능합니다.");
	}

	@Test
	@DisplayName("쓸 수 없으면 eligible 은 false 이고 reason 은 이유 이름 문자열, message 는 그 이유의 문구다")
	void rejectedResponseKeys() throws Exception {
		// when
		JsonNode json = objectMapper.readTree(
			objectMapper.writeValueAsString(ReviewEligibilityResponse.rejected(RejectionReason.EXPIRED)));

		// then
		assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("eligible", "reason", "message");
		assertThat(json.get("eligible").asBoolean()).isFalse();
		assertThat(json.get("reason").asText()).isEqualTo("EXPIRED");
		assertThat(json.get("message").asText()).isEqualTo("구매 후 30일이 지나 리뷰를 작성할 수 없습니다.");
	}
}

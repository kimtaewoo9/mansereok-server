package com.mansereok.server.domain.interpret.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.support.fixture.ResultFixture;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 해석 이력 한 줄의 JSON 모양을 프론트와 맞춘 대로 고정한다. 프론트는 resultType 문자열("SAJU", "COMPATIBILITY")로 목록을 거르므로,
 * 결과 유형을 열거 타입으로 바꾼 뒤에도 같은 문자열로 나가야 한다. 운영의 스프링 부트와 같은 기본값의 ObjectMapper 로 쓴다.
 *
 * <p>두 결과를 섞어 최신순으로 늘어놓는 것은 UserServiceTest 가 본다.
 */
class SajuHistoryResponseDtoTest {

	// 스프링 부트의 ObjectMapper 처럼 날짜를 숫자 배열이 아닌 ISO 문자열로 쓴다. 모르는 속성 무시는 이 빌더의 기본값이다.
	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
		.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.build();

	@Test
	@DisplayName("사주 결과로 만든 이력은 resultType SAJU 와 결과의 id·상품명·만든 시각·상태·결제 id 를 담는다")
	void sajuResultBecomesSajuLine() throws Exception {
		// given
		Result result = ResultFixture.withIdAndCreatedAt(Result.createInitial(1L, 30L, "인생 총운"), 11L,
			LocalDateTime.of(2026, 9, 3, 9, 0));
		result.completeInterpretation("해석", "요약");

		// when
		JsonNode json = writeAsJson(SajuHistoryResponseDto.fromSaju(result));

		// then
		assertThat(json.get("resultType").asText()).isEqualTo("SAJU");
		assertThat(json.get("resultId").asLong()).isEqualTo(11L);
		assertThat(json.get("productName").asText()).isEqualTo("인생 총운");
		assertThat(json.get("createdAt").asText()).isEqualTo("2026-09-03T09:00:00");
		assertThat(json.get("status").asText()).isEqualTo("COMPLETED");
		assertThat(json.get("paymentId").asLong()).isEqualTo(30L);
		assertThat(json.size()).as("키는 여섯 개뿐이다").isEqualTo(6);
	}

	@Test
	@DisplayName("궁합 결과로 만든 이력은 resultType COMPATIBILITY 와 결과의 id·상품명·만든 시각·상태·결제 id 를 담는다")
	void compatibilityResultBecomesCompatibilityLine() throws Exception {
		// given
		CompatibilityResult result = ResultFixture.withIdAndCreatedAt(CompatibilityResult.createInitial(1L, 40L, "궁합"),
			21L, LocalDateTime.of(2026, 9, 4, 9, 0));

		// when
		JsonNode json = writeAsJson(SajuHistoryResponseDto.fromCompatibility(result));

		// then
		assertThat(json.get("resultType").asText()).isEqualTo("COMPATIBILITY");
		assertThat(json.get("resultId").asLong()).isEqualTo(21L);
		assertThat(json.get("productName").asText()).isEqualTo("궁합");
		assertThat(json.get("createdAt").asText()).isEqualTo("2026-09-04T09:00:00");
		assertThat(json.get("status").asText()).isEqualTo("INPUT_REQUIRED");
		assertThat(json.get("paymentId").asLong()).isEqualTo(40L);
		assertThat(json.size()).as("키는 여섯 개뿐이다").isEqualTo(6);
	}

	@Test
	@DisplayName("이력은 fromSaju·fromCompatibility 로만 만들고 public 생성자는 없다")
	void hasNoPublicConstructor() {
		assertThat(SajuHistoryResponseDto.class.getConstructors()).isEmpty();
	}

	/** HTTP 응답 본문과 같게 문자열로 쓴 뒤 다시 읽는다. */
	private JsonNode writeAsJson(Object response) throws Exception {
		return objectMapper.readTree(objectMapper.writeValueAsString(response));
	}
}

package com.mansereok.server.domain.review.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.review.entity.Review;
import com.mansereok.server.support.fixture.ReviewFixture;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 리뷰 응답은 로그인하지 않은 사람도 받는다. 응답 JSON 에 어떤 키와 값이 나가는지를 고정한다.
 */
class ReviewResponseTest {

	// 스프링 기본 빌더(Jackson2ObjectMapperBuilder)로 만든 ObjectMapper. 운영 응답은 Spring Boot 가 spring.jackson.* 설정을
	// 얹은 ObjectMapper 로 쓰지만, 여기서는 그 설정을 반영하지 않는다.
	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Test
	@DisplayName("리뷰 응답은 리뷰의 번호·상품·본문·작성 시각을 그대로 담고 작성자 이름만 가린다")
	void copiesReviewAndMasksName() {
		// given
		Review review = ReviewFixture.review()
			.id(7L)
			.subCategoryId(3L)
			.content("풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다.")
			.userName("홍길동")
			.createdAt(LocalDateTime.of(2026, 9, 1, 10, 0))
			.build();

		// when
		ReviewResponse response = ReviewResponse.from(review);

		// then
		assertThat(response).isEqualTo(new ReviewResponse(7L, 3L,
			"풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다.", "홍*동",
			LocalDateTime.of(2026, 9, 1, 10, 0)));
	}

	@Test
	@DisplayName("리뷰 응답 JSON 의 키는 reviewId·subCategoryId·content·userName·createdAt 다섯 개뿐이고 email 은 없다")
	void jsonHasOnlyPublicKeys() throws Exception {
		// given
		Review review = ReviewFixture.review().build();

		// when
		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(ReviewResponse.from(review)));

		// then
		assertThat(json.fieldNames()).toIterable()
			.containsExactlyInAnyOrder("reviewId", "subCategoryId", "content", "userName", "createdAt");
	}

	@Test
	@DisplayName("리뷰 응답 JSON 어디에도 작성자의 원래 이메일과 원래 이름이 나오지 않는다")
	void jsonHasNoRawEmailOrName() throws Exception {
		// given
		Review review = ReviewFixture.review().userName("홍길동").userEmail("hong@example.com").build();

		// when
		String json = objectMapper.writeValueAsString(ReviewResponse.from(review));

		// then
		assertThat(json).doesNotContain("hong@example.com", "홍길동");
	}
}

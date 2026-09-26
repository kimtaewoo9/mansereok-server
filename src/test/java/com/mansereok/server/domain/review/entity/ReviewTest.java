package com.mansereok.server.domain.review.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.support.fixture.OrderFixture;
import com.mansereok.server.support.fixture.UserFixture;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 새 리뷰를 만드는 {@link Review#write} 가 값을 제자리에서 꺼내는지, 본문 길이 규칙을 지키는지 확인한다.
 *
 * <p>회원 id(10), 주문 id(100), 상품 id(3)를 모두 다르게 두어, 두 값을 바꿔 넣으면 드러나게 한다.
 */
class ReviewTest {

	private static final String CONTENT = "풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다.";

	@Nested
	@DisplayName("회원과 주문으로 리뷰를 만들면")
	class WhenWritten {

		@Test
		@DisplayName("작성자 id·이름은 회원에서, 상품 id·주문 id 는 주문에서 가져오고 이메일은 저장하지 않는다")
		void takesValuesFromAuthorAndOrder() {
			// given
			User author = author(10L, "홍길동", "hong@example.com");
			Order order = OrderFixture.paidOrder().id(100L).userId(10L).subCategoryId(3L).build();

			// when
			Review review = Review.write(author, order, CONTENT);

			// then
			assertThat(review)
				.extracting(Review::getUserId, Review::getUserName, Review::getSubCategoryId, Review::getOrderId,
					Review::getContent, Review::getUserEmail)
				.containsExactly(10L, "홍길동", 3L, 100L, CONTENT, null);
		}

		@Test
		@DisplayName("본문의 앞뒤 공백은 길이를 셀 때만 빼고 저장은 보낸 그대로 한다")
		void keepsContentAsSent() {
			// given
			String content = "  " + CONTENT + "\n";

			// when
			Review review = Review.write(author(10L, "홍길동", "hong@example.com"), OrderFixture.paidOrder().build(),
				content);

			// then
			assertThat(review.getContent()).isEqualTo("  " + CONTENT + "\n");
		}
	}

	static Stream<Arguments> acceptedContents() {
		return Stream.of(
			Arguments.of("20자", "가".repeat(20)),
			Arguments.of("앞뒤 공백을 빼고 20자", "   " + "가".repeat(20) + "   "),
			Arguments.of("가운데 공백을 포함해 20자", "가".repeat(10) + " " + "가".repeat(9)),
			Arguments.of("2000자", "가".repeat(2000))
		);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("acceptedContents")
	@DisplayName("앞뒤 공백을 뺀 길이가 20자 이상이고 전체 길이가 2000자 이하이면 리뷰를 만든다")
	void acceptsContent(String situation, String content) {
		// when
		Review review = Review.write(author(10L, "홍길동", "hong@example.com"), OrderFixture.paidOrder().build(),
			content);

		// then
		assertThat(review.getContent()).isEqualTo(content);
	}

	static Stream<Arguments> rejectedContents() {
		String tooShort = "리뷰 내용은 앞뒤 공백을 빼고 최소 20자 이상이어야 합니다.";
		return Stream.of(
			Arguments.of("본문 없음(null)", null, tooShort),
			Arguments.of("공백 20칸", " ".repeat(20), tooShort),
			Arguments.of("19자", "가".repeat(19), tooShort),
			Arguments.of("앞뒤 공백을 붙여 20자를 넘겼지만 공백을 빼면 19자", "  " + "가".repeat(19) + "  ", tooShort),
			Arguments.of("2001자", "가".repeat(2001), "리뷰 내용은 최대 2000자까지 쓸 수 있습니다.")
		);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("rejectedContents")
	@DisplayName("앞뒤 공백을 뺀 길이가 20자 미만이거나 2000자를 넘으면 IllegalArgumentException 으로 거절한다")
	void rejectsContent(String situation, String content, String expectedMessage) {
		// given
		User author = author(10L, "홍길동", "hong@example.com");
		Order order = OrderFixture.paidOrder().build();

		// when & then
		assertThatThrownBy(() -> Review.write(author, order, content))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(expectedMessage);
	}

	private static User author(Long id, String name, String email) {
		User user = User.create("writer", name, "password", email, LocalDate.of(1990, 1, 1), Gender.MALE, true,
			true, false);
		return UserFixture.withId(user, id);
	}
}

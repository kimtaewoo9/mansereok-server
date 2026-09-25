package com.mansereok.server.domain.discount.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.discount.dto.request.DiscountCheckRequest;
import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 할인 코드 확인 요청은 상품 ID 가 없으면 서비스에 닿기 전에 400 으로 끝나는지 확인한다.
 *
 * <p>검증이 없으면 상품 ID 가 null 인 채로 findById 까지 내려가 500(운영) 이나 404(여기서는 저장소가 목이라 빈 결과) 가 된다.
 * 컨트롤러는 스프링 기본 검증기와 {@link GlobalExceptionHandler} 를 쓰는 standalone MockMvc 로 부르고, 서비스는 진짜를 쓰며
 * 저장소는 돌려줄 값만 정한다.
 */
@ExtendWith(MockitoExtension.class)
class DiscountCheckRequestValidationTest {

	private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

	@AfterAll
	static void closeValidatorFactory() {
		VALIDATOR_FACTORY.close();
	}

	@Nested
	@DisplayName("요청 본문의 제약은")
	class Constraints {

		private final Validator validator = VALIDATOR_FACTORY.getValidator();

		@Test
		@DisplayName("상품 ID 가 없으면 '상품 ID는 필수입니다.' 위반 한 건을 낸다")
		void requiresProductId() {
			// given
			DiscountCheckRequest request = new DiscountCheckRequest();
			request.setDiscountCode("WELCOME");

			// when
			Set<ConstraintViolation<DiscountCheckRequest>> violations = validator.validate(request);

			// then
			assertThat(violations).singleElement().satisfies(violation -> {
				assertThat(violation.getPropertyPath()).hasToString("subCategoryId");
				assertThat(violation.getMessage()).isEqualTo("상품 ID는 필수입니다.");
			});
		}

		@Test
		@DisplayName("할인 코드는 비어 있어도 된다")
		void allowsMissingDiscountCode() {
			// given
			DiscountCheckRequest request = new DiscountCheckRequest();
			request.setSubCategoryId(3L);

			// when
			Set<ConstraintViolation<DiscountCheckRequest>> violations = validator.validate(request);

			// then
			assertThat(violations).isEmpty();
		}
	}

	@Nested
	@DisplayName("POST /api/payment/discount 는")
	class Endpoint {

		@Mock
		private DiscountCodeRepository discountCodeRepository;
		@Mock
		private SubCategoryRepository subCategoryRepository;

		private MockMvc mockMvc;

		@BeforeEach
		void setUp() {
			DiscountCodeService discountCodeService =
				new DiscountCodeService(discountCodeRepository, subCategoryRepository);
			mockMvc = MockMvcBuilders.standaloneSetup(new DiscountController(discountCodeService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
		}

		@Test
		@DisplayName("상품 ID 없이 보내면 400 VALIDATION_ERROR 와 필드별 메시지로 답한다")
		void answersBadRequestWithoutProductId() throws Exception {
			// when
			ResultActions result = mockMvc.perform(post("/api/payment/discount")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"discountCode\":\"WELCOME\"}"));

			// then
			result.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.subCategoryId").value("상품 ID는 필수입니다."));
		}

		@Test
		@DisplayName("상품 ID 만 보내면 검증을 통과해 할인 없는 원래 금액을 돌려준다")
		void answersOriginalAmountWithoutDiscountCode() throws Exception {
			// given
			given(subCategoryRepository.findById(3L))
				.willReturn(Optional.of(SubCategoryFixture.paidProduct().id(3L).price(10000).build()));

			// when
			ResultActions result = mockMvc.perform(post("/api/payment/discount")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"subCategoryId\":3}"));

			// then
			result.andExpect(status().isOk())
				.andExpect(jsonPath("$.originalAmount").value(10000))
				.andExpect(jsonPath("$.discountedAmount").value(10000))
				.andExpect(jsonPath("$.discountAmount").value(0));
		}
	}
}

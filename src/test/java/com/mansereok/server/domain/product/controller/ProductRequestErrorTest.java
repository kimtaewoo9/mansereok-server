package com.mansereok.server.domain.product.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.product.service.ProductService;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.RequestErrorExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 로그인 없이 부를 수 있는 상품 조회 API 에 잘못된 요청을 보내면 500 이 아니라 400·405 로 답하는지 확인한다.
 *
 * <p>예전에는 누구나 categoryId 를 빼거나 숫자 자리에 글자를 넣어 500 과 ERROR 로그를 만들 수 있었다. 요청은 서비스에 닿기 전에
 * 끝나므로 서비스는 아무 값도 정하지 않은 목으로 둔다. 보안 필터는 빼고 운영과 같은 두 예외 처리기만 등록한다.
 */
class ProductRequestErrorTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(mock(ProductService.class)))
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler())
			.build();
	}

	@ParameterizedTest(name = "[{index}] GET {0} → 400 {1}")
	@CsvSource(textBlock = """
		# 요청 주소,                          errorCode
		/api/v1/products,                     MISSING_PARAMETER
		/api/v1/products?categoryId=abc,      INVALID_PARAMETER
		/api/v1/products/abc,                 INVALID_PARAMETER
		""")
	@DisplayName("categoryId 가 없거나 숫자 자리에 글자가 오면 400 과 원인별 errorCode 를 돌려준다")
	void badRequest(String url, String errorCode) throws Exception {
		mockMvc.perform(get(url))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value(errorCode));
	}

	@Test
	@DisplayName("상품 주소에 PUT 을 보내면 405 와 Allow: GET 을 돌려준다")
	void methodNotAllowed() throws Exception {
		mockMvc.perform(put("/api/v1/products/1"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(header().string("Allow", "GET"))
			.andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
	}
}

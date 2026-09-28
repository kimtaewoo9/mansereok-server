package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.global.config.SecurityConfig.SpaCsrfTokenRequestHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * CSRF 토큰을 원문 그대로 주고받는 처리기의 약속을 확인한다. 헤더나 파라미터의 값을 가공 없이 꺼내고, XOR 로 가린 값을 풀지 않으며,
 * 요청마다 토큰을 꺼내 XSRF-TOKEN 쿠키가 첫 응답에 실리게 한다.
 */
class SpaCsrfTokenRequestHandlerTest {

	private final SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();
	private final CsrfToken issuedToken = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token");

	@Test
	@DisplayName("X-XSRF-TOKEN 헤더의 값을 그대로 꺼낸다")
	void resolvesHeaderValueAsIs() {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews");
		request.addHeader("X-XSRF-TOKEN", "raw-token");

		// when
		String resolved = handler.resolveCsrfTokenValue(request, issuedToken);

		// then
		assertThat(resolved).isEqualTo("raw-token");
	}

	@Test
	@DisplayName("헤더가 없으면 _csrf 파라미터 값을 그대로 꺼낸다")
	void fallsBackToParameter() {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews");
		request.addParameter("_csrf", "raw-token");

		// when
		String resolved = handler.resolveCsrfTokenValue(request, issuedToken);

		// then
		assertThat(resolved).isEqualTo("raw-token");
	}

	@Test
	@DisplayName("스프링 시큐리티 기본 처리기처럼 XOR 로 가린 토큰을 보내면 풀지 않아 원래 토큰과 다른 값이 된다")
	void doesNotUnmaskXorToken() {
		// given: 기본 처리기가 내려주는 방식으로 토큰을 가린다
		MockHttpServletRequest issueRequest = new MockHttpServletRequest("GET", "/api/auth/csrf-token");
		new XorCsrfTokenRequestAttributeHandler()
			.handle(issueRequest, new MockHttpServletResponse(), () -> issuedToken);
		String maskedToken = ((CsrfToken) issueRequest.getAttribute(CsrfToken.class.getName())).getToken();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews");
		request.addHeader("X-XSRF-TOKEN", maskedToken);

		// when
		String resolved = handler.resolveCsrfTokenValue(request, issuedToken);

		// then
		assertThat(resolved).isEqualTo(maskedToken).isNotEqualTo("raw-token");
	}

	@Test
	@DisplayName("쿠키가 없던 요청도 토큰을 꺼내 XSRF-TOKEN 쿠키를 응답에 싣고, 같은 값을 요청 속성에 남긴다")
	void issuesCookieOnFirstResponse() {
		// given: 운영과 같은 쿠키 저장소. 토큰을 처음 꺼낼 때 쿠키를 만든다
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
		MockHttpServletResponse response = new MockHttpServletResponse();
		DeferredCsrfToken deferredToken = CookieCsrfTokenRepository.withHttpOnlyFalse()
			.loadDeferredToken(request, response);

		// when
		handler.handle(request, response, deferredToken::get);

		// then
		Cookie cookie = response.getCookie("XSRF-TOKEN");
		assertThat(cookie).as("XSRF-TOKEN 쿠키").isNotNull();
		assertThat(((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken())
			.as("컨트롤러가 받는 토큰은 쿠키 값과 같다")
			.isEqualTo(cookie.getValue());
	}
}

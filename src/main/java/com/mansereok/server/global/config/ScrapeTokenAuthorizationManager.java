package com.mansereok.server.global.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * 지표 수집 경로를 수집 토큰을 가진 요청에만 연다. Authorization 헤더가 "Bearer {수집 토큰}" 일 때만 통과시키고, 헤더가 없거나
 * 다른 방식(Basic 등)이거나 토큰이 다르면 막는다. 수집 토큰 설정이 비어 있으면 모든 요청을 막는다.
 *
 * <p>토큰은 {@link MessageDigest#isEqual} 로 비교한다. String.equals 는 처음 다른 글자에서 멈추므로 응답 시간 차이로 토큰을 앞에서부터
 * 한 글자씩 알아낼 여지가 있다. MessageDigest.isEqual 은 받은 값의 내용과 상관없이 설정된 토큰 길이만큼 끝까지 비교한다.
 *
 * <p>이 확인은 회원 로그인과 무관하다. JwtAuthenticationFilter 는 actuator 경로의 Authorization 헤더를 JWT 로 해석하지 않으므로,
 * 막힌 요청은 익명으로 남아 JwtAuthenticationEntryPoint 가 401 로 답한다.
 */
public final class ScrapeTokenAuthorizationManager implements
	AuthorizationManager<RequestAuthorizationContext> {

	private static final String BEARER_PREFIX = "Bearer ";

	// 설정이 비어 있으면 null 이고, 그때는 어떤 요청도 통과시키지 않는다.
	private final byte[] scrapeToken;

	public ScrapeTokenAuthorizationManager(ScrapeTokenProperties properties) {
		this.scrapeToken = properties.hasScrapeToken() ? properties.scrapeToken().getBytes(UTF_8) : null;
	}

	@Override
	public AuthorizationResult authorize(Supplier<Authentication> authentication,
		RequestAuthorizationContext context) {
		return new AuthorizationDecision(carriesScrapeToken(context.getRequest()));
	}

	/**
	 * 스프링 시큐리티 6.4 부터 {@link #authorize} 로 바뀌었지만 아직 구현해야 하는 메서드라 같은 판정을 돌려준다.
	 */
	@Deprecated
	@Override
	public AuthorizationDecision check(Supplier<Authentication> authentication,
		RequestAuthorizationContext context) {
		return new AuthorizationDecision(carriesScrapeToken(context.getRequest()));
	}

	private boolean carriesScrapeToken(HttpServletRequest request) {
		if (scrapeToken == null) {
			return false;
		}
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			return false;
		}
		byte[] presentedToken = authorization.substring(BEARER_PREFIX.length()).getBytes(UTF_8);
		return MessageDigest.isEqual(scrapeToken, presentedToken);
	}
}

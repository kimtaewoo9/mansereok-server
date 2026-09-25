package com.mansereok.server.domain.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 리프레시 토큰 쿠키를 싣고 가거나 새로 심는 요청(재발급, 로그아웃, 소셜 로그인)은 허용한 출처에서 온 것만 받는다.
 *
 * <p>이 경로들은 CSRF 토큰 검사에서 빠져 있어서 다른 사이트의 페이지도 사용자의 브라우저로 부를 수 있다. 막으려는 위험은 경로마다
 * 다르다.
 * <ul>
 *   <li>재발급·로그아웃은 브라우저가 사용자의 리프레시 토큰 쿠키를 싣고 가는 요청이라, 다른 사이트가 사용자 대신 재발급이나
 *   로그아웃을 일으킬 수 있다.</li>
 *   <li>소셜 로그인은 쿠키를 읽지 않고 새 리프레시 토큰 쿠키를 심는다. 다른 사이트가 공격자 계정의 인가 코드로 부르면 사용자의
 *   브라우저에 공격자 계정의 쿠키가 심긴다(로그인 CSRF).</li>
 * </ul>
 * 브라우저는 다른 출처로 보내는 POST 에 Origin 헤더를 붙이므로, 그 값이 허용 목록에 글자 그대로 없으면 403 으로 끝낸다. 목록은
 * CorsProperties 가 소문자와 끝의 / 없는 주소로만 받으므로, 브라우저가 보내는 Origin 에 대해서는 대소문자를 가리지 않는 CORS
 * 설정의 판정과 결과가 같다.
 * Origin 이 없는 요청은 같은 출처에서 온 요청이거나 브라우저가 아닌 클라이언트라 그대로 통과시킨다.
 *
 * <p>CORS 설정도 같은 목록으로 다른 출처의 요청을 거절하지만, CORS 는 응답을 읽을 수 있는 출처를 정하는 설정이다. 이 필터는
 * CorsFilter 보다 앞에서 CSRF 검사 대신 요청 자체를 막아, CORS 설정이 바뀌어도 이 경로들은 계속 막히게 한다.
 * 빈으로 등록하지 않고 SecurityConfig 가 만들어 보안 필터 체인에만 넣는다(빈이면 서블릿 필터로도 한 번 더 등록된다).
 */
@Slf4j
public class AllowedOriginFilter extends OncePerRequestFilter {

	private static final RequestMatcher ORIGIN_CHECKED_REQUESTS = new OrRequestMatcher(
		PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/refresh"),
		PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/sign-out"),
		PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/member/**")
	);

	private final Set<String> allowedOrigins;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public AllowedOriginFilter(List<String> allowedOrigins) {
		this.allowedOrigins = Set.copyOf(allowedOrigins);
	}

	@Override
	protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
		return !ORIGIN_CHECKED_REQUESTS.matches(request);
	}

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain filterChain) throws ServletException, IOException {

		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (origin == null || allowedOrigins.contains(origin)) {
			filterChain.doFilter(request, response);
			return;
		}

		log.warn("허용하지 않은 출처의 요청을 막았습니다. origin={}, path={}", origin, request.getRequestURI());
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), Map.of(
			"status", HttpServletResponse.SC_FORBIDDEN,
			"error", "CORS_ORIGIN_FORBIDDEN",
			"message", "허용하지 않은 출처에서 온 요청입니다.",
			"path", request.getRequestURI()
		));
	}
}

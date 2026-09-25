package com.mansereok.server.domain.auth.filter;

import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.global.exception.JwtAuthenticationException;
import com.mansereok.server.global.exception.JwtSignatureException;
import com.mansereok.server.global.exception.JwtTokenExpiredException;
import com.mansereok.server.global.exception.JwtTokenMalformedException;
import com.mansereok.server.global.exception.JwtTokenMissingException;
import com.mansereok.server.global.exception.JwtUnsupportedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	// 토큰을 해석하지 않고 넘기는 요청. CORS 사전 요청(OPTIONS)과, 회원 로그인과 무관한 운영 경로(actuator)다.
	// 어느 경로를 로그인 없이 열지는 여기서 정하지 않고 SecurityConfig 한 곳에서만 정한다.
	private static final RequestMatcher SKIPPED_REQUESTS = new OrRequestMatcher(
		PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.OPTIONS, "/**"),
		PathPatternRequestMatcher.withDefaults().matcher("/actuator/**")
	);

	private final JwtUtil jwtUtil;

	@Override
	protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
		return SKIPPED_REQUESTS.matches(request);
	}

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain filterChain) throws ServletException, IOException {

		// 토큰이 없으면 익명으로, 잘못된 토큰이면 예외를 요청 속성에 남기고 익명으로 넘긴다.
		// 로그인 없이 여는 경로는 그대로 통과하고, 로그인이 필요한 경로는 진입점이 남긴 예외를 보고 401 로 답한다.
		try {
			String jwtToken = extractJwtFromtRequest(request);

			if (jwtToken != null) {
				validateAndProcessToken(jwtToken, request);
			}
		} catch (JwtAuthenticationException ex) {
			request.setAttribute("jwt.exception", ex);
		} catch (Exception ex) {
			logger.error("JWT 인증 처리 중 예상치 못한 오류 발생", ex);
			request.setAttribute("jwt.exception",
				new JwtAuthenticationException("JWT 처리 중 내부 오류가 발생했습니다.", 500,
					"JWT_INTERNAL_ERROR"));
		}

		filterChain.doFilter(request, response);
	}

	private String extractJwtFromtRequest(HttpServletRequest request) {
		String bearerToken = request.getHeader("Authorization");

		if (bearerToken == null) {
			return null; // 토큰이 없는 것은 정상임.
		}

		if (!bearerToken.startsWith("Bearer ")) {
			throw new JwtTokenMalformedException("Authorization Header 는 'Bearer '로 시작해야 합니다.");
		}

		String token = bearerToken.substring(7);
		if (token.trim().isEmpty()) {
			throw new JwtTokenMissingException("Bearer Token이 비어있습니다.");
		}

		return token;
	}

	private void validateAndProcessToken(String jwtToken, HttpServletRequest request) {
		try {
			// 검증 + 추출 .. 만약에 검증 실패하면 오류뜸 .
			Claims claims = jwtUtil.extractAllClaims(jwtToken);

			String username = claims.getSubject();
			String role = claims.get("role", String.class);

			if (username != null) {
				List<GrantedAuthority> authorities =
					Collections.singletonList(new SimpleGrantedAuthority(role));

				UsernamePasswordAuthenticationToken authentication =
					new UsernamePasswordAuthenticationToken(
						username,
						null,
						authorities
					);

				authentication.setDetails(
					new WebAuthenticationDetailsSource().buildDetails(request));

				// 세션 정보 대신 JWT 토큰 정보로 덮어쓰기
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		} catch (ExpiredJwtException e) {
			throw new JwtTokenExpiredException("JWT 토큰이 만료되었습니다.");
		} catch (UnsupportedJwtException e) {
			throw new JwtUnsupportedException("지원하지 않는 JWT 토큰입니다.");
		} catch (MalformedJwtException e) {
			throw new JwtTokenMalformedException("JWT 토큰 형식이 올바르지 않습니다.");
		} catch (SignatureException e) {
			throw new JwtSignatureException("JWT 토큰의 서명이 유효하지 않습니다.");
		} catch (IllegalArgumentException e) {
			throw new JwtTokenMalformedException("JWT 토큰이 비어있거나 올바르지 않습니다.");
		}
	}
}

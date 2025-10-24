package com.mansereok.server.filter;

import com.mansereok.server.exception.JwtAuthenticationException;
import com.mansereok.server.exception.JwtSignatureException;
import com.mansereok.server.exception.JwtTokenExpiredException;
import com.mansereok.server.exception.JwtTokenMalformedException;
import com.mansereok.server.exception.JwtTokenMissingException;
import com.mansereok.server.exception.JwtUnsupportedException;
import com.mansereok.server.util.JwtUtil;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtUtil jwtUtil;

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain filterChain) throws ServletException, IOException {
		try {
			String jwtToken = extractJwtFromtRequest(request);

			if (jwtToken != null) {
				validateAndProcessToken(jwtToken, request);
			}
		} catch (JwtAuthenticationException ex) {
			// JWT 관련 예외는 request attribute에 저장하여 EntryPoint에서 처리
			request.setAttribute("jwt.exception", ex);
		} catch (Exception ex) {
			// 기타 예외는 일반적인 인증 예외로 처리
			logger.error("JWT 인증 처리 중 예상치 못한 오류 발생", ex);
			request.setAttribute("jwt.exception",
				new JwtAuthenticationException("JWT 처리 중 내부 오류가 발생했습니다.", 500,
					"JWT_INTERNAL_ERROR"));
		}

		// 다음 필터로 요청 전달
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

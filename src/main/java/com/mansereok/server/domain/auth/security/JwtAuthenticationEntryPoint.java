package com.mansereok.server.domain.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.auth.filter.JwtAuthenticationFilter;
import com.mansereok.server.global.exception.JwtAuthenticationException;
import com.mansereok.server.global.exception.JwtErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public void commence(HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException) throws IOException {

		// 응답 헤더 설정
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);

		// JWT 관련 예외가 있는지 확인
		JwtAuthenticationException jwtException =
			(JwtAuthenticationException) request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE);

		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("path", request.getServletPath());

		if (jwtException != null) {
			// 필터가 남긴 오류 종류가 상태 코드, error 값, 안내 문구를 모두 정한다.
			JwtErrorCode errorCode = jwtException.getErrorCode();
			response.setStatus(errorCode.getStatus().value());
			body.put("status", errorCode.getStatus().value());
			body.put("error", errorCode.getError());
			body.put("message", jwtException.getMessage());
			body.put("hint", errorCode.getHint());
		} else {
			// 일반적인 인증 예외 처리
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			body.put("status", HttpServletResponse.SC_UNAUTHORIZED);
			body.put("error", "UNAUTHORIZED");
			body.put("message", "인증이 필요합니다.");
			body.put("hint", "로그인 후 JWT 토큰을 Authorization 헤더에 포함해주세요.");
		}

		// JSON 응답 전송
		objectMapper.writeValue(response.getOutputStream(), body);
	}
}

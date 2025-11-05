package com.mansereok.server.domain.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.global.exception.JwtAuthenticationException;
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
			(JwtAuthenticationException) request.getAttribute("jwt.exception");

		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("path", request.getServletPath());

		if (jwtException != null) {
			// 세분화된 JWT 예외 처리
			response.setStatus(jwtException.getHttpStatus());
			body.put("status", jwtException.getHttpStatus());
			body.put("error", jwtException.getErrorCode());
			body.put("message", jwtException.getMessage());

			// 예외 타입별 추가 정보
			switch (jwtException.getErrorCode()) {
				case "JWT_TOKEN_MISSING":
					body.put("hint", "Authorization 헤더에 'Bearer {토큰}' 형식으로 JWT 토큰을 포함해주세요.");
					break;
				case "JWT_TOKEN_EXPIRED":
					body.put("hint", "토큰이 만료되었습니다. refresh token을 사용하여 새 토큰을 발급받아주세요.");
					break;
				case "JWT_SIGNATURE_INVALID":
					body.put("hint", "토큰이 변조되었거나 유효하지 않습니다. 새로 로그인해주세요.");
					break;
				case "JWT_TOKEN_MALFORMED":
					body.put("hint", "토큰 형식이 올바르지 않습니다. 올바른 JWT 토큰인지 확인해주세요.");
					break;
				case "JWT_TOKEN_UNSUPPORTED":
					body.put("hint", "지원하지 않는 토큰 형식입니다.");
					break;
				default:
					body.put("hint", "인증이 필요합니다. 올바른 JWT 토큰을 제공해주세요.");
					break;
			}
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

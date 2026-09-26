package com.mansereok.server.domain.auth.controller;

import com.mansereok.server.domain.auth.dto.request.LoginRequest;
import com.mansereok.server.domain.auth.dto.request.PasswordResetConfirmDto;
import com.mansereok.server.domain.auth.dto.request.PasswordResetRequestDto;
import com.mansereok.server.domain.auth.dto.request.RegisterRequest;
import com.mansereok.server.domain.auth.dto.response.TokenRefreshResponse;
import com.mansereok.server.domain.auth.dto.response.TokenRefreshResponse.UserDto;
import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.auth.util.RefreshTokenCookies;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.CustomUserDetailsService;
import com.mansereok.server.domain.user.service.RefreshTokenService;
import com.mansereok.server.domain.user.service.RotatedRefreshToken;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.InvalidRefreshTokenException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AuthController {

	private final AuthenticationManager authenticationManager;
	private final UserService userService;
	private final JwtUtil jwtUtil;
	private final RefreshTokenService refreshTokenService;
	private final RefreshTokenCookies refreshTokenCookies;

	/**
	 * 사용자 로그인 (Refresh Token 포함)
	 *
	 * <p>가입하지 않은 이메일, 소셜로 가입한 이메일(비밀번호가 없어 어떤 비밀번호도 맞지 않는다), 틀린 비밀번호는 모두
	 * BadCredentialsException 이 되어 같은 401 INVALID_CREDENTIALS 응답으로 끝난다(GlobalExceptionHandler). 로그인 응답만 보고는
	 * 그 이메일이 가입했는지, 어떤 방식으로 가입했는지 알 수 없게 하려는 것이다.
	 *
	 * @param loginRequest 로그인 요청 정보
	 * @return Access Token과 Refresh Token
	 */
	@PostMapping("/api/auth/sign-in")
	public ResponseEntity<Map<String, Object>> login(
		@Valid @RequestBody LoginRequest loginRequest,
		HttpServletResponse response) {

		Authentication authentication = authenticationManager.authenticate(
			new UsernamePasswordAuthenticationToken(
				loginRequest.getEmail(),
				loginRequest.getPassword()
			)
		);
		User user = ((CustomUserDetailsService.CustomUserPrincipal) authentication.getPrincipal()).getUser();

		Map<String, Object> claims = Map.of(
			"name", user.getName(),
			"role", user.getRole().getAuthority(),
			"email", user.getEmail(),
			"userId", user.getId()
		);

		String accessToken = jwtUtil.generateAccessToken(user.getUsername(), claims);

		Map<String, Object> responseBody = Map.of(
			"accessToken", accessToken,
			"type", "Bearer",
			"user", Map.of(
				"email", user.getEmail(),
				"role", user.getRole().name()
			)
		);

		// 이 기기의 토큰만 새로 넣는다. 다른 기기에서 받은 토큰은 그대로 쓸 수 있다.
		String refreshToken = refreshTokenService.issue(user);
		addCookie(response, refreshTokenCookies.issue(refreshToken));

		return ResponseEntity.ok(responseBody);
	}

	/**
	 * 사용자 회원가입 API
	 *
	 * @param registerRequest 회원가입 요청 정보
	 * @return 생성된 사용자 정보
	 */
	@PostMapping("/api/auth/users")
	public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest registerRequest) {
		User user = userService.createUser(
			registerRequest.getName(),
			registerRequest.getEmail(),
			registerRequest.getPassword(),
			registerRequest.getBirthDate(),
			registerRequest.getGender(),
			registerRequest.isPrivacyPolicyAgreed(),
			registerRequest.isMarketingAgreed()
		);

		return ResponseEntity.ok(Map.of(
			"message", "회원가입이 완료되었습니다.",
			"name", user.getName(),
			"email", user.getEmail(),
			"role", user.getRole().name()
		));
	}

	@PostMapping("/api/auth/refresh")
	public ResponseEntity<TokenRefreshResponse> refreshToken(
		@CookieValue(value = RefreshTokenCookies.NAME, required = false) String token,
		HttpServletResponse response
	) {
		RotatedRefreshToken rotated = rotateOrExpireCookie(token, response);
		User user = rotated.user();

		// 새로운 Access Token 생성
		Map<String, Object> claims = new java.util.HashMap<>();

		claims.put("name", user.getName());
		claims.put("role", user.getRole().getAuthority());
		claims.put("email", user.getEmail());
		claims.put("userId", user.getId());

		String newAccessToken = jwtUtil.generateAccessToken(user.getUsername(), claims);

		addCookie(response, refreshTokenCookies.issue(rotated.token()));

		return ResponseEntity.ok(new TokenRefreshResponse(UserDto.from(user), newAccessToken));
	}

	/**
	 * 쿠키의 토큰 하나만 새 토큰으로 바꾼다(토큰 회전). 같은 회원이 다른 기기에서 받은 토큰은 건드리지 않는다.
	 *
	 * <p>쿠키가 없거나 비었거나, 없거나 폐기·만료·재사용된 토큰이면 InvalidRefreshTokenException(401)을 그대로 던진다. 던지기 전에
	 * 쿠키를 지우는 Set-Cookie 를 응답에 넣어, 쓸 수 없는 쿠키를 브라우저가 요청마다 다시 싣지 않게 한다. 예외 처리기는 응답
	 * 본문만 새로 쓰고 이미 넣은 Set-Cookie 헤더는 그대로 둔다(DispatcherServlet 은 Content-Type·Content-Disposition 과 본문만
	 * 비운다).
	 */
	private RotatedRefreshToken rotateOrExpireCookie(String token, HttpServletResponse response) {
		try {
			if (token == null || token.isBlank()) {
				throw new InvalidRefreshTokenException("인증 정보가 없습니다.");
			}
			return refreshTokenService.rotate(token);
		} catch (InvalidRefreshTokenException e) {
			addCookie(response, refreshTokenCookies.expire());
			throw e;
		}
	}

	@PostMapping("/api/auth/sign-out")
	public ResponseEntity<Void> signOut(
		@CookieValue(value = RefreshTokenCookies.NAME, required = false) String token,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		if (token != null && !token.isBlank()) {
			refreshTokenService.revoke(token);
		}

		addCookie(response, refreshTokenCookies.expire());

		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}

		// 서블릿 세션 쿠키를 지운다. CSRF 토큰은 세션이 아니라 XSRF-TOKEN 쿠키에 있어 이 쿠키와 상관없다.
		Cookie sessionCookie = new Cookie("JSESSIONID", "");
		sessionCookie.setMaxAge(0);
		sessionCookie.setPath("/");
		response.addCookie(sessionCookie);

		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/auth/csrf-token")
	public ResponseEntity<Map<String, String>> getCsrfToken(CsrfToken csrfToken) {
		// 쓰기 요청을 보내기 전에 CSRF 토큰을 받아 가는 API 다. 토큰은 세션이 아니라 XSRF-TOKEN 쿠키에 저장된다
		// (SecurityConfig 의 CookieCsrfTokenRepository). 프론트엔드는 그 값을 X-XSRF-TOKEN 헤더에 담아 보내고, 서버는 헤더 값과
		// 쿠키 값이 같은지 확인한다.
		if (csrfToken != null) {
			return ResponseEntity.ok(Map.of(
				"token", csrfToken.getToken(), // body 에 csrf 토큰 전달 .
				"headerName", csrfToken.getHeaderName(),
				"parameterName", csrfToken.getParameterName()
			));
		}
		log.error("[AuthController.getCsrfToken] csrf token is null");
		return ResponseEntity.status(401).build();
	}

	@PostMapping("/api/auth/password-reset/request")
	public ResponseEntity<?> requestPasswordReset(@Valid @RequestBody PasswordResetRequestDto requestDto) {
		userService.requestPasswordReset(requestDto.email());
		return ResponseEntity.ok(Map.of("message", "비밀번호 재설정 메일이 전송되었습니다."));
	}

	@PostMapping("/api/auth/password-reset/confirm")
	public ResponseEntity<?> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmDto confirmDto) {
		userService.resetPassword(confirmDto.token(), confirmDto.newPassword());
		return ResponseEntity.ok(Map.of("message", "비밀번호가 성공적으로 변경되었습니다."));
	}

	private static void addCookie(HttpServletResponse response, ResponseCookie cookie) {
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}
}

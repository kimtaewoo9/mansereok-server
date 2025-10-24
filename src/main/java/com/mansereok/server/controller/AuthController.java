package com.mansereok.server.controller;

import com.mansereok.server.dto.LoginRequest;
import com.mansereok.server.dto.RegisterRequest;
import com.mansereok.server.dto.TokenRefreshResponse;
import com.mansereok.server.dto.TokenRefreshResponse.UserDto;
import com.mansereok.server.entity.RefreshToken;
import com.mansereok.server.entity.User;
import com.mansereok.server.service.CustomUserDetailsService;
import com.mansereok.server.service.RefreshTokenService;
import com.mansereok.server.service.UserService;
import com.mansereok.server.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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

	/**
	 * 사용자 로그인 (Refresh Token 포함)
	 *
	 * @param loginRequest 로그인 요청 정보
	 * @return Access Token과 Refresh Token
	 */
	@PostMapping("/api/auth/sign-in")
	public ResponseEntity<?> login(
		@Valid @RequestBody LoginRequest loginRequest,
		HttpServletResponse response) {
		try {
			// 1. UserPasswordAuthenticationToken 생성 .
			// 2. AuthenticationManager 가 이를 받아서 검증 ..
			// 3. UserDetailsService 를 통해 사용자 정보 조회 .
			// 4. 비밀번호 일치 여부 확인 .
			// 5. 성공 Authentication 객체 반환 .

			// Spring Security의 AuthenticationManager를 통한 인증
			// Spring Security 가 UserDetailService 사용해서 사용자 정보 가져와서 비밀번호 검증함 .
			// 성공하면 인증된 Authentication 객체 반환 !. 인증 실패하면 AuthenticationException 발생시킴 .
			Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(
					loginRequest.getEmail(),
					loginRequest.getPassword()
				)
			);

			// 인증 성공 시 사용자 정보 조회
			User user = ((CustomUserDetailsService.CustomUserPrincipal) authentication.getPrincipal()).getUser();

			// JWT Access Token 생성 (사용자 정보 포함)
			// 1. Claims 생성
			Map<String, Object> claims = Map.of(
				"name", user.getName(),
				"role", user.getRole().name(),
				"email", user.getEmail(),
				"userId", user.getId() // claims 에 userId 가 들어있음 .
			);

			// 2. accessToken 생성 .
			String accessToken = jwtUtil.generateAccessToken(user.getUsername(), claims);

			// 성공 응답 생성
			Map<String, Object> responseBody = Map.of(
				"accessToken", accessToken,
				"type", "Bearer",
				"user", Map.of(
					"email", user.getEmail(),
					"role", user.getRole().name()
				)
			);

			// 새로운 로그인 시 기존 모든 리프레시 토큰 무효화 및 새 토큰 생성
			RefreshToken refreshToken = refreshTokenService.generateRefreshToken(user);

			// refresh 토큰을 쿠키에 저장
			Cookie refreshCookie = new Cookie("REFRESH_TOKEN", refreshToken.getToken());
			refreshCookie.setHttpOnly(true);
			refreshCookie.setSecure(false);
			refreshCookie.setPath("/");
			refreshCookie.setMaxAge(7 * 24 * 60 * 60); // 7일
			response.addCookie(refreshCookie);

			return ResponseEntity.ok(responseBody);
		} catch (BadCredentialsException e) {
			log.error("로그인 처리 중 오류 발생: {}", e.getMessage(), e);
			return ResponseEntity.badRequest()
				.body(Map.of("error", "잘못된 사용자명 또는 비밀번호입니다."));
		} catch (AuthenticationException e) {
			log.error("로그인 처리 중 오류 발생: {}", e.getMessage(), e);
			return ResponseEntity.badRequest()
				.body(Map.of("error", "인증에 실패했습니다."));
		} catch (Exception e) {
			log.error("로그인 처리 중 오류 발생: {}", e.getMessage(), e);
			return ResponseEntity.internalServerError()
				.body(Map.of("error", "로그인 처리 중 오류가 발생했습니다."));
		}
	}

	/**
	 * 사용자 회원가입 API
	 *
	 * @param registerRequest 회원가입 요청 정보
	 * @return 생성된 사용자 정보
	 */
	@PostMapping("/api/auth/users")
	public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest registerRequest) {
		try {
			User user = userService.createUser(
				registerRequest.getName(),
				registerRequest.getEmail(),
				registerRequest.getPassword(),
				registerRequest.getBirthDate(),
				registerRequest.getGender(),
				registerRequest.isPrivacyPolicyAgreed()
			);

			return ResponseEntity.ok(Map.of(
				"message", "회원가입이 완료되었습니다.",
				"name", user.getName(),
				"email", user.getEmail(),
				"role", user.getRole().name()
			));

		} catch (RuntimeException e) {
			return ResponseEntity.badRequest()
				.body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.internalServerError()
				.body(Map.of("error", "회원가입 처리 중 오류가 발생했습니다."));
		}
	}

	@PostMapping("/api/auth/refresh")
	public ResponseEntity<TokenRefreshResponse> refreshToken(
		@CookieValue("REFRESH_TOKEN") String token,
		HttpServletResponse response
	) {
		RefreshToken refreshToken = refreshTokenService.findByToken(token)
			.orElseThrow(() -> new RuntimeException("유효하지 않거나, 만료된 refresh token 입니다."));

		// refresh token 이 유효한지 검증 .
		if (!refreshToken.isValid()) {
			throw new RuntimeException("만료되었거나 무효화된 토큰입니다.");
		}

		User user = refreshToken.getUser();

		// 새로운 Access Token 생성
		Map<String, Object> claims = Map.of(
			"name", user.getName(),
			"role", user.getRole().name(),
			"email", user.getEmail(),
			"userId", user.getId()
		);

		String newAccessToken = jwtUtil.generateAccessToken(user.getUsername(), claims);

		// 새로운 refresh token 생성 ..(토큰 회전)
		// 기존 토큰은 그대로 유지하여 여러 기기에서 동시 로그인이 가능하도록 합니다.
		RefreshToken newRefreshToken = refreshTokenService.generateRefreshToken(user);

		// refresh 토큰은 쿠키에 저장해서 전달 .
		Cookie refreshCookie = new Cookie("REFRESH_TOKEN", newRefreshToken.getToken());
		refreshCookie.setHttpOnly(true);
		refreshCookie.setSecure(false); //
//		refreshCookie.setSecure(true); // HTTPS 환경에서만

		refreshCookie.setPath("/");
		refreshCookie.setMaxAge(7 * 24 * 60 * 60); // 7일
		response.addCookie(refreshCookie);

		// 응답 생성
		TokenRefreshResponse tokenRefreshResponse = new TokenRefreshResponse(
			new UserDto(
				user.getId().toString(),
				user.getCreatedAt().toString(),
				user.getEmail(),
				user.getUsername(),
				user.getRole().toString(),
				false
			),
			newAccessToken
		);

		return ResponseEntity.ok(tokenRefreshResponse);
	}

	@PostMapping("/api/auth/sign-out")
	public ResponseEntity<Void> signOut(
		@CookieValue(value = "REFRESH_TOKEN", required = false) String token,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		try {
			// 1. 서버 측 Refresh Token 무효화 .
			if (token != null) {
				refreshTokenService.findByToken(token)
					.ifPresent(refreshTokenService::revokeToken);
			}
			// 2. 쿠키 삭제
			Cookie cookie = new Cookie("REFRESH_TOKEN", "");
			cookie.setMaxAge(0);
			cookie.setPath("/");
			cookie.setHttpOnly(true);
			response.addCookie(cookie);

			HttpSession session = request.getSession(false); // 세션이 없으면 새로 만들지 않음
			if (session != null) {
				session.invalidate(); // 세션 파기
			}

			// 4. [추가] JSESSIONID 쿠키 삭제 (선택 사항이지만 권장)
			Cookie csrfCookie = new Cookie("JSESSIONID", "");
			csrfCookie.setMaxAge(0);
			csrfCookie.setPath("/");
			response.addCookie(csrfCookie);

			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			log.error("로그아웃 중 오류 발생", e);
			return ResponseEntity.status(401).build();
		}
	}

	@GetMapping("/api/auth/csrf-token")
	public ResponseEntity<Map<String, String>> getCsrfToken(CsrfToken csrfToken) {
		// 요청을 보내기 전에 먼저 서버로 부터 CSRF 토큰을 달라고 요청을 보냄 .
		// 사용자는 요청을 보낼때 csrf token 을 헤더 같은 곳에 넣어서 요청과 같이 보냄 .
		// 서버는 CSRF 토큰이 있는 요청만 유효하다고 판단하고 처리를 한다 .

		// 서버가 이 csrf 토큰이 공격자가 보낸건지 사용자가 보낸건지 어떻게 알지 ?
		// 서버가 자동으로 토큰을 생성하고 .. session 에 저장함 . 그래서 요청마다 이제 token 을 검증

		// 그러면 모든 요청에 대해서 csrf 토큰을 검증을 해야함 session 사용해서 .. -> 그럼 jwt 사용하는 의미가 없잖아.
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
}

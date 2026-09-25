package com.mansereok.server.domain.auth.controller;

import com.mansereok.server.domain.auth.dto.response.oauth.NaverRedirectDto;
import com.mansereok.server.domain.auth.dto.response.oauth.RedirectDto;
import com.mansereok.server.domain.auth.dto.response.oauth.XRedirectDto;
import com.mansereok.server.domain.auth.service.oauth.GoogleService;
import com.mansereok.server.domain.auth.service.oauth.KakaoService;
import com.mansereok.server.domain.auth.service.oauth.NaverService;
import com.mansereok.server.domain.auth.service.oauth.OauthLoginResult;
import com.mansereok.server.domain.auth.service.oauth.OauthLoginService;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.auth.service.oauth.XService;
import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 소셜 로그인. 네 제공자 모두 "제공자에서 사용자 정보 받기(OauthProfile) → 계정 찾기·가입 → 토큰 응답" 순서로 돈다.
 * 제공자 호출(토큰 교환·프로필 조회)은 각 제공자 서비스의 authenticate 안에서 끝나고, 계정 찾기·가입 규칙은
 * {@link OauthLoginService} 한 곳에 있다.
 *
 * <p>제공자가 요청을 거절하면 401, 제공자에 닿지 못하거나 시간 안에 답이 없으면 503 으로 답한다
 * ({@link com.mansereok.server.global.exception.OauthExceptionHandler}).
 */
@RestController
@RequiredArgsConstructor
public class OauthController {

	private final OauthLoginService oauthLoginService;
	// Oauth
	private final GoogleService googleService;
	private final KakaoService kakaoService;
	private final NaverService naverService;
	private final XService xService;

	// Access Token, Refresh Token
	private final JwtUtil jwtUtil;
	private final RefreshTokenService refreshTokenService;

	@PostMapping("/member/google/doLogin")
	public ResponseEntity<?> googleLogin(
		@RequestBody RedirectDto redirectDto,
		HttpServletResponse response) {

		OauthProfile profile = googleService.authenticate(redirectDto.getCode());

		OauthLoginResult loginResult = oauthLoginService.loginOrRegister(profile);
		return createTokenResponse(response, loginResult);
	}

	@PostMapping("/member/kakao/doLogin")
	public ResponseEntity<?> kakaoLogin(
		@RequestBody RedirectDto redirectDto,
		HttpServletResponse response) {

		OauthProfile profile = kakaoService.authenticate(redirectDto.getCode());

		OauthLoginResult loginResult = oauthLoginService.loginOrRegister(profile);
		return createTokenResponse(response, loginResult);
	}

	// 네이버 로그인은 인가코드뿐 아니라 state 값도 보내야 한다.
	@PostMapping("/member/naver/doLogin")
	public ResponseEntity<?> naverLogin(
		@RequestBody NaverRedirectDto redirectDto,
		HttpServletResponse response
	) {

		OauthProfile profile = naverService.authenticate(redirectDto.getCode(), redirectDto.getState());

		OauthLoginResult loginResult = oauthLoginService.loginOrRegister(profile);
		return createTokenResponse(response, loginResult);
	}

	// X 로그인은 PKCE 를 쓰므로 code_verifier 도 보내야 한다.
	@PostMapping("/member/X/doLogin")
	public ResponseEntity<?> xLogin(
		@RequestBody XRedirectDto redirectDto,
		HttpServletResponse response) {

		OauthProfile profile = xService.authenticate(redirectDto.getCode(),
			redirectDto.getCodeVerifier());

		OauthLoginResult loginResult = oauthLoginService.loginOrRegister(profile);
		return createTokenResponse(response, loginResult);
	}

	private ResponseEntity<?> createTokenResponse(HttpServletResponse response,
		OauthLoginResult loginResult) {
		User user = loginResult.user();
		Map<String, Object> claims = Map.of(
			"role", user.getRole().getAuthority(),
			"email", user.getEmail() != null ? user.getEmail() : "",
			"userId", user.getId()
		);

		// ⭐ user.getUsername()을 사용하여 토큰 생성 (네이버의 경우 랜덤 생성된 ID가 사용됨)
		String accessToken = jwtUtil.generateAccessToken(user.getUsername(), claims);

		RefreshToken refreshToken = refreshTokenService.generateRefreshToken(user);

		// refresh 토큰을 쿠키에 저장
		ResponseCookie refreshCookie = ResponseCookie.from("REFRESH_TOKEN", refreshToken.getToken())
			.path("/")
			.sameSite("None")
			.httpOnly(true)
			.secure(true)
			.maxAge(7 * 24 * 60 * 60)
			.build();

		response.addHeader("Set-Cookie", refreshCookie.toString());

		// 최종 응답 생성 .
		Map<String, Object> responseBody = Map.of(
			"accessToken", accessToken,
			"type", "Bearer",
			"isNewUser", loginResult.newlyRegistered(),
			"user", Map.of(
				"username", user.getUsername(),
				"email", user.getEmail() != null ? user.getEmail() : "",
				"role", user.getRole().name()
			)
		);

		return ResponseEntity.ok(responseBody);
	}
}

package com.mansereok.server.controller;

import com.mansereok.server.dto.AccessTokenDto;
import com.mansereok.server.dto.oauth.GoogleProfileDto;
import com.mansereok.server.dto.oauth.KakaoProfileDto;
import com.mansereok.server.dto.oauth.NaverProfileDto;
import com.mansereok.server.dto.oauth.NaverRedirectDto;
import com.mansereok.server.dto.oauth.RedirectDto;
import com.mansereok.server.dto.oauth.XProfileDto;
import com.mansereok.server.dto.oauth.XRedirectDto;
import com.mansereok.server.entity.RefreshToken;
import com.mansereok.server.entity.SocialType;
import com.mansereok.server.entity.User;
import com.mansereok.server.service.RefreshTokenService;
import com.mansereok.server.service.UserService;
import com.mansereok.server.service.oauth.GoogleService;
import com.mansereok.server.service.oauth.KakaoService;
import com.mansereok.server.service.oauth.NaverService;
import com.mansereok.server.service.oauth.XService;
import com.mansereok.server.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class OauthController {

	private final UserService userService;
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

		// access token 발급 .
		AccessTokenDto accessTokenDto = googleService.getAccessToken(redirectDto.getCode());
		// 사용자 정보 얻기 .
		GoogleProfileDto googleProfileDto =
			googleService.getGoogleProfile(accessTokenDto.getAccess_token());

		// 회원가입이 되어 있지 않다면, 회원가입 해야함.
		User user = userService.getUserBySocialId(googleProfileDto.getSub());
		if (user == null) {
			user = userService.registerWithOauth(
				googleProfileDto.getSub(),
				googleProfileDto.getEmail(),
				googleProfileDto.getName(),
				googleProfileDto.getSub(),
				SocialType.GOOGLE
			);
		}

		// 회원가입이 되어있는 회원이라면, JWT 토큰 발급 + refresh token 발급
		Map<String, Object> claims = Map.of(
			"role", user.getRole().name(),
			"email", user.getEmail(),
			"userId", user.getId()
		);

		// username 에 socialId 가 들어있기 때문에 ..username 넘겨도 되고 socialId 넘겨도 됨 둘이 똑같음 .
		String accessToken = jwtUtil.generateAccessToken(user.getSocialId(), claims);

		RefreshToken refreshToken = refreshTokenService.generateRefreshToken(user);

		// refresh 토큰을 쿠키에 저장
		Cookie refreshCookie = new Cookie("REFRESH_TOKEN", refreshToken.getToken());
		refreshCookie.setHttpOnly(true);
		refreshCookie.setSecure(false);
		refreshCookie.setPath("/");
		refreshCookie.setMaxAge(7 * 24 * 60 * 60); // 7일
		response.addCookie(refreshCookie); // response 에 담아서 전송 .

		// 최종 응답 생성 .
		Map<String, Object> responseBody = Map.of(
			"accessToken", accessToken,
			"type", "Bearer",
			"user", Map.of(
				"username", user.getUsername(),
				"email", user.getEmail(),
				"role", user.getRole().name()
			)
		);

		// access token 이랑 refresh token 반환 .
		// 프론트엔드에서는 이제 token을 꺼내서 localStorage에 저장하면 됨 .
		return ResponseEntity.ok(responseBody);
	}

	@PostMapping("/member/kakao/doLogin")
	public ResponseEntity<?> kakaoLogin(
		@RequestBody RedirectDto redirectDto,
		HttpServletResponse response) {

		// 인가코드 받아서 access token 받아옴
		AccessTokenDto accessTokenDto = kakaoService.getAccessTokenDto(redirectDto.getCode());

		// access token 으로 사용자 정보 얻어오기.
		KakaoProfileDto kakaoProfileDto = kakaoService.getKakaoProfileDto(
			accessTokenDto.getAccess_token());

		// 회원가입 안되어 있으면 회원가입 ..
		User user = userService.getUserBySocialId(kakaoProfileDto.getId());
		if (user == null) {
			user = userService.registerWithOauth(
				kakaoProfileDto.getId(),
				kakaoProfileDto.getKakao_account().getEmail(),
				null, // 카카오는 본명 정보를 주지 않음.
				kakaoProfileDto.getId(),
				SocialType.KAKAO
			);
		}
		// 회원가입 되어 있으면 access token + refresh token 발급 .
		// access token
		Map<String, Object> claims = Map.of(
			"role", user.getRole().name(),
			"email", user.getEmail(),
			"userId", user.getId()
		);
		String accessToken = jwtUtil.generateAccessToken(user.getSocialId(), claims);

		// refresh token
		RefreshToken refreshToken = refreshTokenService.generateRefreshToken(user);
		Cookie refreshCookie = new Cookie("REFRESH_TOKEN", refreshToken.getToken());
		refreshCookie.setHttpOnly(true);
		refreshCookie.setSecure(false);
		refreshCookie.setPath("/");
		refreshCookie.setMaxAge(7 * 24 * 60 * 60); // 7일
		response.addCookie(refreshCookie); // response 에 담아서 전송 .

		Map<String, Object> responseBody = Map.of(
			"accessToken", accessToken,
			"type", "Bearer",
			"user", Map.of(
				"username", user.getUsername(),
				"email", user.getEmail(),
				"role", user.getRole().name()
			)
		);

		// access token 이랑 refresh token 반환 .
		// 프론트엔드에서는 이제 token을 꺼내서 localStorage에 저장하면 됨 .
		return ResponseEntity.ok(responseBody);
	}

	// 네이버 로그인은 .. 인가코드 뿐만 아니라 state 값도 보내야함 .
	@PostMapping("/member/naver/doLogin")
	public ResponseEntity<?> naverLogin(
		@RequestBody NaverRedirectDto redirectDto,
		HttpServletResponse response) {

		// 인가코드로 access token 받아오기 .
		AccessTokenDto accessTokenDto = naverService
			.getAccessTokenDto(redirectDto.getCode(), redirectDto.getState());

		// access token 으로 naver profile
		NaverProfileDto naverProfileDto = naverService
			.getNaverProfileDto(accessTokenDto.getAccess_token());

		User user = userService.getUserBySocialId(naverProfileDto.getResponse().getId());
		// 회원가입 안되어있으면 회원가입
		if (user == null) {
			user = userService.registerWithOauth(
				naverProfileDto.getResponse().getId(),
				naverProfileDto.getResponse().getEmail(),
				naverProfileDto.getResponse().getName(),
				naverProfileDto.getResponse().getId(),
				SocialType.NAVER
			);
		}
		// 회원가입 되어있으면, access token 이랑 refresh token 전달 .
		Map<String, Object> claims = Map.of(
			"role", user.getRole().name(),
			"email", user.getEmail(),
			"userId", user.getId()
		);
		String accessToken = jwtUtil.generateAccessToken(user.getSocialId(), claims);

		RefreshToken refreshToken = refreshTokenService.generateRefreshToken(user);
		Cookie refreshCookie = new Cookie("REFRESH_TOKEN", refreshToken.getToken());
		refreshCookie.setHttpOnly(true);
		refreshCookie.setSecure(false);
		refreshCookie.setPath("/");
		refreshCookie.setMaxAge(7 * 24 * 60 * 60); // 7일
		response.addCookie(refreshCookie);

		Map<String, Object> responseBody = Map.of(
			"accessToken", accessToken,
			"type", "Bearer",
			"user", Map.of(
				"username", user.getUsername(),
				"email", user.getEmail(),
				"role", user.getRole().name()
			)
		);

		return ResponseEntity.ok(responseBody);
	}

	@PostMapping("/member/X/doLogin")
	public ResponseEntity<?> XLogin(
		@RequestBody XRedirectDto redirectDto,
		HttpServletResponse response) {

		// access token 가져오기 .
		AccessTokenDto accessTokenDto = xService.getAccessToken(
			redirectDto.getCode(),
			redirectDto.getCodeVerifier()
		);
		// profile 정보 가져오기 .
		XProfileDto xProfileDto = xService.getXProfileDto(accessTokenDto.getAccess_token());

		User user = userService.getUserBySocialId(xProfileDto.getId());
		if (user == null) {
			user = userService.registerWithOauth(
				xProfileDto.getId(), // social id 로 로그인하게함 .
				xProfileDto.getName(),
				xProfileDto.getEmail() != null ? xProfileDto.getEmail() : "",

				xProfileDto.getId(),
				SocialType.X
			);
		}

		// access token + refresh 토큰 전달.
		Map<String, Object> claims = Map.of(
			"role", user.getRole().name(),
			"email", user.getEmail() != null ? user.getEmail() : "",
			"userId", user.getId()
		);
		String accessToken = jwtUtil.generateAccessToken(user.getSocialId(), claims);

		RefreshToken refreshToken = refreshTokenService.generateRefreshToken(user);
		Cookie refreshCookie = new Cookie("REFRESH_TOKEN", refreshToken.getToken());
		refreshCookie.setHttpOnly(true);
		refreshCookie.setSecure(false);
		refreshCookie.setPath("/");
		refreshCookie.setMaxAge(7 * 24 * 60 * 60); // 7일
		response.addCookie(refreshCookie);

		Map<String, Object> responseBody = Map.of(
			"accessToken", accessToken,
			"type", "Bearer",
			"user", Map.of(
				"username", user.getUsername(),
				"email", user.getEmail() != null ? user.getEmail() : "",
				"role", user.getRole().name()
			)
		);

		return ResponseEntity.ok(responseBody);
	}
}

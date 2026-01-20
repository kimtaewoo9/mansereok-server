package com.mansereok.server.domain.auth.controller;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.GoogleProfileDto;
import com.mansereok.server.domain.auth.dto.response.oauth.KakaoProfileDto;
import com.mansereok.server.domain.auth.dto.response.oauth.NaverProfileDto;
import com.mansereok.server.domain.auth.dto.response.oauth.NaverRedirectDto;
import com.mansereok.server.domain.auth.dto.response.oauth.RedirectDto;
import com.mansereok.server.domain.auth.dto.response.oauth.XProfileDto;
import com.mansereok.server.domain.auth.dto.response.oauth.XRedirectDto;
import com.mansereok.server.domain.auth.service.oauth.GoogleService;
import com.mansereok.server.domain.auth.service.oauth.KakaoService;
import com.mansereok.server.domain.auth.service.oauth.NaverService;
import com.mansereok.server.domain.auth.service.oauth.XService;
import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.RefreshTokenService;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.DuplicateEmailException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
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

	private final UserRepository userRepository;

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

		boolean isNewUser = false;

		if (user == null) {
			// 2. 구글로 가입 안 되어 있으면 → 이메일로 일반 가입 여부 확인
			User existingUser = userRepository.findByEmail(googleProfileDto.getEmail())
				.orElse(null);

			if (existingUser != null) {
				// 이미 일반 회원가입으로 가입된 이메일
				throw new DuplicateEmailException(
					"해당 이메일은 이미 일반 회원가입으로 등록되어 있습니다. " +
						"일반 로그인을 이용해주세요."
				);
			}

			// 3. 신규 구글 회원가입
			user = userService.registerWithOauth(
				googleProfileDto.getSub(),
				googleProfileDto.getEmail(),
				googleProfileDto.getName(),
				googleProfileDto.getSub(),
				SocialType.GOOGLE
			);
			isNewUser = true;
		}

		return createTokenResponse(response, user, isNewUser);
	}

	@PostMapping("/member/kakao/doLogin")
	public ResponseEntity<?> kakaoLogin(
		@RequestBody RedirectDto redirectDto,
		HttpServletResponse response) {

		Cookie deleteCookie = new Cookie("REFRESH_TOKEN", null);
		deleteCookie.setMaxAge(0);
		deleteCookie.setPath("/");
		response.addCookie(deleteCookie);

		// 인가코드 받아서 access token 받아옴
		AccessTokenDto accessTokenDto = kakaoService.getAccessTokenDto(redirectDto.getCode());

		// access token 으로 사용자 정보 얻어오기.
		KakaoProfileDto kakaoProfileDto = kakaoService.getKakaoProfileDto(
			accessTokenDto.getAccess_token());

		log.info("KakaoProfileDto: {} ", kakaoProfileDto);

		User user = userService.getUserBySocialId(kakaoProfileDto.getId());

		boolean isNewUser = false;

		if (user == null) {
			// 2. 카카오로 가입 안 되어 있으면 → 이메일로 일반 가입 여부 확인
			User existingUser = userRepository
				.findByEmail(kakaoProfileDto.getKakao_account().getEmail()).orElse(null);

			if (existingUser != null) {
				// 이미 일반 회원가입으로 가입된 이메일
				throw new DuplicateEmailException(
					"해당 이메일은 이미 일반 회원가입으로 등록되어 있습니다. " +
						"일반 로그인을 이용해주세요."
				);
			}

			// 3. 신규 카카오 회원가입
			user = userService.registerWithOauth(
				kakaoProfileDto.getId(),
				kakaoProfileDto.getKakao_account().getEmail(),
				kakaoProfileDto.getNickname(),
				kakaoProfileDto.getId(),
				SocialType.KAKAO
			);

			isNewUser = true; // 신규 가입시 isNewUser 표시해주기.
		}

		return createTokenResponse(response, user, isNewUser);
	}

	// 네이버 로그인은 .. 인가코드 뿐만 아니라 state 값도 보내야함 .
	// OauthController.java 수정 제안

	@PostMapping("/member/naver/doLogin")
	public ResponseEntity<?> naverLogin(
		@RequestBody NaverRedirectDto redirectDto,
		HttpServletResponse response
	) {

		// 1. 토큰 및 프로필 요청
		AccessTokenDto accessTokenDto = naverService.getAccessTokenDto(redirectDto.getCode(),
			redirectDto.getState());
		NaverProfileDto naverProfileDto = naverService.getNaverProfileDto(
			accessTokenDto.getAccess_token());

		String socialId = naverProfileDto.getResponse().getId();
		String email = naverProfileDto.getResponse().getEmail();

		// 2. Social ID로 회원 조회
		User user = userService.getUserBySocialId(socialId);
		boolean isNewUser = false;

		// 3. ID로 못 찾았을 경우 (현재 질문자님의 상황)
		if (user == null) {
			// 이메일로 다시 찾아봄
			User existingUser = userRepository.findByEmail(email).orElse(null);

			if (existingUser != null) {
				// [중요 수정] 이메일은 있는데, 그게 'NAVER' 회원이면 -> 본인으로 인정!
				if (existingUser.getSocialType() == SocialType.NAVER) {
					user = existingUser;
					// (선택) DB의 SocialID가 바뀌었을 수 있으니 최신값으로 업데이트 로직 추가 권장
					// userService.updateSocialId(user, socialId);
				} else {
					// 다른 소셜(구글, 카카오)이나 일반 가입자면 -> 진짜 중복 에러
					throw new DuplicateEmailException(
						"이미 " + existingUser.getSocialType() + "로 가입된 이메일입니다."
					);
				}
			} else {
				// 이메일도 없으면 -> 진짜 신규 가입
				user = userService.registerWithOauth(
					UUID.randomUUID().toString().substring(0, 10).toUpperCase(), // username 랜덤 생성
					email,
					naverProfileDto.getResponse().getName(),
					socialId,
					SocialType.NAVER
				);
				isNewUser = true;
			}
		}

		// 4. 토큰 발급 및 응답 (기존 코드 동일)
		return createTokenResponse(response, user, isNewUser);
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

		boolean isNewUser = false;

		if (user == null) {
			String email = xProfileDto.getEmail();

			// ⭐ 추가: 이메일 중복 검사 로직
			// X는 이메일이 없을 수도 있으므로, 이메일이 있는 경우에만 체크
			if (email != null && !email.isBlank()) {
				User existingUser = userRepository.findByEmail(email).orElse(null);

				if (existingUser != null) {
					throw new DuplicateEmailException(
						"해당 이메일은 이미 일반 회원가입으로 등록되어 있습니다. " +
							"일반 로그인을 이용해주세요."
					);
				}
			}

			// 3. 신규 X 회원가입
			user = userService.registerWithOauth(
				xProfileDto.getId(), // social id
				xProfileDto.getName(),
				email != null ? email : "", // 이메일 없으면 빈 문자열 처리
				xProfileDto.getId(),
				SocialType.X
			);

			// 신규 가입임을 표시
			isNewUser = true;
		}

		return createTokenResponse(response, user, isNewUser);
	}

	private ResponseEntity<?> createTokenResponse(HttpServletResponse response, User user,
		boolean isNewUser) {
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
			"isNewUser", isNewUser,
			"user", Map.of(
				"username", user.getUsername(),
				"email", user.getEmail() != null ? user.getEmail() : "",
				"role", user.getRole().name()
			)
		);

		return ResponseEntity.ok(responseBody);
	}
}

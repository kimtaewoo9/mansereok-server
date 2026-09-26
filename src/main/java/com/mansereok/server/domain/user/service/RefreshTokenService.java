package com.mansereok.server.domain.user.service;

import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.global.config.JwtProperties;
import com.mansereok.server.global.config.RefreshTokenProperties;
import com.mansereok.server.global.exception.InvalidRefreshTokenException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리프레시 토큰을 발급하고, 새 토큰으로 바꾸고(재발급), 폐기한다(로그아웃).
 *
 * <p>토큰은 기기(브라우저)마다 따로 둔다. 로그인은 새 토큰을 넣기만 하고 회원의 다른 토큰을 지우지 않는다. 그래서 휴대폰에서 로그인해도
 * PC 의 토큰은 그대로 쓸 수 있다. 재발급은 요청에 실려 온 토큰 한 행만 "썼음" 으로 표시하고 새 토큰을 넣는다. 회원의 토큰을 모두 지우는
 * 일은 탈퇴(UserService.deleteUser)만 한다. 쌓이는 만료·쓴 토큰은 RefreshTokenCleanupScheduler 가 지운다.
 *
 * <p>잠그는 순서는 UserService 클래스 설명의 "리프레시 토큰 → users" 를 따른다. 로그인은 새 토큰을 넣을 때 외래 키 확인으로 users 행을
 * 공유 잠금하는 것뿐이다. 재발급은 쓴 토큰 행을 잠근 뒤 새 토큰을 넣으며 users 행을 공유 잠금한다. 예전처럼 회원의 토큰을 user_id 로
 * 지운 뒤 넣지 않으므로, 토큰이 없는 신규 회원 둘이 동시에 로그인해도 빈 틈 잠금끼리 서로를 기다리는 교착이 나지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

	private static final String NOT_FOUND_MESSAGE = "유효하지 않은 리프레시 토큰입니다.";
	private static final String REVOKED_MESSAGE = "로그아웃했거나 폐기된 리프레시 토큰입니다. 다시 로그인해주세요.";
	private static final String EXPIRED_MESSAGE = "만료된 리프레시 토큰입니다. 다시 로그인해주세요.";
	private static final String ALREADY_USED_MESSAGE = "이미 사용한 리프레시 토큰입니다. 다시 로그인해주세요.";

	private static final int TOKEN_BYTES = 32; // 256비트

	private final RefreshTokenRepository refreshTokenRepository;
	private final JwtProperties jwtProperties;
	private final RefreshTokenProperties refreshTokenProperties;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	/**
	 * 로그인한 회원에게 새 토큰을 발급한다. INSERT 한 번만 하고 회원의 다른 토큰은 건드리지 않는다.
	 *
	 * @return 쿠키로 내려줄 토큰 값
	 */
	public String issue(User user) {
		return save(user, LocalDateTime.now(clock));
	}

	/**
	 * 요청에 실려 온 토큰을 새 토큰으로 바꾼다.
	 *
	 * <ol>
	 *   <li>조건부 UPDATE(markUsedIfUsable)로 토큰을 "썼음" 으로 표시한다. 1 이면 이 요청이 처음 쓴 것이라 새 토큰을 발급한다.</li>
	 *   <li>0 이면 토큰 행을 잠가 다시 읽는다. 없으면, 폐기됐으면, 만료됐으면 그 사유로 거절한다. 다른 요청이 쓴 지 유예 시간
	 *   (app.auth.refresh-token.reuse-grace) 안이면 같은 쿠키로 거의 동시에 온 요청으로 보고 새 토큰을 한 번 더 발급한다. 유예
	 *   시간이 지났으면 거절한다.</li>
	 * </ol>
	 *
	 * <p>UPDATE 전에는 이 트랜잭션에서 refresh_tokens 를 읽지 않는다. 먼저 읽으면 그 엔티티가 영속성 컨텍스트에 남아, UPDATE 가 고친
	 * used_at 과 다른 값을 들고 있게 된다.
	 *
	 * <p>유예 시간 안의 재발급은 토큰 행을 잠근 채 새 토큰을 넣는다. 그래서 같은 토큰의 로그아웃(revoke)은 이 재발급이 커밋할 때까지
	 * 기다렸다가, 이 재발급이 넣은 새 토큰까지 폐기한다.
	 *
	 * @throws InvalidRefreshTokenException 토큰이 없거나, 폐기·만료됐거나, 유예 시간이 지난 뒤 다시 쓰였을 때(401)
	 */
	public RotatedRefreshToken rotate(String presentedToken) {
		LocalDateTime now = LocalDateTime.now(clock);
		if (refreshTokenRepository.markUsedIfUsable(presentedToken, now) == 1) {
			return issueNextToken(presentedToken, now);
		}

		RefreshToken presented = refreshTokenRepository.findByTokenForUpdate(presentedToken)
			.orElseThrow(() -> new InvalidRefreshTokenException(NOT_FOUND_MESSAGE));
		// 잠금을 기다린 뒤의 시각으로 판단한다. 처음에 구한 now 는 먼저 온 요청이 used_at 에 적은 시각보다 이를 수 있다.
		LocalDateTime lockedAt = LocalDateTime.now(clock);
		if (presented.isRevoked()) {
			throw new InvalidRefreshTokenException(REVOKED_MESSAGE);
		}
		if (presented.isExpiredAt(lockedAt)) {
			throw new InvalidRefreshTokenException(EXPIRED_MESSAGE);
		}
		if (!presented.wasUsedWithin(refreshTokenProperties.reuseGrace(), lockedAt)) {
			throw new InvalidRefreshTokenException(ALREADY_USED_MESSAGE);
		}
		return issueNextToken(presentedToken, lockedAt);
	}

	/**
	 * 로그아웃한 토큰을 폐기한다. 없는 토큰이면 아무것도 하지 않는다.
	 *
	 * <p>그 토큰이 이미 새 토큰으로 바뀌었으면(used_at 이 있으면), 바뀐 시각부터 유예 시간 안에 그 회원에게 만든 토큰도 폐기한다. 로그아웃과
	 * 재발급이 겹쳐 재발급이 먼저 커밋하면 브라우저에 새 토큰이 남기 때문이다. 재발급은 쓴 토큰 행을 잠근 채 새 토큰을 넣으므로, 첫
	 * UPDATE 가 그 잠금을 기다린 뒤에는 새 토큰이 이미 커밋돼 있다. 같은 몇 초 사이에 다른 기기에서 로그인해 받은 토큰도 함께 폐기된다.
	 * 둘을 가를 컬럼이 없어 받아들인 절충이다.
	 *
	 * <p>새 토큰은 id 를 잠그지 않고 먼저 읽은 뒤 id 로 폐기한다. user_id 로 훑으며 UPDATE 하면 조건에 맞지 않는 행까지 잠가, 한 회원의
	 * 두 기기가 쓴 토큰으로 동시에 로그아웃할 때 서로 상대가 폐기 중인 토큰 행을 기다리다 교착이 났다(READ COMMITTED 에서도 났다).
	 */
	public void revoke(String presentedToken) {
		if (refreshTokenRepository.revokeByToken(presentedToken) == 0) {
			return;
		}
		RefreshToken revoked = refreshTokenRepository.findByToken(presentedToken).orElseThrow();
		if (revoked.getUsedAt() == null) {
			return;
		}
		List<Long> issuedAfterUse = refreshTokenRepository.findUnrevokedIdsCreatedBetween(revoked.getUser().getId(),
			revoked.getUsedAt(), revoked.getUsedAt().plus(refreshTokenProperties.reuseGrace()));
		if (!issuedAfterUse.isEmpty()) {
			refreshTokenRepository.revokeByIds(issuedAfterUse);
		}
	}

	/**
	 * 쓴 토큰의 회원에게 새 토큰을 넣는다. 회원은 fetch join 으로 읽어 이 트랜잭션 안에서 값을 채워 둔다. 잠금 읽기
	 * (findByTokenForUpdate)는 토큰 행만 잠그려고 회원을 함께 읽지 않아서, 영속성 컨텍스트에는 값이 비어 있는 회원 프록시가 남아 있다.
	 * 이 조회가 그 프록시를 채운다. 채우지 않으면 컨트롤러가 트랜잭션 밖에서 회원 값을 꺼낼 때 지연 로딩을 한다.
	 */
	private RotatedRefreshToken issueNextToken(String usedToken, LocalDateTime now) {
		User user = refreshTokenRepository.findWithUserByToken(usedToken).orElseThrow().getUser();
		return new RotatedRefreshToken(save(user, now), user);
	}

	private String save(User user, LocalDateTime now) {
		Duration lifetime = Duration.ofMillis(jwtProperties.refreshTokenExpiration());
		return refreshTokenRepository.save(RefreshToken.issue(newTokenValue(), user, now, lifetime)).getToken();
	}

	/**
	 * 암호학적으로 안전한 난수로 토큰 값을 만든다(Base64 URL, 패딩 없음).
	 */
	private String newTokenValue() {
		byte[] tokenBytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(tokenBytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
	}
}

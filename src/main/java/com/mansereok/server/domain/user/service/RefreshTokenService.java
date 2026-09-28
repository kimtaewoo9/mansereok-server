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
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
		return insertNewToken(user, LocalDateTime.now(clock));
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
	 * 기다렸다가, 이 재발급이 넣은 새 토큰까지 폐기한다. REPEATABLE READ(MySQL 기본값)에서는 0 행을 고친 UPDATE 도 찾은 토큰 행의
	 * 잠금을 트랜잭션 끝까지 쥐지만, READ COMMITTED 에서는 조건에 맞지 않은 행의 잠금을 바로 푼다. 격리 수준에 기대지 않도록 FOR UPDATE
	 * 로 다시 잠가 읽는다. 로컬 MySQL 8.0 에서 FOR UPDATE 를 뺐을 때 REPEATABLE READ 에서는 로그아웃이 여전히 기다렸고, READ COMMITTED
	 * 에서는 기다리지 않아 새 토큰이 폐기되지 않고 남았다. RefreshTokenMySqlTest 가 커넥션 기본 격리 수준을 READ COMMITTED 로 두고 이
	 * 경우를 확인한다.
	 *
	 * <p>유예 시간이 지난 재사용은 거절만 한다. 먼저 쓴 쪽이 받은 새 토큰은 폐기하지 않으므로, 훔친 토큰을 정상 사용자보다 먼저 쓴 쪽은
	 * 그 새 토큰(수명 app.jwt.refresh-token-expiration)으로 재발급을 이어 갈 수 있고 정상 사용자만 401 을 받는다. 재사용을
	 * 알아챘을 때 회원의 토큰을 모두 폐기할지는 아직 정하지 않았다.
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
	 * <p>같은 브라우저가 재발급으로 거쳐 온 토큰이 로그아웃 뒤에 쓸 수 있는 채로 남지 않도록 다음 토큰도 함께 폐기한다. 어느 토큰을 바꿔
	 * 받은 토큰인지 적는 컬럼이 없어, 쓴 시각과 만든 시각이 유예 시간(reuseGrace) 안에 있는지로 고른다. 한 단계만 본다.
	 * <ol>
	 *   <li>이 토큰을 낸 옛 토큰. 이 토큰을 만들기 전 유예 시간 안에 쓰인 토큰이다. 옛 쿠키를 싣고 늦게 온 재발급이 유예 시간 안이라고
	 *   새 토큰을 다시 받지 못하게 한다.</li>
	 *   <li>옛 토큰이 쓰인 뒤 유예 시간 안에 만든 다른 토큰. 같은 쿠키로 거의 동시에 온 재발급들이 하나씩 받은 새 토큰이다. 브라우저에는
	 *   그중 하나만 남고, 로그아웃은 그 토큰으로 온다.</li>
	 *   <li>이 토큰이 쓰인 뒤 유예 시간 안에 만든 토큰. 로그아웃과 재발급이 겹쳐 재발급이 먼저 커밋하면 브라우저에 새 토큰이 남는다.</li>
	 * </ol>
	 * 같은 몇 초 사이에 같은 회원이 다른 기기에서 재발급하거나 로그인해 받은 토큰도 함께 폐기될 수 있다. 둘을 가를 컬럼이 없어 받아들인
	 * 절충이다. 정확히 가르려면 바꾸기 전 토큰의 id 컬럼(수동 DDL)이 필요하다.
	 *
	 * <p>순서가 중요하다. 먼저 이 토큰을 UPDATE 로 폐기한다. 이 토큰으로 유예 시간 안의 재발급이 행을 잠근 채 새 토큰을 넣는 중이면 그
	 * 커밋을 기다린다. 다음에 옛 토큰을 폐기한다. 옛 토큰으로 재발급하는 중이면 역시 그 커밋을 기다린다. 그 뒤에 새 토큰들을 읽어야
	 * 기다리는 동안 커밋된 새 토큰까지 보인다. REPEATABLE READ 는 트랜잭션에서 처음 읽은 때의 데이터를 끝까지 보여 줘서 이 새 토큰을
	 * 놓친다. 그래서 READ COMMITTED 로 돈다. READ COMMITTED 트랜잭션의 쓰기에는 UserService 클래스 설명의 binlog_format 전제가 같이 걸린다.
	 *
	 * <p>함께 폐기할 토큰은 잠그지 않고 먼저 읽은 뒤 id 로 폐기한다. user_id 로 훑으며 UPDATE 하면 조건에 맞지 않는 행까지 잠가, 한 회원의
	 * 두 기기가 쓴 토큰으로 동시에 로그아웃할 때 서로 상대가 폐기 중인 토큰 행을 기다리다 교착이 났다(READ COMMITTED 에서도 났다).
	 *
	 * <p>남은 교착이 하나 있다. 한 회원의 두 기기가 유예 시간 안에 차례로 재발급하고 그 새 토큰으로 동시에 로그아웃하면, 두 로그아웃이
	 * 서로의 토큰을 함께 폐기할 토큰으로 고른다. 로컬 MySQL 8.0 에서 두 로그아웃이 자기 토큰을 폐기한 뒤 함께 멈췄다 가게 하자 세 번 모두
	 * 한쪽이 교착(1213)으로 롤백되어 503 이 될 예외를 받았고, 롤백되지 않은 쪽이 두 기기의 새 토큰을 모두 폐기했다. 두 로그아웃이 자기
	 * 토큰을 폐기한 때부터 함께 폐기할 토큰을 폐기하기까지의 짧은 구간에서 겹쳐야 나는 일이라 재시도를 두지 않았다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void revoke(String presentedToken) {
		if (refreshTokenRepository.revokeByToken(presentedToken) == 0) {
			return;
		}
		RefreshToken revoked = refreshTokenRepository.findByToken(presentedToken).orElseThrow();
		Long memberId = revoked.getUser().getId();
		Duration grace = refreshTokenProperties.reuseGrace();

		List<RefreshToken> replacedTokens = refreshTokenRepository.findUsedBetween(memberId,
			revoked.getCreatedAt().minus(grace), revoked.getCreatedAt());
		revokeByIdsIfAny(replacedTokens.stream().filter(token -> !token.isRevoked()).map(RefreshToken::getId).toList());

		// 옛 토큰을 폐기한 뒤에 읽는다. 폐기하며 기다린 재발급이 넣은 새 토큰까지 보인다.
		Set<Long> issuedWithinGrace = new TreeSet<>();
		for (RefreshToken replaced : replacedTokens) {
			issuedWithinGrace.addAll(findUnrevokedIdsIssuedWithinGraceAfter(memberId, replaced.getUsedAt(), grace));
		}
		if (revoked.getUsedAt() != null) {
			issuedWithinGrace.addAll(findUnrevokedIdsIssuedWithinGraceAfter(memberId, revoked.getUsedAt(), grace));
		}
		revokeByIdsIfAny(List.copyOf(issuedWithinGrace));
	}

	private List<Long> findUnrevokedIdsIssuedWithinGraceAfter(Long memberId, LocalDateTime usedAt, Duration grace) {
		return refreshTokenRepository.findUnrevokedIdsCreatedBetween(memberId, usedAt, usedAt.plus(grace));
	}

	private void revokeByIdsIfAny(List<Long> ids) {
		if (!ids.isEmpty()) {
			refreshTokenRepository.revokeByIds(ids);
		}
	}

	/**
	 * 쓴 토큰의 회원에게 새 토큰을 넣는다. 회원은 fetch join 으로 읽어 이 트랜잭션 안에서 값을 채워 둔다. 잠금 읽기
	 * (findByTokenForUpdate)는 토큰 행만 잠그려고 회원을 함께 읽지 않아서, 영속성 컨텍스트에는 값이 비어 있는 회원 프록시가 남아 있다.
	 * 이 조회가 그 프록시를 채운다. 채우지 않으면 컨트롤러가 트랜잭션 밖에서 회원 값을 꺼낼 때 지연 로딩을 한다.
	 */
	private RotatedRefreshToken issueNextToken(String usedToken, LocalDateTime now) {
		User user = refreshTokenRepository.findWithUserByToken(usedToken).orElseThrow().getUser();
		return new RotatedRefreshToken(insertNewToken(user, now), user);
	}

	/**
	 * 새 토큰 값을 만들어 회원의 토큰 행으로 넣고, 그 값을 돌려준다. 로그인(issue)과 재발급이 함께 쓴다.
	 */
	private String insertNewToken(User user, LocalDateTime now) {
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

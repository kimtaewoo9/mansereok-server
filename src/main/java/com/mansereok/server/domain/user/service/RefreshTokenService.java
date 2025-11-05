package com.mansereok.server.domain.user.service;

import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.global.config.JwtProperties;
import jakarta.transaction.Transactional;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final JwtProperties jwtProperties;
	private final SecureRandom secureRandom = new SecureRandom();

	/**
	 * 사용자를 위한 새로운 Refresh Token을 생성한다. 토큰 회전(Token Rotation) 패턴 적용
	 *
	 * @param user 토큰을 생성할 사용자
	 * @return 생성된 RefreshToken 엔티티
	 */
	public RefreshToken generateRefreshToken(User user) {
		cleanupOldTokens(user);

		// 새로운 토큰 생성
		String token = generateSecureRandomToken();
		LocalDateTime expiresAt = LocalDateTime.now()
			.plusSeconds(jwtProperties.refreshTokenExpiration() / 1000);

		RefreshToken refreshToken = new RefreshToken(token, user, expiresAt);
		return refreshTokenRepository.save(refreshToken);
	}

	/**
	 * Refresh Token을 검증하고 조회한다.
	 *
	 * @param token 검증할 Refresh Token 문자열
	 * @return 유효한 RefreshToken 엔티티 또는 빈 Optional
	 */
	public Optional<RefreshToken> findByToken(String token) {
		return refreshTokenRepository.findByToken(token)
			.filter(RefreshToken::isValid);
	}

	/**
	 * Refresh Token을 사용 처리한다. 토큰 회전 패턴에서 사용된 토큰 추적을 위함
	 *
	 * @param refreshToken 사용할 RefreshToken
	 */
	public void markAsUsed(RefreshToken refreshToken) {
		refreshToken.setUsedAt(LocalDateTime.now());
		refreshTokenRepository.save(refreshToken);
	}

	/**
	 * 사용자의 모든 Refresh Token을 무효화한다. 로그아웃, 비밀번호 변경, 보안 위반 시 사용
	 *
	 * @param user 토큰을 무효화할 사용자
	 */
	public void revokeAllUserTokens(User user) {
		refreshTokenRepository.revokeAllUserTokens(user);
	}

	/**
	 * 특정 Refresh Token을 무효화한다.
	 *
	 * @param refreshToken 무효화할 RefreshToken
	 */
	public void revokeToken(RefreshToken refreshToken) {
		refreshToken.setRevoked(true);
		refreshTokenRepository.save(refreshToken);
	}

	/**
	 * 만료된 토큰들을 데이터베이스에서 삭제한다. 정기적으로 실행하여 저장소 크기 관리
	 */
	public void cleanupExpiredTokens() {
		refreshTokenRepository.deleteExpiredTokens(LocalDateTime.now());
	}

	/**
	 * 사용자의 오래된 토큰들을 정리한다.
	 *
	 * @param user 정리할 사용자
	 */
	private void cleanupOldTokens(User user) {
		refreshTokenRepository.deleteByUser(user);
	}

	/**
	 * 암호학적으로 안전한 랜덤 토큰을 생성한다.
	 *
	 * @return Base64 인코딩된 랜덤 토큰 문자열
	 */
	private String generateSecureRandomToken() {
		byte[] tokenBytes = new byte[32]; // 256비트
		secureRandom.nextBytes(tokenBytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
	}

	public RefreshToken save(RefreshToken refreshToken) {
		return refreshTokenRepository.save(refreshToken);
	}
}

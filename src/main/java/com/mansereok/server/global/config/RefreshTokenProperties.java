package com.mansereok.server.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 리프레시 토큰 재발급 설정(app.auth.refresh-token).
 *
 * <p>reuseGrace 는 이미 새 토큰으로 바꾼(쓴) 토큰을 다시 받아도 새 토큰을 한 번 더 내주는 시간이다. 기본 10초. 액세스 토큰이 만료된
 * 직후 여러 탭이나 병렬 API 호출이 같은 쿠키로 재발급을 거의 동시에 부르면, 먼저 온 요청만 토큰을 쓰고 나머지는 "이미 쓴 토큰" 을 받는다.
 * 이 시간 안이면 그 요청들도 새 토큰을 받아 강제로 로그아웃되지 않는다. 0 이면 쓴 토큰은 거절한다.
 *
 * <p>로그아웃(RefreshTokenService.revoke)도 이 시간을 쓴다. 로그아웃한 토큰을 낸 옛 토큰과, 옛 토큰이나 로그아웃한 토큰이 쓰인 뒤 이
 * 시간 안에 만든 토큰을 함께 폐기한다.
 *
 * <p>쓴 시각(used_at)과 만든 시각(created_at)을 소수 초가 없는 컬럼(schema.sql 의 refresh_tokens 는 TIMESTAMP)에 적으면 MySQL 이
 * 초 단위로 반올림한다. 로컬 MySQL 8.0 에서 12:00:00.600 을 TIMESTAMP 에 넣으면 12:00:01 이 됐다. 그래서 유예의 경계가 0.5초
 * 안팎 흔들린다. 0 이어도 쓴 뒤 0.5초 안에 온 요청은 쓴 시각이 요청 시각보다 늦게 적혀 새 토큰을 받을 수 있다. 테스트 DB 는
 * ddl-auto 가 DATETIME(6) 으로 만들어 이 차이가 드러나지 않는다. 없애려면 두 컬럼을 DATETIME(6) 으로 바꾸는 수동 DDL 이 필요하다.
 *
 * <p>대가로, 이 시간 안에 훔친 토큰을 쓴 요청과 정상 병렬 요청을 구분하지 못한다. 흔히 쓰는 절충이다.
 */
@ConfigurationProperties(prefix = "app.auth.refresh-token")
public record RefreshTokenProperties(@DefaultValue("10s") Duration reuseGrace) {

	public RefreshTokenProperties {
		if (reuseGrace == null || reuseGrace.isNegative()) {
			throw new IllegalArgumentException("app.auth.refresh-token.reuse-grace 는 0 이상이어야 합니다: " + reuseGrace);
		}
	}
}

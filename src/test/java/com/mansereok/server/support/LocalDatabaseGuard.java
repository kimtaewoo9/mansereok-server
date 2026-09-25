package com.mansereok.server.support;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 테스트가 붙은 DB 가 이 PC 의 테스트 전용 스키마인지 확인한다.
 *
 * <p>실제 MySQL 테스트는 행을 만들고 지운다. 환경변수가 잘못 잡혀 개발·운영 DB 에 붙으면 그 DB 의 데이터를 건드리게 되므로,
 * 호스트가 127.0.0.1·localhost 이고 스키마 이름이 _test 로 끝나거나 중간에 _test_ 가 있을 때만 통과시킨다.
 * mansereok_test 와 스택마다 따로 둔 mansereok_test_develop 은 통과하고, mansereok·mysql·mansereok_latest 는 멈춘다.
 */
public final class LocalDatabaseGuard {

	private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost");
	private static final String JDBC_PREFIX = "jdbc:";

	private LocalDatabaseGuard() {
	}

	/**
	 * jdbcUrl 의 호스트가 이 PC 이고 schemaName 이 테스트 전용 스키마 이름인지 확인한다.
	 *
	 * @throws IllegalStateException 둘 중 하나라도 맞지 않을 때
	 */
	public static void check(String jdbcUrl, String schemaName) {
		if (!isLocalHost(jdbcUrl) || !isTestSchema(schemaName)) {
			throw new IllegalStateException("로컬 테스트 DB 가 아니다: " + jdbcUrl + ", " + schemaName);
		}
	}

	/**
	 * 스키마 이름을 URL 경로에서 읽어 {@link #check(String, String)} 한다. DB 에 붙기 전, 설정값만 있을 때 쓴다.
	 *
	 * @throws IllegalStateException 호스트가 이 PC 가 아니거나 URL 의 스키마 이름이 테스트 전용 스키마 이름이 아닐 때
	 */
	public static void checkUrl(String jdbcUrl) {
		check(jdbcUrl, schemaNameIn(jdbcUrl));
	}

	private static boolean isLocalHost(String jdbcUrl) {
		URI uri = toUri(jdbcUrl);
		if (uri == null || uri.getHost() == null) {
			return false;
		}
		return LOCAL_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
	}

	private static boolean isTestSchema(String schemaName) {
		if (schemaName == null) {
			return false;
		}
		String name = schemaName.toLowerCase(Locale.ROOT);
		return name.endsWith("_test") || name.contains("_test_");
	}

	private static String schemaNameIn(String jdbcUrl) {
		URI uri = toUri(jdbcUrl);
		if (uri == null || uri.getPath() == null || uri.getPath().length() <= 1) {
			return null;
		}
		return uri.getPath().substring(1);
	}

	/**
	 * jdbc:mysql://host:port/schema?... 를 URI 로 바꾼다. 형식이 다르면(여러 호스트, loadbalance 등) null 을 돌려주어
	 * 검사가 실패 쪽으로 기울게 한다.
	 */
	private static URI toUri(String jdbcUrl) {
		if (jdbcUrl == null || !jdbcUrl.startsWith(JDBC_PREFIX)) {
			return null;
		}
		try {
			return new URI(jdbcUrl.substring(JDBC_PREFIX.length()));
		} catch (URISyntaxException e) {
			return null;
		}
	}
}

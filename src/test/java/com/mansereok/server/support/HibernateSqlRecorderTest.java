package com.mansereok.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HibernateSqlRecorderTest {

	private final HibernateSqlRecorder recorder = new HibernateSqlRecorder();

	@Test
	@DisplayName("action 을 돌리는 동안 보낸 문장 가운데 표 이름을 한 낱말로 적은 문장만 보낸 순서대로 돌려준다")
	void keepsOnlyStatementsNamingTheTable() {
		// when
		List<String> statements = HibernateSqlRecorder.statementsOnTable("manses", () -> {
			recorder.inspect("select m1_0.id from manses m1_0 where m1_0.solar_date=?");
			recorder.inspect("update orders set status=? where id=?");
			recorder.inspect("select u1_0.id from user_manses u1_0");
			recorder.inspect("select m1_0.id from MANSES m1_0 where m1_0.season_start_time>=? limit ?");
		});

		// then
		assertThat(statements).containsExactly(
			"select m1_0.id from manses m1_0 where m1_0.solar_date=?",
			"select m1_0.id from MANSES m1_0 where m1_0.season_start_time>=? limit ?");
	}

	@Test
	@DisplayName("Hibernate 가 DB 로 보낼 문장을 바꾸지 않고 그대로 돌려준다")
	void returnsStatementUnchanged() {
		// when
		String returned = recorder.inspect("select m1_0.id from manses m1_0 where m1_0.solar_date=?");

		// then
		assertThat(returned).isEqualTo("select m1_0.id from manses m1_0 where m1_0.solar_date=?");
	}
}

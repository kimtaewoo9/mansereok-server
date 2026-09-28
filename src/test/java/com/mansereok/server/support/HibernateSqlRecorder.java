package com.mansereok.server.support;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate 가 DB 로 보내는 SQL 문장을 테스트가 모아 볼 수 있게 한다. {@link InterpretationMySqlTest} 가
 * hibernate.session_factory.statement_inspector 로 이 클래스를 등록한다.
 *
 * <p>테스트가 저장소 메서드 이름을 보고 SQL 을 손으로 옮겨 적으면, 저장소가 실제로 보내는 SQL 이 바뀌어도(정렬 컬럼을 바꾸거나
 * {@code @Query} 로 컬럼에 함수를 씌우는 경우 등) 테스트는 그대로 통과한다. 그래서 실제로 보낸 문장을 잡는다.
 *
 * <p>모으기는 {@link #statementsOnTable(String, Runnable)} 이 action 을 돌리는 동안만 켜진다. 스레드를 가리지 않고 모으므로
 * 여러 스레드에서 동시에 계산한 SQL 도 잡힌다. 같은 SessionFactory 를 쓰는 스케줄러(주문 만료 30분마다, 오래 멈춘 해석 되돌리기
 * 5분마다)의 문장이 그사이에 섞일 수 있어, 표 이름으로 거른 문장만 돌려준다. 문장은 바꾸지 않고 그대로 DB 로 보낸다.
 */
public class HibernateSqlRecorder implements StatementInspector {

	private static final Queue<String> RECORDED = new ConcurrentLinkedQueue<>();
	private static volatile boolean recording;

	/**
	 * action 을 돌리는 동안 Hibernate 가 보낸 SQL 가운데 table 을 이름으로 적은 문장만 보낸 순서대로 돌려준다. 문장은 JDBC 에
	 * 넘긴 모양 그대로라 값 자리는 {@code ?} 다. 한 번에 한 테스트만 모으도록 막는다.
	 */
	public static synchronized List<String> statementsOnTable(String table, Runnable action) {
		RECORDED.clear();
		recording = true;
		try {
			action.run();
		} finally {
			recording = false;
		}
		Pattern tableName = Pattern.compile("\\b" + Pattern.quote(table) + "\\b", Pattern.CASE_INSENSITIVE);
		return RECORDED.stream().filter(sql -> tableName.matcher(sql).find()).toList();
	}

	@Override
	public String inspect(String sql) {
		if (recording) {
			RECORDED.add(sql);
		}
		return sql;
	}
}

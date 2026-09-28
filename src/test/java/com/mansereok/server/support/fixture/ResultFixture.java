package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 사주 결과(Result)와 궁합 결과(CompatibilityResult)에 id 와 만든 시각을 채운다. id 는 DB 가 채우고 만든 시각은 저장할 때
 * (@PrePersist) 정해져 두 엔티티에 바꾸는 메서드가 없다. 그래서 리플렉션이 필요한데, 그 우회를 이 클래스 한 곳에만 둔다. 필드 이름이
 * 바뀌면 이 클래스만 고친다.
 */
public final class ResultFixture {

	private ResultFixture() {
	}

	/**
	 * DB 에 저장된 결과처럼 id 를 채우고, 받은 result 를 그대로 돌려준다.
	 */
	public static Result withId(Result result, Long id) {
		ReflectionTestUtils.setField(result, "id", id);
		return result;
	}

	/**
	 * DB 에 저장된 결과처럼 id 를 채우고, 받은 result 를 그대로 돌려준다.
	 */
	public static CompatibilityResult withId(CompatibilityResult result, Long id) {
		ReflectionTestUtils.setField(result, "id", id);
		return result;
	}

	/**
	 * DB 에 저장된 결과처럼 id 와 만든 시각을 채우고, 받은 result 를 그대로 돌려준다.
	 */
	public static Result withIdAndCreatedAt(Result result, Long id, LocalDateTime createdAt) {
		ReflectionTestUtils.setField(withId(result, id), "createdAt", createdAt);
		return result;
	}

	/**
	 * DB 에 저장된 결과처럼 id 와 만든 시각을 채우고, 받은 result 를 그대로 돌려준다.
	 */
	public static CompatibilityResult withIdAndCreatedAt(CompatibilityResult result, Long id,
		LocalDateTime createdAt) {
		ReflectionTestUtils.setField(withId(result, id), "createdAt", createdAt);
		return result;
	}
}

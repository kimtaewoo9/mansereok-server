package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 사주 결과(Result)와 궁합 결과(CompatibilityResult)를 원하는 상태로 만든다.
 *
 * <p>운영 코드에서 결과를 해석 중(PROCESSING)으로 바꾸는 길은 리포지토리의 조건부 UPDATE(markProcessingIfInputRequired) 하나뿐이고,
 * 엔티티에는 상태를 해석 중으로 바꾸는 메서드가 없다. DB 없이 해석 중인 엔티티가 필요한 테스트를 위해 상태 필드를 리플렉션으로 바꾸는
 * 우회를 이 클래스 한 곳에만 둔다. 완료(COMPLETED)는 해석 중을 거쳐 공개 메서드 completeInterpretation 으로 만든다.
 *
 * <p>updated_at 을 받는 메서드는 DB 없이 "이 시각에 시작한 해석 중" 인 결과가 필요한 테스트를 위한 것이다. 해석 중인 결과의
 * updated_at 은 해석을 시작한 시각이다. DB 에 저장하면 @PrePersist 가 updated_at 을 지금으로 덮어쓰므로, MySQL 테스트는 저장한 뒤
 * JdbcTemplate 로 바꾼다.
 */
public final class ResultFixture {

	private ResultFixture() {
	}

	/** 상태가 status 인 사주 결과. 완료면 본문 "사주 본문", 요약 "사주 요약" 이 들어 있다. */
	public static Result saju(Long userId, Long paymentId, ResultStatus status) {
		Result result = Result.createInitial(userId, paymentId, "인생 총운");
		if (status == ResultStatus.INPUT_REQUIRED) {
			return result;
		}
		ReflectionTestUtils.setField(result, "status", ResultStatus.PROCESSING);
		if (status == ResultStatus.COMPLETED) {
			result.completeInterpretation("사주 본문", "사주 요약");
		}
		return result;
	}

	/** 상태가 status 이고 updated_at 이 updatedAt 인 사주 결과. 해석 중이면 updatedAt 이 해석을 시작한 시각이다. */
	public static Result saju(Long userId, Long paymentId, ResultStatus status, LocalDateTime updatedAt) {
		Result result = saju(userId, paymentId, status);
		ReflectionTestUtils.setField(result, "updatedAt", updatedAt);
		return result;
	}

	/** 상태가 status 인 궁합 결과. 완료면 본문 "궁합 본문", 점수 80, 요약 "궁합 요약" 이 들어 있다. */
	public static CompatibilityResult compatibility(Long userId, Long paymentId, ResultStatus status) {
		CompatibilityResult result = CompatibilityResult.createInitial(userId, paymentId, "연인 궁합");
		if (status == ResultStatus.INPUT_REQUIRED) {
			return result;
		}
		ReflectionTestUtils.setField(result, "status", ResultStatus.PROCESSING);
		if (status == ResultStatus.COMPLETED) {
			result.completeInterpretation("궁합 본문", 80, "궁합 요약");
		}
		return result;
	}

	/** 상태가 status 이고 updated_at 이 updatedAt 인 궁합 결과. 해석 중이면 updatedAt 이 해석을 시작한 시각이다. */
	public static CompatibilityResult compatibility(Long userId, Long paymentId, ResultStatus status,
		LocalDateTime updatedAt) {
		CompatibilityResult result = compatibility(userId, paymentId, status);
		ReflectionTestUtils.setField(result, "updatedAt", updatedAt);
		return result;
	}
}

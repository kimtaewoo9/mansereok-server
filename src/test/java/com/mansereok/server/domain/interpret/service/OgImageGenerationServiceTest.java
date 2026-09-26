package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.support.fixture.ResultFixture;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * OG 이미지 서비스가 요약을 그려 올리고 주소를 저장하는지 본다. S3 업로드와 주소 저장(UPDATE)만 목으로 두고 이미지는 실제로 그린다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OG 이미지 생성")
class OgImageGenerationServiceTest {

	private static final Long RESULT_ID = 7L;
	private static final String PUBLIC_URL = "https://named-og-image.s3.ap-northeast-2.amazonaws.com/og-images/x.png";
	private static final String SUMMARY = "올해는 기회가 많은 해입니다.\n차분하게 준비하면 좋은 결과가 따라옵니다.";

	@Mock
	private S3UploadService s3UploadService;
	@Mock
	private ResultRepository resultRepository;
	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	private OgImageGenerationService service;

	@BeforeEach
	void setUp() {
		service = new OgImageGenerationService(s3UploadService, resultRepository, compatibilityResultRepository);
	}

	@Nested
	@DisplayName("요약이 없으면")
	class WhenSummaryIsMissing {

		@Test
		@DisplayName("사주 결과는 이미지를 올리지도 주소를 저장하지도 않는다")
		void skipsSaju() {
			// when
			service.generateAndUploadOgImage(sajuResult(ResultStatus.INPUT_REQUIRED));

			// then
			verifyNoInteractions(s3UploadService, resultRepository);
		}

		@Test
		@DisplayName("궁합 결과는 이미지를 올리지도 주소를 저장하지도 않는다")
		void skipsCompatibility() {
			// when
			service.generateAndUploadOgImage(compatibilityResult(ResultStatus.INPUT_REQUIRED));

			// then
			verifyNoInteractions(s3UploadService, compatibilityResultRepository);
		}
	}

	@Nested
	@DisplayName("요약이 있으면")
	class WhenSummaryExists {

		@Test
		@DisplayName("사주 결과는 og-images/saju-{id}.png 키로 올리고 돌려받은 주소를 그 결과 id 로 저장한다")
		void uploadsSajuImageAndSavesUrl() {
			// given
			given(s3UploadService.uploadFileAndGetPublicUrl(any(byte[].class), eq("og-images/saju-7.png"),
				eq("image/png"))).willReturn(PUBLIC_URL);

			// when
			service.generateAndUploadOgImage(sajuResult(ResultStatus.COMPLETED));

			// then
			then(resultRepository).should().updateOgImageUrl(RESULT_ID, PUBLIC_URL);
		}

		@Test
		@DisplayName("궁합 결과는 og-images/compat-{id}.png 키로 올리고 돌려받은 주소를 그 결과 id 로 저장한다")
		void uploadsCompatibilityImageAndSavesUrl() {
			// given
			given(s3UploadService.uploadFileAndGetPublicUrl(any(byte[].class), eq("og-images/compat-7.png"),
				eq("image/png"))).willReturn(PUBLIC_URL);

			// when
			service.generateAndUploadOgImage(compatibilityResult(ResultStatus.COMPLETED));

			// then
			then(compatibilityResultRepository).should().updateOgImageUrl(RESULT_ID, PUBLIC_URL);
		}

		@Test
		@DisplayName("S3 업로드가 실패하면 예외를 밖으로 던지지 않고 주소도 저장하지 않는다")
		void swallowsUploadFailure() {
			// given
			willThrow(SdkClientException.create("S3 연결 실패")).given(s3UploadService)
				.uploadFileAndGetPublicUrl(any(byte[].class), eq("og-images/saju-7.png"), eq("image/png"));

			// when & then
			assertThatCode(() -> service.generateAndUploadOgImage(sajuResult(ResultStatus.COMPLETED)))
				.doesNotThrowAnyException();
			verifyNoInteractions(resultRepository);
		}

		@Test
		@DisplayName("요약이 같으면 사주와 궁합은 같은 템플릿 크기의 같은 PNG 를 올린다")
		void sajuAndCompatibilityDrawSameImage() throws IOException {
			// given
			Result saju = sajuResult(ResultStatus.PROCESSING);
			saju.completeInterpretation("사주 본문", SUMMARY);
			CompatibilityResult compatibility = compatibilityResult(ResultStatus.PROCESSING);
			compatibility.completeInterpretation("궁합 본문", 80, SUMMARY);

			// when
			service.generateAndUploadOgImage(saju);
			service.generateAndUploadOgImage(compatibility);

			// then
			ArgumentCaptor<byte[]> sajuImage = ArgumentCaptor.forClass(byte[].class);
			ArgumentCaptor<byte[]> compatibilityImage = ArgumentCaptor.forClass(byte[].class);
			then(s3UploadService).should()
				.uploadFileAndGetPublicUrl(sajuImage.capture(), eq("og-images/saju-7.png"), eq("image/png"));
			then(s3UploadService).should()
				.uploadFileAndGetPublicUrl(compatibilityImage.capture(), eq("og-images/compat-7.png"),
					eq("image/png"));
			assertThat(compatibilityImage.getValue()).isEqualTo(sajuImage.getValue());
			BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(sajuImage.getValue()));
			assertThat(decoded.getWidth()).isEqualTo(1200);
			assertThat(decoded.getHeight()).isEqualTo(630);
		}
	}

	/**
	 * 운영과 같은 글꼴(Noto Sans KR Light 36px)과 같은 폭(템플릿 1200px - 좌우 여백 60px × 2)으로 줄을 나눈다. 이 글꼴에서
	 * '가' 한 글자는 33px 이다.
	 */
	@Nested
	@DisplayName("요약 줄 나누기")
	class WrapSummaryLines {

		private static final int MAX_WIDTH = 1080;

		private static FontMetrics metrics;

		@BeforeAll
		static void loadRealFont() {
			Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
			graphics.setFont(OgImageGenerationService.loadSummaryFont());
			metrics = graphics.getFontMetrics();
			graphics.dispose();
		}

		@Test
		@DisplayName("AI 가 넣은 줄바꿈은 그대로 줄로 나눈다")
		void keepsIntendedLineBreaks() {
			// when
			List<String> lines = OgImageGenerationService.getWrappedLines(metrics, "첫째 줄입니다\n둘째 줄입니다", MAX_WIDTH);

			// then
			assertThat(lines).containsExactly("첫째 줄입니다", "둘째 줄입니다");
		}

		@Test
		@DisplayName("한 줄이 폭보다 넓으면 띄어쓰기에서 나누고 모든 줄이 폭 이하다")
		void wrapsAtSpaces() {
			// given
			String longLine = "사주 해석 결과 요약 문장이 이미지 한 줄의 폭보다 길어지면 띄어쓰기에서 나누어 다음 줄로 넘기고 가운데 정렬로 그린다";

			// when
			List<String> lines = OgImageGenerationService.getWrappedLines(metrics, longLine, MAX_WIDTH);

			// then
			assertThat(lines).hasSizeGreaterThan(1)
				.allSatisfy(line -> {
					assertThat(line).isNotBlank();
					assertThat(metrics.stringWidth(line)).isLessThanOrEqualTo(MAX_WIDTH);
				});
			assertThat(String.join(" ", lines)).isEqualTo(longLine);
		}

		@Test
		@DisplayName("띄어쓰기 없이 폭보다 넓은 단어는 첫 줄을 비우지 않고 글자 단위로 잘라 폭 안에 넣는다")
		void splitsWordWiderThanLine() {
			// given: '가' 40자(1320px) 뒤에 짧은 단어 하나
			String text = "가".repeat(40) + " 끝";

			// when
			List<String> lines = OgImageGenerationService.getWrappedLines(metrics, text, MAX_WIDTH);

			// then: 32자(1056px)에서 자르고, 남은 8자 뒤에 다음 단어를 이어 붙인다
			assertThat(lines).containsExactly("가".repeat(32), "가".repeat(8) + " 끝");
		}

		@Test
		@DisplayName("폭보다 넓은 단어 앞에 다른 단어가 있으면 그 단어를 한 줄로 두고 긴 단어는 다음 줄부터 자른다")
		void startsLongWordOnNewLine() {
			// given
			String text = "링크 " + "a".repeat(120);

			// when
			List<String> lines = OgImageGenerationService.getWrappedLines(metrics, text, MAX_WIDTH);

			// then
			assertThat(lines.getFirst()).isEqualTo("링크");
			assertThat(lines).allSatisfy(line -> {
				assertThat(line).isNotEmpty();
				assertThat(metrics.stringWidth(line)).isLessThanOrEqualTo(MAX_WIDTH);
			});
			assertThat(String.join("", lines)).isEqualTo("링크" + "a".repeat(120));
		}
	}

	/** 결과 행 id 는 DB 가 채우는 값이라 저장 없이 쓰려고 리플렉션으로 넣는다. */
	private static Result sajuResult(ResultStatus status) {
		Result result = ResultFixture.saju(1L, 100L, status);
		ReflectionTestUtils.setField(result, "id", RESULT_ID);
		return result;
	}

	private static CompatibilityResult compatibilityResult(ResultStatus status) {
		CompatibilityResult result = ResultFixture.compatibility(1L, 100L, status);
		ReflectionTestUtils.setField(result, "id", RESULT_ID);
		return result;
	}
}

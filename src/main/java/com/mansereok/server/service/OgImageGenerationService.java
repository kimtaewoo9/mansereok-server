package com.mansereok.server.service;

import com.mansereok.server.entity.CompatibilityResult;
import com.mansereok.server.entity.Result;
import com.mansereok.server.repository.CompatibilityResultRepository;
import com.mansereok.server.repository.ResultRepository;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OgImageGenerationService {

	private final S3UploadService s3UploadService;
	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

	// 1. 폰트 경로 (Regular만 사용)
	private static final String FONT_PATH_REGULAR = "fonts/NotoSansKR-Regular.ttf";
	// private static final String FONT_PATH_BOLD = "fonts/NotoSansKR-Bold.ttf"; // Bold 제거

	private static final String SAJU_TEMPLATE_PATH = "static/result_image_template.png";
	private static final String COMPAT_TEMPLATE_PATH = "static/result_image_template.png";

	private Font notoSansRegular;
	// private Font notoSansBold; // Bold 제거

	// 2. 폰트 로드 (생성자 수정)
	public OgImageGenerationService(S3UploadService s3UploadService,
		ResultRepository resultRepository,
		CompatibilityResultRepository compatibilityResultRepository) {
		this.s3UploadService = s3UploadService;
		this.resultRepository = resultRepository;
		this.compatibilityResultRepository = compatibilityResultRepository;

		try {
			InputStream regularStream = new ClassPathResource(FONT_PATH_REGULAR).getInputStream();
			this.notoSansRegular = Font.createFont(Font.TRUETYPE_FONT, regularStream)
				.deriveFont(30f);
			regularStream.close();

			// Bold 폰트 로드 제거
		} catch (Exception e) {
			log.error("!!!!!!!!!! Noto Sans KR 폰트 로드 실패: {}. 기본 폰트를 사용합니다. !!!!!!!!!!!",
				e.getMessage());
			this.notoSansRegular = new Font("Arial", Font.PLAIN, 30);
			// this.notoSansBold = new Font("Arial", Font.BOLD, 30); // Bold 제거
		}
	}

	// --- 1인 사주(Result)용 비동기 처리 ---
	@Async
	@Transactional
	public void generateAndUploadOgImage(Result savedResult) {
		try {
			// 이름(name)은 더 이상 필요 없음, summary만 확인
			String summary = savedResult.getSummary();
			if (summary == null) {
				log.warn("Result(id={})에 summary가 없어 OG 생성을 건너뜁니다.", savedResult.getId());
				return;
			}

			// (1) 이미지 그리기 (name 인자 제거)
			byte[] imageBytes = generateSajuOgImage(savedResult.getSummary());

			// (2) S3 업로드
			String objectKey = "og-images/saju-" + savedResult.getId() + ".png";
			String publicUrl = s3UploadService.uploadFileAndGetPublicUrl(
				new ByteArrayInputStream(imageBytes),
				imageBytes.length,
				objectKey,
				"image/png"
			);

			// (3) DB에 URL 저장
			Result resultToUpdate = resultRepository.findById(savedResult.getId())
				.orElseThrow(() -> new RuntimeException(
					"OG 이미지 저장 중 Result를 찾을 수 없음: " + savedResult.getId()));

			resultToUpdate.setOgImageUrl(
				publicUrl);
			resultRepository.save(resultToUpdate);

			log.info("Result(id={}) OG 이미지 URL 저장 완료: {}", savedResult.getId(), publicUrl);

		} catch (Exception e) {
			log.error("Result(id={}) OG 이미지 생성/업로드 실패: {}", savedResult.getId(), e.getMessage(), e);
		}
	}

	// --- 궁합(CompatibilityResult)용 비동기 처리 ---
	@Async
	@Transactional
	public void generateAndUploadOgImage(CompatibilityResult savedResult) {
		try {
			// 이름(name1, name2)은 더 이상 필요 없음, summary만 확인
			String summary = savedResult.getSummary();

			if (summary == null) {
				log.warn("CompatResult(id={})에 summary가 없어 OG 생성을 건너뜁니다.",
					savedResult.getId());
				return;
			}

			// (1) 이미지 그리기 (name 인자 제거)
			byte[] imageBytes = generateCompatOgImage(savedResult.getSummary());
			// (2) S3 업로드
			String objectKey = "og-images/compat-" + savedResult.getId() + ".png";
			String publicUrl = s3UploadService.uploadFileAndGetPublicUrl(
				new ByteArrayInputStream(imageBytes),
				imageBytes.length,
				objectKey,
				"image/png"
			);

			// (3) DB에 URL 저장
			CompatibilityResult resultToUpdate = compatibilityResultRepository.findById(
					savedResult.getId())
				.orElseThrow(() -> new RuntimeException(
					"OG 이미지 저장 중 CompatibilityResult를 찾을 수 없음: " + savedResult.getId()));

			resultToUpdate.setOgImageUrl(
				publicUrl);
			compatibilityResultRepository.save(resultToUpdate);

			log.info("CompatResult(id={}) OG 이미지 URL 저장 완료: {}", savedResult.getId(), publicUrl);

		} catch (Exception e) {
			log.error("CompatResult(id={}) OG 이미지 생성/업로드 실패: {}", savedResult.getId(),
				e.getMessage(), e);
		}
	}

	// --- 3. 이미지 그리기 메서드 (스타일 및 정렬 적용) ---

	// 1인 사주 이미지 그리기
	private byte[] generateSajuOgImage(String summary) throws IOException {
		BufferedImage baseImage = loadTemplate(SAJU_TEMPLATE_PATH);
		Graphics2D g2d = baseImage.createGraphics();
		setupGraphics(g2d);

		// [스타일 적용]
		Color textColor = new Color(0x111111);
		Font summaryFont = this.notoSansRegular.deriveFont(40f) // (font-size: 40px)
			.deriveFont(Map.of(TextAttribute.TRACKING, 0.04f)); // (letter-spacing: 4%)
		int lineHeight = 52; // (line-height: 52px)
		int margin = 60; // (양 옆 마진 60px)

		g2d.setFont(summaryFont);
		g2d.setColor(textColor);

		// [레이아웃 적용]
		int x = margin;
		int maxWidth = baseImage.getWidth() - (margin * 2);

		// 1. 텍스트 줄바꿈 계산
		List<String> lines = getWrappedLines(g2d, summary, maxWidth);

		// 2. 전체 텍스트 블록의 세로 높이 계산
		FontMetrics metrics = g2d.getFontMetrics();
		// (줄 개수 - 1) * 줄간격 + 마지막 줄의 높이
		int blockHeight = (lines.size() - 1) * lineHeight + metrics.getHeight();

		// 3. 텍스트 블록의 시작 Y좌표 계산 (위아래 가운데 정렬)
		// (전체 높이 - 블록 높이) / 2 + 첫 줄의 baseline (Ascent)
		int startY = (baseImage.getHeight() - blockHeight) / 2 + metrics.getAscent();

		// 4. 텍스트 그리기
		int currentY = startY;
		for (String line : lines) {
			g2d.drawString(line, x, currentY);
			currentY += lineHeight;
		}

		g2d.dispose();
		return toByteArray(baseImage, "png");
	}

	// 궁합 이미지 그리기 (사주 이미지와 동일하게 수정)
	private byte[] generateCompatOgImage(String summary) throws IOException {
		BufferedImage baseImage = loadTemplate(COMPAT_TEMPLATE_PATH);
		Graphics2D g2d = baseImage.createGraphics();
		setupGraphics(g2d);

		// [스타일 적용]
		Color textColor = new Color(0x111111);
		Font summaryFont = this.notoSansRegular.deriveFont(40f) // (font-size: 40px)
			.deriveFont(Map.of(TextAttribute.TRACKING, 0.04f)); // (letter-spacing: 4%)
		int lineHeight = 52; // (line-height: 52px)
		int margin = 60; // (양 옆 마진 60px)

		g2d.setFont(summaryFont);
		g2d.setColor(textColor);

		// [레이아웃 적용]
		int x = margin;
		int maxWidth = baseImage.getWidth() - (margin * 2);

		// 1. 텍스트 줄바꿈 계산
		List<String> lines = getWrappedLines(g2d, summary, maxWidth);

		// 2. 전체 텍스트 블록의 세로 높이 계산
		FontMetrics metrics = g2d.getFontMetrics();
		int blockHeight = (lines.size() - 1) * lineHeight + metrics.getHeight();

		// 3. 텍스트 블록의 시작 Y좌표 계산 (위아래 가운데 정렬)
		int startY = (baseImage.getHeight() - blockHeight) / 2 + metrics.getAscent();

		// 4. 텍스트 그리기
		int currentY = startY;
		for (String line : lines) {
			g2d.drawString(line, x, currentY);
			currentY += lineHeight;
		}

		g2d.dispose();
		return toByteArray(baseImage, "png");
	}

	// --- 그래픽스 도우미 ---

	private BufferedImage loadTemplate(String path) throws IOException {
		try (InputStream is = new ClassPathResource(path).getInputStream()) {
			BufferedImage image = ImageIO.read(is);
			if (image == null) {
				throw new IOException("템플릿 이미지 읽기 실패: " + path);
			}

			BufferedImage newImage = new BufferedImage(image.getWidth(), image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = newImage.createGraphics();
			g.drawImage(image, 0, 0, null);
			g.dispose();
			return newImage;
		}
	}

	private void setupGraphics(Graphics2D g2d) {
		g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}

	// (수정) 텍스트를 그리는 대신, 줄바꿈된 라인 리스트를 반환
	private List<String> getWrappedLines(Graphics2D g, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		FontMetrics metrics = g.getFontMetrics();
		String[] words = text.split(" ");
		StringBuilder currentLine = new StringBuilder();

		for (String word : words) {
			// (한글은 보통 띄어쓰기 기준이므로 이 로직이 잘 동작합니다)
			if (metrics.stringWidth(currentLine + " " + word) < maxWidth) {
				if (currentLine.length() > 0) {
					currentLine.append(" ");
				}
				currentLine.append(word);
			} else {
				lines.add(currentLine.toString());
				currentLine = new StringBuilder(word);
			}
		}
		if (currentLine.length() > 0) {
			lines.add(currentLine.toString());
		}
		return lines;
	}

	// (제거) drawTextCentered - 더 이상 사용하지 않음

	// (제거) drawMultiLineText - getWrappedLines로 대체됨

	// BufferedImage -> byte[] 변환
	private byte[] toByteArray(BufferedImage image, String format) throws IOException {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			ImageIO.write(image, format, baos);
			return baos.toByteArray();
		}
	}
}

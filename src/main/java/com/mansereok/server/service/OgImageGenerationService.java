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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OgImageGenerationService {

	// 1. 의존성 주입
	private final S3UploadService s3UploadService; // 님이 만든 업로더
	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

	// 2. 템플릿 및 폰트 경로 (src/main/resources/ 에 있어야 함)
	private static final String FONT_PATH_REGULAR = "fonts/NotoSansKR-Regular.ttf"; // 일반체
	private static final String FONT_PATH_BOLD = "fonts/NotoSansKR-Bold.ttf";

	private static final String SAJU_TEMPLATE_PATH = "static/result_image_template.png";
	private static final String COMPAT_TEMPLATE_PATH = "static/result_image_template.png";

	private Font notoSansRegular;
	private Font notoSansBold;

	private Font customFont;

	// 3. 폰트 로드 (생성자)
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

			InputStream boldStream = new ClassPathResource(FONT_PATH_BOLD).getInputStream();
			this.notoSansBold = Font.createFont(Font.TRUETYPE_FONT, boldStream).deriveFont(30f);
			boldStream.close();
		} catch (Exception e) {
			log.error("!!!!!!!!!! Noto Sans KR 폰트 로드 실패: {}. 기본 폰트를 사용합니다. !!!!!!!!!!!",
				e.getMessage());
			this.notoSansRegular = new Font("Arial", Font.PLAIN, 30);
			this.notoSansBold = new Font("Arial", Font.BOLD, 30);
		}
	}

	// --- 4. 1인 사주(Result)용 비동기 처리 ---
	@Async
	@Transactional // 새 트랜잭션에서 URL을 저장해야 함
	public void generateAndUploadOgImage(Result savedResult) {
		try {
			String name = savedResult.getName();
			String summary = savedResult.getSummary();
			if (name == null || summary == null) {
				log.warn("Result(id={})에 name 또는 summary가 없어 OG 생성을 건너뜁니다.", savedResult.getId());
				return;
			}

			// (1) 이미지 그리기
			byte[] imageBytes = generateSajuOgImage(savedResult.getName(),
				savedResult.getSummary());

			// (2) S3 업로드 (님이 만든 서비스 호출)
			String objectKey = "og-images/saju/" + savedResult.getId() + ".png";
			String publicUrl = s3UploadService.uploadFileAndGetPublicUrl(
				new ByteArrayInputStream(imageBytes),
				imageBytes.length,
				objectKey,
				"image/png"
			);

			// (3) DB에 URL 저장
			// @Async + @Transactional 이므로, savedResult가 Detached 상태일 수 있음. ID로 다시 조회.
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

	// --- 5. 궁합(CompatibilityResult)용 비동기 처리 ---
	@Async
	@Transactional
	public void generateAndUploadOgImage(CompatibilityResult savedResult) {
		try {
			String name1 = savedResult.getPerson1Name();
			String name2 = savedResult.getPerson2Name();
			String summary = savedResult.getSummary();

			if (name1 == null || name2 == null || summary == null) {
				log.warn("CompatResult(id={})에 이름 또는 summary가 없어 OG 생성을 건너뜁니다.",
					savedResult.getId());
				return;
			}

			// (1) 이미지 그리기
			byte[] imageBytes = generateCompatOgImage(savedResult.getPerson1Name(),
				savedResult.getPerson2Name(), savedResult.getSummary());
			// (2) S3 업로드
			String objectKey = "og-images/compat/" + savedResult.getId() + ".png";
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
				publicUrl); // (CompatibilityResult 엔티티에 setOgImageUrl(String url) 메서드 필요)
			compatibilityResultRepository.save(resultToUpdate);

			log.info("CompatResult(id={}) OG 이미지 URL 저장 완료: {}", savedResult.getId(), publicUrl);

		} catch (Exception e) {
			log.error("CompatResult(id={}) OG 이미지 생성/업로드 실패: {}", savedResult.getId(),
				e.getMessage(), e);
		}
	}

	// --- 4. 이미지 그리기 메서드 (폰트 적용) ---

	// 1인 사주 이미지 그리기
	private byte[] generateSajuOgImage(String name, String summary) throws IOException {
		BufferedImage baseImage = loadTemplate(SAJU_TEMPLATE_PATH);
		Graphics2D g2d = baseImage.createGraphics();
		setupGraphics(g2d);

		// (디자인 템플릿에 맞게 폰트, 색상, X/Y 좌표 수정 필수)

		// 이름 (Bold 폰트)
		g2d.setFont(notoSansBold.deriveFont(60f));
		g2d.setColor(Color.BLACK);
		drawTextCentered(g2d, name + "님의 사주", baseImage.getWidth(), 200);

		// 요약 (Regular 폰트)
		g2d.setFont(notoSansRegular.deriveFont(36f));
		g2d.setColor(Color.BLACK);
		drawMultiLineText(g2d, summary, 100, 400, baseImage.getWidth() - 200, 46); // 줄간격 46

		g2d.dispose();
		return toByteArray(baseImage, "png");
	}

	// 궁합 이미지 그리기
	private byte[] generateCompatOgImage(String name1, String name2, String summary)
		throws IOException {
		BufferedImage baseImage = loadTemplate(COMPAT_TEMPLATE_PATH); // (궁합용 템플릿이 따로 있다면 경로 수정)
		Graphics2D g2d = baseImage.createGraphics();
		setupGraphics(g2d);

		// 이름 (Bold 폰트)
		g2d.setFont(notoSansBold.deriveFont(50f));
		g2d.setColor(Color.BLACK);
		drawTextCentered(g2d, name1 + "님과 " + name2 + "님의 궁합", baseImage.getWidth(), 200);

		// 요약 (Regular 폰트)
		g2d.setFont(notoSansRegular.deriveFont(36f));
		g2d.setColor(Color.BLACK);
		drawMultiLineText(g2d, summary, 100, 400, baseImage.getWidth() - 200, 46);

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

			// (중요) 원본 이미지가 ARGB가 아니면 글씨가 안 써질 수 있으므로 ARGB 타입으로 변환
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

	// 텍스트 중앙 정렬
	private void drawTextCentered(Graphics2D g, String text, int totalWidth, int y) {
		FontMetrics metrics = g.getFontMetrics();
		int x = (totalWidth - metrics.stringWidth(text)) / 2;
		g.drawString(text, x, y);
	}

	// 텍스트 자동 줄 바꿈 (maxWidth 초과 시)
	private void drawMultiLineText(Graphics2D g, String text, int x, int y, int maxWidth,
		int lineHeight) {
		FontMetrics metrics = g.getFontMetrics();
		String[] words = text.split(" ");
		StringBuilder currentLine = new StringBuilder();

		for (String word : words) {
			// 단어가 너무 길어서 maxWidth를 초과하면 강제 줄 바꿈 (예시: 영어)
			if (metrics.stringWidth(word) > maxWidth) {
				// (이 부분은 한글의 경우 로직이 더 복잡해질 수 있음, 일단 단어 단위로만 처리)
				g.drawString(currentLine.toString(), x, y);
				y += lineHeight;
				currentLine = new StringBuilder(word);
			}

			if (metrics.stringWidth(currentLine + " " + word) < maxWidth) {
				if (currentLine.length() > 0) {
					currentLine.append(" ");
				}
				currentLine.append(word);
			} else {
				g.drawString(currentLine.toString(), x, y);
				y += lineHeight;
				currentLine = new StringBuilder(word);
			}
		}
		if (currentLine.length() > 0) {
			g.drawString(currentLine.toString(), x, y);
		}
	}

	// BufferedImage -> byte[] 변환
	private byte[] toByteArray(BufferedImage image, String format) throws IOException {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			ImageIO.write(image, format, baos);
			return baos.toByteArray();
		}
	}
}

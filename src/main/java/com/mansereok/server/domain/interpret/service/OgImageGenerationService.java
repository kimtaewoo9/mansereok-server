package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 해석 결과의 요약을 공유 미리보기(OG) 이미지로 그려 S3 에 올리고, 그 주소를 결과에 저장한다.
 *
 * <p>해석 작업 스레드가 결과를 저장한 직후 이 서비스를 바로 부른다(비동기로 넘기지 않는다). 그래서 다른 풀이 가득 찼거나 먼저 닫혀서
 * 이미지 작업이 버려지는 일이 없다. 대신 해석 스레드가 이미지 생성과 업로드 시간만큼 더 붙잡힌다.
 *
 * <p>이미지 생성이나 업로드가 Exception 으로 실패하면 해석 결과는 그대로 두고 로그만 남긴다. OutOfMemoryError 같은 Error 는 잡지
 * 않는다. Error 는 해석 스레드로 올라가므로, 그 해석의 뒤 단계(결과 준비 메일)는 돌지 않는다.
 */
@Slf4j
@Service
public class OgImageGenerationService {

	private static final String FONT_PATH = "fonts/NotoSansKR-Light.ttf";
	private static final String TEMPLATE_PATH = "static/result_image_template.png";

	private static final String SAJU_KEY_PREFIX = "og-images/saju-";
	private static final String COMPATIBILITY_KEY_PREFIX = "og-images/compat-";
	private static final String CONTENT_TYPE = "image/png";

	// 요약 글자 모양. 디자인의 font-size 36px, line-height 48px, 좌우 여백 60px 을 그대로 옮겼다.
	private static final float FONT_SIZE = 36f;
	private static final Color TEXT_COLOR = new Color(0x111111);
	private static final int LINE_HEIGHT = 48;
	private static final int SIDE_MARGIN = 60;

	private final S3UploadService s3UploadService;
	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;
	private final Font summaryFont;

	public OgImageGenerationService(S3UploadService s3UploadService,
		ResultRepository resultRepository,
		CompatibilityResultRepository compatibilityResultRepository) {
		this.s3UploadService = s3UploadService;
		this.resultRepository = resultRepository;
		this.compatibilityResultRepository = compatibilityResultRepository;
		this.summaryFont = loadSummaryFont();
	}

	/**
	 * 요약에 쓰는 글꼴(Noto Sans KR Light 36px)을 읽는다. 글꼴 파일을 읽지 못하면 Arial 로 대신 그린다. 글꼴 스트림은 읽다가
	 * 실패해도 닫는다.
	 */
	static Font loadSummaryFont() {
		try (InputStream fontStream = new ClassPathResource(FONT_PATH).getInputStream()) {
			return Font.createFont(Font.TRUETYPE_FONT, fontStream).deriveFont(FONT_SIZE);
		} catch (IOException | FontFormatException e) {
			log.error("Noto Sans KR Light 폰트 로드 실패. 기본 폰트(Arial)를 사용합니다.", e);
			return new Font("Arial", Font.PLAIN, Math.round(FONT_SIZE));
		}
	}

	/** 사주 결과의 OG 이미지를 만들어 올리고 주소를 저장한다. 요약이 없으면 아무것도 하지 않는다. */
	public void generateAndUploadOgImage(Result savedResult) {
		uploadAndSave(SAJU_KEY_PREFIX, savedResult.getId(), savedResult.getSummary(),
			resultRepository::updateOgImageUrl);
	}

	/** 궁합 결과의 OG 이미지를 만들어 올리고 주소를 저장한다. 요약이 없으면 아무것도 하지 않는다. */
	public void generateAndUploadOgImage(CompatibilityResult savedResult) {
		uploadAndSave(COMPATIBILITY_KEY_PREFIX, savedResult.getId(), savedResult.getSummary(),
			compatibilityResultRepository::updateOgImageUrl);
	}

	/**
	 * 요약을 이미지로 그려 "{keyPrefix}{id}.png" 키로 올리고, 받은 공개 주소를 saveUrl 로 저장한다. 실패는 로그로만 남긴다.
	 *
	 * <p>이미지 생성과 업로드는 DB 커넥션 없이 하고, 주소 저장만 짧은 UPDATE 하나로 한다.
	 */
	private void uploadAndSave(String keyPrefix, Long id, String summary, BiConsumer<Long, String> saveUrl) {
		if (summary == null) {
			return;
		}
		String objectKey = keyPrefix + id + ".png";
		try {
			byte[] imageBytes = renderSummaryImage(summary);
			String publicUrl = s3UploadService.uploadFileAndGetPublicUrl(imageBytes, objectKey, CONTENT_TYPE);
			saveUrl.accept(id, publicUrl);
			log.info("OG 이미지 URL 저장 완료 - key: {}, url: {}", objectKey, publicUrl);
		} catch (Exception e) {
			log.error("OG 이미지 생성 실패 - key: {}", objectKey, e);
		}
	}

	/**
	 * 템플릿 이미지 위에 요약을 가로·세로 가운데 맞춰 그린 PNG 를 돌려준다. 사주와 궁합이 같은 모양을 쓴다.
	 */
	private byte[] renderSummaryImage(String summary) throws IOException {
		BufferedImage image = loadTemplate();
		Graphics2D g2d = image.createGraphics();
		try {
			setupGraphics(g2d);
			g2d.setFont(summaryFont);
			g2d.setColor(TEXT_COLOR);

			FontMetrics metrics = g2d.getFontMetrics();
			int maxWidth = image.getWidth() - SIDE_MARGIN * 2;
			List<String> lines = getWrappedLines(metrics, summary, maxWidth);

			// 글자 블록 전체 높이로 첫 줄의 기준선을 정해 위아래 가운데에 놓는다.
			int blockHeight = (lines.size() - 1) * LINE_HEIGHT + metrics.getHeight();
			int baselineY = (image.getHeight() - blockHeight) / 2 + metrics.getAscent();

			for (String line : lines) {
				int x = (image.getWidth() - metrics.stringWidth(line)) / 2;
				g2d.drawString(line, x, baselineY);
				baselineY += LINE_HEIGHT;
			}
		} finally {
			g2d.dispose();
		}
		return toPngBytes(image);
	}

	private BufferedImage loadTemplate() throws IOException {
		try (InputStream is = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
			BufferedImage image = ImageIO.read(is);
			if (image == null) {
				throw new IOException("템플릿 이미지 읽기 실패: " + TEMPLATE_PATH);
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

	/**
	 * 요약을 그릴 줄로 나눈다. AI 가 넣은 '\n' 은 그대로 줄바꿈으로 쓰고, 한 줄이 maxWidth 보다 넓으면 띄어쓰기에서 나눈다.
	 * 띄어쓰기 없이 maxWidth 보다 넓은 단어(긴 URL, 붙여 쓴 구절)는 글자 단위로 자른다. 그래서 모든 줄의 폭이 maxWidth 이하다.
	 *
	 * <p>'\n' 이 두 번 이어진 빈 줄은 AI 가 의도한 문단 간격이라 그대로 둔다. 폭 때문에 나누다 생기는 빈 줄은 넣지 않는다.
	 */
	static List<String> getWrappedLines(FontMetrics metrics, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		for (String intendedLine : text.split("\n")) {
			if (metrics.stringWidth(intendedLine) <= maxWidth) {
				lines.add(intendedLine);
				continue;
			}
			String currentLine = "";
			for (String word : intendedLine.split(" ")) {
				String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
				if (metrics.stringWidth(candidate) <= maxWidth) {
					currentLine = candidate;
					continue;
				}
				if (!currentLine.isEmpty()) {
					lines.add(currentLine);
				}
				currentLine = word;
				if (metrics.stringWidth(word) > maxWidth) {
					// 단어 하나가 한 줄보다 넓으면 글자 단위로 잘라 앞부분을 줄로 넣고, 남은 끝부분 뒤에 다음 단어를 이어 붙인다.
					List<String> pieces = splitByCharacters(metrics, word, maxWidth);
					lines.addAll(pieces.subList(0, pieces.size() - 1));
					currentLine = pieces.getLast();
				}
			}
			if (!currentLine.isEmpty()) {
				lines.add(currentLine);
			}
		}
		return lines;
	}

	/** 띄어쓰기 없는 문자열을 폭이 maxWidth 이하인 조각으로 글자 단위로 자른다. 글자 하나가 maxWidth 보다 넓어도 한 조각에는 한 글자가 들어간다. */
	private static List<String> splitByCharacters(FontMetrics metrics, String word, int maxWidth) {
		List<String> pieces = new ArrayList<>();
		StringBuilder piece = new StringBuilder();
		word.codePoints().forEach(codePoint -> {
			String character = Character.toString(codePoint);
			if (!piece.isEmpty() && metrics.stringWidth(piece + character) > maxWidth) {
				pieces.add(piece.toString());
				piece.setLength(0);
			}
			piece.append(character);
		});
		pieces.add(piece.toString());
		return pieces;
	}

	private byte[] toPngBytes(BufferedImage image) throws IOException {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			ImageIO.write(image, "png", baos);
			return baos.toByteArray();
		}
	}
}

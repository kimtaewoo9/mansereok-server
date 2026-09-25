package com.mansereok.server.domain.interpret.postprocess;

import java.util.Arrays;
import java.util.List;

/**
 * 문단 단위로 자르고 다시 붙이는 잔손질. 빈 문단은 언제나 버린다.
 */
final class TextBlocks {

	private TextBlocks() {
	}

	/** 빈 줄을 경계로 문단을 나눈다. 각 문단은 trim 되고 빈 문단은 빠진다. */
	static List<String> splitParagraphs(String text) {
		return Arrays.stream(text.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(block -> !block.isEmpty())
			.toList();
	}

	/** 문단 사이에 빈 줄 하나를 넣어 다시 붙인다. */
	static String joinParagraphs(List<String> paragraphs) {
		return String.join("\n\n", paragraphs);
	}
}

package com.mansereok.server.domain.interpret.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SajuDataServiceTest {

	private final SajuDataService sajuDataService = new SajuDataService();

	@Test
	void shouldUseStandardHiddenStemForSingleBranch() {
		Map<String, Map<String, Object>> jijangan = sajuDataService.getJijangan();
		Map<String, Object> ja = jijangan.get("子");
		Map<String, Object> first = (Map<String, Object>) ja.get("first");

		assertEquals("癸", first.get("chinese"));
		assertEquals(30, first.get("rate"));
		assertNull(ja.get("second"));
		assertNull(ja.get("third"));
	}

	@Test
	void shouldUseStandardHiddenStemOrderForInBranch() {
		Map<String, Map<String, Object>> jijangan = sajuDataService.getJijangan();
		Map<String, Object> in = jijangan.get("寅");
		Map<String, Object> first = (Map<String, Object>) in.get("first");
		Map<String, Object> second = (Map<String, Object>) in.get("second");
		Map<String, Object> third = (Map<String, Object>) in.get("third");

		assertEquals("甲", first.get("chinese"));
		assertEquals("丙", second.get("chinese"));
		assertEquals("戊", third.get("chinese"));
	}
}

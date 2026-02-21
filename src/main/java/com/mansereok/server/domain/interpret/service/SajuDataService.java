package com.mansereok.server.domain.interpret.service;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SajuDataService {

	/**
	 * 천간, 지지의 음양 데이터
	 */
	public Map<String, String> getMinusPlus() {
		Map<String, String> data = new HashMap<>();
		// 천간 음양
		data.put("甲", "양");
		data.put("丙", "양");
		data.put("戊", "양");
		data.put("庚", "양");
		data.put("壬", "양");
		data.put("乙", "음");
		data.put("丁", "음");
		data.put("己", "음");
		data.put("辛", "음");
		data.put("癸", "음");

		// 지지 음양
		data.put("子", "양");
		data.put("寅", "양");
		data.put("辰", "양");
		data.put("午", "양");
		data.put("申", "양");
		data.put("戌", "양");
		data.put("丑", "음");
		data.put("卯", "음");
		data.put("巳", "음");
		data.put("未", "음");
		data.put("酉", "음");
		data.put("亥", "음");

		return data;
	}

	/**
	 * 시간대별 시주 범위 데이터
	 */
	public Map<String, LocalTime[]> getTimeJuData() {
		Map<String, LocalTime[]> data = new HashMap<>();
		data.put("0", new LocalTime[]{LocalTime.of(23, 30), LocalTime.of(1, 29)});  // 자시
		data.put("1", new LocalTime[]{LocalTime.of(1, 30), LocalTime.of(3, 29)});   // 축시
		data.put("2", new LocalTime[]{LocalTime.of(3, 30), LocalTime.of(5, 29)});   // 인시
		data.put("3", new LocalTime[]{LocalTime.of(5, 30), LocalTime.of(7, 29)});   // 묘시
		data.put("4", new LocalTime[]{LocalTime.of(7, 30), LocalTime.of(9, 29)});   // 진시
		data.put("5", new LocalTime[]{LocalTime.of(9, 30), LocalTime.of(11, 29)});  // 사시
		data.put("6", new LocalTime[]{LocalTime.of(11, 30), LocalTime.of(13, 29)}); // 오시
		data.put("7", new LocalTime[]{LocalTime.of(13, 30), LocalTime.of(15, 29)}); // 미시
		data.put("8", new LocalTime[]{LocalTime.of(15, 30), LocalTime.of(17, 29)}); // 신시
		data.put("9", new LocalTime[]{LocalTime.of(17, 30), LocalTime.of(19, 29)}); // 유시
		data.put("10", new LocalTime[]{LocalTime.of(19, 30), LocalTime.of(21, 29)}); // 술시
		data.put("11", new LocalTime[]{LocalTime.of(21, 30), LocalTime.of(23, 29)}); // 해시
		return data;
	}

	/**
	 * 일간별 시주 계산 데이터 (완전판)
	 */
	public Map<String, Map<String, String[]>> getTimeJuData2() {
		Map<String, Map<String, String[]>> data = new HashMap<>();

		// 甲일, 己일
		Map<String, String[]> gapgi = new HashMap<>();
		gapgi.put("0", new String[]{"甲", "子"});
		gapgi.put("1", new String[]{"乙", "丑"});
		gapgi.put("2", new String[]{"丙", "寅"});
		gapgi.put("3", new String[]{"丁", "卯"});
		gapgi.put("4", new String[]{"戊", "辰"});
		gapgi.put("5", new String[]{"己", "巳"});
		gapgi.put("6", new String[]{"庚", "午"});
		gapgi.put("7", new String[]{"辛", "未"});
		gapgi.put("8", new String[]{"壬", "申"});
		gapgi.put("9", new String[]{"癸", "酉"});
		gapgi.put("10", new String[]{"甲", "戌"});
		gapgi.put("11", new String[]{"乙", "亥"});
		data.put("甲", gapgi);
		data.put("己", gapgi);

		// 乙일, 庚일
		Map<String, String[]> eulgyang = new HashMap<>();
		eulgyang.put("0", new String[]{"丙", "子"});
		eulgyang.put("1", new String[]{"丁", "丑"});
		eulgyang.put("2", new String[]{"戊", "寅"});
		eulgyang.put("3", new String[]{"己", "卯"});
		eulgyang.put("4", new String[]{"庚", "辰"});
		eulgyang.put("5", new String[]{"辛", "巳"});
		eulgyang.put("6", new String[]{"壬", "午"});
		eulgyang.put("7", new String[]{"癸", "未"});
		eulgyang.put("8", new String[]{"甲", "申"});
		eulgyang.put("9", new String[]{"乙", "酉"});
		eulgyang.put("10", new String[]{"丙", "戌"});
		eulgyang.put("11", new String[]{"丁", "亥"});
		data.put("乙", eulgyang);
		data.put("庚", eulgyang);

		// 丙일, 辛일
		Map<String, String[]> byeongsin = new HashMap<>();
		byeongsin.put("0", new String[]{"戊", "子"});
		byeongsin.put("1", new String[]{"己", "丑"});
		byeongsin.put("2", new String[]{"庚", "寅"});
		byeongsin.put("3", new String[]{"辛", "卯"});
		byeongsin.put("4", new String[]{"壬", "辰"});
		byeongsin.put("5", new String[]{"癸", "巳"});
		byeongsin.put("6", new String[]{"甲", "午"});
		byeongsin.put("7", new String[]{"乙", "未"});
		byeongsin.put("8", new String[]{"丙", "申"});
		byeongsin.put("9", new String[]{"丁", "酉"});
		byeongsin.put("10", new String[]{"戊", "戌"});
		byeongsin.put("11", new String[]{"己", "亥"});
		data.put("丙", byeongsin);
		data.put("辛", byeongsin);

		// 丁일, 壬일
		Map<String, String[]> jeongtim = new HashMap<>();
		jeongtim.put("0", new String[]{"庚", "子"});
		jeongtim.put("1", new String[]{"辛", "丑"});
		jeongtim.put("2", new String[]{"壬", "寅"});
		jeongtim.put("3", new String[]{"癸", "卯"});
		jeongtim.put("4", new String[]{"甲", "辰"});
		jeongtim.put("5", new String[]{"乙", "巳"});
		jeongtim.put("6", new String[]{"丙", "午"});
		jeongtim.put("7", new String[]{"丁", "未"});
		jeongtim.put("8", new String[]{"戊", "申"});
		jeongtim.put("9", new String[]{"己", "酉"});
		jeongtim.put("10", new String[]{"庚", "戌"});
		jeongtim.put("11", new String[]{"辛", "亥"});
		data.put("丁", jeongtim);
		data.put("壬", jeongtim);

		// 戊일, 癸일
		Map<String, String[]> mugye = new HashMap<>();
		mugye.put("0", new String[]{"壬", "子"});
		mugye.put("1", new String[]{"癸", "丑"});
		mugye.put("2", new String[]{"甲", "寅"});
		mugye.put("3", new String[]{"乙", "卯"});
		mugye.put("4", new String[]{"丙", "辰"});
		mugye.put("5", new String[]{"丁", "巳"});
		mugye.put("6", new String[]{"戊", "午"});
		mugye.put("7", new String[]{"己", "未"});
		mugye.put("8", new String[]{"庚", "申"});
		mugye.put("9", new String[]{"辛", "酉"});
		mugye.put("10", new String[]{"壬", "戌"});
		mugye.put("11", new String[]{"癸", "亥"});
		data.put("戊", mugye);
		data.put("癸", mugye);

		return data;
	}

	/**
	 * 일간에 따른 십성 데이터 (완전판 - 10개 일간 모두)
	 */
	public Map<String, Map<String, String>> getTenStar() {
		Map<String, Map<String, String>> data = new HashMap<>();

		// 甲일간 십성
		Map<String, String> gapData = new HashMap<>();
		gapData.put("甲", "비견,목");
		gapData.put("寅", "비견,목");
		gapData.put("乙", "겁재,목");
		gapData.put("卯", "겁재,목");
		gapData.put("丙", "식신,화");
		gapData.put("巳", "식신,화");
		gapData.put("丁", "상관,화");
		gapData.put("午", "상관,화");
		gapData.put("戊", "편재,토");
		gapData.put("辰", "편재,토");
		gapData.put("戌", "편재,토");
		gapData.put("己", "정재,토");
		gapData.put("丑", "정재,토");
		gapData.put("未", "정재,토");
		gapData.put("庚", "편관,금");
		gapData.put("申", "편관,금");
		gapData.put("辛", "정관,금");
		gapData.put("酉", "정관,금");
		gapData.put("壬", "편인,수");
		gapData.put("亥", "편인,수");
		gapData.put("癸", "정인,수");
		gapData.put("子", "정인,수");
		data.put("甲", gapData);

		// 乙일간 십성
		Map<String, String> eulData = new HashMap<>();
		eulData.put("甲", "겁재,목");
		eulData.put("寅", "겁재,목");
		eulData.put("乙", "비견,목");
		eulData.put("卯", "비견,목");
		eulData.put("丙", "상관,화");
		eulData.put("巳", "상관,화");
		eulData.put("丁", "식신,화");
		eulData.put("午", "식신,화");
		eulData.put("戊", "정재,토");
		eulData.put("辰", "정재,토");
		eulData.put("戌", "정재,토");
		eulData.put("己", "편재,토");
		eulData.put("丑", "편재,토");
		eulData.put("未", "편재,토");
		eulData.put("庚", "정관,금");
		eulData.put("申", "정관,금");
		eulData.put("辛", "편관,금");
		eulData.put("酉", "편관,금");
		eulData.put("壬", "정인,수");
		eulData.put("亥", "정인,수");
		eulData.put("癸", "편인,수");
		eulData.put("子", "편인,수");
		data.put("乙", eulData);

		// 丙일간 십성
		Map<String, String> byeongData = new HashMap<>();
		byeongData.put("甲", "편인,목");
		byeongData.put("寅", "편인,목");
		byeongData.put("乙", "정인,목");
		byeongData.put("卯", "정인,목");
		byeongData.put("丙", "비견,화");
		byeongData.put("巳", "비견,화");
		byeongData.put("丁", "겁재,화");
		byeongData.put("午", "겁재,화");
		byeongData.put("戊", "식신,토");
		byeongData.put("辰", "식신,토");
		byeongData.put("戌", "식신,토");
		byeongData.put("己", "상관,토");
		byeongData.put("丑", "상관,토");
		byeongData.put("未", "상관,토");
		byeongData.put("庚", "편재,금");
		byeongData.put("申", "편재,금");
		byeongData.put("辛", "정재,금");
		byeongData.put("酉", "정재,금");
		byeongData.put("壬", "편관,수");
		byeongData.put("亥", "편관,수");
		byeongData.put("癸", "정관,수");
		byeongData.put("子", "정관,수");
		data.put("丙", byeongData);

		// 丁일간 십성
		Map<String, String> jeongData = new HashMap<>();
		jeongData.put("甲", "정인,목");
		jeongData.put("寅", "정인,목");
		jeongData.put("乙", "편인,목");
		jeongData.put("卯", "편인,목");
		jeongData.put("丙", "겁재,화");
		jeongData.put("巳", "겁재,화");
		jeongData.put("丁", "비견,화");
		jeongData.put("午", "비견,화");
		jeongData.put("戊", "상관,토");
		jeongData.put("辰", "상관,토");
		jeongData.put("戌", "상관,토");
		jeongData.put("己", "식신,토");
		jeongData.put("丑", "식신,토");
		jeongData.put("未", "식신,토");
		jeongData.put("庚", "정재,금");
		jeongData.put("申", "정재,금");
		jeongData.put("辛", "편재,금");
		jeongData.put("酉", "편재,금");
		jeongData.put("壬", "정관,수");
		jeongData.put("亥", "정관,수");
		jeongData.put("癸", "편관,수");
		jeongData.put("子", "편관,수");
		data.put("丁", jeongData);

		// 戊일간 십성
		Map<String, String> muData = new HashMap<>();
		muData.put("甲", "편관,목");
		muData.put("寅", "편관,목");
		muData.put("乙", "정관,목");
		muData.put("卯", "정관,목");
		muData.put("丙", "편인,화");
		muData.put("巳", "편인,화");
		muData.put("丁", "정인,화");
		muData.put("午", "정인,화");
		muData.put("戊", "비견,토");
		muData.put("辰", "비견,토");
		muData.put("戌", "비견,토");
		muData.put("己", "겁재,토");
		muData.put("丑", "겁재,토");
		muData.put("未", "겁재,토");
		muData.put("庚", "식신,금");
		muData.put("申", "식신,금");
		muData.put("辛", "상관,금");
		muData.put("酉", "상관,금");
		muData.put("壬", "편재,수");
		muData.put("亥", "편재,수");
		muData.put("癸", "정재,수");
		muData.put("子", "정재,수");
		data.put("戊", muData);

		// 己일간 십성
		Map<String, String> giData = new HashMap<>();
		giData.put("甲", "정관,목");
		giData.put("寅", "정관,목");
		giData.put("乙", "편관,목");
		giData.put("卯", "편관,목");
		giData.put("丙", "정인,화");
		giData.put("巳", "정인,화");
		giData.put("丁", "편인,화");
		giData.put("午", "편인,화");
		giData.put("戊", "겁재,토");
		giData.put("辰", "겁재,토");
		giData.put("戌", "겁재,토");
		giData.put("己", "비견,토");
		giData.put("丑", "비견,토");
		giData.put("未", "비견,토");
		giData.put("庚", "상관,금");
		giData.put("申", "상관,금");
		giData.put("辛", "식신,금");
		giData.put("酉", "식신,금");
		giData.put("壬", "정재,수");
		giData.put("亥", "정재,수");
		giData.put("癸", "편재,수");
		giData.put("子", "편재,수");
		data.put("己", giData);

		// 庚일간 십성
		Map<String, String> gyeongData = new HashMap<>();
		gyeongData.put("甲", "편재,목");
		gyeongData.put("寅", "편재,목");
		gyeongData.put("乙", "정재,목");
		gyeongData.put("卯", "정재,목");
		gyeongData.put("丙", "편관,화");
		gyeongData.put("巳", "편관,화");
		gyeongData.put("丁", "정관,화");
		gyeongData.put("午", "정관,화");
		gyeongData.put("戊", "편인,토");
		gyeongData.put("辰", "편인,토");
		gyeongData.put("戌", "편인,토");
		gyeongData.put("己", "정인,토");
		gyeongData.put("丑", "정인,토");
		gyeongData.put("未", "정인,토");
		gyeongData.put("庚", "비견,금");
		gyeongData.put("申", "비견,금");
		gyeongData.put("辛", "겁재,금");
		gyeongData.put("酉", "겁재,금");
		gyeongData.put("壬", "식신,수");
		gyeongData.put("亥", "식신,수");
		gyeongData.put("癸", "상관,수");
		gyeongData.put("子", "상관,수");
		data.put("庚", gyeongData);

		// 辛일간 십성
		Map<String, String> sinData = new HashMap<>();
		sinData.put("甲", "정재,목");
		sinData.put("寅", "정재,목");
		sinData.put("乙", "편재,목");
		sinData.put("卯", "편재,목");
		sinData.put("丙", "정관,화");
		sinData.put("巳", "정관,화");
		sinData.put("丁", "편관,화");
		sinData.put("午", "편관,화");
		sinData.put("戊", "정인,토");
		sinData.put("辰", "정인,토");
		sinData.put("戌", "정인,토");
		sinData.put("己", "편인,토");
		sinData.put("丑", "편인,토");
		sinData.put("未", "편인,토");
		sinData.put("庚", "겁재,금");
		sinData.put("申", "겁재,금");
		sinData.put("辛", "비견,금");
		sinData.put("酉", "비견,금");
		sinData.put("壬", "상관,수");
		sinData.put("亥", "상관,수");
		sinData.put("癸", "식신,수");
		sinData.put("子", "식신,수");
		data.put("辛", sinData);

		// 壬일간 십성
		Map<String, String> imData = new HashMap<>();
		imData.put("甲", "식신,목");
		imData.put("寅", "식신,목");
		imData.put("乙", "상관,목");
		imData.put("卯", "상관,목");
		imData.put("丙", "편재,화");
		imData.put("巳", "편재,화");
		imData.put("丁", "정재,화");
		imData.put("午", "정재,화");
		imData.put("戊", "편관,토");
		imData.put("辰", "편관,토");
		imData.put("戌", "편관,토");
		imData.put("己", "정관,토");
		imData.put("丑", "정관,토");
		imData.put("未", "정관,토");
		imData.put("庚", "편인,금");
		imData.put("申", "편인,금");
		imData.put("辛", "정인,금");
		imData.put("酉", "정인,금");
		imData.put("壬", "비견,수");
		imData.put("亥", "비견,수");
		imData.put("癸", "겁재,수");
		imData.put("子", "겁재,수");
		data.put("壬", imData);

		// 癸일간 십성
		Map<String, String> gyeData = new HashMap<>();
		gyeData.put("甲", "상관,목");
		gyeData.put("寅", "상관,목");
		gyeData.put("乙", "식신,목");
		gyeData.put("卯", "식신,목");
		gyeData.put("丙", "정재,화");
		gyeData.put("巳", "정재,화");
		gyeData.put("丁", "편재,화");
		gyeData.put("午", "편재,화");
		gyeData.put("戊", "정관,토");
		gyeData.put("辰", "정관,토");
		gyeData.put("戌", "정관,토");
		gyeData.put("己", "편관,토");
		gyeData.put("丑", "편관,토");
		gyeData.put("未", "편관,토");
		gyeData.put("庚", "정인,금");
		gyeData.put("申", "정인,금");
		gyeData.put("辛", "편인,금");
		gyeData.put("酉", "편인,금");
		gyeData.put("壬", "겁재,수");
		gyeData.put("亥", "겁재,수");
		gyeData.put("癸", "비견,수");
		gyeData.put("子", "비견,수");
		data.put("癸", gyeData);

		return data;
	}

	/**
	 * 한글 한자 변환
	 */
	public Map<String, String> convertChineseToKorean() {
		Map<String, String> data = new HashMap<>();
		// 천간
		data.put("甲", "갑");
		data.put("乙", "을");
		data.put("丙", "병");
		data.put("丁", "정");
		data.put("戊", "무");
		data.put("己", "기");
		data.put("庚", "경");
		data.put("辛", "신");
		data.put("壬", "임");
		data.put("癸", "계");
		// 지지
		data.put("子", "자");
		data.put("丑", "축");
		data.put("寅", "인");
		data.put("卯", "묘");
		data.put("辰", "진");
		data.put("巳", "사");
		data.put("午", "오");
		data.put("未", "미");
		data.put("申", "신");
		data.put("酉", "유");
		data.put("戌", "술");
		data.put("亥", "해");
		return data;
	}

	/**
	 * 지장간 데이터 (12지지 완전판)
	 */
	public Map<String, Map<String, Object>> getJijangan() {
		Map<String, Map<String, Object>> data = new HashMap<>();

		// 子: 癸
		Map<String, Object> ja = new HashMap<>();
		ja.put("first", hidden("癸", "계", "수", "음", 30));
		ja.put("second", null);
		ja.put("third", null);
		data.put("子", ja);

		// 丑: 己 癸 辛
		Map<String, Object> chuk = new HashMap<>();
		chuk.put("first", hidden("己", "기", "토", "음", 18));
		chuk.put("second", hidden("癸", "계", "수", "음", 9));
		chuk.put("third", hidden("辛", "신", "금", "음", 3));
		data.put("丑", chuk);

		// 寅: 甲 丙 戊
		Map<String, Object> in = new HashMap<>();
		in.put("first", hidden("甲", "갑", "목", "양", 16));
		in.put("second", hidden("丙", "병", "화", "양", 7));
		in.put("third", hidden("戊", "무", "토", "양", 7));
		data.put("寅", in);

		// 卯: 乙
		Map<String, Object> myo = new HashMap<>();
		myo.put("first", hidden("乙", "을", "목", "음", 30));
		myo.put("second", null);
		myo.put("third", null);
		data.put("卯", myo);

		// 辰: 戊 乙 癸
		Map<String, Object> jin = new HashMap<>();
		jin.put("first", hidden("戊", "무", "토", "양", 18));
		jin.put("second", hidden("乙", "을", "목", "음", 9));
		jin.put("third", hidden("癸", "계", "수", "음", 3));
		data.put("辰", jin);

		// 巳: 丙 庚 戊
		Map<String, Object> sa = new HashMap<>();
		sa.put("first", hidden("丙", "병", "화", "양", 16));
		sa.put("second", hidden("庚", "경", "금", "양", 7));
		sa.put("third", hidden("戊", "무", "토", "양", 7));
		data.put("巳", sa);

		// 午: 丁 己
		Map<String, Object> o = new HashMap<>();
		o.put("first", hidden("丁", "정", "화", "음", 20));
		o.put("second", hidden("己", "기", "토", "음", 10));
		o.put("third", null);
		data.put("午", o);

		// 未: 己 丁 乙
		Map<String, Object> mi = new HashMap<>();
		mi.put("first", hidden("己", "기", "토", "음", 18));
		mi.put("second", hidden("丁", "정", "화", "음", 9));
		mi.put("third", hidden("乙", "을", "목", "음", 3));
		data.put("未", mi);

		// 申: 庚 壬 戊
		Map<String, Object> sin = new HashMap<>();
		sin.put("first", hidden("庚", "경", "금", "양", 16));
		sin.put("second", hidden("壬", "임", "수", "양", 7));
		sin.put("third", hidden("戊", "무", "토", "양", 7));
		data.put("申", sin);

		// 酉: 辛
		Map<String, Object> yu = new HashMap<>();
		yu.put("first", hidden("辛", "신", "금", "음", 30));
		yu.put("second", null);
		yu.put("third", null);
		data.put("酉", yu);

		// 戌: 戊 辛 丁
		Map<String, Object> sul = new HashMap<>();
		sul.put("first", hidden("戊", "무", "토", "양", 18));
		sul.put("second", hidden("辛", "신", "금", "음", 9));
		sul.put("third", hidden("丁", "정", "화", "음", 3));
		data.put("戌", sul);

		// 亥: 壬 甲
		Map<String, Object> hae = new HashMap<>();
		hae.put("first", hidden("壬", "임", "수", "양", 20));
		hae.put("second", hidden("甲", "갑", "목", "양", 10));
		hae.put("third", null);
		data.put("亥", hae);

		return data;
	}

	private Map<String, Object> hidden(String chinese, String korean, String fiveCircle,
		String minusPlus, int rate) {
		String color = switch (fiveCircle) {
			case "목" -> "#4CAF50";
			case "화" -> "#F44336";
			case "토" -> "#FFD600";
			case "금" -> "#E0E0E0";
			case "수" -> "#039BE5";
			default -> "";
		};

		return Map.of(
			"chinese", chinese,
			"korean", korean,
			"fiveCircle", fiveCircle,
			"fiveCircleColor", color,
			"minusPlus", minusPlus,
			"rate", rate
		);
	}

	/**
	 * 60갑자 데이터 (대운 계산용)
	 */
	public Map<Integer, String[]> getSixtyGapjaForBigFortuneList() {
		Map<Integer, String[]> data = new HashMap<>();
		String[] cheongan = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
		String[] jiji = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};

		for (int i = 1; i <= 180; i++) {
			int cheonganIndex = (i - 1) % 10;
			int jijiIndex = (i - 1) % 12;
			data.put(i, new String[]{cheongan[cheonganIndex], jiji[jijiIndex]});
		}

		return data;
	}

	/**
	 * 60갑자 데이터 (일반용)
	 */
	public Map<Integer, String[]> getSixtyGapja() {
		Map<Integer, String[]> data = new HashMap<>();
		String[] cheongan = {"庚", "辛", "壬", "癸", "甲", "乙", "丙", "丁", "戊", "己"}; // 시작점 조정
		String[] jiji = {"申", "酉", "戌", "亥", "子", "丑", "寅", "卯", "辰", "巳", "午",
			"未"}; // 시작점 조정

		for (int i = 0; i <= 59; i++) {
			int cheonganIndex = i % 10;
			int jijiIndex = i % 12;
			data.put(i, new String[]{cheongan[cheonganIndex], jiji[jijiIndex]});
		}

		return data;
	}
}

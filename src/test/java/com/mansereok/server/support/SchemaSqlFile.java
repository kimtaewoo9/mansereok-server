package com.mansereok.server.support;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.springframework.core.io.ClassPathResource;

/**
 * schema.sql 을 읽어 표 블록, 컬럼 이름, 인덱스·제약 이름을 찾는다. 엔티티가 매핑하는 컬럼 이름도 계산해, 영역마다 쓰는
 * "schema.sql 과 엔티티가 어긋나지 않았는가" 테스트가 함께 쓴다.
 *
 * <p>운영 DB 는 ddl-auto: validate 이고 스키마는 사람이 손으로 적용한다. 그래서 엔티티를 바꾸면 schema.sql 도 함께 바꿔야
 * 하는데, 이를 잊으면 배포 때 validate 에서 처음 드러난다. 이 도우미는 그 어긋남을 기본 테스트에서 먼저 잡게 한다.
 *
 * <p>읽는 문장은 {@code CREATE TABLE}, {@code CREATE [UNIQUE] INDEX ... ON}, {@code ALTER TABLE} 이다. 이름은 백틱이
 * 있어도 없어도 찾고, 대소문자를 가리지 않는다. 줄 끝 주석({@code --}, {@code #})과 블록 주석은 먼저 걷어 낸다. 따옴표 안은
 * 글자 그대로 두고, 작은따옴표·큰따옴표 안의 백슬래시 이스케이프({@code 'it\'s'})도 MySQL 기본 동작대로 읽는다.
 */
public final class SchemaSqlFile {

	private static final String CLASSPATH_LOCATION = "schema.sql";
	private static final Set<String> NON_COLUMN_KEYWORDS = Set.of(
		"PRIMARY", "KEY", "INDEX", "UNIQUE", "CONSTRAINT", "FOREIGN", "FULLTEXT", "SPATIAL", "CHECK");
	// CREATE TABLE 안에서 인덱스·제약 정의를 여는 첫 낱말. 이 낱말로 시작하지 않는 줄은 컬럼 정의이거나 이름 없는 CHECK 다.
	private static final Set<String> KEY_DEFINITION_STARTS = Set.of(
		"PRIMARY", "KEY", "INDEX", "UNIQUE", "CONSTRAINT", "FOREIGN", "FULLTEXT", "SPATIAL");
	// 인덱스·제약 정의에서 컬럼 목록 앞에 올 수 있는 낱말. 이 낱말이 아닌 것이 인덱스·제약 이름이다.
	private static final Set<String> KEY_HEADER_KEYWORDS = Set.of(
		"PRIMARY", "KEY", "INDEX", "UNIQUE", "CONSTRAINT", "FOREIGN", "FULLTEXT", "SPATIAL", "CHECK", "USING",
		"BTREE", "HASH");
	private static final CamelCaseToUnderscoresNamingStrategy SPRING_BOOT_NAMING =
		new CamelCaseToUnderscoresNamingStrategy();

	private final String text;
	private final List<String> statements;

	private SchemaSqlFile(String text) {
		this.text = text;
		this.statements = splitStatements(stripComments(text));
	}

	/** 클래스패스의 schema.sql(src/main/resources/schema.sql)을 읽는다. */
	public static SchemaSqlFile load() {
		try {
			return new SchemaSqlFile(
				new ClassPathResource(CLASSPATH_LOCATION).getContentAsString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("클래스패스에서 " + CLASSPATH_LOCATION + " 를 읽지 못했다.", e);
		}
	}

	/** 문자열로 준 SQL 을 schema.sql 처럼 읽는다. 도우미 자체를 검증할 때 쓴다. */
	public static SchemaSqlFile of(String sql) {
		return new SchemaSqlFile(sql);
	}

	/** 주석까지 포함한 원문. "DROP TABLE 이 없다" 같은 파일 전체 검사에 쓴다. */
	public String text() {
		return text;
	}

	public boolean hasTable(String table) {
		return findCreateTable(table).isPresent();
	}

	/**
	 * 표의 CREATE TABLE 문장 전체(주석을 걷어 낸 것, 끝의 세미콜론 제외)를 돌려준다.
	 *
	 * @throws IllegalArgumentException schema.sql 에 그 표의 CREATE TABLE 이 없을 때
	 */
	public String createTableBlock(String table) {
		return findCreateTable(table).orElseThrow(() ->
			new IllegalArgumentException("schema.sql 에 CREATE TABLE " + table + " 가 없다."));
	}

	/**
	 * CREATE TABLE 괄호 안에서 컬럼 정의의 이름만 소문자로 돌려준다. PRIMARY KEY, INDEX, CONSTRAINT 같은 줄은 뺀다.
	 *
	 * @throws IllegalArgumentException schema.sql 에 그 표의 CREATE TABLE 이 없을 때
	 */
	public Set<String> columnNames(String table) {
		Set<String> columns = new LinkedHashSet<>();
		for (String definition : columnDefinitions(createTableBlock(table))) {
			columns.add(normalizedName(firstWord(definition)));
		}
		return Collections.unmodifiableSet(columns);
	}

	/**
	 * 인덱스나 제약 이름이 그 표에 걸려 있는지 본다. 이름이 놓이는 자리만 보므로 컬럼 이름, 표 이름, 컬럼 목록 안의 이름은 걸리지
	 * 않는다. 이름 일부만 겹치는 경우(idx_orders 와 idx_orders_user_id)도 걸리지 않는다.
	 *
	 * <ul>
	 *   <li>CREATE TABLE 안에서 INDEX·KEY·UNIQUE·CONSTRAINT·PRIMARY·FOREIGN·FULLTEXT·SPATIAL 로 시작하는 정의의 이름</li>
	 *   <li>그 표를 대상으로 한 {@code CREATE [UNIQUE] INDEX 이름 ON 표 (...)} 의 이름</li>
	 *   <li>그 표를 대상으로 한 {@code ALTER TABLE 표 ADD ...} 로 더한 인덱스·제약의 이름</li>
	 * </ul>
	 *
	 * <p>{@code email VARCHAR(255) UNIQUE} 처럼 컬럼 끝에 UNIQUE 를 붙이면 MySQL 은 컬럼 이름(email)을 인덱스 이름으로 쓰지만,
	 * 여기서는 이름으로 보지 않는다. 그런 UNIQUE 는 {@link #isUniqueColumn(String, String)} 으로 확인한다.
	 */
	public boolean mentions(String table, String indexOrConstraintName) {
		String name = normalizedName(indexOrConstraintName);
		return keyDefinitions(table).stream().anyMatch(key -> key.names().contains(name));
	}

	/**
	 * 그 컬럼 하나만으로 된 UNIQUE 가 그 표에 있는지 본다. 컬럼 정의 끝의 UNIQUE, CREATE TABLE 안의 {@code UNIQUE KEY (컬럼)},
	 * {@code CREATE UNIQUE INDEX ... ON 표 (컬럼)}, {@code ALTER TABLE 표 ADD UNIQUE ... (컬럼)} 을 센다.
	 *
	 * <p>여러 컬럼을 묶은 UNIQUE 와 PRIMARY KEY 는 세지 않는다. ALTER TABLE 의 ADD COLUMN·MODIFY 끝에 붙인 UNIQUE 는 읽지 않는다.
	 * 따옴표 안의 UNIQUE(예: COMMENT)는 낱말로 보지 않는다.
	 */
	public boolean isUniqueColumn(String table, String column) {
		String name = normalizedName(column);
		boolean uniqueAtColumnEnd = findCreateTable(table).stream()
			.flatMap(createTable -> columnDefinitions(createTable).stream())
			.filter(definition -> normalizedName(firstWord(definition)).equals(name))
			.anyMatch(definition -> wordsOutsideQuotesAndParentheses(definition).contains("UNIQUE"));
		return uniqueAtColumnEnd || keyDefinitions(table).stream()
			.anyMatch(key -> key.unique() && key.columns().equals(List.of(name)));
	}

	/**
	 * 엔티티가 매핑하는 컬럼 이름을 소문자로 돌려준다. 스프링 부트 기본 이름 규칙(CamelCaseToUnderscoresNamingStrategy)을
	 * 그대로 써서 Hibernate 가 실제로 쓰는 이름과 같게 계산한다.
	 *
	 * <ul>
	 *   <li>{@code @Column(name)} 이나 {@code @JoinColumn(name)} 이 있으면 그 이름, 없으면 필드 이름(연관이면 필드 이름 + _id).</li>
	 *   <li>static 필드, transient 필드, {@code @Transient}, 컬렉션 연관({@code @OneToMany}, {@code @ManyToMany},
	 *   {@code @ElementCollection}), mappedBy 로 반대편이 주인인 {@code @OneToOne} 은 빼고 센다.</li>
	 * </ul>
	 *
	 * @throws IllegalArgumentException 부모 클래스({@code @MappedSuperclass} 등)가 있거나 {@code @Embedded}·{@code @EmbeddedId}
	 *                                  필드가 있을 때. 이 저장소의 엔티티에 아직 없는 모양이라 틀린 답을 내는 대신 멈춘다.
	 */
	public static Set<String> mappedColumnNames(Class<?> entity) {
		Class<?> parent = entity.getSuperclass();
		if (parent != null && parent != Object.class) {
			throw new IllegalArgumentException(entity.getSimpleName() + " 는 부모 클래스("
				+ parent.getSimpleName() + ")가 있어 컬럼 이름을 계산하지 않는다.");
		}
		Set<String> columns = new LinkedHashSet<>();
		for (Field field : entity.getDeclaredFields()) {
			columnNameOf(entity, field).ifPresent(columns::add);
		}
		return Collections.unmodifiableSet(columns);
	}

	private static Optional<String> columnNameOf(Class<?> entity, Field field) {
		int modifiers = field.getModifiers();
		if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()
			|| field.isAnnotationPresent(Transient.class)
			|| field.isAnnotationPresent(OneToMany.class)
			|| field.isAnnotationPresent(ManyToMany.class)
			|| field.isAnnotationPresent(ElementCollection.class)) {
			return Optional.empty();
		}
		OneToOne oneToOne = field.getAnnotation(OneToOne.class);
		if (oneToOne != null && !oneToOne.mappedBy().isEmpty()) {
			return Optional.empty();
		}
		if (field.isAnnotationPresent(Embedded.class) || field.isAnnotationPresent(EmbeddedId.class)) {
			throw new IllegalArgumentException(
				entity.getSimpleName() + "." + field.getName() + " 는 @Embedded 라 컬럼 이름을 계산하지 않는다.");
		}
		return Optional.of(physicalName(logicalName(field)));
	}

	private static String logicalName(Field field) {
		JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
		if (joinColumn != null) {
			return joinColumn.name().isEmpty() ? field.getName() + "_id" : joinColumn.name();
		}
		if (oneToOneOrManyToOne(field)) {
			return field.getName() + "_id";
		}
		Column column = field.getAnnotation(Column.class);
		return column == null || column.name().isEmpty() ? field.getName() : column.name();
	}

	private static boolean oneToOneOrManyToOne(Field field) {
		return field.isAnnotationPresent(OneToOne.class)
			|| field.isAnnotationPresent(ManyToOne.class);
	}

	private static String physicalName(String logicalName) {
		Identifier identifier = SPRING_BOOT_NAMING.toPhysicalColumnName(
			Identifier.toIdentifier(unquote(logicalName)), null);
		return identifier.getText().toLowerCase(Locale.ROOT);
	}

	private Optional<String> findCreateTable(String table) {
		return statements.stream()
			.filter(statement -> isCreateTable(statement, table))
			.findFirst();
	}

	private static boolean isCreateTable(String statement, String table) {
		return Pattern.compile(
				"^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?" + quotedOrBare(table) + "\\s*\\(",
				Pattern.CASE_INSENSITIVE)
			.matcher(statement)
			.find();
	}

	/**
	 * 표 이름을 백틱이 있든 없든 맞춘다. 이 식 뒤에는 늘 여는 괄호나 공백을 붙여 쓰므로 orders 가 orders_history 에 걸리지
	 * 않는다.
	 */
	private static String quotedOrBare(String name) {
		String quoted = Pattern.quote(name);
		return "(`" + quoted + "`|" + quoted + ")";
	}

	/** CREATE TABLE 괄호 안의 정의 중 컬럼 정의만 돌려준다. */
	private static List<String> columnDefinitions(String createTable) {
		return splitTopLevel(parenthesizedBody(createTable)).stream()
			.filter(definition -> {
				String firstWord = firstWord(definition);
				return !firstWord.isEmpty()
					&& !NON_COLUMN_KEYWORDS.contains(firstWord.toUpperCase(Locale.ROOT));
			})
			.toList();
	}

	/**
	 * 그 표에 걸린 인덱스·제약 정의를 모두 모은다. CREATE TABLE 안의 정의, CREATE INDEX ... ON 표, ALTER TABLE 표 ADD ... 를 본다.
	 */
	private List<KeyDefinition> keyDefinitions(String table) {
		Pattern createIndexOnTable = Pattern.compile(
			"^\\s*CREATE\\s+(?<kind>UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?INDEX\\s+(?<name>\\S+)\\s+(USING\\s+\\w+\\s+)?ON\\s+"
				+ quotedOrBare(table) + "\\s*\\(", Pattern.CASE_INSENSITIVE);
		Pattern alterTable = Pattern.compile(
			"^\\s*ALTER\\s+TABLE\\s+" + quotedOrBare(table) + "(\\s|$)", Pattern.CASE_INSENSITIVE);
		List<KeyDefinition> keys = new ArrayList<>();
		for (String statement : statements) {
			if (isCreateTable(statement, table)) {
				splitTopLevel(parenthesizedBody(statement)).forEach(
					definition -> keyDefinition(definition).ifPresent(keys::add));
				continue;
			}
			Matcher createIndex = createIndexOnTable.matcher(statement);
			if (createIndex.find()) {
				String kind = createIndex.group("kind");
				keys.add(new KeyDefinition(
					Set.of(normalizedName(createIndex.group("name"))),
					kind != null && kind.trim().equalsIgnoreCase("UNIQUE"),
					keyColumns(parenthesizedBody(statement.substring(createIndex.end() - 1)))));
				continue;
			}
			Matcher alter = alterTable.matcher(statement);
			if (alter.find()) {
				for (String change : splitTopLevel(statement.substring(alter.end()))) {
					String[] words = change.trim().split("\\s+", 2);
					if (words.length == 2 && words[0].equalsIgnoreCase("ADD")) {
						keyDefinition(words[1]).ifPresent(keys::add);
					}
				}
			}
		}
		return keys;
	}

	/**
	 * 인덱스·제약 정의 하나를 읽는다. 첫 여는 괄호 앞(머리)에서 예약어가 아닌 낱말을 이름으로, 첫 괄호 안을 컬럼 목록으로 본다.
	 * {@code CONSTRAINT fk FOREIGN KEY (user_id)}, {@code UNIQUE KEY uk (a, b)}, {@code INDEX idx USING BTREE (a)} 를 모두 읽는다.
	 *
	 * @return 컬럼 정의나 이름 없는 CHECK 처럼 인덱스·제약 정의가 아니면 빈 값
	 */
	private static Optional<KeyDefinition> keyDefinition(String definition) {
		if (!KEY_DEFINITION_STARTS.contains(firstWord(definition).toUpperCase(Locale.ROOT))) {
			return Optional.empty();
		}
		int open = indexOfOpenParenthesis(definition);
		String header = open < 0 ? definition : definition.substring(0, open);
		Set<String> names = new LinkedHashSet<>();
		boolean unique = false;
		for (String word : header.trim().split("\\s+")) {
			String upper = word.toUpperCase(Locale.ROOT);
			unique |= upper.equals("UNIQUE");
			if (!KEY_HEADER_KEYWORDS.contains(upper)) {
				names.add(normalizedName(word));
			}
		}
		List<String> columns = open < 0 ? List.of() : keyColumns(parenthesizedBody(definition.substring(open)));
		return Optional.of(new KeyDefinition(names, unique, columns));
	}

	/** 인덱스 컬럼 목록에서 컬럼 이름만 소문자로 꺼낸다. 앞부분 길이(email(10))와 ASC·DESC 는 뺀다. */
	private static List<String> keyColumns(String columnList) {
		return splitTopLevel(columnList).stream()
			.map(SchemaSqlFile::firstWord)
			.filter(word -> !word.isEmpty())
			.map(SchemaSqlFile::normalizedName)
			.toList();
	}

	/**
	 * 인덱스·제약 정의 하나. names 는 소문자 이름(CONSTRAINT 이름과 인덱스 이름을 함께 적으면 둘 다), unique 는 UNIQUE 로 선언했는지,
	 * columns 는 괄호 안 컬럼 이름을 적힌 순서대로 담는다.
	 */
	private record KeyDefinition(Set<String> names, boolean unique, List<String> columns) {

	}

	private static String normalizedName(String name) {
		return unquote(name.trim()).toLowerCase(Locale.ROOT);
	}

	/** 따옴표와 괄호 밖에 있는 낱말을 대문자로 돌려준다. 컬럼 정의 끝의 UNIQUE 처럼 속성 낱말을 찾을 때 쓴다. */
	private static List<String> wordsOutsideQuotesAndParentheses(String text) {
		StringBuilder visible = new StringBuilder(text.length());
		int depth = 0;
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (isQuote(c)) {
				i = endOfQuoted(text, i);
				visible.append(' ');
				continue;
			}
			if (c == '(') {
				depth++;
				visible.append(' ');
			} else if (c == ')') {
				depth--;
				visible.append(' ');
			} else if (depth == 0) {
				visible.append(c);
			}
			i++;
		}
		return List.of(visible.toString().toUpperCase(Locale.ROOT).trim().split("\\s+"));
	}

	private static boolean isQuote(char c) {
		return c == '\'' || c == '"' || c == '`';
	}

	/**
	 * open 자리의 여는 따옴표와 짝이 맞는 닫는 따옴표 바로 다음 자리를 돌려준다. 닫는 따옴표가 없으면 문자열 길이를 돌려준다.
	 *
	 * <p>작은따옴표·큰따옴표 안의 백슬래시는 다음 글자를 글자 그대로 둔다({@code 'it\'s'}). 백틱 안의 백슬래시는 MySQL 에서도
	 * 이스케이프가 아니라 그대로 둔다. 따옴표 두 개를 겹쳐 쓴 경우({@code 'it''s'})는 닫힌 뒤 곧바로 다시 열리므로 따로 다루지
	 * 않아도 같은 결과가 된다.
	 */
	private static int endOfQuoted(String sql, int open) {
		char quote = sql.charAt(open);
		int i = open + 1;
		while (i < sql.length()) {
			char c = sql.charAt(i);
			if (c == '\\' && quote != '`') {
				i += 2;
			} else if (c == quote) {
				return i + 1;
			} else {
				i++;
			}
		}
		return sql.length();
	}

	/** 따옴표 밖의 첫 여는 괄호 자리. 없으면 -1. */
	private static int indexOfOpenParenthesis(String text) {
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (isQuote(c)) {
				i = endOfQuoted(text, i);
			} else if (c == '(') {
				return i;
			} else {
				i++;
			}
		}
		return -1;
	}

	/** 첫 여는 괄호와 짝이 맞는 닫는 괄호 사이를 돌려준다. 따옴표 안의 괄호는 세지 않는다. */
	private static String parenthesizedBody(String text) {
		int open = indexOfOpenParenthesis(text);
		int depth = 0;
		int i = Math.max(open, 0);
		while (open >= 0 && i < text.length()) {
			char c = text.charAt(i);
			if (isQuote(c)) {
				i = endOfQuoted(text, i);
				continue;
			}
			if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return text.substring(open + 1, i);
			}
			i++;
		}
		throw new IllegalArgumentException("괄호 짝이 맞지 않는다: " + text);
	}

	/** 괄호 깊이 0 에 있는 쉼표로 나눈다. ENUM('A', 'B') 나 DECIMAL(10, 2), 따옴표 안의 쉼표에서는 나누지 않는다. */
	private static List<String> splitTopLevel(String body) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int partStart = 0;
		int i = 0;
		while (i < body.length()) {
			char c = body.charAt(i);
			if (isQuote(c)) {
				i = endOfQuoted(body, i);
				continue;
			}
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (c == ',' && depth == 0) {
				parts.add(body.substring(partStart, i).trim());
				partStart = i + 1;
			}
			i++;
		}
		parts.add(body.substring(partStart).trim());
		return parts;
	}

	private static String firstWord(String definition) {
		String trimmed = definition.trim();
		if (trimmed.startsWith("`")) {
			int close = trimmed.indexOf('`', 1);
			return close < 0 ? trimmed : trimmed.substring(0, close + 1);
		}
		String[] words = trimmed.split("[\\s(]+", 2);
		return words[0];
	}

	private static String unquote(String name) {
		if (name.length() >= 2 && name.startsWith("`") && name.endsWith("`")) {
			return name.substring(1, name.length() - 1);
		}
		return name;
	}

	/** 세미콜론으로 문장을 나눈다. 따옴표 안의 세미콜론에서는 나누지 않는다. */
	private static List<String> splitStatements(String sql) {
		List<String> result = new ArrayList<>();
		int statementStart = 0;
		int i = 0;
		while (i < sql.length()) {
			char c = sql.charAt(i);
			if (isQuote(c)) {
				i = endOfQuoted(sql, i);
				continue;
			}
			if (c == ';') {
				addIfNotBlank(result, sql.substring(statementStart, i));
				statementStart = i + 1;
			}
			i++;
		}
		addIfNotBlank(result, sql.substring(statementStart));
		return result;
	}

	private static void addIfNotBlank(List<String> statements, String statement) {
		String trimmed = statement.trim();
		if (!trimmed.isEmpty()) {
			statements.add(trimmed);
		}
	}

	/** -- 와 # 로 시작하는 줄 끝 주석과 블록 주석을 걷어 낸다. 따옴표 안은 그대로 둔다. */
	private static String stripComments(String sql) {
		StringBuilder result = new StringBuilder(sql.length());
		int i = 0;
		while (i < sql.length()) {
			char c = sql.charAt(i);
			if (isQuote(c)) {
				int end = endOfQuoted(sql, i);
				result.append(sql, i, end);
				i = end;
			} else if (sql.startsWith("--", i) || c == '#') {
				int lineEnd = sql.indexOf('\n', i);
				i = lineEnd < 0 ? sql.length() : lineEnd;
			} else if (sql.startsWith("/*", i)) {
				int blockEnd = sql.indexOf("*/", i + 2);
				i = blockEnd < 0 ? sql.length() : blockEnd + 2;
				result.append(' ');
			} else {
				result.append(c);
				i++;
			}
		}
		return result.toString();
	}
}

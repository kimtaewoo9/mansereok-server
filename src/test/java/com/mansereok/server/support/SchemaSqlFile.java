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
import java.util.regex.Pattern;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.springframework.core.io.ClassPathResource;

/**
 * schema.sql 을 읽어 표 블록, 컬럼 이름, 인덱스·제약 이름을 찾는다. 엔티티가 매핑하는 컬럼 이름도 계산해, 각 스택의
 * "schema.sql 과 엔티티가 어긋나지 않았는가" 테스트가 함께 쓴다.
 *
 * <p>운영 DB 는 ddl-auto: validate 이고 스키마는 사람이 손으로 적용한다. 그래서 엔티티를 바꾸면 schema.sql 도 함께 바꿔야
 * 하는데, 이를 잊으면 배포 때 validate 에서 처음 드러난다. 이 도우미는 그 어긋남을 기본 테스트에서 먼저 잡게 한다.
 *
 * <p>읽는 문장은 {@code CREATE TABLE}, {@code CREATE [UNIQUE] INDEX ... ON}, {@code ALTER TABLE} 이다. 이름은 백틱이
 * 있어도 없어도 찾고, 대소문자를 가리지 않는다. 줄 끝 주석({@code --}, {@code #})과 블록 주석은 먼저 걷어 낸다.
 */
public final class SchemaSqlFile {

	private static final String CLASSPATH_LOCATION = "schema.sql";
	private static final Set<String> NON_COLUMN_KEYWORDS = Set.of(
		"PRIMARY", "KEY", "INDEX", "UNIQUE", "CONSTRAINT", "FOREIGN", "FULLTEXT", "SPATIAL", "CHECK");
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
		for (String definition : splitTopLevel(parenthesizedBody(createTableBlock(table)))) {
			String firstWord = firstWord(definition);
			if (firstWord.isEmpty() || NON_COLUMN_KEYWORDS.contains(firstWord.toUpperCase(Locale.ROOT))) {
				continue;
			}
			columns.add(unquote(firstWord).toLowerCase(Locale.ROOT));
		}
		return Collections.unmodifiableSet(columns);
	}

	/**
	 * 인덱스나 제약 이름이 그 표에 걸려 있는지 본다. CREATE TABLE 안, 그 표를 대상으로 한 CREATE INDEX ... ON 과
	 * ALTER TABLE 문장을 모두 찾는다. 이름 일부만 겹치는 경우(idx_orders 와 idx_orders_user_id)는 걸리지 않는다.
	 */
	public boolean mentions(String table, String indexOrConstraintName) {
		Pattern name = identifierPattern(indexOrConstraintName);
		Pattern createIndexOnTable = Pattern.compile(
			"^\\s*CREATE\\s+(UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?INDEX\\s+\\S+\\s+ON\\s+" + quotedOrBare(table)
				+ "\\s*\\(", Pattern.CASE_INSENSITIVE);
		Pattern alterTable = Pattern.compile(
			"^\\s*ALTER\\s+TABLE\\s+" + quotedOrBare(table) + "(\\s|$)", Pattern.CASE_INSENSITIVE);
		for (String statement : statements) {
			boolean aboutTable = isCreateTable(statement, table)
				|| createIndexOnTable.matcher(statement).find()
				|| alterTable.matcher(statement).find();
			if (aboutTable && name.matcher(statement).find()) {
				return true;
			}
		}
		return false;
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
	 *                                  필드가 있을 때. 세 스택의 엔티티에 아직 없는 모양이라 틀린 답을 내는 대신 멈춘다.
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

	private static Pattern identifierPattern(String name) {
		return Pattern.compile("(?<![A-Za-z0-9_$])`?" + Pattern.quote(name) + "`?(?![A-Za-z0-9_$])",
			Pattern.CASE_INSENSITIVE);
	}

	/** 첫 여는 괄호와 짝이 맞는 닫는 괄호 사이를 돌려준다. 따옴표 안의 괄호는 세지 않는다. */
	private static String parenthesizedBody(String createTable) {
		int open = createTable.indexOf('(');
		int depth = 0;
		char quote = 0;
		for (int i = open; i < createTable.length(); i++) {
			char c = createTable.charAt(i);
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				}
			} else if (c == '\'' || c == '"' || c == '`') {
				quote = c;
			} else if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return createTable.substring(open + 1, i);
			}
		}
		throw new IllegalArgumentException("CREATE TABLE 의 괄호 짝이 맞지 않는다: " + createTable);
	}

	/** 괄호 깊이 0 에 있는 쉼표로 나눈다. ENUM('A', 'B') 나 DECIMAL(10, 2) 안의 쉼표에서는 나누지 않는다. */
	private static List<String> splitTopLevel(String body) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int depth = 0;
		char quote = 0;
		for (char c : body.toCharArray()) {
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				}
			} else if (c == '\'' || c == '"' || c == '`') {
				quote = c;
			} else if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (c == ',' && depth == 0) {
				parts.add(current.toString().trim());
				current.setLength(0);
				continue;
			}
			current.append(c);
		}
		parts.add(current.toString().trim());
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
		StringBuilder current = new StringBuilder();
		char quote = 0;
		for (char c : sql.toCharArray()) {
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				}
			} else if (c == '\'' || c == '"' || c == '`') {
				quote = c;
			} else if (c == ';') {
				addIfNotBlank(result, current);
				continue;
			}
			current.append(c);
		}
		addIfNotBlank(result, current);
		return result;
	}

	private static void addIfNotBlank(List<String> statements, StringBuilder current) {
		String statement = current.toString().trim();
		if (!statement.isEmpty()) {
			statements.add(statement);
		}
		current.setLength(0);
	}

	/** -- 와 # 로 시작하는 줄 끝 주석과 블록 주석을 걷어 낸다. 따옴표 안은 그대로 둔다. */
	private static String stripComments(String sql) {
		StringBuilder result = new StringBuilder(sql.length());
		char quote = 0;
		int i = 0;
		while (i < sql.length()) {
			char c = sql.charAt(i);
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				}
				result.append(c);
				i++;
			} else if (c == '\'' || c == '"' || c == '`') {
				quote = c;
				result.append(c);
				i++;
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

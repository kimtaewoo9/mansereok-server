package com.mansereok.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.discount.entity.DiscountCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SchemaSqlFileTest {

	// 이 프로젝트 schema.sql 에 실제로 있는 모양을 섞었다. 백틱 있는 이름과 없는 이름, 주석 속 괄호·쉼표,
	// ENUM 안의 쉼표, 표 안의 INDEX 와 표 밖의 CREATE INDEX.
	private static final String SCHEMA = """
		-- 테이블 전체 삭제 (개발 초기 안전을 위해 사용)
		DROP TABLE IF EXISTS `orders`;

		-- 이름 앞부분이 같은 표를 먼저 두어, orders 를 찾을 때 이 표가 걸리지 않는지 본다.
		CREATE TABLE orders_history (
		    id BIGINT PRIMARY KEY,
		    INDEX idx_orders_history_id (id)
		);

		CREATE TABLE `orders` (
		    `id` BIGINT NOT NULL AUTO_INCREMENT,
		    `merchant_uid` VARCHAR(255) NOT NULL UNIQUE,
		    -- 주문 상태 (PENDING, PAID) 주석 속 쉼표와 괄호
		    `status` ENUM('PENDING', 'PAID', 'FAILED') NOT NULL DEFAULT 'PENDING',
		    `amount` DECIMAL(10, 2) NOT NULL,
		    PRIMARY KEY (`id`),
		    INDEX `idx_orders_merchant_uid` (`merchant_uid`),
		    CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

		CREATE TABLE payments
		(
		    id BIGINT AUTO_INCREMENT PRIMARY KEY,
		    imp_uid VARCHAR(255) NOT NULL UNIQUE,
		    order_id BIGINT NOT NULL,
		    UNIQUE KEY uk_payments_order_id (order_id),
		    INDEX idx_payments_order_id (order_id)
		);

		CREATE INDEX idx_orders_status ON orders(status);
		ALTER TABLE payments ADD CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id);
		""";

	private final SchemaSqlFile schemaSqlFile = SchemaSqlFile.of(SCHEMA);

	@Test
	@DisplayName("클래스패스의 schema.sql(src/main/resources)을 읽어 만세력 기초 데이터 표를 찾는다")
	void loadsSchemaSqlFromClasspath() {
		assertThat(SchemaSqlFile.load().hasTable("manses")).isTrue();
	}

	@Nested
	@DisplayName("표 블록을 찾을 때")
	class CreateTableBlock {

		@Test
		@DisplayName("백틱 있는 표 이름으로 그 표의 CREATE TABLE 문장만 돌려준다")
		void findsBacktickedTable() {
			// when
			String block = schemaSqlFile.createTableBlock("orders");

			// then
			assertThat(block).startsWith("CREATE TABLE `orders` (")
				.contains("`idx_orders_merchant_uid`")
				.doesNotContain("CREATE TABLE payments")
				.doesNotContain("주석 속 쉼표");
		}

		@Test
		@DisplayName("백틱 없는 표 이름도 찾고, 이름이 앞부분만 같은 다른 표(orders_history)와 헷갈리지 않는다")
		void findsBareTableWithoutMatchingLongerName() {
			assertThat(schemaSqlFile.createTableBlock("payments")).startsWith("CREATE TABLE payments");
			assertThat(schemaSqlFile.createTableBlock("orders_history")).startsWith(
				"CREATE TABLE orders_history");
		}

		@Test
		@DisplayName("CREATE TABLE 이 없는 표는 이름을 담은 예외로 알려 준다")
		void rejectsUnknownTable() {
			assertThat(schemaSqlFile.hasTable("reviews")).isFalse();
			assertThatThrownBy(() -> schemaSqlFile.createTableBlock("reviews"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("schema.sql 에 CREATE TABLE reviews 가 없다.");
		}
	}

	@Nested
	@DisplayName("컬럼 이름을 찾을 때")
	class ColumnNames {

		@Test
		@DisplayName("컬럼 정의만 골라 소문자로 돌려주고 PRIMARY KEY·INDEX·CONSTRAINT 줄과 ENUM 값은 뺀다")
		void listsColumnDefinitionsOnly() {
			assertThat(schemaSqlFile.columnNames("orders"))
				.containsExactly("id", "merchant_uid", "status", "amount");
		}

		@Test
		@DisplayName("백틱 없는 표에서도 UNIQUE KEY·INDEX 줄을 빼고 컬럼만 돌려준다")
		void listsColumnsOfBareTable() {
			assertThat(schemaSqlFile.columnNames("payments"))
				.containsExactly("id", "imp_uid", "order_id");
		}
	}

	@Nested
	@DisplayName("인덱스·제약 이름을 찾을 때")
	class Mentions {

		@Test
		@DisplayName("표 안의 인덱스·제약 이름을 백틱이 있든 없든 찾는다")
		void findsNamesInsideCreateTable() {
			assertThat(schemaSqlFile.mentions("orders", "idx_orders_merchant_uid")).isTrue();
			assertThat(schemaSqlFile.mentions("orders", "fk_orders_user")).isTrue();
			assertThat(schemaSqlFile.mentions("payments", "uk_payments_order_id")).isTrue();
		}

		@Test
		@DisplayName("표 밖의 CREATE INDEX ... ON 과 ALTER TABLE 에서도 그 표에 걸린 이름을 찾는다")
		void findsNamesInSeparateStatements() {
			assertThat(schemaSqlFile.mentions("orders", "idx_orders_status")).isTrue();
			assertThat(schemaSqlFile.mentions("payments", "fk_payments_order")).isTrue();
		}

		@Test
		@DisplayName("다른 표에 걸린 이름이나 이름 앞부분만 같은 경우는 찾지 않는다")
		void ignoresOtherTablesAndPartialNames() {
			assertThat(schemaSqlFile.mentions("payments", "idx_orders_status")).isFalse();
			assertThat(schemaSqlFile.mentions("orders", "idx_orders_history_id")).isFalse();
			assertThat(schemaSqlFile.mentions("orders", "idx_orders")).isFalse();
		}
	}

	@Nested
	@DisplayName("엔티티가 매핑하는 컬럼 이름을 계산할 때")
	class MappedColumnNames {

		@Test
		@DisplayName("@Column·@JoinColumn 이름과 필드 이름의 snake_case 를 섞어 돌려주고, 컬럼이 없는 필드는 뺀다")
		void mixesExplicitNamesAndSnakeCase() {
			assertThat(SchemaSqlFile.mappedColumnNames(SampleOrder.class))
				.containsExactlyInAnyOrder(
					"id",
					"merchant_uid",      // 필드 이름 merchantUid
					"is_active",         // boolean 필드 isActive
					"paid_amount",       // @Column(name = "paid_amount") 필드 amount
					"person1name",       // 숫자 뒤 대문자에는 밑줄을 넣지 않는다(스프링 부트 기본 이름 규칙)
					"user_id",           // @ManyToOne + @JoinColumn(name = "user_id")
					"parent_order_id");  // @ManyToOne, @JoinColumn 없음 → 필드 이름 + _id
		}

		@Test
		@DisplayName("실제 엔티티(DiscountCode)도 DB 에 만들어지는 컬럼 이름으로 계산한다")
		void computesRealEntityColumns() {
			assertThat(SchemaSqlFile.mappedColumnNames(DiscountCode.class))
				.containsExactlyInAnyOrder("id", "code", "discount_type", "discount_value", "expires_at",
					"max_uses", "current_uses", "min_purchase_amount", "is_active", "sub_category_id",
					"category_id");
		}

		@Test
		@DisplayName("부모 클래스가 있거나 @Embedded 필드가 있으면 틀린 답을 내지 않고 멈춘다")
		void rejectsShapesItDoesNotHandle() {
			assertThatThrownBy(() -> SchemaSqlFile.mappedColumnNames(SampleChildOrder.class))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("SampleChildOrder 는 부모 클래스(SampleOrder)가 있어 컬럼 이름을 계산하지 않는다.");
			assertThatThrownBy(() -> SchemaSqlFile.mappedColumnNames(SampleWithEmbedded.class))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("SampleWithEmbedded.address 는 @Embedded 라 컬럼 이름을 계산하지 않는다.");
		}
	}

	/*
	 * 아래는 컬럼 이름 계산만 확인하는 표본 클래스다. @Entity·@MappedSuperclass·@Embeddable 을 붙이지 않는다. 붙이면 실제
	 * MySQL 테스트에서 Hibernate 가 이 클래스까지 읽어 테스트 DB 에 표를 만든다.
	 */

	@SuppressWarnings("unused")
	static class SampleOrder {

		private static final String NOT_A_COLUMN = "static 필드는 컬럼이 아니다";

		@Id
		private Long id;

		private String merchantUid;

		private boolean isActive;

		@Column(name = "paid_amount")
		private int amount;

		private String person1Name;

		@ManyToOne
		@JoinColumn(name = "user_id")
		private Object user;

		@ManyToOne
		private Object parentOrder;

		@OneToOne(mappedBy = "order")
		private Object receipt;

		@OneToMany
		private List<Object> items;

		@Transient
		private String displayName;

		private transient String cachedLabel;
	}

	static class SampleChildOrder extends SampleOrder {

	}

	@SuppressWarnings("unused")
	static class SampleWithEmbedded {

		@Id
		private Long id;

		@Embedded
		private Object address;
	}
}

-- 테이블 전체 삭제 (개발 초기 안전을 위해 사용)
DROP TABLE IF EXISTS `refresh_tokens`;
DROP TABLE IF EXISTS `personal_info`;
DROP TABLE IF EXISTS `manses`;
DROP TABLE IF EXISTS `users`;

-- 1. 사용자 정보 테이블 (users)
CREATE TABLE `users` (
                         `id` BIGINT NOT NULL AUTO_INCREMENT,
                         `username` VARCHAR(255) UNIQUE,
                         `email` VARCHAR(255) UNIQUE,
                         `password` VARCHAR(255),
                         `name` VARCHAR(255),

    -- Role enum ('ADMIN', 'MANAGER', 'USER')
                         `role` ENUM('ADMIN', 'MANAGER', 'USER') DEFAULT 'USER',
                         `enabled` BOOLEAN NOT NULL DEFAULT TRUE,

    -- SocialType enum and social_id
                         `social_type` VARCHAR(255), -- Changed from TINYINT to VARCHAR based on @Enumerated(EnumType.STRING)
                         `social_id` VARCHAR(255),

    -- Added profile info
                         `birth_date` DATE NULL, -- Changed based on LocalDate
                         `birth_time` TIME NULL, -- 태어난 시각
                         `birth_place` VARCHAR(255) NULL, -- 태어난 장소
                         `gender` ENUM('MALE', 'FEMALE') NULL, -- Changed based on Gender enum and @Enumerated(EnumType.STRING)

    -- Added privacy policy agreement
                         `privacy_policy_agreed` BOOLEAN NOT NULL DEFAULT FALSE, -- Changed based on the new field

                         `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                         PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 2. 만세력 기준 데이터 테이블 (manses)
CREATE TABLE `manses` (
                          `id` BIGINT NOT NULL AUTO_INCREMENT,

                          `solar_date` DATE NOT NULL UNIQUE,
                          `lunar_date` DATE NOT NULL,

                          `season` VARCHAR(10) DEFAULT NULL, -- 절기 데이터

                          `season_start_time` DATETIME NULL DEFAULT NULL, -- 절입시간(절기가 시작하는 시작 시간)

                          `leap_month` BOOLEAN DEFAULT NULL,-- 윤달

                          `year_sky` VARCHAR(10) DEFAULT NULL,
                          `year_ground` VARCHAR(10) DEFAULT NULL,
                          `month_sky` VARCHAR(10) DEFAULT NULL,
                          `month_ground` VARCHAR(10) DEFAULT NULL,
                          `day_sky` VARCHAR(10) DEFAULT NULL,
                          `day_ground` VARCHAR(10) DEFAULT NULL,

                          `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                          PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=73443 DEFAULT CHARSET=utf8mb4;


-- 3. 사용자 개인 정보 테이블 (personal_info)
CREATE TABLE `personal_info` (
                                 `id` BIGINT NOT NULL AUTO_INCREMENT,
                                 `user_id` BIGINT, -- users 테이블을 참조할 외래 키

                                 `name` VARCHAR(255),
                                 `birth_date` VARCHAR(255),
                                 `birth_time` VARCHAR(255),

    -- CalendarType enum (TINYINT로 매핑 추정)
                                 `calendar_type` TINYINT,

    -- Gender enum ('FEMALE','MALE')
                                 `gender` ENUM('FEMALE', 'MALE'),

                                 `is_time_unknown` BOOLEAN NOT NULL DEFAULT FALSE,
                                 `midnight_adjust` BOOLEAN NOT NULL DEFAULT FALSE,

                                 `location_id` VARCHAR(255),
                                 `city` VARCHAR(255),

                                 `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                                 PRIMARY KEY (`id`),
    -- user_id를 users 테이블의 id에 연결
                                 FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 4. 리프레시 토큰 테이블 (refresh_tokens)
CREATE TABLE `refresh_tokens` (
                                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                                  `token` VARCHAR(500) NOT NULL UNIQUE,
                                  `user_id` BIGINT NOT NULL,
                                  `expires_at` TIMESTAMP NOT NULL,
                                  `revoked` BOOLEAN NOT NULL DEFAULT FALSE,

                                  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  `used_at` TIMESTAMP NULL DEFAULT NULL,

                                  PRIMARY KEY (`id`),
    -- user_id를 users 테이블의 id에 연결 (계정 삭제 시 토큰도 삭제)
                                  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 개인 사주 해석 결과 저장 테이블
CREATE TABLE results
(
    -- 기본 키
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- 사용자 정보 (Nullable, users 테이블과 관계를 맺을 수 있음)
    user_id      BIGINT,

    -- 사주 분석 입력 정보
    name         VARCHAR(100) NOT NULL,
    solar_date   DATE         NOT NULL,
    solar_time   TIME         NOT NULL,
    gender       VARCHAR(10)  NOT NULL, -- "MALE", "FEMALE" 등
    is_lunar     BOOLEAN      NOT NULL,

    -- 핵심 결과 정보
    ilgan        VARCHAR(10)  NOT NULL, -- 예: "임수"
    interpretation TEXT       NOT NULL, -- GPT가 생성한 긴 해석 내용

    -- 메타데이터
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6),

    -- 검색 성능 향상을 위한 인덱스
    INDEX        idx_results_user_id (user_id),
    INDEX        idx_results_saju (name, solar_date, solar_time) -- 이름과 생년월일시로 조회하는 경우
);

-- 궁합 분석 결과 저장 테이블
CREATE TABLE compatibility_results
(
    -- 기본 키
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- 사용자 정보 (Nullable)
    user_id              BIGINT,

    -- 궁합 분석 대상자 정보
    person1_name         VARCHAR(100),
    person1_ilgan        VARCHAR(10),
    person2_name         VARCHAR(100),
    person2_ilgan        VARCHAR(10),

    -- 궁합 분석 결과
    compatibility_score  INT, -- 궁합 점수 (0-100)
    interpretation       TEXT NOT NULL, -- GPT가 생성한 긴 궁합 분석 내용

    -- 메타데이터
    created_at           DATETIME(6) NOT NULL,

    -- 인덱스
    INDEX idx_compatibility_results_user_id (user_id)
);

-- 아래 두 표(payments, orders)는 운영 SHOW CREATE TABLE 결과가 아니다. 원래 있던 정의에 엔티티가 매핑하는데 빠져 있던 컬럼을
-- 채우고, 운영에 손으로 적용하는 DDL(UNIQUE·인덱스, payments.user_id 의 NULL 허용)을 더한 것이다. 이름 붙인 UNIQUE·인덱스의 이름과
-- 컬럼 순서는 엔티티 @Table 선언과 같고, 컬럼과 이름이 엔티티와 맞는지는 PaymentSchemaSqlTest 가 본다.
-- payments 의 idx_order_id 와 imp_uid UNIQUE 는 원래 정의에 있던 것으로 엔티티 @Table 선언 밖이다. 그래서 엔티티로 만드는 로컬·테스트
-- DB 에는 idx_order_id 가 없고, imp_uid UNIQUE 는 Payment.impUid 의 @Column(unique = true) 로 Hibernate 가 지은 이름(UK 로 시작)으로
-- 생긴다. 운영에 같은 컬럼·같은 순서의 인덱스가 다른 이름으로 이미 있으면 새로 만들지 않고 그 인덱스를 쓴다.

-- payments 테이블
CREATE TABLE payments (
                          id BIGINT AUTO_INCREMENT PRIMARY KEY,
                          imp_uid VARCHAR(255) NOT NULL UNIQUE,
                          merchant_uid VARCHAR(255) NOT NULL,
                          order_id BIGINT NOT NULL,
    -- 탈퇴하면 결제 이력은 남기고 사용자 연결만 끊으려고 NULL 로 바꾼다(PaymentRepository.detachUser). 그래서 NULL 을 허용한다.
                          user_id BIGINT NULL,
                          sub_category_id BIGINT,
                          amount BIGINT NOT NULL,
                          status VARCHAR(255),
                          created_at DATETIME(6),
    -- 원래 정의에 있던 인덱스다. 지금 order_id 로 찾는 조회는 없다.
                          INDEX idx_order_id (order_id),
    -- 대사의 하루치 결제 조회(created_at 범위)
                          INDEX idx_payments_created_at (created_at),
    -- 대사의 CANCEL_REQUESTED 결제 조회
                          INDEX idx_payments_status (status),
    -- 내 결제 목록(user_id 로 거르고 created_at 내림차순)과 탈퇴의 사용자 연결 끊기. 예전 idx_user_id (user_id) 를 대신한다.
                          INDEX idx_payments_user_id_created_at (user_id, created_at)
);

-- orders 테이블
CREATE TABLE `orders` (
                          `id` BIGINT NOT NULL AUTO_INCREMENT,
                          `merchant_uid` VARCHAR(255) NOT NULL,
                          `payment_id` VARCHAR(255),
    -- 결제 확정 때 붙인 payments.id. 결제가 붙기 전에는 NULL 이다.
                          `payment_pk_id` BIGINT,
                          `user_id` BIGINT,
                          `sub_category_id` BIGINT NOT NULL,
                          `buyer_name` VARCHAR(255),
                          `buyer_email` VARCHAR(255),
                          `amount` INT NOT NULL,
    -- OrderStatus 의 값을 모두 적는다. 원래 있던 네 값 뒤에 나중에 생긴 두 값을 붙인다(ENUM 끝에 값을 붙이는 변경은 표를 다시 만들지
    -- 않는다). ENUM 을 VARCHAR 로 바꾸는 일은 따로 다룬다.
                          `status` ENUM('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'VIRTUAL_ACCOUNT_ISSUED', 'EXPIRED') NOT NULL DEFAULT 'PENDING',
                          `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          `paid_at` TIMESTAMP NULL DEFAULT NULL,
                          `original_amount` INT,
                          `applied_discount_code` VARCHAR(255),
                          `coupon_id` BIGINT,

                          PRIMARY KEY (`id`),
    -- 결제 확정·웹훅·환불이 merchant_uid 로 주문 행을 잠근다. 인덱스가 없으면 훑은 모든 행과 틈이 잠긴다.
    -- 같은 컬럼에 두던 일반 인덱스 idx_orders_merchant_uid 는 이 UNIQUE 와 겹쳐 쓰기 비용만 늘리므로 두지 않는다.
                          UNIQUE KEY `uk_orders_merchant_uid` (`merchant_uid`),
    -- 결제 한 건은 주문 한 건에만 붙는다. 결제 PK 로 주문 찾기도 이 인덱스를 쓴다. NULL 은 여러 개 들어갈 수 있다.
                          UNIQUE KEY `uk_orders_payment_pk_id` (`payment_pk_id`),
    -- 30분 만료 스캔(status 같다 조건, created_at 범위 조건)
                          INDEX `idx_orders_status_created_at` (`status`, `created_at`),
    -- 할인 복구가 같은 쿠폰을 쥔 다른 주문을 찾는다
                          INDEX `idx_orders_coupon_id` (`coupon_id`),
                          INDEX `idx_orders_user_id` (`user_id`),
                          FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE SET NULL
);

-- subcategories 테이블
CREATE TABLE `subcategories` (
                                 `id` BIGINT NOT NULL AUTO_INCREMENT,
                                 `title` VARCHAR(255) NOT NULL,
                                 `description` TEXT,
                                 `icon` VARCHAR(255),
                                 `price` INT NOT NULL,
                                 `category_id` BIGINT,
                                 `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                 PRIMARY KEY (`id`)
);

-- 아래 세 표(coupon_templates, coupons, discount_codes)는 운영 SHOW CREATE TABLE 결과가 아니다. 로컬 MySQL 8.0 에서 ddl-auto 로
-- 만든 표를 엔티티 필드 순서로 옮겨 적고, 운영에 손으로 적용하는 DDL(uk_coupons_user_template, current_issue_count 의 NOT NULL
-- DEFAULT 0)을 더한 것이다. discount_codes.code 의 UNIQUE 이름은 운영마다 다를 수 있어 컬럼 끝의 UNIQUE 로만 적는다.
-- 컬럼과 제약 이름이 엔티티와 맞는지는 PaymentSchemaSqlTest 가 본다.

-- coupon_templates 테이블 (선착순 쿠폰 이벤트)
CREATE TABLE `coupon_templates` (
                                    `id` BIGINT NOT NULL AUTO_INCREMENT,
                                    `name` VARCHAR(255),
                                    `description` VARCHAR(255),
                                    `discount_type` ENUM('FIXED_AMOUNT', 'PERCENTAGE'),
                                    `discount_value` INT NOT NULL,
                                    `min_purchase_amount` INT NOT NULL,
                                    `issue_start_date` DATETIME(6),
                                    `issue_end_date` DATETIME(6),
                                    `valid_days_after_issue` INT,
                                    `valid_until` DATETIME(6),
    -- 선착순 상한. NULL 이면 무제한이다.
                                    `max_issue_count` INT,
    -- 지금까지 발급한 수. 템플릿을 SQL 로 넣을 때 비워 두면 0 이 된다. NULL 이면 이벤트 목록 전체가 실패하므로 NULL 을 허용하지 않는다.
                                    `current_issue_count` INT NOT NULL DEFAULT 0,
                                    `max_count_per_user` INT NOT NULL,

                                    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- coupons 테이블 (사용자에게 발급한 쿠폰)
CREATE TABLE `coupons` (
                           `id` BIGINT NOT NULL AUTO_INCREMENT,
                           `user_id` BIGINT NOT NULL,
                           `name` VARCHAR(255),
                           `discount_type` ENUM('FIXED_AMOUNT', 'PERCENTAGE'),
                           `discount_value` INT NOT NULL,
                           `min_purchase_amount` INT NOT NULL,
                           `expires_at` DATETIME(6),
                           `is_used` BIT(1) NOT NULL,
                           `used_at` DATETIME(6),
                           `template_id` BIGINT,

                           PRIMARY KEY (`id`),
    -- 한 사용자는 한 템플릿의 쿠폰을 한 장만 갖는다. 템플릿 행 잠금을 거치지 않은 발급도 DB 가 마지막으로 막는다.
    -- user_id 를 앞에 두어 user_id 로만 거르는 내 쿠폰함 조회도 이 인덱스를 쓴다.
                           UNIQUE KEY `uk_coupons_user_template` (`user_id`, `template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- discount_codes 테이블 (할인 코드)
CREATE TABLE `discount_codes` (
                                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                                  `code` VARCHAR(255) NOT NULL UNIQUE,
                                  `discount_type` ENUM('FIXED_AMOUNT', 'PERCENTAGE'),
                                  `discount_value` INT NOT NULL,
                                  `expires_at` DATETIME(6),
                                  `max_uses` INT NOT NULL,
                                  `current_uses` INT NOT NULL,
                                  `min_purchase_amount` INT NOT NULL,
                                  `is_active` BIT(1) NOT NULL,
    -- 특정 상품·카테고리 전용 코드. NULL 이면 제한 없음.
                                  `sub_category_id` BIGINT,
                                  `category_id` BIGINT,

                                  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 인덱스 생성 (검색 성능 최적화)
CREATE INDEX idx_manses_solar_date ON manses(solar_date);
CREATE INDEX idx_manses_lunar_date ON manses(lunar_date);
-- solar_date와 leap_month의 조합은 사용자의 요청대로 유지
CREATE INDEX idx_solar_leap ON manses(solar_date, leap_month);

-- 외래 키가 있는 테이블에는 인덱스 생성
CREATE INDEX idx_personal_info_user_id ON personal_info(user_id);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- payment_reconciliation_runs 테이블 (일 배치 결제 대사 실행 이력)
CREATE TABLE payment_reconciliation_runs
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- 대사 대상 영업일(KST)과 실제로 조회한 창
    target_date       DATE        NOT NULL,
    window_from       DATETIME(6) NOT NULL,
    window_until      DATETIME(6) NOT NULL,

    -- ReconciliationStatus enum ('RUNNING', 'COMPLETED', 'FAILED')
    status            VARCHAR(255) NOT NULL,

    -- 대조한 건수
    pg_payment_count  INT         NOT NULL DEFAULT 0,
    db_payment_count  INT         NOT NULL DEFAULT 0,
    mismatch_count    INT         NOT NULL DEFAULT 0,

    started_at        DATETIME(6) NOT NULL,
    finished_at       DATETIME(6) NULL,
    error_message     VARCHAR(1000) NULL,

    -- 인덱스
    INDEX idx_payment_reconciliation_runs_target_date (target_date)
);

-- payment_reconciliation_mismatches 테이블 (대사에서 찾은 불일치)
CREATE TABLE payment_reconciliation_mismatches
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id        BIGINT       NOT NULL,

    -- MismatchType enum ('MISSING_IN_DB', 'MISSING_IN_PG', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH', 'CANCEL_REQUESTED_STALE', 'PG_LOOKUP_FAILED', 'PG_ID_MISMATCH')
    type          VARCHAR(255) NOT NULL,

    imp_uid       VARCHAR(255) NOT NULL,
    merchant_uid  VARCHAR(255) NULL,

    -- PG 쪽 값 (DB 에만 있는 건은 비어 있다)
    pg_status     VARCHAR(255) NULL,
    pg_amount     BIGINT       NULL,

    -- DB 쪽 값 (PG 에만 있는 건은 비어 있다)
    db_status     VARCHAR(255) NULL,
    db_amount     BIGINT       NULL,

    detail        VARCHAR(500) NULL,
    detected_at   DATETIME(6)  NOT NULL,

    -- 인덱스
    INDEX idx_payment_reconciliation_mismatches_run_id (run_id),
    INDEX idx_payment_reconciliation_mismatches_imp_uid (imp_uid)
);

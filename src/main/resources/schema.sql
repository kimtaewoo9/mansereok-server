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
-- 아래 두 표는 엔티티(Result, CompatibilityResult) 매핑에 [해석 4] 운영 DDL(payment_id UNIQUE 이름 고정, (status, updated_at) 인덱스,
-- 궁합 updated_at, 본문 MEDIUMTEXT, idx_results_saju 삭제)을 적용한 모습을 SHOW CREATE TABLE 형식으로 적었다.
-- idx_*_user_id 는 엔티티에 선언이 없고 이전 schema.sql 에서 이어 왔다. 운영 표를 바꾸면 운영 SHOW CREATE TABLE 결과로 통째로 바꾼다.
CREATE TABLE `results` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `payment_id` bigint DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `solar_date` date DEFAULT NULL,
  `solar_time` time(6) DEFAULT NULL,
  `gender` varchar(255) DEFAULT NULL,
  `is_lunar` bit(1) DEFAULT NULL,
  `ilgan` varchar(255) DEFAULT NULL,
  `interpretation` mediumtext,
  `summary` text,
  `og_image_url` varchar(512) DEFAULT NULL,
  `product_name` varchar(255) DEFAULT NULL,
  `status` enum('COMPLETED','INPUT_REQUIRED','PROCESSING') DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_results_payment_id` (`payment_id`),
  KEY `idx_results_user_id` (`user_id`),
  KEY `idx_results_status_updated_at` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 궁합 분석 결과 저장 테이블
CREATE TABLE `compatibility_results` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `payment_id` bigint DEFAULT NULL,
  `person1_name` varchar(255) DEFAULT NULL,
  `person1_ilgan` varchar(255) DEFAULT NULL,
  `person2_name` varchar(255) DEFAULT NULL,
  `person2_ilgan` varchar(255) DEFAULT NULL,
  `compatibility_score` int DEFAULT NULL,
  `interpretation` mediumtext,
  `summary` text,
  `og_image_url` varchar(512) DEFAULT NULL,
  `product_name` varchar(255) DEFAULT NULL,
  `status` enum('COMPLETED','INPUT_REQUIRED','PROCESSING') DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_compatibility_results_payment_id` (`payment_id`),
  KEY `idx_compatibility_results_user_id` (`user_id`),
  KEY `idx_compatibility_results_status_updated_at` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- payments 테이블
CREATE TABLE payments (
                          id BIGINT AUTO_INCREMENT PRIMARY KEY,
                          imp_uid VARCHAR(255) NOT NULL UNIQUE,
                          merchant_uid VARCHAR(255) NOT NULL,
                          order_id BIGINT NOT NULL,
                          user_id BIGINT NOT NULL,
                          amount BIGINT NOT NULL,
                          status VARCHAR(255),
                          created_at DATETIME(6),
                          INDEX idx_order_id (order_id),
                          INDEX idx_user_id (user_id)
);

-- orders 테이블
CREATE TABLE `orders` (
                          `id` BIGINT NOT NULL AUTO_INCREMENT,
                          `merchant_uid` VARCHAR(255) NOT NULL UNIQUE,
                          `payment_id` VARCHAR(255),
                          `user_id` BIGINT,
                          `sub_category_id` BIGINT NOT NULL,
                          `amount` INT NOT NULL,
                          `status` ENUM('PENDING', 'PAID', 'FAILED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
                          `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          `paid_at` TIMESTAMP NULL DEFAULT NULL,

                          PRIMARY KEY (`id`),
                          INDEX `idx_orders_merchant_uid` (`merchant_uid`),
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


-- 인덱스 생성 (검색 성능 최적화)
CREATE INDEX idx_manses_solar_date ON manses(solar_date);
CREATE INDEX idx_manses_lunar_date ON manses(lunar_date);
-- solar_date와 leap_month의 조합은 사용자의 요청대로 유지
CREATE INDEX idx_solar_leap ON manses(solar_date, leap_month);

-- 외래 키가 있는 테이블에는 인덱스 생성
CREATE INDEX idx_personal_info_user_id ON personal_info(user_id);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

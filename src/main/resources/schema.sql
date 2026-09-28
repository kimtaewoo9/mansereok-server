-- 테이블 전체 삭제 (개발 초기 안전을 위해 사용)
DROP TABLE IF EXISTS `refresh_tokens`;
DROP TABLE IF EXISTS `personal_info`;
DROP TABLE IF EXISTS `manses`;
DROP TABLE IF EXISTS `users`;

-- 1. 사용자 정보 테이블 (users)
-- users·refresh_tokens·password_reset_tokens 는 엔티티 선언(@Table)과 같은 컬럼, UNIQUE·인덱스 이름으로 적는다(AuthSchemaSqlTest 가
-- 확인한다). 운영에는 이 파일을 돌리지 않고, 바뀐 부분만 DDL 로 손으로 적용해 운영도 같은 이름을 쓰게 한다.
CREATE TABLE `users` (
                         `id` BIGINT NOT NULL AUTO_INCREMENT,
                         `username` VARCHAR(255),
                         `email` VARCHAR(255),
                         `password` VARCHAR(255),
                         `name` VARCHAR(255),

    -- Role enum ('ADMIN', 'MANAGER', 'SUPER_ADMIN', 'USER')
                         `role` ENUM('ADMIN', 'MANAGER', 'SUPER_ADMIN', 'USER') DEFAULT 'USER',
                         `enabled` BOOLEAN NOT NULL DEFAULT TRUE,

    -- SocialType enum and social_id
                         `social_type` VARCHAR(255), -- Changed from TINYINT to VARCHAR based on @Enumerated(EnumType.STRING)
                         `social_id` VARCHAR(255),

    -- 마케팅 수신 동의
                         `marketing_agreed` BOOLEAN NOT NULL DEFAULT FALSE,

    -- Added profile info
                         `birth_date` DATE NULL, -- Changed based on LocalDate
                         `birth_time` TIME NULL, -- 태어난 시각
                         `birth_place` VARCHAR(255) NULL, -- 태어난 장소
                         `gender` ENUM('MALE', 'FEMALE') NULL, -- Changed based on Gender enum and @Enumerated(EnumType.STRING)

    -- Added privacy policy agreement
                         `privacy_policy_agreed` BOOLEAN NOT NULL DEFAULT FALSE, -- Changed based on the new field

                         `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                         PRIMARY KEY (`id`),
    -- 같은 이메일·username·소셜 계정의 두 번째 가입을 DB 가 마지막으로 막는다. NULL 은 여러 행이어도 걸리지 않는다.
                         UNIQUE KEY `uk_users_email` (`email`),
                         UNIQUE KEY `uk_users_username` (`username`),
    -- social_id 를 앞에 두어 social_id 만으로 찾는 조회도 이 인덱스를 쓴다.
                         UNIQUE KEY `uk_users_social_id_type` (`social_id`, `social_type`)
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
    -- 만료된 토큰과 이미 쓴 토큰을 정리할 때 표 전체를 훑지 않게 한다. 아직 쓰는 코드는 없고, 정리 작업이 들어올 때 쓰려고
    -- 미리 건다(RefreshToken 의 @Table 주석 참고).
                                  INDEX `idx_refresh_tokens_expires_at` (`expires_at`),
                                  INDEX `idx_refresh_tokens_used_at` (`used_at`),
    -- user_id를 users 테이블의 id에 연결 (계정 삭제 시 토큰도 삭제)
                                  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 비밀번호 재설정 토큰 테이블 (password_reset_tokens)
CREATE TABLE `password_reset_tokens` (
                                         `id` BIGINT NOT NULL AUTO_INCREMENT,
                                         `token` VARCHAR(255) NOT NULL,
                                         `user_id` BIGINT NOT NULL,
                                         `expiry_date` DATETIME(6) NOT NULL,

                                         PRIMARY KEY (`id`),
                                         UNIQUE KEY `uk_password_reset_tokens_token` (`token`),
    -- 사용자 한 명에 토큰 하나. 이 UNIQUE 가 user_id 외래 키의 인덱스를 겸한다.
                                         UNIQUE KEY `uk_password_reset_tokens_user_id` (`user_id`),
                                         FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
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

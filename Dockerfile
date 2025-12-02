# 멀티 스테이지 빌드로 최적화
FROM --platform=linux/arm64 gradle:8.5-jdk21 AS build
WORKDIR /app

# Gradle 캐시 레이어 분리
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

# 소스 코드 복사 및 빌드
COPY . .
RUN gradle clean bootJar -x test --no-daemon

# --------------------------------------------------------
# 런타임 이미지 - Java 21 사용
FROM --platform=linux/arm64 amazoncorretto:21-alpine
WORKDIR /app

# 타임존 및 폰트 설치
RUN apk add --no-cache tzdata fontconfig ttf-dejavu && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone

ENV TZ=Asia/Seoul

# 비root 사용자 생성 (보안 강화)
RUN addgroup -g 1000 spring && \
    adduser -D -u 1000 -G spring spring

# 로그 디렉토리 생성
RUN mkdir -p /app/logs && chown -R spring:spring /app/logs

# [Scouter 추가] 1. scouter 폴더 복사 및 권한 부여 (중요!)
# 프로젝트 루트의 ./scouter 폴더를 컨테이너의 /app/scouter로 복사합니다.
# --chown 옵션으로 spring 유저가 읽을 수 있게 합니다.
COPY --chown=spring:spring ./scouter /app/scouter

# JAR 파일 복사
COPY --from=build /app/build/libs/*.jar app.jar

# 소유권 변경
RUN chown spring:spring app.jar

# 비root 사용자로 전환
USER spring

# 애플리케이션 포트
EXPOSE 8080

# 헬스체크
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-javaagent:/app/scouter/agent.java/scouter.agent.jar", \
    "-Dscouter.config=/app/scouter/agent.java/conf/scouter.conf", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", \
    "-XX:+UseContainerSupport", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]

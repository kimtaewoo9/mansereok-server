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

# 런타임 이미지 - Java 21 사용
FROM --platform=linux/arm64 amazoncorretto:21-alpine
WORKDIR /app

# 타임존 및 폰트 설치 (이 부분이 수정되었습니다)
RUN apk add --no-cache tzdata fontconfig ttf-dejavu && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone

ENV TZ=Asia/Seoul

# 비root 사용자 생성 (보안 강화)
RUN addgroup -g 1000 spring && \
    adduser -D -u 1000 -G spring spring

# 로그 디렉토리 생성
RUN mkdir -p /app/logs && chown -R spring:spring /app/logs

# JAR 파일 복사
COPY --from=build /app/build/libs/*.jar app.jar

# 소유권 변경
RUN chown spring:spring app.jar

# 비root 사용자로 전환
USER spring

# 애플리케이션 포트
EXPOSE 8080

# 헬스체크 - wget 대신 curl 사용 (alpine에서 더 안정적)
# (alpine-corretto에는 wget이 기본 포함되어 있을 수 있으나, curl이 권장됩니다)
# RUN apk add --no-cache curl (만약 curl이 없다면 이 줄 추가)
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 실행
ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", \
    "-XX:+UseContainerSupport", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]

# ⑤ 치지직 서버 컨테이너 이미지. 먼저 실행 가능한 JAR를 만든 뒤 빌드한다.
#   .\gradlew.bat bootJar
#   docker build -t chzzk-app:local .
# 같은 이미지를 앱 인스턴스와 H2 TCP 서버(공용 DB) 컨테이너가 함께 쓴다(deploy/compose.yaml).
FROM eclipse-temurin:25-jre

WORKDIR /app
RUN groupadd --system chzzk \
    && useradd --system --gid chzzk --home-dir /app chzzk \
    && mkdir -p /data/uploads /h2data \
    && chown -R chzzk:chzzk /app /data /h2data

ARG JAR_FILE=build/libs/chzzk-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} /app/app.jar

USER chzzk
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=cluster
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

# 基于 Eclipse Temurin 21 JRE Alpine
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 复制 jar(JDK 21 + Spring Boot 3.5.16)
COPY target/campus-food-recommend.jar app.jar

# 容器默认使用 dev profile;真实 LLM 调用需 SPRING_PROFILES_ACTIVE=bench
ENV SPRING_PROFILES_ACTIVE=dev
ENV JAVA_OPTS="-XX:+UseG1GC -Xmx512m"

EXPOSE 8080

# 健康检查(Spring Boot Actuator)
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -q -O - http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
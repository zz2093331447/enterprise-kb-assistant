# ---- 构建阶段：必须用能跑 Maven 的镜像 ----
# 注意：eclipse-temurin 的纯 JDK 镜像里没有 Maven，
# 原写法（用 17-jdk + 调 mvn/mvnw）在没有 Maven 包装器时会编译失败。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# 打包可执行 jar（跳过测试，CI/镜像构建阶段不连 Ollama）
RUN mvn -B -DskipTests package

# ---- 运行阶段：只要 JRE，镜像更小 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/enterprise-kb-assistant-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

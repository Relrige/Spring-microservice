FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=builder /workspace/extracted/dependencies/ ./
COPY --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/extracted/application/ ./

EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:InitialRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
# .mvn/jvm.config : ouvre les API du compilateur à Error Prone et à google-java-format
COPY pom.xml .
COPY .mvn .mvn
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests=false

FROM eclipse-temurin:25-jre-alpine
# UID/GID numériques : vérifiables par l'hôte et par les politiques runAsNonRoot
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app \
 && mkdir -p /app/data && chown -R app:app /app
USER 10001:10001
WORKDIR /app
COPY --from=build /src/target/conso-collector-*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -Duser.timezone=Europe/Paris"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

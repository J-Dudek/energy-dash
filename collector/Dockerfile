FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests=false

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
 && mkdir -p /app/data && chown -R app:app /app
USER app
WORKDIR /app
COPY --from=build /src/target/conso-collector-*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -Duser.timezone=Europe/Paris"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

FROM openjdk:21-jdk-slim as builder
WORKDIR /src
COPY . .
RUN ./gradlew build
RUN ls -a
RUN ls app/build/libs

FROM openjdk:21-jdk-slim
WORKDIR /app
COPY --from=builder /src/app/build/libs/*.jar /app/
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]

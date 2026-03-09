FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY . .

# gradlew 실행 권한 부여
RUN chmod +x gradlew

RUN ./gradlew clean build -x test

FROM eclipse-temurin:21-jdk
WORKDIR /app

COPY --from=build /app/build/libs/*.jar rami-art-studio-api.jar

ENTRYPOINT ["java", "-jar", "rami-art-studio-api.jar"]
# Estágio 1: compila o projeto
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY LP/pom.xml .
COPY LP/src ./src
RUN mvn -q clean package -DskipTests

# Escolhe o jar final (ignora o original-*.jar que o shade plugin gera)
RUN cp $(ls target/*.jar | grep -v '^target/original-' | head -n 1) /app/app.jar

# Estágio 2: imagem final, só com o jar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
CMD ["java", "-jar", "app.jar"]
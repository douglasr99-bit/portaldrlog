# ============================================================================
# PORTAL DRLOG — imagem única (páginas Thymeleaf + API)
#
# Multi-stage, compilando o jar dentro da imagem. O Styllus aprendeu isso da
# forma cara: um COPY target/*.jar deixou rodando em produção um jar com a
# chave e a instância erradas, dessincronizado do código-fonte. Compilar aqui
# elimina a categoria inteira desse erro.
# ============================================================================

# --- Etapa 1: compilação ---
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependências primeiro, para aproveitar o cache de camadas do Docker
COPY backend/pom.xml .
RUN mvn -B dependency:go-offline

COPY backend/src ./src
RUN mvn -B clean package -DskipTests

# --- Etapa 2: runtime ---
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /build/target/portal-1.0.0.jar app.jar

# Porta interna: o Traefik do Coolify alcança o container pela rede Docker.
# 8082 para não colidir com a 8081 do Styllus.
EXPOSE 8082

# Percentual, e não -Xmx fixo: o valor acompanha o limite do container e
# sobrevive a um upgrade de VPS sem precisar ser reajustado. Sem este limite,
# a JVM assume que pode usar a máquina inteira — e com Portal e Styllus no
# mesmo servidor, a primeira a crescer sufoca a outra.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]

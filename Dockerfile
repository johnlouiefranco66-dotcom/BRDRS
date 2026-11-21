FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY . .
RUN javac -cp ".:*" src/BarangayServer.java
EXPOSE 8080
CMD ["java", "-cp", ".:src:*", "BarangayServer"]

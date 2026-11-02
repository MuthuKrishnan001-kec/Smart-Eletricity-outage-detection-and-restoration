FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY *.java ./
RUN mkdir -p /app/out /data \
    && javac --release 21 -encoding UTF-8 -d /app/out *.java

ENV PORT=8081
ENV DATA_DIR=/data
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"

EXPOSE 8081

CMD ["java", "-cp", "/app/out", "MainDashboard", "--headless"]

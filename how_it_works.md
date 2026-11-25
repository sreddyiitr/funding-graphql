# How it works: Dockerfile and docker-compose.yml

These two files split the work: the **Dockerfile** says how to *build* an image of the app, and **docker-compose.yml** says how to *run* that image as a container.

## Dockerfile: how to build the image

It's a **multi-stage build**: one temporary image compiles the code, and a second, smaller image runs it.

```dockerfile
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
```
This starts from an image that already has Maven and JDK 21 installed. It's named `build` so the next stage can copy from it.

```dockerfile
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
```
This copies **only** `pom.xml` and downloads every dependency. Docker caches each step as a layer and reuses it if that step's inputs haven't changed. Because `pom.xml` rarely changes, the slow download step is reused on most rebuilds, and editing Java code doesn't download everything again.

```dockerfile
COPY src ./src
RUN mvn -q -DskipTests package
```
This copies the source and compiles it to `target/funding-graphql-0.1.0.jar`. Tests are skipped because they need Docker themselves (Testcontainers), which isn't available inside a build. Run them on your machine with `mvn test`.

```dockerfile
# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/funding-graphql-*.jar app.jar
```
This starts over from a small image that has only a Java **runtime**: no Maven, no compiler, no source code. It takes just the finished jar from the build stage. Everything else from that stage is thrown away, so the final image is much smaller and has less installed that could be attacked.

```dockerfile
EXPOSE 4000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```
`EXPOSE` only documents that the app listens on port 4000; it doesn't open anything. `ENTRYPOINT` is the command that runs when the container starts.

`.dockerignore` keeps `target/` and `old-node-scaffold/` out of what Docker reads when building, so builds are faster and stale local files can't leak in.

## docker-compose.yml: how to run it

```yaml
services:
  funding-graphql:
    build: .
```
Build the image from the `Dockerfile` in this folder. `docker compose up --build` rebuilds it; without `--build`, Compose reuses the existing image.

```yaml
    container_name: funding-graphql
```
A fixed name, so `docker logs funding-graphql` and similar commands work.

```yaml
    ports:
      - "4000:4000"
```
Maps **your Mac's port 4000 to the container's port 4000**. This is what makes `http://localhost:4000/graphiql` work in your browser.

```yaml
    environment:
      DB_HOST: funder-postgres
      DB_PORT: "5432"
      ...
```
These are environment variables passed into the container. `application.yml` reads them: `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/...`. The `:localhost` part is a fallback, which is why running the jar directly on your Mac uses `localhost`, while in Docker it uses `funder-postgres`.

```yaml
    networks:
      - funding-db
    restart: unless-stopped

networks:
  funding-db:
    external: true
    name: funding-db_default
```
This is the important part. Every Compose project gets its own private network, and the Postgres container was started by the **`funding-db`** project, so it lives on the network `funding-db_default`. `external: true` means "don't create a network; join the existing one."

On a shared Docker network, containers find each other **by container name** through Docker's built-in name lookup. That's why `DB_HOST: funder-postgres` works: the app connects straight to the Postgres container on its internal port 5432. Your Mac's `localhost:5432` isn't involved at all, so a Homebrew Postgres running on the Mac doesn't get in the way.

`restart: unless-stopped` restarts the app automatically if it crashes or Docker restarts, unless you stopped it yourself.

## What happens when you run `docker compose up -d --build`

1. Compose builds the image from the Dockerfile: the Maven stage compiles the jar, and the small runtime stage packages it.
2. It creates the `funding-graphql` container, joins it to `funding-db_default`, sets the `DB_*` variables, and maps port 4000.
3. The container runs `java -jar app.jar`, and Spring Boot starts on port 4000.
4. When the first request needs the database, the app connects to `funder-postgres:5432` over the shared network.
5. `-d` runs it in the background, and `docker compose logs -f` shows the output.

```
Your Mac
 ├─ browser ──localhost:4000──┐
 │                            ▼  (port mapping)
 └─ Docker ─ network "funding-db_default" ──────────────────┐
             funding-graphql:4000  ──"funder-postgres:5432"──▶ funder-postgres
             (from this folder)                               (from ../funding-db)
```

One consequence: this setup **depends on the `funding-db` project starting first**, because its network must exist. If you run `docker compose up` here while Postgres is down, Compose fails with `network funding-db_default declared as external, but could not be found`.



Screenshot 
![alt text](<graphql.png>)

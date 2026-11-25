# funding-graphql

A GraphQL API, written in Java (Spring Boot 3.5 with Spring for GraphQL, Java 21), for the auto-funding Postgres database (`auto_funding_db`).

```
browser / client ──HTTP :4000──▶ funding-graphql (container) ──JDBC──▶ funder-postgres (container)
                                        └──────── Docker network: funding-db_default ────────┘
```

## Prerequisites

- Docker Desktop
- The Postgres container from the sibling `funding-db` project must be running:
  ```bash
  cd ../funding-db && docker compose up -d
  ```
  That starts the `funder-postgres` container on the Docker network `funding-db_default`, which this app joins.
- For local builds and tests only: Java 21 and Maven 3.9 or later.

## Start the app

### Option A: Docker (recommended)

```bash
docker compose up -d --build       # build the image and start it
docker compose logs -f             # follow the logs
curl localhost:4000/actuator/health   # {"status":"UP"}
docker compose down                # stop
```

The database connection is set in `docker-compose.yml` through these environment variables:

| Variable      | Default (in compose) |
|---------------|----------------------|
| `DB_HOST`     | `funder-postgres`    |
| `DB_PORT`     | `5432`               |
| `DB_NAME`     | `auto_funding_db`    |
| `DB_USER`     | `funder_admin`       |
| `DB_PASSWORD` | `funder_dev_password`|

For a walkthrough of what the `Dockerfile` and `docker-compose.yml` do, see [how_it_works.md](how_it_works.md).

### Option B: Run the jar on your Mac

```bash
mvn -DskipTests package
java -jar target/funding-graphql-0.1.0.jar
```

Outside Docker, the app connects to `localhost:5432`. If a Homebrew Postgres is also running, it takes `127.0.0.1:5432` and you'll see `role "funder_admin" does not exist`. Stop it first with `brew services stop postgresql@18`.

## Endpoints

| URL | What |
|-----|------|
| http://localhost:4000/graphiql | GraphiQL in-browser query editor |
| http://localhost:4000/graphql | GraphQL endpoint (POST) |
| http://localhost:4000/graphql/schema | Full schema (SDL) |
| http://localhost:4000/actuator/health | Health check |

The schema source is in `src/main/resources/graphql/schema.graphqls`.

## Example queries

Paste these into GraphiQL. The ids below exist in the `funding-db` seed data.

**Applications waiting on stipulations, with their flagged tasks**
```graphql
{
  applications(status: PENDING_STIPULATIONS, limit: 5) {
    applicationId
    dealNumber
    slaMinutesRemaining
    dealer { legalName }
    tasks(status: DISCREPANCY_FLAGGED) { taskId title }
  }
}
```

`applications` also accepts `dealerId`, `limit` (max 200) and `offset`. Results are sorted newest first by `submittedAt`.

All `applications` filters are optional and combine with AND:

| Argument | Matches |
|----------|---------|
| `status` | One status |
| `statuses` | Any of a list of statuses (intersected with `status` if both are given) |
| `dealerId` | One dealer |
| `search` | Case-insensitive partial match on `applicationId` or `dealNumber` (`%` and `_` are literal) |
| `taskCategory`, `taskStatus` | Applications with at least one task matching both |

**Unfunded applications with a VIN discrepancy**
```graphql
{
  applications(
    statuses: [READY_TO_FUND, PENDING_STIPULATIONS, PENDING_AGENT_VERIFICATION, EXCEPTION_HOLD]
    taskCategory: VIN_VERIFICATION
    taskStatus: DISCREPANCY_FLAGGED
  ) {
    applicationId
    dealNumber
    status
  }
}
```

**One application with everything attached**
```graphql
{
  application(applicationId: "app_9084701") {
    dealNumber
    status
    dealer { legalName dbaName }
    applicant { fullName ficoScore statedMonthlyIncome verifiedMonthlyIncome }
    vehicle { modelYear make model vin condition }
    loanTerms { amountFinanced apr termMonths monthlyPayment ltvPercent }
    tasks { taskId category status verifiedBy }
    riscLineItems { bucket description amount }
    activity(limit: 5) { occurredAt actor action details }
  }
}
```

**A single task, including its JSON evidence**
```graphql
{
  fundingTask(taskId: "req_001_01") {
    title
    status
    sourceComparison
    ocrEvidence
  }
}
```

**Lookups**
```graphql
{
  dealers { dealerId legalName }
  actors  { actorName role }
}
```

## Example mutations

These change data in the database. `actor` must be an existing name from `actors` (for example `"S. Chen"`). The two status mutations also write an `activity_events` row in the same transaction.

**Verify a task.** `verifiedBy` is set when the status is `VERIFIED` or `WAIVED`.
```graphql
mutation {
  updateFundingTaskStatus(input: {
    taskId: "req_040_05", status: VERIFIED, actor: "S. Chen", details: "Docs reviewed"
  }) {
    taskId status verifiedBy updatedAt
  }
}
```

**Change an application's status.** Setting it to `FUNDED` also sets `fundedAt`.
```graphql
mutation {
  updateApplicationStatus(input: {
    applicationId: "app_9084701", status: FUNDED, actor: "S. Chen"
  }) {
    status fundedAt
    activity(limit: 1) { action details metadata }
  }
}
```

**Add a note to the activity log.** `metadata` is any JSON object.
```graphql
mutation {
  addActivityEvent(input: {
    applicationId: "app_9084701", actor: "S. Chen", action: "NOTE",
    details: "Called dealer about title", metadata: { channel: "phone", attempts: 1 }
  }) {
    eventId occurredAt role
  }
}
```

**Errors.** An unknown id or actor comes back as a `BAD_REQUEST` GraphQL error, and nothing is written:
```json
{ "errors": [{ "message": "Unknown actor: Ghost", "extensions": { "classification": "BAD_REQUEST" } }] }
```

**Reset the data.** To undo your experiments, reload the seed data (`02-data.sql` truncates the tables first):
```bash
docker exec -i funder-postgres psql -U funder_admin -d auto_funding_db < ../funding-db/init/02-data.sql
```

## Calling it from the command line

```bash
curl -s localhost:4000/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ applications(status: READY_TO_FUND, limit: 3) { applicationId dealNumber } }"}'
```

With variables:
```bash
curl -s localhost:4000/graphql \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "query($id: String!) { application(applicationId: $id) { status dealer { legalName } } }",
    "variables": { "id": "app_9084701" }
  }'
```

## Tests

```bash
mvn test
```

These are end-to-end tests. They send real HTTP requests to the app, which runs against a throwaway Postgres 18 container started by Testcontainers. Docker must be running. They never touch `funder-postgres`.

- Test schema: `src/test/resources/db/01-schema.sql`, a copy of `../funding-db/init/01-schema.sql`. Copy it again whenever that schema changes.
- Test data: `src/test/resources/db/02-seed.sql`, reloaded before every test.

## Project layout

```
src/main/java/com/funding/graphql/
  FundingGraphqlApplication.java   entry point, custom scalars (DateTime, Date, BigDecimal, JSON)
  FundingController.java           query/mutation handlers, batched nested fields
  FundingRepository.java           SQL (Spring JdbcClient)
  Models.java                      records for tables and mutation inputs
  GraphqlErrors.java               maps validation errors to BAD_REQUEST
src/main/resources/
  application.yml                  port 4000, datasource from DB_* env vars
  graphql/schema.graphqls          GraphQL schema
src/test/...                       E2E tests and fixtures
Dockerfile, docker-compose.yml     container build and run
old-node-scaffold/                 previous Node/Apollo files (unused)
```

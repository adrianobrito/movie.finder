# Movie Finder

Movie Finder is a movie recommendation and search service. The MVP is implemented entirely in **Java**, including ingestion, search, ranking, and API code.

## Technology

- Java 21
- Spring Boot 4.1
- Gradle 8.14 (via the included wrapper)
- JUnit 5

## Build and test

Install a Java 21 JDK and run the complete build with one command:

```shell
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

## Run locally

The `local` profile is selected by default:

```shell
./gradlew bootRun
```

To select another environment, set `SPRING_PROFILES_ACTIVE` to `local`, `test`, or `prod` before starting the application. Configuration shared by every environment is in `application.yml`; environment-specific overrides are in the corresponding `application-<profile>.yml` file.

Once the application is running, its operational endpoints are:

| Purpose | Endpoint |
| --- | --- |
| Health | `GET http://localhost:8080/actuator/health` |
| Liveness | `GET http://localhost:8080/actuator/health/liveness` |
| Readiness | `GET http://localhost:8080/actuator/health/readiness` |
| Liveness alias | `GET http://localhost:8080/livez` |
| Readiness alias | `GET http://localhost:8080/readyz` |

A successful health response has HTTP status `200` and includes `{"status":"UP"}`.

## Package structure

Production code lives under `com.moviefinder`:

- `api`: HTTP endpoints and consistent API error responses
- `domain`: core movie and person concepts
- `ingestion`: dataset import and normalization
- `search`: search indexing and retrieval
- `ranking`: deterministic result ranking
- `config`: application configuration and lifecycle logging

The packages intentionally contain only their bootstrap boundaries for now. Features should keep domain logic independent and have the API, ingestion, search, and ranking layers depend on it.

## Operational defaults

Spring Boot Actuator supplies health and availability probes. Only health and info are exposed over HTTP. Production health responses do not reveal component details. Unhandled API exceptions are logged server-side and returned as RFC 9457 problem details without exposing exception internals.

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

## IMDb dataset acquisition

The MVP uses six archives from the official [IMDb non-commercial datasets](https://developer.imdb.com/non-commercial-datasets/) collection. The download base URL is `https://datasets.imdbws.com/`, and IMDb refreshes the published files daily. Each archive contains a UTF-8 TSV file; IMDb represents a missing value as `\N`.

| Archive | MVP purpose | Required columns |
| --- | --- | --- |
| `title.basics.tsv.gz` | Movie identity, names, year, and genres | `tconst`, `titleType`, `primaryTitle`, `originalTitle`, `isAdult`, `startYear`, `genres` |
| `title.akas.tsv.gz` | Regional titles and searchable aliases | `titleId`, `title`, `region`, `language`, `types`, `isOriginalTitle` |
| `title.principals.tsv.gz` | Principal cast and crew links | `tconst`, `nconst`, `category` |
| `title.crew.tsv.gz` | Director links | `tconst`, `directors` |
| `name.basics.tsv.gz` | Actor, actress, and director identities | `nconst`, `primaryName`, `primaryProfession`, `knownForTitles` |
| `title.ratings.tsv.gz` | Rating quality and vote counts | `tconst`, `averageRating`, `numVotes` |

Download all six files without starting the Spring application:

```powershell
.\gradlew.bat downloadImdbDatasets
```

Valid existing archives are skipped. Use either an explicit destination or a forced refresh when needed:

```powershell
.\gradlew.bat downloadImdbDatasets -PimdbDataDir=C:\data\movie-finder\imdb
.\gradlew.bat downloadImdbDatasets -PimdbRefresh=true
```

The destination is selected in this order: the `imdbDataDir` Gradle property, the `IMDB_DATA_DIR` environment variable, then `data/imdb/raw`. Local downloads under `data/imdb/raw` and transient `.part` files are ignored by Git. In CI and shared development environments, point `IMDB_DATA_DIR` at ephemeral, access-controlled storage; do not publish the archives as build artifacts or commit them. Tests use the small synthetic archives in `src/test/resources/imdb`, which contain no IMDb rows.

The downloader validates the HTTP response, non-empty gzip stream, gzip checksum, and exact IMDb TSV header before atomically replacing a destination file. A failed refresh leaves the previous file intact, removes the partial download, prints a downloaded/skipped/failed summary, and exits non-zero.

### License and attribution

IMDb permits these files only for personal and non-commercial use, subject to its [usage conditions](https://help.imdb.com/article/imdb/general-information/can-i-use-imdb-data-in-my-software/G5JTRESSHJBBHTGX) and the license information supplied with the data. IMDb prohibits altering, republishing, reselling, or repurposing the data to create an online or offline movie database except for individual personal use, and it may withdraw permission. Do not redistribute either the downloaded files or a derived movie database.

Required attribution: Information courtesy of [IMDb](https://www.imdb.com). Used with permission.

Wikidata, TMDB, studio datasets, and studio search are explicitly outside the MVP data scope. The six IMDb files above are sufficient for movie-title, actor/actress, and director queries.

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

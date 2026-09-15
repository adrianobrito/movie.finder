# Movie Finder

Movie Finder is a movie recommendation and search service. The MVP is implemented entirely in **Java**, including ingestion, search, ranking, and API code.

## Technology

- Java 21
- Spring Boot 4.1
- Gradle 8.14 (via the included wrapper)
- JUnit 5
- OpenSearch 3.8 for the movie and person read model

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

## IMDb import

Run the Java ingestion task after acquiring the six archives:

```powershell
.\gradlew.bat importImdbDatasets
```

The task reads `imdbDataDir` / `IMDB_DATA_DIR` / `data/imdb/raw`, in that order, and writes to `imdbOutputDir` / `IMDB_OUTPUT_DIR` / `data/imdb/canonical`. For example, to import archives in another directory:

```powershell
.\gradlew.bat importImdbDatasets -PimdbDataDir=C:/data/movie-finder/imdb -PimdbOutputDir=C:/data/movie-finder/canonical
```

The output files are `movies.ndjson` and `people.ndjson`, one JSON record per line. Movie records contain title ID, names, sorted aliases, release year, sorted cast and director person IDs, genres, rating, and vote count. Person records contain person ID, name, normalized name, professions, and known-for movie IDs. The ID links join movies to people and can be used to enrich the future search index with cast and director names. Only non-adult `movie` and `tvMovie` titles are included. Missing years and ratings become JSON `null`; duplicate aliases and links are removed. Links to people absent from the names archive are omitted and counted as `missing_linked_people`. The importer reports read, accepted, rejected, and filtered rows for every archive, plus output counts and duration. It logs invalid rows with the archive and line number, then continues. Missing files, bad headers, corrupt gzip streams, and output failures stop the import; existing output files are preserved until replacements are ready. Re-running with the same archives produces byte-identical records. Local canonical output is ignored by Git and must not be redistributed.

## OpenSearch read model

Start the local single-node OpenSearch service, then index the canonical output:

```powershell
docker compose up -d
.\gradlew.bat indexImdbDatasets
```

`indexImdbDatasets` creates the `people` and `movies` indexes from the version-controlled [people mapping](src/main/resources/opensearch/people-v1.json) and [movies mapping](src/main/resources/opensearch/movies-v1.json). It reads `imdbOutputDir` / `IMDB_OUTPUT_DIR` / `data/imdb/canonical` in that order, and connects to `opensearchUrl` / `OPENSEARCH_URL` / `http://localhost:9200`. For a cluster requiring basic authentication, set both `OPENSEARCH_USERNAME` and `OPENSEARCH_PASSWORD`. The local Compose service disables OpenSearch security and binds only to `127.0.0.1`.

Indexing retains existing indexes by default. To delete and recreate both indexes before a full refresh, run:

```powershell
.\gradlew.bat indexImdbDatasets -PopensearchRecreate=true
```

For example, an alternate cluster and canonical directory can be selected with `-PopensearchUrl=http://localhost:9201 -PimdbOutputDir=C:/data/movie-finder/canonical`. A full refresh is `downloadImdbDatasets -PimdbRefresh=true`, then `importImdbDatasets`, then `indexImdbDatasets -PopensearchRecreate=true`. Recreating removes the old documents; indexing without recreation replaces records with matching IMDb IDs but does not remove IDs missing from the new data.

People are bulk indexed first. Movie batches use OpenSearch realtime multi-get to resolve their cast and director IDs into display names, then are bulk indexed under their IMDb title IDs. Batches contain at most 500 records; a bulk item error stops the task and reports the rejected ID. IDs and person links are `keyword` fields for exact filters. Names, titles, and aliases use lowercase and accent-folding text analyzers, with `.raw` keyword fields for exact name/title matching and `.prefix` text fields for prefix queries. Limited typo tolerance belongs in search queries via OpenSearch `fuzziness: "AUTO"`; the mapping does not silently apply fuzzy matching to every query.

The Testcontainers integration tests use the same OpenSearch image and exercise index creation, canonical bulk indexing, exact ID filters, prefix/alias matching, and fuzzy title matching. They run automatically when a Docker-compatible container runtime is available.

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

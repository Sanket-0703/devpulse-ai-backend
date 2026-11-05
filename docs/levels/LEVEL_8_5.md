# Level 8.5 — Framework upgrade (Spring Boot 4, Spring AI 2)

**Goal:** move the backend to the current major versions of its frameworks, **with no feature work mixed in**, and prove nothing broke. The 185 tests written in Levels 1–8 are the safety net: an upgrade is done when they all pass again, the app starts cleanly, and the pages behave exactly as before.

**What changed for the user:** nothing visible, which is the point. The API contract is byte-for-byte the same: the TypeScript types generated from it didn't change by a single character.

> Decided in [TECHNICAL_DECISIONS.md](../project-restart/TECHNICAL_DECISIONS.md) (D-09): first the latest 3.5.x patch, then a separate major upgrade once tests exist.

---

## 1. Versions

| | Before | After |
|---|---|---|
| Spring Boot | 3.5.16 | **4.1.2** |
| Spring Framework | 6.2 | **7** |
| Spring AI | 1.1.8 | **2.0.2** |
| Spring Security | 6.5 | **7.1** |
| Spring Data JPA | 3.5 | **2026.0 (4.x)** |
| Hibernate ORM | 6.6 | **7.4** |
| Jackson (Boot's JSON) | 2.x | **3.1** |
| Tomcat | 10.1 | **11** (Jakarta EE 11) |
| Flyway | 11 | **12** |
| Testcontainers | 1.21 | **2.0** |
| springdoc-openapi | 2.9 | **3.2** (now OpenAPI **3.1**) |

**How the versions were chosen:**
- Spring AI 2.0.2's own build depends on **Spring Boot 4.1.2**, found in its published pom on Maven Central. Using exactly that pair means the two were tested together.
- springdoc 3.2.0 is built on Boot 4.1.2 too.
- Newer previews exist (Boot 4.2 RC, Spring AI 2.1 RC). We stay on stable releases.

---

## 2. What had to change, and why

The process was a loop: change versions → compile → fix → run all tests → fix → run the app → fix. Here is every change, in the order the loop found them.

### 2.1 Build: Boot 4 splits its starters (`pom.xml`)

Boot 4 is **modular**: instead of one big auto-configuration jar, each technology has its own module and starter. That means smaller apps and clearer dependencies, but some artifact names changed.

| Before | After | Why |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | renamed (there is also a WebFlux starter; "web" was ambiguous) |
| `flyway-core` | `spring-boot-starter-flyway` | Flyway's auto-configuration moved into its own module; the starter brings it plus `flyway-core` |
| (test) — | `spring-boot-starter-webmvc-test`, `spring-boot-resttestclient` | MVC test support and `TestRestTemplate` moved into their own modules |
| (test) `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` | Testcontainers 2 renamed every module to `testcontainers-<name>` |

### 2.2 Spring AI 2: options are passed as a builder (`OpenAIProvider`)

```java
// Spring AI 1
.options(ChatOptions.builder().model(model).temperature(temperature).build())
// Spring AI 2
.options(ChatOptions.builder().model(model).temperature(temperature))
```

In Spring AI 2 the request takes the **builder**, so it can merge your options with the client's defaults before building. This was the only compile error in the main code. Spring AI 2 also talks to OpenAI through the **official OpenAI Java SDK**, but `ChatClient` hides that, so nothing else in our code changed.

### 2.3 Jackson 3 (`JsonSecurityErrorHandler`, `PullRequestFilesServiceImpl`, `DemoServiceImpl`)

Boot 4's JSON library is **Jackson 3**:
- the package is `tools.jackson…`, not `com.fasterxml.jackson…`;
- Boot provides a `JsonMapper` bean instead of an `ObjectMapper`.

The trap: other libraries (jjwt, the AWS SDK) still bring Jackson 2, so code injecting the old `ObjectMapper` **still compiled**, and would only have failed at startup ("no bean of type ObjectMapper"). The three classes that used Jackson directly now take `tools.jackson.databind.json.JsonMapper`, and catch `JacksonException`. That exception is **unchecked** in Jackson 3: no more `throws JsonProcessingException` everywhere.

**Unchanged:** the annotations (`@JsonProperty`, `@JsonIgnoreProperties`) keep their old package `com.fasterxml.jackson.annotation`, so no DTO changed.

### 2.4 Spring Data 4: "no condition" is no longer `null` (`RepositoryServiceImpl`, `PullRequestServiceImpl`)

Our list filters returned `null` from a `Specification` to mean "no condition" (no search text, "All" states). Spring Data 4 rejects that:

```
IllegalArgumentException: Other specification must not be null
```

They now return **`Specification.unrestricted()`**, an explicit "matches everything". The `Specification.where(...)` wrapper isn't needed either; the first condition is the start of the chain. The compiler couldn't catch this one; the integration tests did, with a 500 on `GET /api/github/repositories`.

### 2.5 Two copies of the Swagger annotations (`pom.xml`)

`/v3/api-docs` answered 500:

```
NoSuchMethodError: 'String io.swagger.v3.oas.annotations.media.Schema.$dynamicRef()'
```

`mvn dependency:tree` showed why:
- springdoc 3.2 needs `swagger-core` **2.2.55**;
- Spring AI's chat client brought `swagger-annotations-jakarta` **2.2.38**;
- the OpenAI SDK brought `swagger-annotations` **2.2.31**.

The last two contain **the same classes**, both older. Whichever came first on the classpath won, and it lacked the method springdoc calls. The fix:
1. exclude the OpenAI SDK's copy from `spring-ai-starter-model-openai`;
2. pin `swagger-annotations-jakarta` to springdoc's version in `<dependencyManagement>` (`swagger.version` property).

This is a classic "dependency hell" bug: it compiles fine, and fails only at runtime when the missing method is called.

### 2.6 Test infrastructure

- **`TestRestTemplate`** moved to `org.springframework.boot.resttestclient` and is no longer registered by `@SpringBootTest` alone. `AbstractIntegrationTest` now carries **`@AutoConfigureTestRestTemplate`**, so every integration test gets it.
- **Cookies.** Boot 4's HTTP clients keep cookies by default (`HttpCookieHandling`). Our tests attach the exact refresh cookie each request should send, so a client that also remembers cookies replayed old ones: "Refresh token has already been used". `application-test.properties` now sets `spring.http.clients.cookie-handling=disable`.
- **Jackson 3 is strict about missing numbers.** A few tests read an *error* response (401, 400) into the *success* type (`AuthResponse`, `PlaygroundResponse`):
  - Jackson 2 quietly filled the missing `long`/`int` fields with 0;
  - Jackson 3 refuses (`FAIL_ON_NULL_FOR_PRIMITIVES` is on by default).

  Those tests now read error responses as a `Map` (`loginExpectingError`, `reviewExpectingError`). The new behaviour is the better one: a test expecting an error shouldn't pretend the body is a success object.
- **Testcontainers 2:** `PostgreSQLContainer` moved to `org.testcontainers.postgresql` and lost its generic parameter (`PostgreSQLContainer`, not `PostgreSQLContainer<?>`).

### 2.7 A clean startup log

Two things only showed up when running the real app:
- **A red ERROR from Netty** ("Unable to load MacOSDnsServerAddressStreamProvider"). Spring AI 2's HTTP clients use Netty, which wants a native library to read macOS's DNS settings. It falls back fine, but an ERROR on every start trains you to ignore red lines.
  - A Maven **profile activated only on macOS** adds `netty-resolver-dns-native-macos`, with both CPU variants (Apple Silicon and Intel); Netty loads the matching one.
  - Linux (CI, Docker) doesn't get it.
- **Seven INFO lines** from Spring Data Redis wondering whether each JPA repository might be a Redis repository. We use Redis only through `RedisTemplate`, so `spring.data.redis.repositories.enabled=false` turns that scan off.

---

## 3. What was checked

| Check | Result |
|---|---|
| Main code compiles | after 1 fix (§2.2) |
| Deprecation warnings (`-Dmaven.compiler.showDeprecation=true`) | **none** |
| All backend tests | **185 / 185** (after §2.3–2.6) |
| `mvn clean verify` exit code | **0** |
| Instruction coverage | **93.4%**, the same as before the upgrade |
| App startup | no ERROR or WARN; Flyway validates all 9 migrations; Hibernate 7 validates the schema |
| Generated TypeScript API types | **identical**: the API contract didn't change |
| Browser (demo account) | dashboard numbers, a new review (same 9 findings, same 1,723 tokens as on Boot 3), Playground (same 832 tokens), Swagger UI |

No frontend change was needed.

---

## 4. Explain it in an interview

**How did you approach a major framework upgrade?**
On its own branch, with no feature work, so any change in behaviour is the upgrade's fault. First I picked versions that are certified together (Spring AI's published pom names the Boot version it's built on). Then I looped: compile, fix, run the whole test suite, fix, start the app and click through it. The integration tests (real Postgres and Redis in containers, WireMock for GitHub) caught what the compiler couldn't. Finally I checked the generated API types didn't change, so the frontend contract stayed the same.

**What broke that the compiler didn't catch?**
- Spring Data 4 rejecting `null` specifications (a 500 on list endpoints).
- A classpath conflict between two older copies of the Swagger annotations (`NoSuchMethodError` at runtime on the API docs).
- Jackson 2 still on the classpath: code injecting the old `ObjectMapper` compiled, but Boot 4 only provides a Jackson 3 `JsonMapper` bean.
- The test HTTP client starting to keep cookies.

All were found by tests or by running the app, not by reading release notes.

**How do you debug a `NoSuchMethodError`?**
It means the code was compiled against a newer version of a class than the one loaded at runtime. `mvn dependency:tree -Dincludes=<group>` showed two artifacts providing the same package at older versions. I excluded the duplicate and pinned the right version in `dependencyManagement`, so every transitive user gets the version the caller was built against.

**What's new in Jackson 3 that matters day to day?**
The `tools.jackson` package, `JsonMapper` (immutable, built with a builder) instead of a mutable `ObjectMapper`, and unchecked `JacksonException`. Defaults are stricter: null into a primitive fails, and dates are ISO strings by default. Annotations kept their old package, so DTOs didn't change.

**Why upgrade at all, if nothing visible changed?**
Security patches and support: the 3.5 line's open-source support was ending. Also access to Java 21-era features and the newer Spring AI APIs the later levels use (RAG in Level 10). Upgrading early, while the codebase is small and well tested, is cheaper than upgrading late under pressure.

---

## 5. Files in this level

**Build:** `pom.xml` (versions, Boot 4 starters, Testcontainers 2 module, swagger-annotations pin and exclusion, macOS Netty profile)

**Main code:**
- `service/impl/OpenAIProvider` (options builder)
- `security/JsonSecurityErrorHandler`, `service/impl/PullRequestFilesServiceImpl`, `service/impl/DemoServiceImpl` (Jackson 3)
- `service/impl/RepositoryServiceImpl`, `service/impl/PullRequestServiceImpl` (`Specification.unrestricted()`)
- `application.properties` (Redis repositories off)

**Tests:**
- `AbstractIntegrationTest` (`@AutoConfigureTestRestTemplate`, Testcontainers 2 `PostgreSQLContainer`)
- every integration test (the new `TestRestTemplate` package)
- `ProfileIntegrationTest`, `PlaygroundIntegrationTest` (read errors as errors)
- `PullRequestFilesServiceImplTest` (Jackson 3)
- `application-test.properties` (cookie handling)

**Docs:**
- `docs/levels/LEVEL_8_5.md`
- `BUILD_PLAN.md`, `TECHNICAL_DECISIONS.md` (D-09 outcome)
- `IDE_GUIDE.md` (Level 8.5)

# Ghibli AI Art Generator

A full-stack web application that turns photos and text prompts into Studio Ghibli style artwork
using Stability AI. Accounts, generation and per-user history are all persisted.

- **Photo to Art** — upload an image, get it re-imagined in Ghibli style (image-to-image)
- **Text to Art** — describe a scene and pick a style (text-to-image)
- **Accounts** — email/password signup and login, stateless JWT sessions
- **History** — every generation is stored per user, browsable, downloadable, deletable

## Project Structure

| Path | What it is |
| --- | --- |
| `ghbliapi` | Spring Boot 3.5.3 / Java 21 REST API |
| `ghbli-art-generator` | React 19 SPA (Create React App, Tailwind CSS) |

## Tech Stack

**Backend** — Spring Boot 3.5.3, Java 21, Spring Web, Spring Security 6.5 (stateless JWT via
jjwt 0.13), Spring Data MongoDB, Spring Cloud OpenFeign 4.3.0, Bean Validation, Maven wrapper.

**Frontend** — React 19, react-router-dom 7, Tailwind CSS 3, the Fetch API (no axios).

**Data** — MongoDB (Atlas in deployment, a local `mongod` or Flapdoodle embedded Mongo in tests).

**External** — Stability AI SDXL, text-to-image and image-to-image.

## Architecture Notes

### Two transports to Stability, on purpose

Text-to-image goes through OpenFeign (`StabilityAIClient`). It sends a JSON body, which the
declarative client handles cleanly.

Image-to-image does **not**. It is posted by `GhibliArtService` with an injected `RestTemplate`,
and the reason is measured rather than assumed — see
`src/test/java/in/suhansingh/ghbliapi/client/FeignMultipartEncodingTest.java`, which encodes the
real request and asserts on the bytes Feign would put on the wire. In short: `feign-form` (a
non-optional dependency of `spring-cloud-starter-openfeign`) routes a Spring `Resource`-typed
`@RequestPart` to its catch-all `PojoWriter`, which reflects over declared fields and skips final
ones. `ByteArrayResource` has exactly one field, `private final byte[] byteArray`, so the
`init_image` part is written as nothing at all — **with no exception and no log**. The other two
parts encode correctly, so Stability answers with a 400 naming `init_image`, which reads like a
caller bug rather than an encoder fault. The resize step is what forces the issue: it produces
`byte[]`, never the original `MultipartFile` that `feign-form` does handle.

`RestTemplate` encodes all three parts correctly, so it stays. `StabilityHttpClientConfig` gives
it explicit 10s connect / 60s read timeouts, because the default constructor's infinite timeouts
would let a hung Stability connection pin a Tomcat worker thread.

### Why MongoDB

The stored shape is a document, not a set of related rows: a generation is one prompt, one style,
one engine id, one set of PNG dimensions and one blob, read back as a whole or not at all. There
are no joins in the read path — history is `findByUserIdOrderByCreatedAtDesc`, served by a
compound index — and nothing here needs a transaction across aggregates. The PNG matters too: a
`byte[]` maps directly to BSON BinData, so image bytes live in the same store as their metadata
without a second system or a filesystem to keep in sync.

`spring.data.mongodb.auto-index-creation=true` is **required, and silent when missing**. Spring
Data MongoDB flipped that default to `false` in 3.0, so `@Indexed(unique = true)` on `User.email`
creates no index and enforces nothing — two accounts could share one address, and it would only
surface much later. `UserIndexTest` asserts the index physically exists rather than trusting the
annotation.

### Why BinData in a separate collection, not GridFS

Two decisions, often confused with each other.

**Separate collection** (`generation_images`, one document per image). Mongo returns whole
documents unless a projection is spelled out, so the reliable way to keep 1–2 MB of binary out of
a paginated list query is for the bytes not to be in the listed document at all. `Generation`
holds metadata and an `imageId`; `GenerationImage` holds the blob. The list endpoint never touches
the image collection, so a page of twelve rows costs kilobytes.

**Not GridFS.** MongoDB recommends GridFS for files above the 16 MB BSON document limit. An SDXL
PNG is 1–2 MB, an order of magnitude below it. GridFS would add chunk assembly on every read, its
own two collections, and ObjectId/hex-string plumbing, in exchange for a ceiling this data never
approaches.

`GenerationImage` also stores `userId`, duplicated from its `Generation`. Redundant in the happy
path, deliberately: the image fetch re-asserts ownership instead of resting on one query being
written correctly.

### Security model

Stateless. No sessions, no cookies. `AuthController` issues a signed HS256 JWT whose **subject is
the Mongo user id**, not the email — an address can be corrected, an id cannot. Every other
endpoint requires `Authorization: Bearer <token>`; `JwtAuthenticationFilter` validates it and
populates the `SecurityContext`.

CSRF is disabled, which is safe **only** because the session policy is stateless and the token
travels in a header the browser never attaches automatically. Reintroducing cookie auth would make
that line a vulnerability.

No history endpoint takes a user id — not as a path variable, not as a query parameter. The owner
is resolved from the `SecurityContext` inside `GenerationHistoryService`, because
`/api/v1/generations?userId=…` is an IDOR that no later validation makes safe.

## API Reference

Base URL: `http://localhost:8080`. Every path below is prefixed `/api/v1`.

Only the two auth endpoints are public. Everything else needs
`Authorization: Bearer <token>` and answers **401** (not 403) without one.

### Errors

Every failure is an RFC 9457 `application/problem+json` body:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Image is too large. Stability supports up to 5MB for photo-to-art.",
  "instance": "/api/v1/generate"
}
```

A validation failure adds a per-field `errors` map, which is what lets the auth forms mark the
offending input rather than only showing a banner:

```json
{ "status": 400, "errors": { "email": "must be a well-formed email address" } }
```

### Authentication

#### `POST /auth/signup` → **201**

Request `application/json`:

| Field | Rules |
| --- | --- |
| `name` | not blank, ≤ 100 chars |
| `email` | not blank, well-formed, ≤ 254 chars; trimmed and lower-cased on the way in |
| `password` | 8–72 chars (72 is BCrypt's input limit — longer is silently truncated) |

Response `application/json` — the `AuthResponse`, seven fields:

| Field | Notes |
| --- | --- |
| `token` | signed JWT |
| `tokenType` | always `Bearer`, spelled out so no client hardcodes it |
| `expiresIn` | lifetime in **seconds**, not a timestamp (`jwt.expiration` is in milliseconds) |
| `userId` | Mongo id, also the JWT subject |
| `name` | display name |
| `email` | normalised form as stored |
| `roles` | authorities, already carrying the `ROLE_` prefix |

201 is returned with an immediately usable token, so there is no follow-up login call.
**409** for an address already taken.

#### `POST /auth/login` → **200**

Request: `{ "email", "password" }`. Response: the same `AuthResponse` shape.

**401** `"Incorrect email or password."` for both a wrong password and an unknown address — the
difference is hidden deliberately, so the endpoint cannot be used to enumerate accounts.

### Generation

Both endpoints return **raw `image/png` bytes** on success, and record a history row afterwards.
Recording cannot fail the request: a Mongo problem is swallowed so the user never loses a
generated image to a bookkeeping error.

#### `POST /generate` → **200 `image/png`**

`multipart/form-data`, two parts:

| Part | Notes |
| --- | --- |
| `image` | the photo. Hard limit 5 MB (Stability's `init_image` ceiling), checked before the call |
| `prompt` | free text; the Ghibli style suffix is appended server-side |

The upload is resized server-side to the nearest allowed SDXL dimension pair before it is sent,
otherwise Stability rejects it with `invalid_sdxl_v1_dimensions`. Style is fixed to `anime` here —
this endpoint accepts no style parameter.

**400** for an image over 5 MB or one Java cannot decode.

#### `POST /generate-from-text` → **200 `image/png`**

`application/json`: `{ "prompt", "style" }`. `style` is optional — `null`, blank or `general` all
map to Stability's `anime` preset; anything else has `_` replaced with `-` (so `analog_film`
becomes `analog-film`).

### History

All three are scoped to the authenticated caller. A generation belonging to somebody else answers
**404**, the same as an id that never existed.

#### `GET /generations?page=0&size=12` → **200 `application/json`**

Newest first. `page` ≥ 0, `size` 1–100; outside that range is a **400 ProblemDetail, not a clamp**.
There is no `sort` parameter — order is fixed server-side.

The `PageResponse` envelope, eight fields:

```json
{ "content": [ … ], "page": 0, "size": 12, "totalElements": 37,
  "totalPages": 4, "first": true, "last": false, "numberOfElements": 12 }
```

Each `content` item is a `GenerationSummaryResponse`, nine fields — `id`, `type`
(`TEXT_TO_IMAGE` | `IMAGE_TO_IMAGE`), `prompt` (as typed, without the Ghibli suffix), `style`,
`engineId`, `width`, `height`, `imageSizeBytes`, `createdAt` (ISO-8601 UTC). The last three are
nullable for rows whose PNG header could not be read; they exist so a grid can reserve layout
space and show a file size without fetching a megabyte.

`content` carries **no image bytes and no image id** — the bytes are addressed by the *generation*
id below. An empty `content` array, not a 404, when the user has no history.

#### `GET /generations/{id}/image` → **200 `image/png`**

Cached `private, max-age=86400`, since the bytes behind an id never change.

This is the endpoint `<img src="…">` cannot reach: the browser's image loader sends no
`Authorization` header, so a naive `src` attribute 401s with nothing in the console to explain it.
The frontend fetches the blob and wraps it in `URL.createObjectURL` (and revokes it on unmount).

#### `DELETE /generations/{id}` → **204**

Removes the metadata document and the image document. Not idempotent-by-404: deleting twice gives
204 then 404, which is the honest report of what happened.

## Configuration

`src/main/resources/application.properties` is tracked in git and holds **no literal secrets** —
every sensitive value is resolved from an environment variable at startup.
`src/main/resources/application-example.properties` is the annotated reference for every key, with
placeholder values only. `ConfigurationCompletenessTest` fails the build if the two drift apart or
if either ever carries a real credential.

### Environment variables

| Variable | Required | What it is |
| --- | --- | --- |
| `STABILITY_API_KEY` | yes, to generate | Stability key from <https://platform.stability.ai/account/keys>; starts with `sk-` |
| `JWT_SECRET` | **yes, to start** | Base64-encoded HS256 key, ≥ 32 bytes decoded |
| `MONGODB_URI` | recommended | `mongodb+srv://…` from Atlas → Connect → Drivers |
| `STABILITY_TEXT_ENGINE` | no | defaults to `stable-diffusion-xl-1024-v1-0` |
| `STABILITY_IMAGE_ENGINE` | no | defaults to `stable-diffusion-xl-1024-v1-0` |

`JWT_SECRET` has no fallback: the application refuses to start without it, rather than signing
tokens with a default that would be identical in every deployment. Generate one with

```bash
openssl rand -base64 32
```

or, on PowerShell:

```powershell
[Convert]::ToBase64String((1..32 | % { Get-Random -Max 256 }))
```

Rotating it invalidates every issued token, which logs everyone out.

`STABILITY_API_KEY` defaults to empty on purpose, so the context starts and the test suite runs
offline; the first generation then fails with a 401 from Stability rather than at startup.

`MONGODB_URI` falls back to `mongodb://localhost:27017/ghbli`. That keeps a clean clone runnable,
but note it is **not** Atlas — with the variable unset the app starts happily against a local
`mongod` and nothing says so. The fallback is still needed: without it an unset variable binds the
literal string `${MONGODB_URI}` and later surfaces as a misleading "connection string is invalid".

### Property keys

| Key | Value |
| --- | --- |
| `stability.api.base-url` | `https://api.stability.ai` |
| `stability.api.key` | `${STABILITY_API_KEY:}` |
| `stability.api.text-engine` | `${STABILITY_TEXT_ENGINE:stable-diffusion-xl-1024-v1-0}` |
| `stability.api.image-engine` | `${STABILITY_IMAGE_ENGINE:stable-diffusion-xl-1024-v1-0}` |
| `spring.servlet.multipart.max-file-size` | `20MB` — raised from the 1 MB default, which rejected ordinary phone photos with a 413 before the handler ran |
| `spring.servlet.multipart.max-request-size` | `20MB` — kept equal to the file limit |
| `spring.data.mongodb.uri` | `${MONGODB_URI:mongodb://localhost:27017/ghbli}` |
| `spring.data.mongodb.auto-index-creation` | `true` — see above; silent when missing |
| `jwt.secret` | `${JWT_SECRET}` |
| `jwt.expiration` | `86400000` (24 h, in **milliseconds**) |

The 20 MB multipart limit is Spring's; `GhibliArtService` still enforces Stability's own 5 MB
`init_image` ceiling itself and returns a readable 400 for anything larger.

### Frontend

`ghbli-art-generator/.env.example` documents the single key the SPA reads:

| Variable | Default |
| --- | --- |
| `REACT_APP_API_BASE_URL` | `http://localhost:8080` |

CRA only exposes `REACT_APP_`-prefixed variables, and inlines them **at build time** — `VITE_*` and
`import.meta.env` do not exist in `react-scripts` 5.

## Local Setup

### Prerequisites

- Java 21+
- Node.js 18+
- MongoDB — an Atlas cluster, or a local `mongod` on 27017
- A Stability AI API key
- Maven is not needed; use the bundled wrapper

The tests need neither Mongo nor a key: Flapdoodle starts its own `mongod` on a free port, and
nothing in the suite calls Stability.

### 1. Backend

From `ghbliapi`, set the environment and run. macOS / Linux:

```bash
export STABILITY_API_KEY="sk-your-key-here"
export JWT_SECRET="$(openssl rand -base64 32)"
export MONGODB_URI="mongodb+srv://user:password@cluster.mongodb.net/ghbli"
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
$env:STABILITY_API_KEY="sk-your-key-here"
$env:JWT_SECRET="REPLACE_WITH_BASE64_32_BYTE_SECRET"
$env:MONGODB_URI="mongodb+srv://user:password@cluster.mongodb.net/ghbli"
.\mvnw.cmd spring-boot:run
```

Set these in your shell or IDE run configuration — never by replacing the `${...}` in
`application.properties`. The API listens on <http://localhost:8080>.

### 2. Frontend

From `ghbli-art-generator`:

```bash
npm install
npm start
```

Served on <http://localhost:3000>. Only `http://localhost:3000` and `http://127.0.0.1:3000` are
allowed origins, so a different port will fail CORS preflight.

## Tests and Build

Backend suite (Flapdoodle downloads `mongod` 7.0.14 on first run):

```bash
./mvnw test
```

Frontend production build:

```bash
npm run build
```

Worth knowing about the test setup: `src/test/resources/application.properties` **shadows** the
main file rather than merging with it, because Spring resolves `classpath:/application.properties`
to the first match and `target/test-classes` precedes `target/classes`. Anything the context needs
at startup has to be restated there, which is why the Stability and JWT keys appear in it. The
values are test-only placeholders and sign nothing real.

`de.flapdoodle.mongodb.embedded.version=7.0.14` is required in that file — the Flapdoodle jar
self-activates for every `@SpringBootTest` and fails at startup without it. It is pinned rather
than tracking latest so a new upstream release cannot change what CI runs against.

## Usage

Sign up or log in first — every generation endpoint is authenticated, and the session is held in
`localStorage` under `ghbli.auth.v1`.

**Photo to Art** — open Create → Photo to Art → browse for an image (max 5 MB) → add details →
Transform. Download the result, or Create Another to reset the form.

**Text to Art** — open Create → Text to Art → choose a style → describe the scene → Generate.

**History** — every generation appears in History, newest first, twelve per page, with download
and delete.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| App won't start, "Could not resolve placeholder 'jwt.secret'" | `JWT_SECRET` is not set |
| Startup fails complaining the key is too short | `JWT_SECRET` decodes to under 32 bytes; HS256 needs at least that |
| 401 on every generation, login works | Stability rejected the key — check `STABILITY_API_KEY` |
| Network errors from the SPA | backend not running on 8080, or the SPA is served from a port other than 3000 |
| Photo upload fails | image over 5 MB, or a format `ImageIO` cannot decode |
| Two accounts share one email | `spring.data.mongodb.auto-index-creation` is not `true` |
| Data written but Atlas is empty | `MONGODB_URI` unset, so the localhost fallback took effect |

## Project Screenshots

Stored in `src/main/resources/static`.

### Home Page
![Home Page](src/main/resources/static/S1.png)

### Features Section
![Features Section](src/main/resources/static/S2.png)

### Footer
![Footer](src/main/resources/static/S3.png)

### PhotoToArt Generation
![PhotoToArt Generation](src/main/resources/static/S4.png)

### TextToArt Creation
![TextToArt Creation](src/main/resources/static/S5.png)

## Notes on the Engineering

- **Full-stack, end to end** — React SPA through Spring Boot REST API to MongoDB and a third-party
  AI provider, including auth, pagination, binary payloads and a unified error contract.
- **Third-party integration with the sharp edges handled** — SDXL's fixed dimension pairs, the 5 MB
  `init_image` ceiling, binary responses, and two different transports because one of them
  provably cannot carry a multipart image.
- **Decisions are documented where they were made, and measured where they could be.** The reason
  image-to-image bypasses Feign is a test that inspects the encoded bytes, not a comment asserting
  a cause. If a future `feign-form` release fixes the drop, that test fails and the decision gets
  re-examined instead of quietly outliving its reason.
- **Security treated as structure, not validation.** History endpoints take no user id at all, so
  IDOR is not something to remember to check for. `AuthResponse` is a record, so "there is no
  password field, and no setter could add one" is enforced by the type.

## License

For educational and portfolio use.







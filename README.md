# audd-java

Official Java SDK for [music recognition API](https://audd.io): identify music from a short audio clip, a long audio file, or a live stream.

The API itself is so simple that it can easily be used even without an SDK: [docs.audd.io](https://docs.audd.io).

## Install

Maven:

```xml
<dependency>
  <groupId>io.audd</groupId>
  <artifactId>audd</artifactId>
  <version>1.5.5</version>
</dependency>
```

Get your API token at [dashboard.audd.io](https://dashboard.audd.io).

Gradle (Kotlin DSL):

```kotlin
implementation("io.audd:audd:1.5.5")
```

Java 11+. Modular consumers: `requires io.audd;`.

## Hello, AudD

Recognize from a URL:

```java
import io.audd.AudD;

try (AudD audd = new AudD("test")) {
    var result = audd.recognize("https://audd.tech/example.mp3");
    if (result != null) {
        System.out.println(result.artist() + " — " + result.title());
    }
}
```

Recognize a local file:

```java
import io.audd.AudD;
import java.nio.file.Path;

try (AudD audd = new AudD("test")) {
    var result = audd.recognize(Path.of("clip.mp3"));
    if (result != null) {
        System.out.println(result.artist() + " — " + result.title());
    }
}
```

`recognize(Object)` accepts a URL string, `Path`, `File`, `byte[]`, or
`InputStream`. The public `"test"` token is capped at 10 requests; get a
real one at [dashboard.audd.io](https://dashboard.audd.io/).

## Authentication

Pass the token explicitly:

```java
AudD audd = new AudD("your-token");
// or, with the builder:
AudD audd = AudD.builder().apiToken("your-token").build();
```

Or set `AUDD_API_TOKEN` in the environment and the builder picks it up:

```java
AudD audd = AudD.fromEnvironment();   // reads AUDD_API_TOKEN
```

For long-running services that hot-rotate credentials,
`audd.setApiToken(newToken)` is atomic and thread-safe — in-flight
requests finish on the old token, subsequent ones use the new one.

## What you get back

By default `recognize()` returns the core tags plus AudD's universal song
link — no metadata-block opt-in needed:

```java
import io.audd.AudD;
import io.audd.models.RecognitionResult;
import io.audd.models.StreamingProvider;

try (AudD audd = new AudD("your-token")) {
    RecognitionResult r = audd.recognize(Path.of("clip.mp3"));
    if (r == null) return;   // no match

    // Core metadata
    System.out.println(r.artist() + " — " + r.title());
    System.out.println("album:        " + r.album());
    System.out.println("released:     " + r.releaseDate());
    System.out.println("label:        " + r.label());
    System.out.println("timecode:     " + r.timecode());   // hh:mm:ss within the clip
    System.out.println("song_link:    " + r.songLink());   // lis.tn page (links into every provider)

    // Helpers — driven off songLink, work without any returnMetadata opt-in
    System.out.println("thumbnail:    " + r.thumbnailUrl());            // cover art
    System.out.println("on Spotify:   " + r.streamingUrl(StreamingProvider.SPOTIFY));
    r.streamingUrls().forEach((p, url) -> System.out.println(p.slug() + ": " + url));

    // Custom-catalog match? audio_id is set, public-match fields aren't.
    if (r.isCustomMatch()) {
        System.out.println("matched custom audio_id=" + r.audioId());
    }
}
```

If you need provider-specific metadata blocks, opt in per call. Request
only what you need — each provider you ask for adds latency:

```java
import io.audd.RecognizeOptions;

RecognitionResult r = audd.recognize(
    "https://audd.tech/example.mp3",
    RecognizeOptions.builder()
        .returnMetadata("apple_music", "spotify")
        .build()
);
if (r.appleMusic() != null) System.out.println("Apple Music: " + r.appleMusic().url());
if (r.spotify()    != null) System.out.println("Spotify uri: " + r.spotify().uri());
System.out.println("preview:     " + r.previewUrl());  // first preview across requested providers, null if none
```

Valid `returnMetadata` values: `apple_music`, `spotify`, `deezer`,
`napster`, `musicbrainz`. Block accessors (`r.appleMusic()`, etc.) return
`null` when not requested.

For audio longer than 25 seconds, use `audd.recognizeEnterprise(source,
opts)` — it returns `List<EnterpriseMatch>`. Each `EnterpriseMatch`
carries the same core tags plus `score()`, `startOffset()`, `endOffset()`,
`isrc()`, `upc()`. Access to `isrc`, `upc`, and `score` requires a Startup
plan or higher — [contact us](mailto:api@audd.io) for enterprise features.

### Reading additional metadata

`RecognitionResult` and the per-provider blocks each expose
`extras()` — a `Map<String, Object>` of every field the server returned
that doesn't have a typed accessor. This is the entry point for
undocumented or beta server fields:

```java
Object isrc = r.spotify().extras().get("external_ids");   // server may add more keys
JsonNode raw = r.rawResponse();                           // full unparsed JSON tree
```

## Errors

Everything the SDK throws extends `AudDException`:

```
AudDException
├── AudDApiError                       // server returned status=error
│   ├── AudDAuthenticationError        (900, 901, 903)
│   ├── AudDQuotaError                 (902)
│   ├── AudDSubscriptionError          (904, 905)
│   │   └── AudDCustomCatalogAccessError
│   ├── AudDInvalidRequestError        (50, 51, 600/601/602, 700/701/702, 906)
│   ├── AudDInvalidAudioError          (300, 400, 500)
│   ├── AudDRateLimitError             (611)
│   ├── AudDStreamLimitError           (610)
│   ├── AudDNotReleasedError           (907)
│   ├── AudDBlockedError               (19, 31337)
│   ├── AudDNeedsUpdateError           (20)
│   └── AudDServerError                (100, 1000, generic 5xx)
├── AudDConnectionError                // network / TLS / timeout, no response
└── AudDSerializationError             // 2xx with malformed JSON
```

Catch the leaf you care about, or pattern-match on Java 21:

```java
try {
    audd.recognize(clip);
} catch (AudDException e) {
    switch (e) {
        case AudDQuotaError q          -> backOffUntilNextMonth();
        case AudDRateLimitError r      -> sleepAndRetry();
        case AudDInvalidAudioError ia  -> log.warn("not music: {}", ia.getMessage());
        case AudDConnectionError ce    -> log.warn("network: {}", ce.getMessage());
        default                        -> throw e;
    }
}
```

Every `AudDApiError` exposes `errorCode()`, `httpStatus()`, and
`requestId()` for support tickets.

## Configuration

```java
import okhttp3.OkHttpClient;

AudD audd = AudD.builder()
    .apiToken("your-token")
    .maxRetries(5)
    .backoffFactorMs(1000)
    .standardTimeoutSeconds(120)
    .enterpriseTimeoutSeconds(7200)
    .httpClient(new OkHttpClient.Builder()      // corporate proxy, custom interceptors
        // .proxy(...)
        .build())
    .onDeprecation(msg -> log.warn("audd-deprecation: {}", msg))
    .onEvent(event -> metrics.record(event))    // request / response / exception
    .build();
```

Retries are cost-aware: read endpoints retry on 408/429/5xx + connection
errors; recognition endpoints retry on 5xx and pre-upload connection
failures only (no replay after a metered upload completed); mutating
endpoints retry only on pre-upload connection failures.

## Async

`AsyncAudD` mirrors `AudD` and returns `CompletableFuture` from every
network method. Same builder, same configuration:

```java
import io.audd.AsyncAudD;

try (AsyncAudD audd = AudD.builder().apiToken("your-token").buildAsync()) {
    audd.recognize("https://audd.tech/example.mp3")
        .thenAccept(r -> System.out.println(r.artist() + " — " + r.title()))
        .join();
}
```

## Streams

A stream is a long-running URL (radio, Twitch, YouTube live) that AudD
monitors and reports matches against. Manage streams via
`audd.streams()`:

```java
import io.audd.streams.AddStreamRequest;

audd.streams().setCallbackUrl("https://your-app.example.com/audd-hook");
audd.streams().add(new AddStreamRequest("https://stream.example.com/live", 1234));
var streams = audd.streams().list();
audd.streams().delete(1234);
```

Match events arrive two ways. Either AudD POSTs each match to your
callback URL — parse the body inside your handler:

```java
import io.audd.streams.Streams;
import io.audd.models.CallbackEvent;

// Inside your servlet / HTTP-handler:
try (var in = httpRequest.getInputStream()) {
    CallbackEvent event = Streams.parseCallback(in);
    event.match().ifPresent(m ->
        System.out.println(m.song().artist() + " — " + m.song().title()));
    event.notification().ifPresent(n ->
        System.out.println("notif: " + n.notificationMessage()));
}
```

`parseCallback` is overloaded for `InputStream`, `byte[]`, and Jackson
`JsonNode`. The returned `CallbackEvent` has exactly one of `match()` /
`notification()` populated. A `match` exposes the top match as `song()`
plus any extra candidates (which may legitimately have a different
`artist`/`title` — variant catalog releases) as `alternatives()`.

### Receiving events without a callback URL (longpoll)

If hosting a callback receiver isn't an option, longpoll for events
directly. Three callback registrations — match, notification, error —
drive a blocking dispatch loop:

```java
import io.audd.streams.LongpollPoll;

String category = audd.streams().deriveLongpollCategory(1234);
try (LongpollPoll poll = audd.streams().longpoll(category)) {
    poll.onMatch(m -> System.out.println(m.song().artist() + " — " + m.song().title()));
    poll.onNotification(n -> System.out.println("notif: " + n.notificationMessage()));
    poll.onError(err -> System.err.println(err));
    poll.run();   // blocks until close() or terminal error
}
```

`runAsync()` returns a `CompletableFuture<Void>` if you'd rather drive the
loop on a daemon thread. `close()` is idempotent and safe to call from
any thread (signal handler, shutdown hook, etc.).

For browser widgets, embedded UIs, or anywhere you need to consume a
category without leaking the API token, derive it server-side and ship
just the category to the untrusted client:

```java
// On your server — derive locally, no network call:
String category = Streams.deriveLongpollCategory(serverToken, radioId);
respondWithCategory(category);    // ship to the browser

// In the browser / widget — poll directly, no api_token:
//   GET https://api.audd.io/longpoll/?category=<category>&timeout=50
```

You're free to drive that GET however you like — `fetch()` from
JavaScript, `java.net.http.HttpClient` from a JVM widget, anything. The
SDK isn't required on the consuming side.

## Custom catalog (advanced)

> This is **not** how you submit audio for music recognition. For that,
> use `audd.recognize(...)` (or `audd.recognizeEnterprise(...)` for
> files longer than 25 seconds). The custom-catalog endpoint manipulates
> your **private fingerprint catalog** so AudD's recognition can later
> identify *your own* tracks for *your account only*. Requires special
> access — contact api@audd.io if you need it enabled.

```java
audd.customCatalog().add(146, Path.of("track.mp3"));
```

`audd.advanced().rawRequest(method, params)` is the escape hatch for
any AudD endpoint not yet wrapped by typed methods on this SDK.

## License & support

- Issues: <https://github.com/AudDMusic/audd-java/issues>
- Security: see [SECURITY.md](./SECURITY.md)
- License: MIT (see [LICENSE](./LICENSE))

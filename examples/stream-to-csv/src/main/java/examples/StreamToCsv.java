/*
 * Subscribe to (or attach to) an AudD stream slot, longpoll its recognitions,
 * and append each match as a row in a CSV.
 *
 * Two modes:
 *
 *   Provision-and-listen (Mode 1):
 *     mvn -q exec:java -Dexec.args="--url https://stream.example/live.m3u8"
 *     mvn -q exec:java -Dexec.args="--url https://stream.example/live.m3u8 --radio-id 12345"
 *   Adds the stream slot on startup and DELETES it on exit.
 *
 *   Listen-only (Mode 2):
 *     mvn -q exec:java -Dexec.args="--radio-id 12345"
 *   Attaches to an existing slot. Does NOT add or delete; account state is
 *   left unchanged.
 *
 * Reads the API token from AUDD_API_TOKEN.
 */
package examples;

import com.fasterxml.jackson.databind.JsonNode;
import io.audd.AudD;
import io.audd.errors.AudDApiError;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.streams.AddStreamRequest;
import io.audd.streams.LongpollOptions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StreamToCsv {

    private static final String DEFAULT_OUTPUT = "audd_stream_tracks.csv";
    private static final String EMPTY_CALLBACK_URL = "https://audd.tech/empty/";
    private static final int DEFAULT_RADIO_ID = 99999;
    private static final int NO_CALLBACK_ERROR_CODE = 19;
    private static final String[] CSV_HEADER =
            { "timestamp", "radio_id", "score", "artist", "title", "album", "song_link" };

    public static void main(String[] argv) {
        Args args = Args.parse(argv);
        if (args == null) {
            System.err.println("Usage:");
            System.err.println("  StreamToCsv --url <stream-url> [--radio-id N] [--output FILE]   (provision)");
            System.err.println("  StreamToCsv --radio-id N [--output FILE]                         (listen-only)");
            System.exit(2);
            return;
        }

        try (AudD audd = AudD.fromEnvironment()) {
            run(audd, args);
        } catch (Exception e) {
            System.err.println("fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(AudD audd, Args args) throws IOException {
        // 1. Callback-URL preflight. Differs by mode: Mode 1 will install our
        //    placeholder if none is set; Mode 2 refuses, since changing the
        //    account state isn't this command's job.
        boolean weSetPlaceholder = preflightCallback(audd, args);

        // 2. Mode 1 only: register the stream slot.
        int radioId = args.effectiveRadioId();
        if (args.mode == Mode.PROVISION) {
            audd.streams().add(new AddStreamRequest(args.url, radioId));
            System.err.println("subscribed: radio_id=" + radioId + " url=" + args.url);
        } else {
            System.err.println("listening to existing slot: radio_id=" + radioId);
        }

        // 3. Open the CSV (append; header only when fresh).
        Path output = args.output;
        boolean fresh = !Files.exists(output) || Files.size(output) == 0;
        BufferedWriter csv = Files.newBufferedWriter(output,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            csv.write(String.join(",", CSV_HEADER));
            csv.newLine();
            csv.flush();
        }

        // 4. Shutdown hook for clean teardown on Ctrl-C / SIGTERM.
        AtomicBoolean stopping = new AtomicBoolean(false);
        Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopping.set(true);
            mainThread.interrupt();
            // We can't safely call audd.streams().delete from here while another
            // request might be in flight, so the actual cleanup runs in main()
            // after the longpoll loop unwinds. Wait briefly for that.
            try { mainThread.join(5000); } catch (InterruptedException ignored) { }
        }, "audd-stream-shutdown"));

        // 5. Longpoll. Notification envelopes log to stderr; result envelopes
        //    write CSV rows. The {"timeout":"..."} no-events shape is silently
        //    skipped (it just means "no song this 50s window").
        String category = audd.streams().deriveLongpollCategory(radioId);
        System.err.println("longpolling category=" + category + " — Ctrl-C to stop");
        try {
            Iterator<JsonNode> events = audd.streams().longpoll(category,
                    LongpollOptions.builder().timeout(50).build());
            while (!stopping.get() && events.hasNext()) {
                JsonNode body;
                try {
                    body = events.next();
                } catch (RuntimeException re) {
                    if (stopping.get()) break;
                    System.err.println("longpoll error: " + re.getMessage());
                    continue;
                }
                handleEvent(body, radioId, csv, System.err);
            }
        } finally {
            csv.close();
            teardown(audd, args, weSetPlaceholder);
        }
    }

    /**
     * Returns true iff this run installed {@link #EMPTY_CALLBACK_URL} on the
     * account (so the exit notice can mention it). Mode 2 never installs.
     */
    static boolean preflightCallback(AudD audd, Args args) {
        String existing = null;
        boolean noneSet = false;
        try {
            existing = audd.streams().getCallbackUrl();
            if (existing == null || existing.isEmpty()) noneSet = true;
        } catch (AudDApiError exc) {
            if (exc.errorCode() == NO_CALLBACK_ERROR_CODE) {
                noneSet = true;
            } else {
                throw exc;
            }
        }

        if (args.mode == Mode.LISTEN_ONLY) {
            if (noneSet) {
                throw new AudDInvalidRequestError(0,
                        "stream slot exists but no callback URL is configured for this account; "
                        + "longpoll won't deliver. Set one first via streams.setCallbackUrl(...).",
                        0, null, java.util.Collections.emptyMap(), null, null, null);
            }
            // Real URL set — leave it alone. Mode 2 never mutates account state.
            return false;
        }

        // Mode 1 — provision-and-listen.
        if (noneSet) {
            audd.streams().setCallbackUrl(EMPTY_CALLBACK_URL);
            System.err.println("longpoll requires any 200-OK URL server-side; "
                    + "using audd.tech/empty/ as a default.");
            return true;
        }
        // Real URL is set — don't touch it.
        System.err.println("keeping existing callback URL: " + existing);
        return false;
    }

    /** Mode 1: delete the stream slot. Mode 2: no-op. Always logs. */
    static void teardown(AudD audd, Args args, boolean weSetPlaceholder) {
        if (args.mode == Mode.PROVISION) {
            try {
                audd.streams().delete(args.effectiveRadioId());
                System.err.println("deleted stream slot radio_id=" + args.effectiveRadioId());
            } catch (Exception e) {
                System.err.println("teardown: failed to delete radio_id="
                        + args.effectiveRadioId() + ": " + e.getMessage());
            }
            if (weSetPlaceholder) {
                System.err.println("left audd.tech/empty/ as your account callback — "
                        + "change it via setCallbackUrl(...) if needed.");
            }
        } else {
            System.err.println("listen-only: account state unchanged.");
        }
    }

    static void handleEvent(JsonNode body, int radioId, BufferedWriter csv, PrintStream err) throws IOException {
        if (body == null || !body.isObject()) return;
        if (body.has("timeout")) return;            // no events this window
        if (body.has("notification")) {
            JsonNode n = body.get("notification");
            err.printf("notification radio_id=%s code=%s %s%n",
                    text(n, "radio_id"),
                    text(n, "notification_code"),
                    text(n, "notification_message"));
            return;
        }
        // Result envelope: either at body.result or body itself.
        JsonNode inner = body.has("result") ? body.get("result") : body;
        JsonNode results = inner.path("results");
        if (!results.isArray()) return;
        String ts = Instant.now().atOffset(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        for (JsonNode entry : results) {
            csv.write(csvRow(
                    ts,
                    String.valueOf(radioId),
                    text(entry, "score"),
                    text(entry, "artist"),
                    text(entry, "title"),
                    text(entry, "album"),
                    text(entry, "song_link")));
            csv.newLine();
            csv.flush();
            err.printf("logged %s — %s (radio_id=%d)%n",
                    text(entry, "artist"), text(entry, "title"), radioId);
        }
    }

    /** Read a string field, returning "" for missing/null nodes. */
    private static String text(JsonNode parent, String field) {
        JsonNode v = parent == null ? null : parent.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) return "";
        return v.asText();
    }

    /** RFC 4180 minimal CSV: quote any field with comma/quote/CR/LF; double-quote internal quotes. */
    static String csvRow(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(quoteIfNeeded(fields[i]));
        }
        return sb.toString();
    }

    private static String quoteIfNeeded(String s) {
        if (s == null) return "";
        boolean needs = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needs) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    enum Mode { PROVISION, LISTEN_ONLY }

    static final class Args {
        final Mode mode;
        final String url;          // null in LISTEN_ONLY
        final Integer radioId;     // user-supplied; null if not given
        final Path output;

        private Args(Mode mode, String url, Integer radioId, Path output) {
            this.mode = mode;
            this.url = url;
            this.radioId = radioId;
            this.output = output;
        }

        int effectiveRadioId() { return radioId != null ? radioId : DEFAULT_RADIO_ID; }

        static Args parse(String[] argv) {
            String url = null;
            Integer radioId = null;
            Path output = Path.of(DEFAULT_OUTPUT);
            for (int i = 0; i < argv.length; i++) {
                String a = argv[i];
                switch (a) {
                    case "--url":
                        if (i + 1 >= argv.length) return null;
                        url = argv[++i];
                        break;
                    case "--radio-id":
                        if (i + 1 >= argv.length) return null;
                        try { radioId = Integer.parseInt(argv[++i]); }
                        catch (NumberFormatException nfe) { return null; }
                        break;
                    case "--output":
                        if (i + 1 >= argv.length) return null;
                        output = Path.of(argv[++i]);
                        break;
                    default:
                        return null;
                }
            }
            if (url == null && radioId == null) return null;
            // url present (with or without explicit radio-id) → PROVISION.
            // radio-id only → LISTEN_ONLY.
            Mode mode = url != null ? Mode.PROVISION : Mode.LISTEN_ONLY;
            return new Args(mode, url, radioId, output);
        }
    }
}

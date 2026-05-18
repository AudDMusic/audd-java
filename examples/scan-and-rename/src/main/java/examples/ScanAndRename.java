/*
 * Walk a folder of audio files, recognize each via the AudD API, write
 * recognition metadata into the file's tags, then rename to
 * "Artist - Title.ext". Default is dry-run; pass --apply to write tags
 * and rename.
 *
 *   mvn -q exec:java -Dexec.args="/path/to/folder"
 *   mvn -q exec:java -Dexec.args="/path/to/folder --apply"
 *   mvn -q exec:java -Dexec.args="/path/to/folder --apply --concurrency 8"
 *
 * Reads the API token from AUDD_API_TOKEN.
 */
package examples;

import io.audd.AudD;
import io.audd.models.RecognitionResult;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class ScanAndRename {

    private static final Set<String> AUDIO_EXTS = Set.of(
            ".mp3", ".flac", ".ogg", ".opus", ".m4a", ".mp4", ".wav", ".aac");

    private static final int MAX_BASE_LEN = 200;

    static {
        // jaudiotagger logs aggressively at INFO; quiet it so example output stays clean.
        Logger.getLogger("org.jaudiotagger").setLevel(Level.WARNING);
    }

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        if (parsed == null) {
            System.err.println("Usage: ScanAndRename <folder> [--apply] [--concurrency N]");
            System.exit(2);
            return;
        }

        List<Path> files = collectAudio(parsed.folder);
        if (files.isEmpty()) {
            System.out.println("no audio files found under " + parsed.folder);
            return;
        }
        System.out.printf("found %d audio file(s) under %s%n", files.size(), parsed.folder);
        if (!parsed.apply) {
            System.out.println("dry-run: no tags written, no files renamed. Pass --apply to commit changes.");
        }

        Stats stats = new Stats(files.size());
        ExecutorService pool = Executors.newFixedThreadPool(parsed.concurrency);
        // try-with-resources on AudD closes underlying OkHttp executor cleanly.
        try (AudD audd = AudD.fromEnvironment()) {
            List<Future<?>> futures = new ArrayList<>(files.size());
            AtomicInteger done = new AtomicInteger();
            for (Path file : files) {
                futures.add(pool.submit(() -> processFile(audd, file, parsed.apply, stats, done, files.size())));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // Per-task exceptions are already logged inside processFile;
                    // anything reaching here is genuinely unexpected.
                    System.err.println("worker error: " + e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        System.out.println();
        System.out.printf(
                "summary: scanned=%d matched=%d %s skipped=%d errored=%d%n",
                files.size(),
                stats.matched.get(),
                parsed.apply ? "renamed=" + stats.renamed.get() : "wouldRename=" + stats.wouldRename.get(),
                stats.skipped.get(),
                stats.errored.get());
        if (!parsed.apply && stats.matched.get() > 0) {
            System.out.println("re-run with --apply to write tags and rename.");
        }
    }

    private static void processFile(AudD audd, Path file, boolean apply, Stats stats,
                                    AtomicInteger done, int total) {
        int n = done.incrementAndGet();
        String prefix = String.format("[%d/%d] %s", n, total, file.getFileName());
        RecognitionResult result;
        try {
            result = audd.recognize(file);
        } catch (Exception e) {
            stats.errored.incrementAndGet();
            System.out.println(prefix + "  recognize error: " + e.getMessage());
            return;
        }
        if (result == null || (isBlank(result.artist()) && isBlank(result.title()))) {
            stats.skipped.incrementAndGet();
            System.out.println(prefix + "  no match");
            return;
        }

        stats.matched.incrementAndGet();
        String artist = nullToEmpty(result.artist()).trim();
        String title = nullToEmpty(result.title()).trim();
        String label = String.format("\"%s - %s\"", artist, title);

        if (!apply) {
            stats.wouldRename.incrementAndGet();
            System.out.println(prefix + "  would tag + rename to " + label);
            return;
        }

        try {
            // 1) Write tags into the original file (rename-after-tag is safer:
            //    if writing fails we keep the original filename for a retry).
            writeTags(file, result);
            // 2) Rename in place.
            Path target = renameTarget(file, artist, title);
            if (target.equals(file)) {
                System.out.println(prefix + "  tagged (already named correctly)");
                stats.renamed.incrementAndGet();
                return;
            }
            if (Files.exists(target)) {
                stats.skipped.incrementAndGet();
                System.out.println(prefix + "  skipped (target exists): " + target.getFileName());
                return;
            }
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            stats.renamed.incrementAndGet();
            System.out.println(prefix + "  tagged + renamed → " + label);
        } catch (Exception e) {
            stats.errored.incrementAndGet();
            System.out.println(prefix + "  apply error: " + e.getMessage());
        }
    }

    /** Write artist/title/album/year tags. Year is the first 4 chars of releaseDate (YYYY-MM-DD). */
    static void writeTags(Path file, RecognitionResult r) throws Exception {
        AudioFile af = AudioFileIO.read(file.toFile());
        Tag tag = af.getTagOrCreateAndSetDefault();
        if (!isBlank(r.artist())) tag.setField(FieldKey.ARTIST, r.artist());
        if (!isBlank(r.title()))  tag.setField(FieldKey.TITLE, r.title());
        if (!isBlank(r.album()))  tag.setField(FieldKey.ALBUM, r.album());
        String rd = r.releaseDate();
        if (rd != null && rd.length() >= 4) {
            tag.setField(FieldKey.YEAR, rd.substring(0, 4));
        }
        af.commit();
    }

    /** {@code dir/Artist - Title.ext}, with sanitized basename. */
    static Path renameTarget(Path file, String artist, String title) {
        Path dir = file.getParent();
        String ext = extOf(file.getFileName().toString());
        String base = sanitize(artist + " - " + title);
        return (dir == null ? Path.of(base + ext) : dir.resolve(base + ext));
    }

    /** Replace {@code / \ : * ? " < > |} (and control chars) with {@code _}; trim to 200 chars. */
    static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || "/\\:*?\"<>|".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        if (out.length() > MAX_BASE_LEN) out = out.substring(0, MAX_BASE_LEN);
        if (out.isEmpty()) out = "untitled";
        return out;
    }

    static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    static List<Path> collectAudio(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> AUDIO_EXTS.contains(extOf(p.getFileName().toString())))
                    .forEach(out::add);
        } catch (IOException e) {
            System.err.println("walk error: " + e.getMessage());
        }
        return out;
    }

    static boolean isBlank(String s) { return s == null || s.isEmpty(); }
    static String nullToEmpty(String s) { return s == null ? "" : s; }

    static final class Stats {
        final AtomicInteger matched = new AtomicInteger();
        final AtomicInteger wouldRename = new AtomicInteger();
        final AtomicInteger renamed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger errored = new AtomicInteger();
        Stats(int total) { /* total is informational; not stored. */ }
    }

    static final class Args {
        final Path folder;
        final boolean apply;
        final int concurrency;

        private Args(Path folder, boolean apply, int concurrency) {
            this.folder = folder;
            this.apply = apply;
            this.concurrency = concurrency;
        }

        static Args parse(String[] argv) {
            Path folder = null;
            boolean apply = false;
            int concurrency = 4;
            for (int i = 0; i < argv.length; i++) {
                String a = argv[i];
                switch (a) {
                    case "--apply":
                        apply = true;
                        break;
                    case "--concurrency":
                        if (i + 1 >= argv.length) return null;
                        try { concurrency = Math.max(1, Integer.parseInt(argv[++i])); }
                        catch (NumberFormatException nfe) { return null; }
                        break;
                    default:
                        if (a.startsWith("--")) return null;
                        if (folder != null) return null;
                        folder = Path.of(a);
                }
            }
            if (folder == null || !Files.isDirectory(folder)) return null;
            return new Args(folder, apply, concurrency);
        }
    }
}

# scan-and-rename

Walks a folder of audio files, recognizes each via the AudD API, writes the
match into the file's tags, and renames it to `Artist - Title.ext`.

```sh
export AUDD_API_TOKEN=aud_xxx
mvn -q exec:java -Dexec.args="/path/to/folder"                     # dry-run (default)
mvn -q exec:java -Dexec.args="/path/to/folder --apply"             # actually tag + rename
mvn -q exec:java -Dexec.args="/path/to/folder --apply --concurrency 8"
```

What it does:

- Walks the folder recursively. Picks up `.mp3 .flac .ogg .opus .m4a .mp4 .wav .aac`.
- Calls `audd.recognize(path)` once per file, with a fixed-size `ExecutorService`
  (default 4 threads) bounding parallelism.
- On a match, sanitizes `Artist - Title` (replaces `/ \ : * ? " < > |` with
  `_`, trims to 200 chars) and renames the file in place. Skips on collision.
- Writes ARTIST / TITLE / ALBUM / YEAR tags via
  [jaudiotagger](https://www.jthink.net/jaudiotagger/), which handles ID3v2,
  Vorbis Comments, MP4 atoms, and WAV INFO uniformly.
- Prints `[3/27] foo.mp3  tagged + renamed → "Artist - Title"` per file and a
  summary at the end.

`--apply` is the destructive mode — it mutates tag bytes and renames files.
The default dry-run prints what it _would_ do; verify the output before adding
`--apply`.

`AudD.fromEnvironment()` reads `AUDD_API_TOKEN`. Get one at
[dashboard.audd.io](https://dashboard.audd.io/).

This example has its own `pom.xml` so jaudiotagger doesn't pollute the SDK
module's dependency graph. **jaudiotagger is licensed LGPL 2.1**; if your
project links against this example's compiled output, that license applies to
your distribution. The SDK itself (`io.audd:audd`) stays MIT.

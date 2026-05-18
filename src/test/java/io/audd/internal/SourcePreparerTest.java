package io.audd.internal;

import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePreparerTest {

    private static String bodyToString(RequestBody body) throws Exception {
        Buffer b = new Buffer();
        body.writeTo(b);
        return b.readUtf8();
    }

    @Test
    void httpsUrl_detectedAsUrlSource() throws Exception {
        SourcePreparer.Prepared p = SourcePreparer.prepare("https://example.com/audio.mp3");
        assertThat(p.isUrl()).isTrue();
        assertThat(p.urlField()).isEqualTo("https://example.com/audio.mp3");
        assertThat(p.reopener()).isNull();
    }

    @Test
    void httpUrl_detectedAsUrlSource() throws Exception {
        SourcePreparer.Prepared p = SourcePreparer.prepare("http://example.com/audio.mp3");
        assertThat(p.isUrl()).isTrue();
    }

    @Test
    void existingFilePath_aStringIsTreatedAsFile() throws Exception {
        Path tmp = Files.createTempFile("audd-test", ".bin");
        Files.writeString(tmp, "hello");
        try {
            SourcePreparer.Prepared p = SourcePreparer.prepare(tmp.toString());
            assertThat(p.isUrl()).isFalse();
            assertThat(p.reopener()).isNotNull();
            RequestBody b = p.reopener().open();
            assertThat(bodyToString(b)).isEqualTo("hello");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void nonExistingNonUrl_string_throwsHelpfulError() {
        assertThatThrownBy(() -> SourcePreparer.prepare("not-a-url-or-file"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not an HTTP URL")
            .hasMessageContaining("not an existing file path");
    }

    @Test
    void path_returnsBodyWithFileContents() throws Exception {
        Path tmp = Files.createTempFile("audd-test", ".bin");
        Files.writeString(tmp, "hello world");
        try {
            SourcePreparer.Prepared p = SourcePreparer.prepare(tmp);
            assertThat(p.isUrl()).isFalse();
            assertThat(bodyToString(p.reopener().open())).isEqualTo("hello world");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void file_returnsBodyWithFileContents() throws Exception {
        Path tmp = Files.createTempFile("audd-test", ".bin");
        Files.writeString(tmp, "file-data");
        try {
            File f = tmp.toFile();
            SourcePreparer.Prepared p = SourcePreparer.prepare(f);
            assertThat(bodyToString(p.reopener().open())).isEqualTo("file-data");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void byteArray_isClonedAndRewindableAcrossAttempts() throws Exception {
        byte[] orig = new byte[]{'a', 'b', 'c'};
        SourcePreparer.Prepared p = SourcePreparer.prepare(orig);
        // mutate original; the prepared body must not see the mutation
        orig[0] = 'X';
        // open multiple times — each call yields a fresh body
        RequestBody b1 = p.reopener().open();
        RequestBody b2 = p.reopener().open();
        assertThat(bodyToString(b1)).isEqualTo("abc");
        assertThat(bodyToString(b2)).isEqualTo("abc");
    }

    @Test
    void inputStream_bufferedOnceAndReusable() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream("stream-bytes".getBytes());
        SourcePreparer.Prepared p = SourcePreparer.prepare(in);
        // Multiple opens produce identical fresh bodies.
        assertThat(bodyToString(p.reopener().open())).isEqualTo("stream-bytes");
        assertThat(bodyToString(p.reopener().open())).isEqualTo("stream-bytes");
    }

    @Test
    void unsupportedType_throws() {
        assertThatThrownBy(() -> SourcePreparer.prepare(123))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported source type");
    }

    @Test
    void nullSource_throws() {
        assertThatThrownBy(() -> SourcePreparer.prepare(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

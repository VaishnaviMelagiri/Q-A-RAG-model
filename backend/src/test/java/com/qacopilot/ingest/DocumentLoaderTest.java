package com.qacopilot.ingest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loader accepts only the allow-listed extensions (pdf, html/htm, md/markdown, txt) and
 * rejects anything else with a clear {@link IllegalArgumentException} — instead of silently
 * decoding a binary blob as UTF-8 and polluting the corpus with garbage.
 */
class DocumentLoaderTest {

    private final DocumentLoader loader = new DocumentLoader();

    @Test
    void txt_loadsAsTextVerbatim() {
        RawDoc doc = loader.load("notes.txt", "hello world".getBytes(StandardCharsets.UTF_8));
        assertThat(doc.sourceType()).isEqualTo("text");
        assertThat(doc.text()).isEqualTo("hello world");
    }

    @Test
    void markdown_bothExtensions_loadAsMarkdownVerbatim() {
        assertThat(loader.load("a.md", "# Title".getBytes(StandardCharsets.UTF_8)).sourceType())
                .isEqualTo("markdown");
        assertThat(loader.load("a.markdown", "# Title".getBytes(StandardCharsets.UTF_8)).sourceType())
                .isEqualTo("markdown");
    }

    @Test
    void html_bothExtensions_extractVisibleText() {
        byte[] html = "<html><body><p>Hi <b>there</b></p></body></html>".getBytes(StandardCharsets.UTF_8);
        RawDoc doc = loader.load("page.html", html);
        assertThat(doc.sourceType()).isEqualTo("html");
        assertThat(doc.text()).isEqualTo("Hi there");
        assertThat(loader.load("page.htm", html).sourceType()).isEqualTo("html");
    }

    @Test
    void unsupportedExtension_isRejectedWithClearMessage() {
        assertThatThrownBy(() -> loader.load("resume.docx", new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type")
                .hasMessageContaining("resume.docx")
                .hasMessageContaining("pdf, html/htm, md/markdown, txt");
    }

    @Test
    void noExtension_isRejected() {
        assertThatThrownBy(() -> loader.load("README", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");
    }
}

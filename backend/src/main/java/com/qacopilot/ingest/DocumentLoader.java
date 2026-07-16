package com.qacopilot.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Source-agnostic loader: turns raw bytes of any supported document into plain text.
 * Type is detected from the filename extension against a fixed allow-list — pdf, html/htm,
 * md/markdown, txt. Markdown and plain text are kept as-is; PDF and HTML are extracted to
 * readable text. Any other extension is rejected with an {@link IllegalArgumentException}
 * (surfaced as HTTP 400) rather than blindly decoded as UTF-8 — binary blobs (e.g. .docx,
 * .png) would otherwise produce garbage "text" and pollute the corpus.
 *
 * <p>This is deliberately corpus-agnostic — it accepts whatever real documents the user
 * provides in a supported format and never fabricates or supplements content.
 */
@Component
public class DocumentLoader {

    public RawDoc load(String filename, byte[] bytes) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return new RawDoc(filename, "pdf", extractPdf(bytes));
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            String html = new String(bytes, StandardCharsets.UTF_8);
            return new RawDoc(filename, "html", Jsoup.parse(html).text());
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return new RawDoc(filename, "markdown", new String(bytes, StandardCharsets.UTF_8));
        }
        if (lower.endsWith(".txt")) {
            return new RawDoc(filename, "text", new String(bytes, StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException(
                "Unsupported file type: '" + (filename == null ? "(no name)" : filename)
                + "'. Supported types are: pdf, html/htm, md/markdown, txt.");
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract PDF text", e);
        }
    }
}

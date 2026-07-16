package com.qacopilot.ingest;

import com.qacopilot.embedding.EmbeddingClient;
import com.qacopilot.retrieval.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end ingestion: load → chunk → embed (real model) → store vectors.
 * Corpus-agnostic; operates on whatever real document bytes it is given.
 *
 * <p><b>Atomic replace-on-upload.</b> All the slow, failure-prone work (extraction + embedding)
 * happens first, into in-memory {@link PreparedDocument}s. Only once every file is prepared do we
 * persist in a SINGLE transaction that clears the old corpus and inserts the new one. So a failure
 * mid-batch — a provider error, a network blip on file 2 of 3 — throws before anything is cleared
 * or written, leaving the previously-loaded corpus fully intact.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final DocumentLoader loader;
    private final Chunker chunker;
    private final EmbeddingClient embeddings;
    private final ChunkStore store;

    public IngestionService(DocumentLoader loader, Chunker chunker,
                            EmbeddingClient embeddings, ChunkStore store) {
        this.loader = loader;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.store = store;
    }

    /**
     * Replace the corpus with the given files, atomically. Every file is fully loaded, chunked, and
     * embedded in memory first; if any of that fails, this throws and the existing corpus is
     * untouched (nothing cleared, nothing written). Only after all files are prepared is the store
     * replaced in one transaction.
     */
    public List<IngestResult> ingestAll(List<SourceFile> files) {
        List<PreparedDocument> prepared = new ArrayList<>(files.size());
        for (SourceFile file : files) {
            prepared.add(prepare(file.filename(), file.bytes()));
        }
        // All slow work succeeded; now the only DB touch — a single transactional clear + insert.
        store.replaceAll(prepared);
        return prepared.stream()
                .map(p -> new IngestResult(p.sourceName(), p.sourceType(), p.chunks().size()))
                .toList();
    }

    /** Load → chunk → embed a single file into an in-memory prepared document. No DB writes. */
    private PreparedDocument prepare(String filename, byte[] bytes) {
        RawDoc doc = loader.load(filename, bytes);
        List<Chunk> chunks = chunker.chunk(doc.text());
        if (chunks.isEmpty()) {
            log.warn("No text extracted from '{}' — will store no chunks for it", filename);
            return new PreparedDocument(doc.sourceName(), doc.sourceType(), List.of(), List.of());
        }
        List<String> texts = chunks.stream().map(Chunk::content).toList();
        List<float[]> vectors = embeddings.embedDocuments(texts);
        return new PreparedDocument(doc.sourceName(), doc.sourceType(), chunks, vectors);
    }

    /** A single uploaded file's raw input, decoupled from the web layer's MultipartFile. */
    public record SourceFile(String filename, byte[] bytes) {}

    public record IngestResult(String sourceName, String sourceType, int chunksIngested) {}
}

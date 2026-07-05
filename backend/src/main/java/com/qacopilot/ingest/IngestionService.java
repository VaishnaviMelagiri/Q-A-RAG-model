package com.qacopilot.ingest;

import com.qacopilot.embedding.EmbeddingClient;
import com.qacopilot.retrieval.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * End-to-end ingestion: load → chunk → embed (real model) → store vectors.
 * Corpus-agnostic; operates on whatever real document bytes it is given.
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

    public IngestResult ingest(String filename, byte[] bytes) {
        RawDoc doc = loader.load(filename, bytes);
        List<Chunk> chunks = chunker.chunk(doc.text());
        if (chunks.isEmpty()) {
            log.warn("No text extracted from '{}' — nothing to ingest", filename);
            return new IngestResult(doc.sourceName(), doc.sourceType(), 0);
        }

        List<String> texts = chunks.stream().map(Chunk::content).toList();
        List<float[]> vectors = embeddings.embedDocuments(texts);

        long documentId = store.insertDocument(doc.sourceName(), doc.sourceType());
        store.insertChunks(documentId, chunks, vectors);

        log.info("Ingested '{}' ({}) as {} chunks", doc.sourceName(), doc.sourceType(), chunks.size());
        return new IngestResult(doc.sourceName(), doc.sourceType(), chunks.size());
    }

    public record IngestResult(String sourceName, String sourceType, int chunksIngested) {}
}

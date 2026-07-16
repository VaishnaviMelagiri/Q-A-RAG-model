package com.qacopilot.ingest;

import com.qacopilot.embedding.EmbeddingClient;
import com.qacopilot.ingest.IngestionService.IngestResult;
import com.qacopilot.ingest.IngestionService.SourceFile;
import com.qacopilot.retrieval.ChunkStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Atomic ingest: all extraction/embedding happens in memory first, and the corpus is replaced in a
 * single transactional store call. A failure mid-batch must therefore never touch the store — so
 * the previously-loaded corpus is left fully intact and no orphan document rows are written.
 */
class IngestionServiceTest {

    private final DocumentLoader loader = mock(DocumentLoader.class);
    private final Chunker chunker = mock(Chunker.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final ChunkStore store = mock(ChunkStore.class);
    private final IngestionService service = new IngestionService(loader, chunker, embeddings, store);

    private static final List<Chunk> ONE_CHUNK = List.of(new Chunk(0, "body", 0, 4));
    private static final List<float[]> ONE_VEC = List.of(new float[]{0.1f, 0.2f});

    @Test
    void embeddingFailureMidBatch_leavesCorpusUntouched_andWritesNothing() {
        when(loader.load(any(), any())).thenReturn(new RawDoc("f", "text", "body"));
        when(chunker.chunk(any())).thenReturn(ONE_CHUNK);
        // File 1 embeds fine; file 2 fails (e.g. a provider 500 / network blip mid-batch).
        when(embeddings.embedDocuments(anyList()))
                .thenReturn(ONE_VEC)
                .thenThrow(new RuntimeException("upstream embedding error"));

        List<SourceFile> files = List.of(
                new SourceFile("a.txt", "aaa".getBytes()),
                new SourceFile("b.txt", "bbb".getBytes()));

        assertThatThrownBy(() -> service.ingestAll(files))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("upstream embedding error");

        // The failure happened during in-memory preparation, before any persistence: the store was
        // never cleared and nothing was written, so the prior corpus is intact and no orphan rows.
        verifyNoInteractions(store);
    }

    @Test
    void happyPath_persistsAtomicallyInOneReplaceCall_andReportsCounts() {
        when(loader.load(any(), any()))
                .thenReturn(new RawDoc("a.txt", "text", "body"))
                .thenReturn(new RawDoc("b.txt", "text", "body"));
        when(chunker.chunk(any())).thenReturn(ONE_CHUNK);
        when(embeddings.embedDocuments(anyList())).thenReturn(ONE_VEC);

        List<SourceFile> files = List.of(
                new SourceFile("a.txt", "aaa".getBytes()),
                new SourceFile("b.txt", "bbb".getBytes()));

        List<IngestResult> results = service.ingestAll(files);

        // Exactly one transactional replace (clear + insert), not a clear followed by N inserts.
        verify(store, times(1)).replaceAll(anyList());
        verify(store, times(0)).clearAll();
        assertThat(results).hasSize(2);
        assertThat(results.get(0).sourceName()).isEqualTo("a.txt");
        assertThat(results.get(0).chunksIngested()).isEqualTo(1);
    }
}

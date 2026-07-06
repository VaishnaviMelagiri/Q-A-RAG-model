package com.qacopilot.api;

import com.qacopilot.retrieval.ChunkStore;
import com.qacopilot.retrieval.ChunkStore.CorpusSource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Corpus management: see what is currently loaded, and clear it to start fresh.
 *
 * <p>Ingest is <b>replace-on-upload</b> (POST /api/ingest clears then loads), so the store always
 * holds exactly one current document set. This endpoint provides the explicit "clear now" control
 * and the visibility that makes it unambiguous what the store currently contains.
 *
 * <pre>
 *   curl http://localhost:8080/api/corpus                 # what's loaded (per source)
 *   curl -X DELETE http://localhost:8080/api/corpus       # clear everything
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class CorpusController {

    private final ChunkStore store;

    public CorpusController(ChunkStore store) {
        this.store = store;
    }

    /** What is currently loaded: per-source chunk counts + totals. */
    @GetMapping("/corpus")
    public CorpusResponse corpus() {
        List<CorpusSource> sources = store.corpusSources();
        long totalChunks = sources.stream().mapToLong(CorpusSource::chunks).sum();
        return new CorpusResponse(sources.size(), totalChunks, sources);
    }

    /** Clear ALL documents and chunks (empty the current set without uploading a replacement). */
    @DeleteMapping("/corpus")
    public CorpusResponse clear() {
        store.clearAll();
        return new CorpusResponse(0, 0, List.of());
    }

    private record CorpusResponse(int documents, long totalChunks, List<CorpusSource> sources) {}
}

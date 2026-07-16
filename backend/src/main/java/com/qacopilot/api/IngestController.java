package com.qacopilot.api;

import com.qacopilot.config.RagProperties;
import com.qacopilot.ingest.IngestionService;
import com.qacopilot.ingest.IngestionService.IngestResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingestion endpoint. Accepts one or more uploaded documents (PDF/MD/TXT/HTML) and runs the
 * load → chunk → embed → store pipeline. Source-agnostic: whatever real files are uploaded.
 *
 * <p><b>Replace-on-upload (single-session demo model):</b> each upload REPLACES the corpus — the
 * store is cleared first, then the uploaded set is loaded — so there is only ever one current
 * document set and answers never draw on previously-loaded data. The batch of files in a single
 * request becomes the new corpus. (See ARCHITECTURE.md "Corpus management" for the scope +
 * per-session-isolation extension path.)
 *
 * <pre>
 *   curl -F "files=@OS.pdf" http://localhost:8080/api/ingest
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class IngestController {

    private final IngestionService ingestion;
    private final RagProperties props;

    public IngestController(IngestionService ingestion, RagProperties props) {
        this.ingestion = ingestion;
        this.props = props;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestParam("files") List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body("No files uploaded (use form field 'files').");
        }
        // Validate every file BEFORE clearing/ingesting anything, so a single bad file in the batch
        // (over-size / unsupported type) fails the whole request without wiping the existing corpus.
        long maxBytes = props.getIngest().getMaxFileBytes();
        for (MultipartFile file : files) {
            if (file.getSize() > maxBytes) {
                throw new IllegalArgumentException(
                        "File '" + file.getOriginalFilename() + "' is " + file.getSize()
                        + " bytes, over the " + maxBytes + "-byte per-file limit (rag.ingest.max-file-bytes).");
            }
        }
        // Replace-on-upload: wipe the current set first so this upload stands alone. Done AFTER the
        // guards so a bad request never clears an existing corpus.
        ingestion.clearCorpus();
        List<IngestResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(ingestion.ingest(file.getOriginalFilename(), file.getBytes()));
        }
        int totalChunks = results.stream().mapToInt(IngestResult::chunksIngested).sum();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IngestResponse(results.size(), totalChunks, results));
    }

    private record IngestResponse(int documents, int totalChunks, List<IngestResult> details) {}
}

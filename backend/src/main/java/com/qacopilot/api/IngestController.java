package com.qacopilot.api;

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
 * <pre>
 *   curl -F "files=@OS.pdf" http://localhost:8080/api/ingest
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class IngestController {

    private final IngestionService ingestion;

    public IngestController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestParam("files") List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body("No files uploaded (use form field 'files').");
        }
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

package com.qacopilot.api;

import com.qacopilot.config.RagProperties;
import com.qacopilot.ingest.IngestionService;
import com.qacopilot.pipeline.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Input bounds at the HTTP edge: a blank or oversize question is a 400 before the pipeline runs,
 * and an over-size upload is a 400 before the corpus is touched. Validation short-circuits so the
 * downstream services are never invoked on bad input.
 */
class InputValidationTest {

    private QueryService queryService;
    private IngestionService ingestion;
    private MockMvc queryMvc;
    private MockMvc ingestMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(QueryService.class);
        ingestion = mock(IngestionService.class);

        // Tiny per-file cap so the over-size test needs only a few bytes, not 10 MB.
        RagProperties props = new RagProperties();
        props.getIngest().setMaxFileBytes(10);

        queryMvc = MockMvcBuilders.standaloneSetup(new QueryController(queryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        ingestMvc = MockMvcBuilders.standaloneSetup(new IngestController(ingestion, props))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void blankQuestion_returns400_andPipelineNeverRuns() throws Exception {
        queryMvc.perform(post("/api/query")
                        .contentType("application/json")
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("question must not be blank"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        verifyNoInteractions(queryService);
    }

    @Test
    void missingQuestion_returns400() throws Exception {
        queryMvc.perform(post("/api/query")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("question must not be blank"));
        verifyNoInteractions(queryService);
    }

    @Test
    void oversizeQuestion_returns400() throws Exception {
        String tooLong = "a".repeat(4001);
        queryMvc.perform(post("/api/query")
                        .contentType("application/json")
                        .content("{\"question\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("question must be at most 4000 characters"));
        verifyNoInteractions(queryService);
    }

    @Test
    void oversizeFile_returns400_andCorpusIsNotCleared() throws Exception {
        // 11 bytes > the 10-byte test cap.
        MockMultipartFile big = new MockMultipartFile(
                "files", "big.txt", "text/plain", "hello world".getBytes());
        ingestMvc.perform(multipart("/api/ingest").file(big))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("per-file limit")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        // Guard runs before clearCorpus/ingest, so a bad upload never wipes the existing corpus.
        verifyNoInteractions(ingestion);
    }
}

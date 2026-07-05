-- Milestone 1 schema: documents + their embedded chunks.
-- Requires the pgvector extension (provided by the pgvector/pgvector image).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id          BIGSERIAL   PRIMARY KEY,
    source_name TEXT        NOT NULL,
    source_type TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chunks (
    id           BIGSERIAL   PRIMARY KEY,
    document_id  BIGINT      NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index  INT         NOT NULL,      -- 0-based order within the document
    content      TEXT        NOT NULL,      -- the exact excerpt returned in citations
    start_offset INT         NOT NULL,      -- char offset into the source text (for citations)
    end_offset   INT         NOT NULL,
    embedding    vector(1024) NOT NULL,     -- mistral-embed dimension (1024)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Approximate-nearest-neighbour index for cosine distance (<=>).
-- HNSW gives fast, high-recall search; cosine matches how we score similarity.
CREATE INDEX chunks_embedding_idx
    ON chunks USING hnsw (embedding vector_cosine_ops);

CREATE INDEX chunks_document_id_idx ON chunks (document_id);

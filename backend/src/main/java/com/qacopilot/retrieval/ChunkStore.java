package com.qacopilot.retrieval;

import com.qacopilot.ingest.Chunk;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Persistence for documents and their embedded chunks, and cosine-similarity search over
 * pgvector. Uses explicit SQL (rather than an ORM) so the vector type and the {@code <=>}
 * distance operator are handled directly and transparently.
 */
@Repository
public class ChunkStore {

    private final NamedParameterJdbcTemplate jdbc;

    public ChunkStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a document row and return its generated id. */
    public long insertDocument(String sourceName, String sourceType) {
        KeyHolder keys = new GeneratedKeyHolder();
        var params = new MapSqlParameterSource()
                .addValue("name", sourceName)
                .addValue("type", sourceType);
        jdbc.update(
                "INSERT INTO documents (source_name, source_type) VALUES (:name, :type)",
                params, keys, new String[]{"id"});
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    /** Persist all chunks of a document with their embeddings, in one transaction. */
    @Transactional
    public void insertChunks(long documentId, List<Chunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks/embeddings size mismatch");
        }
        String sql = """
                INSERT INTO chunks
                    (document_id, chunk_index, content, start_offset, end_offset, embedding)
                VALUES
                    (:docId, :idx, :content, :start, :end, CAST(:embedding AS vector))
                """;
        MapSqlParameterSource[] batch = new MapSqlParameterSource[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("docId", documentId)
                    .addValue("idx", c.index())
                    .addValue("content", c.content())
                    .addValue("start", c.startOffset())
                    .addValue("end", c.endOffset())
                    .addValue("embedding", toVectorLiteral(embeddings.get(i)));
        }
        jdbc.batchUpdate(sql, batch);
    }

    /** Top-k chunks by cosine similarity to the query embedding. */
    public List<ScoredChunk> searchTopK(float[] queryEmbedding, int k) {
        String sql = """
                SELECT c.id, c.document_id, d.source_name, c.chunk_index, c.content,
                       c.start_offset, c.end_offset,
                       1 - (c.embedding <=> CAST(:q AS vector)) AS similarity
                FROM chunks c
                JOIN documents d ON d.id = c.document_id
                ORDER BY c.embedding <=> CAST(:q AS vector)
                LIMIT :k
                """;
        var params = new MapSqlParameterSource()
                .addValue("q", toVectorLiteral(queryEmbedding))
                .addValue("k", k);
        return jdbc.query(sql, params, (rs, rowNum) -> new ScoredChunk(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getString("source_name"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getInt("start_offset"),
                rs.getInt("end_offset"),
                rs.getDouble("similarity")));
    }

    public long countChunks() {
        Long n = jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM chunks", Long.class);
        return n == null ? 0 : n;
    }

    /** Per-source breakdown of what is currently loaded (one row per ingested document). */
    public List<CorpusSource> corpusSources() {
        String sql = """
                SELECT d.id, d.source_name, d.source_type, d.created_at,
                       count(c.id) AS chunk_count
                FROM documents d
                LEFT JOIN chunks c ON c.document_id = d.id
                GROUP BY d.id, d.source_name, d.source_type, d.created_at
                ORDER BY d.created_at, d.id
                """;
        return jdbc.getJdbcTemplate().query(sql, (rs, rowNum) -> new CorpusSource(
                rs.getLong("id"),
                rs.getString("source_name"),
                rs.getString("source_type"),
                rs.getLong("chunk_count"),
                rs.getTimestamp("created_at").toInstant().toString()));
    }

    /**
     * Remove ALL documents and chunks (start fresh). TRUNCATE cascades to {@code chunks} via the
     * FK and resets id sequences; it is transactional in Postgres.
     */
    @Transactional
    public void clearAll() {
        jdbc.getJdbcTemplate().execute("TRUNCATE documents RESTART IDENTITY CASCADE");
    }

    /**
     * @param documentId internal document id
     * @param sourceName original filename / source
     * @param sourceType detected type (pdf/md/txt/html)
     * @param chunks     number of chunks stored for this document
     * @param createdAt  ISO-8601 ingest timestamp
     */
    public record CorpusSource(long documentId, String sourceName, String sourceType,
                               long chunks, String createdAt) {}

    /** Render a float[] as pgvector's text form: {@code [f1,f2,...]}. */
    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}

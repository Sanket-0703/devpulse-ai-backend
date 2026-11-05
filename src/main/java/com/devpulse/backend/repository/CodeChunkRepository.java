package com.devpulse.backend.repository;

import com.devpulse.backend.codeindex.CodeChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The code_chunks table, in plain SQL: JPA has no type for pgvector's
 * {@code vector}, and the queries are short. A vector is sent as text,
 * e.g. {@code '[0.12,-0.03,…]'}, and cast with {@code ::vector}.
 *
 * <p>{@code <=>} is pgvector's cosine distance (0 = same direction, 2 =
 * opposite). Ordering by it uses the HNSW index: an approximate nearest-
 * neighbour search that doesn't compare against every row.
 */
@Repository
public class CodeChunkRepository {

    private final JdbcTemplate jdbc;

    public CodeChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored chunk ready to insert: the chunk, the hash of its embedded text, its vector. */
    public record NewChunk(CodeChunk chunk, String contentHash, float[] embedding) {
    }

    /** A search hit. {@code score} is the cosine similarity: 1 = same meaning, 0 = unrelated. */
    public record Hit(Long repositoryId, String repositoryFullName, String repositoryHtmlUrl, String commitSha,
                      String path, int startLine, int endLine, String language, String content, double score) {
    }

    /** Replaces all chunks of a repository (call inside a transaction: readers see old or new, never half). */
    public void replaceAll(long repositoryId, List<NewChunk> chunks) {
        jdbc.update("DELETE FROM code_chunks WHERE repository_id = ?", repositoryId);
        jdbc.batchUpdate("""
                        INSERT INTO code_chunks
                            (repository_id, path, start_line, end_line, language, content, content_hash, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector)
                        """,
                chunks, 500, (ps, chunk) -> {
                    ps.setLong(1, repositoryId);
                    ps.setString(2, chunk.chunk().path());
                    ps.setInt(3, chunk.chunk().startLine());
                    ps.setInt(4, chunk.chunk().endLine());
                    ps.setString(5, chunk.chunk().language());
                    ps.setString(6, chunk.chunk().content());
                    ps.setString(7, chunk.contentHash());
                    ps.setString(8, vectorLiteral(chunk.embedding()));
                });
    }

    /** The user's chunks closest in meaning to the vector, optionally in one repository. */
    public List<Hit> search(long userId, Long repositoryId, float[] vector, int limit) {
        String vectorText = vectorLiteral(vector);
        String sql = """
                SELECT c.repository_id, r.full_name, r.html_url, r.indexed_commit_sha, c.path, c.start_line,
                       c.end_line, c.language, c.content, 1 - (c.embedding <=> ?::vector) AS score
                FROM code_chunks c
                JOIN github_repositories r ON r.id = c.repository_id
                JOIN github_accounts a ON a.id = r.github_account_id
                WHERE a.user_id = ?
                """ + (repositoryId == null ? "" : " AND c.repository_id = ?") + """

                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """;
        Object[] args = repositoryId == null
                ? new Object[]{vectorText, userId, vectorText, limit}
                : new Object[]{vectorText, userId, repositoryId, vectorText, limit};
        return jdbc.query(sql, (rs, row) -> new Hit(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9),
                rs.getDouble(10)), args);
    }

    /** One repository's chunks closest to the vector (for review context). */
    public List<Hit> nearest(long repositoryId, float[] vector, int limit) {
        String vectorText = vectorLiteral(vector);
        return jdbc.query("""
                        SELECT c.repository_id, NULL, NULL, NULL, c.path, c.start_line, c.end_line, c.language,
                               c.content, 1 - (c.embedding <=> ?::vector)
                        FROM code_chunks c
                        WHERE c.repository_id = ?
                        ORDER BY c.embedding <=> ?::vector
                        LIMIT ?
                        """,
                (rs, row) -> new Hit(rs.getLong(1), null, null, null, rs.getString(5), rs.getInt(6), rs.getInt(7),
                        rs.getString(8), rs.getString(9), rs.getDouble(10)),
                vectorText, repositoryId, vectorText, limit);
    }

    public int countByRepository(long repositoryId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE repository_id = ?",
                Integer.class, repositoryId);
        return count == null ? 0 : count;
    }

    /** pgvector's text format: [1.0,2.5,-0.3] */
    static String vectorLiteral(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }
}

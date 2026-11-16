package com.dra.infra.persistence;

import com.dra.common.hash.Hashing;
import com.dra.domain.model.FetchedSource;
import com.dra.domain.model.SourceContent;
import com.dra.domain.port.SourceRepository;
import com.dra.domain.util.UrlCanonicalizer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Repository
public class JdbcSourceRepository implements SourceRepository {

    private final JdbcClient jdbc;

    public JdbcSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(FetchedSource source) {
        String canonicalUrl = canonicalUrl(source.url());
        String urlHash = Hashing.sha256Hex(canonicalUrl);
        String domain = domainOf(canonicalUrl);

        jdbc.sql("""
                INSERT INTO source
                    (id, task_id, url, url_hash, canonical_url, domain, title, http_status,
                     content_hash, content_len, fetch_status, fetched_at)
                VALUES
                    (:id, :taskId, :url, :urlHash, :canonicalUrl, :domain, :title, :httpStatus,
                     :contentHash, :contentLen, :fetchStatus, NOW(3))
                ON DUPLICATE KEY UPDATE
                    title = VALUES(title),
                    http_status = VALUES(http_status),
                    content_hash = VALUES(content_hash),
                    content_len = VALUES(content_len),
                    fetch_status = VALUES(fetch_status),
                    fetched_at = VALUES(fetched_at)
                """)
                .param("id", source.sourceId())
                .param("taskId", source.taskId())
                .param("url", source.url())
                .param("urlHash", urlHash)
                .param("canonicalUrl", canonicalUrl)
                .param("domain", domain)
                .param("title", source.title())
                .param("httpStatus", source.ok() ? 200 : 0)
                .param("contentHash", source.contentHash())
                .param("contentLen", source.contentLength())
                .param("fetchStatus", source.ok() ? "OK" : "FAILED")
                .update();

        if (source.ok() && source.content() != null) {
            jdbc.sql("""
                    INSERT INTO source_content (source_id, content_gzip, content_hash, created_at)
                    VALUES (:sourceId, :content, :contentHash, NOW(3))
                    ON DUPLICATE KEY UPDATE
                        content_gzip = VALUES(content_gzip),
                        content_hash = VALUES(content_hash)
                    """)
                    .param("sourceId", source.sourceId())
                    .param("content", gzip(source.content()))
                    .param("contentHash", source.contentHash())
                    .update();
        }
    }

    @Override
    public Optional<SourceContent> findContent(String sourceId) {
        return jdbc.sql("""
                SELECT s.id, s.url, s.title, c.content_gzip
                  FROM source s
                  JOIN source_content c ON c.source_id = s.id
                 WHERE s.id = :id
                """)
                .param("id", sourceId)
                .query((rs, rowNum) -> new SourceContent(
                        rs.getString("id"),
                        rs.getString("url"),
                        rs.getString("title"),
                        gunzip(rs.getBytes("content_gzip"))))
                .optional();
    }

    private static String canonicalUrl(String url) {        try {
            return UrlCanonicalizer.canonicalize(url);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String domainOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] gzip(String content) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot compress source content", e);
        }
    }

    private static String gunzip(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot decompress source content", e);
        }
    }
}

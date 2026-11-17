package com.dra.worker.activity;

import com.dra.common.hash.Hashing;
import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.FetchInput;
import com.dra.contracts.dto.SourceRef;
import com.dra.domain.model.FetchedSource;
import com.dra.domain.port.SourceRepository;
import com.dra.domain.util.UrlCanonicalizer;
import com.dra.infra.fetch.FetchedPage;
import com.dra.infra.fetch.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "dra.fetch", name = "mode", havingValue = "http")
public class FetchActivitiesImpl implements ResearchActivities.Fetch {

    private static final Logger log = LoggerFactory.getLogger(FetchActivitiesImpl.class);

    private final WebFetcher webFetcher;
    private final SourceRepository sourceRepository;

    public FetchActivitiesImpl(WebFetcher webFetcher, SourceRepository sourceRepository) {
        this.webFetcher = webFetcher;
        this.sourceRepository = sourceRepository;
    }

    @Override
    public SourceRef fetch(FetchInput input) {
        FetchedPage page = HeartbeatGuard.await(() -> webFetcher.fetch(input.url()));
        if (!page.ok()) {
            log.warn("fetch failed {}: {}", input.url(), page.errorCode());
            return SourceRef.failed(input.url(), page.errorCode());
        }
        String canonical;
        try {
            canonical = UrlCanonicalizer.canonicalize(page.url());
        } catch (IllegalArgumentException e) {
            canonical = page.url();
        }
        String sourceId = Ids.stableId(input.taskId(), canonical);
        String contentHash = Hashing.sha256Hex(page.content());
        sourceRepository.save(new FetchedSource(
                sourceId,
                input.taskId(),
                page.url(),
                page.title(),
                true,
                null,
                contentHash,
                page.content().length(),
                page.content()));
        return new SourceRef(sourceId, page.url(), page.title(), true, null,
                contentHash, page.content().length());
    }
}

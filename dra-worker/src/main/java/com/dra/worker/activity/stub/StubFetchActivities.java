package com.dra.worker.activity.stub;

import com.dra.common.hash.Hashing;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.FetchInput;
import com.dra.contracts.dto.SourceRef;
import com.dra.domain.model.FetchedSource;
import com.dra.domain.port.SourceRepository;
import com.dra.worker.activity.HeartbeatGuard;
import com.dra.worker.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "dra.fetch", name = "mode", havingValue = "stub", matchIfMissing = true)
public class StubFetchActivities implements ResearchActivities.Fetch {

    private final SourceRepository sources;
    private final WorkerProperties properties;

    public StubFetchActivities(SourceRepository sources, WorkerProperties properties) {
        this.sources = sources;
        this.properties = properties;
    }

    @Override
    public SourceRef fetch(FetchInput input) {
        return HeartbeatGuard.await(() -> {
            StubSupport.sleep(properties.getFetchSleepMs());
            String sourceId = StubSupport.stableId(input.taskId() + "|" + input.url());
            String title = "Mock page for " + input.url();
            String content = "Mock content for " + input.url()
                    + ". This paragraph is deterministic test content used until the real fetcher lands."
                    + " Stub paragraph A. Stub paragraph B. Stub paragraph C.";
            String hash = Hashing.sha256Hex(content);
            sources.save(new FetchedSource(sourceId, input.taskId(), input.url(), title,
                    true, null, hash, content.length(), content));
            return new SourceRef(sourceId, input.url(), title, true, null, hash, content.length());
        });
    }
}

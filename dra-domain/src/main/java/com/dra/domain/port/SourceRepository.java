package com.dra.domain.port;

import com.dra.domain.model.FetchedSource;
import com.dra.domain.model.SourceContent;

import java.util.Optional;

public interface SourceRepository {

    void save(FetchedSource source);

    Optional<SourceContent> findContent(String sourceId);
}

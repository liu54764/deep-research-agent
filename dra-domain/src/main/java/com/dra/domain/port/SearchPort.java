package com.dra.domain.port;

import com.dra.contracts.dto.SearchResult;

public interface SearchPort {

    SearchResult search(String query, int topK);
}

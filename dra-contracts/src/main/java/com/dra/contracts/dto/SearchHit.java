package com.dra.contracts.dto;

public record SearchHit(
        String url,
        String title,
        String snippet,
        int rank,
        String provider,
        String publishedAt) {
}

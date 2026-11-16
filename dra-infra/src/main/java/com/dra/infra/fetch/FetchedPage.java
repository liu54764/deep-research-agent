package com.dra.infra.fetch;

public record FetchedPage(
        boolean ok,
        String url,
        String title,
        String content,
        String errorCode,
        int httpStatus) {

    public static FetchedPage failed(String url, String errorCode, int httpStatus) {
        return new FetchedPage(false, url, null, null, errorCode, httpStatus);
    }
}

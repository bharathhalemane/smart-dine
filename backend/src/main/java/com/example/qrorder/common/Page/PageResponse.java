package com.example.qrorder.common.page;

import java.util.List;

/** One page of results, with enough to draw a pager. Pages count from 0. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int pages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(items, page, size, totalElements, pages);
    }
}
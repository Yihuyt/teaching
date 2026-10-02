package cn.utcy.teaching.shared.web;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total,
        long totalPages
) {
    public PageResponse {
        items = List.copyOf(items);
    }

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        long pages = total == 0 ? 0 : (total + size - 1) / size;
        return new PageResponse<>(items, page, size, total, pages);
    }
}

package com.yak.zerotrust.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record AuditPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {

    public AuditPageResponse {
        content = List.copyOf(content);
    }

    public static <T> AuditPageResponse<T> from(Page<T> page) {
        return new AuditPageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious()
        );
    }
}

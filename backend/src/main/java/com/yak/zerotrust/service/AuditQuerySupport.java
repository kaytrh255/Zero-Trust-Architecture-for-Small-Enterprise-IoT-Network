package com.yak.zerotrust.service;

import com.yak.zerotrust.exception.InvalidAuditQueryException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuditQuerySupport {

    public static final int MAX_SIZE = 100;

    private AuditQuerySupport() {
    }

    public static Pageable pageable(
            int page,
            int size,
            Instant from,
            Instant to,
            String timestampProperty
    ) {
        if (page < 0) {
            throw new InvalidAuditQueryException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidAuditQueryException("size must be between 1 and " + MAX_SIZE);
        }
        validateRange(from, to);

        Sort sort = Sort.by(
                Sort.Order.desc(timestampProperty),
                Sort.Order.desc("id")
        );
        return PageRequest.of(page, size, sort);
    }

    public static void validateRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidAuditQueryException("from must be earlier than or equal to to");
        }
    }

    public static <T> Specification<T> timestampRange(String property, Instant from, Instant to) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (from != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.<Instant>get(property), from));
            }
            if (to != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.<Instant>get(property), to));
            }
            return predicates.isEmpty()
                    ? criteriaBuilder.conjunction()
                    : criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static <T> Specification<T> equal(String property, Object value) {
        return (root, query, criteriaBuilder) -> value == null
                ? criteriaBuilder.conjunction()
                : criteriaBuilder.equal(root.get(property), value);
    }

    public static <T> Specification<T> equalIgnoreCase(String property, String value) {
        if (value == null || value.isBlank()) {
            return (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
        }
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(
                criteriaBuilder.lower(root.<String>get(property)),
                normalizedValue
        );
    }
}

package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.exception.InvalidAuditQueryException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditQuerySupportTest {

    @Test
    void createsNewestFirstPagesWithStableIdTieBreaking() {
        var pageable = AuditQuerySupport.pageable(2, 25, null, null, "changedAt");

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort().stream().toList()).containsExactly(
                Sort.Order.desc("changedAt"),
                Sort.Order.desc("id")
        );
    }

    @Test
    void rejectsNegativePagesOutOfRangePageSizesAndReversedTimeRanges() {
        Instant earlier = Instant.parse("2026-10-01T00:00:00Z");
        Instant later = Instant.parse("2026-10-02T00:00:00Z");

        assertThatThrownBy(() -> AuditQuerySupport.pageable(-1, 25, null, null, "changedAt"))
                .isInstanceOf(InvalidAuditQueryException.class)
                .hasMessageContaining("page");
        assertThatThrownBy(() -> AuditQuerySupport.pageable(0, 0, null, null, "changedAt"))
                .isInstanceOf(InvalidAuditQueryException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> AuditQuerySupport.pageable(0, 101, null, null, "changedAt"))
                .isInstanceOf(InvalidAuditQueryException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> AuditQuerySupport.pageable(0, 25, later, earlier, "changedAt"))
                .isInstanceOf(InvalidAuditQueryException.class)
                .hasMessageContaining("from");
    }

    @Test
    void exposesExplicitPageMetadataWithoutSpringPageSerializationDetails() {
        var springPage = new PageImpl<>(List.of("audit-1", "audit-2"), PageRequest.of(1, 2), 5);

        AuditPageResponse<String> response = AuditPageResponse.from(springPage);

        assertThat(response.content()).containsExactly("audit-1", "audit-2");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.hasPrevious()).isTrue();
    }
}

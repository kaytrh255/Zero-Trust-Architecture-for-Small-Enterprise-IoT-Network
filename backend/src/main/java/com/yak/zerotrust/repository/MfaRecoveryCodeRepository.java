package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.MfaRecoveryCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from MfaRecoveryCode code where code.userId = :userId and code.usedAt is null order by code.id")
    List<MfaRecoveryCode> findUnusedByUserIdForUpdate(@Param("userId") Long userId);

    long countByUserIdAndUsedAtIsNull(Long userId);

    void deleteAllByUserId(Long userId);
}

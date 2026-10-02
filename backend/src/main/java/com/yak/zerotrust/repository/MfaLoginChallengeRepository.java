package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.MfaLoginChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MfaLoginChallengeRepository extends JpaRepository<MfaLoginChallenge, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from MfaLoginChallenge challenge where challenge.id = :id")
    Optional<MfaLoginChallenge> findByIdForUpdate(@Param("id") String id);

    @Modifying
    @Query("delete from MfaLoginChallenge challenge where challenge.expiresAt < :expiredBefore")
    int deleteExpiredBefore(@Param("expiredBefore") Instant expiredBefore);

    @Modifying
    @Query("delete from MfaLoginChallenge challenge where challenge.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}

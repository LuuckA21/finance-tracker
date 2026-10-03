package me.luucka.finance.passkey;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasskeyRepository extends JpaRepository<Passkey, Long> {

    List<Passkey> findByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<Passkey> findByIdAndUserId(Long id, Long userId);

    Optional<Passkey> findByCredentialId(byte[] credentialId);

    boolean existsByCredentialId(byte[] credentialId);

    long countByUserId(Long userId);

    @Modifying
    @Query("delete from Passkey p where p.userId = :userId")
    int deleteByUser(Long userId);
}

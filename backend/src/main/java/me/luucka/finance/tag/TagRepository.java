package me.luucka.finance.tag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByUserIdOrderByNameAsc(Long userId);

    Optional<Tag> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}

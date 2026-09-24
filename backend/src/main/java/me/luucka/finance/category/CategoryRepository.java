package me.luucka.finance.category;

import java.util.List;
import java.util.Optional;

import me.luucka.finance.core.EntryKind;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdOrderByKindAscNameAsc(Long userId);

    Optional<Category> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndKindAndNameIgnoreCase(Long userId, EntryKind kind, String name);
}

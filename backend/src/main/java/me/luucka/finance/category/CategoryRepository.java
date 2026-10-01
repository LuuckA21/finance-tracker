package me.luucka.finance.category;

import java.util.List;
import java.util.Optional;

import me.luucka.finance.core.EntryKind;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdOrderByKindAscNameAsc(Long userId);

    Optional<Category> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    /** A macro of that kind and name, ignoring case. */
    boolean existsByUserIdAndKindAndParentIdIsNullAndNameIgnoreCase(Long userId, EntryKind kind, String name);

    /** A detail of that name under the macro, ignoring case. */
    boolean existsByParentIdAndNameIgnoreCase(Long parentId, String name);

    boolean existsByParentId(Long parentId);
}

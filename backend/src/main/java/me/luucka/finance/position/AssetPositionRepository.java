package me.luucka.finance.position;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetPositionRepository extends JpaRepository<AssetPosition, Long> {

    List<AssetPosition> findByUserIdOrderByArchivedAscNameAsc(Long userId);

    Optional<AssetPosition> findByIdAndUserId(Long id, Long userId);
}

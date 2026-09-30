package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.domain.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only store of click events (FR-5, D8). It declares no query methods and carries no transaction
 * annotation: the click recorder owns the transaction, so the counter update and the event insert commit together.
 */
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
}

package com.relayhub.controller;

import com.relayhub.security.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final JdbcTemplate jdbc;
    public OperationsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/outbox-quarantine")
    public List<Map<String,Object>> quarantinedOutbox() {
        return jdbc.queryForList("""
                SELECT o.id,o.event_id,o.failure_count,o.last_error,o.quarantined_at
                FROM event_outbox o JOIN events e ON e.id=o.event_id
                WHERE e.tenant_id=? AND o.quarantined_at IS NOT NULL AND o.published_at IS NULL
                ORDER BY o.id LIMIT 100
                """, TenantContext.getTenant().id());
    }

    @PostMapping("/outbox-quarantine/{id}/retry")
    public Map<String,Integer> retry(@PathVariable long id) {
        int queued = jdbc.update("""
                UPDATE event_outbox o SET quarantined_at=NULL,failure_count=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL
                FROM events e WHERE e.id=o.event_id AND e.tenant_id=? AND o.id=?
                AND o.quarantined_at IS NOT NULL AND o.published_at IS NULL
                """, TenantContext.getTenant().id(), id);
        return Map.of("queued", queued);
    }
    @GetMapping("/legacy-pending")
    public List<Map<String,Object>> legacyPending() {
        return jdbc.queryForList("""
                SELECT e.id,e.created_at FROM events e WHERE e.tenant_id=? AND e.status='PENDING'
                AND NOT EXISTS(SELECT 1 FROM event_outbox o WHERE o.event_id=e.id)
                AND NOT EXISTS(SELECT 1 FROM webhook_deliveries d WHERE d.event_id=e.id)
                ORDER BY e.created_at LIMIT 100
                """, TenantContext.getTenant().id());
    }

    @PostMapping("/legacy-pending/reconcile")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Integer> reconcile() {
        var ids = jdbc.queryForList("""
                SELECT e.id FROM events e WHERE e.tenant_id=? AND e.status='PENDING'
                AND NOT EXISTS(SELECT 1 FROM event_outbox o WHERE o.event_id=e.id)
                AND NOT EXISTS(SELECT 1 FROM webhook_deliveries d WHERE d.event_id=e.id)
                ORDER BY e.created_at LIMIT 100 FOR UPDATE OF e SKIP LOCKED
                """,String.class,TenantContext.getTenant().id());
        for(String id:ids) jdbc.update("INSERT INTO event_outbox(event_id,kind) VALUES (?,'EVENT')",id);
        return Map.of("queued",ids.size());
    }

}

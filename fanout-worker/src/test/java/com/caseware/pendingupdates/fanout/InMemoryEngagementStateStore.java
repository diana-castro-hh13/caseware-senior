package com.caseware.pendingupdates.fanout;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A simple in-memory version of the tracking table, used only by the tests. It follows the same rules as the SQL in schema.sql. */
final class InMemoryEngagementStateStore implements EngagementStateStore {

    enum Status { UNKNOWN, VERIFIED, FAILED }

    static final class Row {
        final EngagementRef ref;
        Status status = Status.UNKNOWN;
        TemplateBinding binding;
        Instant observedAt;
        String leaseOwner;
        Instant leaseUntil;
        String failureReason;

        Row(EngagementRef ref) {
            this.ref = ref;
        }
    }

    private final TreeMap<String, Row> rows = new TreeMap<>();
    private final Clock clock;

    InMemoryEngagementStateStore(Clock clock) {
        this.clock = clock;
    }

    synchronized void addUnknown(String engagementId, String firmId) {
        rows.put(engagementId, new Row(new EngagementRef(engagementId, firmId)));
    }

    /** Pretends the engagement system told us the version (because the file was created, opened or updated). */
    void recordFromEvent(String engagementId, TemplateBinding binding, Instant observedAt) {
        recordVerified(engagementId, binding, observedAt);
    }

    synchronized Row row(String engagementId) {
        return rows.get(engagementId);
    }

    synchronized long countWithStatus(Status status) {
        return rows.values().stream().filter(r -> r.status == status).count();
    }

    @Override
    public synchronized List<EngagementRef> findUnverified(String after, int limit) {
        Map<String, Row> tail = after == null ? rows : rows.tailMap(after, false);
        List<EngagementRef> out = new ArrayList<>();
        Instant now = clock.instant();
        for (Row r : tail.values()) {
            if (out.size() >= limit) break;
            if (r.status == Status.UNKNOWN && !leaseLive(r, now)) {
                out.add(r.ref);
            }
        }
        return out;
    }

    @Override
    public synchronized boolean tryClaim(String id, String owner, Instant leaseUntil, Instant notBefore) {
        Row r = rows.get(id);
        if (r == null || r.status == Status.FAILED || leaseLive(r, clock.instant())) return false;
        if (r.observedAt != null && !r.observedAt.isBefore(notBefore)) return false;
        r.leaseOwner = owner;
        r.leaseUntil = leaseUntil;
        return true;
    }

    @Override
    public synchronized void recordVerified(String id, TemplateBinding binding, Instant observedAt) {
        Row r = rows.get(id);
        if (r == null) return;
        if (r.observedAt == null || r.observedAt.isBefore(observedAt)) {
            r.binding = binding;
            r.observedAt = observedAt;
            r.status = Status.VERIFIED;
        }
        r.leaseOwner = null;
        r.leaseUntil = null;
    }

    @Override
    public synchronized void releaseClaim(String id, String owner) {
        Row r = rows.get(id);
        if (r != null && owner.equals(r.leaseOwner)) {
            r.leaseOwner = null;
            r.leaseUntil = null;
        }
    }

    @Override
    public synchronized void recordPermanentFailure(String id, String reason) {
        Row r = rows.get(id);
        if (r != null) {
            r.status = Status.FAILED;
            r.failureReason = reason;
            r.leaseOwner = null;
            r.leaseUntil = null;
        }
    }

    private static boolean leaseLive(Row r, Instant now) {
        return r.leaseUntil != null && r.leaseUntil.isAfter(now);
    }
}

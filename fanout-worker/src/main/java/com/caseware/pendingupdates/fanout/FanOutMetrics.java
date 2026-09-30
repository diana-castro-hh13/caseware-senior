package com.caseware.pendingupdates.fanout;

import java.util.concurrent.atomic.AtomicLong;

/** Simple counters so we can see on a dashboard what the worker is doing. */
public final class FanOutMetrics {

    final AtomicLong publishesReceived = new AtomicLong();
    final AtomicLong enqueued = new AtomicLong();
    final AtomicLong downstreamCalls = new AtomicLong();
    final AtomicLong verified = new AtomicLong();
    final AtomicLong skippedAlreadyVerified = new AtomicLong();
    final AtomicLong retriesScheduled = new AtomicLong();
    final AtomicLong permanentFailures = new AtomicLong();
    final AtomicLong overloadSignals = new AtomicLong();

    public record Snapshot(long publishesReceived, long enqueued, long downstreamCalls, long verified,
                           long skippedAlreadyVerified, long retriesScheduled, long permanentFailures,
                           long overloadSignals, int queueDepth) {
    }

    Snapshot snapshot(int queueDepth) {
        return new Snapshot(publishesReceived.get(), enqueued.get(), downstreamCalls.get(), verified.get(),
                skippedAlreadyVerified.get(), retriesScheduled.get(), permanentFailures.get(),
                overloadSignals.get(), queueDepth);
    }
}

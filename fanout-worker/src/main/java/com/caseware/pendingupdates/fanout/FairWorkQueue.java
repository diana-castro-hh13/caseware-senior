package com.caseware.pendingupdates.fanout;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A waiting line for jobs that takes turns between firms.
 *
 * Why: firm sizes are very uneven (a typical firm has about 40 engagements, the largest about 40,000).
 * In a normal first-come-first-served line, the largest firm could fill every slot for days while
 * small firms wait. Here each firm with waiting jobs gets one turn per round.
 * The line has a maximum size so it cannot use too much memory.
 */
final class FairWorkQueue {

    private final int capacity;
    private final Map<String, ArrayDeque<WorkItem>> byFirm = new HashMap<>();
    private final ArrayDeque<String> rotation = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private int size;

    FairWorkQueue(int capacity) {
        this.capacity = capacity;
    }

    /** Adds a job. Returns false if the line is full. That is fine: the job is still in the table for later. */
    boolean offer(WorkItem item) {
        lock.lock();
        try {
            if (size >= capacity) {
                return false;
            }
            ArrayDeque<WorkItem> firmQueue = byFirm.get(item.firmId());
            if (firmQueue == null) {
                firmQueue = new ArrayDeque<>();
                byFirm.put(item.firmId(), firmQueue);
                rotation.addLast(item.firmId());
            }
            firmQueue.addLast(item);
            size++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Takes the next job, moving on to the next firm each time. Returns null if nothing arrives in time. */
    WorkItem poll(Duration timeout) throws InterruptedException {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (size == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            String firm = rotation.pollFirst();
            ArrayDeque<WorkItem> firmQueue = byFirm.get(firm);
            WorkItem item = firmQueue.pollFirst();
            if (firmQueue.isEmpty()) {
                byFirm.remove(firm);
            } else {
                rotation.addLast(firm);
            }
            size--;
            return item;
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }
}

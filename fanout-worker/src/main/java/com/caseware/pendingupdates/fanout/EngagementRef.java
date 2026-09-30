package com.caseware.pendingupdates.fanout;

import java.util.Objects;

/** Points to one engagement file and the firm that owns it. */
public record EngagementRef(String engagementId, String firmId) {
    public EngagementRef {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(firmId, "firmId");
    }
}

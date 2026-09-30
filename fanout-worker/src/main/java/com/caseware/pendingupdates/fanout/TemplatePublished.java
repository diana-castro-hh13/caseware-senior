package com.caseware.pendingupdates.fanout;

import java.time.Instant;
import java.util.Objects;

/**
 * Message we receive when a new template version is published.
 * The same message can arrive more than once, so nothing here may assume it is new.
 */
public record TemplatePublished(String publishId, String templateId, String branch, int version, Instant publishedAt) {
    public TemplatePublished {
        Objects.requireNonNull(publishId, "publishId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}

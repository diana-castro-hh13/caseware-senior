package com.caseware.pendingupdates.fanout;

import java.util.Objects;

/** The answer we want for each engagement: which template, which market branch, which version. */
public record TemplateBinding(String templateId, String branch, int version) {
    public TemplateBinding {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(branch, "branch");
    }
}

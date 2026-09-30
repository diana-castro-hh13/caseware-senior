package com.caseware.pendingupdates.fanout;

/** Something went wrong when we asked the other team's service to check an engagement. */
public class DownstreamException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Their service is too busy. Everyone waits a bit, then tries again. */
        OVERLOADED,
        /** A temporary problem, like a timeout. Try this one again later. */
        TRANSIENT,
        /** Trying again will not help (file deleted, broken, or no access). Stop and flag it. */
        PERMANENT
    }

    private final Kind kind;

    public DownstreamException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}

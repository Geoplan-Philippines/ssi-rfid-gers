package com.geoplan.rfid.agent.middleware;

/** What the coordinator should do with a batch after trying to append it. */
public enum AppendOutcome {
    /** Middleware accepted the batch. */
    SENT,
    /** Transient problem. Put the EPCs back and try again on the next flush. */
    RETRY,
    /** Middleware rejected the payload itself. Retrying would loop forever. */
    DROPPED,
    /** Middleware says this session no longer accepts reads. Stop scanning. */
    SESSION_CLOSED
}

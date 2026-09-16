package com.geoplan.rfid.agent.reader;

/**
 * What the rest of the agent needs from a reader. Keeps the vendor SDK behind
 * one seam so the control server and the scan coordinator stay testable.
 */
public interface TagReader {

    void setListener(EpcListener listener);

    /** Connects if needed. Returns true when the reader is usable. Never throws. */
    boolean connect();

    /** Starts continuous inventory. Returns true when tags are flowing. Never throws. */
    boolean startInventory();

    void stopInventory();

    boolean isConnected();

    boolean isInventoryRunning();

    /** Stops inventory and drops the connection. */
    void close();

    String describe();
}

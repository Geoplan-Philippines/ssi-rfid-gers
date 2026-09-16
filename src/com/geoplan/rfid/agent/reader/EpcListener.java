package com.geoplan.rfid.agent.reader;

/** Receives every EPC the reader reports, already normalised to uppercase hex. */
public interface EpcListener {
    void onEpc(String epc);
}

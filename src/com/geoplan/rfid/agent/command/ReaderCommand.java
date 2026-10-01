package com.geoplan.rfid.agent.command;

/** One durable command claimed from the cloud middleware. */
public record ReaderCommand(String commandId, Type type, String sessionId) {
    public enum Type {
        START,
        STOP
    }
}

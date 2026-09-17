# Warehouse document scan start change note

Date: 16 September 2026
Branch: `feat/document-rooted-warehouse-scan`

## Why

The floor tablet starts and cancels a warehouse document. It does not open a
free EPC session. `POST /epc-scan-processing/sessions` and
`POST /epc-scan-processing/sessions/:sessionId/cancel` are gone. Start and
cancel live on the document. The Java agent still appends reads to
`POST /sessions/:sessionId/reads`.

## Schema

`RFIDReader.controlPort` is optional (`Int?`, column `control_port`). There is
no default on the column. Existing rows stay null. The app uses 8443 when the
port is missing. There is no reader foreign key on `EpcScanSession`. `deviceId`
stays a string and holds the reader UUID after a successful start.

This database is disposable local data. Reset it, then create the migration:

```bash
npm exec prisma migrate reset
npm exec prisma migrate dev --name add_rfid_reader_control_port
npm exec prisma generate
```

Do not hand-write files under `prisma/migrations/`.

## HTTP

`POST /warehouse-documents/:id/start` with `{ "deviceId": "<ACTIVE reader UUID>" }`
returns 200. The document status is `in progress` and `sessionId` is set.
Middleware then POSTs `{ sessionId }` to HTTPS `/scan/start`.

If the agent is unreachable or times out, the response is 503 after the OPEN
session is deleted and the document is NEW again.

If the agent returns a non-2xx response, the response is 502 after the same
rollback.

`POST /warehouse-documents/:id/cancel` stops the agent, marks the session
CANCELLED, and reverts the document to NEW. Idle cancel returns 200 with no
agent call. A parked goods receipt returns 409.

`GET /epc-scan-processing/sessions`, GET one session, append reads, and complete
stay.

## Env

`READER_AGENT_TLS_REJECT_UNAUTHORIZED` defaults to true. LAN self-signed agents
set it to false. Commands use `node:https` so that flag maps to
`rejectUnauthorized`. Node 24 does not expose `node:undici`.

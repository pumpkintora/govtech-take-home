# PulseChat — Real-Time 1-to-1 Messaging MVP

A full-stack, locally runnable real-time 1-to-1 private messaging web application designed for **high-throughput mobile and desktop traffic**.

Instead of wrapping an existing real-time library (such as Socket.IO, STOMP, or `spring-boot-starter-websocket`), the core real-time transport is implemented **from scratch directly on top of Java 21 standard-library TCP sockets (`ServerSocket` / `Socket`) and Virtual Threads** following **RFC 6455**, paired with **PostgreSQL** for durable ordered storage and **RabbitMQ** for horizontal multi-node fan-out.

---

## Executive Summary (Non-Technical Overview)

PulseChat lets any two users have instant, private 1-to-1 conversations across phones, laptops, or multiple browser tabs simultaneously.

- **Instant & reliable:** Messages are saved safely before they appear to the recipient, so unstable Wi-Fi or mobile signal drops will neither lose nor duplicate messages.
- **Scales out cleanly:** The local setup runs **two independent chat servers** side by side behind a single gateway. Even when Alice is connected to Server 1 and Bob is connected to Server 2, their messages, read receipts, online indicators, and typing status flow seamlessly in real time.
- **Zero external cloud dependencies:** Everything runs 100% locally via a single `docker compose up --build` command.

---

## Quickstart (Docker Compose)

### Prerequisites
- Docker Desktop (or Docker Engine + Compose v2)

### Run the full multi-node stack
```bash
docker compose up --build
```

Once healthy (typically 30–60 seconds on first build):
- **Web App:** [http://localhost:8080](http://localhost:8080)
- **RabbitMQ Management UI (optional inspection):** [http://localhost:15672](http://localhost:15672) (`guest` / `guest`)

### Demo Accounts (pre-seeded)
| Username | Display Name | Password |
| :--- | :--- | :--- |
| `alice` | Alice Tan | `password123` |
| `bob` | Bob Lim | `password123` |
| `charlie` | Charlie Ng | `password123` |

*(You can also register new accounts directly from the login screen.)*

---

## 2-Minute Verification Walkthrough

1. **Test 1-to-1 Cross-Node Real-Time Chat:**
   - Open [http://localhost:8080](http://localhost:8080) in a normal browser window and click **Alice** (notice the connection badge in the sidebar, e.g. `Connected · node-1`).
   - Open [http://localhost:8080](http://localhost:8080) in an **Incognito / Private window** and click **Bob** (Nginx round-robins Bob onto `node-2`).
   - In Alice's window, open the **People** tab → click **Bob Lim** → send a message.
   - Observe instant delivery in Bob's window across `node-1 → PostgreSQL → RabbitMQ → node-2`, live typing indicator (`typing...`), online status dot, and delivery/read ticks (`✓` Sent → `✓✓` Delivered → blue `✓✓` Read).
2. **Test Multi-Tab / Multi-Device Sync:**
   - Open a **second regular tab** at [http://localhost:8080](http://localhost:8080) (shares Alice's `HttpOnly` session cookie).
   - Send a message to Bob from Tab 1 — it appears immediately in Tab 2 as well. Close Tab 1 — Bob still sees Alice as **Online** because Tab 2 is still connected.
3. **Test Resilient Reconnect & Gap Recovery:**
   - Stop one backend replica: `docker compose stop backend-1`.
   - Any browser connected to `node-1` automatically switches to `Reconnecting...`, fails over to `node-2` with exponential backoff + jitter, and pulls any missed messages via cursor sync (`GET /api/messages/sync?afterSeq=...`).

---

## Running Automated Tests Locally

### Backend (Java 21 + Maven)
Runs RFC 6455 frame codec tests, HTTP Upgrade handshake test vectors, PBKDF2 auth tests, and an end-to-end TCP WebSocket integration test against our custom `RawWebSocketServer`:
```bash
cd backend
mvn test
```

### Frontend (TypeScript + Vite)
```bash
cd frontend
npm install
npm run build
```

---

## Architecture & Engineering Deep Dive

See [ARCHITECTURE.md](./ARCHITECTURE.md) for:
- System topology and message flow diagrams
- How the custom RFC 6455 WebSocket engine works under the hood
- Specific real-world concurrency bugs, mobile network edge cases, and race conditions handled in this codebase
- Production hardening and legacy maintenance roadmap

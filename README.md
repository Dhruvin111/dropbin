# 📦 DropBin Live — Ephemeral Real-Time Collaborative Pad & File Sharing

[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Server-Sent Events](https://img.shields.io/badge/Real--Time-SSE-ff69b4)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**DropBin Live** is an ephemeral, real-time collaborative text pad and temporary file-sharing hub. It allows any device on a local network (or web) to instantly create or join a pad, type collaboratively with live multi-device synchronization, and share documents up to 10MB. Everything auto-expires after **5 minutes of inactivity**, keeping storage clean and private.

---

## ✨ Features

- ✍️ **Always-in-Edit-Mode Text Editor**: Zero-click editing with line-number gutter, live character/word/line counters, and Tab indentation support.
- 💾 **Debounced Auto-Save & Beacon**: Changes automatically persist 1 second after you stop typing, via `Ctrl+S`, or upon navigating away using `navigator.sendBeacon`.
- 🔄 **Real-Time Multi-Device Sync (SSE)**: Powered by Server-Sent Events (`SseEmitter`) with a 25-second keep-alive heartbeat (`: ping`). Edits and file uploads from one PC or phone reflect instantly on all other connected screens without websockets or third-party cloud services.
- 🛡️ **Smart Conflict Protection**: If another device pushes updates while you are actively typing locally, DropBin avoids clobbering your draft and presents a non-intrusive prompt (*Load* vs. *Dismiss*).
- 📎 **File Sharing (Max 5 Files, up to 10MB each)**: Drag-and-drop or browse files (Images: PNG, JPG, WEBP, SVG; PDFs; Word `.doc` / `.docx`). Strictly capped at 5 files per workspace to prevent resource exhaustion.
- 👁️ **Inline Lightbox Preview & Direct Download**: Built-in modal image preview and dedicated preview/download routes for documents with automatic SVG XSS sandboxing.
- ⏱️ **5-Minute Inactivity Auto-Expiry**: Resets dynamically on every text edit or file upload. Includes a live countdown timer in the header.
- 🧹 **Automatic Cleanup Worker**: Background scheduled task runs every 60 seconds (`@Scheduled`) to purge expired pastes and delete uploaded files from disk.
- 🛡️ **Rate Limiting & DoS Protection**: Built-in sliding-window IP rate limiting (120 saves/min, 20 uploads/min, 30 new bins/min) with proxy IP resolution (`X-Forwarded-For`).
- 🔒 **Zero-Trust File Security**: Content-Security-Policy sandboxing on SVG previews, path traversal protection, and atomic concurrency locking on uploads.
- 🌐 **Zero-Config LAN Sharing**: Automatically detects host LAN IP address and generates copyable URLs for instant cross-device sharing across phones, laptops, and workstations on the same Wi-Fi.
- 🔒 **Secure Read-Only Share Links**: Share unguessable, tokenized view-only links (`/d/readonly/ro_<token>`) that lock the editor and disable uploads for viewers while continuing to stream live updates in real time. Deleting `readonly/` or attempting to edit from a read-only token is automatically blocked and forbidden.
- 📱 **Integrated Dual QR Codes**: Instant camera scanning for both Collaborative Edit and Read-Only links. Features built-in preview, download to PNG, and direct image endpoints (`/d/{id}/qr` & `/d/readonly/{token}/qr`).

---

## 🏛️ Architecture & Real-Time Sync Flow

DropBin uses a lightweight, resilient hybrid sync architecture combining **debounced HTTP persistence**, **Server-Sent Events (SSE)**, and **status polling fallback**:

```mermaid
sequenceDiagram
    autonumber
    actor UserA as Device A (Editor)
    participant BrowserA as Browser Tab A
    participant Server as Spring Boot Backend
    participant DB as PostgreSQL
    participant SyncService as PasteSyncService (SSE)
    participant BrowserB as Browser Tab B (Remote Peer)

    Note over BrowserA,BrowserB: Both clients connected via GET /api/dropbin/{id}/stream
    UserA->>BrowserA: Types or pastes content
    BrowserA->>BrowserA: 1-second debounce timer
    BrowserA->>Server: POST /api/dropbin/{id} (includes tab token)
    Server->>DB: Save paste, increment version, extend expiry (+5m)
    Server->>SyncService: broadcastUpdate(pasteId, response)
    SyncService-->>BrowserB: Push SSE event ("paste_update")
    alt Device B is idle
        BrowserB->>BrowserB: Auto-applies new text & resets expiry timer
    else Device B has unsaved typing
        BrowserB->>BrowserB: Shows conflict banner ("Load" / "Keep My Draft")
    end
```

---

## 🛠️ Tech Stack

- **Backend**: Java 17+, Spring Boot 3.2.4 (Spring Web, Spring Data JPA, Validation)
- **Database**: PostgreSQL 16 (with indexed `expires_at` column for fast cleanup)
- **Real-Time Mechanism**: Spring Web `SseEmitter` (Server-Sent Events) + Client-side `EventSource`
- **Frontend**: Thymeleaf, Vanilla Modern JavaScript (ES6+), Vanilla CSS3 (Custom properties, dark aesthetics, responsive layout)
- **Storage**: Local filesystem disk storage (`uploads/{pasteId}/...`) with safe path-traversal prevention

---

## 🚀 Quick Start

### 1. Prerequisites
- [JDK 17+](https://adoptium.net/) or higher
- [Docker](https://www.docker.com/) & Docker Compose (for PostgreSQL)
- Maven 3.8+ (or use the included `./mvnw`)

### 2. Start PostgreSQL with Docker
Run the included `docker-compose.yml`:
```bash
docker compose up -d
```
*This starts a PostgreSQL 16 instance on port `5432` with database `textbin` and initializes schema indexes from `init.sql`.*

### 3. Configure Database Credentials
Verify or update `src/main/resources/application.properties` (or set environment variables):
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/textbin
spring.datasource.username=textbin_user
spring.datasource.password=changeme
```

### 4. Run the Application
Run via Maven wrapper:
```bash
# Linux / macOS
./mvnw spring-boot:run

# Windows PowerShell
./mvnw.cmd spring-boot:run
```

Once started, open your browser at:
- **Localhost**: [http://localhost:8080](http://localhost:8080)
- **Local Network**: `http://<your-lan-ip>:8080` (displayed right on the app header and share modal)

---

## 🧭 URL & Routing Structure

| Route | Method | Description |
|---|---|---|
| `/` or `/d` | `GET` | Landing page to create or open a named pad |
| `/d/new` | `GET` | Generates a random alphanumeric pad ID and redirects |
| `/d/{id}` | `GET` | Main live collaborative editor view for pad `{id}` |
| `/d/readonly/{token}` | `GET` | View-only link: locks editor & uploads while streaming live updates |
| `/d/{id}/raw` | `GET` | Plain text raw view of bin content |
| `/{id}` | `GET` | Convenient shortcut redirecting to `/d/{id}` |
| `/dropbin/{id}` | `GET` | Legacy route redirecting to `/d/{id}` |

---

## 📡 REST API Reference

### Text & Pad Endpoints

#### Save / Update Pad Content
`POST /api/d/{id}` *(legacy `/api/dropbin/{id}` also supported)*
- **Payload**:
  ```json
  {
    "content": "Updated note or code snippet...",
    "title": "meeting-notes",
    "syntaxLanguage": "plaintext",
    "clientToken": "tab_x98f21"
  }
  ```
- **Response**: `200 OK` with updated `PasteResponse` object (includes new version and timestamp).

#### Real-Time SSE Stream
`GET /api/d/{id}/stream`
- **Response**: `text/event-stream`
- Emits events:
  - `init`: Fired upon successful initial connection.
  - `paste_update`: Broadcasts updated text payload, version, and author token.
  - `file_uploaded`: Broadcasts uploaded file metadata DTO.
  - `file_deleted`: Broadcasts deleted file ID.
  - `files_cleared`: Broadcasts when all attached files are cleared from the workspace.
  - `bin_deleted`: Broadcasts when the entire bin is taken down.

#### Get Pad Status (Lightweight Polling)
`GET /api/d/{id}/status`
- **Response**:
  ```json
  {
    "id": "meeting-notes",
    "version": 4,
    "updatedAt": "2026-09-25T11:45:00Z",
    "contentLength": 1042
  }
  ```

#### Get Full Pad Details
`GET /api/d/{id}`
- **Response**: `200 OK` with complete pad JSON.

---

### File Sharing Endpoints (Up to 10MB, Max 5 Files)

| Endpoint | Method | Description |
|---|---|---|
| `/api/d/{pasteId}/files` | `POST` | Upload file (multipart field: `file`, max 5 files per bin, max 10MB each) |
| `/api/d/{pasteId}/files` | `GET` | List all active attached files for the pad |
| `/api/d/{pasteId}/files/{fileId}` | `DELETE` | Delete a single attached file |
| `/api/d/{pasteId}/files` | `DELETE` | Purge all attached files for the pad |
| `/d/{pasteId}/files/{fileId}/view` | `GET` | Inline preview (Images / PDFs with CSP sandbox protection) |
| `/d/{pasteId}/files/{fileId}/download` | `GET` | Download attachment with original filename header |

---

## ⚙️ Configuration Reference

Key properties in [`src/main/resources/application.properties`](src/main/resources/application.properties):

```properties
# Server bind configuration
server.port=8080
server.address=0.0.0.0

# File Upload Limits (10MB max file size)
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=12MB

# Database Configuration
spring.datasource.url=jdbc:postgresql://localhost:5432/textbin
spring.datasource.username=textbin_user
spring.datasource.password=changeme

# Connection Pool (HikariCP)
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=2

# Hibernate & Template Cache
spring.jpa.hibernate.ddl-auto=update
spring.thymeleaf.cache=false
```

---

## 🛡️ Security & Resource Management

DropBin includes comprehensive, production-grade defenses across memory, storage, concurrency, and client security:

### 1. In-Memory IP Rate Limiting (Flood & DoS Guard)
- **Service & Interceptor**: `RateLimitingService` and `RateLimitInterceptor` implement sliding-window rate limits per client IP.
- **Reverse-Proxy Aware**: Extracts real client IPs from `X-Forwarded-For` and `X-Real-IP`, preventing proxy IP collisions on cloud platforms (Railway, Cloudflare, Nginx).
- **Enforced Thresholds**:
  - `POST .../files` (Uploads): **20 uploads / minute** per IP.
  - `POST /api/d/{id}` (Text sync): **120 saves / minute** per IP (accommodating debounced typing bursts while preventing API flooding).
  - `/d/new` (Workspace generation): **30 requests / minute** per IP.
  - Returns `HTTP 429 Too Many Requests` with `Retry-After: 60` on violation.
- **Memory Safety**: Scheduled worker (`@Scheduled(fixedRate = 60_000)`) purges stale IP buckets to ensure bounded heap memory.

### 2. SVG Stored-XSS Mitigation
- Files served inline (`/view`) feature strict defensive headers:
  ```http
  Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox
  X-Content-Type-Options: nosniff
  X-Frame-Options: SAMEORIGIN
  ```
- Any uploaded `.svg` file containing malicious `<script>` tags or event handlers (`onload`, `onerror`) is executed in an isolated, origin-null sandbox where script execution and cookie/DOM access are completely blocked.

### 3. Server Disk Quota & Free Space Protection
- `FileStorageService` verifies `usableSpace` via `Files.getFileStore()`. If the host disk space drops below **50MB**, incoming uploads are rejected before host storage fills to 100%, preventing database corruption or system lockups.

### 4. Concurrency Guard on 5-File Limit
- Workspace file uploads are synchronized per workspace key (`synchronized (pasteId.intern())`), eliminating race conditions where parallel upload requests could simultaneously read `count = 4` and bypass the 5-file limit.

### 5. Socket Lifecycle & Graceful Shutdown
- `SseEmitter` instances are configured with 10-minute timeouts, disconnection event hooks (`onCompletion`, `onTimeout`, `onError`), and periodic heartbeat health checks.
- On application shutdown/reload, an `@PreDestroy` hook cleanly signals `.complete()` to all active browser connections, preventing hanging threads or zombie sockets.

---

## 📂 Project Structure

```text
dropbin/
├── .vscode/                     # IDE debug & launch configurations
├── docker-compose.yml           # PostgreSQL service configuration
├── init.sql                     # Initial database indexes
├── pom.xml                      # Maven project configuration
├── README.md                    # Project documentation
├── uploads/                     # Ephemeral file attachments directory (git-ignored)
└── src/
    ├── main/
    │   ├── java/com/textbin/
    │   │   ├── TextbinApplication.java    # Application entry point & @EnableScheduling
    │   │   ├── config/
    │   │   │   ├── DataSourceConfig.java  # Railway DATABASE_URL adapter
    │   │   │   ├── RateLimitInterceptor.java # Request throttling interceptor
    │   │   │   └── WebConfig.java         # Interceptor & static resource registry
    │   │   ├── controller/
    │   │   │   ├── FileController.java    # File upload, inline preview (CSP), download & purge
    │   │   │   ├── PasteApiController.java# REST API for auto-save, status, & SSE stream
    │   │   │   └── PasteController.java   # UI web page routes & QR redirection
    │   │   ├── dto/                       # Request & Response DTOs
    │   │   ├── model/                     # JPA Entities (Paste, BinFile)
    │   │   ├── repository/                # Spring Data Repositories
    │   │   ├── service/
    │   │   │   ├── CleanupService.java    # 60s background task for expired purge
    │   │   │   ├── FileStorageService.java# Disk storage, 5-file cap & space protection
    │   │   │   ├── PasteService.java      # Pad business logic & version management
    │   │   │   ├── PasteSyncService.java  # In-memory SseEmitter broker & heartbeat
    │   │   │   ├── QrCodeService.java     # QR code image generator
    │   │   │   └── RateLimitingService.java # Sliding-window IP rate limiter
    │   │   └── util/NetworkUtil.java      # Local LAN IP resolution
    │   └── resources/
    │       ├── application.properties     # Application settings
    │       ├── static/                    # CSS stylesheets and vector icons
    │       └── templates/                 # Thymeleaf views (index.html, view.html)
    └── test/                              # Unit & integration tests
```

---

## 🔒 Production & Deployment Best Practices

When deploying DropBin in a production environment:

1. **Reverse Proxy (Nginx / Caddy)**:
   Place Nginx or Caddy in front of the application for SSL/TLS termination and enable SSE proxy buffering off:
   ```nginx
   location /api/dropbin/ {
       proxy_pass http://localhost:8080;
       proxy_set_header Connection '';
       proxy_http_version 1.1;
       chunked_transfer_encoding off;
       proxy_buffering off;
       proxy_cache off;
   }
   ```
2. **Environment Variables**:
   Inject sensitive credentials like `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` via environment variables rather than hardcoding in properties files.
3. **Hibernate DDL Auto**:
   Set `spring.jpa.hibernate.ddl-auto=validate` once initial tables and indexes are created.
4. **Thymeleaf Template Cache**:
   Set `spring.thymeleaf.cache=true` in production to optimize template parsing.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

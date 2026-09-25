# 📋 TextBin — Pastebin Clone (Spring Boot + PostgreSQL)

A minimal pastebin clone where every paste auto-deletes after **5 minutes**.

## Features
- Create pastes at `POST /`  → redirects to `/<id>`
- View paste at `GET /<id>` with live countdown timer
- Raw text at `GET /raw/<id>`
- Syntax highlighting (Highlight.js, 17 languages)
- Automatic DB cleanup every 60 s via `@Scheduled`
- PostgreSQL with indexed `expires_at` column for fast purge

---

## Quick Start

### 1. Start PostgreSQL (Docker)
```bash
docker-compose up -d
```

### 2. Configure credentials
Edit `src/main/resources/application.properties`:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/textbin
spring.datasource.username=textbin_user
spring.datasource.password=changeme
```

### 3. Run the app
```bash
./mvnw spring-boot:run
```

Open `http://localhost:8080` in your browser.

---

## URL Structure
| Path          | Description                   |
|---------------|-------------------------------|
| `GET /`       | New paste form                |
| `POST /`      | Create paste, redirect to ID  |
| `GET /<id>`   | View paste with countdown     |
| `GET /raw/<id>` | Plain text response         |

---

## Production Checklist
- [ ] Set `spring.jpa.hibernate.ddl-auto=validate`
- [ ] Set `spring.thymeleaf.cache=true`
- [ ] Use environment variables for DB credentials (not `application.properties`)
- [ ] Put Nginx or Caddy in front for HTTPS + rate-limiting
- [ ] Consider `pg_cron` extension as a backup cleanup mechanism

---

## Alternative Architecture (Scale-up path)
If you need to handle millions of pastes:
1. **Redis** as primary store with native `EXPIRE` — zero cleanup code
2. **PostgreSQL** only for audit/analytics
3. **CDN** in front for raw text caching

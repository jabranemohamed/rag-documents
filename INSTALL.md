# How to Install — RAG Documents

Complete installation guide, from a fresh machine to a running application.
The `brew` commands target macOS; on Linux, use your package manager
(apt, dnf…) for the same tools.

## 1. Prerequisites

| Tool | Minimum version | Install (macOS) | Check |
|---|---|---|---|
| Java (JDK) | 21 | `brew install openjdk@21` | `java -version` |
| Maven | 3.9 | `brew install maven` | `mvn -version` |
| Docker | 24+ (daemon running) | [Docker Desktop](https://www.docker.com/products/docker-desktop/) | `docker ps` |
| Node.js + npm | Node 20+ | `brew install node` | `node --version` |
| Ollama *(free mode)* | latest | `brew install ollama` | `ollama --version` |
| Tesseract *(OCR, optional)* | 5+ | `brew install tesseract` | `tesseract --version` |

> Tesseract is optional: without it the application still works, but
> scanned/image PDF pages will not be read (OCR disables itself gracefully).

## 2. Clone the project

```bash
git clone <repo-url>
cd rag-documents
```

## 3. Vector database (PostgreSQL + pgvector)

```bash
docker compose up -d
```

This starts a `docintel-pgvector` container on **host port 5442**
(user/password/db: `docintel`). The schema (`document_chunks` table, `vector`
extension) is created automatically when the backend starts.

Check:

```bash
docker exec docintel-pgvector pg_isready -U docintel -d docintel
```

## 4. Choose the LLM provider

### Option A — Ollama (free, 100% local)

```bash
brew services start ollama
ollama pull qwen2.5:14b
```

> `qwen2.5:14b` is ~9 GB and needs ~16 GB of RAM. On a smaller machine:
> `ollama pull qwen2.5:7b` then `export OLLAMA_MODEL=qwen2.5:7b`.

### Option B — Claude (best quality, requires API credits)

```bash
cp .env.example .env
# edit .env and set your key: ANTHROPIC_API_KEY=sk-ant-...
```

Create the key at https://console.anthropic.com/settings/keys. It requires
API credits (Plans & Billing) — a claude.ai subscription is not enough.
**Never commit `.env`** (already excluded by `.gitignore`).

## 5. Backend (port 8085)

```bash
cd backend

# Ollama mode (free)
LLM_PROVIDER=ollama mvn spring-boot:run

# OR Claude mode
set -a && source ../.env && set +a && mvn spring-boot:run
```

First run: Maven downloads the dependencies, then the ONNX embedding model
(all-MiniLM-L6-v2, ~90 MB) is downloaded and cached on the first pipeline
call.

Check:

```bash
curl http://localhost:8085/health
```

Swagger: http://localhost:8085/swagger-ui.html

## 6. Frontend (port 4200)

In a second terminal:

```bash
cd frontend
npm install
npm start
```

Open **http://localhost:4200**.

## 7. First use

1. Drop PDFs into `data/raw/` (subfolders allowed) **or** use the
   *Upload & Process* button on the Dashboard.
2. Click *Run Pipeline* (only needed for files dropped manually — an upload
   triggers the pipeline on its own).
3. **Library** menu: indexed documents, PDF viewing and downloading.
4. **Dashboard → RAG** menu: ask a question about the content.
5. **Agent** menu: document list, processing summary, free-form questions.

## 8. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Connection refused` on 5442 | pgvector container stopped | `docker compose up -d` |
| Port 8085 or 4200 in use | Another process | change `server.port` in `application.yml` / `ng serve --port` |
| RAG/agent errors in Ollama mode | Server or model missing | `brew services start ollama` then `ollama pull qwen2.5:14b` |
| `401` / `credit balance is too low` in Claude mode | Invalid key or no credits | check the key and Plans & Billing in the console |
| Scanned pages not read | Tesseract missing | `brew install tesseract` then restart the backend |
| Totals show 0 in the UI while documents are listed | Page loaded during a backend restart | Refresh / F5 |

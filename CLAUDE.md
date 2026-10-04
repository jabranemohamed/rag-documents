# RAG Documents

**Generic PDF document intelligence** application: upload (text + images via
Tesseract OCR), vector indexing, paginated library with PDF
viewing/downloading, grounded RAG Q&A, tool-using agent.
Java 21 / Spring Boot 4.1 (Maven) · Angular 20 · PGVector (Docker, port 5442)
· LLM of choice: Claude (official Anthropic SDK) or free local Ollama.

Getting started: see INSTALL.md. Backend on **8085**, frontend on **4200**.

## LLM providers

- `LLM_PROVIDER=claude` (default): Claude Opus 5.5 via `com.anthropic:anthropic-java`.
  Requires `ANTHROPIC_API_KEY` **and API credits**. JSON extraction =
  structured outputs; agent = native tool use (BetaToolRunner).
- `LLM_PROVIDER=ollama`: free, local, `qwen2.5:14b` model (`OLLAMA_MODEL` to
  change). JSON constrained via Ollama's `format` parameter; agent runs a
  2-step flow (JSON tool selection → Java execution → final answer).
- Abstraction lives in `backend/src/main/java/com/docintel/llm/`.
- **Embeddings are always local** (ONNX all-MiniLM-L6-v2, 384 dims, Spring AI
  Transformers) — no API call, whatever the provider.

## Backend

Pipeline (`PipelineService`, idempotent — only new documents are processed):
PDF (PDFBox + Tesseract OCR for image pages, `OcrService`) → cleaning →
chunks (1000/150) → embeddings → PGVector (`document_chunks` table, cosine
`<=>`). One PDF = one document; `document_id` = sanitized file stem.

Endpoints: `/health`, `/pipeline/status`, `/pipeline/run`,
`/documents?page&size` (**server-side pagination**, SQL LIMIT/OFFSET, 10 per
page, `size` ≤ 50, returns `total_documents`/`total_pages`/`total_chunks`),
`/documents/{id}/file` (PDF inline, `?download=true` to download, strict id
resolution, 404 otherwise), `/documents/upload` (multipart, stores under
`data/raw/uploads`, re-runs the pipeline), `/rag/ask`, `/agent/ask`.

Agent: 3 read-only tools (`list_documents`, `summarize_pipeline_outputs`,
`ask_documents`); destructive keywords are blocked before any LLM call.

## Frontend

Angular standalone + signals, a single `AppComponent`, three views switched
by the `activeView` signal (no router): **Dashboard** (metrics, upload, RAG),
**Library** (paginated; name = link opening the PDF, Download button),
**Agent**. Light admin-template theme: dark header bar (logo, tabs), rounded
white cards, indigo accents — palette in the CSS variables of
`frontend/src/styles.css`. Displayed totals always come from the server
(`total_documents`/`total_chunks`), never from the current page.

## Known pitfalls

- `Set.of(...).contains(null)` throws an NPE — always null-check first.
- Ollama error responses sometimes arrive without a JSON content type:
  `OllamaProvider` reads the body as a String and parses it with Jackson.
- PDF text extraction can produce NUL bytes (0x00) that PostgreSQL rejects:
  stripped at extraction (`sanitizeExtractedText`) + defensively on insert.
- Embeddings: `maxLength=512` is **mandatory** on `TransformersEmbeddingModel`
  (otherwise truncation makes chunks with identical headers
  indistinguishable).
- Ollama `num_ctx` is set to 16384 (the default 4096 truncates large documents).
- Changing `angular.json` requires restarting `ng serve`.
- `.env` holds the API key: gitignored, never commit it.
- "credit balance is too low" error = no Anthropic API credits →
  use `LLM_PROVIDER=ollama` or buy credits.

## Deliberately removed features — do not reintroduce

The project started as an insurance-claims pipeline. Removed on request:
structured extraction (11 fields), claim_dataset.csv, data quality/dictionary,
CSV query plans, `/claims*` endpoints, audit trail (`/audit-trail`) and the
matching UI sections.

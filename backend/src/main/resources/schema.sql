DROP TABLE IF EXISTS claim_chunks;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document_chunks (
    chunk_id    TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    chunk_index INT  NOT NULL,
    source_file TEXT NOT NULL,
    text_length INT  NOT NULL,
    chunk_text  TEXT NOT NULL,
    embedding   vector(384) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_document_chunks_document_id ON document_chunks (document_id);

export interface ArtifactStatus {
  exists: boolean;
  path: string;
}

export interface PipelineStatus {
  project_root: string;
  output_files: Record<string, ArtifactStatus>;
  processing_summary: Record<string, unknown> | null;
}

export interface RagSource {
  source_number: number;
  chunk_id: string;
  document_id: string;
  source_file: string;
  chunk_index: number;
  cosine_distance: number;
}

export interface RagResponse {
  question: string;
  answer: string;
  sources: RagSource[];
}

export interface AgentResponse {
  user_request: string;
  tool_decision: {
    tool_name: string;
    arguments: Record<string, unknown>;
    reason: string;
  };
  tool_used: string;
  final_answer: string;
  result: unknown;
}

export interface DocumentInfo {
  document_id: string;
  chunk_count: number;
  source_file: string;
}

export interface DocumentsPage {
  page: number;
  page_size: number;
  total_documents: number;
  total_pages: number;
  total_chunks: number;
  documents: DocumentInfo[];
}

export interface UploadResponse {
  document_id: string;
  stored_file: string;
  original_filename: string;
  size_bytes: number;
  pipeline: {
    documents_processed: number;
    chunks_loaded: number;
    vector_store_updated: boolean;
    vector_store_chunks: number;
  };
}

export interface HealthResponse {
  status: string;
  service: string;
}

import { Injectable, signal } from '@angular/core';

import {
  AgentResponse,
  DocumentsPage,
  HealthResponse,
  PipelineStatus,
  RagResponse,
  UploadResponse
} from './models';

const DEFAULT_API_BASE_URL = 'http://localhost:8085';
const STORAGE_KEY = 'docIntelApiBaseUrl';

/**
 * Thin typed client over the Spring Boot REST API.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  readonly apiBaseUrl = signal(this.initialBaseUrl());

  private initialBaseUrl(): string {
    try {
      return localStorage.getItem(STORAGE_KEY) || DEFAULT_API_BASE_URL;
    } catch {
      return DEFAULT_API_BASE_URL;
    }
  }

  setBaseUrl(url: string): void {
    this.apiBaseUrl.set(url);
    try {
      localStorage.setItem(STORAGE_KEY, url);
    } catch {
      // storage unavailable - keep in memory only
    }
  }

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const response = await fetch(`${this.apiBaseUrl()}${path}`, {
      headers: { 'Content-Type': 'application/json' },
      ...options
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const message =
        (data as { message?: string; error?: string }).message ||
        (data as { error?: string }).error ||
        'API request failed';
      throw new Error(message);
    }
    return data as T;
  }

  getHealth(): Promise<HealthResponse> {
    return this.request<HealthResponse>('/health');
  }

  getPipelineStatus(): Promise<PipelineStatus> {
    return this.request<PipelineStatus>('/pipeline/status');
  }

  runPipeline(): Promise<{ success: boolean; message: string }> {
    return this.request('/pipeline/run', { method: 'POST' });
  }

  getDocuments(page: number, size = 10): Promise<DocumentsPage> {
    return this.request<DocumentsPage>(`/documents?page=${page}&size=${size}`);
  }

  askRag(question: string): Promise<RagResponse> {
    return this.request<RagResponse>('/rag/ask', {
      method: 'POST',
      body: JSON.stringify({ question, top_k: 3, use_cache: true })
    });
  }

  async uploadDocument(file: File): Promise<UploadResponse> {
    const formData = new FormData();
    formData.append('file', file);

    // No Content-Type header: the browser sets the multipart boundary itself.
    const response = await fetch(`${this.apiBaseUrl()}/documents/upload`, {
      method: 'POST',
      body: formData
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const message =
        (data as { message?: string; error?: string }).message ||
        (data as { error?: string }).error ||
        'Upload failed';
      throw new Error(message);
    }
    return data as UploadResponse;
  }

  askAgent(question: string): Promise<AgentResponse> {
    return this.request<AgentResponse>('/agent/ask', {
      method: 'POST',
      body: JSON.stringify({ question })
    });
  }
}

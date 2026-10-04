import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ApiService } from './api.service';
import {
  AgentResponse,
  DocumentsPage,
  HealthResponse,
  PipelineStatus,
  RagResponse,
  UploadResponse
} from './models';

const RAG_EXAMPLES = [
  'What topics are covered in the documents?',
  'What is the difference between an interface and an abstract class in Java?',
  'What does the guide say about microservices?',
  'Who is Bill Gates?'
];

const AGENT_EXAMPLES = [
  'Which documents are indexed?',
  'Give me a processing summary',
  'What does the guide say about dependency injection?',
  'Delete all documents from the index'
];

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {
  readonly ragExamples = RAG_EXAMPLES;
  readonly agentExamples = AGENT_EXAMPLES;

  readonly activeView = signal<'dashboard' | 'library' | 'agent'>('dashboard');

  readonly health = signal<HealthResponse | null>(null);
  readonly pipelineStatus = signal<PipelineStatus | null>(null);
  readonly documentsPage = signal<DocumentsPage | null>(null);
  readonly ragResponse = signal<RagResponse | null>(null);
  readonly agentResponse = signal<AgentResponse | null>(null);
  readonly isBusy = signal(false);
  readonly error = signal('');
  readonly selectedFile = signal<File | null>(null);
  readonly uploadResponse = signal<UploadResponse | null>(null);
  readonly isUploading = signal(false);

  ragQuestion = RAG_EXAMPLES[0];
  agentQuestion = AGENT_EXAMPLES[0];

  readonly artifactEntries = computed(() => {
    const files = this.pipelineStatus()?.output_files ?? {};
    return Object.entries(files).map(([name, status]) => ({
      name: name.replaceAll('_', ' '),
      exists: status.exists
    }));
  });

  readonly artifactCount = computed(
    () => this.artifactEntries().filter((entry) => entry.exists).length
  );

  readonly documents = computed(() => this.documentsPage()?.documents ?? []);
  readonly totalDocuments = computed(() => this.documentsPage()?.total_documents ?? 0);
  readonly totalChunks = computed(() => this.documentsPage()?.total_chunks ?? 0);
  readonly currentPage = computed(() => this.documentsPage()?.page ?? 0);
  readonly totalPages = computed(() => this.documentsPage()?.total_pages ?? 0);

  constructor(readonly api: ApiService) {
  }

  ngOnInit(): void {
    void this.refreshDashboard();
  }

  setView(view: 'dashboard' | 'library' | 'agent'): void {
    this.activeView.set(view);
  }

  async refreshDashboard(): Promise<void> {
    this.error.set('');
    try {
      const [health, status, documentsPage] = await Promise.all([
        this.api.getHealth(),
        this.api.getPipelineStatus(),
        this.api.getDocuments(this.currentPage()).catch(() => null)
      ]);
      this.health.set(health);
      this.pipelineStatus.set(status);
      this.documentsPage.set(documentsPage);
    } catch (err) {
      this.error.set(this.messageOf(err));
    }
  }

  async loadDocumentsPage(page: number): Promise<void> {
    if (page < 0 || (this.totalPages() > 0 && page >= this.totalPages())) {
      return;
    }
    this.error.set('');
    try {
      this.documentsPage.set(await this.api.getDocuments(page));
    } catch (err) {
      this.error.set(this.messageOf(err));
    }
  }

  async runPipeline(): Promise<void> {
    await this.withBusy(async () => {
      await this.api.runPipeline();
      await this.refreshDashboard();
    });
  }

  async askRag(): Promise<void> {
    await this.withBusy(async () => {
      this.ragResponse.set(await this.api.askRag(this.ragQuestion));
    });
  }

  async askAgent(): Promise<void> {
    await this.withBusy(async () => {
      this.agentResponse.set(await this.api.askAgent(this.agentQuestion));
    });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files && input.files.length ? input.files[0] : null);
    this.uploadResponse.set(null);
  }

  async uploadDocument(): Promise<void> {
    const file = this.selectedFile();
    if (!file) {
      this.error.set('Select a PDF file first.');
      return;
    }

    this.isUploading.set(true);
    this.error.set('');
    try {
      this.uploadResponse.set(await this.api.uploadDocument(file));
      await this.refreshDashboard();
    } catch (err) {
      this.error.set(this.messageOf(err));
    } finally {
      this.isUploading.set(false);
    }
  }

  documentFileUrl(documentId: string, download = false): string {
    const base = `${this.api.apiBaseUrl()}/documents/${encodeURIComponent(documentId)}/file`;
    return download ? `${base}?download=true` : base;
  }

  asJson(value: unknown): string {
    return JSON.stringify(value, null, 2);
  }

  private async withBusy(action: () => Promise<void>): Promise<void> {
    this.isBusy.set(true);
    this.error.set('');
    try {
      await action();
    } catch (err) {
      this.error.set(this.messageOf(err));
    } finally {
      this.isBusy.set(false);
    }
  }

  private messageOf(err: unknown): string {
    return err instanceof Error ? err.message : 'Unexpected error';
  }
}

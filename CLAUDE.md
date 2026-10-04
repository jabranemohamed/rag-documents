# RAG Documents

Application de **document intelligence générique pour PDF** : upload (texte +
images via OCR Tesseract), indexation vectorielle, bibliothèque paginée avec
consultation/téléchargement des PDF, Q&A RAG groundé, agent à outils.
Java 21 / Spring Boot 3.5 (Maven) · Angular 20 · PGVector (Docker, port 5442)
· LLM au choix : Claude (SDK Anthropic officiel) ou Ollama local gratuit.

Démarrage : voir INSTALL.md. Backend sur **8085**, frontend sur **4200**.

## Providers LLM

- `LLM_PROVIDER=claude` (défaut) : Claude Opus 5.5 via `com.anthropic:anthropic-java`.
  Nécessite `ANTHROPIC_API_KEY` **et des crédits API**. Extraction JSON =
  structured outputs ; agent = tool use natif (BetaToolRunner).
- `LLM_PROVIDER=ollama` : gratuit, local, modèle `qwen2.5:14b` (`OLLAMA_MODEL`
  pour changer). JSON contraint via le paramètre `format` d'Ollama ; agent en
  flux 2 étapes (sélection d'outil JSON → exécution Java → réponse finale).
- Abstraction dans `backend/src/main/java/com/docintel/llm/`.
- Les **embeddings sont toujours locaux** (ONNX all-MiniLM-L6-v2, 384 dims,
  Spring AI Transformers) — aucun appel API, quel que soit le provider.

## Backend

Pipeline (`PipelineService`, idempotent — seuls les nouveaux documents sont
traités) : PDF (PDFBox + OCR Tesseract pour pages images, `OcrService`) →
nettoyage → chunks (1000/150) → embeddings → PGVector (table
`document_chunks`, cosinus `<=>`). Un PDF = un document ; `document_id` =
stem du fichier sanitizé.

Endpoints : `/health`, `/pipeline/status`, `/pipeline/run`,
`/documents?page&size` (pagination **côté serveur**, SQL LIMIT/OFFSET, 10 par
page, `size` ≤ 50, renvoie `total_documents`/`total_pages`/`total_chunks`),
`/documents/{id}/file` (PDF inline, `?download=true` pour télécharger,
résolution stricte de l'id, 404 sinon), `/documents/upload` (multipart,
stocke sous `data/raw/uploads`, relance le pipeline), `/rag/ask`, `/agent/ask`.

Agent : 3 outils read-only (`list_documents`, `summarize_pipeline_outputs`,
`ask_documents`) ; mots-clés destructifs bloqués avant tout appel LLM.

## Frontend

Angular standalone + signals, un seul `AppComponent`, trois vues commutées
par le signal `activeView` (pas de router) : **Dashboard** (métriques, upload,
RAG), **Library** (paginée ; nom = lien qui ouvre le PDF, bouton Download),
**Agent**. Thème clair type template admin : bandeau sombre (logo, API Base
URL, onglets), cartes blanches arrondies, accents indigo — palette dans les
variables CSS de `frontend/src/styles.css`. Les totaux affichés viennent
toujours du serveur (`total_documents`/`total_chunks`), jamais de la page.

## Pièges connus

- `Set.of(...).contains(null)` lève un NPE — toujours null-checker avant.
- Les erreurs Ollama arrivent parfois sans content-type JSON : `OllamaProvider`
  lit le corps en String et parse avec Jackson.
- Le texte extrait des PDF peut contenir des octets NUL (0x00) que PostgreSQL
  refuse : nettoyés à l'extraction (`sanitizeExtractedText`) + défense à
  l'insertion.
- Embeddings : `maxLength=512` est **obligatoire** sur
  `TransformersEmbeddingModel` (sinon la troncature rend les chunks à en-tête
  identique indiscernables).
- `num_ctx` Ollama fixé à 16384 (le défaut 4096 tronque les gros documents).
- Modifier `angular.json` exige un redémarrage de `ng serve`.
- `.env` contient la clé API : gitignoré, ne jamais le committer.
- Erreur « credit balance is too low » = pas de crédits API Anthropic →
  `LLM_PROVIDER=ollama` ou achat de crédits.

## Fonctionnalités retirées volontairement — ne pas réintroduire

Le projet était à l'origine un pipeline de claims d'assurance. Ont été
supprimés à la demande : extraction structurée (11 champs), claim_dataset.csv,
data quality/dictionary, query plans CSV, endpoints `/claims*`, audit trail
(`/audit-trail`) et les sections d'UI correspondantes.

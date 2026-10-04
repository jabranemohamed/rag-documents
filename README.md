# RAG Documents — Java + Angular + Claude

Application de document intelligence **générique pour PDF** : upload de
documents (texte **et images**, OCR intégré), indexation vectorielle,
bibliothèque paginée avec consultation/téléchargement des PDF, Q&A RAG
groundé et agent à outils. Backend **Java 21 / Spring Boot 3.5**, frontend
**Angular 20**, LLM **Claude** (SDK officiel Anthropic) ou **Ollama local
gratuit** — aucune dépendance OpenAI.

| Couche | Techno |
|---|---|
| Backend | Spring Boot 3.5, Maven |
| LLM | **Claude Opus 5.5** via `com.anthropic:anthropic-java` (défaut) **ou Ollama local gratuit** (`LLM_PROVIDER=ollama`) |
| Embeddings | ONNX local `all-MiniLM-L6-v2` (384 dims) — aucun appel API |
| Vector store | PostgreSQL + pgvector (Docker, port hôte **5442**) |
| PDF | Apache PDFBox (texte) + **Tesseract OCR** (pages images/scannées) |
| Agent | Tool use natif Claude (ou flux 2 étapes sur Ollama) + guardrails read-only |
| API | Spring Web REST, Swagger via springdoc |
| UI | Angular 20 standalone + signals |

## Démarrage rapide (mode local gratuit)

> Installation complète pas à pas : voir **[INSTALL.md](INSTALL.md)**.

Trois commandes, dans trois terminaux, depuis la racine du projet :

```bash
# 1. Infra : PGVector + Ollama (+ OCR)
docker compose up -d && brew services start ollama
```

```bash
# 2. Backend (port 8085)
cd backend && LLM_PROVIDER=ollama mvn spring-boot:run
```

```bash
# 3. Frontend (port 4200)
cd frontend && npm start
```

Puis ouvrez http://localhost:4200. Pour utiliser Claude au lieu d'Ollama,
retirez `LLM_PROVIDER=ollama` et exportez `ANTHROPIC_API_KEY` avant la
commande 2.

## Fonctionnement

```text
PDF (data/raw, récursif)            POST /documents/upload
        │                                   │
        ▼                                   ▼
Extraction texte (PDFBox) + OCR des pages images (Tesseract)
        ▼
Nettoyage → chunks (1000 car. / 150 overlap)
        ▼
Embeddings locaux ONNX → PGVector (table document_chunks, cosinus <=>)
        ▼
RAG Q&A groundé (sources + distances) · Agent à outils
```

Un PDF = un document ; l'identifiant est dérivé du nom de fichier. Chaque
étape est idempotente : relancer le pipeline ne retraite que les nouveaux
documents.

## Endpoints

| Méthode | Endpoint | Rôle |
|---|---|---|
| GET | `/health` | Statut de l'API |
| GET | `/pipeline/status` | Statut des artefacts générés |
| POST | `/pipeline/run` | Pipeline complet (idempotent) |
| GET | `/documents?page=0&size=10` | Documents indexés, **pagination côté serveur** (SQL LIMIT/OFFSET, 10 par page par défaut, `size` max 50 ; renvoie `page`, `total_pages`, `total_documents`, `total_chunks`) |
| GET | `/documents/{id}/file` | Le PDF original : affichage inline dans le navigateur, ou téléchargement avec `?download=true` |
| POST | `/documents/upload` | Upload d'un PDF (multipart `file`, 25 Mo max) + traitement automatique |
| POST | `/rag/ask` | Question RAG groundée (`{"question", "top_k", "use_cache"}`) |
| POST | `/agent/ask` | Agent documentaire (`{"question"}`) |

Swagger : http://localhost:8085/swagger-ui.html

## Interface (Angular)

Thème **clair** : bandeau de navigation sombre (logo, champ API Base URL,
onglets), contenu sur fond gris clair avec cartes blanches arrondies et
accents indigo. Trois menus :

- **Dashboard** : métriques (API, documents, chunks), upload de PDF +
  Run Pipeline, Q&A RAG avec sources et distances.
- **Library** : bibliothèque paginée (10 documents par page, pagination
  serveur) ; le nom de chaque document est un **lien qui ouvre le PDF** dans
  un nouvel onglet, et chaque ligne a un bouton **Download**.
- **Agent** : assistant documentaire (sélection d'outil visible + réponse
  finale + résultat brut).

La palette se règle via les variables CSS en tête de `frontend/src/styles.css`.

## L'agent

Trois outils read-only : `list_documents`, `summarize_pipeline_outputs`,
`ask_documents` (RAG). En mode Claude, le routage passe par le **tool use
natif** de l'API (boucle gérée par le BetaToolRunner du SDK). En mode Ollama,
flux en deux étapes : sélection d'outil en JSON contraint par schéma →
exécution Java → réponse finale. Dans les deux modes, les requêtes
destructives (delete, drop…) sont bloquées avant tout appel LLM.

## Configuration

| Variable | Valeurs | Défaut |
|---|---|---|
| `LLM_PROVIDER` | `claude` \| `ollama` | `claude` |
| `ANTHROPIC_API_KEY` | clé API (mode claude) | — |
| `CLAUDE_MODEL` | modèle Claude | `claude-opus-5-5` |
| `OLLAMA_MODEL` | tout modèle Ollama | `qwen2.5:14b` |
| `OLLAMA_BASE_URL` | URL du serveur | `http://localhost:11434` |
| `OCR_LANGUAGE` | langues Tesseract (ex. `eng+fra`) | `eng` |
| `TESSDATA_PREFIX` | dossier traineddata | `/opt/homebrew/share/tessdata` |
| `RETRIEVAL_DISTANCE_THRESHOLD` | seuil no-context | `1.25` |

Autres réglages dans `backend/src/main/resources/application.yml`
(préfixe `docintel.*` : chunk-size, chunk-overlap, data-root…).

## Données

- `data/raw/` : les PDF sources (sous-dossiers acceptés) ; les uploads vont
  dans `data/raw/uploads/`.
- `data/processed/` : textes extraits, nettoyés, chunks, manifest.
- `data/output/` : cache des réponses RAG, résumé de traitement.

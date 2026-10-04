# How to Install — RAG Documents

Guide d'installation complet, de la machine vierge à l'application qui tourne.
Les commandes `brew` ciblent macOS ; sur Linux, utilisez votre gestionnaire de
paquets (apt, dnf…) pour les mêmes outils.

## 1. Prérequis

| Outil | Version minimale | Installation (macOS) | Vérification |
|---|---|---|---|
| Java (JDK) | 21 | `brew install openjdk@21` | `java -version` |
| Maven | 3.9 | `brew install maven` | `mvn -version` |
| Docker | 24+ (daemon lancé) | [Docker Desktop](https://www.docker.com/products/docker-desktop/) | `docker ps` |
| Node.js + npm | Node 20+ | `brew install node` | `node --version` |
| Ollama *(mode gratuit)* | dernière | `brew install ollama` | `ollama --version` |
| Tesseract *(OCR, optionnel)* | 5+ | `brew install tesseract` | `tesseract --version` |

> Tesseract est optionnel : sans lui, l'application fonctionne mais les pages
> de PDF scannées/images ne seront pas lues (l'OCR se désactive proprement).

## 2. Cloner le projet

```bash
git clone <url-du-repo>
cd rag-documents
```

## 3. Base vectorielle (PostgreSQL + pgvector)

```bash
docker compose up -d
```

Cela démarre un conteneur `docintel-pgvector` sur le **port hôte 5442**
(user/password/db : `docintel`). Le schéma (`document_chunks`, extension
`vector`) est créé automatiquement au démarrage du backend.

Vérification :

```bash
docker exec docintel-pgvector pg_isready -U docintel -d docintel
```

## 4. Choisir le fournisseur LLM

### Option A — Ollama (gratuit, 100 % local)

```bash
brew services start ollama
ollama pull qwen2.5:14b
```

> `qwen2.5:14b` fait ~9 Go et demande ~16 Go de RAM. Sur une machine plus
> modeste : `ollama pull qwen2.5:7b` puis `export OLLAMA_MODEL=qwen2.5:7b`.

### Option B — Claude (meilleure qualité, nécessite des crédits API)

```bash
cp .env.example .env
# éditez .env et mettez votre clé : ANTHROPIC_API_KEY=sk-ant-...
```

La clé se crée sur https://console.anthropic.com/settings/keys et nécessite
des crédits API (Plans & Billing) — un abonnement claude.ai ne suffit pas.
**Ne commitez jamais `.env`** (déjà exclu par `.gitignore`).

## 5. Backend (port 8085)

```bash
cd backend

# Mode Ollama (gratuit)
LLM_PROVIDER=ollama mvn spring-boot:run

# OU mode Claude
set -a && source ../.env && set +a && mvn spring-boot:run
```

Premier lancement : Maven télécharge les dépendances, puis le modèle
d'embeddings ONNX (all-MiniLM-L6-v2, ~90 Mo) est téléchargé et mis en cache
au premier appel du pipeline.

Vérification :

```bash
curl http://localhost:8085/health
```

Swagger : http://localhost:8085/swagger-ui.html

## 6. Frontend (port 4200)

Dans un second terminal :

```bash
cd frontend
npm install
npm start
```

Ouvrez **http://localhost:4200**.

## 7. Premier usage

1. Déposez des PDF dans `data/raw/` (sous-dossiers acceptés) **ou** utilisez
   le bouton *Upload & Process* du Dashboard.
2. Cliquez *Run Pipeline* (uniquement nécessaire pour les fichiers déposés à
   la main — l'upload lance le pipeline tout seul).
3. Menu **Library** : documents indexés, consultation et téléchargement des PDF.
4. Menu **Dashboard → RAG** : posez une question sur le contenu.
5. Menu **Agent** : liste des documents, résumé de traitement, questions libres.

## 8. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `Connection refused` sur 5442 | Conteneur pgvector arrêté | `docker compose up -d` |
| Port 8085 ou 4200 occupé | Autre process | changez `server.port` dans `application.yml` / `ng serve --port` |
| Réponses RAG/agent en erreur en mode Ollama | Serveur ou modèle absent | `brew services start ollama` puis `ollama pull qwen2.5:14b` |
| `401` / `credit balance is too low` en mode Claude | Clé invalide ou pas de crédits | vérifiez la clé et Plans & Billing dans la console |
| Pages scannées non lues | Tesseract absent | `brew install tesseract` puis relancez le backend |
| Totaux à 0 dans l'UI alors que des documents s'affichent | Page chargée pendant un redémarrage backend | bouton Refresh / F5 |

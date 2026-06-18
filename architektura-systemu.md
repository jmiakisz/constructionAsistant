# System zarządzania budowami z AI — architektura i ustalenia

## Cel systemu
Platforma dla firm budowlanych łącząca zarządzanie projektami z asystentem AI który:
- odpowiada na pytania w kontekście konkretnej budowy
- pilnuje harmonogramu i alarmuje o ryzykach
- uczy się z każdego projektu i buduje wiedzę firmową

---

## Tech Stack

| Warstwa | Technologia |
|---|---|
| Backend | Java 21, Spring Boot 3 |
| Baza danych | PostgreSQL + pgvector |
| Migracje | Flyway |
| Auth | Spring Security + JWT |
| Parsowanie dokumentów | Apache Tika (PDF, DOCX, XLSX) |
| Embeddingi | Lokalny model Python (sentence-transformers) |
| AI / Reasoning | Anthropic API (claude-sonnet-4-6) |
| Storage plików | Hetzner Object Storage (S3-compatible) |
| Hosting | Hetzner VPS (docelowo własny serwer w firmie) |

---

## Model danych

```
User
- id, email, password, name, created_at

Project
- id, name, description, created_at, created_by

ProjectMember
- project_id, user_id, role (OWNER/ADMIN/KIEROWNIK/INZYNIER/KOSZTORYSANT/BRYGADZISTA/PODWYKONAWCA)

Document
- id, project_id, name, file_path
- visible_for_roles (array)
- ai_indexing_mode (enum: FULL / CHUNKS_ONLY / NONE)
- status (PROCESSING/READY/ERROR)
- uploaded_by, created_at

DocumentChunk
- id, document_id
- content (text)
- embedding (vector)
- page_number, chunk_index

Conversation
- id, project_id, user_id, created_at

Message
- id, conversation_id, role, content
- useful_for_knowledge (boolean, default null)
- knowledge_category (TECHNICZNA/FINANSOWA/PODWYKONAWCY/MATERIALY/null)
- tokens_used
- processed_for_knowledge (boolean, default false)
- created_at

ProjectMemory
- id, project_id, role
- content (text)
- updated_at

KnowledgeEntry
- id, company_id, project_id (null = wiedza firmowa)
- content (max 500 znaków)
- embedding (vector)
- source_role
- category (TECHNICZNA/FINANSOWA/PODWYKONAWCY/MATERIALY)
- confidence (ile razy potwierdzone)
- created_at, last_confirmed_at
```

---

## Architektura AI

### Mikroserwis embeddingowy (Python/FastAPI)
- Model: `paraphrase-multilingual-MiniLM-L12-v2`
- Port: 8001
- Endpoint: `POST /embed { text }` → `{ embedding: [...] }`
- Działa lokalnie — zero kosztów, zero danych na zewnątrz
- Model pobiera się automatycznie z HuggingFace (~420MB)

### Dostawca AI (Anthropic API)
- Model: `claude-sonnet-4-6`
- Dane przez API nigdy nie są używane do trenowania modeli
- Logi przechowywane tylko 7 dni
- Płatność za tokeny (pay-as-you-go)

### Abstrakcja providera (łatwa zmiana)
```java
public interface AiProvider {
    String chat(String prompt);
    float[] embed(String text);
}
// implementacje: AnthropicProvider, OpenAiProvider, LocalOllamaProvider
// zmiana przez application.yml
```

---

## Flow dokumentów (RAG)

### Upload dokumentu
```
PDF/DOCX upload
→ użytkownik wskazuje typ dokumentu
→ użytkownik wybiera tryb indeksowania AI
→ Tika wyciąga tekst
→ w zależności od ai_indexing_mode:

FULL:
├── chunking → embeddingi → pgvector
├── cały tekst → Claude #1 → extracted_data + alerty wewnętrzne
└── po zapisaniu extracted_data:
    └── Claude #2 → cross analiza z innymi dokumentami projektu

CHUNKS_ONLY:
└── chunking → embeddingi → pgvector
    (cały dokument nigdy nie leci do Claude)
    (dostępny przez RAG — Claude widzi tylko małe fragmenty)

NONE:
└── tylko zapis na storage
    (zero AI, tylko ręczne pobieranie)

→ status: PROCESSING → READY
```

### Tryby indeksowania — porównanie
```
                    FULL    CHUNKS_ONLY   NONE
Wyszukiwanie RAG:    ✅         ✅          ❌
Ekstrakcja danych:   ✅         ❌          ❌
Cross analiza umów:  ✅         ❌          ❌
Cały tekst do Claude:✅         ❌          ❌
Ręczne pobieranie:   ✅         ✅          ✅
```

> CHUNKS_ONLY to kompromis dla dokumentów confidential —
> można pytać "jakie są kary umowne?" i Claude dostaje
> tylko relevantny fragment, nigdy całą umowę.

### Pytanie użytkownika
```
Pytanie
→ embedding pytania (lokalny model, ~50ms)
→ similarity search w pgvector
  (filtr: tylko dokumenty które user może widzieć)
→ top 5 chunków
→ do promptu
```

---

## Budowanie promptu (przy każdym pytaniu)

Prompt składa się z 5 warstw:

```
1. Dane strukturalne projektu (zawsze)
   budżet, postęp, harmonogram 14 dni,
   aktywne ryzyka, podwykonawcy

2. ProjectMemory per rola (zawsze)
   skumulowana wiedza o tym projekcie

3. KnowledgeEntry — wiedza firmowa (RAG)
   top 5 relevantnych wpisów przez similarity search
   filtrowane: source_role >= user.role

4. Chunki dokumentów (RAG)
   top 5 relevantnych fragmentów przez similarity search
   filtrowane: visible_for_roles >= user.role

5. Historia rozmowy
   ostatnie 10 wiadomości z tej konwersacji

+ Pytanie użytkownika
```

Szacowany koszt promptu: ~3500 tokenów = grosze za zapytanie.

---

## Bezpieczeństwo i uprawnienia

### Hierarchia ról
```
OWNER
  └── ADMIN
        └── KIEROWNIK
              └── INZYNIER / KOSZTORYSANT
                    └── BRYGADZISTA
                          └── PODWYKONAWCA
```

### Zasada widoczności
- Dokument ma `visible_for_roles` — lista ról które mogą go widzieć
- Wiedza ma `source_role` — rola która ją wygenerowała
- User widzi wiedzę jeśli jego rola >= source_role
- Similarity search filtrowany na poziomie SQL — żaden chunk nie wyjdzie poza uprawnienia

### Przykład
```
Brygadzista widzi:
- dokumenty oznaczone dla BRYGADZISTA+
- wiedzę wygenerowaną przez BRYGADZISTA+
- NIE widzi finansów, umów, decyzji zarządu

Kierownik widzi:
- dokumenty oznaczone dla KIEROWNIK+
- wiedzę brygadzistów I kierowników
- NIE widzi danych finansowych admina
```

---

## Agent nocny

### Flagowanie w czasie rzeczywistym (chat)
Claude przy każdej odpowiedzi zwraca flagę — zero dodatkowego kosztu:

```json
{
  "response": "Beton C25/30 zamawiaj min 2 tygodnie przed lanem...",
  "useful_for_knowledge": true,
  "knowledge_category": "MATERIALY"
}
```

Claude zna kontekst rozmowy więc trafnie ocenia co jest wiedzą
a co zwykłą wymianą zdań ("dzięki", "jak się zalogować?").

### Co robi co noc (2:00) — trzy etapy

**Etap 1 — filtr (Haiku, tani):**
```
SELECT WHERE useful_for_knowledge = true
         AND processed_for_knowledge = false
        ↓
Haiku grupuje tematycznie i deduplikuje:
"czy to samo mamy już w knowledge_entry? TAK/NIE"
→ odrzuca duplikaty przed Sonnetem
→ koszt: minimalny (mały wolumen bo już przefiltrowany)
```

**Etap 2 — klasyfikacja i zapis (Sonnet, tylko nowe):**
```
tylko wiadomości które Haiku uznał za nowe
+ kontekst projektu (project_memory, dane strukturalne)
        ↓
Sonnet dla każdego wpisu ocenia:

A) Ogólna wiedza przydatna na innych budowach?
   "beton wiązał wolniej przy mrozie"
   "podwykonawca X opóźnia się średnio 2 tygodnie"
   → KnowledgeEntry (wiedza FIRMOWA, cross-project)
   → embedding → pgvector
   → source_role = rola usera w projekcie

B) Ustalenie specyficzne dla tej budowy?
   "inwestor Kowalski wstrzymał decyzję o stolarce"
   "zmieniono fundamenty z ławowych na płytę +180k"
   → ProjectMemory (wiedza PROJEKTOWA, per projekt per rola)

C) Dotyczy obu?
   "podwykonawca X opóźnił się 2 tygodnie na budowie Y"
   → KnowledgeEntry: "podwykonawca X ma tendencję do opóźnień"
   → ProjectMemory: "podwykonawca X opóźnił się 2 tygodnie"
   → zapis do obu z różnym poziomem szczegółowości
```

**Oznaczenie jako przetworzone:**
```
processed_for_knowledge = true
```

### Podział odpowiedzialności
```
Claude w chacie   → zna kontekst rozmowy → flaga useful (real-time)
Haiku w nocy      → tani → deduplikacja i grupowanie
Sonnet w nocy     → drogi ale mały wolumen → jakościowy wniosek
```

### Co robi co tydzień (niedziela 3:00)
1. Konsoliduje podobne wpisy wiedzy (similarity > 0.85)
2. Łączy duplikaty w jeden wpis z wyższym `confidence`
3. Czyści szum

> **Uwaga:** Tworzenie wpisów wiedzy firmowej (KnowledgeEntry) dzieje się codziennie razem z ProjectMemory — nie co tydzień. Co tydzień odbywa się tylko konsolidacja duplikatów. Dzięki temu wiedza przepływa między projektami następnego dnia.

### Co robi co miesiąc
1. Archiwizuje wiedzę starszą niż 2 lata z niskim confidence
2. Czyści orphan chunki

### Szacowane koszty agenta
```
Real-time flagowanie:  zero extra (ta sama odpowiedź Claude)
Haiku noc:             grosze (mały wolumen useful=true)
Sonnet noc:            ~$0.12/noc = ~$4/miesiąc
─────────────────────────────────────────────────────
Total agent:           ~$4-5/miesiąc
```

---

## Wiedza projektowa vs firmowa

### ProjectMemory (per projekt, per rola)
- Decyzje podjęte na tej budowie
- Problemy i ich rozwiązania
- Ustalenia nieformalne
- Zmiany w projekcie
- Specyficzne dla tej budowy

### KnowledgeEntry — wiedza firmowa (cross-project)
- Wzorce zachowania podwykonawców
- Typowe ryzyka i problemy
- Dobre praktyki wykonawcze
- Wnioski finansowe
- Rośnie i mądrzeje z każdym projektem

### Kluczowa różnica
```
ProjectMemory: "na budowie X zmieniono fundamenty +180k PLN"
KnowledgeEntry: "zmiana fundamentów to średnio +8% budżetu"
```

---

## Historia rozmów

- Każda wiadomość użytkownika i odpowiedź AI zapisywana do bazy
- Per user (nie współdzielona między userami projektu)
- Ostatnie 10 wiadomości leci do każdego promptu
- Przy długich konwersacjach: summary co 20 wiadomości

---

## Analiza dokumentów przy uploadzie

Upload dokumentu triggeruje **trzy równoległe procesy** oraz **dwa wywołania Claude**:

### Procesy przy uploadzie (async, równolegle)
```
Upload dokumentu
        ↓
równolegle:
├── wątek 1: Tika → chunking → embeddingi → pgvector
│   (do wyszukiwania i RAG przy pytaniach)
│
└── wątek 2: Tika → cały tekst → [Wywołanie Claude #1]
    (do ekstrakcji strukturalnej i analizy wewnętrznej)
```

### Wywołanie Claude #1 — analiza pojedynczego dokumentu
Cały tekst dokumentu leci do Claude. Typ dokumentu wskazuje użytkownik przy uploadzie.

```
Typy: UMOWA_INWESTOR / UMOWA_PODWYKONAWCA / KOSZTORYS /
      HARMONOGRAM / PROJEKT_WYKONAWCZY / SWZ / INNE
```

Claude zwraca JSON z trzema sekcjami:
```json
{
  "extracted_data": {
    "kary_opoznienie_procent": 0.05,
    "kara_max_procent": 10,
    "wartosc_kontraktu": 10000000,
    "termin": "2025-12-31",
    "gwarancja_miesiac": 36
  },
  "wewnetrzne_niespojnosci": [
    "§12 mówi kara 0.05% ale §24 mówi 0.08%"
  ],
  "ryzyka": [
    "brak zapisu o karach za wady ukryte"
  ]
}
```

Wynik zapisywany jako `extracted_data` (JSONB) w tabeli Document.

### Wywołanie Claude #2 — cross analiza (po zapisaniu extracted_data)
Nie wysyła pełnych dokumentów — tylko małe JSONy extracted_data wszystkich umów projektu.

```
extracted_data zapisane w bazie
        ↓
pobierz extracted_data wszystkich umów projektu:
- umowa_inwestor.pdf → { kary: 0.05, gwarancja: 60 }
- umowa_podwykonawca_X.pdf → { kary: 0.08, gwarancja: 36 }
- umowa_podwykonawca_Y.pdf → { kary: 0.03, gwarancja: 24 }
        ↓
jeden prompt z wszystkimi JSONami → Claude
        ↓
alerty per poziom (KRYTYCZNY / OSTRZEŻENIE / INFO)
```

Przykładowe alerty:
```
🔴 KRYTYCZNY — niespójność finansowa:
   Kara podwykonawcy X (0.08%) > kara wobec inwestora (0.05%)
   Ryzyko: płacisz więcej niż dostajesz

🔴 KRYTYCZNY — luka gwarancyjna:
   Gwarancja podwykonawcy Y: 24 miesiące
   Gwarancja wobec inwestora: 60 miesięcy
   Luka: 36 miesięcy ryzyka

🟡 OSTRZEŻENIE — semantyczna niespójność:
   Umowa inwestor: "odbiór końcowy"
   Umowa podwykonawca X: "protokół odbioru częściowego"
   Różne punkty startowe gwarancji
```

> **Dlaczego cały dokument przy wywołaniu #1?**
> Chunki są zaprojektowane pod wyszukiwanie, nie pod analizę całości.
> Similarity search może pominąć ważny paragraf lub zgubić zależności
> między paragrafami ("§12 odnosi się do §8"). Cały dokument kosztuje
> ~$0.075-0.30 jednorazowo przy uploadzie — akceptowalne.

> **Dlaczego tylko extracted_data przy wywołaniu #2?**
> Cross analiza nie potrzebuje pełnych tekstów — tylko kluczowych danych.
> Wszystkie extracted_data projektu to kilkaset tokenów, nie dziesiątki tysięcy.

---

## Koszty infrastruktury

### MVP (Hetzner)
```
VPS CPX31 (4 vCPU, 8GB RAM):  ~85 PLN/miesiąc
Hetzner Object Storage 1TB:    ~25 PLN/miesiąc
Anthropic API (chat):          ~50-100 PLN/miesiąc
Anthropic API (agent nocny):   ~15 PLN/miesiąc
Domena:                        ~50 PLN/rok
─────────────────────────────────────────────
Total:                         ~175-210 PLN/miesiąc
```

### Docelowo
Własny serwer w firmie — dane w 100% lokalnie, koszt tylko prąd i sprzęt.

---

## Plan implementacji (MVP ~1-2 tygodnie z Claude Code)

```
Etap 1: Fundament
- setup Spring Boot + Flyway + migracje
- encje JPA + repozytoria
- auth (register/login/JWT)
- CRUD projekty + członkowie z rolami

Etap 2: Dokumenty
- upload PDF/DOCX + Tika
- chunking z overlap
- async job (PROCESSING → READY)

Etap 3: Embeddingi
- mikroserwis Python/FastAPI
- sentence-transformers lokalnie
- pgvector + zapisywanie embeddingów

Etap 4: Chat
- endpoint POST /api/projects/{id}/chat
- budowanie promptu (5 warstw)
- RAG + Anthropic API
- zapis historii rozmów

Etap 5: Agent nocny
- @Scheduled jobs
- budowanie ProjectMemory
- budowanie KnowledgeEntry
- konsolidacja wiedzy

Etap 6: Dashboard + monitoring kosztów
```

---

## Ryzyka i decyzje architektoniczne

### OCR dla skanów
Tika wykrywa czy PDF jest natywny czy skan. Dla skanów wymagany Tesseract OCR.
Na MVP można odrzucać skany i wymagać natywnych PDF — duże uproszczenie.

### Dedykowany parser XLSX (kosztorysy)
Zwykły chunking niszczy relacje między kolumnami tabeli. Każdy wiersz kosztorysu
zamieniany na czytelny tekst przed embeddingiem:
```
"Pozycja: Wylanie fundamentów, Ilość: 50m3, Cena jedn.: 400 PLN, Wartość: 20 000 PLN"
```

### Hybrid Search
pgvector (wyszukiwanie semantyczne) + TSVector (pełnotekstowe) w formule RRF.
Samo wyszukiwanie wektorowe gubi numery artykułów umów, numery działek, oznaczenia norm.

### Słownik synonimów budowlanych
Model embeddingowy może nie łączyć "beton B25" z "beton C25/30".
Słownik synonimów stosowany przed embeddingiem rozwiązuje problem bez zmiany modelu.

### Job queue zamiast @Scheduled
Tabela `job_queue` w Postgres zamiast Spring @Scheduled dla procesów uploadów.
Odporność na restarty serwera, pełna transakcyjność. Camunda/State Machine — overhead na MVP.

### Role globalne vs per projekt
Model danych przyjęty:
```
User
- company_role (OWNER / ADMIN / MEMBER)  ← dostęp globalny

ProjectMember
- project_role (KIEROWNIK / INZYNIER / KOSZTORYSANT
               / BRYGADZISTA / PODWYKONAWCA)  ← per projekt
```

OWNER i ADMIN widzą wszystkie projekty automatycznie.
Reszta tylko projekty do których została przypisana z konkretną rolą.

> ❓ **Pytanie na przyszłość:**
> source_role w KnowledgeEntry = rola użytkownika w tym konkretnym projekcie.
> Problem: ta sama osoba może być KIEROWNIKIEM na budowie X i BRYGADZISTĄ na budowie Y.
> Wiedza wygenerowana jako BRYGADZISTA może być niedostępna dla tej osoby
> gdy działa jako KIEROWNIK na innym projekcie.
> Rozważyć: czy source_role powinna być rolą w projekcie, najwyższą rolą w firmie,
> czy poziomem wiedzy ocenianym przez agenta niezależnie od roli autora?

### On-premise — świadoma decyzja
```
Wariant przyjęty:
backend + embeddingi lokalnie (własny serwer docelowo)
Claude API w chmurze Anthropic

Dane wychodzące na zewnątrz:
- przy uploadzie: cały tekst dokumentu (jednorazowo)
- przy pytaniu: ~3500 tokenów (fragmenty + kontekst)
- Anthropic API policy: dane nie są używane do trenowania modeli

Wariant odrzucony (on-premise LLM):
- wymaga GPU (~15-30k PLN)
- gorsza jakość niż Claude
- AiProvider interface umożliwia migrację w przyszłości bez przepisywania kodu
```

1. Odpowiada na pytania na podstawie Twoich dokumentów z podaniem źródła
2. Pamięta kontekst projektu i uczy się z każdej rozmowy
3. Wykrywa niespójności między umowami semantycznie
4. Agent alarmuje o ryzykach zanim zapytasz
5. Wiedza firmowa rośnie z każdym projektem
6. Każda rola widzi tylko to co powinna

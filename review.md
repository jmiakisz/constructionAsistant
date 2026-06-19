# Code Review — constructionAsistant

Data: 2026-06-19

---

## Krytyczne

### 1. Brak autoryzacji na `/api/admin/**`
**Plik:** `AdminController.java:23`

Żaden endpoint admina nie ma `@PreAuthorize`, `@Secured` ani reguły w `SecurityConfig`. Każdy zalogowany użytkownik z rolą `MEMBER` może:
- uruchamiać nocne joby AI (`POST /api/admin/nightly/run`)
- czytać całą bazę wiedzy firmy (`GET /api/admin/knowledge`)
- przeglądać wiadomości wszystkich użytkowników (`GET /api/admin/messages/unprocessed`)

**Fix:**
```java
// SecurityConfig.java
.requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "OWNER")
```

---

### 2. Hardcoded fallback sekret JWT
**Plik:** `application.yml:29`

```yaml
jwt.secret: ${JWT_SECRET:zmien-ten-sekret-w-produkcji-min64znaki-lorem-ipsum-dolor-sit-amet}
```

Jeśli `JWT_SECRET` nie jest ustawiony w środowisku, znany publiczny sekret podpisuje wszystkie tokeny. Atakujący może sfałszować JWT dla dowolnego użytkownika.

**Fix:** Usunąć fallback, wyrzucić błąd startu gdy zmienna nie jest ustawiona:
```yaml
jwt.secret: ${JWT_SECRET}
```

---

## Wysokie

### 3. Hash hasła serializowany do JSON
**Plik:** `User.java:25`

Pole `password` nie ma `@JsonIgnore`. Każdy endpoint zwracający obiekt `User` (lista członków projektu, profil) wycieka hash bcrypt do klienta.

**Fix:**
```java
@JsonIgnore
private String password;
```

---

### 4. IDOR — alerty projektu bez sprawdzenia członkostwa
**Plik:** `ProjectController.java:47`

`GET /{id}/alerts` przekazuje `id` bezpośrednio do `alertRepository.findByProjectIdOrderByCreatedAtDesc(id)`, z pominięciem `ProjectService`. Każdy zalogowany użytkownik może odczytać alerty dowolnego projektu.

**Fix:** Dodać sprawdzenie członkostwa przed zapytaniem do repozytorium, analogicznie jak w pozostałych handlerach w tym kontrolerze.

---

### 5. IDOR — dokument dostępny bez weryfikacji projektu
**Plik:** `DocumentController.java:63`

`documentService.get(documentId, userId)` sprawdza właściciela dokumentu, ale ignoruje `projectId` z URL-a. Można pobrać dokument z obcego projektu znając tylko `documentId`.

**Fix:** Przekazać `projectId` do serwisu i dodać warunek `document.getProject().getId().equals(projectId)`.

---

### 6. Infinite loop w `pollBatch` — blokuje scheduler permanentnie
**Plik:** `NightlyAgentService.java:231`

Nieskończona pętla `while(true)` czeka na status `"ended"` z Anthropic Batch API bez żadnego timeout'u. Jeśli batch utknął lub API zwróciło nieznany status (`"expired"`, `"cancelling"`), jedyny wątek Spring schedulera blokuje się na zawsze, trzymając otwartą transakcję i row-level locki w bazie.

**Fix:** Dodać deadline bazowany na `Instant`:
```java
Instant deadline = Instant.now().plus(Duration.ofHours(2));
while (Instant.now().isBefore(deadline)) {
    // ...
    if ("ended".equals(status)) break;
    Thread.sleep(30_000);
}
// obsłuż brak zakończenia jako błąd
```

---

### 7. Wiadomości oznaczane jako przetworzone mimo błędu AI
**Plik:** `NightlyAgentService.java:226`

Linie 226–228 bezwarunkowo ustawiają `processedForKnowledge=true` dla wszystkich wiadomości niezależnie od tego, czy ekstrakcja wiedzy się powiodła. Błąd Sonnet = wiedza z tych konwersacji utracona na zawsze, bez żadnego alertu.

**Fix:** Oznaczać wiadomości jako przetworzone tylko po potwierdzeniu pomyślnego zapisu `KnowledgeEntry`. Przy błędzie logować i pozostawić `processedForKnowledge=false` do ponownego przetworzenia.

---

### 8. Brak walidacji plików uploadowanych
**Plik:** `DocumentController.java:27`

Endpointy `/upload` i `/bulk` nie weryfikują MIME type, rozszerzenia ani rozmiaru pliku. Umożliwia to:
- upload ZIP bomb (mały skompresowany plik → gigabajty po rozpakowaniu, wyczerpanie heap/dysku)
- path traversal jeśli oryginalna nazwa pliku trafia do ścieżki zapisu

**Fix:**
```java
private static final Set<String> ALLOWED_TYPES = Set.of(
    "application/pdf", "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain"
);
private static final long MAX_SIZE = 50 * 1024 * 1024; // 50 MB

if (!ALLOWED_TYPES.contains(file.getContentType())) throw new IllegalArgumentException("Niedozwolony typ pliku");
if (file.getSize() > MAX_SIZE) throw new IllegalArgumentException("Plik zbyt duży");
```

---

## Średnie

| Plik | Linia | Problem |
|------|-------|---------|
| `ChatService.java` | 190 | `maybeCompact` łyka wyjątek po zapisie summary → stare wiadomości nie są usuwane, summary rośnie bez końca przy każdym chacie |
| `DocumentService.java` | 48 | Plik zapisywany na dysk przed commitem DB → po rollbacku transakcji plik zostaje jako sierota |
| `DocumentService.java` | 71 | `uploadBulk` bez `@Transactional` → częściowy bulk upload commituje pliki 1..N-1 przy błędzie na N, bez możliwości rollbacku |
| `AnthropicService.java` | 50 | Brak null-checka na odpowiedź API → NPE/IOOBE przy błędzie 429/529 |
| `DocumentAnalysisService.java` | 97 | Nieograniczona budowa promptu — pełne JSONy wszystkich dokumentów projektu w jednym prompcie → przekroczenie context window przy dużych projektach |
| `DocumentAnalysisService.java` | 159 | Raw tekst dokumentu wstrzykiwany do promptu bez sanityzacji → prompt injection attack |
| `GlobalExceptionHandler.java` | 13 | Brak catch-all `@ExceptionHandler(Exception.class)` → nieobsłużone wyjątki mogą zwracać stack trace do klienta |
| `MessageRepository.java` | 13 | Metody `findByConversationId*` filtrują tylko po `conversationId`, nie po `userId` → IDOR na poziomie wiadomości |
| `Conversation.java` | 38 | Kolekcja `messages` bez `@BatchSize` w kontekście list → N+1 queries |

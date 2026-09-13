# IT-alane infoagent

## Eesmärk

IT teenuste infoagent, mis võimaldab kasutajal teadmusbaasi alusel küsida selle kohta küsimusi ning vastab ainult teadmusbaasis leiduva info põhjal koos allikaviidetega.


## Rakenduse tehniline info

| Komponent | Versioon / märkus |
|-----------|-----------------|
| Java | **21** |
| Spring Boot | **4.0.8** |
| Spring AI | **2.0.0** (BOM) |
| OpenAI mudel (vaikimisi) | `gpt-4o-mini`|
| Build | **Gradle 9.7.1** |


## API

### `GET /api/v1/health`

Rakenduse *health* kontroll (ilma OpenAI kutseta).

Näidipäring kasutades curl-i:
```bash
curl -s http://localhost:8080/api/v1/health
```

Vastuse näidis:
```json
{ "status": "UP" }
```

### `POST /api/v1/agent/ask`

Agendi kasutamise päring.

Näidispäring kasutades curl-i:
```bash
curl -s -X POST http://localhost:8080/api/v1/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"Kuidas taotleda ligipääsu GitLabile?"}'
```

| Väli | Reegel |
|------|--------|
| `question` | Kohustuslik, mitte tühi, max 2000 tähemärki |
| `sessionId` | Valikuline; max 100 tähemärki; ainult `a-zA-Z0-9_-` |

Õnnestunud vastus (näide):
```json
{
  "answer": "GitLabi ligipääsu taotlemiseks … [allikas: gitlab-access.md]",
  "sources": [
    {
      "file": "gitlab-access.md",
      "title": "GitLab ligipääs",
      "excerpt": "…"
    }
  ],
  "confidence": "high",
  "refused": false,
  "refusalReason": null
}
```

Keeldumine (näide):
```json
{
  "answer": "Kahjuks ei saa ma selle päringuga jätkata. …",
  "sources": [],
  "confidence": "low",
  "refused": true,
  "refusalReason": "Keeldutud turvapoliitika alusel"
}
```

### HTTP staatused

| Kood | Millal |
|------|--------|
| 200 | Agendi vastus (ka `refused: true`) |
| 400 | Vigane JSON, tühi/liiga pikk küsimus, vigane `sessionId` |
| 429 | Rate limit ületatud |
| 503 | `OPENAI_API_KEY` puudub |
| 502 | OpenAI / provider viga (avalik teade + correlationId logis) |

### Interaktiivne klient

Rakenduse kiireks käsitsi katsetamiseks/testimiseks on võimalik kasutada `./agent.sh` skripti, mis võimaldab interaktiivselt agendiga käsurealt suhelda.

Eeldab, et rakendus jookseb samas masinas pordil 8080.

```bash
./agent.sh # (ilma sessioonita)

# või

./agent.sh --session demo-1
```

Valikuliselt: `AGENT_BASE_URL`, `AGENT_SESSION_ID`.


## Turvalisus

- **Sisendi valideerimine enne LLM-i:** tühjad/liiga pikad küsimused → 400; teadaolevad injection-mustrid ja secret-laadsed mustrid → `refused` ilma LLM kutseta; isikukoodi-laadsed numbrid maskitakse.
- **Prompt injection:** system ja user rollid on eraldatud; kasutaja sisendit ei käsitleta süsteemijuhisena; prompt + järelkontroll takistavad prompti/tööriistade lekkeid.
- **Ulatus:** ainult allowlist tööriistad teadmusbaasi lugemiseks; faktiline vastus peab olema seotud loetud allikatega.
- **Rate limiting:** vaikimisi 10 päringut minutis IP kohta (`POST /api/v1/agent/ask`).
- **Andmed:** täielikku küsimust ei logita


## Konfiguratsioon

Näidis: `.env.example`

| Muutuja | Vaikimisi | Kirjeldus |
|---------|-----------|-----------|
| `OPENAI_API_KEY` | *(tühi)* | Kohustuslik agendi funktsionaalsuse kasutamiseks ja integratsioonitestide jooksutamiseks |
| `OPENAI_MODEL` | `gpt-4o-mini` | Keelemudel |
| `OPENAI_TEMPERATURE` | `0.2` | Mudeli temperatuur |
| `KNOWLEDGE_PATH` | `knowledge` | Teadmusbaasi kaust |
| `KNOWLEDGE_SEARCH_TOP_K` | `3` | Otsingu top-K |
| `AGENT_SESSION_MAX_MESSAGES` | `20` | Sõnumeid sessiooni aknas |
| `AGENT_SESSION_MAX_SESSIONS` | `1000` | Max sessioone mälus |
| `AGENT_SESSION_TTL` | `45m` | Sessiooni TTL |
| `AGENT_GROUNDING_ENABLED` | `true` | Vastuse allikatega kokkusobivuse kontroll (UNGROUNDED); `false` lülitab grounding-keeldumise välja |
| `AGENT_RATE_LIMIT_ENABLED` | `true` | Rate limit |
| `AGENT_RATE_LIMIT_RPM` | `10` | Päringuid minutis IP kohta |
| `AGENT_TRUST_FORWARDED_HEADERS` | `false` | Proxy IP pealkirjad |

Rakenduse seadistus: `src/main/resources/application.yml`.


## Käivitamine lokaalselt

### Eeldused

- JDK **21** (puudumisel proovib Gradle toolchain automaatset resolverit)
- OpenAI API võti kas keskkonnamuutujana `OPENAI_API_KEY` või lisada `.env` faili.


### Rakenduse käivitamine käsurealt

```bash
export OPENAI_API_KEY=sk-...   # või loe .env-st (mitte commiti)
./gradlew bootRun
```


### Testide käivitamine

Unit testide käivitamiseks:

```bash
./gradlew test
```

HTML raport: `build/reports/tests/test/index.html`  

### Integratsioonitestid

```bash
./gradlew integrationTest
```

- Vajavad võtit (Gradle `doFirst` peatab käivituse, kui võti puudub).
- Rate limit on nendes testides välja lülitatud.
- LLM on stohhastiline — testid kontrollivad käitumist (refused, allikad, lekke puudumine), mitte sõna-sõnalist teksti.

HTML raport: `build/reports/tests/integrationTest/index.html`


### CI (GitHub Actions)

Fail: `.github/workflows/ci.yml`.

| Job | Tingimus                                                           | Artefakt |
|-----|--------------------------------------------------------------------|----------|
| Unit tests | Alati (push/PR `main`, `workflow_dispatch`)                        | `unit-test-report` |
| Integration tests | Pärast unit’i; ainult siis kui secret `OPENAI_API_KEY` on määratud | `integration-test-report` |

Kui secret puudub, jäetakse integratsioonitestid vahele (notice logis). Artefaktide säilitus: 30 päeva.

Tulemuste ülevaatus: GitHub **Actions** → vali workflow run → **Artifacts** (`unit-test-report` / `integration-test-report`).
# Lahenduse kokkuvõte

## Arhitektuur

```
Klient
  → RateLimitFilter (IP-põhine)
  → AgentController (sisendi validatsioon)
  → AgentService
       → InputGuard / SensitiveDataRedactor   (sisendi kontroll, tundlike andmete eemaldus enne LLM-i)
       → ChatClient (LLM päring kasutaja küsimuse alusel)
       → KnowledgeTools → KnowledgeBase
       → applyPostRules (järelfiltrid allikate korrastamiseks, viitamiseks, vastuse grounding, lekete kontroll)
  → AskResponse JSON
```

Vastutused on eraldatud: HTTP kiht valideerib päringu kuju, turvakihid otsustavad enne mudelit, Spring AI teeb tool calling’u, rakenduse järelkontroll sunnib allikaviiteid ja keeldumisi ka siis, kui mudel eksib.


## Tööriistad ja teadmusbaas

Agendile on kasutatavad ainult järgnevad tööriistad (allowlist):

| Tool | Seletus |
|------|---------|
| `list_topics` | Teemade loetelu (suhteline failitee + faili pealkiri) |
| `search_knowledge` | Lühikeste märksõnadega otsing (eelvaade; **ei** ole allikas) |
| `get_document` | Ühe faili täisteksti päring; **peamiselt** selle kaudu loetud failid lähevad `sources`-isse |

Kõik tööriistad loevad ainult teadmusbaasis olevaid dokumente. Üldist „käivita käsk“ või välist API tööriista pole.

Teadmusbaas asub repositooriumi `knowledge` kaustas (markdown, sh alamkaustad). Käivitamisel laetakse mällu kõik `**/*.md` failid. Otsinguna on kasutusel primitiivne *full-scan* + skoori arvutus (pealkiri/failinimi/sisu, stop-sõnad, top-K).


## Süsteemiprompt ja rollid

Fail: `src/main/resources/prompts/system-prompt.md`.

Määrab rolli (IT FAQ), eesti keele, tööriistade kasutuse, allikaviited, keeldumised ja keelud (prompti/tööriistade lekke vastu).

Kasutaja poolt antav sisend on alati **user** rollis ja seda ei käsitleta süsteemijuhisena. System ja user rollid on Spring AI vahendite kaudu eraldatud.


## Sessioon

Valikuline väli `sessionId` (1–100 märki, muster `[a-zA-Z0-9_-]`).

- Ilma `sessionId`-ta: iga küsimus on eelnevast sõltumatu.
- Sama `sessionId`-ga: in-memory vestlusmälu (järelküsimused).
- Limidid: max sõnumeid akna kohta, max sessioone, TTL (vaikimisi 45 min).


## Järelkontroll (`applyPostRules`)

Rakenduse tasemel (mitte ainult promptis):

- Kui mudeli vastus on refused ja allikad on tühjad, keeldutakse vastamast.
- Kui päringul on `sessionId`, aga agent seekord dokumenti ei lugenud:
  - kui eelmise vastuse allikad on veel mälus **ja** uus vastus sobib nendega kokku → kasutatakse neid allikaid uuesti (sh viide vastuses);
  - vastasel juhul võib vastus tulla tühjade allikatega ja `confidence: low` (prompt juhib mudelit siiski alati `get_document` kutsuma).
- Vastuses lubatakse allikaviiteid **ainult** failidele, mida agent selle päringu jooksul tegelikult luges (mitte mudeli väljamõeldud failinimed).
- **Allikatega kokkusobivuse kontroll** (lihtne tekstivõrdlus, mitte “tõeline” faktikontroll):
  1. iga link (URL) vastuses peab olema ka allika tekstis;
  2. iga number vastuses peab olema allikas; kui numbri järel on sõna (nt *„2 tööpäeva“*), peab see sõna allikas samuti esinema;
  3. kogu vastusest peab vähemalt **~70%** sisulistest sõnadest (vähemalt 4 tähte) leiduma allikates;
  4. iga sisuline lause eraldi peab allikatega kattuma vähemalt **~50%** ulatuses — muidu keeldutakse.
- Kui vastus üritab lekkida tööriistade nimesid või sisemist kataloogi, see eemaldatakse / keeldutakse.
- Kui vastuses puudub inimloetav viide, lisatakse vajadusel `[allikas: fail.md]`.


## Turvalisus ja põhjendused

### Sisendi valideerimine enne LLM-i

- Filtreeritakse välja tühjad ja liiga pikad küsimused (max 2000 tähemärki) → HTTP 400, LLM-i ei kutsuta.
- Kontrollitakse teadaolevaid injection-mustreid (nt *ignore previous instructions*, *you are now*, *system:*, eesti vasteid jms) → **kohe `refused: true`**, LLM-i ei saadeta.
- Tundlike andmete kontroll (API võtme / parooli-laadsed mustrid) → refused; isikukoodi-laadsed numbrid maskitakse enne LLM-i saatmist.

**Põhjendus (injection):** refused-first, mitte „hoiatusega LLM-i“. Selge piir; ei raiska tokeneid ega riski, et „hoiatatud“ mudel ikkagi lekib või täidab ründejuhist.

Kui kasutaja päring sisaldab legitiimset küsimust, aga ka ründavat juhist, siis eelistatakse turvakeeldumist või ainult lubatud osa käsitlemist nii, et väliselt ette antud juhist ei täideta. Turvakeeldumistel on teadlikult üldine põhjus, et kasutajale ei lekiks, milline turvareegel täpsemalt piirangu põhjustab.

### Prompt injection

- System ja user rollid on eraldatud; kasutaja sisendit ei kasutata kunagi süsteemipromptiks.
- Prompt keelab süsteemiprompti, toolide ja sisemiste reeglite avaldamise; järelkontroll filtreerib lekkekahtlust.

### Ulatus ja tööriistad

- Mudeli jaoks on kasutatavad ainult allowlist-is olevad kindlad tööriistad.
- Teadmusbaas on failisüsteemi sandbox (ainult base path all).
- Faktiline vastus peab olema seotud tool’ide loetud allikatega; vastasel juhul keeldutakse või antakse tulemus, mille usaldus on madal.

### Rate limiting

- Vaikimisi sees: 10 päringut minutis kliendi IP kohta (`POST /api/v1/agent/ask`).
- Protsessisisene loendur (ei ole Redis/hajus).
- `X-Forwarded-For` usaldamine on vaikimisi **väljas** (`AGENT_TRUST_FORWARDED_HEADERS=false`) — lülita sisse ainult usaldusväärse proxy taga.

### Andmete töötlemine

| Andmed | Käsitlus |
|--------|----------|
| Kasutaja küsimus | Pärast *guard*-e ja filtreid saadetakse OpenAI mudelisse (koos süsteemiprompti ja tool-tulemustega) |
| Teadmusbaas | Staatiline markdown; secret-laadne sisu blokeeritakse/maskitakse toolides |
| Logid | Täielikku küsimust ei logita; injection puhul `sessionHash`, `reasonCode`, pikkus |
| sessionId | Logides hashitud |
| API vead | Correlation ID logis ja osaliselt kliendile (5xx) |
| `OPENAI_API_KEY` | Ainult keskkonnamuutuja / secret — **mitte repos** |

OpenAI töötleb päringut vastavalt nende tingimustele. Ära saada päris paroole, võtmeid ega isikuandmeid.


## Disainiotsused

| Otsus | Põhjendus                                                                            |
|-------|--------------------------------------------------------------------------------------|
| Markdown failid repos | Lihtne auditeerida, versioonida; ei vaja välist DB-d                                 |
| Injection → kohe refused | Selge piir, ei raiska tokeneid ega riski mudeli lekkega                              |
| Allikad peamiselt `get_document` / `list_topics` kaudu | Search preview ei tohi olla peidetud allikas; allikaviide peab olema kontrollitav    |
| In-memory session | Piisav järelküsimuste demoks; multi-instance vajaks Redis jms                        |
| Eraldi `test` ja `integrationTest` | Unit alati CI-s; OpenAI kulud/võti eraldi                                            |
| Temperature 0.2 | Stabiilsemad FAQ vastused                                                            |
| Järelkontroll rakenduses, mitte ainult promptis | LLM võib eksida; `sources` ja keeldumised peavad olema rakenduse reeglitega sunnitud |


## Teadaolevad piirangud ja puudused

- Teadmusbaasi otsinguks on kasutusel lihtne full-scan. Suure andmemahu, sünonüümide või ebatäpse keele puhul võib tulemus olla nõrk; semantiline RAG / Lucene puudub teadlikult.
- Mudeli *grounding* on heuristiline (sõnade kattuvus). See ei tõesta fakte loogiliselt — mudel võib segada kasutaja antud valeväiteid allikaviidetega, kui nende kattuvus on piisavalt suur.
- Tundlike andmete filtreerimine toimub regex-i põhiselt; see ei kata kõiki võimalikke mustreid ja kombinatsioone.
- Rakendus hoiab sessioonide infot mälus. Teades teise kasutaja `sessionId`-d on võimalus mudelilt kätte saada teise kasutaja eelneva sessiooni infot. Mälus hoitav sessiooniinfo ei püsi peale rakenduse taaskäivitamist ega ole jagatud mitme rakendusinstantsi vahel.
- Rate limit loendurit hoitakse mälus ja samuti ei püsi peale rakenduse taaskäivitamist ega ole jagatud mitme rakendusinstantsi vahel.
- Teadmusbaasi allikates kasutatav `excerpt` on võetud faili algusest ja ei pruugi alati olla täpne vastuse lõik.
- Erinevate mudelite puhul võib käitumine erineda, system prompt ja post-rules leevendavad seda, kuid ei garanteeri täielikku stabiilsust.
- Turvakeeldumistel on hetkel teadlikult üldine põhjus, et kasutajale ei lekiks, milline turvareegel täpsemalt piirangu põhjustab.
# Lahenduse kokkuvõte

## Agendi arhitektuur

```
Klient
  → RateLimitFilter (IP-põhine piirang POST /api/v1/agent/ask peal)
  → AgentController (JSON + Bean Validation: question, sessionId)
  → AgentService
       → InputGuardService          (injection-mustrid → vastus kohe refused)
       → SensitiveDataRedactor      (secret → vastus refused; PII maskimine)
       → ChatClient (süsteemiprompt + KnowledgeTools)
            → list_topics / search_knowledge / get_document
            → KnowledgeBase (mällu laetud markdown)
       → ToolSourcesBuffer / SessionSourcesCache
       → applyPostRules
            → allikad, viited, lekked, grounding
            → hybrid: lexical soft-fail → GroundingJudge
              (eraldi tool-free ChatClient)
  → AskResponse JSON
```

### Süsteemiprompt

Süsteemiprompt asub failis: `src/main/resources/prompts/system-prompt.md`.


## Turvalisus ja põhjendused

### Sisendi valideerimine enne keelemudelit

Enne kasutaja sisendi LLM-i saatmist tehakse täiendavad kontrollid ilma keelemudelita:

* Sisendi pikkuste kontroll
* Tihti esinevate injection mustrite kontroll (nt "you are now", "unusta reeglid" jne)
* Saladuste või paroolide kontroll sisendis
* Isikukoodi kontroll sisendis

Ründava sisendi puhul tagastatakse keelduv vastus, saladuste või isikukoodi puhul neid andmeid LLM-i ei edastata.

Kui päringus on legitiimne küsimus ja ründav juhis, eelistatakse turvakeeldumist või ainult lubatud osa nii, et välist ründejuhist ei täideta. Turvakeeldumistel (injection, secret, leke) on API-s teadlikult sama SECURITY tekst, et ei lekiks, milline reegel täpselt rakendus. Detailne `reasonCode` jääb logisse.

### Prompt injection

- System ja user rollid on eraldatud
- Süsteemiprompt keelab prompti, toolide ja sisemiste reeglite avaldamise
- Järelkontroll püüab lekkeid kinni (nt tool nimed)

### Mudeli tööriistad

- Mudelil on ainult allowlist-tööriistad teadmusbaasi jaoks
- Faktiline vastus peab olema seotud loetud (või sessioonist turvaliselt taaskasutatud) allikatega

### Rate limiting

- Vaikimisi sisse lülitatud. 10 päringut minutis kliendi IP kohta (seadistatav), ainult `POST /api/v1/agent/ask`.


### Andmete töötlemine

* Kasutaja sisend/küsimus saadetakse peale guard-e/filtreid OpenAI mudelisse
* Terviklikku küsimust ei logita, keeldumisel logitakse nt sessionHash, reasonCode

OpenAI töötleb andmeid vastavalt nende teenusetingimustele. Ära sisesta päris paroole, võtmeid ega detailseid isikuandmeid.


## Disainiotsused

| Otsus                                           | Põhjendus                                                                                                             |
|-------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| Teadmusbaas: markdown failid repositooriumis    | Lihtne demo jaoks kasutada ja versioneerida                                                                           |
| Tuvastatud injection → vastus kohe `refused`      | Konkreetne sisendi piir, vähem tokenite kulutamist, väiksem lekke-/allumise risk                                      |
| In-memory session + rate limit                  | Piisav demoks ja ühe instantsi jaoks, multi-instance vajaks jagatud salvestust                                        |
| Mudeli temperatuuri parameeter  0.2             | Stabiilsemad, vähem „loovad“ mudeli vastused                                                                          |
| Järelkontroll rakenduses, mitte ainult promptis | LLM võib eksida; `sources` ja keeldumised peavad olema reeglitega sunnitud                                            |
| Hybrid grounding                                | Lexical püüab kindlad hallutsinatsioonid (numbrid/URL-id); judge pehmendab parafraase ilma hard-reegleid lõdvendamata |
| Ühtne SECURITY tekst API-s                      | Ei anna ründajale tagasisidet, milline filter rakendus                                                                |


## Teadaolevad piirangud ja puudused

- **Otsing** on lihtne full-scan. Suure mahu, sünonüümide või ebatäpse sõnastuse korral võib tabavus olla nõrk.
- **Grounding** on kihiline heuristika (lexical + valikuline LLM judge). Hybrid lisab soft-faili korral latentsust ja kulu.
- **Tundlike andmete** tuvastus on regex-põhine ega kata kõiki võimalikke mustreid
- **Sessioonid** on mälus, taaskäivitus kustutab need, mitu instantsi ei jaga seisu, `sessionId` teadmine võimaldab teise vestluse konteksti kuritarvitamist (puudub päris autentimine).
- **Rate limit** on samuti protsessisisene — sama piirang taaskäivituse ja multi-instance kohta.
- Allika **excerpt** on tüüpiliselt faili algusest (pikkuse piiranguga) ega pruugi olla just see lõik, millest vastus tuli.
- Erinevad mudelid käituvad erineval. System prompt ja post-rules leevendavad seda, aga ei garanteeri täielikku stabiilsust.
- Turvakeeldumise põhjus on kasutajale teadlikult üldine, diagnoosimiseks tuleb vaadata logisid (`reasonCode`, correlation id).

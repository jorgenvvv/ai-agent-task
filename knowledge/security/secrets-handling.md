# Saladuste ja võtmete haldus

Juhend, kuidas organisatsiooni teenustes hoitakse paroole, API võtmeid ja muid saladusi. Eesmärk on vältida lekkeid reposse ja logidesse.

## Põhireeglid

- Saladusi **ei** commit'ita Git'i (sh `.env`, private key'd, connection string'id).
- Tootmise saladused elavad vault'is või CI/CD protected variables'ites.
- Arenduskeskkonna näidisväärtused lähevad ainult `.env.example` faili ilma päris saladusteta.

## Kus saladusi hoida

| Keskkond | Koht | Märkus |
|----------|------|--------|
| Kohalik arendus | isiklik `.env` (gitignore'd) | Ära jaga kolleegidele chati kaudu |
| CI/CD | GitLab CI/CD variables (masked + protected) | Ainult vajalikud job'id |
| Kubernetes | Sealed Secrets või cluster secret store | Ei pane plain Secret'eid pikaajaliselt git'i |
| Jagatud tiimi saladus | Organisatsiooni vault (tiimi path) | Ligipääs rollipõhine |

## Lekke kahtlus

1. Pööra kohe kompromiteeritud võti/parool (rotate).
2. Eemalda saladus ajaloost ainult turvatiimi juhiste järgi (force-push ei ole vaikimisi OK).
3. Teavita turvaintsidendi kanalit (vt `security/incident/response.md`).

## Hea tava

- Eelista lühikese elueaga tokeneid pikaajalistele paroolidele.
- Ära logi Authorization headereid ega request body'sid, mis võivad saladusi sisaldada.
- Review's keela MR, kui diffis on päris saladus — isegi "ajutine".

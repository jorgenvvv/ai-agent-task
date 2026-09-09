# CI/CD pipeline

Kirjeldus standardse GitLab CI/CD pipeline'i kohta SMIT teenuste jaoks. Pipeline on defineeritud failis `.gitlab-ci.yml` teenuse repos.

## Pipeline etapid

1. **build** — kompileerimine, sõltuvuste tõmbamine, artefaktide loomine.
2. **test** — unit- ja komponenttestid; ebaõnnestumine peatab pipeline'i.
3. **security** — sõltuvuste skaneerimine (nt OWASP dependency check) ja konteineri baaspildi kontroll.
4. **package** — Docker/OCI pildi build ja push registry'sse.
5. **deploy-dev** — automaatne deploy arenduskeskkonda (feature harud).
6. **deploy-test** — automaatne deploy testkeskkonda (`main` haru).
7. **deploy-prod** — manuaalne job tootmiseks (vt kubernetes-deploy.md).

## Käivitamise tingimused

| Sündmus | Mis jookseb |
|---------|-------------|
| Push feature harusse | build, test, security, package, deploy-dev |
| Merge request | build, test, security (deploy ei käi) |
| Push / merge `main` | kõik etapid kuni deploy-test |
| Manual | deploy-prod, rollback-prod |

## Kui pipeline ebaõnnestub

1. Ava GitLabis vastav pipeline ja vaata ebaõnnestunud jobi logi.
2. **test** ebaõnnestub → paranda testid või kood lokaalselt, push uuesti.
3. **security** ebaõnnestub → vaata leitud CVE-d; kriitilised blokeerivad merge'i.
4. **package** ebaõnnestub → kontrolli Dockerfile'i ja registry ühendust.
5. **deploy-*** ebaõnnestub → vaata Kubernetes sündmusi ja eelmise deploy staatust (vt kubernetes-deploy.md).

Ära käivita `retry` tootmise jobidel ilma põhjust teadmata — eelistatud on uus pipeline sama commit'iga pärast parandust.

## Muutujad ja saladused

- CI muutujad (mitte-salajased) on projekti või grupi Settings → CI/CD → Variables all.
- Saladused (API võtmed, deploy tokenid) on **masked** ja **protected**; neid ei tohi logidesse printida.
- Kohalikud saladused ei kuulu reposse (kasuta `.env`, mis on `.gitignore`s).

## Hea tava

- Hoia `.gitlab-ci.yml` võimalusel lühike; korduv loogika tõsta include'itud template'idesse.
- Pinni toolide versioonid (image tag'id), et build oleks reprodutseeritav.
- Merge request peab olema rohelise pipeline'iga enne koodireview lõpetamist.

## Seotud teemad

- Kubernetes deploy: vt kubernetes-deploy.md
- Koodireview: vt code-review.md
- Git töövoog: vt git-workflow.md

# Kubernetes deploy protsess

Ülevaade, kuidas SMIT sisemised teenused viiakse Kubernetes klastrisse. Protsess on standardiseeritud, et vähendada käsitsi vigu ja tagada jälgitavus.

## Eeldused

- Teenus on ehitatud CI/CD pipeline'is (vt cicd-pipeline.md).
- Konteineripilt on pushitud organisatsiooni container registry'sse.
- Helm chart või Kustomize manifestid asuvad teenuse repos kataloogis `deploy/`.
- Sul on vähemalt Developer õigused vastavas GitLab projektis.

## Deploy sammud

1. **Arendus / feature haru**  
   Pipeline ehitab pildi ja deploy'ib automaatselt `dev` nimeruumi (namespace).

2. **Testkeskkond**  
   Pärast edukat merge'i `main` harusse käivitub deploy `test` keskkonda. Smoke-testid peavad läbima.

3. **Tootmine**  
   Tootmise deploy on **käsitsi kinnitatav** (manual job pipeline'is). Kinnituse annab tiimi on-call või release owner.
   - Vali pipeline'is job `deploy-prod`.
   - Kontrolli pildi tag'i ja changelog'i.
   - Käivita job; Helm teeb rolling update'i.

4. **Kontroll pärast deploy'd**  
   - `kubectl rollout status deployment/<teenus> -n <namespace>`
   - Vaata Grafana dashboard'i ja alert'e 15 minuti jooksul.
   - Ebaõnnestumisel käivita pipeline'is `rollback-prod` job.

## Keskkonnad

| Keskkond | Namespace | Deploy |
|----------|-----------|--------|
| dev | `dev` | automaatne feature harust |
| test | `test` | automaatne `main` peale |
| prod | `prod` | manual approval |

## Reeglid

- Otse `kubectl apply` tootmises on keelatud, v.a intsidentide ajal duty-inseneri loal.
- Secret'id tulevad Vault'ist / sealed-secrets mehhanismist — ära pane paroole manifestidesse.
- Ressursipiirangud (`requests`/`limits`) peavad olema chart'is määratud.

## Tüüpilised probleemid

- **ImagePullBackOff** — kontrolli registry õigusi ja pildi tag'i.
- **CrashLoopBackOff** — vaata podi logisid; sageli puuduv konfiguratsioon või ebaõige migratsioon.
- **Pending** — klastris võib puududa ressurss; teavita platformi tiimi.

## Seotud teemad

- CI/CD pipeline: vt cicd-pipeline.md
- Git töövoog: vt git-workflow.md

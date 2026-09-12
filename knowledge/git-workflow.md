# Git töövoog ja branching

Standardne Git töövoog organisatsiooni arendusmeeskondades. Eesmärk on ühtne harustrateegia, turvaline `main` ja selge ajalugu.

## Harud

| Haru | Otstarve |
|------|----------|
| `main` | Alati deployatav; protected; ainult läbi merge request'i |
| `feature/<ticket>-lühikirjeldus` | Uus funktsionaalsus või väike muudatus |
| `fix/<ticket>-lühikirjeldus` | Veaparandus |
| `hotfix/<kirjeldus` | Kiire tootmise parandus otse `main` pealt |

Nimede näited: `feature/JIRA-123-gitlab-sync`, `fix/JIRA-456-null-check`.

## Tüüpiline voog

1. Uuenda kohalik `main`: `git checkout main && git pull`.
2. Loo haru: `git checkout -b feature/JIRA-123-kirjeldus`.
3. Tee väikeseid commit'e selgete sõnumitega (imperatiiv, miks/mis).
4. Push haru GitLabi ja ava merge request `main` suunas.
5. Läbi CI ja koodireview (vt code-review.md, cicd-pipeline.md).
6. Squash või merge vastavalt projekti seadistusele; kustuta haru pärast merge'i.

## Commit sõnumid

- Eelistatud formaat: `JIRA-123: Lisa GitLab ligipääsu juhend`.
- Üks loogiline muudatus commit'i kohta.
- Ära commit'i genereeritud faile, saladusi ega kohaliku IDE seadistusi.

## Protected branch reeglid (`main`)

- Otsene push on keelatud.
- Nõutud on roheline pipeline ja vähemalt üks approve.
- Force push on keelatud.

## Hotfix

1. Haru `main` pealt: `hotfix/...`.
2. Minimaalne muudatus + test.
3. Kiirendatud review (vähemalt üks approve).
4. Merge `main`i ja vajadusel tagasi port teistesse harudesse.
5. Tootmise deploy vastavalt kubernetes-deploy.md protsessile.

## Levinud vead

- Pikk eluiga feature harudel ilma regulaarse rebase/merge'ita `main`ist → konfliktid.
- Suured "kõik-ühes" MR-id → jaga väiksemateks.
- Commit'itud `.env` või võtmed → pööra kohe saladused, eemalda ajaloost vastavalt turvajuhisele.

## Seotud teemad

- Koodireview: vt code-review.md
- CI/CD: vt cicd-pipeline.md
- Kubernetes deploy: vt kubernetes-deploy.md
- GitLab ligipääs: vt gitlab-access.md

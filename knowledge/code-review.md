# Koodireview enne merge'i

Reeglid ja soovitused koodi ülevaatamiseks enne `main` (või muu protected haru) merge'i. Eesmärk on kvaliteet, teadmiste jagamine ja turvalisus.

## Millal review on kohustuslik

- Iga merge request (MR) protected harusse vajab **vähemalt ühe** approve'i teise tiimiliikme poolt.
- Kriitilised teenused (auth, maksed, isikuandmed): **kaks** approve'i.
- Autor ei tohi ise oma MR-i approve'ida.

## Kuidas koodi üle vaadata

1. Ava GitLabis merge request ja loe kirjeldust (miks muutus, riskid, testiplaan).
2. Veendu, et CI pipeline on roheline (build, test, security).
3. Vaata diffi:
   - loogikavead, servatingimused, veakäsitlus;
   - turvalisus (sisendi valideerimine, saladused, õigused);
   - loetavus ja vastavus projekti stiilile;
   - testide olemasolu uue käitumise jaoks.
4. Jäta konkreetsed kommentaarid ridadele; erista **blokeeriv** vs **soovitus**.
5. Approve või request changes. Request changes korral oota autori parandusi ja uut pipeline'i.

## Autori kohustused enne review'd

- MR kirjeldus on täidetud (sh kuidas testida).
- Enesekontroll tehtud; debug-logid ja ajutine kood eemaldatud.
- Branch on ajakohane `main` suhtes (rebase või merge vastavalt tiimi kokkuleppele).
- Seotud issue / ticket on viidatud.

## SLA

- Esmane review: eesmärk **1 tööpäev** pärast MR-i valmis märkimist.
- Väikesed parandused: re-review samal päeval kui võimalik.
- Kui review venib, märgi MR-is reviewer või tõsta tiimi standup'is.

## Keelatud enne merge'i

- Merge ilma nõutud approve'ideta (protected branch reeglid blokeerivad).
- `--no-verify` või CI vahelejätmine ilma duty-inseneri loata.
- Tootmise konfiguratsiooni või saladuste hardcode'imine.

## Seotud teemad

- Git töövoog: vt git-workflow.md
- CI/CD pipeline: vt cicd-pipeline.md
- GitLab ligipääs: vt gitlab-access.md

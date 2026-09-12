# GitLab ligipääs

Juhend organisatsiooni sisemise GitLabi ligipääsu taotlemiseks. See dokument kirjeldab protsessi uutele töötajatele ja rollimuudatusteks.

## Kes saab taotleda

Ligipääsu saavad taotleda kõik organisatsiooni töötajad ja lepingulised partnerid, kellel on kehtiv töösuhe ning kelle tööülesanded nõuavad koodihoidlate kasutamist.

## Taotlemise sammud

1. Logi sisse organisatsiooni teenuste portaali (SSO kaudu).
2. Vali menüüst **Ligipääsutaotlus** → **GitLab**.
3. Täida taotlusvorm:
   - põhjendus (projekt või tiim);
   - soovitud roll (Reporter, Developer või Maintainer);
   - seotud projektide või gruppide nimed.
4. Esita taotlus. Süsteem suunab selle sinu otsese juhi kinnitusele.
5. Pärast juhi kinnitust loob GitLabi administraator konto või lisab õigused olemasolevale kontole.

## SLA ja tähtajad

- Juhi kinnitus: tavaliselt **1–2 tööpäeva**.
- Administraatori seadistus pärast kinnitust: kuni **1 tööpäev**.
- Kiireloomulised taotlused märgi vormil eraldi; need vaadatakse samal tööpäeval, kui esitatud enne kella 12:00.

## Rollid lühidalt

| Roll | Õigused |
|------|---------|
| Reporter | Lugemine, issue'd, CI logide vaatamine |
| Developer | Push harudesse (v.a protected), merge request'id |
| Maintainer | Protected branch'id, seadistused, liikmete haldus |

Maintainer rolli antakse ainult tiimijuhi või projektiomaniku põhjendatud taotlusel.

## Probleemide korral

Kui taotlus on ootel kauem kui 2 tööpäeva, kontrolli teenuste portaalist taotluse staatust või võta ühendust IT teenusedeskiga. Ära jaga oma GitLabi paroole ega personal access token'eid.

## Seotud teemad

- Git töövoog ja branching: vt git-workflow.md
- Koodireview enne merge'i: vt code-review.md

# Turvaintsidendi esmane reageerimine

Lühike playbook arendajale ja on-call'ile, kui kahtlustatakse turvaintsidenti (leke, kahtlane ligipääs, ransomware sümptomid teenuses).

## Mis loetakse intsidendiks

- Teadaolev või kahtlustatav andmeleke (sh saladused repos või logides).
- Volitamata ligipääs süsteemile või kontole.
- Teenuse terviklikkuse rikkumine (muudetud conf, tundmatu deploy).

## Esimesed 30 minutit

1. **Ära vaiki** — kirjuta turvakanalisse (`#security-incident` või vastav sisekanal) lühike faktide kokkuvõte.
2. **Säilita tõendid** — ära kustuta logisid ega "paranda vaikides" enne esmast teavitust.
3. **Piira kahju** kui see on ohutu ja selge:
   - keela kompromiteeritud konto / token;
   - peata kahtlane pipeline või roll-out;
   - ära tee ulatuslikku prod muudatust ilma turvatiimita.
4. **Kogu kontekst**: millal avastati, milline teenus/keskkond, mis logi/alert, kes on seotud.

## Kontaktid ja eskaleerimine

| Olukord | Keda teavitada |
|---------|----------------|
| Tavaline tööaeg | turvatiim + teenuse product owner |
| Väljaspool tööaega | on-call rota + turvavahenduse number teenuste portaalis |
| Isikuandmed või seaduslik risk | turvatiim eskaleerib DPO / juhtkonnaga |

## Mida mitte teha

- Ära postita paroole, tokeneid ega isikuandmeid chatti "näiteks".
- Ära force-push'i ega kirjutata üle audit-logisid ilma juhiseta.
- Ära ava intsidendi detaile avalikus kanalis ega väljaspool organisatsiooni.

## Järeltegevus

Pärast stabiliseerimist oodatakse lühikest post-mortem'i: juurpõhjus, ajajoon, parandused ja omanikud. Tähtaeg lepitakse turvatiimiga (tavaliselt 5 tööpäeva jooksul kriitilise intsidendi puhul).

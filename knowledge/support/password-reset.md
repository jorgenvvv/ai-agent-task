# Parooli ja SSO lähtestamine

Kuidas taastada organisatsiooni SSO / Active Directory konto ligipääs, kui parool on ununenud või konto lukustunud.

## Iseteenindus (eelistatud)

1. Mine SSO sisselogimise lehele.
2. Vali **Unustasin parooli** / **Reset password**.
3. Kinnita isik e-posti või kinnitatud MFA seadmega.
4. Sea uus parool, mis vastab poliitikale (pikkus, keerukus, ei korda viimaseid paroole).

Iseteeninduse SLA: tavaliselt **kohene**, kui MFA/e-post töötab.

## Konto lukustunud

- Liiga palju valesid katseid lukustab konto ajutiselt (tavaliselt **15 minutit**).
- Oota lukustuse lõppu või kasuta iseteeninduse unlock'i, kui see on saadaval.
- Ära proovi korduvalt vana parooli — pikendab lukustust.

## Helpdesk

Kui iseteenindus ei tööta (pole MFA-d, e-post ei jõua kohale):

1. Helista või kirjuta organisatsiooni helpdesk'i (kontakt teenuste portaalis).
2. Ole valmis isikut tuvastama (töötaja ID / juhi kinnitus vastavalt protsessile).
3. Helpdesk lähtub: uue ajutise parooli väljastamine + kohustuslik vahetus esimesel login'il.

Helpdesk SLA tavalistele paroolitaotlustele: **4 töötundi** tööajal.

## MFA seade kadunud

1. Ära jaga ühekordseid koode kolmandatele isikutele.
2. Taotle MFA reset'i helpdesk'i kaudu; vajalik on juhi või HR kinnitus.
3. Pärast reset'i registreeri uus seade esimesel võimalusel.

## Hea tava

- Kasuta paroolihaldurit; ära taaskasuta organisatsiooni parooli mujal.
- Ära saada paroole e-posti ega chati teel.

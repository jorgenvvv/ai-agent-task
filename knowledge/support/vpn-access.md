# VPN ligipääs

Juhend organisatsiooni sisemise VPN-i kasutamiseks kaugtööks ja sisevõrgu teenustele ligipääsuks.

## Kes vajab VPN-i

- Kaugtöö, kui teenus või admin-liides on ainult sisevõrgus.
- Ligipääs sisemistele dashboard'idele, jump-hostidele või staging keskkondadele, mis ei ole avalikud.

## Taotlemine

1. Ava organisatsiooni teenuste portaal (SSO).
2. Vali **Ligipääsutaotlus** → **VPN**.
3. Märgi põhjendus (tiim/projekt) ja vajadusel tähtaeg (nt projekti lõpuni).
4. Oota juhi kinnitust. Pärast kinnitust saad seadistamise juhendi e-posti.

## SLA

- Tavapärane kinnitus ja konto: **1 tööpäev** pärast juhi approve'i.
- Kiiretaotlus (tootmise intsident): märgi taotluses "urgent" ja teavita on-call'i; eesmärk **2 tundi** tööajal.

## Ühendamine

1. Paigalda heakskiidetud VPN klient (juhend tuleb koos õigustega; ära kasuta suvalist kolmanda osapoole klienti).
2. Impordi profiil / skanni QR vastavalt juhendile.
3. Logi sisse SSO või väljastatud sertifikaadiga.
4. Kontrolli ühendust: ava sisemine status-leht või `ping` lubatud sisehostile.

## Tõrkeotsing

| Probleem | Mida proovida |
|----------|----------------|
| Ei ühendu | Kontrolli internetti, kellaaega (sertifikaadid), et profiil pole aegunud |
| Ühendub, aga teenus ei avane | Veendu, et oled õiges VPN grupis; mõned teenused nõuavad eraldi firewall reeglit |
| Korduvad katkestused | Vaheta võrku (nt telefoni hotspot testiks), uuenda klienti |

Kui probleem püsib, ava support ticket kategooriaga **Network / VPN** ja lisa kliendi versioon + kellaaeg.

# Uue arendaja onboarding

Kontrollnimekiri esimese nädala jaoks, et uus tiimiliige saaks turvaliselt koodi ja keskkondadeni.

## Enne esimest koodi

1. **SSO ja e-post** — veendu, et sisselogimine töötab.
2. **GitLab ligipääs** — taotle vastavalt `gitlab-access.md` (Developer roll tiimi projektidele).
3. **VPN** — vajadusel `support/vpn-access.md`.
4. **Arendusmasin** — paigalda heakskiidetud runtime'id (JDK, Node jne) tiimi README järgi.

## Esimese päeva checklist

- [ ] Liitu tiimi chat'i ja on-call teadete kanaliga
- [ ] Clone'i peamine teenuserepo (HTTPS või SSH vastavalt GitLab juhisele)
- [ ] Käivita projekt lokaalselt README `Getting started` sektsiooni järgi
- [ ] Loe `git-workflow.md` ja `code-review.md`
- [ ] Sea üles MFA kõikidele admin-kontoidele

## Esimese nädala eesmärgid

| Päev | Soovituslik fookus |
|------|--------------------|
| 1–2 | Keskkond + dokumentatsioon + pair-onboarding buddy'ga |
| 3–4 | Väike good-first issue või docs-MR |
| 5 | Demo tiimile: mis seadistasid, mis jäi segaseks |

## Õigused, mida tavaliselt ei anta kohe

- Production cluster admin / kubectl production
- GitLab Maintainer kriitilistes repodes
- Vault production path'id

Need tulevad hiljem rolli ja vajaduse alusel; ära taotle "igaks juhuks".

## Abi

- Tehniline: tiimi buddy või tech lead
- Ligipääsud: teenuste portaal + `support/` dokumendid
- Turva: `security/secrets-handling.md` enne esimest saladuste kasutamist

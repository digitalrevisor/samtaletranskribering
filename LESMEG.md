# Samtaletranskribering (Android)

Live tale-til-tekst av telefonsamtaler med Googles gjenkjenning **på telefonen**
(Androids `SpeechRecognizer` – samme motor som Live Transcribe/Gboard-dikteringen).
Kjører som forgrunnstjeneste, så transkriberingen fortsetter mens du er inne i
Telefon-appen. Resultatet vises løpende og kan lagres som `.txt`.

## Last ned APK
Nyeste ferdigbygde APK ligger alltid under **Releases** i dette repoet
(`samtaletranskribering.apk`). Åpne siden på telefonen mens du er logget inn på GitHub.

## Lagrede samtaler
Samtalen lagres automatisk for hver ny replikk – du trenger ikke trykke Lagre.
- **Lagrede** (øverst til høyre): liste over alle samtaler. Trykk for å åpne,
  hold inne for å slette. I en åpnet samtale kan du **Dele** (e-post, SMS, Drive …)
  eller **Slette**.
- **⋮ → Åpne Nedlastinger**: en kopi av hver samtale ligger i
  `Nedlastinger/Samtaletranskribering` (Samsung: *Mine filer → Nedlastinger*).
- **Ny samtale** tømmer skjermen og starter en ny fil.

## Hvem snakker?
Hver replikk merkes **Meg** eller **Anroper**. Appen skiller på lydstyrke: med
høyttaler er din stemme nær mikrofonen (sterkere), mens anroperens stemme kommer
svakere fra høyttaleren.

- **Trykk** på en linje for å bytte taler. Appen lærer av rettelsen og merker
  resten av samtalen på nytt (rettede linjer får ✓).
- **Hold inne** en linje for å gi taleren et navn (f.eks. «Kari»). Navnet huskes.
- Filene har formatet `[12:01:22] Meg: tekst`.

Begrensninger: de første replikkene merkes «Meg» til appen har hørt begge
stemmene. Snakker begge uten pause, kan én linje inneholde begge stemmer.

## ⚠️ Viktig om «parallelle lydkilder»
Du ba om at mikrofon **og** selve samtalekilden tas opp samtidig. Det er sperret
av Android selv:

- Fra **Android 10** kan en vanlig (ikke-rootet) app **ikke** lese samtalestrømmen
  (`VOICE_CALL`/`VOICE_DOWNLINK`) – Google blokkerte dette av personvernhensyn.
- Operativsystemet gir heller ikke to vanlige apper full mikrofontilgang samtidig;
  det er prioritetsregler, og telefoni-stacken vinner under en samtale.
- `AudioPlaybackCapture` (Android 10+) kan fange lyd fra andre apper, men
  **ekskluderer uttrykkelig samtaler**.

Ekte parallell-opptak av begge sider krever derfor enten en **system-/OEM-app**,
**root**, eller Androids egen samtaleopptaksfunksjon (finnes i Google Telefon-appen
i enkelte land/enheter).

**Praktisk løsning som faktisk virker i denne appen:** sett samtalen på **høyttaler**.
Da fanger mikrofonen både din stemme og motpartens stemme fra høyttaleren, og alt
blir transkribert.

## Bygg APK-en

### Alternativ A – uten å installere noe (GitHub Actions, gratis)
Prosjektet har en ferdig byggeoppskrift i `.github/workflows/build-apk.yml`.
1. Opprett et gratis **GitHub**-konto hvis du ikke har det.
2. Lag et nytt (gjerne privat) repository og last opp hele denne mappen
   (dra-og-slipp filene i nettleseren, eller `git push`).
3. Gå til fanen **Actions** → kjøringen «Build APK» starter automatisk.
4. Når den er ferdig (grønt flagg, ca. 3–5 min): åpne kjøringen og last ned
   **app-debug-apk** under «Artifacts». Pakk ut → der ligger `app-debug.apk`.
5. Overfør til telefonen og installer (tillat «ukjente kilder»).

### Alternativ B – Android Studio lokalt
1. Installer **Android Studio** (gratis).
2. **File → Open** og velg denne mappen (`Samtaletranskribering`).
3. La Android Studio laste ned Gradle/SDK (skjer automatisk første gang).
4. Bygg: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. APK-en legges i `app/build/outputs/apk/debug/app-debug.apk`.
6. Overfør til telefonen og installer (tillat «ukjente kilder»).

Vil du ha en installérbar release-APK, må du signere den:
**Build → Generate Signed Bundle / APK → APK**.

## Krav på telefonen
- Google-appen / tale-til-tekst må være til stede (standard på de fleste Android-telefoner).
- For gjenkjenning **uten nett**: last ned norsk offline-språkpakke under
  *Innstillinger → System → Språk → Tale → Frakoblet tale-gjenkjenning*.
- Ellers brukes Googles nett-gjenkjenning (krever internett).

## Juridisk
Opptak/transkribering av samtaler har regler som varierer. I Norge kan du som regel
ta opp samtaler du selv deltar i til eget bruk, men deling kan være ulovlig, og i
mange land kreves samtykke fra alle parter. **Informer motparten.** Dette er ditt
ansvar.

## Språk
**Nynorsk:** Googles talegjenkjenning har ingen nynorsk-modell. Velger du
«Norsk nynorsk (via bokmål)», gjenkjennes talen som bokmål og hver replikk
oversettes automatisk til nynorsk med [Apertium](https://apertium.org) (gratis,
åpen kildekode). Bare teksten sendes til apertium.org, ikke lyden. Uten nett blir
teksten stående på bokmål.

Standard er bokmål (`nb-NO`). Endre `"nb-NO"` i `MainActivity.kt` og
`TranscriptionService.kt` for andre språk (f.eks. `"en-US"`).

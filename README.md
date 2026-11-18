# PriceFloor — Universal Live Price Comparison & Lowest-Price Discovery Engine

**PriceFloor** is a live, cross-platform price discovery engine designed to find the lowest total acquisition cost for any product, game, book, trading card, instrument, hardware item, or digital license across online marketplaces, conditions (`New`, `Used`, `Refurbished`, `Open Box`, `Damaged/Parts`), and acquisition methods (`Buy Now`, `Digital`, `Auction`, `Rental`).

Rather than relying on static product catalogs or cached historical prices, every search executes **fresh concurrent requests**, normalizes currencies against live exchange rates, separates verified delivered totals from unverified seller-shipping estimates, flags accessory/spare-part traps, estimates cross-border EU import VAT/duties, and maintains an honest **Source Transparency Ledger** (`SEARCHED`, `NO_RESULTS`, `UNAVAILABLE`, `REQUIRES_AUTH`, `UNSUPPORTED`).

This repository includes:
1. **Native Android App (`/app`)** — Built with Kotlin, Jetpack Compose (Material 3), Coroutines/Flow, OkHttp, Retrofit/Moshi (including standalone eBay Finding API integration), Firebase AI Logic, Firebase Firestore + Credential Manager Google Sign-In, and Firebase App Check.
2. **Browser Live Web App (`/docs`)** — A zero-build static ES-module web app deployed automatically to **GitHub Pages** so anyone can run live multi-marketplace price searches directly in a browser.

---

## Run in Your Browser via GitHub

You can run PriceFloor directly in a browser from GitHub using either **GitHub Pages** (instant live web app) or **GitHub Codespaces** (full browser IDE + live preview + Gradle unit test runner).

### Option A: GitHub Pages (Instant Live Web App)

The `/docs` directory contains the browser-based live PriceFloor web app (`index.html`, `styles.css`, `app.js`), paired with `.github/workflows/pages.yml`.

1. **Commit and push** this repository to GitHub (`main` or `master` branch).
2. In your GitHub repository, open **Settings → Pages**.
3. Under **Build and deployment → Source**, select **GitHub Actions**.
4. Wait ~30 seconds for the **Deploy PriceFloor Web Companion to GitHub Pages** workflow to finish (or trigger it manually under **Actions → Deploy PriceFloor Web Companion to GitHub Pages → Run workflow**).
5. Open the published URL shown in the workflow summary or Settings → Pages:
   ```text
   https://<your-github-username>.github.io/<your-repository-name>/
   ```

#### What Runs Live in the Browser Companion?
Because browsers enforce CORS policies and should never bundle private API secrets in client-side JavaScript:
- **Live Browser Connectors (Zero Keys Required):**
  - **Live FX Rates:** `api.frankfurter.app` (ECB rates) with `open.er-api.com` fallback
  - **CheapShark PC Deals:** Multi-store digital PC game pricing
  - **Apple iTunes Store:** Apps, music, movies, audiobooks & digital rentals
  - **Pokémon TCG API:** Cardmarket (`EUR`) & TCGplayer (`USD`) live card market lows
  - **Scryfall API:** Magic: The Gathering pricing across Cardmarket & TCGplayer
  - **Google Books:** eBook & retail book pricing
  - **Reverb / Discogs (optional session token):** Live music gear & vinyl release lowest prices (enter an optional Discogs personal token in memory if desired)
- **Native-Android-Only / Auth-Gated Sources:**
  - Marketplaces that block browser CORS or require API credentials (**eBay Finding API**, **Google Shopping via SerpApi**, **CeX**, **Back Market**, **Adverts.ie**, **DoneDeal.ie**, **Amazon**) are truthfully marked `UNAVAILABLE` or `REQUIRES_AUTH` in the browser ledger and are queried natively in the Android app.

---

### Option B: GitHub Codespaces (Browser IDE + Live Web Preview + Gradle Tests)

1. On GitHub, click the green **`<> Code`** button → **Codespaces** tab → **Create codespace on main**.
   - `.devcontainer/devcontainer.json` automatically provisions **Java 17**, **Gradle 9.3.1**, **Node 20**, decodes `debug.keystore.base64` into `debug.keystore` (ignored by git), and initializes `.env` from `.env.example`.
2. **Preview the live web app in your browser:**
   ```bash
   python3 -m http.server 8080 --directory docs
   ```
   Click **Open in Browser** when Codespaces forwards port `8080`.
3. **Run Android unit & Robolectric tests in the browser terminal:**
   ```bash
   gradle :app:testDebugUnitTest
   ```
   *(If you need the full Android SDK inside Codespaces to assemble APKs, install `cmdline-tools` or use the included GitHub Actions CI workflow `.github/workflows/android.yml` which builds `pricefloor-debug-apk` automatically on every push.)*

---

## Android App Architecture & Key Features

| Layer | Highlights |
| --- | --- |
| **Search Orchestration** | `LiveSearchOrchestrator` fans out concurrent searches across marketplace connectors, streams real-time per-source status, and timestamps every retrieval. |
| **eBay Finding Service** | Standalone Retrofit + OkHttp + Moshi KSP service (`data/network/ebay/EbayFindingService.kt`) injecting `X-EBAY-SOA-SECURITY-APPNAME` cleanly for `findItemsByKeywords`. |
| **Cost Normalization** | `CurrencyConverter` fetches live FX rates and computes base price + shipping + estimated EU cross-border import VAT/duties (`IE`, `DE`, `FR`, etc.). |
| **Matching & Trap Detection** | `ProductMatchingEngine` extracts model/SKU/ISBN tokens, deduplicates identical offers, ranks exact/equivalent/partial matches, and flags accessory/spare-part traps (`case`, `box only`, `charger`, `replacement`, `dlc`, etc.). |
| **AI Search Assistant** | `GeminiSearchAssistant` powered by **Firebase AI Logic** (`firebase-ai`) + **App Check** generates alternative query expansions (model numbers, SKUs, equivalents) and rent-vs-buy insights without hardcoding a `GEMINI_API_KEY`. |
| **Cloud Sync & Auth** | `FirestoreTrackerRepository` + `AuthManager` (Google Sign-In via Jetpack Credential Manager) persists saved price watches and search alerts per user. |

---

## Local Android Build & Setup

### Prerequisites
- **JDK 17** (project targets Java 11 bytecode, `compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`)
- **Gradle 9.3.1** (or Android Studio Meerkat / Narwhal+)
- **Android SDK** (`ANDROID_SDK_ROOT` / `ANDROID_HOME` configured)

### 1. Prepare Debug Keystore & Environment Keys
`debug.keystore` and `.env` are intentionally `.gitignore`d so secrets and local keystores are never committed.

```bash
# Decode the shared debug keystore locally
base64 -d debug.keystore.base64 > debug.keystore

# Create your local .env from the template
cp .env.example .env
```

Optional third-party marketplace credentials in `.env`:
```dotenv
SERPAPI_KEY=your_serpapi_key
EBAY_CLIENT_ID=your_ebay_app_id
EBAY_CLIENT_SECRET=your_ebay_client_secret
DISCOGS_TOKEN=your_discogs_personal_token
```

> **Security Note:** Never commit `.env`, `debug.keystore`, or `my-upload-key.jks`. Any key placed in `.env` is compiled into `BuildConfig` at build time by the Secrets Gradle Plugin—do not distribute release APKs containing private credentials unless proxied through a backend.

### 2. Build & Test Commands
```bash
# Run all JVM unit & Robolectric tests
gradle :app:testDebugUnitTest

# Build the debug APK
gradle :app:assembleDebug
```
*(If using Android Studio, you can also generate wrapper scripts locally with `gradle wrapper --gradle-version 9.3.1` or open the project directly in Android Studio.)*

---

## Project Structure

```text
.
├── .devcontainer/
│   └── devcontainer.json          # GitHub Codespaces configuration (Java 17 + Gradle 9.3.1 + port 8080)
├── .github/workflows/
│   ├── android.yml                # CI: runs :app:testDebugUnitTest & uploads debug APK
│   └── pages.yml                  # CD: publishes /docs live web app to GitHub Pages
├── docs/                          # Zero-build GitHub Pages live web companion
│   ├── .nojekyll
│   ├── index.html
│   ├── styles.css
│   └── app.js
├── app/
│   ├── build.gradle.kts           # App config (applicationId: com.aistudio.pricefloor.qmxvkt)
│   └── src/
│       ├── main/java/com/example/ # Compose UI, orchestrator, connectors, eBay Finding service, Firebase sync
│       └── test/java/com/example/ # Unit & Robolectric tests (including EbayFindingServiceTest)
├── .env.example                   # Third-party marketplace credential template
├── debug.keystore.base64          # Encoded debug keystore for local & CI builds
├── firestore.rules                # Zero-trust Firestore security rules
├── settings.gradle.kts            # rootProject.name = "PriceFloor"
└── README.md
```

---

## Pre-Commit Checklist

Before running `git commit`:
1. Verify no secrets or local artifacts are staged:
   ```bash
   git status
   ```
   Ensure `.env`, `local.properties`, and `debug.keystore` do **not** appear as untracked or modified files.
2. Confirm unit tests pass (`gradle :app:testDebugUnitTest`) or rely on the GitHub Actions **Android Build & Unit Tests** workflow after pushing.

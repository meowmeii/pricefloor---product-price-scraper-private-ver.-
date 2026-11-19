// PriceFloor Live Browser Engine
// Real live HTTP & HTML DOM scraping across public APIs and marketplace search pages.
// Zero mocked listings, zero fabricated historical prices.

const CURRENCY_SYMBOLS = {
  EUR: "€",
  GBP: "£",
  USD: "$",
  CAD: "C$",
  AUD: "A$",
  JPY: "¥",
  CHF: "CHF "
};

const EU_COUNTRIES = new Set(["IE", "DE", "FR", "NL", "ES", "IT", "BE", "AT", "PT", "FI", "SE", "DK", "PL"]);
const EU_VAT_RATES = { IE: 0.23, DE: 0.19, FR: 0.20 };

const STOPWORDS = new Set([
  "the", "and", "or", "of", "for", "in", "on", "at", "to", "by", "with", "from",
  "a", "an", "is", "ed", "edition", "vol", "volume", "book", "textbook", "buy",
  "cheap", "cheapest", "used", "new", "price", "online"
]);

const TRAP_KEYWORDS = [
  "study guide", "workbook", "lab manual", "laboratory manual", "solutions manual",
  "instructor manual", "test bank", "answer key", "flashcards", "flash cards",
  "summary", "notes only", "pocket guide", "atlas", "coloring book",
  "case", "cover", "sleeve", "pouch", "screen protector", "tempered glass",
  "charger", "charging cable", "usb cable", "adapter", "dongle", "replacement",
  "spare part", "shell", "housing", "box only", "empty box", "manual only",
  "sticker", "decal", "strap", "band", "dock", "stand", "mount", "holder",
  "skin", "dlc", "season pass", "soundtrack", "ost", "artbook", "poster", "dummy"
];

const NATIVE_ONLY_CATALOG = [
  { name: "eBay Finding API", status: "REQUIRES_AUTH", detail: "Partner App ID handled natively in the Android Retrofit service." },
  { name: "Google Shopping (SerpApi)", status: "REQUIRES_AUTH", detail: "Requires SERPAPI_KEY in the native Android app." },
  { name: "Amazon Marketplace", status: "REQUIRES_AUTH", detail: "Amazon PA-API requires signed affiliate credentials (server/native only)." },
  { name: "CeX / Back Market / Adverts.ie", status: "UNAVAILABLE", detail: "Strict WAF / client-side rendering requires native Android client." }
];

const HISTORY_STORAGE_KEY = "pricefloor_trend_history_v2";

const state = {
  fxRates: { EUR: 1 },
  fxTimestamp: null,
  fxLive: false,
  cheapSharkStores: null,
  rawListings: [],
  apiHistoricalPoints: [],
  queryContext: null,
  sourceLedgers: [],
  lastQuery: "",
  lastTimestamp: null,
  isSearching: false,
  trendRenderer: null,
  trendLoadFailed: false
};

const el = {
  form: document.getElementById("searchForm"),
  queryInput: document.getElementById("queryInput"),
  searchBtn: document.getElementById("searchBtn"),
  toggleFiltersBtn: document.getElementById("toggleFiltersBtn"),
  filtersDrawer: document.getElementById("filtersDrawer"),
  countrySelect: document.getElementById("countrySelect"),
  sortSelect: document.getElementById("sortSelect"),
  conditionFilter: document.getElementById("conditionFilter"),
  acquisitionFilter: document.getElementById("acquisitionFilter"),
  hideTrapsBtn: document.getElementById("hideTrapsBtn"),
  verifiedShippingOnlyBtn: document.getElementById("verifiedShippingOnlyBtn"),
  discogsTokenInput: document.getElementById("discogsTokenInput"),
  clearTokenBtn: document.getElementById("clearTokenBtn"),
  exportCsvBtn: document.getElementById("exportCsvBtn"),
  fxStatusBadge: document.getElementById("fxStatusBadge"),
  resultsAnchor: document.getElementById("resultsAnchor"),
  canonicalBanner: document.getElementById("canonicalBanner"),
  lowestVerifiedMetric: document.getElementById("lowestVerifiedMetric"),
  lowestVerifiedSub: document.getElementById("lowestVerifiedSub"),
  totalOffersMetric: document.getElementById("totalOffersMetric"),
  dedupSub: document.getElementById("dedupSub"),
  sourcesSearchedMetric: document.getElementById("sourcesSearchedMetric"),
  sourcesProgressSub: document.getElementById("sourcesProgressSub"),
  timestampMetric: document.getElementById("timestampMetric"),
  queryEchoSub: document.getElementById("queryEchoSub"),
  trendChartSection: document.getElementById("trendChartSection"),
  trendChartRoot: document.getElementById("trendChartRoot"),
  resultsCountLabel: document.getElementById("resultsCountLabel"),
  resultsContainer: document.getElementById("resultsContainer"),
  sourceStatusList: document.getElementById("sourceStatusList")
};

function getShipConfig() {
  const [country, currency] = el.countrySelect.value.split("|");
  return { country, currency };
}

function formatMoney(amount, currency) {
  if (amount == null || Number.isNaN(amount)) return "N/A";
  const sym = CURRENCY_SYMBOLS[currency] || `${currency} `;
  return `${sym}${Number(amount).toFixed(2)}`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function normalizeQueryKey(q) {
  return String(q || "").trim().toLowerCase().replace(/\s+/g, " ");
}

function normalizeText(text) {
  return String(text || "")
    .toLowerCase()
    .replace(/['’]s\b/g, "")
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function parseQueryTokens(rawQuery) {
  const norm = normalizeText(rawQuery);
  const rawParts = norm.split(" ").filter(Boolean);
  const coreWords = [];
  const numbers = [];

  for (const part of rawParts) {
    const ordinalMatch = part.match(/^(\d+)(st|nd|rd|th|e)$/);
    if (ordinalMatch) {
      numbers.push(ordinalMatch[1]);
      continue;
    }
    if (/^\d+$/.test(part)) {
      numbers.push(part);
      continue;
    }
    if (part.length >= 2 && !STOPWORDS.has(part)) {
      coreWords.push(part);
    }
  }

  return { norm, coreWords, numbers };
}

function parsePriceString(text, defaultCurrency = "USD") {
  const raw = String(text || "").trim();
  if (!raw) return null;

  let currency = defaultCurrency;
  if (raw.includes("€") || /\bEUR\b/i.test(raw)) currency = "EUR";
  else if (raw.includes("£") || /\bGBP\b/i.test(raw)) currency = "GBP";
  else if (/CA\$|C\$|\bCAD\b/i.test(raw)) currency = "CAD";
  else if (/AU\$|A\$|\bAUD\b/i.test(raw)) currency = "AUD";
  else if (raw.includes("$") || /\bUSD\b/i.test(raw)) currency = "USD";

  const match = raw.replace(/,/g, "").match(/(\d+(?:\.\d{1,2})?)/);
  if (!match) return null;
  const amount = Number.parseFloat(match[1]);
  if (Number.isNaN(amount) || amount < 0) return null;
  return { amount, currency };
}

function convertCurrency(amount, fromCurrency, toCurrency) {
  if (amount == null || Number.isNaN(amount)) return null;
  const from = (fromCurrency || "USD").toUpperCase();
  const to = (toCurrency || "USD").toUpperCase();
  if (from === to) return Number(amount);

  const fromRate = state.fxRates[from];
  const toRate = state.fxRates[to];
  if (!fromRate || !toRate) return null;
  return (Number(amount) / fromRate) * toRate;
}

function estimateImportCharges({ baseTarget, shippingTarget, isPhysical, sellerCountry, shipToCountry }) {
  if (!isPhysical || !sellerCountry || !shipToCountry) return { vat: 0, duty: 0, note: "" };
  const sellerIso = sellerCountry.toUpperCase();
  const destIso = shipToCountry.toUpperCase();
  if (sellerIso === destIso) return { vat: 0, duty: 0, note: "" };

  const bothEu = EU_COUNTRIES.has(sellerIso) && EU_COUNTRIES.has(destIso);
  if (bothEu) return { vat: 0, duty: 0, note: "EU intra-community" };

  if (EU_COUNTRIES.has(destIso)) {
    const customsValue = (baseTarget || 0) + (shippingTarget || 0);
    const vatRate = EU_VAT_RATES[destIso] ?? 0.21;
    const vat = customsValue * vatRate;
    const customsEur = convertCurrency(customsValue, getShipConfig().currency, "EUR") ?? customsValue;
    const duty = customsEur > 150 ? customsValue * 0.025 : 0;
    const parts = [`Est. ${Math.round(vatRate * 100)}% VAT`];
    if (duty > 0) parts.push("est. 2.5% duty");
    return { vat, duty, note: parts.join(" + ") };
  }
  return { vat: 0, duty: 0, note: "" };
}

function detectTrapReason(title, queryNorm) {
  const titleNorm = normalizeText(title);
  for (const kw of TRAP_KEYWORDS) {
    if (titleNorm.includes(kw) && !queryNorm.includes(kw)) {
      return `Possible ${kw}`;
    }
  }
  return null;
}

function detectEditionConflict(haystack, queryNumbers) {
  if (!queryNumbers || queryNumbers.length === 0) return null;
  // Check for explicit edition markers like "15th edition", "14e", "edition 15"
  for (const targetNum of queryNumbers) {
    const n = Number.parseInt(targetNum, 10);
    if (Number.isNaN(n) || n < 2 || n > 99) continue;

    const hasTargetEdition = new RegExp(`\\b${n}(?:st|nd|rd|th|e)?\\b`).test(haystack);
    if (hasTargetEdition) return null;

    const editionRegex = /\b(\d{1,2})(?:st|nd|rd|th)\s*(?:ed|edition)?\b|\bedition\s*(\d{1,2})\b/g;
    let m;
    while ((m = editionRegex.exec(haystack)) !== null) {
      const foundNum = Number.parseInt(m[1] || m[2], 10);
      if (!Number.isNaN(foundNum) && foundNum !== n) {
        return `Ed. ${foundNum} (asked ${n})`;
      }
    }
  }
  return null;
}

function evaluateListingMatch(item, parsedQuery, bookContext) {
  const haystack = normalizeText(`${item.title || ""} ${item.isbn || ""} ${item.note || ""}`);
  const hayWords = new Set(haystack.split(" ").filter(Boolean));
  const core = parsedQuery.coreWords;

  // Check exact ISBN match if resolved from Open Library / query
  let isbnMatched = false;
  if (bookContext?.isbns?.length && item.isbn) {
    const itemIsbnClean = String(item.isbn).replace(/[^0-9Xx]/g, "");
    isbnMatched = bookContext.isbns.includes(itemIsbnClean);
  }
  if (!isbnMatched && bookContext?.isbns?.length) {
    const titleDigits = String(item.title || "").replace(/[^0-9Xx]/g, "");
    isbnMatched = bookContext.isbns.some((isbn) => titleDigits.includes(isbn));
  }

  let matchedWords = 0;
  for (const w of core) {
    if (hayWords.has(w) || (w.length >= 4 && haystack.includes(w))) {
      matchedWords++;
    }
  }

  // Also check canonical book title words if resolved
  let canonicalRatio = 0;
  if (bookContext?.canonicalCoreWords?.length) {
    const cHits = bookContext.canonicalCoreWords.filter(
      (w) => hayWords.has(w) || (w.length >= 4 && haystack.includes(w))
    ).length;
    canonicalRatio = cHits / bookContext.canonicalCoreWords.length;
  }

  const wordRatio = core.length > 0 ? matchedWords / core.length : 1;
  const bestRatio = isbnMatched ? 1.0 : Math.max(wordRatio, canonicalRatio);

  // Strict relevance gate to eliminate unrelated noise (songs/apps with 1 common word)
  let passesRelevance = true;
  if (!isbnMatched) {
    if (core.length >= 3) {
      passesRelevance = matchedWords >= 2 && bestRatio >= 0.6;
    } else if (core.length === 2) {
      passesRelevance = matchedWords === 2 || bestRatio >= 0.75;
    } else if (core.length === 1) {
      passesRelevance = matchedWords === 1;
    }
  }

  // Number / edition matching
  let numberMatched = false;
  for (const num of parsedQuery.numbers) {
    if (new RegExp(`\\b${num}(?:st|nd|rd|th|e)?\\b`).test(haystack)) {
      numberMatched = true;
    }
  }

  const editionConflict = detectEditionConflict(haystack, parsedQuery.numbers);
  const trapReason = detectTrapReason(item.title, parsedQuery.norm);

  let score = bestRatio * 75;
  if (isbnMatched) score += 25;
  if (parsedQuery.numbers.length > 0) {
    score += numberMatched ? 20 : -12;
  }
  if (editionConflict) score -= 35;
  if (trapReason) score -= 40;

  let label = "Partial";
  if (isbnMatched || (bestRatio >= 0.9 && (parsedQuery.numbers.length === 0 || numberMatched) && !editionConflict)) {
    label = "Exact Match";
  } else if (bestRatio >= 0.65 && !editionConflict) {
    label = "Strong Match";
  }

  const isHistoryEligible =
    passesRelevance &&
    !trapReason &&
    !editionConflict &&
    (isbnMatched || bestRatio >= 0.66) &&
    (parsedQuery.numbers.length === 0 || numberMatched || !item.isPhysical);

  return {
    passesRelevance,
    label,
    ratio: bestRatio,
    score,
    numberMatched,
    editionConflict,
    trapReason,
    isHistoryEligible
  };
}

async function fetchWithTimeout(url, options = {}, timeoutMs = 9000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, {
      ...options,
      cache: "no-store",
      signal: controller.signal
    });
  } finally {
    clearTimeout(timer);
  }
}

async function fetchHtmlViaCorsRelay(targetUrl) {
  const relays = [
    (u) => `https://api.allorigins.win/raw?url=${encodeURIComponent(u)}`,
    (u) => `https://api.codetabs.com/v1/proxy?quest=${encodeURIComponent(u)}`,
    (u) => `https://corsproxy.io/?url=${encodeURIComponent(u)}`
  ];

  let lastError = null;
  for (const makeRelayUrl of relays) {
    try {
      const res = await fetchWithTimeout(makeRelayUrl(targetUrl), {}, 8500);
      if (!res.ok) {
        lastError = new Error(`Relay HTTP ${res.status}`);
        continue;
      }
      const html = await res.text();
      if (!html || html.length < 300) {
        lastError = new Error("Empty HTML response");
        continue;
      }
      const lower = html.slice(0, 3500).toLowerCase();
      if (
        lower.includes("cf-browser-verification") ||
        lower.includes("just a moment...") ||
        lower.includes("pardon our interruption") ||
        lower.includes("attention required! | cloudflare") ||
        lower.includes("verify you are human")
      ) {
        lastError = new Error("Blocked by site bot challenge");
        lastError.isBotBlocked = true;
        continue;
      }
      return html;
    } catch (err) {
      lastError = err;
    }
  }
  throw lastError || new Error("All CORS scrape relays failed");
}

function extractJsonLdProducts(doc) {
  const products = [];
  const scripts = doc.querySelectorAll('script[type="application/ld+json"]');
  for (const sc of scripts) {
    try {
      const json = JSON.parse(sc.textContent || "");
      const stack = Array.isArray(json) ? [...json] : [json];
      while (stack.length > 0) {
        const node = stack.shift();
        if (!node || typeof node !== "object") continue;
        if (Array.isArray(node["@graph"])) stack.push(...node["@graph"]);
        if (Array.isArray(node.itemListElement)) {
          for (const elItem of node.itemListElement) {
            if (elItem?.item) stack.push(elItem.item);
            else stack.push(elItem);
          }
        }
        const type = String(node["@type"] || "").toLowerCase();
        if (type.includes("product") || type.includes("book")) {
          const offers = Array.isArray(node.offers) ? node.offers[0] : node.offers;
          const priceVal = Number.parseFloat(offers?.price ?? offers?.lowPrice);
          if (node.name && !Number.isNaN(priceVal) && priceVal > 0) {
            products.push({
              title: String(node.name),
              price: priceVal,
              currency: offers?.priceCurrency || "USD",
              url: offers?.url || node.url || "",
              imageUrl: Array.isArray(node.image) ? node.image[0] : (node.image || ""),
              isbn: node.isbn || ""
            });
          }
        }
      }
    } catch (_) {
      // Ignore malformed JSON-LD blocks
    }
  }
  return products;
}

async function loadLiveFxRates() {
  try {
    const res = await fetchWithTimeout("https://api.frankfurter.app/latest?from=EUR", {}, 7000);
    if (res.ok) {
      const data = await res.json();
      if (data?.rates) {
        state.fxRates = { EUR: 1, ...data.rates };
        state.fxTimestamp = data.date || new Date().toISOString().slice(0, 10);
        state.fxLive = true;
        el.fxStatusBadge.classList.add("pill-badge--live");
        el.fxStatusBadge.innerHTML = `<span class="dot"></span><span>Live FX (${escapeHtml(state.fxTimestamp)})</span>`;
        return;
      }
    }
  } catch (_) {}

  try {
    const res = await fetchWithTimeout("https://open.er-api.com/v6/latest/EUR", {}, 7000);
    if (res.ok) {
      const data = await res.json();
      if (data?.rates) {
        state.fxRates = { EUR: 1, ...data.rates };
        state.fxTimestamp = new Date().toISOString().slice(0, 10);
        state.fxLive = true;
        el.fxStatusBadge.classList.add("pill-badge--live");
        el.fxStatusBadge.innerHTML = `<span class="dot"></span><span>Live FX (${escapeHtml(state.fxTimestamp)})</span>`;
        return;
      }
    }
  } catch (err) {
    el.fxStatusBadge.innerHTML = `<span class="dot"></span><span>FX offline (${escapeHtml(err.message)})</span>`;
  }
}

// --- Canonical Book / ISBN Resolution via Open Library ---

async function resolveQueryContext(rawQuery) {
  const parsed = parseQueryTokens(rawQuery);
  const context = {
    rawQuery,
    parsed,
    canonicalTitle: null,
    canonicalAuthor: null,
    canonicalCoreWords: [],
    isbns: [],
    searchTerms: [rawQuery]
  };

  try {
    let res = await fetchWithTimeout(
      `https://openlibrary.org/search.json?q=${encodeURIComponent(rawQuery)}&limit=5`,
      {},
      5500
    );
    let data = res.ok ? await res.json() : null;
    let docs = Array.isArray(data?.docs) ? data.docs : [];

    // Retry without edition number if exact query had 0 hits (e.g. "Hole's Anatomy and Physiology")
    if (docs.length === 0 && parsed.numbers.length > 0 && parsed.coreWords.length >= 2) {
      const wordsOnly = parsed.coreWords.join(" ");
      res = await fetchWithTimeout(
        `https://openlibrary.org/search.json?q=${encodeURIComponent(wordsOnly)}&limit=5`,
        {},
        5500
      );
      data = res.ok ? await res.json() : null;
      docs = Array.isArray(data?.docs) ? data.docs : [];
    }

    if (docs.length > 0) {
      const best = docs.find((d) => {
        const hay = normalizeText(`${d.title || ""} ${(d.author_name || []).join(" ")}`);
        return parsed.coreWords.filter((w) => hay.includes(w)).length >= Math.min(2, parsed.coreWords.length);
      }) || docs[0];

      if (best?.title) {
        const hay = normalizeText(`${best.title} ${(best.author_name || []).join(" ")}`);
        const hits = parsed.coreWords.filter((w) => hay.includes(w)).length;
        if (hits >= Math.min(2, parsed.coreWords.length)) {
          context.canonicalTitle = best.title;
          context.canonicalAuthor = Array.isArray(best.author_name) ? best.author_name[0] : null;
          context.canonicalCoreWords = parseQueryTokens(best.title).coreWords;
          const isbns = Array.isArray(best.isbn) ? best.isbn : [];
          context.isbns = isbns
            .filter((code) => /^(97[89]\d{10}|\d{9}[\dXx])$/.test(code))
            .slice(0, 6);

          const edSuffix = parsed.numbers.length > 0 ? ` ${parsed.numbers[0]}th` : "";
          const canonicalWithEd = `${best.title}${edSuffix}`.trim();
          if (normalizeText(canonicalWithEd) !== parsed.norm) {
            context.searchTerms.push(canonicalWithEd);
          }
          if (context.isbns[0]) {
            context.searchTerms.push(context.isbns[0]);
          }
        }
      }
    }
  } catch (_) {}

  return context;
}

// --- Live API & Scrape Connectors ---

async function searchGoogleBooks(query, ctx) {
  const queries = Array.from(new Set([query, ...(ctx?.searchTerms || []).slice(1, 3)]));
  const allItems = [];
  const seenVol = new Set();

  for (const q of queries) {
    const res = await fetchWithTimeout(
      `https://www.googleapis.com/books/v1/volumes?q=${encodeURIComponent(q)}&maxResults=16`
    );
    if (!res.ok) continue;
    const data = await res.json();
    for (const vol of (data?.items || [])) {
      if (!seenVol.has(vol.id)) {
        seenVol.add(vol.id);
        allItems.push(vol);
      }
    }
  }

  const hits = [];
  for (const vol of allItems) {
    const info = vol.volumeInfo || {};
    const sale = vol.saleInfo || {};
    const priceObj = sale.retailPrice || sale.listPrice;
    if (!priceObj || typeof priceObj.amount !== "number") continue;

    const isEbook = Boolean(sale.isEbook);
    const authors = Array.isArray(info.authors) ? ` — ${info.authors.slice(0, 2).join(", ")}` : "";
    const isbnObj = (info.industryIdentifiers || []).find((x) => x.type === "ISBN_13") ||
      (info.industryIdentifiers || [])[0];
    const subtitle = info.subtitle ? `: ${info.subtitle}` : "";

    hits.push({
      id: `gbooks:${vol.id}`,
      marketplace: isEbook ? "Google Play Books" : "Google Books",
      sourceKey: "Google Books",
      title: `${info.title || "Untitled"}${subtitle}${authors}`,
      isbn: isbnObj?.identifier || "",
      basePrice: priceObj.amount,
      currency: priceObj.currencyCode || "USD",
      shippingCost: isEbook ? 0 : null,
      shippingVerified: isEbook,
      isPhysical: !isEbook,
      sellerCountry: "US",
      condition: "NEW",
      acquisition: isEbook ? "DIGITAL" : "BUY_NOW",
      url: sale.buyLink || info.infoLink || "https://books.google.com",
      imageUrl: info.imageLinks?.smallThumbnail || "",
      note: isEbook ? "Digital eBook / Textbook" : "Retail book"
    });
  }
  return hits;
}

async function searchItBookStore(query, ctx) {
  const searchTerm = ctx?.canonicalTitle || query;
  const res = await fetchWithTimeout(`https://api.itbook.store/1.0/search/${encodeURIComponent(searchTerm)}`);
  if (!res.ok) throw new Error(`ITBookStore HTTP ${res.status}`);
  const data = await res.json();
  const books = Array.isArray(data?.books) ? data.books.slice(0, 10) : [];
  return books
    .map((b) => {
      const parsed = parsePriceString(b.price, "USD");
      if (!parsed || parsed.amount <= 0) return null;
      return {
        id: `itbook:${b.isbn13}`,
        marketplace: "IT Bookstore",
        sourceKey: "IT Bookstore",
        title: b.subtitle ? `${b.title}: ${b.subtitle}` : b.title,
        isbn: b.isbn13 || "",
        basePrice: parsed.amount,
        currency: parsed.currency,
        shippingCost: 0,
        shippingVerified: true,
        isPhysical: false,
        sellerCountry: "US",
        condition: "NEW",
        acquisition: "BUY_NOW",
        url: b.url || `https://itbook.store/books/${b.isbn13}`,
        imageUrl: b.image || "",
        note: b.isbn13 ? `ISBN ${b.isbn13}` : "Bookstore listing"
      };
    })
    .filter(Boolean);
}

async function searchGutendex(query) {
  const res = await fetchWithTimeout(`https://gutendex.com/books?search=${encodeURIComponent(query)}`);
  if (!res.ok) throw new Error(`Gutendex HTTP ${res.status}`);
  const data = await res.json();
  const books = Array.isArray(data?.results) ? data.results.slice(0, 5) : [];
  return books.map((b) => {
    const authors = (b.authors || []).map((a) => a.name).slice(0, 2).join(", ");
    const htmlUrl = b.formats?.["text/html"] || b.formats?.["application/epub+zip"] || `https://www.gutenberg.org/ebooks/${b.id}`;
    return {
      id: `gutenberg:${b.id}`,
      marketplace: "Project Gutenberg",
      sourceKey: "Gutendex",
      title: authors ? `${b.title} — ${authors}` : b.title,
      basePrice: 0,
      currency: "USD",
      shippingCost: 0,
      shippingVerified: true,
      isPhysical: false,
      sellerCountry: "US",
      condition: "NEW",
      acquisition: "DIGITAL",
      url: htmlUrl,
      imageUrl: b.formats?.["image/jpeg"] || "",
      note: "Free public domain eBook"
    };
  });
}

async function searchAbeBooksScrape(query, ctx) {
  const q = ctx?.searchTerms?.[1] || query;
  const targetUrl = `https://www.abebooks.com/servlet/SearchResults?kn=${encodeURIComponent(q)}&sortby=17`;
  const html = await fetchHtmlViaCorsRelay(targetUrl);
  const doc = new DOMParser().parseFromString(html, "text/html");
  const hits = [];

  const cards = doc.querySelectorAll('[data-cy="listing-item"], .result-item, li.cf.result-item');
  cards.forEach((card, idx) => {
    if (idx >= 12) return;
    const titleEl = card.querySelector('[data-cy="listing-title"] a, h2.title a, .title a');
    const priceEl = card.querySelector('[data-cy="listing-price"], .item-price, .srp-item-price');
    const shipEl = card.querySelector('[data-cy="listing-shipping"], .item-shipping');
    const condEl = card.querySelector('[data-cy="listing-book-condition"], .books-condition');
    const imgEl = card.querySelector("img");

    const title = titleEl?.textContent?.trim();
    const priceParsed = parsePriceString(priceEl?.textContent, "USD");
    if (!title || !priceParsed) return;

    const shipText = shipEl?.textContent?.trim() || "";
    let shippingCost = null;
    let shippingVerified = false;
    if (/free\s+shipping/i.test(shipText)) {
      shippingCost = 0;
      shippingVerified = true;
    } else {
      const shipParsed = parsePriceString(shipText, priceParsed.currency);
      if (shipParsed) {
        shippingCost = shipParsed.amount;
        shippingVerified = true;
      }
    }

    const href = titleEl?.getAttribute("href") || "";
    const fullUrl = href.startsWith("http") ? href : `https://www.abebooks.com${href}`;
    const condRaw = (condEl?.textContent || "Used").toUpperCase();
    const condition = condRaw.includes("NEW") ? "NEW" : "USED";

    hits.push({
      id: `abebooks:${idx}:${title.slice(0, 20)}`,
      marketplace: "AbeBooks",
      sourceKey: "AbeBooks (Scrape)",
      title,
      basePrice: priceParsed.amount,
      currency: priceParsed.currency,
      shippingCost,
      shippingVerified,
      isPhysical: true,
      sellerCountry: "US",
      condition,
      acquisition: "BUY_NOW",
      url: fullUrl,
      imageUrl: imgEl?.getAttribute("src") || "",
      note: condEl?.textContent?.trim() || "Bookseller marketplace"
    });
  });

  if (hits.length === 0) {
    for (const p of extractJsonLdProducts(doc).slice(0, 8)) {
      hits.push({
        id: `abebooks:ld:${p.title.slice(0, 20)}`,
        marketplace: "AbeBooks",
        sourceKey: "AbeBooks (Scrape)",
        title: p.title,
        isbn: p.isbn,
        basePrice: p.price,
        currency: p.currency,
        shippingCost: null,
        shippingVerified: false,
        isPhysical: true,
        sellerCountry: "US",
        condition: "USED",
        acquisition: "BUY_NOW",
        url: p.url || targetUrl,
        imageUrl: p.imageUrl || "",
        note: "Extracted via Schema.org"
      });
    }
  }
  return hits;
}

async function searchBiblioScrape(query, ctx) {
  const q = ctx?.searchTerms?.[1] || query;
  const targetUrl = `https://www.biblio.com/search.php?stage=1&keyisbn=${encodeURIComponent(q)}&order=price_ship_asc`;
  const html = await fetchHtmlViaCorsRelay(targetUrl);
  const doc = new DOMParser().parseFromString(html, "text/html");
  const hits = [];

  doc.querySelectorAll(".item, .book-result, .srp-item").forEach((card, idx) => {
    if (idx >= 10) return;
    const titleEl = card.querySelector("h2 a, .title a, a[itemprop='url']");
    const priceEl = card.querySelector(".price, .item-price, [itemprop='price']");
    const title = titleEl?.textContent?.trim();
    const priceParsed = parsePriceString(priceEl?.textContent || priceEl?.getAttribute("content"), "USD");
    if (!title || !priceParsed) return;

    const href = titleEl?.getAttribute("href") || "";
    const url = href.startsWith("http") ? href : `https://www.biblio.com${href}`;
    hits.push({
      id: `biblio:${idx}:${title.slice(0, 20)}`,
      marketplace: "Biblio",
      sourceKey: "Biblio (Scrape)",
      title,
      basePrice: priceParsed.amount,
      currency: priceParsed.currency,
      shippingCost: null,
      shippingVerified: false,
      isPhysical: true,
      sellerCountry: "US",
      condition: "USED",
      acquisition: "BUY_NOW",
      url,
      imageUrl: card.querySelector("img")?.getAttribute("src") || "",
      note: "Independent bookseller"
    });
  });

  if (hits.length === 0) {
    for (const p of extractJsonLdProducts(doc).slice(0, 8)) {
      hits.push({
        id: `biblio:ld:${p.title.slice(0, 20)}`,
        marketplace: "Biblio",
        sourceKey: "Biblio (Scrape)",
        title: p.title,
        isbn: p.isbn,
        basePrice: p.price,
        currency: p.currency,
        shippingCost: null,
        shippingVerified: false,
        isPhysical: true,
        sellerCountry: "US",
        condition: "USED",
        acquisition: "BUY_NOW",
        url: p.url || targetUrl,
        imageUrl: p.imageUrl || "",
        note: "Extracted via Schema.org"
      });
    }
  }
  return hits;
}

async function searchBlackwellsScrape(query, ctx) {
  const q = ctx?.searchTerms?.[1] || query;
  const targetUrl = `https://blackwells.co.uk/bookshop/search?keyword=${encodeURIComponent(q)}`;
  const html = await fetchHtmlViaCorsRelay(targetUrl);
  const doc = new DOMParser().parseFromString(html, "text/html");
  const hits = [];

  doc.querySelectorAll(".product_pod, li.search-result, .product-card").forEach((card, idx) => {
    if (idx >= 10) return;
    const titleEl = card.querySelector(".product_pod__title a, h4 a, .product-name a, a.product-link");
    const priceEl = card.querySelector(".product-price--current, .price_color, .product-price");
    const title = titleEl?.textContent?.trim();
    const priceParsed = parsePriceString(priceEl?.textContent, "GBP");
    if (!title || !priceParsed) return;

    const href = titleEl?.getAttribute("href") || "";
    const url = href.startsWith("http") ? href : `https://blackwells.co.uk${href}`;
    hits.push({
      id: `blackwells:${idx}:${title.slice(0, 20)}`,
      marketplace: "Blackwell's",
      sourceKey: "Blackwell's (Scrape)",
      title,
      basePrice: priceParsed.amount,
      currency: priceParsed.currency,
      shippingCost: 0,
      shippingVerified: true,
      isPhysical: true,
      sellerCountry: "GB",
      condition: "NEW",
      acquisition: "BUY_NOW",
      url,
      imageUrl: card.querySelector("img")?.getAttribute("src") || "",
      note: "Free delivery included"
    });
  });

  if (hits.length === 0) {
    for (const p of extractJsonLdProducts(doc).slice(0, 8)) {
      hits.push({
        id: `blackwells:ld:${p.title.slice(0, 20)}`,
        marketplace: "Blackwell's",
        sourceKey: "Blackwell's (Scrape)",
        title: p.title,
        isbn: p.isbn,
        basePrice: p.price,
        currency: p.currency || "GBP",
        shippingCost: 0,
        shippingVerified: true,
        isPhysical: true,
        sellerCountry: "GB",
        condition: "NEW",
        acquisition: "BUY_NOW",
        url: p.url || targetUrl,
        imageUrl: p.imageUrl || "",
        note: "Extracted via Schema.org"
      });
    }
  }
  return hits;
}

async function searchThriftBooksScrape(query, ctx) {
  const q = ctx?.searchTerms?.[1] || query;
  const targetUrl = `https://www.thriftbooks.com/browse/?b.search=${encodeURIComponent(q)}`;
  const html = await fetchHtmlViaCorsRelay(targetUrl);
  const doc = new DOMParser().parseFromString(html, "text/html");
  const hits = [];

  for (const p of extractJsonLdProducts(doc).slice(0, 8)) {
    hits.push({
      id: `thriftbooks:ld:${p.title.slice(0, 20)}`,
      marketplace: "ThriftBooks",
      sourceKey: "ThriftBooks (Scrape)",
      title: p.title,
      isbn: p.isbn,
      basePrice: p.price,
      currency: p.currency || "USD",
      shippingCost: null,
      shippingVerified: false,
      isPhysical: true,
      sellerCountry: "US",
      condition: "USED",
      acquisition: "BUY_NOW",
      url: p.url || targetUrl,
      imageUrl: p.imageUrl || "",
      note: "Used bookseller"
    });
  }

  if (hits.length === 0) {
    doc.querySelectorAll(".AllEditionsItem, .SearchResultGridItem, [data-testid='search-result']").forEach((card, idx) => {
      if (idx >= 10) return;
      const titleEl = card.querySelector("a.SearchResultTileItem-title, a[href*='/w/'], h2 a");
      const priceEl = card.querySelector(".SearchResultListItem-dollarAmount, .SearchResultTileItem-price, .price");
      const title = titleEl?.textContent?.trim();
      const priceParsed = parsePriceString(priceEl?.textContent, "USD");
      if (!title || !priceParsed) return;
      const href = titleEl?.getAttribute("href") || "";
      hits.push({
        id: `thriftbooks:${idx}:${title.slice(0, 20)}`,
        marketplace: "ThriftBooks",
        sourceKey: "ThriftBooks (Scrape)",
        title,
        basePrice: priceParsed.amount,
        currency: priceParsed.currency,
        shippingCost: null,
        shippingVerified: false,
        isPhysical: true,
        sellerCountry: "US",
        condition: "USED",
        acquisition: "BUY_NOW",
        url: href.startsWith("http") ? href : `https://www.thriftbooks.com${href}`,
        imageUrl: card.querySelector("img")?.getAttribute("src") || "",
        note: "Used bookseller"
      });
    });
  }
  return hits;
}

async function searchWorldOfBooksScrape(query, ctx) {
  const q = ctx?.searchTerms?.[1] || query;
  const targetUrl = `https://www.wob.com/en-gb/category/all?search=${encodeURIComponent(q)}`;
  const html = await fetchHtmlViaCorsRelay(targetUrl);
  const doc = new DOMParser().parseFromString(html, "text/html");
  const hits = [];

  for (const p of extractJsonLdProducts(doc).slice(0, 8)) {
    hits.push({
      id: `wob:ld:${p.title.slice(0, 20)}`,
      marketplace: "World of Books",
      sourceKey: "World of Books (Scrape)",
      title: p.title,
      isbn: p.isbn,
      basePrice: p.price,
      currency: p.currency || "GBP",
      shippingCost: 0,
      shippingVerified: true,
      isPhysical: true,
      sellerCountry: "GB",
      condition: "USED",
      acquisition: "BUY_NOW",
      url: p.url || targetUrl,
      imageUrl: p.imageUrl || "",
      note: "Free UK shipping / Used"
    });
  }
  return hits;
}

async function searchEbayScrape(query) {
  const targetUrl = `https://www.ebay.com/sch/i.html?_nkw=${encodeURIComponent(query)}&_sop=15`;
  const html = await fetchHtmlViaCorsRelay(targetUrl);
  const doc = new DOMParser().parseFromString(html, "text/html");
  const hits = [];

  doc.querySelectorAll("li.s-item, li.s-card").forEach((card, idx) => {
    if (idx >= 14) return;
    const titleEl = card.querySelector(".s-item__title, .s-card__title");
    const linkEl = card.querySelector("a.s-item__link, a.s-card__link");
    const priceEl = card.querySelector(".s-item__price, .s-card__price");
    const shipEl = card.querySelector(".s-item__shipping, .s-item__logisticsCost");
    const condEl = card.querySelector(".SECONDARY_INFO");

    const title = titleEl?.textContent?.replace(/^New Listing/i, "").trim();
    if (!title || /^shop on ebay$/i.test(title)) return;
    const priceText = priceEl?.textContent?.split(" to ")[0] || "";
    const priceParsed = parsePriceString(priceText, "USD");
    if (!priceParsed) return;

    const shipText = shipEl?.textContent?.trim() || "";
    let shippingCost = null;
    let shippingVerified = false;
    if (/free/i.test(shipText)) {
      shippingCost = 0;
      shippingVerified = true;
    } else {
      const shipParsed = parsePriceString(shipText, priceParsed.currency);
      if (shipParsed) {
        shippingCost = shipParsed.amount;
        shippingVerified = true;
      }
    }

    const condRaw = (condEl?.textContent || "Pre-Owned").toUpperCase();
    let condition = "USED";
    if (condRaw.includes("BRAND NEW") || condRaw === "NEW") condition = "NEW";
    else if (condRaw.includes("OPEN BOX")) condition = "OPEN_BOX";
    else if (condRaw.includes("REFURBISHED")) condition = "REFURBISHED";
    else if (condRaw.includes("PARTS")) condition = "DAMAGED";

    const bidEl = card.querySelector(".s-item__bids");
    const acquisition = bidEl ? "AUCTION" : "BUY_NOW";

    hits.push({
      id: `ebay:${idx}:${title.slice(0, 20)}`,
      marketplace: "eBay",
      sourceKey: "eBay (Scrape)",
      title,
      basePrice: priceParsed.amount,
      currency: priceParsed.currency,
      shippingCost,
      shippingVerified,
      isPhysical: true,
      sellerCountry: "US",
      condition,
      acquisition,
      url: linkEl?.getAttribute("href") || targetUrl,
      imageUrl: card.querySelector("img")?.getAttribute("src") || "",
      note: condEl?.textContent?.trim() || "eBay listing"
    });
  });

  return hits;
}

async function searchCheapShark(query, parsedQuery) {
  if (!state.cheapSharkStores) {
    const storeRes = await fetchWithTimeout("https://www.cheapshark.com/api/1.0/stores");
    if (storeRes.ok) {
      const stores = await storeRes.json();
      state.cheapSharkStores = new Map(stores.map((s) => [String(s.storeID), s.storeName]));
    } else {
      state.cheapSharkStores = new Map();
    }
  }

  const url = `https://www.cheapshark.com/api/1.0/deals?title=${encodeURIComponent(query)}&pageSize=12`;
  const res = await fetchWithTimeout(url);
  if (!res.ok) throw new Error(`CheapShark HTTP ${res.status}`);
  const deals = await res.json();
  if (!Array.isArray(deals) || deals.length === 0) return [];

  const mapped = deals
    .map((d) => {
      const price = Number.parseFloat(d.salePrice);
      if (Number.isNaN(price)) return null;
      const storeName = state.cheapSharkStores.get(String(d.storeID)) || `Store #${d.storeID}`;
      const safeDealId = String(d.dealID || "").includes("%")
        ? d.dealID
        : encodeURIComponent(d.dealID || "");
      return {
        id: `cheapshark:${d.dealID}`,
        gameID: d.gameID,
        marketplace: `${storeName} (PC Digital)`,
        sourceKey: "CheapShark PC Deals",
        title: d.title,
        basePrice: price,
        currency: "USD",
        shippingCost: 0,
        shippingVerified: true,
        isPhysical: false,
        sellerCountry: "US",
        condition: "NEW",
        acquisition: "DIGITAL",
        url: `https://www.cheapshark.com/redirect?dealID=${safeDealId}`,
        imageUrl: d.thumb || "",
        note: d.savings && Number.parseFloat(d.savings) > 1
          ? `${Math.round(Number.parseFloat(d.savings))}% off retail ($${d.normalPrice})`
          : "Digital PC key"
      };
    })
    .filter(Boolean);

  // Only fetch historical all-time low if top deal actually passes relevance check!
  const bestRelevantDeal = mapped.find((item) => evaluateListingMatch(item, parsedQuery, state.queryContext).isHistoryEligible);
  const searchedQueryKey = normalizeQueryKey(query);
  if (bestRelevantDeal?.gameID) {
    fetchWithTimeout(`https://www.cheapshark.com/api/1.0/games?id=${encodeURIComponent(bestRelevantDeal.gameID)}`, {}, 5000)
      .then((r) => (r.ok ? r.json() : null))
      .then((gameData) => {
        if (normalizeQueryKey(state.lastQuery) !== searchedQueryKey) return;
        const lowEver = gameData?.cheapestPriceEver;
        const lowPrice = Number.parseFloat(lowEver?.price);
        if (lowEver?.date && !Number.isNaN(lowPrice)) {
          const dt = new Date(lowEver.date * 1000);
          state.apiHistoricalPoints.push({
            timestampMs: dt.getTime(),
            label: dt.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "2-digit" }),
            kindLabel: `All-Time Low (${bestRelevantDeal.title}) — CheapShark API`,
            matchedItem: bestRelevantDeal.title,
            platform: "PC Digital All-Time Low",
            amount: lowPrice,
            currency: "USD"
          });
          if (!state.isSearching) renderResults();
        }
      })
      .catch(() => {});
  }

  return mapped;
}

async function searchItunes(query, shipCountry) {
  const url = `https://itunes.apple.com/search?term=${encodeURIComponent(query)}&country=${encodeURIComponent(shipCountry)}&limit=12`;
  const res = await fetchWithTimeout(url);
  if (!res.ok) throw new Error(`iTunes HTTP ${res.status}`);
  const payload = await res.json();
  const items = Array.isArray(payload?.results) ? payload.results : [];
  const hits = [];

  for (const item of items) {
    const title = item.trackName || item.collectionName;
    if (!title) continue;
    const currency = item.currency || "USD";
    const link = item.trackViewUrl || item.collectionViewUrl;
    if (!link) continue;

    const buyPrice = item.trackPrice ?? item.collectionPrice;
    if (typeof buyPrice === "number" && buyPrice >= 0) {
      hits.push({
        id: `itunes:buy:${item.trackId || item.collectionId}:${title}`,
        marketplace: `Apple Store (${item.kind || item.wrapperType || "Digital"})`,
        sourceKey: "Apple iTunes Store",
        title: item.artistName ? `${title} — ${item.artistName}` : title,
        basePrice: buyPrice,
        currency,
        shippingCost: 0,
        shippingVerified: true,
        isPhysical: false,
        sellerCountry: shipCountry,
        condition: "NEW",
        acquisition: "DIGITAL",
        url: link,
        imageUrl: item.artworkUrl100 || "",
        note: buyPrice === 0 ? "Free download" : "Digital store"
      });
    }

    if (typeof item.trackRentalPrice === "number" && item.trackRentalPrice > 0) {
      hits.push({
        id: `itunes:rent:${item.trackId || title}`,
        marketplace: "Apple Store Rental",
        sourceKey: "Apple iTunes Store",
        title: `${title} (Rental)`,
        basePrice: item.trackRentalPrice,
        currency,
        shippingCost: 0,
        shippingVerified: true,
        isPhysical: false,
        sellerCountry: shipCountry,
        condition: "NEW",
        acquisition: "RENTAL",
        url: link,
        imageUrl: item.artworkUrl100 || "",
        note: "Digital rental"
      });
    }
  }
  return hits;
}

async function searchPokemonTcg(query, parsedQuery) {
  const clean = query.replace(/"/g, "").trim();
  const qParam = clean.includes(" ") ? `name:"${clean}"` : `name:${clean}*`;
  let res = await fetchWithTimeout(
    `https://api.pokemontcg.io/v2/cards?q=${encodeURIComponent(qParam)}&pageSize=8`
  );
  if (!res.ok) throw new Error(`Pokémon TCG HTTP ${res.status}`);
  let json = await res.json();
  let cards = Array.isArray(json?.data) ? json.data : [];

  if (cards.length === 0 && clean.includes(" ")) {
    const fallbackQ = clean.split(/\s+/).filter((w) => w.length > 1).map((w) => `name:${w}*`).join(" ");
    if (fallbackQ) {
      res = await fetchWithTimeout(`https://api.pokemontcg.io/v2/cards?q=${encodeURIComponent(fallbackQ)}&pageSize=8`);
      if (res.ok) {
        json = await res.json();
        cards = Array.isArray(json?.data) ? json.data : [];
      }
    }
  }

  const hits = [];
  for (const card of cards) {
    const label = `${card.name} (${card.set?.name || "Pokémon"} #${card.number || "?"})`;
    const img = card.images?.small || "";

    const cmPrices = card.cardmarket?.prices;
    const cmLow = cmPrices?.lowPrice ?? cmPrices?.trendPrice ?? cmPrices?.averageSellPrice;
    if (typeof cmLow === "number" && cmLow > 0) {
      hits.push({
        id: `pkm:cm:${card.id}`,
        marketplace: "Cardmarket (Pokémon TCG)",
        sourceKey: "Pokémon TCG",
        title: label,
        basePrice: cmLow,
        currency: "EUR",
        shippingCost: null,
        shippingVerified: false,
        isPhysical: true,
        sellerCountry: "DE",
        condition: "USED",
        acquisition: "BUY_NOW",
        url: card.cardmarket?.url || `https://www.cardmarket.com/en/Pokemon/Products/Search?searchString=${encodeURIComponent(card.name)}`,
        imageUrl: img,
        note: "Market index low; shipping at checkout"
      });
    }

    const tcgPrices = card.tcgplayer?.prices;
    const firstVariant = tcgPrices ? Object.values(tcgPrices)[0] : null;
    const tcgLow = firstVariant?.low ?? firstVariant?.market ?? firstVariant?.mid;
    if (typeof tcgLow === "number" && tcgLow > 0) {
      hits.push({
        id: `pkm:tcg:${card.id}`,
        marketplace: "TCGplayer (Pokémon TCG)",
        sourceKey: "Pokémon TCG",
        title: label,
        basePrice: tcgLow,
        currency: "USD",
        shippingCost: null,
        shippingVerified: false,
        isPhysical: true,
        sellerCountry: "US",
        condition: "USED",
        acquisition: "BUY_NOW",
        url: card.tcgplayer?.url || `https://www.tcgplayer.com/search/pokemon/product?q=${encodeURIComponent(card.name)}`,
        imageUrl: img,
        note: "Market index low; seller shipping varies"
      });
    }
  }

  const topCard = cards[0];
  if (topCard?.cardmarket?.prices && hits.some((h) => evaluateListingMatch(h, parsedQuery, state.queryContext).isHistoryEligible)) {
    const cm = topCard.cardmarket.prices;
    const nowMs = Date.now();
    const dayMs = 24 * 60 * 60 * 1000;
    [
      { key: "avg30", daysAgo: 30, label: "30d Avg", kindLabel: "Cardmarket 30-Day Avg (API)" },
      { key: "avg7", daysAgo: 7, label: "7d Avg", kindLabel: "Cardmarket 7-Day Avg (API)" },
      { key: "avg1", daysAgo: 1, label: "24h Avg", kindLabel: "Cardmarket 24-Hour Avg (API)" }
    ].forEach((b) => {
      if (typeof cm[b.key] === "number" && cm[b.key] > 0) {
        state.apiHistoricalPoints.push({
          timestampMs: nowMs - b.daysAgo * dayMs,
          label: b.label,
          kindLabel: `${b.kindLabel} — ${topCard.name}`,
          matchedItem: topCard.name,
          platform: "Cardmarket (Pokémon TCG)",
          amount: cm[b.key],
          currency: "EUR"
        });
      }
    });
  }

  return hits;
}

async function searchScryfall(query) {
  const url = `https://api.scryfall.com/cards/search?q=${encodeURIComponent(query)}`;
  const res = await fetchWithTimeout(url);
  if (res.status === 404) return [];
  if (!res.ok) throw new Error(`Scryfall HTTP ${res.status}`);
  const data = await res.json();
  const cards = Array.isArray(data?.data) ? data.data.slice(0, 8) : [];
  const hits = [];

  for (const card of cards) {
    const img = card.image_uris?.small || card.card_faces?.[0]?.image_uris?.small || "";
    const label = `${card.name} (${card.set_name || "MTG"} #${card.collector_number || "?"})`;

    const eur = Number.parseFloat(card.prices?.eur);
    if (!Number.isNaN(eur) && eur > 0) {
      hits.push({
        id: `mtg:cm:${card.id}`,
        marketplace: "Cardmarket (MTG)",
        sourceKey: "Scryfall MTG",
        title: label,
        basePrice: eur,
        currency: "EUR",
        shippingCost: null,
        shippingVerified: false,
        isPhysical: true,
        sellerCountry: "DE",
        condition: "USED",
        acquisition: "BUY_NOW",
        url: card.purchase_uris?.cardmarket || card.scryfall_uri,
        imageUrl: img,
        note: "Cardmarket index"
      });
    }

    const usd = Number.parseFloat(card.prices?.usd);
    if (!Number.isNaN(usd) && usd > 0) {
      hits.push({
        id: `mtg:tcg:${card.id}`,
        marketplace: "TCGplayer (MTG)",
        sourceKey: "Scryfall MTG",
        title: label,
        basePrice: usd,
        currency: "USD",
        shippingCost: null,
        shippingVerified: false,
        isPhysical: true,
        sellerCountry: "US",
        condition: "USED",
        acquisition: "BUY_NOW",
        url: card.purchase_uris?.tcgplayer || card.scryfall_uri,
        imageUrl: img,
        note: "TCGplayer market price"
      });
    }
  }
  return hits;
}

async function searchReverb(query) {
  const apiUrl = `https://api.reverb.com/api/listings?query=${encodeURIComponent(query)}&per_page=10`;
  let res;
  try {
    res = await fetchWithTimeout(apiUrl, {
      headers: { Accept: "application/hal+json", "Accept-Version": "3.0" }
    });
  } catch (_) {
    // Fallback through CORS relay if direct API call is CORS-blocked
    const raw = await fetchHtmlViaCorsRelay(apiUrl);
    const data = JSON.parse(raw);
    return mapReverbListings(data);
  }
  if (res.status === 401 || res.status === 403) {
    const err = new Error(`Reverb API auth required (HTTP ${res.status})`);
    err.ledgerStatus = "REQUIRES_AUTH";
    throw err;
  }
  if (!res.ok) throw new Error(`Reverb HTTP ${res.status}`);
  return mapReverbListings(await res.json());
}

function mapReverbListings(data) {
  const listings = Array.isArray(data?.listings) ? data.listings : [];
  return listings
    .map((item) => {
      const amount = Number.parseFloat(item.price?.amount);
      if (Number.isNaN(amount)) return null;
      const currency = item.price?.currency || "USD";
      const rate = Array.isArray(item.shipping?.rates) ? item.shipping.rates[0] : null;
      const shipAmount = rate?.rate?.amount != null ? Number.parseFloat(rate.rate.amount) : null;
      const condText = (item.condition?.display_name || "Used").toUpperCase();
      let condition = "USED";
      if (condText.includes("BRAND NEW") || condText === "NEW") condition = "NEW";
      else if (condText.includes("MINT") || condText.includes("OPEN")) condition = "OPEN_BOX";
      else if (condText.includes("NON FUNCTIONING") || condText.includes("POOR")) condition = "DAMAGED";

      return {
        id: `reverb:${item.id}`,
        marketplace: "Reverb",
        sourceKey: "Reverb",
        title: item.title || "Reverb Listing",
        basePrice: amount,
        currency,
        shippingCost: Number.isNaN(shipAmount) ? null : shipAmount,
        shippingVerified: shipAmount != null && !Number.isNaN(shipAmount),
        isPhysical: true,
        sellerCountry: item.shop?.country_code || "US",
        condition,
        acquisition: "BUY_NOW",
        url: item._links?.web?.href || "https://reverb.com",
        imageUrl: item.photos?.[0]?._links?.thumbnail?.href || "",
        note: item.condition?.display_name || "Music gear"
      };
    })
    .filter(Boolean);
}

async function searchDiscogs(query, token) {
  if (!token) {
    const err = new Error("Optional Discogs token not provided.");
    err.ledgerStatus = "REQUIRES_AUTH";
    throw err;
  }
  const res = await fetchWithTimeout(
    `https://api.discogs.com/database/search?q=${encodeURIComponent(query)}&type=release&per_page=5&token=${encodeURIComponent(token)}`
  );
  if (res.status === 401 || res.status === 403) {
    const err = new Error(`Discogs token rejected (HTTP ${res.status})`);
    err.ledgerStatus = "REQUIRES_AUTH";
    throw err;
  }
  if (!res.ok) throw new Error(`Discogs HTTP ${res.status}`);
  const data = await res.json();
  const releases = Array.isArray(data?.results) ? data.results.slice(0, 4) : [];
  const hits = [];

  for (const rel of releases) {
    const statsRes = await fetchWithTimeout(
      `https://api.discogs.com/marketplace/stats/${rel.id}?token=${encodeURIComponent(token)}`
    );
    if (!statsRes.ok) continue;
    const stats = await statsRes.json();
    const low = stats?.lowest_price;
    if (!low || typeof low.value !== "number") continue;
    hits.push({
      id: `discogs:${rel.id}`,
      marketplace: "Discogs",
      sourceKey: "Discogs",
      title: rel.title || "Discogs Release",
      basePrice: low.value,
      currency: low.currency || "USD",
      shippingCost: null,
      shippingVerified: false,
      isPhysical: true,
      sellerCountry: rel.country || "",
      condition: "USED",
      acquisition: "BUY_NOW",
      url: rel.uri ? `https://www.discogs.com${rel.uri}` : `https://www.discogs.com/release/${rel.id}`,
      imageUrl: rel.thumb || "",
      note: `${stats.num_for_sale || 1} for sale`
    });
  }
  return hits;
}

function deduplicateListings(items) {
  const seen = new Set();
  const unique = [];
  for (const item of items) {
    const normTitle = normalizeText(item.title).replace(/\s+/g, "").slice(0, 48);
    const key = `${item.marketplace}|${normTitle}|${Math.round(item.basePrice * 100)}`;
    if (!seen.has(key)) {
      seen.add(key);
      unique.push(item);
    }
  }
  return unique;
}

function enrichAndFilterListings(rawListings) {
  const { country: shipCountry, currency: targetCurrency } = getShipConfig();
  const condFilter = el.conditionFilter.value;
  const acqFilter = el.acquisitionFilter.value;
  const sortMode = el.sortSelect.value;
  const hideTraps = el.hideTrapsBtn.getAttribute("aria-pressed") === "true";
  const verifiedOnly = el.verifiedShippingOnlyBtn.getAttribute("aria-pressed") === "true";
  const parsedQuery = state.queryContext?.parsed || parseQueryTokens(state.lastQuery);

  const enriched = rawListings
    .map((item) => {
      const match = evaluateListingMatch(item, parsedQuery, state.queryContext);
      if (!match.passesRelevance) return null;

      const baseTarget = convertCurrency(item.basePrice, item.currency, targetCurrency);
      const shippingTarget = item.shippingCost != null
        ? convertCurrency(item.shippingCost, item.currency, targetCurrency)
        : null;
      const taxes = estimateImportCharges({
        baseTarget,
        shippingTarget,
        isPhysical: item.isPhysical,
        sellerCountry: item.sellerCountry,
        shipToCountry: shipCountry
      });
      const knownTotalTarget = baseTarget != null
        ? baseTarget + (shippingTarget || 0) + taxes.vat + taxes.duty
        : null;

      return {
        ...item,
        targetCurrency,
        baseTarget,
        shippingTarget,
        vatTarget: taxes.vat,
        dutyTarget: taxes.duty,
        taxNote: taxes.note,
        knownTotalTarget,
        trapReason: match.trapReason,
        editionConflict: match.editionConflict,
        isHistoryEligible: match.isHistoryEligible,
        match
      };
    })
    .filter((item) => {
      if (!item || item.knownTotalTarget == null) return false;
      if (condFilter !== "ALL" && item.condition !== condFilter) return false;
      if (acqFilter !== "ALL" && item.acquisition !== acqFilter) return false;
      if (hideTraps && (item.trapReason || item.editionConflict)) return false;
      if (verifiedOnly && !item.shippingVerified) return false;
      return true;
    });

  enriched.sort((a, b) => {
    if (sortMode === "BEST_MATCH_PRICE") {
      // Tier 0: Exact/Strong non-trap, non-wrong-edition matches
      const tierA = (!a.trapReason && !a.editionConflict && a.match.ratio >= 0.65) ? 0 : 1;
      const tierB = (!b.trapReason && !b.editionConflict && b.match.ratio >= 0.65) ? 0 : 1;
      if (tierA !== tierB) return tierA - tierB;
    }
    if (a.shippingVerified !== b.shippingVerified) {
      return a.shippingVerified ? -1 : 1;
    }
    return a.knownTotalTarget - b.knownTotalTarget;
  });

  return enriched;
}

// --- Price History Persistence & Recharts Model ---

function readHistoryStore() {
  try {
    const raw = window.localStorage.getItem(HISTORY_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    return [];
  }
}

function writeHistoryStore(entries) {
  try {
    window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(entries.slice(-200)));
  } catch (_) {}
}

function recordLiveObservation(query, targetCurrency, filteredListings) {
  const eligible = (filteredListings || []).filter((i) => i.isHistoryEligible);
  if (!query || eligible.length === 0) return;

  const queryKey = normalizeQueryKey(query);
  const nowMs = state.lastTimestamp ? state.lastTimestamp.getTime() : Date.now();
  const platforms = {};
  const matchedTitle = state.queryContext?.canonicalTitle || eligible[0].title;

  for (const item of eligible) {
    const name = item.marketplace;
    if (!name || item.knownTotalTarget == null) continue;
    const existing = platforms[name];
    if (!existing || item.knownTotalTarget < existing.amount) {
      platforms[name] = {
        amount: Number(item.knownTotalTarget.toFixed(2)),
        currency: targetCurrency,
        itemTitle: item.title
      };
    }
  }

  if (Object.keys(platforms).length === 0) return;

  const store = readHistoryStore();
  const lastForQuery = [...store].reverse().find((s) => s.queryKey === queryKey);
  if (lastForQuery && Math.abs(nowMs - lastForQuery.timestampMs) < 5000) {
    lastForQuery.platforms = platforms;
    lastForQuery.timestampMs = nowMs;
    lastForQuery.matchedTitle = matchedTitle;
  } else {
    store.push({
      queryKey,
      matchedTitle,
      timestampMs: nowMs,
      platforms
    });
  }
  writeHistoryStore(store);
}

function clearSavedQueryHistory(query) {
  const queryKey = normalizeQueryKey(query);
  writeHistoryStore(readHistoryStore().filter((entry) => entry.queryKey !== queryKey));
  renderResults();
}

function buildTrendChartModel(filteredListings, targetCurrency) {
  const queryKey = normalizeQueryKey(state.lastQuery);
  const eligible = filteredListings.filter((i) => i.isHistoryEligible);
  const savedSnapshots = readHistoryStore().filter((entry) => entry.queryKey === queryKey);
  const timeBuckets = new Map();

  const matchedProductTitle =
    state.queryContext?.canonicalTitle ||
    eligible[0]?.title ||
    savedSnapshots[savedSnapshots.length - 1]?.matchedTitle ||
    null;

  const formatSnapLabel = (tsMs) =>
    new Date(tsMs).toLocaleString(undefined, {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit"
    });

  for (const pt of state.apiHistoricalPoints) {
    const converted = convertCurrency(pt.amount, pt.currency, targetCurrency);
    if (converted == null) continue;
    const bucketKey = `api:${pt.label}`;
    if (!timeBuckets.has(bucketKey)) {
      timeBuckets.set(bucketKey, {
        timestampMs: pt.timestampMs,
        label: pt.label,
        kindLabel: pt.kindLabel,
        matchedItem: pt.matchedItem || matchedProductTitle,
        values: {}
      });
    }
    timeBuckets.get(bucketKey).values[pt.platform] = Number(converted.toFixed(2));
  }

  for (const snap of savedSnapshots) {
    const bucketKey = `obs:${snap.timestampMs}`;
    const values = {};
    let firstItemTitle = snap.matchedTitle || matchedProductTitle;
    for (const [platform, priceObj] of Object.entries(snap.platforms || {})) {
      const converted = convertCurrency(priceObj.amount, priceObj.currency, targetCurrency);
      if (converted != null) {
        values[platform] = Number(converted.toFixed(2));
        if (!firstItemTitle && priceObj.itemTitle) firstItemTitle = priceObj.itemTitle;
      }
    }
    if (Object.keys(values).length > 0) {
      timeBuckets.set(bucketKey, {
        timestampMs: snap.timestampMs,
        label: formatSnapLabel(snap.timestampMs),
        kindLabel: "Live Search Observation",
        matchedItem: firstItemTitle,
        values
      });
    }
  }

  if (eligible.length > 0) {
    const nowMs = state.lastTimestamp ? state.lastTimestamp.getTime() : Date.now();
    const hasCloseSaved = savedSnapshots.some((s) => Math.abs(nowMs - s.timestampMs) < 5000);
    if (!hasCloseSaved) {
      const liveValues = {};
      for (const item of eligible) {
        const name = item.marketplace;
        const val = Number(item.knownTotalTarget.toFixed(2));
        if (liveValues[name] == null || val < liveValues[name]) {
          liveValues[name] = val;
        }
      }
      if (Object.keys(liveValues).length > 0) {
        timeBuckets.set("live:now", {
          timestampMs: nowMs,
          label: "Live Now",
          kindLabel: "Current Live Retrieval",
          matchedItem: eligible[0].title,
          values: liveValues
        });
      }
    }
  }

  const orderedPoints = Array.from(timeBuckets.values()).sort((a, b) => a.timestampMs - b.timestampMs);
  const platformScore = new Map();
  for (const pt of orderedPoints) {
    for (const [platform, val] of Object.entries(pt.values)) {
      const prev = platformScore.get(platform) || { count: 0, min: val };
      platformScore.set(platform, { count: prev.count + 1, min: Math.min(prev.min, val) });
    }
  }

  const topPlatforms = Array.from(platformScore.entries())
    .sort((a, b) => (b[1].count - a[1].count) || (a[1].min - b[1].min))
    .slice(0, 6)
    .map(([name]) => name);

  let minPrice = null;
  let maxPrice = null;
  const series = orderedPoints
    .map((pt) => {
      const row = {
        label: pt.label,
        kindLabel: pt.kindLabel,
        matchedItem: pt.matchedItem
      };
      let hasAny = false;
      for (const p of topPlatforms) {
        if (typeof pt.values[p] === "number") {
          row[p] = pt.values[p];
          hasAny = true;
          minPrice = minPrice == null ? pt.values[p] : Math.min(minPrice, pt.values[p]);
          maxPrice = maxPrice == null ? pt.values[p] : Math.max(maxPrice, pt.values[p]);
        }
      }
      return hasAny ? row : null;
    })
    .filter(Boolean);

  const variance = minPrice != null && maxPrice != null ? maxPrice - minPrice : null;
  const provenanceNote =
    series.length <= 1
      ? "Plotting verified matching offers from this live search. Re-run searches over time to extend multi-point trend lines across platforms."
      : `Plotting ${series.length} verified timestamped points across ${topPlatforms.length} platform(s). Unrelated items and accessory traps are excluded.`;

  return {
    query: state.lastQuery,
    matchedProductTitle,
    currency: targetCurrency,
    series,
    platforms: topPlatforms,
    minPrice,
    maxPrice,
    variance,
    observationCount: series.length,
    provenanceNote
  };
}

async function updateTrendChart(filteredListings, targetCurrency) {
  if (!state.lastQuery) {
    el.trendChartSection.hidden = true;
    return;
  }

  const model = buildTrendChartModel(filteredListings, targetCurrency);
  el.trendChartSection.hidden = false;

  if (state.trendLoadFailed) {
    el.trendChartRoot.innerHTML = `<div class="empty-state" style="padding: 18px;">Recharts library could not be loaded while offline.</div>`;
    return;
  }

  try {
    if (!state.trendRenderer) {
      const mod = await import("./priceTrendChart.js");
      state.trendRenderer = mod.renderRechartsTrend;
    }
    state.trendRenderer(el.trendChartRoot, {
      ...model,
      formatMoney,
      onClearHistory: () => clearSavedQueryHistory(state.lastQuery)
    });
  } catch (err) {
    state.trendLoadFailed = true;
    el.trendChartRoot.innerHTML = `<div class="empty-state" style="padding: 18px;">Chart unavailable (${escapeHtml(err.message)}).</div>`;
  }
}

// --- Rendering ---

function renderSourceLedger() {
  const searchedCount = state.sourceLedgers.filter((s) => s.status !== "PENDING").length;
  el.sourcesSearchedMetric.textContent = `${searchedCount} / ${state.sourceLedgers.length}`;

  const okCount = state.sourceLedgers.filter((s) => s.status === "SEARCHED").length;
  el.sourcesProgressSub.textContent = state.isSearching
    ? "Scanning live sources…"
    : `${okCount} returned matched offers`;

  el.sourceStatusList.innerHTML = state.sourceLedgers
    .map((s) => {
      let badgeClass = "badge";
      if (s.status === "SEARCHED") badgeClass = "badge badge--solid";
      else if (s.status === "NO_RESULTS") badgeClass = "badge badge--bright";
      else if (s.status === "UNAVAILABLE" || s.status === "REQUIRES_AUTH") badgeClass = "badge badge--warn";

      return `
        <div class="source-row">
          <div class="source-row-top">
            <span class="source-name">${escapeHtml(s.name)}</span>
            <span class="${badgeClass}">${escapeHtml(s.status)}</span>
          </div>
          <div class="source-detail">${escapeHtml(s.detail)}</div>
        </div>
      `;
    })
    .join("");
}

function renderCard(item, index, targetCurrency, isCheapestVerified) {
  const thumbHtml = item.imageUrl
    ? `<img class="listing-thumb" src="${escapeHtml(item.imageUrl)}" alt="${escapeHtml(item.title)}" loading="lazy" />`
    : `<div class="listing-thumb">${escapeHtml(item.sourceKey)}</div>`;

  const badges = [];
  if (isCheapestVerified) {
    badges.push(`<span class="badge badge--solid">Lowest Verified Total</span>`);
  }
  badges.push(`<span class="badge badge--bright">${escapeHtml(item.match.label)}</span>`);
  badges.push(`<span class="badge">${escapeHtml(item.condition)}</span>`);
  badges.push(`<span class="badge">${escapeHtml(item.acquisition.replace("_", " "))}</span>`);
  if (!item.shippingVerified) {
    badges.push(`<span class="badge badge--warn">Shipping TBD</span>`);
  }
  if (item.editionConflict) {
    badges.push(`<span class="badge badge--warn">${escapeHtml(item.editionConflict)}</span>`);
  }
  if (item.trapReason) {
    badges.push(`<span class="badge badge--warn">${escapeHtml(item.trapReason)}</span>`);
  }

  const breakdownParts = [`Base ${formatMoney(item.baseTarget, targetCurrency)}`];
  if (item.currency !== targetCurrency) {
    breakdownParts.push(`(${formatMoney(item.basePrice, item.currency)})`);
  }
  if (item.shippingVerified) {
    breakdownParts.push(item.shippingTarget > 0 ? `+ ${formatMoney(item.shippingTarget, targetCurrency)} ship` : "+ Free / Digital");
  } else {
    breakdownParts.push("+ Shipping TBD");
  }
  if (item.vatTarget + item.dutyTarget > 0) {
    breakdownParts.push(`+ ${formatMoney(item.vatTarget + item.dutyTarget, targetCurrency)} est. VAT`);
  }

  return `
    <article class="listing-card ${isCheapestVerified ? "listing-card--cheapest" : ""}">
      ${thumbHtml}
      <div>
        <h3 class="listing-title">#${index + 1} &middot; ${escapeHtml(item.title)}</h3>
        <div class="badges-row">${badges.join("")}</div>
        <div class="listing-meta">
          <strong>${escapeHtml(item.marketplace)}</strong>
          ${item.isbn ? ` &bull; ISBN ${escapeHtml(item.isbn)}` : ""}
          ${item.sellerCountry ? ` &bull; ${escapeHtml(item.sellerCountry)}` : ""}
          ${item.taxNote ? ` &bull; ${escapeHtml(item.taxNote)}` : ""}
          ${item.note ? ` &bull; ${escapeHtml(item.note)}` : ""}
        </div>
      </div>
      <div class="listing-price-col">
        <div class="price-total">${formatMoney(item.knownTotalTarget, targetCurrency)}</div>
        <div class="price-breakdown">${escapeHtml(breakdownParts.join(" "))}</div>
        <a class="btn btn-secondary btn-xs" href="${escapeHtml(item.url)}" target="_blank" rel="noopener noreferrer">
          Open Listing &rarr;
        </a>
      </div>
    </article>
  `;
}

function renderResults() {
  const { currency: targetCurrency } = getShipConfig();
  const filtered = enrichAndFilterListings(state.rawListings);
  const verified = filtered.filter((i) => i.shippingVerified);
  const unverified = filtered.filter((i) => !i.shippingVerified);

  el.totalOffersMetric.textContent = String(filtered.length);
  el.dedupSub.textContent = `${state.rawListings.length} raw hits (${filtered.length} matched)`;
  el.resultsCountLabel.textContent = `${filtered.length} matching offers`;
  el.exportCsvBtn.disabled = filtered.length === 0;

  if (verified.length > 0) {
    el.lowestVerifiedMetric.textContent = formatMoney(verified[0].knownTotalTarget, targetCurrency);
    el.lowestVerifiedSub.textContent = `${verified[0].marketplace} • ${verified[0].condition}`;
  } else if (unverified.length > 0) {
    el.lowestVerifiedMetric.textContent = `${formatMoney(unverified[0].knownTotalTarget, targetCurrency)}+`;
    el.lowestVerifiedSub.textContent = "Base price only (shipping TBD)";
  } else {
    el.lowestVerifiedMetric.textContent = "—";
    el.lowestVerifiedSub.textContent = state.lastQuery ? "No matching offers" : "Run a live search";
  }

  if (!state.lastQuery) return;

  updateTrendChart(filtered, targetCurrency);

  if (filtered.length === 0) {
    el.resultsContainer.innerHTML = `
      <div class="empty-state">
        <p style="margin-top:0;"><strong>No matching live offers found for "${escapeHtml(state.lastQuery)}".</strong></p>
        <p style="margin-bottom:0;">Unrelated matches were filtered out automatically. Inspect the Source Ledger on the right to see which scrapers were blocked by bot protection vs returned 0 hits, or open Filters to adjust condition/edition settings.</p>
      </div>
    `;
    return;
  }

  let html = "";
  if (verified.length > 0) {
    html += verified.map((item, idx) => renderCard(item, idx, targetCurrency, idx === 0)).join("");
  }
  if (unverified.length > 0) {
    html += `<div class="divider-title">Offers With Unverified Shipping</div>`;
    html += unverified.map((item, idx) => renderCard(item, verified.length + idx, targetCurrency, false)).join("");
  }
  el.resultsContainer.innerHTML = html;
}

async function runLiveSearch(query) {
  const trimmed = query.trim();
  if (!trimmed || state.isSearching) return;

  state.isSearching = true;
  state.lastQuery = trimmed;
  state.rawListings = [];
  state.apiHistoricalPoints = [];
  state.queryContext = { rawQuery: trimmed, parsed: parseQueryTokens(trimmed), searchTerms: [trimmed] };
  state.lastTimestamp = new Date();

  el.searchBtn.disabled = true;
  el.searchBtn.textContent = "Searching…";
  el.timestampMetric.textContent = state.lastTimestamp.toLocaleTimeString();
  el.queryEchoSub.textContent = `"${trimmed}"`;
  el.canonicalBanner.hidden = true;

  // Jump straight to the results & trend chart section
  requestAnimationFrame(() => {
    el.resultsAnchor.scrollIntoView({ behavior: "smooth", block: "start" });
  });

  // Resolve canonical title & ISBNs first to maximize textbook/book scraper accuracy
  state.queryContext = await resolveQueryContext(trimmed);
  if (state.queryContext.canonicalTitle) {
    const isbnText = state.queryContext.isbns.length > 0
      ? ` &bull; ISBNs: ${escapeHtml(state.queryContext.isbns.slice(0, 3).join(", "))}`
      : "";
    el.canonicalBanner.innerHTML = `<span><strong>Matched Product:</strong> ${escapeHtml(state.queryContext.canonicalTitle)}${state.queryContext.canonicalAuthor ? ` by ${escapeHtml(state.queryContext.canonicalAuthor)}` : ""}${isbnText}</span><span>Cross-searching resolved ISBNs &amp; title</span>`;
    el.canonicalBanner.hidden = false;
  }

  const { country: shipCountry, currency: targetCurrency } = getShipConfig();
  const discogsToken = el.discogsTokenInput.value.trim();
  const ctx = state.queryContext;
  const parsed = ctx.parsed;

  const liveConnectors = [
    { name: "Google Books", run: () => searchGoogleBooks(trimmed, ctx) },
    { name: "AbeBooks (Scrape)", run: () => searchAbeBooksScrape(trimmed, ctx) },
    { name: "Biblio (Scrape)", run: () => searchBiblioScrape(trimmed, ctx) },
    { name: "ThriftBooks (Scrape)", run: () => searchThriftBooksScrape(trimmed, ctx) },
    { name: "Blackwell's (Scrape)", run: () => searchBlackwellsScrape(trimmed, ctx) },
    { name: "World of Books (Scrape)", run: () => searchWorldOfBooksScrape(trimmed, ctx) },
    { name: "eBay (Scrape)", run: () => searchEbayScrape(trimmed) },
    { name: "IT Bookstore", run: () => searchItBookStore(trimmed, ctx) },
    { name: "Gutendex (Free eBooks)", run: () => searchGutendex(trimmed) },
    { name: "CheapShark PC Deals", run: () => searchCheapShark(trimmed, parsed) },
    { name: "Apple iTunes Store", run: () => searchItunes(trimmed, shipCountry) },
    { name: "Pokémon TCG", run: () => searchPokemonTcg(trimmed, parsed) },
    { name: "Scryfall MTG", run: () => searchScryfall(trimmed) },
    { name: "Reverb", run: () => searchReverb(trimmed) },
    { name: "Discogs", run: () => searchDiscogs(trimmed, discogsToken) }
  ];

  state.sourceLedgers = [
    ...liveConnectors.map((c) => ({
      name: c.name,
      status: "PENDING",
      detail: "Querying live…"
    })),
    ...NATIVE_ONLY_CATALOG.map((c) => ({ ...c }))
  ];
  renderSourceLedger();
  el.resultsContainer.innerHTML = `<div class="empty-state">Scanning ${liveConnectors.length} live APIs &amp; web scrapers concurrently…</div>`;

  await Promise.allSettled(
    liveConnectors.map(async (connector, idx) => {
      try {
        const hits = await connector.run();
        const relevantHits = hits.filter((item) => evaluateListingMatch(item, parsed, ctx).passesRelevance);
        if (relevantHits.length > 0) {
          state.rawListings = deduplicateListings([...state.rawListings, ...relevantHits]);
          state.sourceLedgers[idx] = {
            name: connector.name,
            status: "SEARCHED",
            detail: `Matched ${relevantHits.length} relevant offer${relevantHits.length === 1 ? "" : "s"}.`
          };
        } else if (hits.length > 0) {
          state.sourceLedgers[idx] = {
            name: connector.name,
            status: "NO_RESULTS",
            detail: `Filtered out ${hits.length} unrelated hit${hits.length === 1 ? "" : "s"}.`
          };
        } else {
          state.sourceLedgers[idx] = {
            name: connector.name,
            status: "NO_RESULTS",
            detail: "0 priced matches returned."
          };
        }
      } catch (err) {
        const isCorsOrNetwork = err?.name === "TypeError" || /abort|timeout|network/i.test(err?.message || "");
        const status = err?.ledgerStatus || "UNAVAILABLE";
        const detail = err?.isBotBlocked
          ? "Blocked by marketplace bot protection."
          : isCorsOrNetwork
            ? "Blocked by CORS / relay timeout."
            : (err?.message || "Unavailable.");
        state.sourceLedgers[idx] = {
          name: connector.name,
          status,
          detail
        };
      }
      renderSourceLedger();
      renderResults();
    })
  );

  state.isSearching = false;
  recordLiveObservation(trimmed, targetCurrency, enrichAndFilterListings(state.rawListings));
  el.searchBtn.disabled = false;
  el.searchBtn.textContent = "Search Live";
  renderSourceLedger();
  renderResults();
}

function exportResultsCsv() {
  const rows = enrichAndFilterListings(state.rawListings);
  if (rows.length === 0) return;

  const headers = [
    "Rank", "Title", "Marketplace", "Match", "Condition", "Acquisition",
    "TargetCurrency", "KnownTotal", "BaseConverted", "ShippingVerified",
    "EstVatAndDuty", "OrigAmount", "OrigCurrency", "ISBN", "URL", "RetrievedAt"
  ];
  const csvEscape = (v) => `"${String(v ?? "").replace(/"/g, '""')}"`;
  const lines = [
    headers.join(","),
    ...rows.map((r, i) => [
      i + 1,
      r.title,
      r.marketplace,
      r.match.label,
      r.condition,
      r.acquisition,
      r.targetCurrency,
      r.knownTotalTarget.toFixed(2),
      r.baseTarget.toFixed(2),
      r.shippingVerified ? "Verified" : "Unverified",
      (r.vatTarget + r.dutyTarget).toFixed(2),
      r.basePrice,
      r.currency,
      r.isbn || "",
      r.url,
      state.lastTimestamp ? state.lastTimestamp.toISOString() : ""
    ].map(csvEscape).join(","))
  ];

  const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `pricefloor-${state.lastQuery.toLowerCase().replace(/[^a-z0-9]+/g, "-")}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

// --- Event Wiring ---

el.form.addEventListener("submit", (e) => {
  e.preventDefault();
  runLiveSearch(el.queryInput.value);
});

document.querySelectorAll("[data-query]").forEach((btn) => {
  btn.addEventListener("click", () => {
    const q = btn.getAttribute("data-query") || "";
    el.queryInput.value = q;
    runLiveSearch(q);
  });
});

el.toggleFiltersBtn.addEventListener("click", () => {
  const expanded = el.toggleFiltersBtn.getAttribute("aria-expanded") === "true";
  el.toggleFiltersBtn.setAttribute("aria-expanded", String(!expanded));
  el.filtersDrawer.hidden = expanded;
});

[el.countrySelect, el.sortSelect, el.conditionFilter, el.acquisitionFilter].forEach((ctrl) => {
  ctrl.addEventListener("change", () => renderResults());
});

[el.hideTrapsBtn, el.verifiedShippingOnlyBtn].forEach((btn) => {
  btn.addEventListener("click", () => {
    const pressed = btn.getAttribute("aria-pressed") === "true";
    btn.setAttribute("aria-pressed", String(!pressed));
    renderResults();
  });
});

el.clearTokenBtn.addEventListener("click", () => {
  el.discogsTokenInput.value = "";
});

el.exportCsvBtn.addEventListener("click", exportResultsCsv);

loadLiveFxRates();

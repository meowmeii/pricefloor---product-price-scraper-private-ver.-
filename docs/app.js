// PriceFloor Live Browser Companion
// Real live HTTP retrieval across CORS-enabled public marketplace APIs.
// Zero mocked listings, zero hardcoded historical price tables.

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

const EU_VAT_RATES = {
  IE: 0.23,
  DE: 0.19,
  FR: 0.20
};

const ACCESSORY_KEYWORDS = [
  "case", "cover", "sleeve", "pouch", "screen protector", "tempered glass",
  "charger", "charging cable", "usb cable", "adapter", "dongle",
  "replacement", "spare part", "shell", "housing", "box only", "empty box",
  "manual only", "booklet", "sticker", "decal", "strap", "band", "dock",
  "stand", "mount", "holder", "skin", "dlc", "season pass", "soundtrack",
  "ost", "artbook", "strategy guide", "poster", "dummy", "display model"
];

const NATIVE_ONLY_CATALOG = [
  { id: "ebay", name: "eBay Finding API", status: "REQUIRES_AUTH", detail: "Requires EBAY_CLIENT_ID via native Android Retrofit/OkHttp service." },
  { id: "serpapi", name: "Google Shopping (SerpApi)", status: "REQUIRES_AUTH", detail: "Requires SERPAPI_KEY configured in Android Secrets / .env." },
  { id: "cex", name: "CeX (uk/ie.webuy.com)", status: "UNAVAILABLE", detail: "Public Algolia/web endpoints block browser CORS; queried natively in Android app." },
  { id: "backmarket", name: "Back Market", status: "UNAVAILABLE", detail: "Refurbished storefront blocks browser CORS; supported via native Android search." },
  { id: "adverts", name: "Adverts.ie", status: "UNAVAILABLE", detail: "Local Irish classifieds lack CORS headers for browser fetch; supported on Android." },
  { id: "donedeal", name: "DoneDeal.ie", status: "UNAVAILABLE", detail: "Local classifieds block browser CORS; supported on Android." },
  { id: "amazon", name: "Amazon Marketplace", status: "REQUIRES_AUTH", detail: "Amazon PA-API requires signed affiliate credentials (server/native only)." },
  { id: "vinted", name: "Vinted / Depop / Mercari", status: "UNSUPPORTED", detail: "No public unauthenticated CORS search API permitted for third-party browsers." },
  { id: "rentals", name: "Fat Llama / Grover Rentals", status: "UNSUPPORTED", detail: "No public unauthenticated CORS API; rental comparison links available on Android." }
];

const state = {
  fxBase: "EUR",
  fxRates: { EUR: 1 },
  fxTimestamp: null,
  fxLive: false,
  cheapSharkStores: null,
  rawListings: [],
  sourceLedgers: [],
  lastQuery: "",
  lastTimestamp: null,
  isSearching: false
};

const el = {
  form: document.getElementById("searchForm"),
  queryInput: document.getElementById("queryInput"),
  searchBtn: document.getElementById("searchBtn"),
  countrySelect: document.getElementById("countrySelect"),
  conditionFilter: document.getElementById("conditionFilter"),
  acquisitionFilter: document.getElementById("acquisitionFilter"),
  hideTrapsBtn: document.getElementById("hideTrapsBtn"),
  verifiedShippingOnlyBtn: document.getElementById("verifiedShippingOnlyBtn"),
  discogsTokenInput: document.getElementById("discogsTokenInput"),
  clearTokenBtn: document.getElementById("clearTokenBtn"),
  exportCsvBtn: document.getElementById("exportCsvBtn"),
  fxStatusBadge: document.getElementById("fxStatusBadge"),
  lowestVerifiedMetric: document.getElementById("lowestVerifiedMetric"),
  lowestVerifiedSub: document.getElementById("lowestVerifiedSub"),
  totalOffersMetric: document.getElementById("totalOffersMetric"),
  dedupSub: document.getElementById("dedupSub"),
  sourcesSearchedMetric: document.getElementById("sourcesSearchedMetric"),
  sourcesProgressSub: document.getElementById("sourcesProgressSub"),
  timestampMetric: document.getElementById("timestampMetric"),
  queryEchoSub: document.getElementById("queryEchoSub"),
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

function convertCurrency(amount, fromCurrency, toCurrency) {
  if (amount == null || Number.isNaN(amount)) return null;
  const from = (fromCurrency || "EUR").toUpperCase();
  const to = (toCurrency || "EUR").toUpperCase();
  if (from === to) return Number(amount);

  const fromRate = state.fxRates[from];
  const toRate = state.fxRates[to];
  if (!fromRate || !toRate) return null;
  // state.fxRates are relative to EUR (1 EUR = rate[X] X)
  const inEur = Number(amount) / fromRate;
  return inEur * toRate;
}

function estimateImportCharges({ baseTarget, shippingTarget, isPhysical, sellerCountry, shipToCountry }) {
  if (!isPhysical || !sellerCountry || !shipToCountry) {
    return { vat: 0, duty: 0, note: "" };
  }
  const sellerIso = sellerCountry.toUpperCase();
  const destIso = shipToCountry.toUpperCase();
  if (sellerIso === destIso) return { vat: 0, duty: 0, note: "" };

  const bothEu = EU_COUNTRIES.has(sellerIso) && EU_COUNTRIES.has(destIso);
  if (bothEu) return { vat: 0, duty: 0, note: "EU intra-community (no extra import duty)" };

  if (EU_COUNTRIES.has(destIso)) {
    const customsValue = (baseTarget || 0) + (shippingTarget || 0);
    const vatRate = EU_VAT_RATES[destIso] ?? 0.21;
    const vat = customsValue * vatRate;
    // EU customs duty threshold €150
    const customsEur = convertCurrency(customsValue, getShipConfig().currency, "EUR") ?? customsValue;
    const duty = customsEur > 150 ? customsValue * 0.025 : 0;
    const parts = [`Est. ${Math.round(vatRate * 100)}% import VAT`];
    if (duty > 0) parts.push("est. 2.5% duty (>€150)");
    return { vat, duty, note: parts.join(" + ") };
  }

  return { vat: 0, duty: 0, note: "" };
}

function detectAccessoryTrap(title, query) {
  const lowerTitle = title.toLowerCase();
  const lowerQuery = query.toLowerCase();
  for (const kw of ACCESSORY_KEYWORDS) {
    if (lowerTitle.includes(kw) && !lowerQuery.includes(kw)) {
      return `Possible accessory/part trap ("${kw}")`;
    }
  }
  return null;
}

function computeMatchScore(title, query) {
  const tokens = query
    .toLowerCase()
    .split(/\s+/)
    .map((t) => t.replace(/[^a-z0-9]/g, ""))
    .filter((t) => t.length >= 2);
  if (tokens.length === 0) return { label: "Equivalent", ratio: 0.7 };
  const hay = title.toLowerCase();
  const matched = tokens.filter((t) => hay.includes(t)).length;
  const ratio = matched / tokens.length;
  if (ratio >= 0.99) return { label: "Exact Match", ratio };
  if (ratio >= 0.6) return { label: "Equivalent", ratio };
  return { label: "Partial Match", ratio };
}

async function fetchWithTimeout(url, options = {}, timeoutMs = 9500) {
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

async function loadLiveFxRates() {
  try {
    const res = await fetchWithTimeout("https://api.frankfurter.app/latest?from=EUR", {}, 7000);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    if (!data?.rates) throw new Error("Missing rates payload");
    state.fxRates = { EUR: 1, ...data.rates };
    state.fxTimestamp = data.date || new Date().toISOString().slice(0, 10);
    state.fxLive = true;
    el.fxStatusBadge.classList.add("pill-badge--live");
    el.fxStatusBadge.innerHTML = `<span class="dot"></span><span>Live ECB FX (${escapeHtml(state.fxTimestamp)})</span>`;
    return;
  } catch (_) {
    // Try secondary public CORS FX feed
  }

  try {
    const res = await fetchWithTimeout("https://open.er-api.com/v6/latest/EUR", {}, 7000);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    if (!data?.rates) throw new Error("Missing rates payload");
    state.fxRates = { EUR: 1, ...data.rates };
    state.fxTimestamp = new Date().toISOString().slice(0, 10);
    state.fxLive = true;
    el.fxStatusBadge.classList.add("pill-badge--live");
    el.fxStatusBadge.innerHTML = `<span class="dot"></span><span>Live FX (${escapeHtml(state.fxTimestamp)})</span>`;
  } catch (err) {
    state.fxLive = false;
    el.fxStatusBadge.innerHTML = `<span class="dot"></span><span>FX feed unreachable (${escapeHtml(err.message)})</span>`;
  }
}

// --- Live Marketplace Connectors ---

async function searchCheapShark(query) {
  if (!state.cheapSharkStores) {
    const storeRes = await fetchWithTimeout("https://www.cheapshark.com/api/1.0/stores");
    if (storeRes.ok) {
      const stores = await storeRes.json();
      state.cheapSharkStores = new Map(stores.map((s) => [String(s.storeID), s.storeName]));
    } else {
      state.cheapSharkStores = new Map();
    }
  }

  // Note: Do NOT pass sortBy=Price (CheapShark returns HTTP 400 on that value).
  const url = `https://www.cheapshark.com/api/1.0/deals?title=${encodeURIComponent(query)}&pageSize=12`;
  const res = await fetchWithTimeout(url);
  if (!res.ok) throw new Error(`CheapShark HTTP ${res.status}`);
  const deals = await res.json();
  if (!Array.isArray(deals) || deals.length === 0) return [];

  return deals
    .map((d) => {
      const price = Number.parseFloat(d.salePrice);
      if (Number.isNaN(price)) return null;
      const storeName = state.cheapSharkStores.get(String(d.storeID)) || `Store #${d.storeID}`;
      // Preserve existing % encoding inside CheapShark dealID without double-encoding.
      const safeDealId = String(d.dealID || "").includes("%")
        ? d.dealID
        : encodeURIComponent(d.dealID || "");
      return {
        id: `cheapshark:${d.dealID}`,
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
}

async function searchItunes(query, shipCountry) {
  const url = `https://itunes.apple.com/search?term=${encodeURIComponent(query)}&country=${encodeURIComponent(shipCountry)}&limit=10`;
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
        marketplace: `Apple Store (${item.wrapperType || item.kind || "Digital"})`,
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
        marketplace: "Apple TV / iTunes Rental",
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
        note: "Digital rental option"
      });
    }
  }
  return hits;
}

async function searchPokemonTcg(query) {
  const clean = query.replace(/"/g, "").trim();
  const qParam = clean.includes(" ") ? `name:"${clean}"` : `name:${clean}*`;
  let res = await fetchWithTimeout(
    `https://api.pokemontcg.io/v2/cards?q=${encodeURIComponent(qParam)}&pageSize=8`
  );
  if (!res.ok) throw new Error(`Pokémon TCG HTTP ${res.status}`);
  let json = await res.json();
  let cards = Array.isArray(json?.data) ? json.data : [];

  if (cards.length === 0 && clean.includes(" ")) {
    const fallbackQ = clean
      .split(/\s+/)
      .filter((w) => w.length > 1)
      .map((w) => `name:${w}*`)
      .join(" ");
    if (fallbackQ) {
      res = await fetchWithTimeout(
        `https://api.pokemontcg.io/v2/cards?q=${encodeURIComponent(fallbackQ)}&pageSize=8`
      );
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
        sourceKey: "Pokémon TCG (Cardmarket / TCGplayer)",
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
        note: "Market index low; seller postage calculated at checkout"
      });
    }

    const tcgPrices = card.tcgplayer?.prices;
    const firstVariant = tcgPrices ? Object.values(tcgPrices)[0] : null;
    const tcgLow = firstVariant?.low ?? firstVariant?.market ?? firstVariant?.mid;
    if (typeof tcgLow === "number" && tcgLow > 0) {
      hits.push({
        id: `pkm:tcg:${card.id}`,
        marketplace: "TCGplayer (Pokémon TCG)",
        sourceKey: "Pokémon TCG (Cardmarket / TCGplayer)",
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
        note: "Market index low; shipping depends on seller"
      });
    }
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
        sourceKey: "Scryfall MTG Pricing",
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
        note: "Cardmarket index price; seller shipping varies"
      });
    }

    const usd = Number.parseFloat(card.prices?.usd);
    if (!Number.isNaN(usd) && usd > 0) {
      hits.push({
        id: `mtg:tcg:${card.id}`,
        marketplace: "TCGplayer (MTG)",
        sourceKey: "Scryfall MTG Pricing",
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
        note: "TCGplayer market price; seller shipping varies"
      });
    }
  }
  return hits;
}

async function searchGoogleBooks(query) {
  const url = `https://www.googleapis.com/books/v1/volumes?q=${encodeURIComponent(query)}&maxResults=10`;
  const res = await fetchWithTimeout(url);
  if (!res.ok) throw new Error(`Google Books HTTP ${res.status}`);
  const data = await res.json();
  const items = Array.isArray(data?.items) ? data.items : [];
  const hits = [];

  for (const vol of items) {
    const info = vol.volumeInfo || {};
    const sale = vol.saleInfo || {};
    const priceObj = sale.retailPrice || sale.listPrice;
    if (!priceObj || typeof priceObj.amount !== "number") continue;

    const isEbook = Boolean(sale.isEbook);
    const authors = Array.isArray(info.authors) ? ` — ${info.authors.slice(0, 2).join(", ")}` : "";
    hits.push({
      id: `gbooks:${vol.id}`,
      marketplace: isEbook ? "Google Play Books (eBook)" : "Google Books Store",
      sourceKey: "Google Books",
      title: `${info.title || "Untitled"}${authors}`,
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
      note: isEbook ? "Instant digital edition" : "Print retail listing"
    });
  }
  return hits;
}

async function searchReverb(query) {
  const url = `https://api.reverb.com/api/listings?query=${encodeURIComponent(query)}&per_page=10`;
  const res = await fetchWithTimeout(url, {
    headers: {
      "Accept": "application/hal+json",
      "Accept-Version": "3.0"
    }
  });
  if (res.status === 401 || res.status === 403) {
    const err = new Error(`Reverb API auth required (HTTP ${res.status})`);
    err.ledgerStatus = "REQUIRES_AUTH";
    throw err;
  }
  if (!res.ok) throw new Error(`Reverb HTTP ${res.status}`);
  const data = await res.json();
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
        note: item.condition?.display_name || "Music gear marketplace"
      };
    })
    .filter(Boolean);
}

async function searchDiscogs(query, token) {
  if (!token) {
    const err = new Error("Optional Discogs personal token not entered.");
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
      marketplace: "Discogs Marketplace",
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
      note: `${stats.num_for_sale || 1} for sale; shipping added by seller`
    });
  }
  return hits;
}

function deduplicateListings(items) {
  const seen = new Set();
  const unique = [];
  for (const item of items) {
    const normTitle = item.title.toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 48);
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
  const hideTraps = el.hideTrapsBtn.getAttribute("aria-pressed") === "true";
  const verifiedOnly = el.verifiedShippingOnlyBtn.getAttribute("aria-pressed") === "true";

  const enriched = rawListings
    .map((item) => {
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

      const trapReason = detectAccessoryTrap(item.title, state.lastQuery);
      const match = computeMatchScore(item.title, state.lastQuery);

      return {
        ...item,
        targetCurrency,
        baseTarget,
        shippingTarget,
        vatTarget: taxes.vat,
        dutyTarget: taxes.duty,
        taxNote: taxes.note,
        knownTotalTarget,
        trapReason,
        match
      };
    })
    .filter((item) => {
      if (item.knownTotalTarget == null) return false;
      if (condFilter !== "ALL" && item.condition !== condFilter) return false;
      if (acqFilter !== "ALL" && item.acquisition !== acqFilter) return false;
      if (hideTraps && item.trapReason) return false;
      if (verifiedOnly && !item.shippingVerified) return false;
      return true;
    });

  // Sort strictly lowest total cost first within verified vs unverified shipping groups
  enriched.sort((a, b) => {
    if (a.shippingVerified !== b.shippingVerified) {
      return a.shippingVerified ? -1 : 1;
    }
    return a.knownTotalTarget - b.knownTotalTarget;
  });

  return enriched;
}

function renderSourceLedger() {
  const searchedCount = state.sourceLedgers.filter((s) => s.status !== "PENDING").length;
  el.sourcesSearchedMetric.textContent = `${searchedCount} / ${state.sourceLedgers.length}`;

  const okCount = state.sourceLedgers.filter((s) => s.status === "SEARCHED").length;
  el.sourcesProgressSub.textContent = state.isSearching
    ? "Querying connectors concurrently…"
    : `${okCount} live connectors returned offers`;

  el.sourceStatusList.innerHTML = state.sourceLedgers
    .map((s) => {
      let badgeClass = "badge--slate";
      if (s.status === "SEARCHED") badgeClass = "badge--emerald";
      else if (s.status === "NO_RESULTS") badgeClass = "badge--cyan";
      else if (s.status === "REQUIRES_AUTH") badgeClass = "badge--amber";
      else if (s.status === "UNAVAILABLE") badgeClass = "badge--rose";

      return `
        <div class="source-row">
          <div class="source-row-top">
            <span class="source-name">${escapeHtml(s.name)}</span>
            <span class="badge ${badgeClass}">${escapeHtml(s.status)}</span>
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
    badges.push(`<span class="badge badge--emerald">Lowest Verified Total</span>`);
  }
  badges.push(`<span class="badge badge--cyan">${escapeHtml(item.condition)}</span>`);
  badges.push(`<span class="badge badge--slate">${escapeHtml(item.acquisition.replace("_", " "))}</span>`);
  badges.push(`<span class="badge badge--slate">${escapeHtml(item.match.label)}</span>`);
  if (!item.shippingVerified) {
    badges.push(`<span class="badge badge--amber">Shipping Unverified</span>`);
  }
  if (item.trapReason) {
    badges.push(`<span class="badge badge--rose">${escapeHtml(item.trapReason)}</span>`);
  }

  const breakdownParts = [
    `Base ${formatMoney(item.baseTarget, targetCurrency)}`
  ];
  if (item.currency !== targetCurrency) {
    breakdownParts.push(`(${formatMoney(item.basePrice, item.currency)} orig.)`);
  }
  if (item.shippingVerified) {
    breakdownParts.push(
      item.shippingTarget > 0
        ? `+ ${formatMoney(item.shippingTarget, targetCurrency)} ship`
        : "+ Free / Digital"
    );
  } else {
    breakdownParts.push("+ Shipping TBD");
  }
  if (item.vatTarget + item.dutyTarget > 0) {
    breakdownParts.push(`+ ${formatMoney(item.vatTarget + item.dutyTarget, targetCurrency)} est. VAT/duty`);
  }

  return `
    <article class="listing-card ${isCheapestVerified ? "listing-card--cheapest" : ""}">
      ${thumbHtml}
      <div>
        <h3 class="listing-title">#${index + 1} &middot; ${escapeHtml(item.title)}</h3>
        <div class="badges-row">${badges.join("")}</div>
        <div class="listing-meta">
          <strong>${escapeHtml(item.marketplace)}</strong>
          ${item.sellerCountry ? ` &bull; Seller: ${escapeHtml(item.sellerCountry)}` : ""}
          ${item.taxNote ? ` &bull; ${escapeHtml(item.taxNote)}` : ""}
          ${item.note ? ` &bull; ${escapeHtml(item.note)}` : ""}
        </div>
      </div>
      <div class="listing-price-col">
        <div class="price-total">${formatMoney(item.knownTotalTarget, targetCurrency)}</div>
        <div class="price-breakdown">${escapeHtml(breakdownParts.join(" "))}</div>
        <a class="btn btn-secondary" style="min-height: 40px; padding: 0 14px; font-size: 0.84rem;" href="${escapeHtml(item.url)}" target="_blank" rel="noopener noreferrer">
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
  el.dedupSub.textContent = `${state.rawListings.length} total deduplicated raw offers`;
  el.resultsCountLabel.textContent = `${filtered.length} matching offers (${verified.length} verified total)`;
  el.exportCsvBtn.disabled = filtered.length === 0;

  if (verified.length > 0) {
    el.lowestVerifiedMetric.textContent = formatMoney(verified[0].knownTotalTarget, targetCurrency);
    el.lowestVerifiedSub.textContent = `${verified[0].marketplace} • ${verified[0].condition}`;
  } else if (unverified.length > 0) {
    el.lowestVerifiedMetric.textContent = `${formatMoney(unverified[0].knownTotalTarget, targetCurrency)}+`;
    el.lowestVerifiedSub.textContent = "Base only (shipping unverified)";
  } else {
    el.lowestVerifiedMetric.textContent = "—";
    el.lowestVerifiedSub.textContent = state.lastQuery ? "No live offers matched filters" : "Run a live search";
  }

  if (!state.lastQuery) return;

  if (filtered.length === 0) {
    el.resultsContainer.innerHTML = `
      <div class="empty-state">
        <p><strong>No matching live offers found for "${escapeHtml(state.lastQuery)}".</strong></p>
        <p>Try broadening filters, checking the Source Transparency Ledger on the right, or searching via the native Android app for regional classifieds &amp; eBay coverage.</p>
      </div>
    `;
    return;
  }

  let html = "";
  if (verified.length > 0) {
    html += verified.map((item, idx) => renderCard(item, idx, targetCurrency, idx === 0)).join("");
  }
  if (unverified.length > 0) {
    html += `<div class="divider-title">Offers With Unverified Seller Shipping (Sorted by Base + Estimated Tax)</div>`;
    html += unverified
      .map((item, idx) => renderCard(item, verified.length + idx, targetCurrency, false))
      .join("");
  }
  el.resultsContainer.innerHTML = html;
}

async function runLiveSearch(query) {
  const trimmed = query.trim();
  if (!trimmed || state.isSearching) return;

  state.isSearching = true;
  state.lastQuery = trimmed;
  state.rawListings = [];
  state.lastTimestamp = new Date();
  el.searchBtn.disabled = true;
  el.searchBtn.textContent = "Searching Live…";
  el.timestampMetric.textContent = state.lastTimestamp.toLocaleTimeString();
  el.queryEchoSub.textContent = `Query: "${trimmed}"`;

  const { country: shipCountry } = getShipConfig();
  const discogsToken = el.discogsTokenInput.value.trim();

  const liveConnectors = [
    { name: "CheapShark PC Deals", run: () => searchCheapShark(trimmed) },
    { name: "Apple iTunes Store", run: () => searchItunes(trimmed, shipCountry) },
    { name: "Pokémon TCG (Cardmarket / TCGplayer)", run: () => searchPokemonTcg(trimmed) },
    { name: "Scryfall MTG Pricing", run: () => searchScryfall(trimmed) },
    { name: "Google Books", run: () => searchGoogleBooks(trimmed) },
    { name: "Reverb", run: () => searchReverb(trimmed) },
    { name: "Discogs", run: () => searchDiscogs(trimmed, discogsToken) }
  ];

  state.sourceLedgers = [
    ...liveConnectors.map((c) => ({
      name: c.name,
      status: "PENDING",
      detail: "Executing live browser request…"
    })),
    ...NATIVE_ONLY_CATALOG.map((c) => ({ ...c }))
  ];
  renderSourceLedger();
  el.resultsContainer.innerHTML = `<div class="empty-state"><p>Searching ${liveConnectors.length} live browser connectors concurrently…</p></div>`;

  await Promise.allSettled(
    liveConnectors.map(async (connector, idx) => {
      try {
        const hits = await connector.run();
        if (hits.length > 0) {
          state.rawListings = deduplicateListings([...state.rawListings, ...hits]);
          state.sourceLedgers[idx] = {
            name: connector.name,
            status: "SEARCHED",
            detail: `Returned ${hits.length} live offer${hits.length === 1 ? "" : "s"}.`
          };
        } else {
          state.sourceLedgers[idx] = {
            name: connector.name,
            status: "NO_RESULTS",
            detail: "Live API responded with 0 matching priced items."
          };
        }
      } catch (err) {
        const isCorsOrNetwork = err?.name === "TypeError" || /abort|timeout|network/i.test(err?.message || "");
        const status = err?.ledgerStatus || "UNAVAILABLE";
        const detail = isCorsOrNetwork
          ? "Blocked by browser CORS or request timeout; check native Android app."
          : (err?.message || "Request failed.");
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
  el.searchBtn.disabled = false;
  el.searchBtn.textContent = "Search Live Prices";
  renderSourceLedger();
  renderResults();
}

function exportResultsCsv() {
  const rows = enrichAndFilterListings(state.rawListings);
  if (rows.length === 0) return;

  const headers = [
    "Rank", "Title", "Marketplace", "Condition", "Acquisition",
    "TargetCurrency", "KnownTotal", "BaseConverted", "ShippingVerified",
    "EstVatAndDuty", "OrigAmount", "OrigCurrency", "TrapWarning", "URL", "RetrievedAt"
  ];
  const csvEscape = (v) => `"${String(v ?? "").replace(/"/g, '""')}"`;
  const lines = [
    headers.join(","),
    ...rows.map((r, i) => [
      i + 1,
      r.title,
      r.marketplace,
      r.condition,
      r.acquisition,
      r.targetCurrency,
      r.knownTotalTarget.toFixed(2),
      r.baseTarget.toFixed(2),
      r.shippingVerified ? "Verified" : "Unverified",
      (r.vatTarget + r.dutyTarget).toFixed(2),
      r.basePrice,
      r.currency,
      r.trapReason || "",
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

[el.countrySelect, el.conditionFilter, el.acquisitionFilter].forEach((ctrl) => {
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

// Initial live FX fetch on load
loadLiveFxRates();

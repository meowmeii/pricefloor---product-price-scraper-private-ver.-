const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot list, get, or write watchlist, saved_searches, or price_history", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("watchlist").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("saved_searches").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("price_history").get());

  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("watchlist").doc("item_1").set({
      userId: ALICE_UID,
      title: "iPhone 15 Pro 256GB",
      marketplace: "CeX IE",
      sourceUrl: "https://ie.webuy.com/product-detail?id=123",
      condition: "REFURBISHED",
      acquisitionType: "PURCHASE",
      currency: "EUR",
      initialTotalPrice: 750.0,
      latestTotalPrice: 750.0,
      advertisedPrice: 746.5,
      priceDropped: false,
      lastCheckedAt: past,
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("Authenticated owner: valid Kotlin watchlist create, partial update, and delete succeed; shadow fields and @DocumentId 'id' fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const past = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("watchlist").doc("watch_1").set({
      userId: ALICE_UID,
      title: "NVIDIA RTX 4070 12GB",
      marketplace: "Adverts.ie",
      sourceUrl: "https://www.adverts.ie/123",
      imageUrl: "https://example.com/gpu.jpg",
      condition: "USED",
      acquisitionType: "PURCHASE",
      currency: "EUR",
      initialTotalPrice: 460.0,
      latestTotalPrice: 460.0,
      advertisedPrice: 450.0,
      shippingCost: 10.0,
      priceDropped: false,
      lastCheckedAt: past,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Empty list query succeeds on user-scoped subcollection
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("watchlist").get()
  );

  // Partial update on price drop succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("watchlist").doc("watch_1").update({
      latestTotalPrice: 420.0,
      advertisedPrice: 410.0,
      priceDropped: true,
      lastCheckedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject modifying immutable initialTotalPrice
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("watchlist").doc("watch_1").update({
      initialTotalPrice: 999.0,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost/shadow field or @DocumentId 'id'
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("watchlist").doc("watch_2").set({
      id: "watch_2",
      userId: ALICE_UID,
      title: "Ghost ID",
      marketplace: "Reverb",
      sourceUrl: "https://reverb.com/item/1",
      condition: "NEW",
      acquisitionType: "PURCHASE",
      currency: "EUR",
      initialTotalPrice: 100.0,
      latestTotalPrice: 100.0,
      advertisedPrice: 100.0,
      priceDropped: false,
      lastCheckedAt: past,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("watchlist").doc("watch_3").set({
      userId: ALICE_UID,
      title: "Future TS",
      marketplace: "Reverb",
      sourceUrl: "https://reverb.com/item/1",
      condition: "NEW",
      acquisitionType: "PURCHASE",
      currency: "EUR",
      initialTotalPrice: 100.0,
      latestTotalPrice: 100.0,
      advertisedPrice: 100.0,
      priceDropped: false,
      lastCheckedAt: new Date(Date.now() + 60000),
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("watchlist").doc("watch_1").delete()
  );
});

test("Authenticated owner: saved_searches & price_history CRUD and cross-user isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("saved_searches").doc("search_1").set({
      userId: ALICE_UID,
      query: "RTX 4070",
      countryCode: "IE",
      postcode: "D02 X285",
      currency: "EUR",
      targetMaxPrice: 450.0,
      includeRentals: true,
      includeAuctions: true,
      alertEnabled: true,
      lastLowestTotal: 460.0,
      lastVerifiedCount: 5,
      lastRunAt: past,
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("saved_searches").doc("search_1").update({
      alertEnabled: false,
      lastLowestTotal: 425.0,
      lastVerifiedCount: 8,
      lastRunAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("price_history").doc("hist_1").set({
      userId: ALICE_UID,
      normalizedQuery: "rtx 4070",
      productTitle: "ASUS Dual RTX 4070 12GB",
      marketplace: "CeX IE",
      condition: "USED",
      currency: "EUR",
      lowestVerifiedTotal: 425.0,
      medianPrice: 495.0,
      sourceUrl: "https://ie.webuy.com/product-detail?id=1",
      recordedAt: past,
      createdAt: past,
    })
  );

  // Price history is immutable (update denied)
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("price_history").doc("hist_1").update({
      lowestVerifiedTotal: 100.0,
    })
  );

  // Bob cannot access Alice's saved searches or price history
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("saved_searches").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("saved_searches").doc("search_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("price_history").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("watchlist").get());
});

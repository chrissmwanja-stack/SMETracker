// Emulator tests for the sales section of ../firestore.rules.
// Same run command as storage.rules.test.mjs (runs both files):
//
//   firebase emulators:exec --only firestore,storage --project demo-smetracker-rules "npm --prefix rules-tests test"
//
// Not run by the assistant that wrote it: treat your first local run as the
// real verification.

import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';

const PROJECT_ID = 'demo-smetracker-rules';
const BIZ = 'biz1';
const OTHER_BIZ = 'biz2';

const OWNER = '+256700000001';
const WORKER_A = '+256700000002';
const WORKER_B = '+256700000003';
const OUTSIDER = '+256700000009'; // owner of another business

const SALE_ID = 'sale1'; // recorded by WORKER_A
const NEW_SALE_ID = 'saleNew';

// Mirrors what SaleSync.pushPending writes (RemoteSale), including explicit nulls.
const saleData = (overrides = {}) => ({
  customerId: null,
  customerName: 'Walk-in',
  description: 'Sugar 1kg',
  amount: 5000,
  inventoryItemId: 'item1',
  quantity: 1,
  date: 1760000000000,
  paymentMethod: 'CASH',
  recordedBy: WORKER_A,
  finalReceiptNumber: 'INV-0001',
  isDeleted: false,
  ...overrides,
});

let env;

const db = (phone) =>
  env.authenticatedContext(`uid-${phone}`, { phone_number: phone }).firestore();

const saleRef = (phone, id = SALE_ID) => db(phone).doc(`businesses/${BIZ}/sales/${id}`);

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync('../firestore.rules', 'utf8') },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    await admin.doc(`phoneIndex/${OWNER}`).set({ businessId: BIZ, role: 'OWNER' });
    await admin.doc(`phoneIndex/${WORKER_A}`).set({ businessId: BIZ, role: 'WORKER' });
    await admin.doc(`phoneIndex/${WORKER_B}`).set({ businessId: BIZ, role: 'WORKER' });
    await admin.doc(`phoneIndex/${OUTSIDER}`).set({ businessId: OTHER_BIZ, role: 'OWNER' });
    await admin.doc(`businesses/${BIZ}/sales/${SALE_ID}`).set(saleData());
  });
});

describe('sales: create and read', () => {
  it('worker creates a sale recorded under their own phone -> allow', async () => {
    await assertSucceeds(
      saleRef(WORKER_A, NEW_SALE_ID).set(saleData({ finalReceiptNumber: 'INV-0002' })),
    );
  });

  it("worker creates a sale recorded under someone else's phone -> deny", async () => {
    await assertFails(
      saleRef(WORKER_A, NEW_SALE_ID).set(saleData({ recordedBy: WORKER_B })),
    );
  });

  it('any member reads a sale in their business -> allow', async () => {
    await assertSucceeds(saleRef(WORKER_B).get());
    await assertSucceeds(saleRef(OWNER).get());
  });

  it('member of another business cannot read -> deny', async () => {
    await assertFails(saleRef(OUTSIDER).get());
  });
});

describe('sales: worker updates on their own sale', () => {
  it('re-pushing the identical doc (retry after a partial push) -> allow', async () => {
    await assertSucceeds(saleRef(WORKER_A).set(saleData()));
  });

  it('changing the amount -> deny', async () => {
    await assertFails(saleRef(WORKER_A).set(saleData({ amount: 1 })));
  });

  it('changing the quantity -> deny', async () => {
    await assertFails(saleRef(WORKER_A).set(saleData({ quantity: 10 })));
  });

  it('changing the payment method -> deny', async () => {
    await assertFails(saleRef(WORKER_A).set(saleData({ paymentMethod: 'CREDIT' })));
  });

  it('changing the linked item -> deny', async () => {
    await assertFails(saleRef(WORKER_A).set(saleData({ inventoryItemId: 'item2' })));
  });

  it('soft-deleting it (isDeleted = true) -> deny', async () => {
    await assertFails(saleRef(WORKER_A).set(saleData({ isDeleted: true })));
  });

  it('changing recordedBy -> deny', async () => {
    await assertFails(saleRef(WORKER_A).set(saleData({ recordedBy: WORKER_B })));
  });

  it('hard-deleting it -> deny', async () => {
    await assertFails(saleRef(WORKER_A).delete());
  });
});

describe("sales: worker updates on someone else's sale", () => {
  it('even an identical re-push -> deny', async () => {
    await assertFails(saleRef(WORKER_B).set(saleData()));
  });

  it('soft-deleting it -> deny', async () => {
    await assertFails(saleRef(WORKER_B).set(saleData({ isDeleted: true })));
  });

  it('hard-deleting it -> deny', async () => {
    await assertFails(saleRef(WORKER_B).delete());
  });
});

describe('sales: owner', () => {
  it("edits a worker's sale -> allow", async () => {
    await assertSucceeds(saleRef(OWNER).set(saleData({ amount: 6000 })));
  });

  it("soft-deletes a worker's sale -> allow", async () => {
    await assertSucceeds(saleRef(OWNER).set(saleData({ isDeleted: true })));
  });

  it("hard-deletes a worker's sale -> allow", async () => {
    await assertSucceeds(saleRef(OWNER).delete());
  });

  it("owner of another business cannot edit or delete -> deny", async () => {
    await assertFails(
      db(OUTSIDER).doc(`businesses/${BIZ}/sales/${SALE_ID}`).set(saleData({ amount: 1 })),
    );
    await assertFails(db(OUTSIDER).doc(`businesses/${BIZ}/sales/${SALE_ID}`).delete());
  });
});

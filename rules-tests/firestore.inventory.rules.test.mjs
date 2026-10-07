// Emulator tests for the inventory section of ../firestore.rules.
// Same run command as the other files in this folder (runs all of them):
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
const OWNER = '+256700000001';
const WORKER = '+256700000002';
const ITEM_ID = 'item1';

// Mirrors what InventorySync.pushPending writes (RemoteInventoryItem).
const itemData = (overrides = {}) => ({
  name: 'Salt 1kg',
  category: 'Groceries',
  quantity: 10,
  reorderLevel: 5,
  sellingPrice: 5000,
  updatedAt: 1760000000000,
  recordedBy: OWNER,
  imageUrl: '',
  sku: '',
  isDeleted: false,
  ...overrides,
});

let env;

const db = (phone) =>
  env.authenticatedContext(`uid-${phone}`, { phone_number: phone }).firestore();

const itemRef = (phone) => db(phone).doc(`businesses/${BIZ}/inventory/${ITEM_ID}`);

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
    await admin.doc(`phoneIndex/${WORKER}`).set({ businessId: BIZ, role: 'WORKER' });
    await admin.doc(`businesses/${BIZ}/inventory/${ITEM_ID}`).set(itemData());
  });
});

describe('inventory: worker updates', () => {
  it('sale decrement (lower quantity + newer updatedAt) -> allow', async () => {
    await assertSucceeds(itemRef(WORKER).set(itemData({ quantity: 3, updatedAt: 1760000099999 })));
  });

  it('restock (higher quantity) -> allow', async () => {
    await assertSucceeds(itemRef(WORKER).set(itemData({ quantity: 15 })));
  });

  it('identical re-push -> allow', async () => {
    await assertSucceeds(itemRef(WORKER).set(itemData()));
  });

  it('changing name / category / sku / reorderLevel / imageUrl -> allow', async () => {
    await assertSucceeds(
      itemRef(WORKER).set(
        itemData({ name: 'Salt 2kg', category: 'Bulk', sku: '123', reorderLevel: 2, imageUrl: 'https://x/y.jpg' }),
      ),
    );
  });

  it('changing sellingPrice -> deny', async () => {
    await assertFails(itemRef(WORKER).set(itemData({ sellingPrice: 1 })));
  });

  it('soft-deleting (isDeleted = true) -> deny', async () => {
    await assertFails(itemRef(WORKER).set(itemData({ isDeleted: true })));
  });

  it('changing recordedBy -> deny', async () => {
    await assertFails(itemRef(WORKER).set(itemData({ recordedBy: WORKER })));
  });

  it('hard delete -> deny', async () => {
    await assertFails(itemRef(WORKER).delete());
  });
});

describe('inventory: owner', () => {
  it('owner can change price, soft-delete and lower quantity -> allow', async () => {
    await assertSucceeds(
      itemRef(OWNER).set(itemData({ sellingPrice: 6000, isDeleted: true, quantity: 1 })),
    );
  });
});

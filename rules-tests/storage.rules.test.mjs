// Emulator tests for ../storage.rules, including its cross-service lookups
// into Firestore (phoneIndex + expenses). Run from the repo root:
//
//   firebase emulators:exec --only firestore,storage --project demo-smetracker-rules "npm --prefix rules-tests test"
//
// Not run by the assistant that wrote it (no Firebase emulators in its sandbox):
// treat the first local run as the real verification.

import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';

const PROJECT_ID = 'demo-smetracker-rules';
const BUCKET = `gs://${PROJECT_ID}.appspot.com`;
const BIZ = 'biz1';
const OTHER_BIZ = 'biz2';

const OWNER = '+256700000001';
const WORKER_A = '+256700000002';
const WORKER_B = '+256700000003';
const OUTSIDER = '+256700000009'; // member of another business

const EXISTING_EXPENSE_A = 'expA'; // recorded by WORKER_A
const NEW_EXPENSE = 'expNew'; // no Firestore doc yet (first-upload case)

const JPEG = { contentType: 'image/jpeg' };
const bytes = (n = 16) => new Uint8Array(n).fill(1);

let env;

const asUser = (phone) =>
  env.authenticatedContext(`uid-${phone}`, { phone_number: phone }).storage(BUCKET);

const receiptPath = (id, biz = BIZ) => `businesses/${biz}/expense_receipts/${id}.jpg`;
const inventoryPath = (id, biz = BIZ) => `businesses/${biz}/inventory/${id}.jpg`;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync('../firestore.rules', 'utf8') },
    storage: { rules: readFileSync('../storage.rules', 'utf8') },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();

  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.doc(`phoneIndex/${OWNER}`).set({ businessId: BIZ, role: 'OWNER' });
    await db.doc(`phoneIndex/${WORKER_A}`).set({ businessId: BIZ, role: 'WORKER' });
    await db.doc(`phoneIndex/${WORKER_B}`).set({ businessId: BIZ, role: 'WORKER' });
    await db.doc(`phoneIndex/${OUTSIDER}`).set({ businessId: OTHER_BIZ, role: 'OWNER' });
    await db
      .doc(`businesses/${BIZ}/expenses/${EXISTING_EXPENSE_A}`)
      .set({ recordedBy: WORKER_A });

    const storage = ctx.storage(BUCKET);
    await storage.ref(receiptPath(EXISTING_EXPENSE_A)).put(bytes(), JPEG);
    await storage.ref(inventoryPath('item1')).put(bytes(), JPEG);
  });
});

describe('expense receipts: writes', () => {
  // Matrix rows from the session notes (section 6).
  it('worker creates a receipt whose expense doc does not exist yet -> allow', async () => {
    await assertSucceeds(asUser(WORKER_A).ref(receiptPath(NEW_EXPENSE)).put(bytes(), JPEG));
  });

  it('worker updates a receipt whose expense they recorded -> allow', async () => {
    await assertSucceeds(
      asUser(WORKER_A).ref(receiptPath(EXISTING_EXPENSE_A)).put(bytes(32), JPEG),
    );
  });

  it('worker updates a receipt whose expense another worker recorded -> deny', async () => {
    await assertFails(
      asUser(WORKER_B).ref(receiptPath(EXISTING_EXPENSE_A)).put(bytes(32), JPEG),
    );
  });

  it('owner updates any receipt -> allow', async () => {
    await assertSucceeds(
      asUser(OWNER).ref(receiptPath(EXISTING_EXPENSE_A)).put(bytes(32), JPEG),
    );
  });

  // Extra cases around the same rule.
  it('member of another business cannot write into this business -> deny', async () => {
    await assertFails(asUser(OUTSIDER).ref(receiptPath(NEW_EXPENSE)).put(bytes(), JPEG));
  });

  it('unauthenticated user cannot write -> deny', async () => {
    const anon = env.unauthenticatedContext().storage(BUCKET);
    await assertFails(anon.ref(receiptPath(NEW_EXPENSE)).put(bytes(), JPEG));
  });

  it('non-image content type is rejected even for the recorder -> deny', async () => {
    await assertFails(
      asUser(WORKER_A)
        .ref(receiptPath(EXISTING_EXPENSE_A))
        .put(bytes(), { contentType: 'text/plain' }),
    );
  });

  it('file of 5 MiB or more is rejected -> deny', async () => {
    await assertFails(
      asUser(OWNER).ref(receiptPath(NEW_EXPENSE)).put(bytes(5 * 1024 * 1024), JPEG),
    );
  });
});

describe('expense receipts: reads and deletes', () => {
  it('owner reads any receipt -> allow', async () => {
    await assertSucceeds(asUser(OWNER).ref(receiptPath(EXISTING_EXPENSE_A)).getMetadata());
  });

  it('worker reads the receipt of their own expense -> allow', async () => {
    await assertSucceeds(asUser(WORKER_A).ref(receiptPath(EXISTING_EXPENSE_A)).getMetadata());
  });

  it("worker reads another worker's receipt -> deny", async () => {
    await assertFails(asUser(WORKER_B).ref(receiptPath(EXISTING_EXPENSE_A)).getMetadata());
  });

  it('worker deletes a receipt, even their own -> deny', async () => {
    await assertFails(asUser(WORKER_A).ref(receiptPath(EXISTING_EXPENSE_A)).delete());
  });

  it('owner deletes a receipt -> allow', async () => {
    await assertSucceeds(asUser(OWNER).ref(receiptPath(EXISTING_EXPENSE_A)).delete());
  });
});

describe('inventory photos', () => {
  it('worker adds a photo for a new item -> allow', async () => {
    await assertSucceeds(asUser(WORKER_A).ref(inventoryPath('item2')).put(bytes(), JPEG));
  });

  it('worker replaces an existing photo -> allow', async () => {
    await assertSucceeds(asUser(WORKER_A).ref(inventoryPath('item1')).put(bytes(32), JPEG));
  });

  it('worker reads a photo -> allow', async () => {
    await assertSucceeds(asUser(WORKER_A).ref(inventoryPath('item1')).getMetadata());
  });

  it('worker deletes a photo -> deny', async () => {
    await assertFails(asUser(WORKER_A).ref(inventoryPath('item1')).delete());
  });

  it('owner deletes a photo -> allow', async () => {
    await assertSucceeds(asUser(OWNER).ref(inventoryPath('item1')).delete());
  });

  it('member of another business cannot read or write -> deny', async () => {
    await assertFails(asUser(OUTSIDER).ref(inventoryPath('item1')).getMetadata());
    await assertFails(asUser(OUTSIDER).ref(inventoryPath('item1')).put(bytes(), JPEG));
  });
});
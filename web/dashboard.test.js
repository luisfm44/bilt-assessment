import test from "node:test";
import assert from "node:assert/strict";

import { buildViewModel, renderDashboard } from "./dashboard.js";

const member = {
  pointsThisMonth: 1_500,
  monthlyCap: 100_000,
  streakMonths: 6,
};

test("shows awarded points as a successful payment", () => {
  const view = buildViewModel(
    { pointsAwarded: 1_500, outcome: "AWARDED" },
    member,
  );

  assert.equal(view.title, "1,500 points credited");
  assert.equal(view.tone, "success");
  assert.equal(view.progressPercent, 1.5);
  assert.equal(view.description, "Your rent payment was processed successfully.");
});

test("shows a duplicate event as skipped rather than credited", () => {
  const view = buildViewModel(
    { pointsAwarded: 0, outcome: "DUPLICATE" },
    member,
  );

  assert.equal(view.title, "Duplicate event skipped");
  assert.equal(view.tone, "neutral");
  assert.match(view.description, /already processed/);
  assert.match(view.description, /No additional points were credited/);
});

test("shows when the member has reached the monthly cap", () => {
  const view = buildViewModel(
    { pointsAwarded: 0, outcome: "CAPPED" },
    { ...member, pointsThisMonth: 100_000 },
  );

  assert.equal(view.title, "Monthly cap reached");
  assert.equal(view.tone, "warning");
  assert.equal(view.progressPercent, 100);
  assert.match(view.description, /monthly points cap/);
  assert.match(view.description, /No additional points were credited/);
});

test("a partial award reaching the cap still displays success", () => {
  const view = buildViewModel(
    { pointsAwarded: 500, outcome: "AWARDED" },
    { ...member, pointsThisMonth: 100_000 },
  );

  assert.equal(view.title, "500 points credited");
  assert.equal(view.tone, "success");
  assert.equal(view.progressPercent, 100);
});

test("unknown outcomes cannot announce a successful award", () => {
  assert.throws(
    () => buildViewModel({ pointsAwarded: 1500, outcome: "UNKNOWN" }, member),
    /Unknown processing outcome/,
  );
});

test("rendering successive outcomes updates the visible copy and color", () => {
  const selectors = [
    "[data-status]", "[data-status-title]", "[data-status-description]",
    "[data-points]", "[data-streak]", "[data-progress]", "[data-progress-label]",
  ];
  const nodes = Object.fromEntries(selectors.map(selector => [selector, { style: {} }]));
  const originalDocument = globalThis.document;
  globalThis.document = { querySelector: selector => nodes[selector] };

  try {
    for (const [outcome, color, title, pointsAwarded, pointsThisMonth] of [
      ["AWARDED", "emerald", "1,500 points credited", 1500, 1500],
      ["DUPLICATE", "slate", "Duplicate event skipped", 0, 1500],
      ["CAPPED", "amber", "Monthly cap reached", 0, 100_000],
    ]) {
      const result = { outcome, pointsAwarded };
      const account = { ...member, pointsThisMonth };
      const view = buildViewModel(result, account);
      renderDashboard(result, account);

      assert.equal(nodes["[data-status-title]"].textContent, title);
      assert.equal(nodes["[data-status-description]"].textContent, view.description);
      assert.equal(nodes["[data-status]"].className,
        `rounded-2xl border p-5 border-${color}-200 bg-${color}-50 text-${color}-${color === "slate" ? 700 : 800}`);
      assert.equal(nodes["[data-progress]"].style.width, `${view.progressPercent}%`);
      assert.equal(nodes["[data-points]"].textContent, pointsThisMonth === 1500 ? "1,500" : "100,000");
      assert.equal(nodes["[data-streak]"].textContent, "6 month streak");
      assert.equal(nodes["[data-progress-label]"].textContent,
        `${Math.round(view.progressPercent)}% of monthly cap`);
    }
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

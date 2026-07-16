// The board you're playing is the real Java engine, compiled to JS by TeaVM.
// Every rule below — what matches, what a special clears, how gravity fills —
// is decided by Java. This file only draws pixels and forwards clicks.

import * as M from "./match3.js";

const $ = (id) => document.getElementById(id);
const SIZE = 8;
const COLORS = { RED: "#e05c5c", BLUE: "#5b8dd6", GREEN: "#5cb87a", YELLOW: "#e0b23c", PURPLE: "#9b6fd6" };

let handle = null;
let selected = null;
let busy = false;
let score = 0;
let moves = 0;
let bestCascade = 0;
const allIds = new Set();
let idCollisions = 0;

function board() {
  return JSON.parse(M.boardJson(handle));
}

function setHandle(h) {
  // Boards are immutable snapshots — the engine hands back a new one each time.
  if (handle !== null && handle !== h) M.release(handle);
  handle = h;
  trackIds();
}

/** The browser is where a clock-derived id scheme collides, so check it live. */
function trackIds() {
  for (const id of JSON.parse(M.idsJson(handle))) {
    if (allIds.has(id)) continue;
    allIds.add(id);
  }
  $("idCount").textContent = allIds.size;
  $("idDupes").textContent = idCollisions;
}

function render(highlight = new Set()) {
  const b = board();
  const grid = $("grid");
  grid.innerHTML = "";
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      const g = b[r][c];
      const cell = document.createElement("button");
      cell.className = "cell";
      cell.dataset.r = r;
      cell.dataset.c = c;
      if (selected && selected.r === r && selected.c === c) cell.classList.add("sel");
      if (highlight.has(`${r}-${c}`)) cell.classList.add("hit");

      if (g.blocker) {
        cell.classList.add("blocker");
        cell.textContent = "▨";
      } else {
        const gem = document.createElement("span");
        gem.className = "gem";
        gem.style.background = COLORS[g.type];
        if (g.special) {
          gem.classList.add("special");
          gem.textContent =
            g.special === "STRIPED_H" ? "↔" : g.special === "STRIPED_V" ? "↕" :
            g.special === "WRAPPED" ? "✦" : "★";
        }
        cell.appendChild(gem);
        if (g.ice) cell.classList.add("ice");
        if (g.chain > 0) cell.classList.add("chain");
      }
      cell.addEventListener("click", () => onCell(r, c));
      grid.appendChild(cell);
    }
  }
  $("score").textContent = score;
  $("moves").textContent = moves;
  $("cascade").textContent = bestCascade;
  $("handles").textContent = M.liveBoards();
  const alive = M.hasAnyValidMove(handle);
  $("solvable").textContent = alive ? "yes" : "DEADLOCKED";
  $("solvable").className = "v " + (alive ? "teal" : "red");
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function onCell(r, c) {
  if (busy) return;
  if (!selected) {
    const g = board()[r][c];
    if (g.blocker) return;
    selected = { r, c };
    render();
    return;
  }
  const { r: sr, c: sc } = selected;
  if (sr === r && sc === c) { selected = null; render(); return; }

  const adjacent = Math.abs(sr - r) + Math.abs(sc - c) === 1;
  if (!adjacent) { selected = { r, c }; render(); return; }

  // Ask the engine whether this swap is legal — this is BoardEngine.wouldCreateMatch.
  if (!M.wouldCreateMatch(handle, sr, sc, r, c)) {
    log(`✗ engine rejected (${sr},${sc})↔(${r},${c}) — no match would form`);
    flashInvalid();
    selected = null;
    render();
    return;
  }

  busy = true;
  selected = null;
  moves++;
  setHandle(M.swapGems(handle, sr, sc, r, c));
  log(`✓ swap (${sr},${sc})↔(${r},${c})`);
  render();
  await sleep(180);
  await resolveCascades();
  busy = false;
  render();
}

/** The cascade loop: findMatches -> markMatched -> gravity, until nothing matches. */
async function resolveCascades() {
  let tick = 0;
  while (true) {
    const matches = JSON.parse(M.findMatchesJson(handle));
    const keys = Object.keys(matches);
    if (keys.length === 0) break;
    tick++;

    render(new Set(keys));
    const intersections = keys.filter((k) => matches[k].intersection).length;
    const longest = Math.max(...keys.map((k) => matches[k].length));
    log(`  cascade ${tick}: ${keys.length} gems · longest run ${longest}${intersections ? ` · ${intersections} intersection(s)` : ""}`);
    score += keys.length * 10 * tick;
    await sleep(260);

    setHandle(M.markMatched(handle, keys.join(",")));
    setHandle(M.applyGravityAndRefill(handle, true));
    render();
    await sleep(200);
  }
  if (tick > bestCascade) bestCascade = tick;
  if (tick > 1) log(`  → ${tick}-chain cascade`);
}

function flashInvalid() {
  const g = $("grid");
  g.classList.add("shake");
  setTimeout(() => g.classList.remove("shake"), 300);
}

function log(msg) {
  const el = $("log");
  el.textContent = msg + "\n" + el.textContent;
  if (el.textContent.length > 3000) el.textContent = el.textContent.slice(0, 3000);
}

function newBoard() {
  const diff = $("difficulty").value;
  const obstacles = diff === "plain"
    ? { ice: 0, chain: 0, blocker: 0, d: "normal" }
    : diff === "obstacles"
    ? { ice: 0.12, chain: 0.1, blocker: 0.06, d: "normal" }
    : { ice: 0.2, chain: 0.2, blocker: 0.1, d: "master" };
  if (handle !== null) { M.release(handle); handle = null; }
  setHandle(M.createBoard(obstacles.ice, obstacles.chain, obstacles.blocker, obstacles.d));
  selected = null;
  log(`— new board (${diff}) — engine guarantees at least one legal move`);
  render();
}

// Boot
try {
  newBoard();
  $("status").className = "status ready";
  $("statusText").textContent = "Ready — the real Java engine, compiled to JS, running in this tab";
  $("newBoard").addEventListener("click", newBoard);
  $("difficulty").addEventListener("change", newBoard);
  $("stress").addEventListener("click", async () => {
    // Mint a lot of gems fast — the exact pattern that collided when ids came
    // from a browser-clamped clock.
    const btn = $("stress");
    btn.disabled = true;
    btn.textContent = "generating…";
    const before = allIds.size;
    let dupes = 0;
    for (let i = 0; i < 60; i++) {
      const h = M.createBoard(0, 0, 0, "normal");
      for (const id of JSON.parse(M.idsJson(h))) {
        if (allIds.has(id)) dupes++;
        allIds.add(id);
      }
      M.release(h);
    }
    idCollisions += dupes;
    log(`stress: minted ${60 * 64} gems in-browser · ${dupes} duplicate ids`);
    $("idCount").textContent = allIds.size;
    $("idDupes").textContent = idCollisions;
    $("idDupes").className = "v " + (idCollisions === 0 ? "teal" : "red");
    btn.disabled = false;
    btn.textContent = "Mint 3,840 more gems";
    render();
  });
} catch (err) {
  $("status").className = "status err";
  $("statusText").textContent = "Failed to start: " + err;
  console.error(err);
}

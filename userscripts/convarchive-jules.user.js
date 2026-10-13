// ==UserScript==
// @name         Conversation Archive — Jules Exporter
// @namespace    app.convarchive
// @version      1.0
// @description  EXPERIMENTAL: adds an "Export to Archive (JSON)" button on jules.google.com that saves the open task's title, prompt, status, activity log, plan, outputs, and diffs (as visible) as canonical JSON v1 for the Conversation Archive Android app.
// @author       Conversation Archive
// @match        https://jules.google.com/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

/*
 * CONVERSATION ARCHIVE — JULES COMPANION USERSCRIPT (EXPERIMENTAL v1.0)
 * ---------------------------------------------------------------------
 * Original code. Not derived from any third-party exporter.
 *
 * DOM SELECTORS (defensive, tried in order — all FRAGILE; Jules' markup is
 * undocumented and can change without notice. Missing sections are omitted,
 * never invented):
 *   title:       'h1', '[data-testid="task-title"]', '.task-title'
 *   task id:     parsed from the URL (/task/<id> or /tasks/<id>); never the
 *                page text
 *   status:      '[data-testid="task-status"]', '.task-status',
 *                '[class*="status"]'
 *   prompt:      '[data-testid="task-prompt"]', '.task-prompt',
 *                '[class*="prompt"]'
 *   activity:    '[data-testid="activity-log"] li, [data-testid="activity-log"] > *',
 *                '.activity-log li', '[class*="activity"] li'
 *   plan:        '[data-testid="plan"]', '.plan', '[class*="plan"]'
 *   outputs:     '[data-testid="output"]', '.output', '[class*="output"]'
 *   repo/branch: '[data-testid="repo"]', '[data-testid="branch"]',
 *                '.repo', '.branch'
 *   diffs:       'pre.diff, pre[class*="diff"], code[class*="diff"]'
 *                -> codeBlocks language "diff" (only if actually in the DOM)
 *   timestamps:  'time[datetime]' near the task header (usually absent)
 *
 * FIELDS JULES DOES NOT EXPOSE TO PAGE-SIDE TOOLING (per the project's
 * research, 2026-10-12 — the script does NOT fake them):
 *  - Activity-log ENTRY CONTENTS: the Jules web UI renders an activity panel,
 *    but no reviewed source (including the Jules Task Manager extension,
 *    which reads Jules' internal batchexecute RPCs) documented the log's
 *    per-entry text fields. Generic fallbacks are tried; entries may be
 *    empty or coarse-grained.
 *  - Plan STEP text: same situation — plan panels render but their internal
 *    step shapes are not published; captured only if plain text is visible.
 *  - Per-turn/per-entry timestamps: not attested by any reviewed source.
 *  - Diff/patch content: the reviewed extension cross-checks GitHub PRs
 *    instead of reading diffs from Jules, implying diffs are not reliably
 *    reachable; the script grabs diff-styled code only if the page shows it.
 *  - Internal RPC fields (suggestion category/prompts, model config,
 *    experiment IDs, daily quota): known to the extension but not rendered
 *    as exportable page text; not scraped.
 *  - NO login bypass: reads only what your own signed-in browser already
 *    shows. No passwords, cookies, or tokens are read or sent anywhere.
 */
(function () {
  'use strict';

  var PLATFORM = 'jules';
  var EXPORTER = 'convarchive-userscript-jules/1.0';

  var TITLE_SEL   = ['h1', '[data-testid="task-title"]', '.task-title'];
  var STATUS_SEL  = ['[data-testid="task-status"]', '.task-status', '[class*="task-status"]'];
  var PROMPT_SEL  = ['[data-testid="task-prompt"]', '.task-prompt', '[class*="prompt"]'];
  var ACTLOG_SEL  = ['[data-testid="activity-log"] li', '[data-testid="activity-log"] > *',
                     '.activity-log li', '[class*="activity-log"] li'];
  var PLAN_SEL    = ['[data-testid="plan"]', '.task-plan', '[class*="plan"]'];
  var OUTPUT_SEL  = ['[data-testid="output"]', '.task-output', '[class*="output"]'];
  var REPO_SEL    = ['[data-testid="repo"]', '.repo', '[class*="repo-name"]'];
  var BRANCH_SEL  = ['[data-testid="branch"]', '.branch', '[class*="branch"]'];
  var DIFF_SEL    = ['pre.diff', 'pre[class*="diff"]', 'code[class*="diff"]'];
  var HEADER_SCOPE= ['main', '[role="main"]', 'body'];

  function pick(list, root) {
    var r = root || document;
    for (var i = 0; i < list.length; i++) {
      var els = r.querySelectorAll(list[i]);
      if (els.length) return els;
    }
    return [];
  }
  function pickOne(list, root) {
    var els = pick(list, root);
    return els.length ? els[0] : null;
  }
  function textOf(el) {
    try { return (el.innerText || '').replace(/\n{3,}/g, '\n\n').trim(); }
    catch (e) { return (el.textContent || '').trim(); }
  }

  function taskIdFromUrl() {
    var m = location.pathname.match(/\/tasks?\/([A-Za-z0-9_-]+)/);
    return m ? m[1] : null;
  }

  function timestampNearTaskHeader() {
    for (var i = 0; i < HEADER_SCOPE.length; i++) {
      var scope = document.querySelector(HEADER_SCOPE[i]);
      if (!scope) continue;
      var t = scope.querySelector('time[datetime]');
      if (t) return t.getAttribute('datetime');
    }
    return null; // never invent
  }

  function diffBlocks() {
    var out = [];
    var els = pick(DIFF_SEL);
    for (var i = 0; i < els.length; i++) {
      var code = (els[i].innerText || els[i].textContent || '').replace(/\n$/, '');
      if (!code.trim()) continue;
      out.push({ language: 'diff', code: code });
    }
    return out;
  }

  function extract() {
    var titleEl = pickOne(TITLE_SEL);
    var title = titleEl ? textOf(titleEl).slice(0, 140) : 'Jules task';
    var promptEl = pickOne(PROMPT_SEL);
    var prompt = promptEl ? textOf(promptEl) : null;
    var statusEl = pickOne(STATUS_SEL);
    var status = statusEl ? textOf(statusEl).slice(0, 120) : null;
    var repoEl = pickOne(REPO_SEL);
    var branchEl = pickOne(BRANCH_SEL);
    var taskId = taskIdFromUrl();
    var createdAt = timestampNearTaskHeader();

    var messages = [];
    if (prompt) {
      messages.push({ role: 'user', text: prompt, markdown: prompt, createdAt: createdAt,
                      model: null, codeBlocks: [], attachments: [],
                      raw: { block: 'task-prompt' } });
    }
    // Activity log entries -> role "event".
    var seen = new Set();
    var entries = pick(ACTLOG_SEL);
    for (var i = 0; i < entries.length; i++) {
      var text = textOf(entries[i]);
      if (!text || seen.has(text)) continue;
      seen.add(text);
      var raw = { block: 'activity-log' };
      var t = entries[i].querySelector('time[datetime]');
      if (t) raw.entryTime = t.getAttribute('datetime');
      var st = entries[i].querySelector('[data-testid="activity-status"], .status');
      if (st) raw.status = textOf(st).slice(0, 120);
      messages.push({ role: 'event', text: text, markdown: text,
                      createdAt: raw.entryTime || null, model: null,
                      codeBlocks: [], attachments: [], raw: raw });
    }
    // Plan -> role "assistant" (model-generated), kept separate via raw.block.
    var planEl = pickOne(PLAN_SEL);
    if (planEl) {
      var planText = textOf(planEl);
      if (planText) messages.push({ role: 'assistant', text: planText, markdown: planText,
                                    createdAt: null, model: null, codeBlocks: [],
                                    attachments: [], raw: { block: 'plan' } });
    }
    // Outputs -> role "assistant".
    var outEls = pick(OUTPUT_SEL);
    for (var j = 0; j < outEls.length; j++) {
      var otext = textOf(outEls[j]);
      if (!otext) continue;
      messages.push({ role: 'assistant', text: otext, markdown: otext, createdAt: null,
                      model: null, codeBlocks: diffBlocks(), attachments: [],
                      raw: { block: 'output' } });
    }
    // Diffs on their own (if no output section carried them).
    var diffs = diffBlocks();
    if (diffs.length && !messages.some(function (m) { return m.codeBlocks && m.codeBlocks.length; })) {
      messages.push({ role: 'assistant', text: '(code changes shown below)',
                      markdown: '(code changes shown below)', createdAt: null, model: null,
                      codeBlocks: diffs, attachments: [], raw: { block: 'diff' } });
    }
    return { title: title, taskId: taskId, status: status, createdAt: createdAt,
             repo: repoEl ? textOf(repoEl).slice(0, 200) : null,
             branch: branchEl ? textOf(branchEl).slice(0, 200) : null,
             messages: messages };
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function download(name, text) {
    var blob = new Blob([text], { type: 'application/json;charset=utf-8' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 4000);
  }

  function onExport(btn) {
    try {
      var data = extract();
      console.log('convarchive: found ' + data.messages.length + ' messages/events');
      if (!data.messages.length) throw new Error('no task data found in the page DOM');
      var d = new Date();
      var stamp = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
                  '_' + pad(d.getHours()) + '-' + pad(d.getMinutes());
      var metadata = {};
      if (data.status) metadata.status = data.status;
      if (data.repo) metadata.repo = data.repo;
      if (data.branch) metadata.branch = data.branch;
      var payload = {
        schemaVersion: 1,
        platform: PLATFORM,
        title: data.title,
        sourceId: data.taskId,
        sourceUrl: location.href,
        createdAt: data.createdAt,
        updatedAt: null,
        exportedAt: d.toISOString(),
        exporter: EXPORTER,
        tags: [],
        favorite: false,
        notes: '',
        messages: data.messages,
        metadata: metadata,
        originalExport: null
      };
      download('jules_task_' + stamp + '.json', JSON.stringify(payload, null, 2));
      btn.textContent = '\u2B07 Export to Archive (JSON) \u2014 ' + data.messages.length + ' entries found \u2713';
    } catch (err) {
      console.error('convarchive: extraction failed', err);
      alert('Extraction failed: ' + (err && err.message ? err.message : err) +
            '. The site may have changed its layout.');
    }
  }

  function install() {
    var wrap = document.createElement('div');
    wrap.style.cssText = 'position:fixed;right:12px;bottom:84px;z-index:2147483647;' +
      'display:flex;gap:6px;align-items:center;font-family:system-ui,sans-serif;';
    var btn = document.createElement('button');
    btn.textContent = '\u2B07 Export to Archive (JSON)';
    btn.style.cssText = 'background:#1a73e8;color:#fff;border:0;border-radius:20px;' +
      'padding:10px 16px;font-size:14px;box-shadow:0 2px 8px rgba(0,0,0,.35);cursor:pointer;';
    var x = document.createElement('button');
    x.textContent = '\u2715';
    x.title = 'Dismiss';
    x.setAttribute('aria-label', 'Dismiss');
    x.style.cssText = 'background:#fff;color:#333;border:1px solid #ccc;border-radius:50%;' +
      'width:28px;height:28px;cursor:pointer;font-size:13px;';
    x.onclick = function () { wrap.remove(); };
    btn.onclick = function () { onExport(btn); };
    wrap.appendChild(btn);
    wrap.appendChild(x);
    document.body.appendChild(wrap);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install);
  } else {
    install();
  }
})();

/* =====================================================================
 * Conversation Archive — in-app extractor (v1.1)
 * ---------------------------------------------------------------------
 * Injectable companion to userscripts/*.user.js. Same extraction logic and
 * selectors, adapted for WebView.evaluateJavascript: no Tampermonkey APIs,
 * no download step — the script RETURNS the canonical JSON v1 string (or a
 * small error/empty marker object) and the app saves it into the library.
 *
 * Public API (all on window):
 *   __convarchiveStartScroll(platform) -> "started" | "error: ..."
 *   __convarchiveScrollState()         -> JSON string
 *        {started, done, passes, found}
 *   __convarchiveExtract(platform)     -> JSON string of the canonical
 *        payload, OR JSON string of {"__error": msg}, OR JSON string of
 *        {"__empty": reason}.
 *
 * platform is one of: "gemini", "aistudio", "jules".
 *
 * SECURITY: reads only conversation DOM the user can already see. Never
 * touches password fields, cookies, tokens, or localStorage. Makes no
 * network requests of its own.
 * ===================================================================== */
(function () {
  'use strict';

  /* ---------------- shared helpers ---------------- */

  function pick(list, root) {
    var r = root || document;
    for (var i = 0; i < list.length; i++) {
      try {
        var els = r.querySelectorAll(list[i]);
        if (els.length) return els;
      } catch (e) { /* bad selector, try next */ }
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

  function codeBlocksOf(el) {
    var out = [];
    var pres = el.querySelectorAll('pre');
    for (var i = 0; i < pres.length; i++) {
      var codeEl = pres[i].querySelector('code') || pres[i];
      var code = (codeEl.innerText || codeEl.textContent || '').replace(/\n$/, '');
      if (!code.trim()) continue;
      var cls = (codeEl.className || '') + ' ' + (codeEl.getAttribute('data-language') || '');
      var m = cls.match(/(?:language|lang)[-_]([A-Za-z0-9+#-]+)/i);
      out.push({ language: m ? m[1].toLowerCase() : null, code: code });
    }
    return out;
  }

  function attachmentsOf(el) {
    var out = [];
    var imgs = el.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var alt = imgs[i].getAttribute('alt');
      if (!alt) continue; // skip decorative/emoji images
      out.push({ name: alt.slice(0, 120), mimeType: 'image/*', sizeBytes: 0,
                 note: 'content not embedded, metadata only' });
    }
    return out;
  }

  function createdAtOf(el) {
    var t = el.querySelector('time[datetime]');
    if (t) return t.getAttribute('datetime');
    var d = el.querySelector('[data-timestamp]');
    if (d) return d.getAttribute('data-timestamp');
    return null; // never invent
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function payload(platform, exporter, title, sourceId, messages, metadata) {
    var d = new Date();
    return {
      schemaVersion: 1,
      platform: platform,
      title: title,
      sourceId: sourceId,
      sourceUrl: location.href,
      createdAt: null,
      updatedAt: null,
      exportedAt: d.toISOString(),
      exporter: exporter,
      tags: [],
      favorite: false,
      notes: '',
      messages: messages,
      metadata: metadata || {},
      originalExport: null
    };
  }

  /* ---------------- Gemini ---------------- */

  var GEMINI_USER_SEL  = ['user-query', '[data-test-id="user-query"]', '.user-query',
                          '[data-message-author="user"]'];
  var GEMINI_MODEL_SEL = ['model-response', '[data-test-id="model-response"]', '.model-response',
                          '[data-message-author="assistant"]'];

  function geminiTurnCount() {
    return pick(GEMINI_USER_SEL).length + pick(GEMINI_MODEL_SEL).length;
  }

  function extractGemini() {
    var users = pick(GEMINI_USER_SEL), models = pick(GEMINI_MODEL_SEL);
    var nodes = [], nodeSet = [];
    var seen = function (n) { return nodeSet.indexOf(n) !== -1; };
    var push = function (list, role) {
      for (var i = 0; i < list.length; i++) {
        if (seen(list[i])) continue;
        nodeSet.push(list[i]);
        nodes.push({ node: list[i], role: role });
      }
    };
    push(users, 'user');
    push(models, 'assistant');
    nodes.sort(function (a, b) {
      var p = a.node.compareDocumentPosition(b.node);
      if (p & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
      if (p & Node.DOCUMENT_POSITION_PRECEDING) return 1;
      return 0;
    });
    // Drop hits nested inside another hit.
    nodes = nodes.filter(function (n) {
      for (var j = 0; j < nodes.length; j++) {
        if (nodes[j] !== n && nodes[j].node.contains(n.node)) return false;
      }
      return true;
    });
    var messages = [];
    for (var k = 0; k < nodes.length; k++) {
      var text = textOf(nodes[k].node);
      if (!text) continue;
      messages.push({
        role: nodes[k].role, text: text, markdown: text,
        createdAt: createdAtOf(nodes[k].node), model: null,
        codeBlocks: codeBlocksOf(nodes[k].node),
        attachments: attachmentsOf(nodes[k].node), raw: {}
      });
    }
    if (!messages.length) return { empty: 'no Gemini messages found in the page DOM' };
    var title = (document.title || '').replace(/\s*[-\u2013]\s*Google Gemini\s*$/i, '').trim();
    var firstUser = null;
    for (var m = 0; m < messages.length; m++) {
      if (messages[m].role === 'user') { firstUser = messages[m]; break; }
    }
    if (!title && firstUser) title = firstUser.text.slice(0, 80);
    if (!title) title = 'Gemini conversation';
    return { payload: payload('gemini', 'convarchive-webview-gemini/1.1',
                              title, null, messages, {}) };
  }

  /* ---------------- AI Studio ---------------- */

  var AISTUDIO_TURN_SEL    = ['ms-chat-turn', '[data-turn-role]', '.chat-turn', '[class*="chat-turn"]'];
  var AISTUDIO_SCROLL_SEL  = ['ms-autoscroll-container', '.ms-autoscroll-container', '[class*="autoscroll"]'];
  var AISTUDIO_THOUGHT_SEL = ['ms-thought-chunk', '[data-thought]', '.thought-chunk', '[class*="thought-chunk"]'];
  var AISTUDIO_SYS_SEL     = ['ms-system-instructions-panel .subtitle',
                              'ms-system-instruction-editor textarea',
                              '[data-system-instruction]'];
  var AISTUDIO_MODEL_SEL   = ['.run-settings .model-name', '[data-model-name]', '.model-name'];
  var AISTUDIO_TEMP_SEL    = ['[data-temperature]', '.temperature-value'];

  function aistudioRoleOf(turn) {
    var attr = turn.getAttribute('data-turn-role');
    if (attr) {
      var a = attr.toLowerCase();
      if (a.indexOf('model') === 0 || a === 'assistant') return 'assistant';
      if (a.indexOf('user') === 0) return 'user';
    }
    if (turn.querySelector('.model-prompt-container')) return 'assistant';
    if (turn.querySelector('.user-prompt-container')) return 'user';
    return 'user'; // default, documented as a guess
  }

  function aistudioTurnCount() {
    return pick(AISTUDIO_TURN_SEL).length + pick(AISTUDIO_THOUGHT_SEL).length;
  }

  function extractAistudio() {
    var turns = pick(AISTUDIO_TURN_SEL);
    var collected = [];
    var seenNodes = [];
    var addNode = function (node, role, thought) {
      if (seenNodes.indexOf(node) !== -1) return;
      seenNodes.push(node);
      collected.push({ node: node, role: role, thought: thought });
    };
    for (var i = 0; i < turns.length; i++) addNode(turns[i], aistudioRoleOf(turns[i]), false);
    var thoughts = pick(AISTUDIO_THOUGHT_SEL);
    for (var j = 0; j < thoughts.length; j++) addNode(thoughts[j], 'assistant', true);
    collected.sort(function (a, b) {
      var p = a.node.compareDocumentPosition(b.node);
      if (p & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
      if (p & Node.DOCUMENT_POSITION_PRECEDING) return 1;
      return 0;
    });
    var messages = [];
    var lastFp = null;
    for (var k = 0; k < collected.length; k++) {
      var text = textOf(collected[k].node);
      if (!text) continue;
      var fpk = collected[k].role + '\n' + text.slice(0, 400);
      if (fpk === lastFp) continue; // virtualization overlap
      lastFp = fpk;
      messages.push({
        role: collected[k].role, text: text, markdown: text,
        createdAt: createdAtOf(collected[k].node), model: null,
        codeBlocks: codeBlocksOf(collected[k].node), attachments: [],
        raw: collected[k].thought ? { block: 'thought' } : {}
      });
    }
    if (!messages.length) return { empty: 'no AI Studio turns found in the page DOM' };
    var firstUser = null;
    for (var m = 0; m < messages.length; m++) {
      if (messages[m].role === 'user') { firstUser = messages[m]; break; }
    }
    var title = firstUser ? firstUser.text.slice(0, 80) : 'AI Studio conversation';
    var metadata = {};
    var sys = pickOne(AISTUDIO_SYS_SEL);
    if (sys) {
      var sysText = (sys.value !== undefined && sys.value !== '') ? sys.value : textOf(sys);
      if (sysText) metadata.systemInstruction = sysText;
    }
    var model = pickOne(AISTUDIO_MODEL_SEL);
    if (model && textOf(model)) metadata.model = textOf(model);
    var temp = pickOne(AISTUDIO_TEMP_SEL);
    if (temp && textOf(temp)) metadata.temperature = textOf(temp);
    return { payload: payload('aistudio', 'convarchive-webview-aistudio/1.1',
                              title, null, messages, metadata) };
  }

  /* ---------------- Jules ---------------- */

  var JULES_TITLE_SEL  = ['h1', '[data-testid="task-title"]', '.task-title'];
  var JULES_STATUS_SEL = ['[data-testid="task-status"]', '.task-status', '[class*="task-status"]'];
  var JULES_PROMPT_SEL = ['[data-testid="task-prompt"]', '.task-prompt', '[class*="prompt"]'];
  var JULES_ACTLOG_SEL = ['[data-testid="activity-log"] li', '[data-testid="activity-log"] > *',
                          '.activity-log li', '[class*="activity-log"] li'];
  var JULES_PLAN_SEL   = ['[data-testid="plan"]', '.task-plan', '[class*="plan"]'];
  var JULES_OUTPUT_SEL = ['[data-testid="output"]', '.task-output', '[class*="output"]'];
  var JULES_REPO_SEL   = ['[data-testid="repo"]', '.repo', '[class*="repo-name"]'];
  var JULES_BRANCH_SEL = ['[data-testid="branch"]', '.branch', '[class*="branch"]'];
  var JULES_DIFF_SEL   = ['pre.diff', 'pre[class*="diff"]', 'code[class*="diff"]'];
  var JULES_SCROLL_SEL = ['[class*="activity"]', 'main', '[role="main"]'];

  function julesTaskIdFromUrl() {
    var m = location.pathname.match(/\/tasks?\/([A-Za-z0-9_-]+)/);
    return m ? m[1] : null;
  }

  function julesDiffBlocks() {
    var out = [];
    var els = pick(JULES_DIFF_SEL);
    for (var i = 0; i < els.length; i++) {
      var code = (els[i].innerText || els[i].textContent || '').replace(/\n$/, '');
      if (!code.trim()) continue;
      out.push({ language: 'diff', code: code });
    }
    return out;
  }

  function extractJules() {
    var titleEl = pickOne(JULES_TITLE_SEL);
    var title = titleEl ? textOf(titleEl).slice(0, 140) : 'Jules task';
    var promptEl = pickOne(JULES_PROMPT_SEL);
    var prompt = promptEl ? textOf(promptEl) : null;
    var statusEl = pickOne(JULES_STATUS_SEL);
    var status = statusEl ? textOf(statusEl).slice(0, 120) : null;
    var repoEl = pickOne(JULES_REPO_SEL);
    var branchEl = pickOne(JULES_BRANCH_SEL);
    var taskId = julesTaskIdFromUrl();

    var messages = [];
    if (prompt) {
      messages.push({ role: 'user', text: prompt, markdown: prompt, createdAt: null,
                      model: null, codeBlocks: [], attachments: [],
                      raw: { block: 'task-prompt' } });
    }
    var seen = {};
    var entries = pick(JULES_ACTLOG_SEL);
    for (var i = 0; i < entries.length; i++) {
      var text = textOf(entries[i]);
      if (!text || seen[text]) continue;
      seen[text] = true;
      var raw = { block: 'activity-log' };
      var t = entries[i].querySelector('time[datetime]');
      if (t) raw.entryTime = t.getAttribute('datetime');
      messages.push({ role: 'event', text: text, markdown: text,
                      createdAt: raw.entryTime || null, model: null,
                      codeBlocks: [], attachments: [], raw: raw });
    }
    var planEl = pickOne(JULES_PLAN_SEL);
    if (planEl) {
      var planText = textOf(planEl);
      if (planText) messages.push({ role: 'assistant', text: planText, markdown: planText,
                                    createdAt: null, model: null, codeBlocks: [],
                                    attachments: [], raw: { block: 'plan' } });
    }
    var outEls = pick(JULES_OUTPUT_SEL);
    for (var j = 0; j < outEls.length; j++) {
      var otext = textOf(outEls[j]);
      if (!otext) continue;
      messages.push({ role: 'assistant', text: otext, markdown: otext, createdAt: null,
                      model: null, codeBlocks: julesDiffBlocks(), attachments: [],
                      raw: { block: 'output' } });
    }
    var diffs = julesDiffBlocks();
    var hasCode = false;
    for (var m = 0; m < messages.length; m++) {
      if (messages[m].codeBlocks && messages[m].codeBlocks.length) { hasCode = true; break; }
    }
    if (diffs.length && !hasCode) {
      messages.push({ role: 'assistant', text: '(code changes shown below)',
                      markdown: '(code changes shown below)', createdAt: null, model: null,
                      codeBlocks: diffs, attachments: [], raw: { block: 'diff' } });
    }
    if (!messages.length) return { empty: 'no Jules task data found in the page DOM' };
    var metadata = {};
    if (status) metadata.status = status;
    if (repoEl && textOf(repoEl)) metadata.repo = textOf(repoEl).slice(0, 200);
    if (branchEl && textOf(branchEl)) metadata.branch = textOf(branchEl).slice(0, 200);
    return { payload: payload('jules', 'convarchive-webview-jules/1.1',
                              title, taskId, messages, metadata) };
  }

  /* ---------------- bounded auto-scroll ---------------- */

  var SCROLL_HOST_SEL = {
    gemini: [],
    aistudio: AISTUDIO_SCROLL_SEL,
    jules: JULES_SCROLL_SEL
  };

  function scrollHostFor(platform) {
    var list = SCROLL_HOST_SEL[platform] || [];
    var el = pickOne(list);
    return el || document.scrollingElement || document.documentElement;
  }

  function turnCountFor(platform) {
    if (platform === 'gemini') return geminiTurnCount();
    if (platform === 'aistudio') return aistudioTurnCount();
    return pick(JULES_ACTLOG_SEL).length;
  }

  function scrollTick(platform) {
    var st = window.__cvScroll;
    if (!st || st.done) return;
    var host = st.host;
    var count = 0;
    try { count = turnCountFor(platform); } catch (e) { /* ignore */ }
    st.passes++;
    if (count === st.lastCount) st.stuck++; else { st.stuck = 0; st.lastCount = count; }
    var atBottom = false;
    try {
      atBottom = host.scrollTop >= host.scrollHeight - host.clientHeight - 4;
    } catch (e) { /* ignore */ }
    if (atBottom || st.stuck >= 5 || st.passes >= 60) {
      st.done = true;
      try { clearInterval(st.timer); } catch (e) { /* ignore */ }
      return;
    }
    try { host.scrollTop += 900; } catch (e) { /* ignore */ }
  }

  window.__convarchiveStartScroll = function (platform) {
    try {
      if (window.__cvScroll && window.__cvScroll.timer) {
        try { clearInterval(window.__cvScroll.timer); } catch (e) { /* ignore */ }
      }
      var host = scrollHostFor(platform);
      window.__cvScroll = { host: host, passes: 0, stuck: 0, lastCount: -1,
                            done: false, timer: null };
      try { host.scrollTop = 0; } catch (e) { /* ignore */ }
      window.__cvScroll.timer = setInterval(function () { scrollTick(platform); }, 600);
      return 'started';
    } catch (e) {
      return 'error: ' + (e && e.message ? e.message : e);
    }
  };

  window.__convarchiveScrollState = function () {
    var st = window.__cvScroll;
    if (!st) return JSON.stringify({ started: false, done: false, passes: 0, found: 0 });
    return JSON.stringify({ started: true, done: !!st.done, passes: st.passes,
                            found: st.lastCount < 0 ? 0 : st.lastCount });
  };

  window.__convarchiveExtract = function (platform) {
    try {
      var r;
      if (platform === 'gemini') r = extractGemini();
      else if (platform === 'aistudio') r = extractAistudio();
      else if (platform === 'jules') r = extractJules();
      else return JSON.stringify({ __error: 'unknown platform: ' + platform });
      if (r.empty) return JSON.stringify({ __empty: r.empty });
      return JSON.stringify(r.payload);
    } catch (e) {
      return JSON.stringify({ __error: (e && e.message ? e.message : String(e)) +
        ' (the site may have changed its layout)' });
    }
  };
})();

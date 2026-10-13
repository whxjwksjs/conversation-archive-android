// ==UserScript==
// @name         Conversation Archive — Gemini Exporter
// @namespace    app.convarchive
// @version      1.0
// @description  EXPERIMENTAL: adds an "Export to Archive (JSON)" button on gemini.google.com that saves the visible conversation as canonical JSON v1 for the Conversation Archive Android app.
// @author       Conversation Archive
// @match        https://gemini.google.com/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

/*
 * CONVERSATION ARCHIVE — GEMINI COMPANION USERSCRIPT (EXPERIMENTAL v1.0)
 * ---------------------------------------------------------------------
 * Original code. Not derived from any third-party (AGPL/GPL) exporter.
 *
 * DOM SELECTORS (defensive, tried in order — all FRAGILE; Google changes
 * this markup regularly. If extraction returns nothing, the selectors
 * broke — see the browser console for "convarchive:" logs):
 *   user turns:  'user-query', '[data-test-id="user-query"]', '.user-query',
 *                '[data-message-author="user"]'
 *   model turns: 'model-response', '[data-test-id="model-response"]',
 *                '.model-response', '[data-message-author="assistant"]'
 *   code:        'pre' (inner 'code' preferred; language from a
 *                'language-*'/'lang-*' class or data-language attr, else null)
 *   timestamps: 'time[datetime]' inside the turn, then '[data-timestamp]'.
 *               Usually absent in the DOM — then createdAt is null, NEVER
 *               invented.
 *   title:       document.title minus the " - Google Gemini" suffix; falls
 *                back to the first user message truncated to 80 chars.
 *
 * WHAT IT CANNOT CAPTURE (read before relying on an export):
 *  - Only RENDERED messages. Long chats are virtualized; turns scrolled out
 *    of view may be missing. Scroll through the whole conversation first.
 *  - Image/attachment CONTENT — metadata only (filename/alt label when the
 *    DOM provides one), never embedded.
 *  - Reasoning traces, model name, temperature, system instructions,
 *    alternate responses — not reliably exposed per turn in the DOM.
 *  - NO login bypass: it reads only what your own signed-in browser already
 *    shows. It never reads passwords, cookies, or tokens, and nothing is
 *    sent anywhere — the JSON is downloaded to your device only.
 */
(function () {
  'use strict';

  var PLATFORM = 'gemini';
  var EXPORTER = 'convarchive-userscript-gemini/1.0';

  var USER_SEL  = ['user-query', '[data-test-id="user-query"]', '.user-query', '[data-message-author="user"]'];
  var MODEL_SEL = ['model-response', '[data-test-id="model-response"]', '.model-response', '[data-message-author="assistant"]'];

  // Return the first non-empty NodeList for the selector list.
  function pick(list, root) {
    var r = root || document;
    for (var i = 0; i < list.length; i++) {
      var els = r.querySelectorAll(list[i]);
      if (els.length) return els;
    }
    return [];
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

  function extractMessages() {
    var users = pick(USER_SEL), models = pick(MODEL_SEL);
    var nodes = [], nodeSet = new Set();
    var push = function (list, role) {
      for (var i = 0; i < list.length; i++) {
        if (nodeSet.has(list[i])) continue;
        nodeSet.add(list[i]);
        nodes.push({ node: list[i], role: role });
      }
    };
    push(users, 'user');
    push(models, 'assistant');
    // Order by position in the document.
    nodes.sort(function (a, b) {
      var p = a.node.compareDocumentPosition(b.node);
      if (p & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
      if (p & Node.DOCUMENT_POSITION_PRECEDING) return 1;
      return 0;
    });
    // Drop hits nested inside another hit (selector overlap on the same turn).
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
        role: nodes[k].role,
        text: text,
        markdown: text,
        createdAt: createdAtOf(nodes[k].node),
        model: null,
        codeBlocks: codeBlocksOf(nodes[k].node),
        attachments: attachmentsOf(nodes[k].node),
        raw: {}
      });
    }
    return messages;
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
      var messages = extractMessages();
      console.log('convarchive: found ' + messages.length + ' messages');
      if (!messages.length) throw new Error('no messages found in the page DOM');
      var title = (document.title || '').replace(/\s*[-–]\s*Google Gemini\s*$/i, '').trim();
      var firstUser = messages.filter(function (m) { return m.role === 'user'; })[0];
      if (!title && firstUser) title = firstUser.text.slice(0, 80);
      if (!title) title = 'Gemini conversation';
      var d = new Date();
      var stamp = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
                  '_' + pad(d.getHours()) + '-' + pad(d.getMinutes());
      var payload = {
        schemaVersion: 1,
        platform: PLATFORM,
        title: title,
        sourceId: null,
        sourceUrl: location.href,
        createdAt: null,
        updatedAt: null,
        exportedAt: d.toISOString(),
        exporter: EXPORTER,
        tags: [],
        favorite: false,
        notes: '',
        messages: messages,
        metadata: {},
        originalExport: null
      };
      download('gemini_conversation_' + stamp + '.json', JSON.stringify(payload, null, 2));
      btn.textContent = '\u2B07 Export to Archive (JSON) \u2014 ' + messages.length + ' messages found \u2713';
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

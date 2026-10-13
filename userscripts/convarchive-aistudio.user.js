// ==UserScript==
// @name         Conversation Archive — AI Studio Exporter
// @namespace    app.convarchive
// @version      1.0
// @description  EXPERIMENTAL: adds an "Export to Archive (JSON)" button on aistudio.google.com that scrolls history into view and saves the conversation as canonical JSON v1 for the Conversation Archive Android app.
// @author       Conversation Archive
// @match        https://aistudio.google.com/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

/*
 * CONVERSATION ARCHIVE — AI STUDIO COMPANION USERSCRIPT (EXPERIMENTAL v1.0)
 * ------------------------------------------------------------------------
 * Original code. Not derived from any third-party (AGPL/GPL) exporter.
 *
 * DOM SELECTORS (defensive, tried in order — all FRAGILE; Google changes
 * this markup regularly. If extraction returns nothing, the selectors
 * broke — see the browser console for "convarchive:" logs):
 *   turns:       'ms-chat-turn', '[data-turn-role]', '.chat-turn',
 *                '[class*="chat-turn"]'
 *   role:        data-turn-role="User"/"Model"; else presence of
 *                '.model-prompt-container' (-> assistant) or
 *                '.user-prompt-container' (-> user); default user.
 *   scroll host: 'ms-autoscroll-container', '.ms-autoscroll-container',
 *                '[class*="autoscroll"]', else the page itself
 *                (document.scrollingElement)
 *   code:        'pre' (inner 'code' preferred; language from a
 *                'language-*'/'lang-*' class or data-language attr, else null)
 *   thoughts:    'ms-thought-chunk', '[data-thought]', '.thought-chunk',
 *                '[class*="thought"]' -> role "assistant", raw.block="thought"
 *   system instr:'ms-system-instructions-panel .subtitle',
 *                'ms-system-instruction-editor textarea',
 *                'textarea[aria-label*="system"]'
 *   model:       '.run-settings .model-name', '[data-model-name]' (best effort)
 *   temperature: '[data-temperature]', '.temperature-value' (best effort)
 *   timestamps:  'time[datetime]' / '[data-timestamp]' (rarely present —
 *                then createdAt is null, NEVER invented)
 *
 * WHAT IT CANNOT CAPTURE (read before relying on an export):
 *  - Only RENDERED messages. AI Studio virtualizes long chats, so the script
 *    scrolls the chat to the top and back down to force turns to render.
 *    Watch the button progress ("Loading history… N%"); collection may still
 *    miss turns if virtualization evicts them again, so re-run the export at
 *    the bottom if a long chat looks short.
 *  - Image/attachment CONTENT — metadata only, never embedded.
 *  - Thought/reasoning blocks are attempted but are not guaranteed to be in
 *    the DOM (AI Studio may hide them entirely).
 *  - Model name / temperature / system instruction are best-effort; the
 *    run-settings panel may be collapsed or the fields renamed — missing
 *    fields come back null, never invented.
 *  - NO login bypass: reads only what your own signed-in browser already
 *    shows. No passwords, cookies, or tokens are read or sent anywhere.
 */
(function () {
  'use strict';

  var PLATFORM = 'aistudio';
  var EXPORTER = 'convarchive-userscript-aistudio/1.0';

  var TURN_SEL    = ['ms-chat-turn', '[data-turn-role]', '.chat-turn', '[class*="chat-turn"]'];
  var SCROLL_SEL  = ['ms-autoscroll-container', '.ms-autoscroll-container', '[class*="autoscroll"]'];
  var THOUGHT_SEL = ['ms-thought-chunk', '[data-thought]', '.thought-chunk', '[class*="thought-chunk"]'];
  var SYS_SEL     = ['ms-system-instructions-panel .subtitle', 'ms-system-instruction-editor textarea',
                     'textarea[aria-label*="system"]'];
  var MODEL_SEL   = ['.run-settings .model-name', '[data-model-name]', '.model-name'];
  var TEMP_SEL    = ['[data-temperature]', '.temperature-value'];

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

  function roleOf(turn) {
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

  function createdAtOf(el) {
    var t = el.querySelector('time[datetime]');
    if (t) return t.getAttribute('datetime');
    var d = el.querySelector('[data-timestamp]');
    if (d) return d.getAttribute('data-timestamp');
    return null; // never invent
  }

  function firstText(list) {
    var el = pickOne(list);
    if (!el) return null;
    return (el.value !== undefined && el.value !== '' ? el.value : textOf(el)) || null;
  }

  function findScrollHost() {
    var el = pickOne(SCROLL_SEL);
    return el || document.scrollingElement || document.documentElement;
  }

  // Scroll the chat from top to bottom in steps, collecting turns as they
  // render. Calls onProgress(pct, count); done(collected).
  function scrollAndCollect(host, onProgress, done) {
    var seen = new Set();
    var collected = [];
    var passes = 0, stuck = 0, lastCount = -1;
    try { host.scrollTop = 0; } catch (e) { /* ignore */ }
    function tick() {
      var turns = pick(TURN_SEL);
      for (var i = 0; i < turns.length; i++) {
        if (seen.has(turns[i])) continue;
        seen.add(turns[i]);
        collected.push({ node: turns[i], role: roleOf(turns[i]), thought: false });
      }
      var thoughts = pick(THOUGHT_SEL);
      for (var j = 0; j < thoughts.length; j++) {
        if (seen.has(thoughts[j])) continue;
        seen.add(thoughts[j]);
        collected.push({ node: thoughts[j], role: 'assistant', thought: true });
      }
      var max = Math.max(1, host.scrollHeight - host.clientHeight);
      var pct = Math.min(99, Math.round(host.scrollTop / max * 100));
      onProgress(pct, collected.length);
      passes++;
      if (collected.length === lastCount) stuck++; else { stuck = 0; lastCount = collected.length; }
      var atBottom = host.scrollTop >= host.scrollHeight - host.clientHeight - 4;
      if (atBottom || stuck >= 5 || passes > 80) {
        // Order surviving nodes by document position.
        collected.sort(function (a, b) {
          var p = a.node.compareDocumentPosition(b.node);
          if (p & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
          if (p & Node.DOCUMENT_POSITION_PRECEDING) return 1;
          return 0;
        });
        done(collected);
        return;
      }
      try { host.scrollTop += 900; } catch (e) { /* ignore */ }
      setTimeout(tick, 500);
    }
    setTimeout(tick, 400);
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

  function onExport(btn, restoreLabel) {
    try {
      btn.disabled = true;
      var host = findScrollHost();
      scrollAndCollect(host, function (pct, count) {
        btn.textContent = 'Loading history\u2026 ' + pct + '% (' + count + ' turns)';
      }, function (collected) {
        try {
          var messages = [];
          var lastFp = null;
          for (var k = 0; k < collected.length; k++) {
            var text = textOf(collected[k].node);
            if (!text) continue;
            var fpk = collected[k].role + '\n' + text.slice(0, 400);
            if (fpk === lastFp) continue; // virtualization overlap
            lastFp = fpk;
            var msg = {
              role: collected[k].role,
              text: text,
              markdown: text,
              createdAt: createdAtOf(collected[k].node),
              model: null,
              codeBlocks: codeBlocksOf(collected[k].node),
              attachments: [],
              raw: collected[k].thought ? { block: 'thought' } : {}
            };
            messages.push(msg);
          }
          console.log('convarchive: found ' + messages.length + ' messages');
          if (!messages.length) throw new Error('no messages found in the page DOM');
          var firstUser = messages.filter(function (m) { return m.role === 'user'; })[0];
          var title = firstUser ? firstUser.text.slice(0, 80) : 'AI Studio conversation';
          var d = new Date();
          var stamp = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
                      '_' + pad(d.getHours()) + '-' + pad(d.getMinutes());
          var metadata = {};
          var sys = firstText(SYS_SEL);
          if (sys) metadata.systemInstruction = sys;
          var model = firstText(MODEL_SEL);
          if (model) metadata.model = model;
          var temp = firstText(TEMP_SEL);
          if (temp) metadata.temperature = temp;
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
            metadata: metadata,
            originalExport: null
          };
          download('aistudio_conversation_' + stamp + '.json', JSON.stringify(payload, null, 2));
          btn.textContent = '\u2B07 Export to Archive (JSON) \u2014 ' + messages.length + ' messages found \u2713';
        } catch (err) {
          console.error('convarchive: extraction failed', err);
          alert('Extraction failed: ' + (err && err.message ? err.message : err) +
                '. The site may have changed its layout.');
          btn.textContent = restoreLabel;
        }
        btn.disabled = false;
      });
    } catch (err) {
      console.error('convarchive: extraction failed', err);
      alert('Extraction failed: ' + (err && err.message ? err.message : err) +
            '. The site may have changed its layout.');
      btn.textContent = restoreLabel;
      btn.disabled = false;
    }
  }

  function install() {
    var wrap = document.createElement('div');
    wrap.style.cssText = 'position:fixed;right:12px;bottom:84px;z-index:2147483647;' +
      'display:flex;gap:6px;align-items:center;font-family:system-ui,sans-serif;';
    var label = '\u2B07 Export to Archive (JSON)';
    var btn = document.createElement('button');
    btn.textContent = label;
    btn.style.cssText = 'background:#1a73e8;color:#fff;border:0;border-radius:20px;' +
      'padding:10px 16px;font-size:14px;box-shadow:0 2px 8px rgba(0,0,0,.35);cursor:pointer;';
    var x = document.createElement('button');
    x.textContent = '\u2715';
    x.title = 'Dismiss';
    x.setAttribute('aria-label', 'Dismiss');
    x.style.cssText = 'background:#fff;color:#333;border:1px solid #ccc;border-radius:50%;' +
      'width:28px;height:28px;cursor:pointer;font-size:13px;';
    x.onclick = function () { wrap.remove(); };
    btn.onclick = function () { onExport(btn, label); };
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

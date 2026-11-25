(function () {
  'use strict';
  var found = new Map();
  var visited = new Set();
  var scriptBudget = 384 * 1024;
  var limit = 400;
  var flashMime = /^(application\/x-shockwave-flash|application\/vnd\.adobe\.flash\.movie)(?:;|$)/i;
  var swfLiteral = /(?:^|[\s"'`(=,:{\[;<>])((?:(?:https?:)?\/\/)?[\w.%~/:@+-]{1,2048}\.swf(?:\?[^\s"'<>\\`]{1,2048})?)/gi;

  function add(raw, base, title, knownFlash) {
    if (!raw || raw.length > 4096 || found.size >= limit) return;
    var url;
    try { url = new URL(raw, base); } catch (_) { return; }
    if (!/^https?:$/.test(url.protocol) || url.username || url.password) return;
    if (!knownFlash && !/\.swf$/i.test(url.pathname)) {
      for (var name of ['swf', 'movie', 'gamepath']) {
        var linked = url.searchParams.get(name);
        if (linked && /\.swf(?:[?#]|$)/i.test(linked)) {
          add(linked, base, title, true);
        }
      }
      return;
    }
    url.hash = '';
    if (!found.has(url.href)) found.set(url.href, [url.href, (title || '').slice(0, 120)]);
  }

  function scan(frame) {
    if (!frame || visited.has(frame) || visited.size >= 25 || found.size >= limit) return;
    var doc;
    try { doc = frame.document; } catch (_) { return; }
    visited.add(frame);
    var base = doc.baseURI;
    add(frame.location.href, base);
    var nodes = doc.querySelectorAll('object,embed,param,iframe,frame,ruffle-player,ruffle-object,ruffle-embed,[href],[src],[data],[data-swf],[data-movie],[data-src],[data-url]');
    for (var i = 0; i < Math.min(nodes.length, 3000) && found.size < limit; i++) {
      var node = nodes[i];
      var title = node.getAttribute('title') || node.getAttribute('name');
      var declaredFlash = flashMime.test(node.getAttribute('type') || '') ||
        /D27CDB6E/i.test(node.getAttribute('classid') || '');
      for (var attr of ['data', 'src', 'href', 'data-swf', 'data-movie', 'data-src', 'data-url']) {
        var knownFlash = (declaredFlash && (attr === 'src' || attr === 'data')) ||
          attr === 'data-swf' || attr === 'data-movie';
        add(node.getAttribute(attr), base, title, knownFlash);
      }
      if (node.localName === 'param' && /^(movie|src)$/i.test(node.getAttribute('name') || '')) {
        var object = node.closest('object');
        var movie = (node.getAttribute('name') || '').toLowerCase() === 'movie';
        add(node.getAttribute('value'), base, '', movie || (object &&
          (flashMime.test(object.getAttribute('type') || '') ||
            /D27CDB6E/i.test(object.getAttribute('classid') || ''))));
      }
    }
    try {
      var requests = frame.performance.getEntriesByType('resource');
      for (var entry of requests.slice(-2000)) {
        add(entry.name, base, '', flashMime.test(entry.contentType || ''));
      }
    } catch (_) {}
    for (var script of doc.querySelectorAll('script:not([src])')) {
      if (scriptBudget <= 0 || found.size >= limit) break;
      var source = script.textContent.slice(0, Math.min(scriptBudget, 64 * 1024));
      scriptBudget -= source.length;
      source = source.replace(/\\\//g, '/').replace(/\\u002f/gi, '/');
      swfLiteral.lastIndex = 0;
      var match;
      while (found.size < limit && (match = swfLiteral.exec(source))) add(match[1], base);
    }
    for (var i = 0; i < Math.min(frame.frames.length, 25); i++) scan(frame.frames[i]);
  }

  scan(window);
  return Array.from(found.values());
})();

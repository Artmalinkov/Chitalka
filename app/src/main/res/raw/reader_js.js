/* The same local document supports continuous reading and screen-sized pages.
   Semantic anchors keep the same passage across mode, font and viewport changes. */
(function () {
  'use strict';
  var config = window.readerConfig, viewport = document.getElementById('viewport');
  var flow = document.getElementById('flow');
  var sections = Array.from(document.querySelectorAll('section.chapter'));
  var blocks = sections.map(function (section) {
    return Array.from(section.querySelectorAll('h1,h2,h3,p,li,table,img')).map(function (block, i) {
      block.dataset.block = i; return block;
    });
  });
  var mode = config.mode, page = 0, pageCount = 1, step = 1, busy = true;
  var lastAnchor = null, resizeTimer, scrollTimer;

  function textRange(element, offset) {
    var walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT), node;
    var range = document.createRange();
    while ((node = walker.nextNode())) {
      if (offset <= node.length) { range.setStart(node, Math.max(0, offset)); range.collapse(true); return range; }
      offset -= node.length;
    }
    range.selectNodeContents(element); range.collapse(true); return range;
  }
  function anchorElement(anchor) {
    var c = Math.max(0, Math.min(sections.length - 1, Number(anchor.c) || 0));
    return blocks[c][Math.max(0, Math.min(blocks[c].length - 1, Number(anchor.b) || 0))] || sections[c];
  }
  function rangeRect(anchor) {
    var element = anchorElement(anchor), range = textRange(element, Number(anchor.o) || 0);
    var rect = range.getBoundingClientRect();
    return rect.height ? rect : element.getBoundingClientRect();
  }
  function setPage(value) {
    page = Math.max(0, Math.min(pageCount - 1, value));
    flow.style.transform = 'translateX(' + (-page * step) + 'px)';
  }
  function layout() {
    document.documentElement.classList.toggle('paged', mode === 'pages');
    flow.style.transform = '';
    page = 0;
    // Use the exact same integral width for columns and their translation. A
    // fractional container width otherwise accumulates drift over long books.
    var width = Math.floor(viewport.getBoundingClientRect().width);
    flow.style.width = mode === 'pages' ? width + 'px' : '';
    document.documentElement.style.setProperty('--column-width', width + 'px');
    step = width + 44;
    pageCount = mode === 'pages' ? Math.max(1, Math.ceil((flow.scrollWidth + 44) / step)) : 1;
  }
  function restore(anchor) {
    var rect = rangeRect(anchor);
    if (mode === 'pages') setPage(Math.floor((rect.left - viewport.getBoundingClientRect().left + page * step + 1) / step));
    else window.scrollTo(0, Math.max(0, rect.top + window.scrollY - 16));
    lastAnchor = anchor;
  }
  function visibleAnchor() {
    var left = 23, top = mode === 'pages' ? viewport.getBoundingClientRect().top : 16;
    var points = [[left, top + 6], [window.innerWidth / 2, top + 6], [left, top + 30]];
    for (var i = 0; i < points.length; i++) {
      var range = document.caretRangeFromPoint(points[i][0], points[i][1]);
      if (!range) continue;
      // Chromium may return the first document node when a point falls into a
      // column's heading margin. Accept only a caret inside the visible page.
      var caret = range.getBoundingClientRect();
      if (caret.right < 22 || caret.left > window.innerWidth - 22 || caret.bottom <= top || caret.top >= window.innerHeight) continue;
      var node = range.startContainer;
      var element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
      if (!element) continue;
      var block = element.closest('[data-block]'), section = element.closest('section.chapter');
      if (!block || !section) continue;
      var before = document.createRange(); before.selectNodeContents(block);
      try { before.setEnd(node, range.startOffset); } catch (ignored) { continue; }
      return { c: Number(section.dataset.chapter), b: Number(block.dataset.block), o: before.toString().length };
    }
    // A page may start with whitespace, an image, or the continuation of a block.
    for (var c = 0; c < blocks.length; c++) for (var b = 0; b < blocks[c].length; b++) {
      var rects = blocks[c][b].getClientRects();
      for (var r = 0; r < rects.length; r++) {
        var rect = rects[r];
        if (rect.right > 22 && rect.left < window.innerWidth - 22 && rect.bottom > top && rect.top < window.innerHeight)
          return { c: c, b: b, o: 0 };
      }
    }
    return lastAnchor || { c: 0, b: 0, o: 0 };
  }
  function report() {
    if (busy) return;
    var anchor = visibleAnchor(), section = sections[anchor.c], fraction;
    if (mode === 'scroll') {
      var height = Math.max(1, section.offsetHeight - window.innerHeight);
      fraction = (window.scrollY + 16 - (section.getBoundingClientRect().top + window.scrollY)) / height;
    } else fraction = anchor.b / Math.max(1, blocks[anchor.c].length - 1);
    lastAnchor = anchor;
    if (window.ReaderHost) window.ReaderHost.position(anchor.c, Math.max(0, Math.min(1, fraction)), JSON.stringify(anchor), page + 1, pageCount);
  }
  function afterLayout(anchor) {
    requestAnimationFrame(function () {
      layout(); restore(anchor);
      requestAnimationFrame(function () { busy = false; report(); });
    });
  }
  window.Reader = {
    canTurn: function (direction) {
      return !busy && mode === 'pages' && window.getSelection().isCollapsed && page + direction >= 0 && page + direction < pageCount;
    },
    preview: function (direction) {
      var target = page + direction;
      if (mode !== 'pages' || target < 0 || target >= pageCount || (direction && busy)) return false;
      flow.style.transform = 'translateX(' + (-target * step) + 'px)';
      return true;
    },
    turn: function (direction) {
      if (busy || mode !== 'pages' || !window.getSelection().isCollapsed) return;
      var next = page + direction;
      if (next < 0 || next >= pageCount) {
        if (window.ReaderHost) window.ReaderHost.edge(next < 0);
        return;
      }
      setPage(next); report();
    },
    jump: function (chapter) {
      if (chapter < 0 || chapter >= sections.length) return;
      busy = true;
      var anchor = { c: chapter, b: 0, o: 0 };
      restore(anchor);
      requestAnimationFrame(function () { busy = false; report(); });
    },
    setMode: function (value) {
      var anchor = lastAnchor || visibleAnchor(); busy = true;
      mode = value === 'pages' ? 'pages' : 'scroll';
      window.scrollTo(0, 0); afterLayout(anchor);
    },
    snapshot: function () { return { mode: mode, page: page + 1, pages: pageCount, anchor: lastAnchor, busy: busy }; }
  };
  window.addEventListener('scroll', function () {
    if (busy || mode !== 'scroll') return;
    clearTimeout(scrollTimer); scrollTimer = setTimeout(report, 60);
  }, { passive: true });
  window.addEventListener('resize', function () {
    if (!lastAnchor) return;
    busy = true; clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { afterLayout(lastAnchor); }, 100);
  });
  // Only a short, stationary tap in the centre reveals controls. Keep links,
  // text selection, long press, scrolling and multi-touch with the reader.
  var tap = null, lastScroll = 0;
  window.addEventListener('scroll', function () { lastScroll = Date.now(); }, { passive: true });
  document.addEventListener('touchstart', function (event) {
    if (event.touches.length !== 1) { tap = null; return; }
    var point = event.touches[0];
    tap = { x: point.clientX, y: point.clientY, time: Date.now(), moved: false,
      blocked: !window.getSelection().isCollapsed || Date.now() - lastScroll < 150 ||
        !!event.target.closest('a,button,input,textarea,select') };
  }, { passive: true });
  document.addEventListener('touchmove', function (event) {
    if (!tap || event.touches.length !== 1) { tap = null; return; }
    var point = event.touches[0];
    if (Math.hypot(point.clientX - tap.x, point.clientY - tap.y) > 8) tap.moved = true;
  }, { passive: true });
  document.addEventListener('touchcancel', function () { tap = null; }, { passive: true });
  document.addEventListener('touchend', function (event) {
    var start = tap; tap = null;
    if (!start || start.blocked || start.moved || event.touches.length ||
        Date.now() - start.time >= 300 || !window.getSelection().isCollapsed) return;
    var point = event.changedTouches[0];
    if (point.clientX > window.innerWidth / 3 && point.clientX < window.innerWidth * 2 / 3 &&
        point.clientY > window.innerHeight / 3 && point.clientY < window.innerHeight * 2 / 3 &&
        window.ReaderHost) window.ReaderHost.controls();
  }, { passive: true });
  function start() {
    layout();
    var anchor;
    try { anchor = JSON.parse(config.anchor); } catch (ignored) {}
    if (anchor && anchor.c >= 0 && anchor.c < sections.length) restore(anchor);
    else {
      anchor = { c: Math.max(0, Math.min(sections.length - 1, config.chapter)), b: 0, o: 0 };
      restore(anchor);
      if (mode === 'scroll' && config.fraction > 0) {
        var section = sections[anchor.c];
        window.scrollTo(0, section.getBoundingClientRect().top + window.scrollY - 16 +
          config.fraction * Math.max(0, section.offsetHeight - window.innerHeight));
      }
    }
    requestAnimationFrame(function () { busy = false; report(); });
  }
  var images = Array.from(document.images).map(function (image) {
    return image.complete ? Promise.resolve() : new Promise(function (resolve) { image.onload = image.onerror = resolve; });
  });
  Promise.race([Promise.all(images), new Promise(function (resolve) { setTimeout(resolve, 3000); })])
    .then(function () { requestAnimationFrame(start); });
})();

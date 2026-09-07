package com.local.multiplatformdownloader.platform.instagram


internal const val INSTAGRAM_SNAPSHOT_SCRIPT = """
    (function() {
      var states = [];
      var total = 0;
      var scripts = 0, invalid = 0, skipped = 0;
      document.querySelectorAll('script[type="application/json"], script[data-sjs]').forEach(function(script) {
        scripts++;
        var text = script.textContent || '';
        if (total + text.length > 4000000) { skipped++; return; }
        try { states.push(JSON.parse(text)); total += text.length; } catch (_) { invalid++; }
      });
      return JSON.stringify({
        finalUrl: location.href,
        initialData: JSON.stringify({states: states}),
        captureDiagnostics: {
          ready_state: document.readyState,
          script_count: scripts, parsed_script_count: states.length,
          invalid_script_count: invalid, skipped_script_count: skipped,
          captured_chars: total, input_count: document.querySelectorAll('input').length,
          body_height: document.body ? document.body.clientHeight : 0,
          page_elapsed_ms: Math.round(performance.now())
        },
        title: document.title || '',
        visibleText: (document.body ? document.body.innerText : '').slice(0, 2000)
      });
    })();
"""

internal const val INSTAGRAM_LOGIN_VIEWPORT_SCRIPT = """
    (function() {
      var timer = 0;
      var fixedContainers = window.__aggregateInstagramLayoutFixes || [];
      window.__aggregateInstagramLayoutFixes = fixedContainers;
      function inLoginFlow() {
        return /^\/accounts\/(login|onetap|two_factor_authentication)(\/|$)/.test(location.pathname);
      }
      function syncFixedContainers() {
        var height = Math.round(window.visualViewport ? window.visualViewport.height : innerHeight);
        for (var i = fixedContainers.length - 1; i >= 0; i--) {
          var fix = fixedContainers[i];
          if (!fix.node.isConnected) { fixedContainers.splice(i, 1); continue; }
          if (!inLoginFlow()) {
            fix.original.forEach(function(p) {
              if (p.value) fix.node.style.setProperty(p.name, p.value, p.priority);
              else fix.node.style.removeProperty(p.name);
            });
            fixedContainers.splice(i, 1);
          } else if (height > 100) {
            fix.node.style.setProperty('height', height + 'px', 'important');
            fix.node.style.setProperty('min-height', height + 'px', 'important');
            fix.node.style.setProperty('max-height', 'none', 'important');
          }
        }
      }
      function adjust() {
        syncFixedContainers();
        var input = document.activeElement;
        if (!input || !input.matches || !input.matches('input:not([type="hidden"]), textarea')) return;
        var viewport = window.visualViewport;
        var top = viewport ? viewport.offsetTop : 0;
        var height = viewport ? viewport.height : window.innerHeight;
        var rect = input.getBoundingClientRect();
        if (rect.height > 0 && (rect.top < top + 24 || rect.bottom > top + height - 24)) {
          input.scrollIntoView({block:'center', inline:'nearest', behavior:'auto'});
        }
      }
      function schedule() {
        clearTimeout(timer);
        timer = setTimeout(adjust, 150);
      }
      if (!window.__aggregateInstagramLogin) {
        document.addEventListener('focusin', schedule, true);
        if (window.visualViewport) window.visualViewport.addEventListener('resize', schedule);
        window.__aggregateInstagramLogin = true;
      }
      syncFixedContainers();
      // An input can have normal bounds while its ancestors clip or hide the entire form.
      // Do not collect values, text, URLs or arbitrary element IDs in diagnostics.
      function bounds(element) {
        var r = element.getBoundingClientRect();
        return [r.x, r.y, r.width, r.height].map(Math.round).join(':');
      }
      function clips(style) {
        return /^(hidden|clip|auto|scroll)$/.test(style.overflowY) ||
          /(^| )(paint|strict|content)( |$)/.test(style.contain);
      }
      function inspect(element) {
        var r = element.getBoundingClientRect();
        var chain = [], collapsed = [], hidden = false, clipped = false;
        var v = window.visualViewport;
        var left = v ? v.offsetLeft : 0, top = v ? v.offsetTop : 0;
        var right = left + (v ? v.width : innerWidth);
        var bottom = top + (v ? v.height : innerHeight);
        var visibleLeft = Math.max(left, r.left), visibleRight = Math.min(right, r.right);
        var visibleTop = Math.max(top, r.top), visibleBottom = Math.min(bottom, r.bottom);
        var node = element, lastBounds = '';
        for (var depth = 0; node && depth < 96; depth++, node = node.parentElement) {
          var s = getComputedStyle(node), nr = node.getBoundingClientRect();
          var currentBounds = bounds(node);
          // Skip identical, ordinary wrappers in the log, not in the visibility checks.
          if (depth < 2 || currentBounds !== lastBounds || clips(s) ||
              s.visibility !== 'visible' || Number(s.opacity) < 1 || s.transform !== 'none' ||
              node === document.body || node === document.documentElement) {
          chain.push(depth + ':' + node.tagName.toLowerCase() + '~rect=' + currentBounds +
            '~display=' + s.display + '~visibility=' + s.visibility + '~opacity=' + s.opacity +
            '~overflow=' + s.overflowX + '/' + s.overflowY + '~position=' + s.position +
            '~height=' + s.height + '/' + s.minHeight + '/' + s.maxHeight +
            '~contain=' + s.contain + '~content=' + s.contentVisibility +
            '~clip=' + s.clipPath + '~transform=' + s.transform +
            '~scroll=' + node.clientHeight + '/' + node.scrollHeight);
          }
          lastBounds = currentBounds;
          if (s.display === 'none' || Number(s.opacity) <= 0.01 || s.contentVisibility === 'hidden' || node.inert) hidden = true;
          if (node === element && s.visibility !== 'visible') hidden = true;
          if (node !== element && clips(s)) {
            if (nr.bottom <= r.top || nr.top >= r.bottom) clipped = true;
            visibleTop = Math.max(visibleTop, nr.top);
            visibleBottom = Math.min(visibleBottom, nr.bottom);
            // An outer clip can have scrollHeight=0 until its inner scroll container expands.
            // The input's positive bounds already establish that this chain contains a form.
            if (nr.height <= 2 && nr.width >= innerWidth * 0.6) collapsed.push(node);
          }
          if (node !== element && /^(hidden|clip|auto|scroll)$/.test(s.overflowX)) {
            visibleLeft = Math.max(visibleLeft, nr.left);
            visibleRight = Math.min(visibleRight, nr.right);
          }
        }
        // Probe inside the input itself as well as the geometric intersection. Fixed children
        // can escape an ancestor's clipping box; a hit prevents modifying such normal layouts.
        var points = [[(r.left + r.right) / 2, (r.top + r.bottom) / 2]];
        if (visibleRight > visibleLeft && visibleBottom > visibleTop) {
          points.push([(visibleLeft + visibleRight) / 2, (visibleTop + visibleBottom) / 2]);
        }
        var hit = null, reachable = false;
        points.forEach(function(p) {
          if (p[0] < left || p[0] >= right || p[1] < top || p[1] >= bottom) return;
          var candidate = document.elementFromPoint(p[0], p[1]);
          if (!hit) hit = candidate;
          if (candidate === element || element.contains(candidate)) reachable = true;
        });
        var visible = !hidden && r.width > 0 && r.height > 0 && reachable;
        return {visible: visible, hidden: hidden, collapsed: collapsed, chain: chain.join('^'),
          hit: hit ? hit.tagName.toLowerCase() + '@rect=' + bounds(hit) +
            '@position=' + getComputedStyle(hit).position + '@z=' + getComputedStyle(hit).zIndex : 'none',
          reason: hidden ? 'ancestor-hidden' : visible ? 'none' : collapsed.length ? 'collapsed-clip' :
            clipped ? 'ancestor-clip' : r.bottom <= top || r.top >= bottom ? 'offscreen' : 'occluded-or-unpainted'};
      }
      var inputs = Array.from(document.querySelectorAll('input:not([type="hidden"]), textarea'));
      var input = inputs.find(function(e) { return inspect(e).visible; }) ||
        inputs.find(function(e) { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; });
      var before = input ? inspect(input) : null;
      var repairs = [];
      if (inLoginFlow() && document.body && (document.body.clientHeight <= 2 || fixedContainers.length > 0) &&
          before && !before.visible && !before.hidden && before.collapsed.length) {
        // Only repair an actually collapsed, clipping form container. Do not dismiss overlays,
        // alter hidden verification steps, or shift normally sized login controls.
        var height = Math.round(window.visualViewport ? window.visualViewport.height : innerHeight);
        if (height > 100) before.collapsed.forEach(function(container) {
          var oldHeight = Math.round(container.getBoundingClientRect().height);
          if (!fixedContainers.some(function(fix) { return fix.node === container; })) {
            fixedContainers.push({node: container, original: ['height', 'min-height', 'max-height'].map(function(name) {
              return {name: name, value: container.style.getPropertyValue(name), priority: container.style.getPropertyPriority(name)};
            })});
          }
          container.style.setProperty('height', height + 'px', 'important');
          container.style.setProperty('min-height', height + 'px', 'important');
          container.style.setProperty('max-height', 'none', 'important');
          repairs.push(container.tagName.toLowerCase() + ':' + oldHeight + '>' +
            Math.round(container.getBoundingClientRect().height));
        });
      }
      var after = input ? inspect(input) : null;
      var shownInputs = inputs.filter(function(e) { return inspect(e).visible; });
      var textLength = document.body ? document.body.innerText.trim().length : 0;
      var state = shownInputs.length ? 'form-rendered' :
        (inputs.length ? 'layout-blocked' : 'waiting-for-render');
      // Text existence alone is not proof of visibility either. Inspect a real page control,
      // so a successfully logged-in home page can dismiss the indicator without a login form.
      if (!inputs.length && textLength > 0) {
        var controls = Array.from(document.querySelectorAll('button, a[href], [role="button"]')).slice(0, 40);
        if (controls.some(function(e) { return inspect(e).visible; })) state = 'page-content-rendered';
      }
      var rect = input ? input.getBoundingClientRect() : null;
      var units = [];
      if (document.body) {
        var probe = document.createElement('div');
        probe.style.cssText = 'position:fixed;left:-10000px;top:0;width:1px;visibility:hidden;pointer-events:none;';
        document.body.appendChild(probe);
        ['vh', 'svh', 'dvh'].forEach(function(unit) {
          probe.style.height = '100' + unit;
          units.push(unit + ':' + Math.round(probe.getBoundingClientRect().height));
        });
        probe.remove();
      }
      return 'instagram-layout-v4|state=' + state + '|ready=' + document.readyState +
        '|inputs=' + inputs.length + '|shown_inputs=' + shownInputs.length +
        '|text_length=' + textLength + '|body_height=' + (document.body ? document.body.clientHeight : 0) +
        '|viewport=' + Math.round(innerWidth) + ':' + Math.round(innerHeight) +
        '|input_rect=' + (rect ? [rect.x, rect.y, rect.width, rect.height].map(Math.round).join(':') : 'none') +
        '|viewport_units=' + units.join(',') + '|blocking=' + (after ? after.reason : 'no-input') +
        '|hit=' + (after ? after.hit : 'none') + '|repair=' + (repairs.join(',') || 'none') +
        '|before=' + (before ? before.chain : 'none') +
        '|after=' + (repairs.length && after ? after.chain : 'unchanged');
    })();
"""

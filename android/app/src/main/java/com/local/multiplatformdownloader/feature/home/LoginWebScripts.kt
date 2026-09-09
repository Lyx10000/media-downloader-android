package com.local.multiplatformdownloader.feature.home

internal const val X_LOGIN_VIEWPORT_SCRIPT = """
    (function() {
      try {
        var DIAGNOSTIC_INPUT_SELECTOR = 'input[type="text"], input[type="email"], ' +
          'input[type="tel"], input[type="password"], input:not([type]), textarea';

        function diagnosticSafe(value, limit) {
          return String(value || '').replace(/[|~^,]/g, '_').slice(0, limit);
        }

        function diagnosticRect(element) {
          var rect = element.getBoundingClientRect();
          return [rect.left, rect.top, rect.right, rect.bottom, rect.width, rect.height]
            .map(function(value) { return Math.round(value); }).join(':');
        }

        function diagnosticVisible(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity || 1) > 0 && rect.width > 20 && rect.height > 5;
        }

        var diagnosticInputs = document.querySelectorAll(DIAGNOSTIC_INPUT_SELECTOR);
        var diagnosticTarget = document.activeElement;
        if (!diagnosticTarget || !diagnosticTarget.matches ||
            !diagnosticTarget.matches(DIAGNOSTIC_INPUT_SELECTOR) ||
            !diagnosticVisible(diagnosticTarget)) {
          diagnosticTarget = null;
          for (var diagnosticIndex = 0; diagnosticIndex < diagnosticInputs.length;
              diagnosticIndex += 1) {
            if (diagnosticVisible(diagnosticInputs[diagnosticIndex])) {
              diagnosticTarget = diagnosticInputs[diagnosticIndex];
              break;
            }
          }
        }

        var diagnosticViewport = window.visualViewport;
        var diagnosticViewportHeight = Math.round(Math.max(
          diagnosticViewport ? diagnosticViewport.height : 0,
          window.innerHeight || 0,
          document.documentElement ? document.documentElement.clientHeight : 0));
        var diagnosticViewportText = [
          diagnosticViewport ? diagnosticViewport.offsetLeft : 0,
          diagnosticViewport ? diagnosticViewport.offsetTop : 0,
          diagnosticViewport ? diagnosticViewport.width : window.innerWidth,
          diagnosticViewport ? diagnosticViewport.height : window.innerHeight,
          diagnosticViewport ? diagnosticViewport.scale : 1,
        ].map(function(value) { return Math.round(Number(value) * 100) / 100; }).join(':');

        function diagnosticFindScroller(target) {
          if (!target) return null;
          var diagnosticScroller = target.closest('.jf-vscroller');
          if (!diagnosticScroller) {
            var diagnosticCandidate = target.parentElement;
            while (diagnosticCandidate && diagnosticCandidate !== document.body) {
              var diagnosticCandidateStyle = window.getComputedStyle(diagnosticCandidate);
              var diagnosticCandidateRect = diagnosticCandidate.getBoundingClientRect();
              var diagnosticCandidateScrollable =
                diagnosticCandidateStyle.overflowY === 'scroll' ||
                diagnosticCandidateStyle.overflowY === 'auto';
              if (diagnosticCandidateScrollable && diagnosticCandidateRect.height <= 64 &&
                  diagnosticCandidate.scrollHeight > diagnosticCandidate.clientHeight * 2) {
                diagnosticScroller = diagnosticCandidate;
                break;
              }
              diagnosticCandidate = diagnosticCandidate.parentElement;
            }
          }
          return diagnosticScroller;
        }

        function diagnosticRepairScroller(target) {
          if (!target) return 'not-checked';
          var diagnosticScroller = diagnosticFindScroller(target);
          if (!diagnosticScroller) return 'not-found';
          var diagnosticLiveViewport = window.visualViewport;
          diagnosticViewportHeight = Math.round(Math.max(
            diagnosticLiveViewport ? diagnosticLiveViewport.height : 0,
            window.innerHeight || 0,
            document.documentElement ? document.documentElement.clientHeight : 0));
          var diagnosticScrollerBefore = diagnosticScroller.getBoundingClientRect();
          diagnosticScroller.style.setProperty('top', '0px', 'important');
          diagnosticScroller.style.setProperty('bottom', 'auto', 'important');
          diagnosticScroller.style.setProperty(
            'height', diagnosticViewportHeight + 'px', 'important');
          diagnosticScroller.style.setProperty(
            'min-height', diagnosticViewportHeight + 'px', 'important');
          diagnosticScroller.style.setProperty(
            'max-height', diagnosticViewportHeight + 'px', 'important');
          diagnosticScroller.style.setProperty('box-sizing', 'border-box', 'important');
          diagnosticScroller.style.setProperty('overflow-y', 'auto', 'important');
          diagnosticScroller.style.setProperty(
            '-webkit-overflow-scrolling', 'touch', 'important');
          diagnosticScroller.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
          var diagnosticScrollerAfter = diagnosticScroller.getBoundingClientRect();
          return 'expanded-' + Math.round(diagnosticScrollerBefore.height) + '-' +
            Math.round(diagnosticScrollerAfter.height) + '-target-' + diagnosticViewportHeight;
        }

        var diagnosticScrollerState = diagnosticRepairScroller(diagnosticTarget);

        if (!window.__aggregateXScrollerWatcher) {
          var diagnosticWatcherPending = 0;
          function diagnosticWatcherTarget() {
            var active = document.activeElement;
            if (active && active.matches && active.matches(DIAGNOSTIC_INPUT_SELECTOR) &&
                diagnosticVisible(active)) {
              return active;
            }
            var candidates = document.querySelectorAll(DIAGNOSTIC_INPUT_SELECTOR);
            for (var candidateIndex = 0; candidateIndex < candidates.length; candidateIndex += 1) {
              if (diagnosticVisible(candidates[candidateIndex])) return candidates[candidateIndex];
            }
            return null;
          }
          function diagnosticWatcherRun() {
            var target = diagnosticWatcherTarget();
            diagnosticRepairScroller(target);
            if (target && document.activeElement === target) {
              window.requestAnimationFrame(function() {
                target.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'auto'});
              });
            }
          }
          function diagnosticWatcherSchedule() {
            window.clearTimeout(diagnosticWatcherPending);
            diagnosticWatcherPending = window.setTimeout(diagnosticWatcherRun, 80);
          }
          document.addEventListener('focusin', diagnosticWatcherSchedule, true);
          if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', diagnosticWatcherSchedule);
          }
          var diagnosticWatcherObserver = new MutationObserver(diagnosticWatcherSchedule);
          diagnosticWatcherObserver.observe(
            document.documentElement, {childList: true, subtree: true});
          window.__aggregateXScrollerWatcher = {
            schedule: diagnosticWatcherSchedule,
            observer: diagnosticWatcherObserver,
            marker: 'x-scroller-watch-v1',
          };
        } else {
          window.__aggregateXScrollerWatcher.schedule();
        }

        var diagnosticResult;
        if (!diagnosticTarget) {
          diagnosticResult = 'waiting-for-input|ready=' + document.readyState +
            '|inputs=' + diagnosticInputs.length + '|viewport=' + diagnosticViewportText +
            '|body=' + (document.body ? document.body.children.length : -1) +
            '|x-dom-diagnostic-v1';
        } else {
          var diagnosticAncestors = [];
          var diagnosticNode = diagnosticTarget;
          for (var diagnosticDepth = 0;
              diagnosticNode && diagnosticDepth < 10;
              diagnosticDepth += 1) {
            var diagnosticStyle = window.getComputedStyle(diagnosticNode);
            diagnosticAncestors.push([
              diagnosticDepth + ':' + diagnosticNode.tagName.toLowerCase(),
              'id=' + diagnosticSafe(diagnosticNode.id, 48),
              'class=' + diagnosticSafe(diagnosticNode.className, 100),
              'role=' + diagnosticSafe(diagnosticNode.getAttribute('role'), 32),
              'rect=' + diagnosticRect(diagnosticNode),
              'position=' + diagnosticStyle.position,
              'display=' + diagnosticStyle.display,
              'transform=' + diagnosticSafe(diagnosticStyle.transform, 96),
              'overflow=' + diagnosticStyle.overflowX + '/' + diagnosticStyle.overflowY,
              'margin=' + diagnosticStyle.marginTop + '/' + diagnosticStyle.marginBottom,
              'padding=' + diagnosticStyle.paddingTop + '/' + diagnosticStyle.paddingBottom,
              'scroll=' + diagnosticNode.scrollTop + '/' + diagnosticNode.scrollHeight +
                '/' + diagnosticNode.clientHeight,
            ].join('~'));
            diagnosticNode = diagnosticNode.parentElement;
          }
          diagnosticResult = 'input-found|ready=' + document.readyState +
            '|viewport=' + diagnosticViewportText +
            '|type=' + diagnosticSafe(diagnosticTarget.getAttribute('type'), 24) +
            '|name=' + diagnosticSafe(diagnosticTarget.getAttribute('name'), 48) +
            '|autocomplete=' + diagnosticSafe(
              diagnosticTarget.getAttribute('autocomplete'), 48) +
            '|ancestors=' + diagnosticAncestors.join('^') + '|x-dom-diagnostic-v1';
        }
        diagnosticResult += '|vscroller=' + diagnosticScrollerState +
          '|watcher=x-scroller-watch-v1|x-vscroller-fix-v1';
        return diagnosticResult;

        if (window.__aggregateXLoginAssist &&
            typeof window.__aggregateXLoginAssist.run === 'function') {
          return window.__aggregateXLoginAssist.run();
        }
        var pending = 0;
        var dialogState = 'not-checked';
        var INPUT_SELECTOR = 'input[type="text"], input[type="email"], input[type="tel"], ' +
          'input[type="password"], input:not([type]), textarea';

        function visible(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity || 1) > 0 && rect.width > 80 && rect.height > 20;
        }

        function currentInput() {
          var active = document.activeElement;
          if (active && active.matches &&
              active.matches(INPUT_SELECTOR) && visible(active)) {
            return active;
          }
          var inputs = document.querySelectorAll(INPUT_SELECTOR);
          for (var i = 0; i < inputs.length; i += 1) {
            if (visible(inputs[i])) return inputs[i];
          }
          return null;
        }

        function centerScrollableParents(target) {
          var parent = target.parentElement;
          while (parent && parent !== document.body && parent !== document.documentElement) {
            if (parent.scrollHeight > parent.clientHeight + 8) {
              var parentRect = parent.getBoundingClientRect();
              var targetRect = target.getBoundingClientRect();
              parent.scrollTop += targetRect.top - parentRect.top -
                (parent.clientHeight - targetRect.height) / 2;
            }
            parent = parent.parentElement;
          }
        }

        function stabilizeLoginDialog(target) {
          var viewport = window.visualViewport;
          var viewportTop = viewport ? viewport.offsetTop : 0;
          var viewportHeight = viewport ? viewport.height : window.innerHeight;
          var targetRect = target.getBoundingClientRect();
          var safeTop = viewportTop + 24;
          var safeBottom = viewportTop + viewportHeight - 24;
          if (targetRect.top >= safeTop && targetRect.bottom <= safeBottom) {
            dialogState = 'in-bounds';
            return false;
          }
          var dialog = target.closest('[role="dialog"]');
          var fallback = false;
          var fallbackPosition = '';
          if (!dialog) {
            var ancestor = target.parentElement;
            while (ancestor && ancestor !== document.body) {
              var ancestorStyle = window.getComputedStyle(ancestor);
              var position = ancestorStyle.position;
              if (position === 'fixed') {
                dialog = ancestor;
                break;
              }
              var ancestorRect = ancestor.getBoundingClientRect();
              var transformed = ancestorStyle.transform && ancestorStyle.transform !== 'none';
              var clipped = ancestorStyle.overflowY === 'hidden' ||
                ancestorStyle.overflowY === 'clip';
              var offscreen = ancestorRect.top < safeTop &&
                ancestorRect.bottom > viewportTop && ancestorRect.height >= targetRect.height;
              if (!dialog && ancestorRect.width >= targetRect.width &&
                  ancestorRect.height >= targetRect.height &&
                  (transformed || position === 'absolute' || position === 'sticky' || clipped ||
                    offscreen)) {
                dialog = ancestor;
                fallback = true;
                fallbackPosition = transformed ? 'transform' :
                  (clipped ? 'clipped' : (offscreen ? 'offscreen' : position));
              }
              ancestor = ancestor.parentElement;
            }
          }
          if (!dialog) {
            dialogState = 'container-missing';
            return false;
          }
          if (fallback) {
            var desiredTop = viewportTop + (viewportHeight - targetRect.height) / 2;
            var previousShift = Number(dialog.getAttribute('data-aggregate-shift-y') || 0);
            var delta = desiredTop - targetRect.top;
            var nextShift = Math.max(-viewportHeight, Math.min(viewportHeight, previousShift + delta));
            dialog.style.setProperty('translate', '0px ' + nextShift + 'px', 'important');
            dialog.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
            dialog.setAttribute('data-aggregate-shift-y', String(nextShift));
            dialogState = fallbackPosition === 'offscreen' ?
              'shifted-offscreen' : 'shifted-' + fallbackPosition;
            return true;
          }
          dialog.style.setProperty('position', 'fixed', 'important');
          dialog.style.setProperty('top', viewportTop + 'px', 'important');
          dialog.style.setProperty('bottom', 'auto', 'important');
          dialog.style.setProperty('left', '0px', 'important');
          dialog.style.setProperty('right', 'auto', 'important');
          dialog.style.setProperty('width', '100%', 'important');
          dialog.style.setProperty('height', viewportHeight + 'px', 'important');
          dialog.style.setProperty('max-height', viewportHeight + 'px', 'important');
          dialog.style.setProperty('margin', '0px', 'important');
          dialog.style.setProperty('transform', 'none', 'important');
          dialog.style.setProperty('overflow-y', 'auto', 'important');
          dialog.style.setProperty('overscroll-behavior-y', 'contain', 'important');
          dialog.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');
          dialog.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
          dialogState = 'adjusted';
          return true;
        }

        function centerInput() {
          var target = currentInput();
          if (!target) return false;
          target.style.setProperty('scroll-margin-top', '96px', 'important');
          target.style.setProperty('scroll-margin-bottom', '96px', 'important');
          stabilizeLoginDialog(target);
          centerScrollableParents(target);
          target.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'auto'});
          window.requestAnimationFrame(function() {
            var viewport = window.visualViewport;
            var viewportTop = viewport ? viewport.offsetTop : 0;
            var viewportHeight = viewport ? viewport.height : window.innerHeight;
            var rect = target.getBoundingClientRect();
            var desiredTop = viewportTop + (viewportHeight - rect.height) / 2;
            if (rect.top < viewportTop + 24 || rect.bottom > viewportTop + viewportHeight - 24) {
              window.scrollBy(0, rect.top - desiredTop);
              centerScrollableParents(target);
            }
          });
          return true;
        }

        function scheduleCenter() {
          window.clearTimeout(pending);
          pending = window.setTimeout(centerInput, 120);
          window.setTimeout(centerInput, 420);
          window.setTimeout(centerInput, 780);
        }

        document.addEventListener('focusin', function(event) {
          if (event.target && event.target.matches &&
              event.target.matches(INPUT_SELECTOR)) {
            scheduleCenter();
          }
        }, true);

        if (window.visualViewport) {
          window.visualViewport.addEventListener('resize', scheduleCenter);
        }

        var observer = new MutationObserver(scheduleCenter);
        observer.observe(document.documentElement, {childList: true, subtree: true});
        window.setTimeout(function() { observer.disconnect(); }, 30000);

        function runAndReport() {
          dialogState = 'not-checked';
          var centered = centerInput();
          var target = currentInput();
          var viewport = window.visualViewport;
          var viewportTop = viewport ? viewport.offsetTop : 0;
          var viewportHeight = viewport ? viewport.height : window.innerHeight;
          var geometry = 'none';
          if (target) {
            var rect = target.getBoundingClientRect();
            geometry = Math.round(rect.top) + ',' + Math.round(rect.bottom) + ',' +
              Math.round(viewportTop) + ',' + Math.round(viewportHeight);
          }
          return (centered ? 'input-centered' : 'waiting-for-input') +
            '|dialog=' + dialogState + '|target=' + geometry + '|x-layout-v6';
        }

        window.__aggregateXLoginAssist = {run: runAndReport};

        var attempts = 0;
        var timer = window.setInterval(function() {
          attempts += 1;
          if (centerInput() || attempts >= 20) window.clearInterval(timer);
        }, 300);
        return runAndReport();
      } catch (error) {
        return 'failed';
      }
    })();
"""

internal const val DOUYIN_LOGIN_VIEWPORT_SCRIPT = """
    (function() {
      try {
        function visible(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            rect.width > 120 && rect.height > 80;
        }

        function score(element) {
          if (!visible(element)) return -1;
          var value = 0;
          var role = (element.getAttribute('role') || '').toLowerCase();
          var className = String(element.className || '').toLowerCase();
          var text = (element.innerText || '').slice(0, 500);
          if (role === 'dialog') value += 80;
          if (className.indexOf('login') >= 0 || className.indexOf('passport') >= 0) value += 30;
          if (element.querySelector('input')) value += 100;
          if (element.querySelector('iframe')) value += 55;
          if (element.querySelector('canvas, img')) value += 15;
          if (text.indexOf('登录') >= 0 || text.indexOf('验证码') >= 0 || text.indexOf('扫码') >= 0) {
            value += 35;
          }
          return value;
        }

        function centerLogin() {
          var selectors = [
            '[role="dialog"]',
            '[class*="login"]',
            '[class*="Login"]',
            '[class*="passport"]',
            '[class*="Passport"]'
          ];
          var candidates = [];
          selectors.forEach(function(selector) {
            document.querySelectorAll(selector).forEach(function(element) {
              if (candidates.indexOf(element) < 0) candidates.push(element);
            });
          });
          var target = null;
          var best = 69;
          candidates.forEach(function(element) {
            var candidateScore = score(element);
            if (candidateScore > best) {
              best = candidateScore;
              target = element;
            }
          });
          if (!target) return false;
          target.scrollIntoView({block: 'center', inline: 'center', behavior: 'auto'});
          window.requestAnimationFrame(function() {
            var rect = target.getBoundingClientRect();
            window.scrollBy(
              rect.left + rect.width / 2 - window.innerWidth / 2,
              rect.top + rect.height / 2 - window.innerHeight / 2
            );
          });
          return true;
        }

        function visibleControl(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity || 1) > 0 && rect.width >= 32 && rect.height >= 24;
        }

        function openLogin() {
          var controls = document.querySelectorAll('button, a, [role="button"]');
          for (var i = 0; i < controls.length; i += 1) {
            var text = (controls[i].innerText || controls[i].textContent || '')
              .trim().replace(/\s+/g, '');
            if (visibleControl(controls[i]) &&
                (text === '登录' || text === '登录/注册' || text === '注册/登录')) {
              controls[i].click();
              return true;
            }
          }
          return false;
        }

        var clicked = false;
        function prepareLogin() {
          if (centerLogin()) return true;
          if (!clicked) clicked = openLogin();
          return false;
        }

        if (prepareLogin()) return 'centered';
        var attempts = 0;
        var timer = window.setInterval(function() {
          attempts += 1;
          if (prepareLogin() || attempts >= 20) window.clearInterval(timer);
        }, 500);
        return clicked ? 'login-clicked' : 'waiting-for-login-control';
      } catch (error) {
        return 'failed';
      }
    })();
"""

internal const val XHS_LOGIN_VIEWPORT_SCRIPT = """
    (function() {
      try {
        function visible(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity || 1) > 0 && rect.width > 40 && rect.height > 24;
        }

        function unlockScrolling(element, constrainHeight) {
          if (!element) return;
          element.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
          element.style.setProperty('overscroll-behavior-y', 'auto', 'important');
          element.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');
          if (constrainHeight) {
            element.style.setProperty('max-height', 'calc(100vh - 32px)', 'important');
            element.style.setProperty('overflow-y', 'auto', 'important');
          }
        }

        function unlockPageScrolling() {
          unlockScrolling(document.documentElement, false);
          unlockScrolling(document.body, false);
          if (document.documentElement) {
            document.documentElement.style.setProperty('overflow-y', 'auto', 'important');
          }
          if (document.body) {
            document.body.style.setProperty('overflow-y', 'auto', 'important');
          }
        }

        function unlockPanelScrolling(element) {
          var current = element;
          var levels = 0;
          while (current && current !== document.body && levels < 5) {
            unlockScrolling(current, levels < 2);
            current = current.parentElement;
            levels += 1;
          }
        }

        unlockPageScrolling();

        function center(element) {
          if (!visible(element)) return false;
          unlockPanelScrolling(element);
          element.scrollIntoView({block: 'center', inline: 'center', behavior: 'auto'});
          window.requestAnimationFrame(function() {
            var rect = element.getBoundingClientRect();
            window.scrollBy(
              rect.left + rect.width / 2 - window.innerWidth / 2,
              rect.top + rect.height / 2 - window.innerHeight / 2
            );
          });
          return true;
        }

        function isCaptchaPage() {
          return /\/website-login\/captcha(?:\/|$)/i.test(window.location.pathname || '');
        }

        function revealCaptcha() {
          var root = document.documentElement;
          var body = document.body;
          if (root) {
            root.style.setProperty('overflow-y', 'auto', 'important');
            root.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
            root.style.setProperty('height', 'auto', 'important');
            root.style.setProperty('min-height', '100%', 'important');
          }
          if (body) {
            body.style.setProperty('overflow-y', 'auto', 'important');
            body.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
            body.style.setProperty('height', 'auto', 'important');
            body.style.setProperty('min-height', '100vh', 'important');
            body.style.setProperty('padding-top', '16px', 'important');
            body.style.setProperty('box-sizing', 'border-box', 'important');
          }
          window.scrollTo(0, 0);

          var elements = document.querySelectorAll('body *');
          var anchor = null;
          for (var i = 0; i < elements.length; i += 1) {
            var text = (elements[i].innerText || '').trim();
            if (visible(elements[i]) && (text === '刷新' || text === '问题反馈')) {
              anchor = elements[i];
              break;
            }
          }
          if (!anchor) return false;

          var panel = anchor;
          var candidate = anchor;
          while (candidate && candidate !== body) {
            var rect = candidate.getBoundingClientRect();
            var candidateText = (candidate.innerText || '').trim();
            if (rect.width >= 240 && rect.height >= 160 && candidateText.length < 1200) {
              panel = candidate;
            }
            candidate = candidate.parentElement;
          }
          var panelRect = panel.getBoundingClientRect();
          unlockPanelScrolling(panel);
          if (panelRect.top < 16) {
            panel.style.setProperty(
              'translate',
              '0px ' + Math.ceil(16 - panelRect.top) + 'px',
              'important'
            );
          }
          panel.scrollIntoView({block: 'start', inline: 'center', behavior: 'auto'});
          window.scrollBy(0, -16);
          return true;
        }

        function findLoginPanel() {
          var input = document.querySelector(
            'input[type="tel"], input[placeholder*="手机号"], input[placeholder*="手机号码"]'
          );
          if (visible(input)) {
            return input.closest('[role="dialog"], [class*="login"], [class*="Login"], form') || input;
          }
          var selectors = [
            '[role="dialog"]',
            '[class*="login-container"]',
            '[class*="login-modal"]',
            '[class*="login-content"]',
            '[class*="Login"]'
          ];
          for (var i = 0; i < selectors.length; i += 1) {
            var elements = document.querySelectorAll(selectors[i]);
            for (var j = 0; j < elements.length; j += 1) {
              var text = (elements[j].innerText || '').slice(0, 500);
              if (visible(elements[j]) &&
                  (text.indexOf('手机号') >= 0 || text.indexOf('验证码') >= 0 ||
                   text.indexOf('扫码') >= 0)) {
                return elements[j];
              }
            }
          }
          return null;
        }

        function openLogin() {
          var elements = document.querySelectorAll('button, a, [role="button"]');
          for (var i = 0; i < elements.length; i += 1) {
            var text = (elements[i].innerText || '').trim().replace(/\s+/g, '');
            if (visible(elements[i]) &&
                (text === '登录' || text.indexOf('登录探索更多内容') >= 0)) {
              elements[i].click();
              return true;
            }
          }
          return false;
        }

        var attempts = 0;
        if (isCaptchaPage()) {
          if (revealCaptcha()) return 'captcha-visible';
          var captchaTimer = window.setInterval(function() {
            attempts += 1;
            if (revealCaptcha() || attempts >= 24) window.clearInterval(captchaTimer);
          }, 500);
          return 'captcha-waiting';
        }

        var clicked = false;
        function prepareLogin() {
          var panel = findLoginPanel();
          if (panel && center(panel)) return true;
          if (!clicked) clicked = openLogin();
          return false;
        }

        if (prepareLogin()) return 'centered';
        var timer = window.setInterval(function() {
          attempts += 1;
          if (prepareLogin() || attempts >= 24) window.clearInterval(timer);
        }, 500);
        return 'waiting';
      } catch (error) {
        return 'failed';
      }
    })();
"""

internal const val PAGE_SNAPSHOT_SCRIPT = """
    (function() {
      var path = window.location.pathname || '';
      var answerMatch = path.match(/\/answer\/(\d+)/);
      var root = null;
      var content = null;
      if (answerMatch) {
        var answerId = answerMatch[1];
        root = document.querySelector('[data-answer-id="' + answerId + '"], #answer-' + answerId);
        if (!root) {
          var answers = Array.prototype.slice.call(document.querySelectorAll('.AnswerItem'));
          root = answers.find(function(item) {
            return (item.getAttribute('data-zop') || '').indexOf(answerId) >= 0 ||
              (item.getAttribute('name') || '') === answerId;
          }) || answers[0] || null;
        }
        content = root && root.querySelector('.RichContent-inner, .RichText');
      } else if (/\/pin\//.test(path)) {
        root = document.querySelector('.PinItem, .Pin-content, main');
        content = root && root.querySelector('.RichContent-inner, .RichText, .PinItem-content, .Pin-content');
      } else {
        root = document.querySelector('.Post-Main, article, main');
        content = document.querySelector('.Post-RichTextContainer .RichText, .Post-RichTextContainer, article .RichText');
      }
      var state = document.querySelector('script#js-initialData, script#__NEXT_DATA__');
      var initialData = state ? (state.textContent || '') : '';
      if (!initialData && window.__INITIAL_STATE__) {
        try { initialData = JSON.stringify(window.__INITIAL_STATE__); } catch (_) {}
      }
      var isXiaohongshu = /(^|\.)xiaohongshu\.com$/i.test(window.location.hostname || '');
      var targetNoteMatch = path.match(
        /\/(?:explore|discovery\/item|note)\/([0-9a-zA-Z_-]+)/i
      );
      var targetNoteId = targetNoteMatch ? targetNoteMatch[1] : '';
      if (isXiaohongshu) {
        try {
          var stateCandidates = [];
          function addStateCandidate(value) {
            if (!value || typeof value !== 'string' || value.length > 5000000) return;
            stateCandidates.push(value);
          }
          addStateCandidate(initialData);
          if (window.__INITIAL_STATE__) {
            try { addStateCandidate(JSON.stringify(window.__INITIAL_STATE__)); } catch (_) {}
          }
          Array.prototype.forEach.call(document.scripts || [], function(script) {
            var text = script.textContent || '';
            if (text.indexOf('__INITIAL_STATE__') >= 0 ||
                (targetNoteId && text.indexOf(targetNoteId) >= 0)) {
              addStateCandidate(text);
            }
          });
          stateCandidates.sort(function(left, right) {
            function score(value) {
              return (targetNoteId && value.indexOf(targetNoteId) >= 0 ? 10000000 : 0) +
                value.length;
            }
            return score(right) - score(left);
          });
          if (stateCandidates.length) initialData = stateCandidates[0];
        } catch (_) {}
      }
      if ((!initialData || (targetNoteId && initialData.indexOf(targetNoteId) < 0)) &&
          isXiaohongshu) {
        try {
          var seenNotes = {};
          var notes = [];
          var links = document.querySelectorAll(
            'a[href*="/explore/"], a[href*="/discovery/item/"]'
          );
          Array.prototype.forEach.call(links, function(link) {
            var href = link.href || link.getAttribute('href') || '';
            var match = href.match(/\/(?:explore|discovery\/item)\/([0-9a-f]{24})/i);
            if (!match || seenNotes[match[1]]) return;
            seenNotes[match[1]] = true;
            var card = link.closest(
              '[class*="note-item"], [class*="noteItem"], article, li, section'
            ) || link.parentElement || link;
            var image = card.querySelector('img') || link.querySelector('img');
            var titleNode = card.querySelector(
              '[class*="title"], [class*="desc"], [class*="content"]'
            );
            var token = '';
            try { token = new URL(href, window.location.href).searchParams.get('xsec_token') || ''; }
            catch (_) {}
            notes.push({ noteCard: {
              noteId: match[1],
              displayTitle: titleNode ? (titleNode.textContent || '').trim() :
                (image ? (image.alt || '').trim() : ''),
              type: card.querySelector('video, [class*="play"], [class*="video"]') ?
                'video' : 'normal',
              xsecToken: token,
              cover: { urlDefault: image ?
                (image.currentSrc || image.src || image.getAttribute('data-src') || '') : '' }
            }});
          });
          if (notes.length) {
            initialData = JSON.stringify({ user: { userPageData: { notes: notes } } });
          }
        } catch (_) {}
      }
      var titleNode = document.querySelector('h1.Post-Title, .QuestionHeader-title, article h1, main h1, h1');
      var authorNode = root && root.querySelector('.AuthorInfo-name, [itemprop="name"], .UserLink-link');
      return JSON.stringify({
        finalUrl: window.location.href || '',
        initialData: initialData,
        title: titleNode ? (titleNode.textContent || '').trim() : (document.title || '').trim(),
        author: authorNode ? (authorNode.textContent || '').trim() : '',
        contentHtml: content ? (content.innerHTML || '') : '',
        visibleText: document.body ? (document.body.innerText || '').slice(0, 8000) : ''
      });
    })();
"""
internal const val XHS_CREDENTIAL_SNAPSHOT_SCRIPT = """
    (function() {
      var path = window.location.pathname || '';
      var bodyText = document.body ? (document.body.innerText || '') : '';
      var accountLink = document.querySelector(
        'header a[href*="/user/profile/"], nav a[href*="/user/profile/"], ' +
        '[class*="side-bar"] a[href*="/user/profile/"], ' +
        '[class*="sidebar"] a[href*="/user/profile/"], ' +
        '[class*="user-container"] a[href*="/user/profile/"]'
      );
      var loginControl = document.querySelector(
        'input[type="tel"], input[placeholder*="手机号"], ' +
        '[class*="login-container"], [class*="login-modal"], [class*="login-btn"]'
      );
      var stateText = '';
      if (window.__INITIAL_STATE__) {
        try { stateText = JSON.stringify(window.__INITIAL_STATE__); } catch (_) {}
      }
      var stateLoggedIn = /"guest"\s*:\s*false/i.test(stateText) &&
        /"user_?[iI]d"\s*:\s*"[^"\s]+"/.test(stateText);
      var challengeRequired = /\/website-login\/captcha(?:\/|$)/i.test(path) ||
        /请完成.{0,12}验证|安全验证/.test(bodyText.slice(0, 2000));
      var loginPrompt = /\/login(?:\/|$)/i.test(path) || !!loginControl ||
        /登录探索更多内容|手机号登录|扫码登录/.test(bodyText.slice(0, 4000));
      return JSON.stringify({
        finalUrl: window.location.href || '',
        initialData: JSON.stringify({
          loggedIn: !!accountLink || stateLoggedIn,
          challengeRequired: challengeRequired,
          loginPrompt: loginPrompt
        }),
        title: document.title || '',
        author: '',
        contentHtml: '',
        visibleText: bodyText.slice(0, 500)
      });
    })();
"""

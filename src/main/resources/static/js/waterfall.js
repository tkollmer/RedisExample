// Redis/service node control — defined globally before IIFE so onclick handlers always work
function redisAction(action, node) {
    var url;
    if (action === 'stop-all') {
        url = '/api/redis/stop-all';
    } else if (action === 'start-all') {
        url = '/api/redis/start-all';
    } else {
        url = '/api/redis/' + action + '/' + node;
    }
    var btns = document.querySelectorAll('.node-btn');
    btns.forEach(function (b) { b.disabled = true; b.style.opacity = '0.5'; });
    fetch(url, { method: 'POST' })
        .then(function (r) { return r.json(); })
        .then(function (data) { console.log('Action ' + action + ':', data); })
        .catch(function (e) { console.error('Action failed:', e); })
        .finally(function () {
            btns.forEach(function (b) { b.disabled = false; b.style.opacity = '1'; });
        });
}

(function () {
    'use strict';

    let MAX_WIDTH = 10000;
    let RETENTION_MS = 30000;

    const canvas = document.getElementById('waterfall');
    const ctx = canvas.getContext('2d');
    const mCanvas = document.getElementById('merged-waterfall');
    const mCtx = mCanvas.getContext('2d');

    // State
    let blocks = [];
    let detectorPos = 0;
    let detectorWidth = 200;
    let detectorTimeWin = 1000;
    let entityEvents = [];
    let mergeEvents = [];
    let redisEntities = [];
    let connected = false;
    let paused = false;
    let frameCount = 0;
    let lastFpsTime = performance.now();
    let hoveredBlock = null;
    let hoveredEntity = null;
    let mouseX = 0, mouseY = 0;
    let mouseCanvas = null;

    // DOM refs
    const blockCountEl = document.getElementById('block-count');
    const entityCountEl = document.getElementById('entity-count');
    const queueDepthEl = document.getElementById('queue-depth');
    const detectorPosEl = document.getElementById('detector-pos');
    const wsStatusEl = document.getElementById('ws-status');
    const fpsCounterEl = document.getElementById('fps-counter');
    const entityListEl = document.getElementById('entity-list');
    const mergedCountEl = document.getElementById('merged-count');
    const mergeEventsListEl = document.getElementById('merge-events-list');
    const mergedCardsEl = document.getElementById('merged-cards');
    const instanceRoleLabel = document.getElementById('instance-role-label');

    // Color map
    const COLOR_CSS = {
        blue:   { fill: 'rgb(30, 80, 255)',  hex: '#1e50ff' },
        cyan:   { fill: 'rgb(0, 200, 220)',  hex: '#00c8dc' },
        green:  { fill: 'rgb(0, 200, 60)',   hex: '#00c83c' },
        yellow: { fill: 'rgb(220, 200, 0)',  hex: '#dcc800' },
        red:    { fill: 'rgb(230, 50, 30)',  hex: '#e6321e' },
    };
    function blockColor(block) {
        var c = block.color && COLOR_CSS[block.color];
        return c ? c.fill : 'rgb(0,200,60)';
    }
    function colorHex(name) {
        var c = COLOR_CSS[name];
        return c ? c.hex : '#0f0';
    }
    function colorFill(name) {
        var c = COLOR_CSS[name];
        return c ? c.fill : 'rgb(0,200,60)';
    }

    // Resize both canvases to match their containers
    function resize() {
        var c1 = document.getElementById('waterfall-container');
        canvas.width = c1.clientWidth;
        canvas.height = c1.clientHeight;
        var c2 = document.getElementById('merged-container');
        mCanvas.width = c2.clientWidth;
        mCanvas.height = c2.clientHeight;
    }
    window.addEventListener('resize', resize);
    resize();

    // Shared grid/axis drawing
    function drawGrid(c, w, h, scaleX, scaleY, bg) {
        c.fillStyle = bg;
        c.fillRect(0, 0, w, h);
        c.strokeStyle = '#1a1a1a';
        c.lineWidth = 1;
        var gxStep = MAX_WIDTH <= 2000 ? 100 : MAX_WIDTH <= 10000 ? 500 : 2000;
        for (var x = 0; x < MAX_WIDTH; x += gxStep) {
            var px = x * scaleX;
            c.beginPath(); c.moveTo(px, 0); c.lineTo(px, h); c.stroke();
        }
        var gtStep = RETENTION_MS <= 15000 ? 1000 : RETENTION_MS <= 60000 ? 5000 : 10000;
        for (var t = 0; t < RETENTION_MS; t += gtStep) {
            var py = t * scaleY;
            c.beginPath(); c.moveTo(0, py); c.lineTo(w, py); c.stroke();
        }
        c.fillStyle = '#444';
        c.font = '10px Courier New';
        var wStep = MAX_WIDTH <= 2000 ? 200 : MAX_WIDTH <= 10000 ? 1000 : 5000;
        for (var x2 = 0; x2 <= MAX_WIDTH; x2 += wStep) {
            c.fillText(x2.toString(), x2 * scaleX + 2, h - 4);
        }
        var totalSec = Math.ceil(RETENTION_MS / 1000);
        var tStep = totalSec <= 15 ? 1 : totalSec <= 60 ? 5 : 10;
        for (var s = 0; s <= totalSec; s += tStep) {
            c.fillText('-' + s + 's', 4, s * 1000 * scaleY + 12);
        }
    }

    // === RENDER LOOP ===
    function render() {
        var now = Date.now();

        var w = canvas.width, h = canvas.height;
        var scaleX = w / MAX_WIDTH, scaleY = h / RETENTION_MS;

        drawGrid(ctx, w, h, scaleX, scaleY, '#0a0a0a');

        for (var i = 0; i < blocks.length; i++) {
            var block = blocks[i];
            var age = now - block.startTime;
            var endAge = now - block.endTime;
            var y1 = Math.max(0, age * scaleY);
            var y2 = Math.max(0, endAge * scaleY);
            var yTop = Math.min(y1, y2), yBot = Math.max(y1, y2);
            if (yTop > h || yBot < 0) continue;
            var x = block.widthStart * scaleX;
            var bw = (block.widthEnd - block.widthStart) * scaleX;
            var bh = Math.max(2, yBot - yTop);
            ctx.fillStyle = blockColor(block);
            ctx.globalAlpha = 0.8;
            ctx.fillRect(x, yTop, bw, bh);
            ctx.globalAlpha = 0.4;
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 0.5;
            ctx.strokeRect(x, yTop, bw, bh);
        }
        ctx.globalAlpha = 1.0;

        var dx = detectorPos * scaleX, dw = detectorWidth * scaleX;
        ctx.fillStyle = 'rgba(0, 255, 0, 0.06)';
        ctx.fillRect(dx, 0, dw, h);
        ctx.strokeStyle = 'rgba(0, 255, 0, 0.4)';
        ctx.lineWidth = 2;
        ctx.setLineDash([6, 4]);
        ctx.strokeRect(dx, 0, dw, h);
        ctx.setLineDash([]);
        ctx.fillStyle = 'rgba(0, 255, 0, 0.7)';
        ctx.font = '11px Courier New';
        ctx.fillText('DETECTOR', dx + 4, 16);

        var twH = detectorTimeWin * scaleY;
        ctx.fillStyle = 'rgba(0, 255, 0, 0.04)';
        ctx.fillRect(0, 0, w, twH);
        ctx.strokeStyle = 'rgba(0, 255, 0, 0.3)';
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 4]);
        ctx.beginPath(); ctx.moveTo(0, twH); ctx.lineTo(w, twH); ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = 'rgba(0, 255, 0, 0.5)';
        ctx.font = '9px Courier New';
        ctx.fillText('TIME WINDOW ' + detectorTimeWin + 'ms', w - 160, twH - 4);

        // --- Merged waterfall ---
        var mw = mCanvas.width, mh = mCanvas.height;
        var mScaleX = mw / MAX_WIDTH, mScaleY = mh / RETENTION_MS;

        drawGrid(mCtx, mw, mh, mScaleX, mScaleY, '#060610');

        for (var j = 0; j < redisEntities.length; j++) {
            var e = redisEntities[j];
            var st = Number(e.startTime) || 0;
            var et = Number(e.endTime) || 0;
            var ws = Number(e.widthStart) || 0;
            var we = Number(e.widthEnd) || 0;
            var col = e.color || 'green';

            var eAge = now - st;
            var eEndAge = now - et;
            var ey1 = Math.max(0, eAge * mScaleY);
            var ey2 = Math.max(0, eEndAge * mScaleY);
            var eyTop = Math.min(ey1, ey2), eyBot = Math.max(ey1, ey2);
            if (eyTop > mh || eyBot < 0) continue;

            var ex = ws * mScaleX;
            var ebw = Math.max(2, (we - ws) * mScaleX);
            var ebh = Math.max(2, eyBot - eyTop);

            mCtx.fillStyle = colorFill(col);
            mCtx.globalAlpha = 0.7;
            mCtx.fillRect(ex, eyTop, ebw, ebh);
            mCtx.globalAlpha = 0.5;
            mCtx.strokeStyle = colorHex(col);
            mCtx.lineWidth = 1.5;
            mCtx.strokeRect(ex, eyTop, ebw, ebh);

            mCtx.globalAlpha = 0.9;
            mCtx.fillStyle = '#fff';
            mCtx.font = '9px Courier New';
            if (ebw > 50 && ebh > 12) {
                mCtx.fillText(e.entityId, ex + 3, eyTop + 10);
            }
        }
        mCtx.globalAlpha = 1.0;

        if (redisEntities.length === 0) {
            mCtx.fillStyle = '#333';
            mCtx.font = '12px Courier New';
            mCtx.fillText('No merged entities in Redis', 20, mh / 2);
        }

        if (mouseCanvas === canvas && hoveredBlock) {
            drawTooltip(ctx, mouseX, mouseY, hoveredBlock, 'block');
        }
        if (mouseCanvas === mCanvas && hoveredEntity) {
            drawTooltip(mCtx, mouseX, mouseY, hoveredEntity, 'entity');
        }

        frameCount++;
        var elapsed = performance.now() - lastFpsTime;
        if (elapsed > 1000) {
            fpsCounterEl.textContent = Math.round(frameCount * 1000 / elapsed) + ' fps';
            frameCount = 0;
            lastFpsTime = performance.now();
        }

        requestAnimationFrame(render);
    }

    // === Tooltip ===
    function drawTooltip(c, mx, my, item, kind) {
        var lines = [];
        if (kind === 'block') {
            lines.push('ID: ' + item.id);
            lines.push('Color: ' + (item.color || '?'));
            lines.push('Width: ' + Math.round(item.widthStart) + ' - ' + Math.round(item.widthEnd));
            lines.push('Amp: ' + (item.amplitude ? item.amplitude.toFixed(3) : '?'));
            lines.push('Start: ' + new Date(item.startTime).toLocaleTimeString());
            lines.push('End: ' + new Date(item.endTime).toLocaleTimeString());
            var dur = ((item.endTime - item.startTime) / 1000).toFixed(1);
            lines.push('Duration: ' + dur + 's');
        } else {
            lines.push('Entity: ' + item.entityId);
            lines.push('Color: ' + (item.color || '?'));
            lines.push('Width: ' + Math.round(Number(item.widthStart)) + ' - ' + Math.round(Number(item.widthEnd)));
            lines.push('Amp: ' + Number(item.amplitude).toFixed(3));
            lines.push('Start: ' + new Date(Number(item.startTime)).toLocaleTimeString());
            lines.push('End: ' + new Date(Number(item.endTime)).toLocaleTimeString());
            var dur2 = ((Number(item.endTime) - Number(item.startTime)) / 1000).toFixed(1);
            lines.push('Duration: ' + dur2 + 's');
            lines.push('Detected: ' + new Date(Number(item.detectionTime)).toLocaleTimeString());
        }
        var lineH = 14, pad = 8;
        var tw = 0;
        c.font = '11px Courier New';
        for (var i = 0; i < lines.length; i++) {
            tw = Math.max(tw, c.measureText(lines[i]).width);
        }
        var bw = tw + pad * 2, bh = lines.length * lineH + pad * 2;
        var tx = mx + 12, ty = my - bh / 2;
        if (tx + bw > c.canvas.width) tx = mx - bw - 12;
        if (ty < 0) ty = 4;
        if (ty + bh > c.canvas.height) ty = c.canvas.height - bh - 4;
        c.fillStyle = 'rgba(0,0,0,0.9)';
        c.fillRect(tx, ty, bw, bh);
        c.strokeStyle = '#0ff';
        c.lineWidth = 1;
        c.strokeRect(tx, ty, bw, bh);
        c.fillStyle = '#eee';
        c.font = '11px Courier New';
        for (var j = 0; j < lines.length; j++) {
            c.fillText(lines[j], tx + pad, ty + pad + (j + 1) * lineH - 2);
        }
    }

    // Hit-test
    function hitTestBlock(mx, my) {
        var now = Date.now();
        var w = canvas.width, h = canvas.height;
        var scaleX = w / MAX_WIDTH, scaleY = h / RETENTION_MS;
        for (var i = blocks.length - 1; i >= 0; i--) {
            var b = blocks[i];
            var age = now - b.startTime;
            var endAge = now - b.endTime;
            var y1 = Math.max(0, age * scaleY);
            var y2 = Math.max(0, endAge * scaleY);
            var yTop = Math.min(y1, y2), yBot = Math.max(y1, y2);
            var x = b.widthStart * scaleX;
            var bw = (b.widthEnd - b.widthStart) * scaleX;
            if (mx >= x && mx <= x + bw && my >= yTop && my <= yBot) return b;
        }
        return null;
    }
    function hitTestEntity(mx, my) {
        var now = Date.now();
        var mw = mCanvas.width, mh = mCanvas.height;
        var scaleX = mw / MAX_WIDTH, scaleY = mh / RETENTION_MS;
        for (var i = redisEntities.length - 1; i >= 0; i--) {
            var e = redisEntities[i];
            var st = Number(e.startTime), et = Number(e.endTime);
            var ws = Number(e.widthStart), we = Number(e.widthEnd);
            var eAge = now - st, eEndAge = now - et;
            var ey1 = Math.max(0, eAge * scaleY), ey2 = Math.max(0, eEndAge * scaleY);
            var eyTop = Math.min(ey1, ey2), eyBot = Math.max(ey1, ey2);
            var ex = ws * scaleX, ebw = (we - ws) * scaleX;
            if (mx >= ex && mx <= ex + ebw && my >= eyTop && my <= eyBot) return e;
        }
        return null;
    }

    canvas.addEventListener('mousemove', function (e) {
        var rect = canvas.getBoundingClientRect();
        mouseX = e.clientX - rect.left;
        mouseY = e.clientY - rect.top;
        mouseCanvas = canvas;
        hoveredBlock = hitTestBlock(mouseX, mouseY);
        hoveredEntity = null;
    });
    canvas.addEventListener('mouseleave', function () {
        if (mouseCanvas === canvas) { hoveredBlock = null; mouseCanvas = null; }
    });
    mCanvas.addEventListener('mousemove', function (e) {
        var rect = mCanvas.getBoundingClientRect();
        mouseX = e.clientX - rect.left;
        mouseY = e.clientY - rect.top;
        mouseCanvas = mCanvas;
        hoveredEntity = hitTestEntity(mouseX, mouseY);
        hoveredBlock = null;
    });
    mCanvas.addEventListener('mouseleave', function () {
        if (mouseCanvas === mCanvas) { hoveredEntity = null; mouseCanvas = null; }
    });

    // === Pause ===
    var pauseBtn = document.getElementById('pause-btn');
    if (pauseBtn) {
        pauseBtn.addEventListener('click', function () {
            paused = !paused;
            pauseBtn.textContent = paused ? 'RESUME' : 'PAUSE';
            pauseBtn.className = paused ? 'paused' : '';
            fetch('/api/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ paused: paused })
            }).catch(function (e) { console.error('Pause toggle failed:', e); });
        });
    }

    // === WebSocket ===
    function connectWs() {
        var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        var ws = new WebSocket(proto + '//' + location.host + '/ws');
        ws.onopen = function () {
            connected = true;
            wsStatusEl.textContent = 'CONNECTED';
            wsStatusEl.className = 'connected';
        };
        ws.onclose = function () {
            connected = false;
            wsStatusEl.textContent = 'DISCONNECTED';
            wsStatusEl.className = 'disconnected';
            setTimeout(connectWs, 2000);
        };
        ws.onerror = function () { ws.close(); };
        ws.onmessage = function (event) {
            try { handleMessage(JSON.parse(event.data)); }
            catch (err) { console.error('WS parse error:', err); }
        };
    }

    function handleMessage(msg) {
        switch (msg.type) {
            case 'redis-status':
                if (msg.data && Array.isArray(msg.data)) {
                    updateRedisStatus(msg.data);
                }
                return;
            case 'throughput':
                if (msg.data) {
                    var epsEl = document.getElementById('entities-per-sec');
                    var teEl = document.getElementById('total-entities');
                    if (epsEl) epsEl.textContent = msg.data.entitiesPerSec || 0;
                    if (teEl) teEl.textContent = msg.data.totalEntities || 0;
                }
                return;
            case 'instance-status':
                if (msg.data) {
                    updateInstanceStatus(msg.data);
                }
                return;
        }
        if (paused) return;
        switch (msg.type) {
            case 'blocks':
                if (msg.data && typeof msg.data === 'object' && !Array.isArray(msg.data)) {
                    blocks = msg.data.blocks || [];
                    if (msg.data.maxWidth) MAX_WIDTH = msg.data.maxWidth;
                    if (msg.data.retentionMs) RETENTION_MS = msg.data.retentionMs;
                } else {
                    blocks = msg.data || [];
                }
                blockCountEl.textContent = blocks.length;
                break;
            case 'detector':
                if (msg.data) {
                    detectorPos = msg.data.position || 0;
                    detectorWidth = msg.data.width || 200;
                    detectorTimeWin = msg.data.timeWindowMs || 1000;
                    detectorPosEl.textContent = Math.round(detectorPos);
                }
                break;
            case 'entity':
                if (msg.data) addEntityEvent(msg.data);
                break;
            case 'entities':
                if (msg.data) {
                    var list = Array.isArray(msg.data) ? msg.data : [];
                    entityCountEl.textContent = list.length;
                }
                break;
            case 'merge-start':
                if (msg.data) {
                    mergeEvents.unshift({ type: 'start', time: Date.now(), data: msg.data });
                    if (mergeEvents.length > 30) mergeEvents.length = 30;
                    renderMergeEvents();
                }
                break;
            case 'merge-end':
                if (msg.data) {
                    mergeEvents.unshift({ type: 'end', time: Date.now(), data: msg.data });
                    if (mergeEvents.length > 30) mergeEvents.length = 30;
                    renderMergeEvents();
                }
                break;
            case 'redis-entities':
                if (msg.data) {
                    redisEntities = Array.isArray(msg.data) ? msg.data : [];
                    queueDepthEl.textContent = redisEntities.length;
                    mergedCountEl.textContent = redisEntities.length;
                    renderMergedCards();
                }
                break;
        }
    }

    function updateRedisStatus(nodes) {
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var ind = document.getElementById('ind-' + node.name);
            var roleEl = document.getElementById('role-' + node.name);
            if (ind) {
                ind.className = 'node-indicator ' + (node.running ? 'up' : 'down');
            }
            if (roleEl) {
                var role = node.role || 'down';
                roleEl.textContent = role;
                roleEl.className = 'node-role' +
                    (role === 'master' ? ' master' :
                     role === 'active' ? ' active' :
                     role === 'standby' ? ' standby' :
                     role === 'slave' ? ' slave' : '');
            }
        }
    }

    function updateInstanceStatus(data) {
        if (instanceRoleLabel && data.role) {
            var role = data.role;
            instanceRoleLabel.textContent = data.instanceId + ' (' + role + ')';
            instanceRoleLabel.style.color = role === 'active' ? '#0f0' : '#f80';
        }
    }

    function addEntityEvent(entityMsg) {
        entityEvents.unshift(entityMsg);
        if (entityEvents.length > 50) entityEvents.length = 50;
        renderEntityList();
    }

    function renderEntityList() {
        var html = '<h3>Detected Entities</h3>';
        for (var i = 0; i < Math.min(entityEvents.length, 20); i++) {
            var ev = entityEvents[i];
            var e = ev.entity;
            if (!e) continue;
            var cls = ev.type === 'merged' ? 'entity-card merged' : 'entity-card';
            var hex = e.color ? colorHex(e.color) : '#0f0';
            html += '<div class="' + cls + '" style="border-left-color:' + hex + '">'
                + '<span class="eid" style="color:' + hex + '">' + e.entityId + '</span>'
                + '<span style="color:#888;margin-left:8px">[' + ev.type + ']</span>'
                + '<div class="detail">W: ' + Math.round(e.widthStart) + '\u2013' + Math.round(e.widthEnd)
                + ' | A: ' + (e.amplitude ? e.amplitude.toFixed(2) : '?') + '</div></div>';
        }
        entityListEl.innerHTML = html;
    }

    function renderMergeEvents() {
        var html = '';
        for (var i = 0; i < Math.min(mergeEvents.length, 10); i++) {
            var ev = mergeEvents[i];
            var d = ev.data;
            var ago = ((Date.now() - ev.time) / 1000).toFixed(1);
            var col = d.color ? colorHex(d.color) : '#f0f';
            if (ev.type === 'start') {
                html += '<div class="merge-event" style="border-left-color:' + col + '">START ' + ago + 's: '
                    + d.incomingId + ' \u2192 ' + d.targetId + '</div>';
            } else {
                html += '<div class="merge-event" style="border-left-color:' + col + '">END ' + ago + 's: '
                    + d.entityId + ' \u2190 ' + d.absorbedId
                    + ' W:[' + Math.round(d.widthStart) + '-' + Math.round(d.widthEnd) + ']</div>';
            }
        }
        mergeEventsListEl.innerHTML = html;
    }

    function renderMergedCards() {
        var html = '';
        var sorted = redisEntities.slice().sort(function (a, b) {
            return (Number(b.detectionTime) || 0) - (Number(a.detectionTime) || 0);
        });
        for (var i = 0; i < Math.min(sorted.length, 30); i++) {
            var e = sorted[i];
            var ws = Math.round(Number(e.widthStart) || 0);
            var we = Math.round(Number(e.widthEnd) || 0);
            var st = Number(e.startTime) || 0;
            var et = Number(e.endTime) || 0;
            var dur = ((et - st) / 1000).toFixed(1);
            var amp = Number(e.amplitude) || 0;
            var age = ((Date.now() - (Number(e.detectionTime) || 0)) / 1000).toFixed(1);
            var col = e.color || 'green';
            var hex = colorHex(col);
            html += '<div class="merged-card" style="border-left-color:' + hex + '">'
                + '<span class="mid" style="color:' + hex + '">' + e.entityId + '</span>'
                + ' <span style="color:' + hex + ';font-size:9px">[' + col + ']</span>'
                + '<div class="mdetail">W: ' + ws + '\u2013' + we
                + ' | ' + dur + 's | A:' + amp.toFixed(2)
                + ' | ' + age + 's ago</div></div>';
        }
        mergedCardsEl.innerHTML = html;
    }

    // === Settings ===
    var settingsToggle = document.getElementById('settings-toggle');
    var settingsBody = document.getElementById('settings-body');
    settingsToggle.addEventListener('click', function () {
        settingsBody.classList.toggle('open');
        settingsToggle.classList.toggle('open');
    });

    var sliders = {
        maxWidth:    { el: document.getElementById('s-maxWidth'),     val: document.getElementById('v-maxWidth'),     key: 'maxWidth' },
        retentionMs: { el: document.getElementById('s-retentionMs'),  val: document.getElementById('v-retentionMs'),  key: 'retentionMs' },
        maxBlocks:   { el: document.getElementById('s-maxBlocks'),    val: document.getElementById('v-maxBlocks'),    key: 'maxBlocksPerTick' },
        minDuration: { el: document.getElementById('s-minDuration'),  val: document.getElementById('v-minDuration'),  key: 'minBlockDurationMs' },
        maxDuration: { el: document.getElementById('s-maxDuration'),  val: document.getElementById('v-maxDuration'),  key: 'maxBlockDurationMs' },
        minBlockW:   { el: document.getElementById('s-minBlockWidth'),val: document.getElementById('v-minBlockWidth'),key: 'minBlockWidth' },
        maxBlockW:   { el: document.getElementById('s-maxBlockWidth'),val: document.getElementById('v-maxBlockWidth'),key: 'maxBlockWidth' },
        detWidth:    { el: document.getElementById('s-detWidth'),     val: document.getElementById('v-detWidth'),     key: 'detectorWindowWidthPercent' },
        overlap:     { el: document.getElementById('s-overlap'),      val: document.getElementById('v-overlap'),      key: 'detectorOverlapPercent' },
        detTimeWin:  { el: document.getElementById('s-detTimeWin'),   val: document.getElementById('v-detTimeWin'),   key: 'detectorTimeWindowMs' },
        detProb:     { el: document.getElementById('s-detProb'),      val: document.getElementById('v-detProb'),      key: 'detectionProbability' },
        tickRate:    { el: document.getElementById('s-tickRate'),     val: document.getElementById('v-tickRate'),     key: '_tickRate' },
    };

    // TTL: slider + text input synced
    var ttlSlider = document.getElementById('s-ttl');
    var ttlInput = document.getElementById('s-ttl-input');

    function syncTtl(source) {
        var v = Number(source.value);
        if (isNaN(v) || v < 5) v = 5;
        if (v > 3600) v = 3600;
        ttlSlider.value = v;
        ttlInput.value = v;
        clearTimeout(settingsTimer);
        settingsTimer = setTimeout(sendSettings, 300);
    }
    ttlSlider.addEventListener('input', function () { syncTtl(ttlSlider); });
    ttlInput.addEventListener('change', function () { syncTtl(ttlInput); });

    // Merge window: slider + text input synced
    var mergeWindowSlider = document.getElementById('s-mergeWindow');
    var mergeWindowInput = document.getElementById('s-mergeWindow-input');

    function syncMergeWindow(source) {
        var v = Number(source.value);
        if (isNaN(v) || v < 1) v = 1;
        if (v > 120) v = 120;
        mergeWindowSlider.value = v;
        mergeWindowInput.value = v;
        clearTimeout(settingsTimer);
        settingsTimer = setTimeout(sendSettings, 300);
    }
    mergeWindowSlider.addEventListener('input', function () { syncMergeWindow(mergeWindowSlider); });
    mergeWindowInput.addEventListener('change', function () { syncMergeWindow(mergeWindowInput); });

    var settingsTimer = null;
    function onSliderChange() {
        for (var k in sliders) {
            if (sliders[k].val) sliders[k].val.textContent = sliders[k].el.value;
        }
        clearTimeout(settingsTimer);
        settingsTimer = setTimeout(sendSettings, 300);
    }
    for (var k in sliders) sliders[k].el.addEventListener('input', onSliderChange);

    function sendSettings() {
        var body = {};
        for (var k in sliders) {
            if (sliders[k].key === '_tickRate') {
                body.tickIntervalMs = Math.round(1000 / Number(sliders[k].el.value));
            } else {
                body[sliders[k].key] = Number(sliders[k].el.value);
            }
        }
        body.entityTtlSeconds = Number(ttlSlider.value);
        body.mergeWindowSeconds = Number(mergeWindowSlider.value);
        fetch('/api/settings', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).catch(function (e) { console.error('Settings update failed:', e); });
    }

    function loadSettings() {
        fetch('/api/settings').then(function (r) { return r.json(); }).then(function (cfg) {
            if (cfg.maxWidth !== undefined)              { sliders.maxWidth.el.value = cfg.maxWidth; sliders.maxWidth.val.textContent = cfg.maxWidth; MAX_WIDTH = cfg.maxWidth; }
            if (cfg.retentionMs !== undefined)            { sliders.retentionMs.el.value = cfg.retentionMs; sliders.retentionMs.val.textContent = cfg.retentionMs; RETENTION_MS = cfg.retentionMs; }
            if (cfg.maxBlocksPerTick !== undefined)     { sliders.maxBlocks.el.value = cfg.maxBlocksPerTick; sliders.maxBlocks.val.textContent = cfg.maxBlocksPerTick; }
            if (cfg.minBlockDurationMs !== undefined)    { sliders.minDuration.el.value = cfg.minBlockDurationMs; sliders.minDuration.val.textContent = cfg.minBlockDurationMs; }
            if (cfg.maxBlockDurationMs !== undefined)    { sliders.maxDuration.el.value = cfg.maxBlockDurationMs; sliders.maxDuration.val.textContent = cfg.maxBlockDurationMs; }
            if (cfg.minBlockWidth !== undefined)         { sliders.minBlockW.el.value = cfg.minBlockWidth; sliders.minBlockW.val.textContent = Math.round(cfg.minBlockWidth); }
            if (cfg.maxBlockWidth !== undefined)         { sliders.maxBlockW.el.value = cfg.maxBlockWidth; sliders.maxBlockW.val.textContent = Math.round(cfg.maxBlockWidth); }
            if (cfg.detectorWindowWidthPercent !== undefined) { sliders.detWidth.el.value = cfg.detectorWindowWidthPercent; sliders.detWidth.val.textContent = cfg.detectorWindowWidthPercent; }
            if (cfg.detectorOverlapPercent !== undefined) { sliders.overlap.el.value = cfg.detectorOverlapPercent; sliders.overlap.val.textContent = cfg.detectorOverlapPercent; }
            if (cfg.detectorTimeWindowMs !== undefined)  { sliders.detTimeWin.el.value = cfg.detectorTimeWindowMs; sliders.detTimeWin.val.textContent = cfg.detectorTimeWindowMs; }
            if (cfg.detectionProbability !== undefined)  { sliders.detProb.el.value = cfg.detectionProbability; sliders.detProb.val.textContent = cfg.detectionProbability; }
            if (cfg.entityTtlSeconds !== undefined)    { ttlSlider.value = cfg.entityTtlSeconds; ttlInput.value = cfg.entityTtlSeconds; }
            if (cfg.mergeWindowSeconds !== undefined)   { mergeWindowSlider.value = cfg.mergeWindowSeconds; mergeWindowInput.value = cfg.mergeWindowSeconds; }
            if (cfg.tickIntervalMs !== undefined)       { var rate = Math.round(1000 / cfg.tickIntervalMs); sliders.tickRate.el.value = rate; sliders.tickRate.val.textContent = rate; }
            if (cfg.paused !== undefined) {
                paused = cfg.paused;
                if (pauseBtn) {
                    pauseBtn.textContent = paused ? 'RESUME' : 'PAUSE';
                    pauseBtn.className = paused ? 'paused' : '';
                }
            }
        }).catch(function () {});
    }
    loadSettings();

    // === Sidebar resize ===
    var sidebar = document.getElementById('sidebar');
    var resizeHandle = document.getElementById('resize-handle');
    var panelWidthSlider = document.getElementById('s-panelWidth');
    var panelWidthVal = document.getElementById('v-panelWidth');

    function setSidebarWidth(w) {
        var clamped = Math.max(200, Math.min(800, w));
        sidebar.style.width = clamped + 'px';
        panelWidthSlider.value = clamped;
        panelWidthVal.textContent = clamped;
        resize();
    }
    panelWidthSlider.addEventListener('input', function () {
        panelWidthVal.textContent = panelWidthSlider.value;
        setSidebarWidth(Number(panelWidthSlider.value));
    });

    var hDragging = false;
    resizeHandle.addEventListener('mousedown', function (e) { hDragging = true; resizeHandle.classList.add('active'); e.preventDefault(); });
    window.addEventListener('mousemove', function (e) {
        if (hDragging) setSidebarWidth(window.innerWidth - e.clientX);
    });
    window.addEventListener('mouseup', function () {
        if (hDragging) { hDragging = false; resizeHandle.classList.remove('active'); }
    });

    // Start
    connectWs();
    requestAnimationFrame(render);
})();

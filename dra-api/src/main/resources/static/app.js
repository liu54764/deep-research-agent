/* Deep Research Agent test console: vanilla JS, same-origin, no build step. */
(() => {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELED']);
    const EVENT_TYPES = ['snapshot', 'phase', 'progress', 'source', 'evidence', 'claim',
        'warning', 'paused', 'resumed', 'done', 'error'];

    const state = {
        key: localStorage.getItem('dra.apiKey') || 'dev-key',
        taskId: null,
        status: null,
        report: null,
        markdown: '',
        es: null,
        pollTimer: null,
        terminal: false,
        reportTaskId: null
    };

    // ---------------------------------------------------------------- api

    async function api(path, options = {}) {
        const headers = Object.assign({'X-API-Key': state.key}, options.headers || {});
        if (options.body && !headers['Content-Type']) {
            headers['Content-Type'] = 'application/json';
        }
        const res = await fetch(path, Object.assign({}, options, {headers}));
        if (!res.ok) {
            let detail = `HTTP ${res.status}`;
            try {
                const body = await res.json();
                detail = body.detail || body.message || detail;
            } catch (ignored) { /* non-JSON error */ }
            throw new Error(detail);
        }
        const type = res.headers.get('content-type') || '';
        return type.includes('json') ? res.json() : res.text();
    }

    // ---------------------------------------------------------------- helpers

    const esc = (value) => String(value == null ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

    function safeUrl(url) {
        try {
            const parsed = new URL(url, window.location.origin);
            return (parsed.protocol === 'http:' || parsed.protocol === 'https:') ? parsed.href : '#';
        } catch (e) {
            return '#';
        }
    }

    const fmtNum = (n) => (n == null ? '—' : Number(n).toLocaleString('zh-CN'));
    const fmtCost = (micros) => micros == null ? '—' : '¥' + (micros / 1e6).toFixed(4);
    const fmtRate = (v) => v == null ? '—' : (v * 100).toFixed(1) + '%';

    function fmtDuration(ms) {
        if (ms == null || ms < 0) return '—';
        if (ms < 1000) return ms + ' ms';
        if (ms < 60000) return (ms / 1000).toFixed(1) + ' s';
        const m = Math.floor(ms / 60000);
        const s = Math.round((ms % 60000) / 1000);
        return m + ' m ' + s + ' s';
    }

    function fmtTime(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        return isNaN(d.getTime()) ? iso : d.toLocaleTimeString('zh-CN', {hour12: false});
    }

    function toast(message, isError = false) {
        const el = $('toast');
        el.textContent = message;
        el.className = 'toast' + (isError ? ' error' : '');
        el.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => { el.hidden = true; }, 3800);
    }

    // ---------------------------------------------------------------- task list

    async function refreshTasks() {
        const list = $('tasks');
        try {
            const tasks = await api('/api/v1/research/tasks?limit=30');
            if (!tasks.length) {
                list.innerHTML = '<li class="empty-line">暂无任务</li>';
                return;
            }
            list.innerHTML = tasks.map(t => `
                <li class="task-item ${t.taskId === state.taskId ? 'active' : ''}"
                    data-task="${esc(t.taskId)}">
                    <div class="task-question">${esc(t.question)}</div>
                    <div class="task-sub">
                        <span class="badge" data-status="${esc(t.status)}">${esc(t.status)}</span>
                        <span>${esc(fmtTime(t.createdAt))}</span>
                        ${t.stopReason ? `<span class="mono">${esc(t.stopReason)}</span>` : ''}
                    </div>
                </li>`).join('');
            list.querySelectorAll('.task-item').forEach(item => {
                item.addEventListener('click', () => selectTask(item.dataset.task));
            });
        } catch (e) {
            list.innerHTML = `<li class="empty-line">加载失败：${esc(e.message)}</li>`;
        }
    }

    // ---------------------------------------------------------------- task lifecycle

    function selectTask(taskId, pushHash = true) {
        if (!taskId) return;
        state.taskId = taskId;
        state.status = null;
        state.report = null;
        state.markdown = '';
        state.terminal = false;
        state.reportTaskId = null;
        $('empty').hidden = true;
        $('detail').hidden = false;
        $('markdown').textContent = '';
        $('raw').textContent = '';
        $('events').textContent = '';
        $('warnings').innerHTML = '';
        $('taskIdText').textContent = taskId;
        setSseState('连接中…');
        if (pushHash) {
            history.replaceState(null, '', '#task=' + encodeURIComponent(taskId));
        }
        closeSse();
        stopPolling();
        refreshTasks();
        loadStatus();
        openSse();
        startPolling();
    }

    async function loadStatus() {
        if (!state.taskId) return;
        try {
            const status = await api('/api/v1/research/tasks/' + encodeURIComponent(state.taskId));
            state.status = status;
            renderStatus(status);
            if (TERMINAL.has(status.status) && !state.terminal) {
                onTerminal(status.status);
            }
        } catch (e) {
            toast('状态读取失败：' + e.message, true);
        }
    }

    function renderStatus(status) {
        const badge = $('statusBadge');
        badge.textContent = status.status;
        badge.dataset.status = status.status;
        $('phaseText').textContent = status.phase ? `阶段 ${status.phase}` + (status.round ? ` · 轮次 ${status.round}` : '') : '';
        const percent = status.progress ? status.progress.percent : 0;
        $('progressBar').style.width = Math.max(0, Math.min(100, percent || 0)) + '%';
        $('percent').textContent = (percent || 0) + '%';
        $('tokens').textContent = fmtNum(status.usage ? status.usage.tokens : 0);
        $('cost').textContent = fmtCost(status.usage ? status.usage.costMicros : 0);
        $('stopReason').textContent = status.stopReason || '—';

        $('warnings').innerHTML = (status.warnings || [])
            .map(w => `<div class="warning">${esc(w)}</div>`).join('');

        const running = status.status === 'RUNNING' || status.status === 'QUEUED';
        $('pause').disabled = !running;
        $('resume').disabled = status.status !== 'PAUSED';
        $('cancel').disabled = TERMINAL.has(status.status);
    }

    async function onTerminal(statusValue) {
        state.terminal = true;
        stopPolling();
        closeSse();
        setSseState('已结束');
        await refreshTasks();
        if (statusValue === 'SUCCEEDED') {
            await loadReport();
        }
    }

    // ---------------------------------------------------------------- report

    async function loadReport() {
        const taskId = state.taskId;
        try {
            const [json, markdown] = await Promise.all([
                api('/api/v1/research/tasks/' + encodeURIComponent(taskId) + '/report'),
                api('/api/v1/research/tasks/' + encodeURIComponent(taskId) + '/report?format=markdown')
            ]);
            state.report = json;
            state.markdown = markdown;
            state.reportTaskId = taskId;
            $('markdown').textContent = markdown;
            $('raw').textContent = JSON.stringify(json, null, 2);
            renderReport(json.document || {});
        } catch (e) {
            $('tab-report').innerHTML = `<div class="panel"><p class="hint error">报告加载失败：${esc(e.message)}</p></div>`;
            toast('报告加载失败：' + e.message, true);
        }
    }

    function renderReport(doc) {
        const stats = doc.stats || {};
        const sections = doc.sections || [];
        const conflicts = doc.conflicts || [];
        const references = doc.references || [];

        const statCards = [
            ['来源', fmtNum(stats.sourcesFetched)],
            ['证据', fmtNum(stats.evidence)],
            ['结论', fmtNum(stats.claims)],
            ['校验通过率', fmtRate(stats.verifiedRatio)],
            ['用时', fmtDuration(stats.durationMs)],
            ['tokens', fmtNum(stats.tokens)],
            ['成本', fmtCost(stats.costMicros)],
            ['stop', stats.stopReason || '—']
        ].map(([k, v]) => `<div class="stat"><div class="k">${esc(k)}</div><div class="v">${esc(v)}</div></div>`).join('');

        const sectionsHtml = sections.map(section => {
            const claims = (section.claims || []).map(renderClaim).join('');
            return `<section class="panel section">
                <h3>${esc(section.title || section.key)}</h3>
                ${claims || '<p class="muted">本节无可渲染结论。</p>'}
            </section>`;
        }).join('');

        const conflictsHtml = conflicts.length ? `<section class="panel">
            <h3>争议与反证</h3>
            ${conflicts.map(c => `<div class="conflict">
                <h4>${esc(c.topic)}</h4>
                <p>${esc(c.summary)}</p>
                <div class="positions">${(c.positions || []).map(p => `
                    <div class="position">
                        <span class="stance">${esc(p.stance || 'position')}</span>
                        <span>${esc(p.statement || p.claimId)}</span>
                        ${(p.urls || []).map(u => `<a href="${esc(safeUrl(u))}" target="_blank" rel="noopener">来源</a>`).join(' ')}
                    </div>`).join('')}</div>
            </div>`).join('')}
        </section>` : '';

        const refsHtml = references.length ? `<section class="panel">
            <h3>参考来源</h3>
            <ol class="refs">
                ${references.map(r => `<li>
                    <span class="n">[${esc(r.n)}]</span>
                    <span><a href="${esc(safeUrl(r.url))}" target="_blank" rel="noopener">${esc(r.title || r.url)}</a>
                    <div class="muted small">${esc(r.url)} · ${esc(r.accessedAt || '')}</div></span>
                </li>`).join('')}
            </ol>
        </section>` : '';

        const warnings = (stats.warnings || []).length
            ? `<section class="panel"><h3>告警</h3>${stats.warnings.map(w => `<div class="warning">${esc(w)}</div>`).join('')}</section>`
            : '';

        $('tab-report').innerHTML = `<div class="report">
            <div class="stat-grid">${statCards}</div>
            ${conflictsHtml}
            ${sectionsHtml || '<div class="panel"><p class="muted">报告没有可渲染的章节。</p></div>'}
            ${refsHtml}
            ${warnings}
        </div>`;
    }

    function renderClaim(claim) {
        const confidence = Math.max(0, Math.min(1, Number(claim.confidence || 0)));
        const citations = (claim.citations || []).map(c => `
            <div class="citation">
                <div><span class="ref">${esc(c.ref || '')}</span>
                    <a href="${esc(safeUrl(c.url))}" target="_blank" rel="noopener">${esc(c.title || c.url)}</a></div>
                ${c.quote ? `<blockquote>${esc(c.quote)}</blockquote>` : ''}
            </div>`).join('');
        return `<article class="claim">
            <div class="claim-head">
                <div class="claim-text">${esc(claim.statement)}</div>
                <span class="badge" data-status="${esc(claim.status || '')}">${esc(claim.status || '')}</span>
            </div>
            <div class="claim-meta">
                <span class="conf">置信度 <span class="conf-bar"><i style="width:${Math.round(confidence * 100)}%"></i></span>
                    ${(confidence * 100).toFixed(0)}%</span>
                <span class="muted small mono">${esc(claim.id)}</span>
            </div>
            ${citations ? `<div class="citations">${citations}</div>` : '<div class="muted small" style="margin-top:8px">无引用</div>'}
        </article>`;
    }

    // ---------------------------------------------------------------- SSE + polling

    function openSse() {
        closeSse();
        const url = '/api/v1/research/tasks/' + encodeURIComponent(state.taskId)
            + '/events?api_key=' + encodeURIComponent(state.key);
        const es = new EventSource(url);
        state.es = es;
        EVENT_TYPES.forEach(type => es.addEventListener(type, ev => handleEvent(type, ev)));
        es.onopen = () => setSseState('已连接');
        es.onerror = () => {
            setSseState(state.terminal ? '已结束' : '重连中…');
            if (state.terminal) closeSse();
        };
    }

    function closeSse() {
        if (state.es) {
            state.es.close();
            state.es = null;
        }
    }

    function setSseState(text) {
        const el = $('sseState');
        if (el) el.textContent = 'SSE：' + text;
    }

    function logEvent(type, text) {
        const pre = $('events');
        const line = `[${new Date().toLocaleTimeString('zh-CN', {hour12: false})}] ${type.padEnd(8)} ${text}\n`;
        pre.textContent += line;
        if (pre.textContent.length > 24000) {
            pre.textContent = pre.textContent.slice(-18000);
        }
        pre.scrollTop = pre.scrollHeight;
    }

    function handleEvent(type, ev) {
        let event;
        try {
            event = JSON.parse(ev.data);
        } catch (e) {
            logEvent(type, ev.data);
            return;
        }
        const payload = event.payload || {};
        switch (type) {
            case 'snapshot': {
                logEvent(type, `status=${payload.status || ''} phase=${payload.phase || ''} percent=${payload.percent || 0}`);
                applySnapshot(payload);
                break;
            }
            case 'phase':
                logEvent(type, `phase=${payload.phase || ''} round=${payload.round || ''}`);
                updatePhase(payload.phase);
                break;
            case 'progress':
                logEvent(type, `phase=${payload.phase || ''} ${payload.done || 0}/${payload.total || 0} (${payload.percent || 0}%)`);
                updateProgress(payload);
                break;
            case 'source':
                logEvent(type, `${payload.status || ''} ${payload.url || ''}`);
                break;
            case 'evidence':
                logEvent(type, `subQuestion=${payload.subQuestionId || ''} count=${payload.count || 0}`);
                break;
            case 'claim':
                logEvent(type, `${payload.status || ''} ${payload.claimId || ''}`);
                break;
            case 'warning':
                logEvent(type, `${payload.code || ''}: ${payload.message || ''}`);
                break;
            case 'paused':
            case 'resumed':
                logEvent(type, '');
                loadStatus();
                break;
            case 'done':
                logEvent(type, `reportId=${payload.reportId || ''} stop=${payload.stopReason || ''}`);
                onTerminal('SUCCEEDED');
                break;
            case 'error':
                logEvent(type, `${payload.code || ''}: ${payload.message || ''}`);
                onTerminal('FAILED');
                break;
            default:
                logEvent(type, '');
        }
    }

    function applySnapshot(snapshot) {
        const status = state.status || {};
        const statusValue = snapshot.status || status.status;
        const phase = snapshot.phase || status.phase;
        const done = Number(snapshot.done || 0);
        const total = Number(snapshot.total || 0);
        const percent = Number(snapshot.percent || (total > 0 ? Math.round(done * 100 / total) : 0));
        const warnings = snapshot.warnings
            ? String(snapshot.warnings).split('\n').filter(Boolean)
            : (snapshot.lastWarning ? [snapshot.lastWarning] : (status.warnings || []));
        const merged = Object.assign({}, status, {
            status: statusValue,
            phase: phase,
            progress: {done, total, percent},
            usage: {
                tokens: Number(snapshot.usedTokens || (status.usage ? status.usage.tokens : 0)),
                costMicros: Number(snapshot.usedCostMicros || (status.usage ? status.usage.costMicros : 0))
            },
            stopReason: snapshot.stopReason || status.stopReason,
            warnings: warnings
        });
        state.status = merged;
        renderStatus(merged);
        if (statusValue && TERMINAL.has(statusValue) && !state.terminal) {
            onTerminal(statusValue);
        }
    }

    function updatePhase(phase) {
        if (!state.status) return;
        state.status.phase = phase;
        renderStatus(state.status);
    }

    function updateProgress(payload) {
        if (!state.status) return;
        state.status.phase = payload.phase || state.status.phase;
        state.status.progress = {
            done: payload.done || 0,
            total: payload.total || 0,
            percent: payload.percent || 0
        };
        renderStatus(state.status);
    }

    function startPolling() {
        stopPolling();
        state.pollTimer = setInterval(async () => {
            if (!state.taskId) return;
            try {
                const status = await api('/api/v1/research/tasks/' + encodeURIComponent(state.taskId));
                state.status = status;
                renderStatus(status);
                if (TERMINAL.has(status.status) && !state.terminal) {
                    onTerminal(status.status);
                }
            } catch (ignored) { /* SSE may still recover */ }
        }, 2500);
    }

    function stopPolling() {
        if (state.pollTimer) {
            clearInterval(state.pollTimer);
            state.pollTimer = null;
        }
    }

    // ---------------------------------------------------------------- actions

    async function submitTask() {
        const question = $('question').value.trim();
        const hint = $('submitHint');
        if (!question) {
            hint.textContent = '请输入研究问题';
            hint.classList.add('error');
            return;
        }
        hint.textContent = '提交中…';
        hint.classList.remove('error');
        $('submit').disabled = true;
        try {
            const created = await api('/api/v1/research/tasks', {
                method: 'POST',
                headers: {'Idempotency-Key': 'ui-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)},
                body: JSON.stringify({question, depth: $('depth').value})
            });
            hint.textContent = '已提交：' + created.taskId;
            selectTask(created.taskId);
            toast('任务已提交');
        } catch (e) {
            hint.textContent = '提交失败：' + e.message;
            hint.classList.add('error');
            toast('提交失败：' + e.message, true);
        } finally {
            $('submit').disabled = false;
        }
    }

    async function control(action) {
        if (!state.taskId) return;
        try {
            await api('/api/v1/research/tasks/' + encodeURIComponent(state.taskId) + '/' + action,
                {method: 'POST'});
            toast(action === 'pause' ? '已发送暂停' : action === 'resume' ? '已发送恢复' : '已发送取消');
            await loadStatus();
        } catch (e) {
            toast('操作失败：' + e.message, true);
        }
    }

    function downloadMarkdown() {
        if (!state.markdown) return;
        const blob = new Blob([state.markdown], {type: 'text/markdown;charset=utf-8'});
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = (state.taskId || 'report') + '.md';
        a.click();
        setTimeout(() => URL.revokeObjectURL(a.href), 2000);
    }

    function bind() {
        $('apiKey').value = state.key;
        $('saveKey').addEventListener('click', () => {
            state.key = $('apiKey').value.trim() || 'dev-key';
            localStorage.setItem('dra.apiKey', state.key);
            $('keyState').textContent = '已保存';
            setTimeout(() => { $('keyState').textContent = ''; }, 1800);
            refreshTasks();
            if (state.taskId) {
                openSse();
                loadStatus();
            }
        });
        $('submit').addEventListener('click', submitTask);
        $('question').addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') submitTask();
        });
        $('refresh').addEventListener('click', refreshTasks);
        $('pause').addEventListener('click', () => control('pause'));
        $('resume').addEventListener('click', () => control('resume'));
        $('cancel').addEventListener('click', () => control('cancel'));
        $('downloadMd').addEventListener('click', downloadMarkdown);
        $('openRaw').addEventListener('click', () => switchTab('raw'));
        document.querySelectorAll('.tab').forEach(tab => {
            tab.addEventListener('click', () => switchTab(tab.dataset.tab));
        });
    }

    function switchTab(name) {
        document.querySelectorAll('.tab').forEach(tab => {
            tab.classList.toggle('active', tab.dataset.tab === name);
        });
        ['report', 'markdown', 'events', 'raw'].forEach(tabName => {
            $('tab-' + tabName).hidden = tabName !== name;
        });
    }

    function init() {
        bind();
        refreshTasks();
        setInterval(refreshTasks, 15000);
        const match = /#task=([^&]+)/.exec(window.location.hash);
        if (match) {
            selectTask(decodeURIComponent(match[1]), false);
        }
    }

    init();
})();

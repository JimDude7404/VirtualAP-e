// VirtualAP WebUI
(function () {
  'use strict';

  const VAP = '/data/local/virtualap';
  const AP = `sh ${VAP}/start-ap`;

  const $ = (id) => document.getElementById(id);
  const els = {
    pill: $('status-pill'),
    statusCard: $('status-card'),
    gateway: $('st-gateway'),
    ssid: $('st-ssid'),
    band: $('st-band'),
    upstream: $('st-upstream'),
    clients: $('st-clients'),
    started: $('st-started'),
    leasesWrap: $('leases-wrap'),
    leasesList: $('leases-list'),
    cfgSsid: $('cfg-ssid'),
    cfgPass: $('cfg-pass'),
    cfgBand: $('cfg-band'),
    cfgChannel: $('cfg-channel'),
    cfgUpstream: $('cfg-upstream'),
    cfgBoot: $('cfg-boot'),
    btnToggle: $('btn-toggle'),
    actionMsg: $('action-msg'),
    logView: $('log-view'),
  };

  let running = false;
  let busy = false;
  let pollTimer = null;

  // --- Shell helpers -------------------------------------------------------

  function exec(cmd) {
    return window.cmdExec.execute(cmd, true);
  }

  // User input (SSID/password) crosses two shell layers in `su -c "..."`.
  // base64 the whole command so quotes/dollars/backticks can never break out.
  function execSafe(rawCmd) {
    const bytes = new TextEncoder().encode(rawCmd);
    let bin = '';
    bytes.forEach((b) => { bin += String.fromCharCode(b); });
    const b64 = btoa(bin);
    return exec(`echo ${b64} | base64 -d | sh`);
  }

  function shQuote(s) {
    return `'` + String(s).replace(/'/g, `'\\''`) + `'`;
  }

  // Strip shell quoting from a sourced-conf value ('...' or "...")
  function unquoteShell(v) {
    v = String(v).trim();
    if (v.length >= 2 && v.startsWith(`'`) && v.endsWith(`'`)) {
      return v.slice(1, -1).replace(/'\\''/g, `'`);
    }
    if (v.length >= 2 && v.startsWith(`"`) && v.endsWith(`"`)) {
      return v.slice(1, -1);
    }
    return v;
  }

  function parseKV(text) {
    const out = {};
    (text || '').split('\n').forEach((line) => {
      const i = line.indexOf('=');
      if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    });
    return out;
  }

  // --- Channel options -----------------------------------------------------

  const CHANNELS = {
    '2': ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11'],
    '5': ['36', '40', '44', '48', '149', '153', '157', '161', '165'],
  };

  function fillChannels() {
    const band = els.cfgBand.value;
    const prev = els.cfgChannel.value;
    els.cfgChannel.innerHTML = '<option value="">Auto (follow STA)</option>';
    CHANNELS[band].forEach((ch) => {
      const opt = document.createElement('option');
      opt.value = ch;
      opt.textContent = ch;
      els.cfgChannel.appendChild(opt);
    });
    if ([...els.cfgChannel.options].some((o) => o.value === prev)) {
      els.cfgChannel.value = prev;
    }
  }

  // --- UI state ------------------------------------------------------------

  function setPill(state) {
    els.pill.className = 'pill ' + (state === 'on' ? 'pill-on' : state === 'busy' ? 'pill-busy' : 'pill-off');
    els.pill.textContent = state === 'on' ? 'RUNNING' : state === 'busy' ? 'WORKING…' : 'STOPPED';
  }

  function setToggleButton() {
    els.btnToggle.disabled = busy;
    if (running) {
      els.btnToggle.textContent = 'Stop Access Point';
      els.btnToggle.className = 'btn btn-danger';
    } else {
      els.btnToggle.textContent = 'Start Access Point';
      els.btnToggle.className = 'btn btn-primary';
    }
  }

  function showMsg(text, isError) {
    els.actionMsg.hidden = !text;
    els.actionMsg.textContent = text || '';
    els.actionMsg.style.color = isError ? 'var(--bad)' : 'var(--warn)';
  }

  // --- Data loaders --------------------------------------------------------

  async function refreshStatus() {
    try {
      const st = parseKV(await exec(`${AP} status`));
      running = st.running === '1';
      setPill(busy ? 'busy' : running ? 'on' : 'off');
      setToggleButton();
      els.statusCard.hidden = !running;

      if (running) {
        els.gateway.textContent = st.gateway || '—';
        els.ssid.textContent = st.ssid || '—';
        els.band.textContent = st.band ? `${st.band === '5' ? '5' : '2.4'} GHz · ch ${st.channel || '?'}` : '—';
        els.upstream.textContent = st.upstream === 'auto'
          ? `Auto (${st.upstream_iface || st.upstream_table || '?'})`
          : (st.upstream_iface || st.upstream || '—');
        els.clients.textContent = st.clients || '0';
        els.started.textContent = st.started || '—';
        refreshLeases(parseInt(st.clients || '0', 10));
      }
    } catch (e) {
      setPill('off');
    }
  }

  async function refreshLeases(count) {
    if (!count) { els.leasesWrap.hidden = true; return; }
    try {
      const raw = await exec(`${AP} leases`);
      const rows = (raw || '').trim().split('\n').filter(Boolean);
      els.leasesList.innerHTML = '';
      rows.forEach((row) => {
        // dnsmasq lease: <expiry> <mac> <ip> <hostname> <clientid>
        const f = row.trim().split(/\s+/);
        if (f.length < 4) return;
        const li = document.createElement('li');
        li.innerHTML = `<b>${f[3] !== '*' ? f[3] : 'unknown'}</b> · ${f[2]} · ${f[1]}`;
        els.leasesList.appendChild(li);
      });
      els.leasesWrap.hidden = rows.length === 0;
    } catch (e) {
      els.leasesWrap.hidden = true;
    }
  }

  async function refreshInterfaces() {
    try {
      const raw = await exec(`${AP} interfaces`);
      const prev = els.cfgUpstream.value;
      els.cfgUpstream.innerHTML = '<option value="auto">Auto (recommended)</option>';
      (raw || '').trim().split('\n').filter(Boolean).forEach((line) => {
        const [name, ip] = line.trim().split(':');
        if (!name) return;
        const opt = document.createElement('option');
        opt.value = name;
        opt.textContent = ip && ip !== 'no-ip' ? `${name} (${ip})` : name;
        els.cfgUpstream.appendChild(opt);
      });
      if ([...els.cfgUpstream.options].some((o) => o.value === prev)) {
        els.cfgUpstream.value = prev;
      }
    } catch (e) { /* keep Auto only */ }
  }

  let savedPhyIface = 'wlan0'; // preserved across WebUI saves (set via CLI -w only)

  async function loadSavedConfig() {
    try {
      const raw = await exec(`cat ${VAP}/ap.conf 2>/dev/null`);
      const conf = {};
      (raw || '').split('\n').forEach((line) => {
        const i = line.indexOf('=');
        if (i > 0) conf[line.slice(0, i).trim()] = unquoteShell(line.slice(i + 1));
      });
      if (conf.PHY_IFACE) savedPhyIface = conf.PHY_IFACE;
      if (conf.SSID) els.cfgSsid.value = conf.SSID;
      if (conf.PASSWORD) els.cfgPass.value = conf.PASSWORD;
      if (conf.BAND) { els.cfgBand.value = conf.BAND; fillChannels(); }
      if (conf.CHANNEL) els.cfgChannel.value = conf.CHANNEL;
      if (conf.UPSTREAM) {
        if (![...els.cfgUpstream.options].some((o) => o.value === conf.UPSTREAM)) {
          const opt = document.createElement('option');
          opt.value = conf.UPSTREAM;
          opt.textContent = conf.UPSTREAM;
          els.cfgUpstream.appendChild(opt);
        }
        els.cfgUpstream.value = conf.UPSTREAM;
      }
    } catch (e) { /* fresh install */ }
  }

  async function loadBootFlag() {
    try {
      const v = (await exec(`cat ${VAP}/boot-ap 2>/dev/null || echo 0`)).trim();
      els.cfgBoot.checked = v === '1';
    } catch (e) { els.cfgBoot.checked = false; }
  }

  // Persist form fields to ap.conf as the user edits them - the engine only
  // saves on a start attempt, which would lose edits made while stopped.
  let saveTimer = null;
  function persistConfig() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      const lines = [
        `PHY_IFACE=${shQuote(savedPhyIface)}`,
        `UPSTREAM=${shQuote(els.cfgUpstream.value || 'auto')}`,
        `SSID=${shQuote(els.cfgSsid.value.trim())}`,
        `PASSWORD=${shQuote(els.cfgPass.value)}`,
        `BAND=${shQuote(els.cfgBand.value)}`,
        `CHANNEL=${shQuote(els.cfgChannel.value)}`,
      ];
      // Each line is already shell-source-quoted; shQuote again for transport.
      const script = `printf '%s\\n' ${lines.map(shQuote).join(' ')} > ${VAP}/ap.conf`;
      execSafe(script).catch(() => {});
    }, 500);
  }

  async function refreshLogs() {
    try {
      const raw = await exec(`tail -n 120 ${VAP}/logs/ap.log 2>/dev/null`);
      els.logView.textContent = (raw || '').trim() || 'No logs yet.';
      els.logView.scrollTop = els.logView.scrollHeight;
    } catch (e) { /* ignore */ }
  }

  // --- Actions -------------------------------------------------------------

  async function startAp() {
    const ssid = els.cfgSsid.value.trim();
    const pass = els.cfgPass.value;
    if (!ssid) { showMsg('SSID is required.', true); return; }
    if (pass.length < 8) { showMsg('Password must be at least 8 characters.', true); return; }

    busy = true;
    setPill('busy'); setToggleButton(); showMsg('');

    let cmd = `${AP} start -s ${shQuote(ssid)} -p ${shQuote(pass)} -o ${shQuote(els.cfgUpstream.value)} -b ${shQuote(els.cfgBand.value)}`;
    if (els.cfgChannel.value) cmd += ` -c ${shQuote(els.cfgChannel.value)}`;

    try {
      const out = await execSafe(cmd);
      const warns = (out || '').split('\n').filter((l) => l.includes('[WARN]')).join('\n');
      if (warns) showMsg(warns, false);
    } catch (e) {
      showMsg(`Start failed:\n${e.message}`, true);
    }

    busy = false;
    await refreshStatus();
    refreshLogs();
  }

  async function stopAp() {
    busy = true;
    setPill('busy'); setToggleButton(); showMsg('');
    try {
      await exec(`${AP} stop`);
    } catch (e) {
      showMsg(`Stop failed:\n${e.message}`, true);
    }
    busy = false;
    await refreshStatus();
    refreshLogs();
  }

  // --- Wiring --------------------------------------------------------------

  els.btnToggle.addEventListener('click', () => (running ? stopAp() : startAp()));
  els.cfgBand.addEventListener('change', () => { fillChannels(); persistConfig(); });
  els.cfgSsid.addEventListener('input', persistConfig);
  els.cfgPass.addEventListener('input', persistConfig);
  els.cfgChannel.addEventListener('change', persistConfig);
  els.cfgUpstream.addEventListener('change', persistConfig);
  $('refresh-ifaces').addEventListener('click', refreshInterfaces);
  $('refresh-logs').addEventListener('click', refreshLogs);
  $('clear-logs').addEventListener('click', async () => {
    try { await exec(`: > ${VAP}/logs/ap.log`); } catch (e) { /* ignore */ }
    refreshLogs();
  });
  $('toggle-pass').addEventListener('click', () => {
    els.cfgPass.type = els.cfgPass.type === 'password' ? 'text' : 'password';
  });
  els.cfgBoot.addEventListener('change', () => {
    exec(`echo ${els.cfgBoot.checked ? 1 : 0} > ${VAP}/boot-ap`).catch(() => {});
  });

  let initialized = false;
  async function init() {
    if (initialized) return;
    initialized = true;
    fillChannels();
    await refreshInterfaces();
    await loadSavedConfig();
    await loadBootFlag();
    await refreshStatus();
    refreshLogs();
    pollTimer = setInterval(() => { if (!busy) refreshStatus(); }, 5000);
  }

  document.addEventListener('DOMContentLoaded', init);
  if (document.readyState !== 'loading') init();
})();

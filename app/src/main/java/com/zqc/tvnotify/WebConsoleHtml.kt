package com.zqc.tvnotify

/**
 * 玉简内置手机/电脑 Web 控制台单页 HTML。
 * 纯内联 CSS + 原生 JavaScript，零外部 CDN 依赖，在局域网离线环境下 100% 可靠秒开。
 */
object WebConsoleHtml {

    fun render(
        token: String,
        version: String,
        ip: String,
        port: Int,
        isMuted: Boolean,
        defaultPosition: String
    ): String {
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>玉简 · 传音控制台</title>
<style>
:root {
  --bg: #0a1315;
  --card-bg: #102024;
  --card-stroke: #1b383f;
  --text: #f4fffa;
  --text-muted: #8aa8a4;
  --accent: #36d1a8;
  --accent-glow: rgba(54, 209, 168, 0.25);
  --btn-bg: #183037;
  --btn-stroke: #244953;
  --danger: #ff5c5c;
  --warn: #ffc043;
}
* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
body { background: var(--bg); color: var(--text); padding: 16px; font-size: 14px; line-height: 1.5; -webkit-font-smoothing: antialiased; }
.container { max-width: 680px; margin: 0 auto; }
header { display: flex; align-items: center; justify-content: space-between; padding: 12px 0 18px; border-bottom: 1px solid var(--card-stroke); margin-bottom: 16px; }
.brand { display: flex; align-items: center; gap: 10px; }
.brand-icon { width: 34px; height: 34px; border-radius: 8px; background: var(--card-bg); border: 1px solid var(--accent); display: flex; align-items: center; justify-content: center; font-size: 18px; }
.brand h1 { font-size: 18px; font-weight: 700; color: #fff; letter-spacing: 0.5px; }
.brand .sub { font-size: 11px; color: var(--text-muted); }
.version-badge { font-size: 11px; color: var(--accent); background: var(--accent-glow); padding: 3px 8px; border-radius: 12px; border: 1px solid var(--accent); }
.status-pill { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--accent); background: rgba(54, 209, 168, 0.1); padding: 4px 10px; border-radius: 16px; border: 1px solid var(--card-stroke); margin-bottom: 16px; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 8px var(--accent); }
.card { background: var(--card-bg); border: 1px solid var(--card-stroke); border-radius: 14px; padding: 16px; margin-bottom: 16px; box-shadow: 0 4px 16px rgba(0,0,0,0.25); }
.card-title { font-size: 14px; font-weight: 600; color: var(--accent); margin-bottom: 12px; display: flex; align-items: center; justify-content: space-between; }
.form-group { margin-bottom: 12px; }
label { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 5px; }
input[type="text"], input[type="number"], select, textarea {
  width: 100%; background: rgba(0,0,0,0.35); border: 1px solid var(--card-stroke); color: #fff;
  border-radius: 8px; padding: 9px 12px; font-size: 13.5px; outline: none; transition: border-color 0.2s;
}
input:focus, select:focus, textarea:focus { border-color: var(--accent); box-shadow: 0 0 8px var(--accent-glow); }
textarea { resize: vertical; min-height: 60px; }
.row { display: flex; gap: 10px; }
.col { flex: 1; }
.checkbox-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; margin: 12px 0; }
.checkbox-item { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text); background: rgba(0,0,0,0.2); padding: 8px 10px; border-radius: 8px; border: 1px solid var(--card-stroke); cursor: pointer; }
.checkbox-item input { accent-color: var(--accent); width: 16px; height: 16px; cursor: pointer; }
.btn-group { display: flex; gap: 10px; margin-top: 14px; }
button {
  flex: 1; padding: 11px 16px; border-radius: 8px; font-size: 13.5px; font-weight: 600; cursor: pointer;
  transition: all 0.2s; outline: none; border: 1px solid transparent; text-align: center;
}
.btn-primary { background: var(--accent); color: #0a1315; border-color: var(--accent); font-weight: 700; }
.btn-primary:active { opacity: 0.85; transform: scale(0.98); }
.btn-secondary { background: var(--btn-bg); color: #fff; border-color: var(--btn-stroke); }
.btn-secondary:active { background: var(--card-stroke); }
.btn-danger { background: rgba(255,92,92,0.15); color: var(--danger); border-color: rgba(255,92,92,0.4); }
.details-toggle { font-size: 12px; color: var(--accent); cursor: pointer; text-decoration: underline; margin-bottom: 10px; display: inline-block; }
.details-box { display: none; background: rgba(0,0,0,0.2); border: 1px dashed var(--card-stroke); padding: 12px; border-radius: 8px; margin-bottom: 12px; }
.history-item { padding: 10px; border-bottom: 1px solid var(--card-stroke); display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.history-item:last-child { border-bottom: none; }
.history-info { flex: 1; }
.history-meta { font-size: 11px; color: var(--text-muted); display: flex; gap: 8px; align-items: center; margin-bottom: 3px; }
.history-level { font-size: 10px; padding: 1px 5px; border-radius: 4px; font-weight: bold; }
.level-info { background: rgba(54,209,168,0.2); color: var(--accent); }
.level-warn { background: rgba(255,192,67,0.2); color: var(--warn); }
.level-error { background: rgba(255,92,92,0.2); color: var(--danger); }
.history-title { font-weight: 600; color: #fff; font-size: 13px; }
.history-text { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
.history-btn { font-size: 11px; padding: 4px 8px; border-radius: 6px; background: var(--btn-bg); border: 1px solid var(--btn-stroke); color: #fff; cursor: pointer; white-space: nowrap; }
.code-box { background: rgba(0,0,0,0.45); border: 1px solid var(--card-stroke); border-radius: 8px; padding: 10px; font-family: monospace; font-size: 11.5px; color: #b7e3d8; word-break: break-all; position: relative; }
.copy-btn { position: absolute; right: 8px; top: 8px; font-size: 10.5px; padding: 3px 7px; background: var(--btn-bg); border: 1px solid var(--card-stroke); color: var(--accent); border-radius: 5px; cursor: pointer; }
.toast { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: rgba(16,32,36,0.95); border: 1px solid var(--accent); color: #fff; padding: 8px 16px; border-radius: 20px; font-size: 12.5px; z-index: 999; display: none; box-shadow: 0 4px 12px rgba(0,0,0,0.5); }
</style>
</head>
<body>
<div class="container">
  <header>
    <div class="brand">
      <div class="brand-icon">📜</div>
      <div>
        <h1>玉简 · 传音控制台</h1>
        <div class="sub">灵讯横幅 · 电视大屏局域网传音</div>
      </div>
    </div>
    <span class="version-badge">v$version</span>
  </header>

  <div class="status-pill">
    <div class="status-dot"></div>
    <span>阵法在线 · $ip:$port</span>
    <span id="pill-muted">· ${if (isMuted) "闭关免扰" else "灵音畅通"}</span>
    <span id="pill-pos">· 方位：$defaultPosition</span>
  </div>

  <!-- 发送通知卡片 -->
  <div class="card">
    <div class="card-title">
      <span>🚀 传音入室</span>
      <span style="font-size: 11.5px; color: var(--text-muted);">POST /{token}/notify</span>
    </div>
    <div class="form-group">
      <label>主标题 (Title)</label>
      <input type="text" id="title" value="玉简传音" placeholder="例如：门铃感应 / 洗衣机提醒">
    </div>
    <div class="form-group">
      <label>正文内容 (Text)</label>
      <textarea id="text" placeholder="输入推送文案内容...">山门感应到外部灵讯，此简已传音入室。</textarea>
    </div>
    <div class="row">
      <div class="col form-group">
        <label>传音分级 (Level)</label>
        <select id="level">
          <option value="info" selected>传音 (Info)</option>
          <option value="warn">灵警 (Warn)</option>
          <option value="error">告急 (Error)</option>
        </select>
      </div>
      <div class="col form-group">
        <label>显形方位 (Position)</label>
        <select id="position">
          <option value="" selected>默认 (跟随电视设置)</option>
          <option value="top">顶部居中 (Top)</option>
          <option value="top-right">右上角画中画 (Top-Right PiP)</option>
          <option value="bottom">底部居中 (Bottom)</option>
          <option value="bottom-right">右下角画中画 (Bottom-Right PiP)</option>
          <option value="top-left">左上角画中画 (Top-Left)</option>
          <option value="bottom-left">左下角画中画 (Bottom-Left)</option>
        </select>
      </div>
      <div class="col form-group">
        <label>显示时长 (Duration)</label>
        <input type="number" id="duration" min="1" max="30" value="5">
      </div>
    </div>

    <div class="checkbox-grid">
      <label class="checkbox-item"><input type="checkbox" id="sound" checked> 提示音效 (Sound)</label>
      <label class="checkbox-item"><input type="checkbox" id="tts" checked> 原生 TTS 语音播报</label>
      <label class="checkbox-item"><input type="checkbox" id="sticky"> 常驻停留 (Sticky)</label>
      <label class="checkbox-item"><input type="checkbox" id="queue"> 排队顺延 (Queue)</label>
    </div>

    <span class="details-toggle" onclick="toggleDetails()">⚙️ 展开更多多媒体与标识配置...</span>
    <div class="details-box" id="details-box">
      <div class="form-group">
        <label>静态图片或快照 URL (image)</label>
        <input type="text" id="image" placeholder="http://.../snapshot.jpg">
      </div>
      <div class="form-group">
        <label>快照定时轮询刷新间隔 (毫秒，例如 1000 即 1 秒准视频流)</label>
        <input type="number" id="image_interval" placeholder="0 (不轮询)">
      </div>
      <div class="form-group">
        <label>动态 MJPEG 视频流 URL (video)</label>
        <input type="text" id="video" placeholder="http://.../mjpeg.cgi">
      </div>
      <div class="form-group">
        <label>通知唯一 ID (id，相同时原地无闪烁刷新)</label>
        <input type="text" id="id" placeholder="例如：nas_download_task">
      </div>
    </div>

    <div class="btn-group">
      <button class="btn-primary" onclick="sendNotify()">🚀 立即传音</button>
      <button class="btn-secondary" onclick="dismissBanner()">🧹 消散当前横幅</button>
    </div>
  </div>

  <!-- 电视控制与阵法设置 -->
  <div class="card">
    <div class="card-title">
      <span>🎛️ 电视阵法调控</span>
      <span style="font-size: 11.5px; color: var(--text-muted);">实时下发配置</span>
    </div>
    <div class="row" style="align-items: center;">
      <div class="col">
        <button id="btn-toggle-mute" class="btn-secondary" onclick="toggleMute()">${if (isMuted) "🔇 闭关免扰 (已开启静音)" else "🔊 灵音畅通 (已开启声音)"}</button>
      </div>
      <div class="col">
        <select id="config-pos" onchange="changeDefaultPosition()">
          <option value="top" ${if (defaultPosition == "top") "selected" else ""}>默认方位：顶部天幕 (Top)</option>
          <option value="top-right" ${if (defaultPosition == "top-right") "selected" else ""}>默认方位：右上画中画 (Top-Right)</option>
          <option value="bottom" ${if (defaultPosition == "bottom") "selected" else ""}>默认方位：底部地煞 (Bottom)</option>
          <option value="bottom-right" ${if (defaultPosition == "bottom-right") "selected" else ""}>默认方位：右下画中画 (Bottom-Right)</option>
          <option value="top-left" ${if (defaultPosition == "top-left") "selected" else ""}>默认方位：左上画中画 (Top-Left)</option>
          <option value="bottom-left" ${if (defaultPosition == "bottom-left") "selected" else ""}>默认方位：左下画中画 (Bottom-Left)</option>
        </select>
      </div>
    </div>
  </div>

  <!-- 传音简录 (历史记录) -->
  <div class="card">
    <div class="card-title">
      <span>📜 传音简录 (最近 20 条)</span>
      <button class="history-btn" onclick="fetchHistory()">🔄 刷新简录</button>
    </div>
    <div id="history-container">正在载入简录...</div>
  </div>

  <!-- cURL 示例 -->
  <div class="card">
    <div class="card-title">
      <span>💻 cURL 极速调用代码</span>
    </div>
    <div class="code-box">
      <span class="copy-btn" onclick="copyCurl()">复制</span>
      <pre id="curl-code" style="white-space: pre-wrap; margin: 0;"></pre>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
const TOKEN = "$token";
const NOTIFY_URL = window.location.origin + "/" + TOKEN + "/notify";
const DISMISS_URL = window.location.origin + "/" + TOKEN + "/dismiss";
const CONFIG_URL = window.location.origin + "/" + TOKEN + "/config";
const HISTORY_URL = window.location.origin + "/" + TOKEN + "/history";

let currentMuted = $isMuted;

function toast(msg) {
  const t = document.getElementById("toast");
  t.textContent = msg;
  t.style.display = "block";
  setTimeout(() => { t.style.display = "none"; }, 2000);
}

function toggleDetails() {
  const box = document.getElementById("details-box");
  box.style.display = box.style.display === "block" ? "none" : "block";
}

function buildPayload() {
  const p = {
    title: document.getElementById("title").value.trim(),
    text: document.getElementById("text").value.trim(),
    level: document.getElementById("level").value,
    duration: parseInt(document.getElementById("duration").value) || 5,
    sound: document.getElementById("sound").checked,
    sticky: document.getElementById("sticky").checked
  };
  const pos = document.getElementById("position").value;
  if (pos) p.position = pos;
  if (document.getElementById("tts").checked) p.tts = true;
  if (document.getElementById("queue").checked) p.queue = true;

  const img = document.getElementById("image").value.trim();
  if (img) p.image = img;
  const interval = parseInt(document.getElementById("image_interval").value) || 0;
  if (interval > 0) p.image_interval = interval;
  const vid = document.getElementById("video").value.trim();
  if (vid) p.video = vid;
  const idVal = document.getElementById("id").value.trim();
  if (idVal) p.id = idVal;

  return p;
}

function updateCurlDisplay() {
  const p = buildPayload();
  const jsonStr = JSON.stringify(p);
  const cmd = "curl -X POST -H \"Content-Type: application/json\" -d '" + jsonStr + "' " + NOTIFY_URL;
  document.getElementById("curl-code").textContent = cmd;
}

document.querySelectorAll("input, textarea, select").forEach(el => {
  el.addEventListener("input", updateCurlDisplay);
  el.addEventListener("change", updateCurlDisplay);
});
updateCurlDisplay();

async function sendNotify() {
  const payload = buildPayload();
  if (!payload.text && !payload.title && !payload.image && !payload.video) {
    toast("标题、正文或图片至少需填一项");
    return;
  }
  try {
    const res = await fetch(NOTIFY_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (data.ok) {
      toast("✨ 传音成功！横幅已在电视展现");
      setTimeout(fetchHistory, 500);
    } else {
      toast("❌ 传音失败：" + (data.error || "未知原因"));
    }
  } catch (e) {
    toast("❌ 网络异常：" + e.message);
  }
}

async function dismissBanner() {
  try {
    const res = await fetch(DISMISS_URL, { method: "POST" });
    const data = await res.json();
    if (data.ok) {
      toast(data.dismissed ? "🧹 横幅已消散" : "无正在展示之横幅");
    }
  } catch (e) {
    toast("❌ 消散失败: " + e.message);
  }
}

async function toggleMute() {
  currentMuted = !currentMuted;
  try {
    const res = await fetch(CONFIG_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ muted: currentMuted })
    });
    const data = await res.json();
    if (data.ok) {
      currentMuted = data.muted;
      document.getElementById("btn-toggle-mute").textContent = currentMuted ? "🔇 闭关免扰 (已开启静音)" : "🔊 灵音畅通 (已开启声音)";
      document.getElementById("pill-muted").textContent = "· " + (currentMuted ? "闭关免扰" : "灵音畅通");
      toast(currentMuted ? "已切换至闭关免扰（静音）" : "已开启灵音畅通（发声）");
    }
  } catch (e) {
    toast("❌ 配置更新失败: " + e.message);
  }
}

async function changeDefaultPosition() {
  const pos = document.getElementById("config-pos").value;
  try {
    const res = await fetch(CONFIG_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ position: pos })
    });
    const data = await res.json();
    if (data.ok) {
      document.getElementById("pill-pos").textContent = "· 方位：" + data.position;
      toast("默认方位已更新为：" + data.position);
    }
  } catch (e) {
    toast("❌ 更新失败: " + e.message);
  }
}

async function fetchHistory() {
  const cont = document.getElementById("history-container");
  try {
    const res = await fetch(HISTORY_URL);
    const list = await res.json();
    if (!list || list.length === 0) {
      cont.innerHTML = '<div style="font-size:12px;color:var(--text-muted);text-align:center;padding:12px;">暂无传音简录</div>';
      return;
    }
    window._historyItems = list;
    cont.innerHTML = list.map((item, index) => `
      <div class="history-item">
        <div class="history-info">
          <div class="history-meta">
            <span class="history-level level-${'$'}{item.level}">${'$'}{item.level.toUpperCase()}</span>
            <span>${'$'}{new Date(item.timestamp).toLocaleTimeString()}</span>
            <span>方位: ${'$'}{item.position}</span>
            ${'$'}{item.tts ? '<span style="color:var(--accent);">🔊 TTS</span>' : ''}
          </div>
          <div class="history-title">${'$'}{escapeHtml(item.title || "（无标题）")}</div>
          <div class="history-text">${'$'}{escapeHtml(item.text || "")}</div>
        </div>
        <button class="history-btn" onclick="resendIndex(${'$'}{index})">重发</button>
      </div>
    `).join("");
  } catch (e) {
    cont.innerHTML = '<div style="font-size:12px;color:var(--danger);padding:8px;">加载历史失败</div>';
  }
}

function resendIndex(idx) {
  const item = window._historyItems && window._historyItems[idx];
  if (!item) return;
  document.getElementById("title").value = item.title || "";
  document.getElementById("text").value = item.text || "";
  document.getElementById("level").value = item.level || "info";
  if (item.position) document.getElementById("position").value = item.position;
  if (item.tts) document.getElementById("tts").checked = true;
  updateCurlDisplay();
  sendNotify();
}

function escapeHtml(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function copyCurl() {
  const text = document.getElementById("curl-code").textContent;
  navigator.clipboard.writeText(text).then(() => {
    toast("cURL 命令已复制到剪贴板");
  }).catch(() => {
    toast("复制失败，请手动选取");
  });
}

fetchHistory();
</script>
</body>
</html>"""
    }
}

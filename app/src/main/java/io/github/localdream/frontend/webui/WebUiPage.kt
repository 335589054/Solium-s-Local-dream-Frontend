package io.github.localdream.frontend.webui

/**
 * The page served at the root path: a compact Stable Diffusion front-end laid
 * out like the AUTOMATIC1111 WebUI (dark Gradio theme, orange accents, tabbed
 * top bar, collapsible parameter groups, parameters on the left and the canvas
 * on the right).
 *
 * It is one self-contained file so the device needs no network assets, and it
 * drives the very same sdapi endpoints the app exposes — anything that works
 * here also works from an external A1111 client. The extra prompt-library and
 * host-state pages use two small app-specific routes.
 */
object WebUiPage {
    const val HTML: String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Stable Diffusion · LocalDream WebUI</title>
<style>
  :root {
    --bg:#0b0b0f; --panel:#1f1f28; --panel2:#262633; --line:#3a3a4a;
    --fg:#e8e8f0; --dim:#9aa0ad;
    --accent:#f7a43e; --accent-fg:#1a1a1a; --danger:#d64545;
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--fg);
         font:13px/1.45 "Segoe UI",system-ui,-apple-system,Roboto,sans-serif; }

  /* ---- top bar ---- */
  .topbar { display:flex; align-items:center; gap:16px; padding:0 16px;
            background:var(--panel); border-bottom:1px solid var(--line); position:sticky; top:0; z-index:10; }
  .brand { font-size:14px; font-weight:700; color:var(--accent); white-space:nowrap; padding:10px 0; }
  .tabs { display:flex; flex:1; overflow-x:auto; }
  .tabs button { background:none; border:0; border-bottom:2px solid transparent; color:var(--dim);
                 padding:12px 14px; font:inherit; font-weight:600; cursor:pointer; white-space:nowrap; }
  .tabs button:hover { color:var(--fg); }
  .tabs button.active { color:#fff; border-bottom-color:var(--accent); }
  .conn { font-size:12px; color:var(--dim); white-space:nowrap; }
  .conn.ok { color:#7bd88f; }
  .conn.bad { color:var(--danger); }

  /* ---- layout ---- */
  .page { display:none; }
  .page.active { display:block; }
  .layout { display:grid; grid-template-columns:minmax(340px,460px) 1fr; gap:14px; padding:14px; align-items:start; }
  @media (max-width:1000px){ .layout { grid-template-columns:1fr; } }

  .card { background:var(--panel); border:1px solid var(--line); border-radius:6px; padding:12px; }
  .stack { display:flex; flex-direction:column; gap:10px; }

  label { display:block; font-size:12px; color:var(--dim); margin-bottom:4px; }
  input[type=text], input[type=number], textarea, select {
    width:100%; background:#101018; color:var(--fg); border:1px solid var(--line);
    border-radius:4px; padding:7px 8px; font:inherit; }
  textarea { resize:vertical; min-height:90px; font-family:ui-monospace,Menlo,Consolas,monospace; }
  input[type=range] { width:100%; accent-color:var(--accent); }
  input[type=file] { width:100%; color:var(--dim); font-size:12px; }

  button.btn { background:var(--accent); color:var(--accent-fg); border:0; border-radius:4px;
               padding:9px 14px; font:inherit; font-weight:700; cursor:pointer; }
  button.btn:hover { filter:brightness(1.08); }
  button.btn:disabled { opacity:.45; cursor:default; filter:none; }
  button.ghost { background:#2c2c3a; color:var(--fg); }
  button.danger { background:var(--danger); color:#fff; }

  /* ---- collapsible groups, like the WebUI's accordions ---- */
  details.acc { background:var(--panel2); border:1px solid var(--line); border-radius:6px; margin-bottom:8px; }
  details.acc > summary { cursor:pointer; padding:9px 11px; font-weight:700; font-size:13px;
                          color:var(--fg); list-style:none; position:relative; padding-left:26px; }
  details.acc > summary::-webkit-details-marker { display:none; }
  details.acc > summary::before { content:"▸"; position:absolute; left:10px; color:var(--dim); }
  details.acc[open] > summary::before { content:"▾"; }
  details.acc .body { padding:4px 11px 12px; display:flex; flex-direction:column; gap:10px; }

  .grid2 { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
  .grid3 { display:grid; grid-template-columns:1fr 1fr 1fr; gap:10px; }
  .withbtn { display:flex; gap:8px; align-items:flex-end; }
  .withbtn > .grow { flex:1; }

  /* ---- output ---- */
  #canvas, .canvas { background:#101018; border:1px solid var(--line); border-radius:6px;
                     min-height:440px; display:flex; align-items:center; justify-content:center;
                     overflow:hidden; color:var(--dim); }
  .canvas img { max-width:100%; max-height:80vh; display:block; }
  .progress { height:8px; background:#101018; border:1px solid var(--line); border-radius:4px;
              overflow:hidden; margin-top:10px; }
  .progress > i { display:block; height:100%; width:0; background:var(--accent); transition:width .2s; }
  .meta { display:flex; justify-content:space-between; font-size:12px; color:var(--dim); margin-top:6px; }
  .quick { display:flex; gap:8px; flex-wrap:wrap; margin-top:10px; }

  /* ---- tag library ---- */
  .chips { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:12px; }
  .chip { background:var(--panel2); border:1px solid var(--line); color:var(--dim); border-radius:999px;
          padding:5px 12px; font:inherit; font-size:12px; cursor:pointer; }
  .chip.active { background:var(--accent); border-color:var(--accent); color:var(--accent-fg); font-weight:700; }
  .taggrid { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:8px; }
  .tag { background:var(--panel2); border:1px solid var(--line); border-radius:6px; padding:8px 10px;
         cursor:pointer; text-align:left; font:inherit; color:var(--fg); }
  .tag:hover { border-color:var(--accent); }
  .tag .en { font-family:ui-monospace,Menlo,Consolas,monospace; font-size:13px; display:block; }
  .tag .zh { color:var(--dim); font-size:12px; display:block; margin-top:2px; }

  /* ---- model list ---- */
  .models { display:grid; grid-template-columns:repeat(auto-fill,minmax(260px,1fr)); gap:10px; }
  .model { background:var(--panel); border:1px solid var(--line); border-radius:6px; padding:12px; cursor:pointer; }
  .model:hover { border-color:var(--accent); }
  .model.active { border-color:var(--accent); background:#2a2419; }
  .model .name { font-weight:700; }
  .model .path { color:var(--dim); font-size:11px; word-break:break-all; margin-top:4px; }

  .hint { font-size:12px; color:var(--dim); }
  .err { color:var(--danger); font-size:12px; min-height:16px; }
  table.api { width:100%; border-collapse:collapse; font-size:12px; }
  table.api td { padding:4px 6px; border-bottom:1px solid var(--line); }
  table.api td:first-child { font-family:ui-monospace,Menlo,Consolas,monospace; color:var(--accent); white-space:nowrap; }
  footer { padding:14px 16px 28px; color:var(--dim); font-size:12px; text-align:center; }
  footer a { color:var(--accent); text-decoration:none; }
</style>
</head>
<body>

<div class="topbar">
  <div class="brand">Stable Diffusion</div>
  <nav class="tabs" id="tabs">
    <button data-tab="txt2img" class="active">文生图</button>
    <button data-tab="img2img">图生图</button>
    <button data-tab="prompts">提示词库</button>
    <button data-tab="models">模型</button>
    <button data-tab="settings">设置</button>
  </nav>
  <div class="conn" id="conn">连接中…</div>
</div>

<section class="page active" id="page-txt2img">
  <div class="layout">
    <div class="stack" id="ctrl-txt2img"></div>
    <div class="stack">
      <div class="canvas" id="canvas-txt2img">生成结果会显示在这里</div>
      <div class="progress"><i id="bar-txt2img"></i></div>
      <div class="meta"><span id="step-txt2img"></span><span id="pct-txt2img"></span></div>
      <div class="quick">
        <button class="btn ghost" data-act="save" data-mode="txt2img">下载</button>
        <button class="btn ghost" data-act="toimg" data-mode="txt2img">发送到图生图</button>
        <button class="btn ghost" data-act="upscale" data-mode="txt2img">超分</button>
        <button class="btn ghost" data-act="copy" data-mode="txt2img">复制提示词</button>
      </div>
    </div>
  </div>
</section>

<section class="page" id="page-img2img">
  <div class="layout">
    <div class="stack" id="ctrl-img2img"></div>
    <div class="stack">
      <div class="canvas" id="canvas-img2img">生成结果会显示在这里</div>
      <div class="progress"><i id="bar-img2img"></i></div>
      <div class="meta"><span id="step-img2img"></span><span id="pct-img2img"></span></div>
      <div class="quick">
        <button class="btn ghost" data-act="save" data-mode="img2img">下载</button>
        <button class="btn ghost" data-act="upscale" data-mode="img2img">超分</button>
        <button class="btn ghost" data-act="copy" data-mode="img2img">复制提示词</button>
      </div>
    </div>
  </div>
</section>

<section class="page" id="page-prompts">
  <div style="padding:14px" class="stack">
    <div class="card stack">
      <div><b>提示词库</b> <span class="hint">点击标签即可插入到当前页面的提示词框</span></div>
      <div class="withbtn">
        <div class="grow"><label>搜索</label><input type="text" id="tagSearch" placeholder="按英文或中文筛选"></div>
        <button class="btn ghost" id="tagTarget">插入到：文生图</button>
      </div>
      <div class="chips" id="tagCats"></div>
      <div class="taggrid" id="tagGrid"></div>
      <div class="hint" id="tagCount"></div>
    </div>
  </div>
</section>

<section class="page" id="page-models">
  <div style="padding:14px" class="stack">
    <div class="card stack">
      <div><b>模型</b> <span class="hint">点击即可切换，WebUI 客户端会看到同一个 checkpoint</span></div>
      <div class="err" id="modelErr"></div>
      <div class="models" id="modelList"></div>
    </div>
  </div>
</section>

<section class="page" id="page-settings">
  <div style="padding:14px" class="stack">
    <div class="card stack">
      <div><b>运行状态</b></div>
      <div class="hint" id="stateBox">读取中…</div>
    </div>
    <div class="card stack">
      <div><b>已实现的接口</b> <span class="hint">其余接口后端不支持，已全部去除</span></div>
      <table class="api">
        <tr><td>GET /</td><td>本页面</td></tr>
        <tr><td>GET /localdream/tags</td><td>提示词库</td></tr>
        <tr><td>GET /localdream/state</td><td>主机连接状态</td></tr>
        <tr><td>GET /sdapi/v1/sd-models</td><td>模型列表</td></tr>
        <tr><td>GET·POST /sdapi/v1/options</td><td>读取 / 切换模型</td></tr>
        <tr><td>GET /sdapi/v1/samplers</td><td>采样器</td></tr>
        <tr><td>GET /sdapi/v1/upscalers</td><td>放大器</td></tr>
        <tr><td>GET /sdapi/v1/progress</td><td>进度与预览</td></tr>
        <tr><td>POST /sdapi/v1/txt2img</td><td>文生图</td></tr>
        <tr><td>POST /sdapi/v1/img2img</td><td>图生图</td></tr>
        <tr><td>POST /sdapi/v1/extra-single-image</td><td>超分</td></tr>
        <tr><td>POST /sdapi/v1/interrupt</td><td>中断</td></tr>
        <tr><td>POST /sdapi/v1/unload-checkpoint</td><td>卸载模型</td></tr>
      </table>
    </div>
    <div class="card hint">
      出图前请先在手机 App 的「模型」页启动模型，本服务器会把它作为当前 checkpoint 暴露给客户端。
    </div>
  </div>
</section>

<footer>
  由 LocalDream 驱动 ·
  <a href="https://github.com/AUTOMATIC1111/stable-diffusion-webui" target="_blank" rel="noreferrer">AUTOMATIC1111 stable-diffusion-webui</a>
  兼容接口
</footer>

<script>
var state = { mode:'txt2img', active:'txt2img', results:{}, polls:{}, targets:{} };

function $(id){ return document.getElementById(id); }
function api(path, body){
  var opts = { method: body === undefined ? 'GET' : 'POST' };
  if (body !== undefined){ opts.headers = {'Content-Type':'application/json'}; opts.body = JSON.stringify(body); }
  return fetch(path, opts).then(function(r){
    if (!r.ok) return r.text().then(function(t){ throw new Error(shortError(t, r.status)); });
    return r.json();
  });
}
function shortError(text, code){
  try { var o = JSON.parse(text); if (o.detail) return o.detail; } catch(e){}
  return 'HTTP ' + code;
}

/* ---------------- controls ---------------- */

function controlsHtml(p, img2img){
  var h = '';
  h += '<div class="card stack">';
  h += '<div><label>提示词</label><textarea id="' + p + '-prompt" placeholder="1girl, smile, detailed face, ..."></textarea></div>';
  h += '<div><label>负面提示词</label><textarea id="' + p + '-negative" style="min-height:60px" placeholder="lowres, bad anatomy, ..."></textarea></div>';
  h += '<div class="withbtn"><button class="btn ghost" id="' + p + '-openlib">从提示词库选择</button></div>';
  h += '</div>';

  if (img2img){
    h += '<details class="acc" open><summary>图生图</summary><div class="body">';
    h += '<div><label>底图</label><input type="file" id="' + p + '-init" accept="image/*"></div>';
    h += '<div><label>重绘强度 <span id="' + p + '-dv">0.60</span></label>';
    h += '<input type="range" id="' + p + '-denoise" min="0.1" max="1" step="0.05" value="0.6"></div>';
    h += '</div></details>';
  }

  h += '<details class="acc" open><summary>生成参数</summary><div class="body">';
  h += '<div><label>采样方法</label><select id="' + p + '-sampler"></select></div>';
  h += '<div><label>采样步数 <span id="' + p + '-stepsV">20</span></label>';
  h += '<input type="range" id="' + p + '-steps" min="1" max="100" value="20"></div>';
  h += '<div><label>提示词引导系数 (CFG) <span id="' + p + '-cfgV">7.0</span></label>';
  h += '<input type="range" id="' + p + '-cfg" min="1" max="20" step="0.5" value="7"></div>';
  h += '<div><label>随机数种子 (-1 为随机)</label><div class="withbtn">';
  h += '<div class="grow"><input type="number" id="' + p + '-seed" value="-1"></div>';
  h += '<button class="btn ghost" id="' + p + '-dice">🎲</button></div></div>';
  h += '<div><label>宽度 <span id="' + p + '-wV">512</span></label>';
  h += '<input type="range" id="' + p + '-w" min="256" max="1536" step="64" value="512"></div>';
  h += '<div><label>高度 <span id="' + p + '-hV">512</span></label>';
  h += '<input type="range" id="' + p + '-h" min="256" max="1536" step="64" value="512"></div>';
  h += '</div></details>';

  h += '<div class="withbtn">';
  h += '<button class="btn" id="' + p + '-go" style="flex:1">生成</button>';
  h += '<button class="btn danger" id="' + p + '-stop">中断</button>';
  h += '</div>';
  h += '<div class="err" id="' + p + '-err"></div>';
  return h;
}

function bindControls(p, img2img){
  var bind = function(id, ev, fn){ var e = $(p + '-' + id); if (e) e.addEventListener(ev, fn); };
  bind('steps','input',function(){ $('' + p + '-stepsV').textContent = this.value; });
  bind('cfg','input',function(){ $('' + p + '-cfgV').textContent = Number(this.value).toFixed(1); });
  bind('w','input',function(){ $('' + p + '-wV').textContent = this.value; });
  bind('h','input',function(){ $('' + p + '-hV').textContent = this.value; });
  bind('denoise','input',function(){ $('' + p + '-dv').textContent = Number(this.value).toFixed(2); });
  bind('dice','click',function(){ $('' + p + '-seed').value = Math.floor(Math.random() * 2147483647); });
  bind('go','click',function(){ generate(p); });
  bind('stop','click',function(){ api('/sdapi/v1/interrupt', {}).catch(function(){}); });
  bind('openlib','click',function(){ state.targets[p] = true; showTab('prompts'); });
}

function readControls(p){
  return {
    prompt: $(p + '-prompt').value.trim(),
    negative_prompt: $(p + '-negative').value,
    steps: Number($(p + '-steps').value),
    cfg_scale: Number($(p + '-cfg').value),
    width: Number($(p + '-w').value),
    height: Number($(p + '-h').value),
    sampler_name: $(p + '-sampler').value,
    seed: Number($(p + '-seed').value),
    batch_size: 1, n_iter: 1
  };
}

function readFile(file){
  return new Promise(function(resolve){
    var reader = new FileReader();
    reader.onload = function(){ resolve(String(reader.result).split(',')[1]); };
    reader.readAsDataURL(file);
  });
}

/* ---------------- generation ---------------- */

function poll(p){
  if (state.polls[p]) return;
  var bar = $('bar-' + p), step = $('step-' + p), pct = $('pct-' + p);
  state.polls[p] = setInterval(function(){
    api('/sdapi/v1/progress').then(function(pr){
      var value = Math.round((pr.progress || 0) * 100);
      bar.style.width = value + '%';
      pct.textContent = value + '%';
      var st = pr.state || {};
      if (st.sampling_steps) step.textContent = '第 ' + st.sampling_step + ' / ' + st.sampling_steps + ' 步';
      if (pr.current_image){
        var img = $('canvas-' + p).querySelector('img');
        if (img) img.src = 'data:image/png;base64,' + pr.current_image;
      }
    }).catch(function(){});
  }, 700);
}

function stopPoll(p){
  if (state.polls[p]){ clearInterval(state.polls[p]); state.polls[p] = null; }
}

function generate(p){
  var body = readControls(p);
  if (!body.prompt){ $(p + '-err').textContent = '提示词不能为空'; return; }
  $(p + '-err').textContent = '';
  $(p + '-go').disabled = true;
  $('canvas-' + p).innerHTML = '<img src="" alt="">';
  poll(p);

  var path = '/sdapi/v1/txt2img';
  if (p === 'img2img'){
    var file = $('img2img-init').files[0];
    if (!file){
      $(p + '-err').textContent = '请先选择底图';
      $(p + '-go').disabled = false; stopPoll(p); return;
    }
    path = '/sdapi/v1/img2img';
    body.denoising_strength = Number($('img2img-denoise').value);
    readFile(file).then(function(b64){
      body.init_images = [b64];
      return api(path, body);
    }).then(function(res){ done(p, res); }).catch(function(e){ failed(p, e); });
    return;
  }
  api(path, body).then(function(res){ done(p, res); }).catch(function(e){ failed(p, e); });
}

function done(p, res){
  stopPoll(p);
  $('bar-' + p).style.width = '100%';
  $('pct-' + p).textContent = '100%';
  $('step-' + p).textContent = '完成';
  $(p + '-go').disabled = false;
  if (res.images && res.images[0]){
    state.results[p] = res.images[0];
    $('canvas-' + p).innerHTML = '<img src="data:image/png;base64,' + res.images[0] + '">';
  }
}

function failed(p, e){
  stopPoll(p);
  $(p + '-go').disabled = false;
  $(p + '-err').textContent = '生成失败：' + e.message;
}

/* ---------------- quick actions ---------------- */

document.querySelectorAll('button[data-act]').forEach(function(btn){
  btn.addEventListener('click', function(){
    var p = btn.getAttribute('data-mode');
    var act = btn.getAttribute('data-act');
    var result = state.results[p];
    if (!result) return;

    if (act === 'save'){
      var a = document.createElement('a');
      a.href = 'data:image/png;base64,' + result;
      a.download = 'localdream-' + Date.now() + '.png';
      a.click();
    } else if (act === 'copy'){
      navigator.clipboard.writeText($(p + '-prompt').value);
      btn.textContent = '已复制';
      setTimeout(function(){ btn.textContent = '复制提示词'; }, 1200);
    } else if (act === 'toimg'){
      fetch('data:image/png;base64,' + result).then(function(r){ return r.blob(); }).then(function(blob){
        var dt = new DataTransfer();
        dt.items.add(new File([blob], 'result.png', { type:'image/png' }));
        $('img2img-init').files = dt.files;
        showTab('img2img');
      });
    } else if (act === 'upscale'){
      api('/sdapi/v1/upscalers').then(function(list){
        if (!list.length) throw new Error('主机上没有可用的放大器');
        btn.disabled = true;
        return api('/sdapi/v1/extra-single-image', {
          image: result, upscaling_resize: 2, upscaler_1: list[0].name, upscaler_2: 'None'
        });
      }).then(function(res){
        btn.disabled = false;
        if (res.image){
          state.results[p] = res.image;
          $('canvas-' + p).innerHTML = '<img src="data:image/png;base64,' + res.image + '">';
        }
      }).catch(function(e){ btn.disabled = false; $(p + '-err').textContent = '超分失败：' + e.message; });
    }
  });
});

/* ---------------- tabs ---------------- */

function showTab(name){
  state.active = name;
  document.querySelectorAll('.page').forEach(function(sec){ sec.classList.remove('active'); });
  $('page-' + name).classList.add('active');
  document.querySelectorAll('#tabs button').forEach(function(b){
    b.classList.toggle('active', b.getAttribute('data-tab') === name);
  });
  if (name === 'prompts') loadTags();
  if (name === 'models') loadModels();
  if (name === 'settings') loadState();
}

document.querySelectorAll('#tabs button').forEach(function(btn){
  btn.addEventListener('click', function(){ showTab(btn.getAttribute('data-tab')); });
});

/* ---------------- prompt library ---------------- */

var tagData = { categories:[], tags:[], category:null, query:'' };

function loadTags(){
  api('/localdream/tags').then(function(data){
    tagData.categories = data.categories || [];
    tagData.tags = data.tags || [];
    if (!tagData.category && tagData.categories.length) tagData.category = tagData.categories[0];
    renderCategories();
    renderTags();
  }).catch(function(e){
    $('tagGrid').innerHTML = '';
    $('tagCount').textContent = '读取提示词库失败：' + e.message;
  });
}

function renderCategories(){
  var box = $('tagCats');
  box.innerHTML = '';
  tagData.categories.forEach(function(cat){
    var b = document.createElement('button');
    b.className = 'chip' + (cat === tagData.category ? ' active' : '');
    b.textContent = cat;
    b.onclick = function(){ tagData.category = cat; renderCategories(); renderTags(); };
    box.appendChild(b);
  });
}

function renderTags(){
  var grid = $('tagGrid');
  grid.innerHTML = '';
  var query = tagData.query.toLowerCase();
  var list = tagData.tags.filter(function(t){
    if (t.category !== tagData.category) return false;
    if (!query) return true;
    return (t.en || '').toLowerCase().indexOf(query) >= 0 || (t.zh || '').indexOf(query) >= 0;
  });
  list.forEach(function(t){
    var b = document.createElement('button');
    b.className = 'tag';
    var en = document.createElement('span'); en.className = 'en'; en.textContent = t.en;
    var zh = document.createElement('span'); zh.className = 'zh'; zh.textContent = t.zh || '—';
    b.appendChild(en); b.appendChild(zh);
    b.onclick = function(){ insertTag(t.en); };
    grid.appendChild(b);
  });
  $('tagCount').textContent = '当前分类共 ' + list.length + ' 个标签，共 ' + tagData.tags.length + ' 个';
}

function targetPage(){
  if (state.targets.img2img) return 'img2img';
  if (state.targets.txt2img) return 'txt2img';
  return state.active === 'img2img' ? 'img2img' : 'txt2img';
}

function insertTag(tag){
  var p = targetPage();
  var box = $(p + '-prompt');
  var current = box.value.trim();
  box.value = current ? (current.replace(/,\s*$/, '') + ', ' + tag) : tag;
  state.targets = {};
  showTab(p);
  box.focus();
}

$('tagSearch').addEventListener('input', function(){ tagData.query = this.value; renderTags(); });
$('tagTarget').addEventListener('click', function(){
  state.targets = {};
  state.targets[state.active === 'img2img' ? 'txt2img' : 'img2img'] = true;
  this.textContent = '插入到：' + (state.active === 'img2img' ? '文生图' : '图生图');
});

/* ---------------- models ---------------- */

function loadModels(){
  $('modelErr').textContent = '';
  api('/sdapi/v1/sd-models').then(function(models){
    return api('/sdapi/v1/options').then(function(o){ return { models: models, current: o.sd_model_checkpoint }; });
  }).then(function(data){
    var box = $('modelList');
    box.innerHTML = '';
    if (!data.models.length){ box.innerHTML = '<div class="hint">主机上没有模型</div>'; return; }
    data.models.forEach(function(m){
      var card = document.createElement('div');
      card.className = 'model' + (m.title === data.current ? ' active' : '');
      var name = document.createElement('div'); name.className = 'name'; name.textContent = m.model_name;
      var path = document.createElement('div'); path.className = 'path'; path.textContent = m.filename || m.title;
      card.appendChild(name); card.appendChild(path);
      card.onclick = function(){
        $('modelErr').textContent = '正在切换模型，请稍候…';
        api('/sdapi/v1/options', { sd_model_checkpoint: m.title })
          .then(function(){ $('modelErr').textContent = '已切换到 ' + m.model_name; loadModels(); refreshConn(); })
          .catch(function(e){ $('modelErr').textContent = '切换失败：' + e.message; });
      };
      box.appendChild(card);
    });
  }).catch(function(e){ $('modelErr').textContent = '读取模型失败：' + e.message; });
}

/* ---------------- state ---------------- */

function loadState(){
  api('/localdream/state').then(function(s){
    $('stateBox').innerHTML = [
      '主机连接：' + (s.connected ? '已连接 ' + (s.host || '') : '未连接'),
      '当前模型：' + (s.serving_model || '未启动'),
      '画布：' + (s.width && s.height ? s.width + '×' + s.height : '未知')
    ].join('<br>');
  }).catch(function(e){ $('stateBox').textContent = '读取失败：' + e.message; });
}

function refreshConn(){
  api('/sdapi/v1/options').then(function(o){
    var label = o.sd_model_checkpoint ? '已连接 · ' + o.sd_model_checkpoint : '已连接 · 未启动模型';
    var el = $('conn'); el.textContent = label; el.className = 'conn ok';
  }).catch(function(){
    var el = $('conn'); el.textContent = '未连接'; el.className = 'conn bad';
  });
}

/* ---------------- boot ---------------- */

function loadSamplers(p){
  return api('/sdapi/v1/samplers').then(function(list){
    var sel = $(p + '-sampler');
    sel.innerHTML = '';
    list.forEach(function(s){
      var o = document.createElement('option');
      o.value = s.name; o.textContent = s.name;
      sel.appendChild(o);
    });
  }).catch(function(){});
}

$('ctrl-txt2img').innerHTML = controlsHtml('txt2img', false);
$('ctrl-img2img').innerHTML = controlsHtml('img2img', true);
bindControls('txt2img', false);
bindControls('img2img', true);
loadSamplers('txt2img');
loadSamplers('img2img');
refreshConn();
setInterval(refreshConn, 10000);
</script>
</body>
</html>"""
}
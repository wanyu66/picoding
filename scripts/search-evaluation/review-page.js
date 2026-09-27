'use strict'

function reviewApp() {
  const data = JSON.parse(document.getElementById('data').textContent)
  const key = 'search-review-' + data.manifest.experimentId
  let judgments = structuredClone(data.judgments), selected = 0
  const docs = new Map(data.documents.map(d => [d.id, d.source]))
  const $ = id => document.getElementById(id)
  function element(tag, text, parent) {
    const node = document.createElement(tag)
    if (text !== undefined) node.textContent = text
    if (parent) parent.append(node)
    return node
  }
  function importRows(rows) {
    if (rows.length !== judgments.length || new Set(rows.map(q => q.id)).size !== rows.length) throw Error('查询集合不完整或重复')
    const map = new Map(rows.map(q => [q.id, q]))
    judgments = data.judgments.map(base => {
      const row = map.get(base.id)
      if (!row || row.experimentId !== base.experimentId || row.corpusSha256 !== base.corpusSha256 || row.query !== base.query) throw Error('文件属于不同的查询或语料快照')
      if (Object.keys(row.relevance).sort().join(',') !== Object.keys(base.relevance).sort().join(',')) throw Error('候选文章集合发生变化')
      if (Object.values(row.relevance).some(g => g !== null && (!Number.isInteger(g) || g < 0 || g > 3))) throw Error('无效相关性等级')
      return { ...base, relevance: row.relevance, review: { ...base.review, ...row.review } }
    })
  }
  try { if (localStorage.getItem(key)) importRows(JSON.parse(localStorage.getItem(key))) } catch (e) { $('message').textContent = '未恢复本地草稿：' + e.message }
  function save() {
    try { localStorage.setItem(key, JSON.stringify(judgments)) } catch (_) { $('message').textContent = '浏览器不能保存草稿，请及时导出 JSONL。' }
    progress()
  }
  function invalidate(q) { q.review.status = 'pending'; q.review.reviewedAt = null; save() }
  function progress() {
    const done = judgments.filter(q => q.review.status === 'human_reviewed').length
    const graded = judgments.reduce((n, q) => n + Object.values(q.relevance).filter(g => g !== null).length, 0)
    $('progress').textContent = `${done} / ${judgments.length} 查询已人工确认 · ${graded} / ${data.manifest.candidatePairs} 对已评分`
    $('queries').replaceChildren()
    judgments.forEach((q, i) => {
      const b = element('button', `${q.review.status === 'human_reviewed' ? '✓ ' : ''}${q.id}  ${q.query}`, $('queries'))
      b.className = i === selected ? 'selected' : ''
      b.onclick = () => { selected = i; render() }
    })
  }
  function shuffledIds(q) {
    const hash = text => { let h = 2166136261; for (const c of text) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return h >>> 0 }
    return Object.keys(q.relevance).sort((a, b) => hash(q.id + ':' + a) - hash(q.id + ':' + b))
  }
  function render() {
    const q = judgments[selected]
    $('query').textContent = q.query
    $('need').textContent = '查询意图：' + q.need
    $('answerability').value = q.review.answerability || ''
    $('reviewed').checked = q.review.corpusReviewed === true
    $('note').value = q.review.note || ''
    if (!$('reviewer').value && q.review.reviewer) $('reviewer').value = q.review.reviewer
    $('candidates').replaceChildren()
    shuffledIds(q).forEach(id => {
      const doc = docs.get(id)
      const card = element('article', undefined, $('candidates'))
      element('h3', `文章 ${id} · ${doc.title}`, card)
      const label = element('label', '相关性： ', card)
      const select = element('select', undefined, label)
      for (const [value, text] of [['', '请选择'], ['0', '0 · 无关'], ['1', '1 · 仅提及／弱相关'], ['2', '2 · 部分满足意图'], ['3', '3 · 直接且充分满足']]) {
        const option = element('option', text, select); option.value = value
      }
      select.value = q.relevance[id] === null ? '' : String(q.relevance[id])
      select.onchange = () => { q.relevance[id] = select.value === '' ? null : Number(select.value); invalidate(q) }
      element('p', doc.summary || '无摘要', card)
      const detail = element('details', undefined, card)
      element('summary', '展开完整文章（原始 Markdown，外部图片不加载）', detail)
      element('pre', doc.content || '无正文', detail)
    })
    progress()
  }
  $('answerability').onchange = () => { const q = judgments[selected]; q.review.answerability = $('answerability').value || null; invalidate(q) }
  $('reviewed').onchange = () => { const q = judgments[selected]; q.review.corpusReviewed = $('reviewed').checked; invalidate(q) }
  $('note').oninput = () => { const q = judgments[selected]; q.review.note = $('note').value; invalidate(q) }
  $('finish').onclick = () => {
    const q = judgments[selected], grades = Object.values(q.relevance), answerable = grades.some(g => g >= 2)
    if (!$('reviewer').value.trim()) { $('message').textContent = '请填写人工标注者姓名或稳定代号。'; return }
    if (grades.some(g => g === null)) { $('message').textContent = '请给每篇候选文章评分；未标注不能当作不相关。'; return }
    if (!q.review.answerability || !q.review.corpusReviewed) { $('message').textContent = '请选择语料是否有答案，并确认已检查全部候选。'; return }
    if ((q.review.answerability === 'answerable') !== answerable) { $('message').textContent = '有答案需至少一篇 2/3 分文章；无答案不能存在 2/3 分文章。'; return }
    if (q.review.answerability === 'no_answer' && data.manifest.candidateScope !== 'entire_corpus') {
      $('message').textContent = '候选池不是完整语料，不能在此页面确认全语料无答案；请扩展语料审核。'; return
    }
    q.review = { ...q.review, status: 'human_reviewed', source: 'human', reviewer: $('reviewer').value.trim(), reviewedAt: new Date().toISOString() }
    save(); $('message').textContent = `${q.id} 已确认。请定期导出；浏览器草稿不是共享标注文件。`
    if (selected < judgments.length - 1) selected++
    render()
  }
  $('export').onclick = () => {
    const blob = new Blob([judgments.map(q => JSON.stringify(q)).join('\n') + '\n'], { type: 'application/x-ndjson;charset=utf-8' })
    const url = URL.createObjectURL(blob), link = element('a')
    link.href = url; link.download = 'judgments.reviewed.jsonl'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  $('import').onchange = async event => {
    try { importRows((await event.target.files[0].text()).trim().split(/\r?\n/).map(JSON.parse)); save(); render(); $('message').textContent = '草稿已导入。' }
    catch (e) { $('message').textContent = '导入失败：' + e.message }
  }
  $('dataset').textContent = `${data.manifest.documentCount} 篇真实文章 · ${judgments.length} 条语料驱动查询 · ${data.manifest.candidatePairs} 对待人工标注 · 小语料试评`
  $('scope').textContent = data.manifest.candidateScope === 'entire_corpus' ? '已检查本查询的全部语料候选' : '已检查本查询的全部池化候选'
  render()
}

function buildReviewPage(data) {
  const encoded = JSON.stringify(data).replace(/</g, '\\u003c')
  // The annotation payload has neither run ranks/scores nor heat/time metadata.
  const blind = JSON.parse(encoded)
  blind.documents = data.documents.map(d => ({ id: d.id, source: { title: d.source.title, summary: d.source.summary, content: d.source.content } }))
  const payload = JSON.stringify(blind).replace(/</g, '\\u003c')
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>搜索相关性人工盲标</title><style>
*{box-sizing:border-box}body{margin:0;background:#f5f6f8;color:#182b3a;font:15px/1.65 system-ui,sans-serif}header{padding:22px 28px;background:#153a48;color:white}h1{font-size:24px;margin:0}header p{margin:4px 0}main{display:grid;grid-template-columns:290px 1fr;gap:24px;max-width:1450px;margin:auto;padding:24px}nav{max-height:85vh;overflow:auto;position:sticky;top:12px}button,select,input,textarea{font:inherit;padding:7px 10px;border:1px solid #bac9ce;border-radius:6px}button{cursor:pointer;background:white;color:#153a48}nav button{display:block;text-align:left;width:100%;margin-bottom:6px}.selected{background:#d9efec}article,.guide,.controls{background:white;padding:20px;border-radius:10px;margin-bottom:16px;border:1px solid #dde5e8}h2{font-size:24px;margin-bottom:2px}h3{margin:0 0 12px;font-size:18px}pre{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;font-family:inherit}textarea{width:100%;min-height:65px}.controls label{display:block;margin:10px 0}.primary{background:#12695f;color:white}#message{min-height:30px;color:#875d0b}small{display:block;color:#536673}@media(max-width:760px){main{display:block}nav{position:static;max-height:160px;margin-bottom:20px}}
</style><header><h1>搜索相关性 · 人工盲标</h1><p id="dataset"></p><p id="progress"></p></header>
<main><nav id="queries"></nav><section><div class="guide"><strong>按查询意图判断，不按关键词出现次数判断</strong><p>0 无关；1 仅提及或弱相关，不能回答；2 部分满足；3 直接且充分满足。阅读全文再判断“没有答案”。模型未预填分数，检索版本、名次和分数均隐藏。</p><label>人工标注者：<input id="reviewer" placeholder="姓名或稳定代号"></label> <button id="export">导出 JSONL（含未完成草稿）</button><p><label>导入已有草稿：<input id="import" type="file" accept=".jsonl,.json"></label></p><small>自动草稿仅存当前浏览器。离开前请导出；无需登录，不上传数据。</small></div>
<h2 id="query"></h2><p id="need"></p><div id="candidates"></div><div class="controls">
<label>语料答案状态：<select id="answerability"><option value="">请选择</option><option value="answerable">有答案（至少一篇 2/3 分）</option><option value="no_answer">无答案（均为 0/1 分）</option></select></label>
<label><input type="checkbox" id="reviewed"> <span id="scope"></span></label><label>判断依据／疑问：<textarea id="note"></textarea></label><button class="primary" id="finish">确认本查询并进入下一条</button><p id="message"></p></div></section></main>
<script type="application/json" id="data">${payload}</script><script>(${reviewApp.toString()})();</script></html>`
}

module.exports = { buildReviewPage }

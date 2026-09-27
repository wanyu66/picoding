// Run with NODE_PATH pointing to a Playwright installation; uses locally installed Edge.
// Exercises the actual template's subscriber, rendering, resume and stop handlers.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../..');
const out = path.join(root, 'paicoding-web/target/model-stream-fault');
const template = fs.readFileSync(path.join(root, 'paicoding-ui/src/main/resources/templates/views/chat-home/index.html'), 'utf8');
const fixture = JSON.parse(fs.readFileSync(path.join(out, 'replay-events.json'), 'utf8'));
function between(start, end) {
  const a = template.indexOf(start), b = template.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a, `Template boundary changed: ${start}`);
  return template.slice(a, b);
}
const subscriber = between('stompClient.subscribe(`/user/chat/rsp`, function (message) {', '// 连接建立成功之后');
const handlers = between('function streamResumeKey()', 'function escapeHtml(');
const renderer = between('function appendServerMessage(answer)', '// 复制功能');
const click = between('sendBtn.click(function () {', 'promptField.keydown');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage();
    await page.route('http://model-fixture.local/**', route => route.fulfill({ contentType: 'text/html', body: '<select id="chat-type"><option>fixture</option></select><div id="chatCnt"></div><div id="content"></div><button id="send"><span id="label"></span></button><input id="input">' }));
    await page.goto('http://model-fixture.local/');
    await page.addScriptTag({ path: path.join(root, 'paicoding-ui/src/main/resources/static/js/jquery.min.js') });
    await page.addScriptTag({ content: `
      let currentGenerationId=null,lastStreamSeq=0,generating=false,lastChatId='fixture';
      const chatContent=$('#content'),sendBtn=$('#send'),sendBtnText=$('#label'),inputField=$('#input');
      const converter={makeHtml:x=>x},toastr={warning:()=>{}},hljs={highlightBlock:()=>{}};
      function scrollToBottom(){} function copy(){} function katexRender(){}
      function addClientMsg(){} function addSplit(){} function initWs(){} function doSend(){}
      window.sent=[];
      const stompClient={subscribe:(route,callback)=>window.deliver=event=>callback({body:JSON.stringify(event)}),send:(route,headers,body)=>window.sent.push({route,...JSON.parse(body)})};
      ${handlers}\n${renderer}\n${subscriber}\n${click}
      window.resumeFixture=resumeGeneration;
      window.resetFixture=()=>{currentGenerationId=null;lastStreamSeq=0;generating=false;chatContent.html('');sessionStorage.clear();window.sent=[];};
    ` });
    await page.evaluate(events => events.forEach(window.deliver), fixture.initial);
    const partial = await page.locator('.markdown-body').innerText();
    assert.equal(partial.trim(), 'chunk01|chunk02|chunk03|');
    await page.evaluate(() => window.resumeFixture());
    const resume = await page.evaluate(() => window.sent[0]);
    assert.equal(resume.type, 'resume'); assert.equal(resume.lastSeq, 4);
    const start = performance.now();
    await page.evaluate(events => events.forEach(window.deliver), fixture.replay);
    assert.equal((await page.locator('.markdown-body').innerText()).trim(), fixture.expected);
    await page.evaluate(events => events.forEach(window.deliver), [...fixture.replayAgain, ...fixture.initial]);
    assert.equal(await page.locator('.server-msg').count(), 1);
    assert.equal((await page.locator('.markdown-body').innerText()).trim(), fixture.expected);
    const replayRenderMs = performance.now() - start;
    await page.evaluate(() => window.resetFixture());
    await page.evaluate(events => events.forEach(window.deliver), fixture.initial);
    await page.locator('#send').click();
    const cancel = await page.evaluate(() => window.sent[0]);
    assert.equal(cancel.type, 'cancel');
    assert.equal(cancel.generationId, fixture.initial[0].generationId);
    const result = {status:'PASS',browser:'Microsoft Edge / Playwright',source:'extracted production template functions',dropAfterChunk:3,resumeCursor:resume.lastSeq,finalAnswer:fixture.expected,renderedAnswerElements:1,duplicateReplayExtraElements:0,stopClickCommand:cancel.type,replayRenderMs,scope:'DOM and STOMP command contract; no actual authenticated WebSocket connection'};
    fs.writeFileSync(path.join(out, 'ui-results.json'), JSON.stringify(result,null,2)+'\n');
    console.log(JSON.stringify(result));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode=1; });

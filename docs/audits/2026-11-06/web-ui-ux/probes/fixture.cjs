const { chromium } = require(require('node:path').resolve('web/node_modules/@playwright/test'));
const AxeBuilder = require(require('node:path').resolve('web/node_modules/@axe-core/playwright')).default;
const fs = require('fs');
const output = 'docs/audits/2026-11-06/web-ui-ux';
const base = 'http://127.0.0.1:4174';
const results = [];
(async () => {
 const browser = await chromium.launch();
 try {
  for (const surface of ['profile','delete-account','sign-out','privacy']) {
   for (const size of [{width:1440,height:900},{width:320,height:568},{width:720,height:450}]) {
    const context=await browser.newContext({viewport:size}); const page=await context.newPage();
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(`${base}/e2e/fixtures/ui-ux-audit/?surface=${surface}&theme=light`,{waitUntil:'networkidle'});
    await page.getByRole('dialog').first().waitFor();
    if(surface==='delete-account') await page.getByRole('button',{name:'Export a complete backup first',exact:true}).click();
    if(surface==='sign-out') {
      const choice=page.getByRole('button',{name:/delete cloud data/i});
      if(await choice.count()) await choice.click();
    }
    const geometry=await page.getByRole('dialog').first().evaluate(el=>({top:el.getBoundingClientRect().top,bottom:el.getBoundingClientRect().bottom,height:el.clientHeight,scrollHeight:el.scrollHeight,overflow:getComputedStyle(el).overflowY,rootOverflow:getComputedStyle(document.documentElement).overflow}));
    const scan=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();
    const name=`${surface}-${size.width}x${size.height}`;
    await page.screenshot({path:`${output}/evidence/${name}.png`});
    results.push({name,geometry,errors,violations:scan.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.map(n=>({html:n.html,target:n.target,summary:n.failureSummary}))}))});
    await context.close();
   }
  }
 } finally {await browser.close();fs.writeFileSync(`${output}/fixture-results.json`,JSON.stringify(results,null,2));}
 console.log(JSON.stringify(results.map(r=>({name:r.name,geometry:r.geometry,violations:r.violations.map(v=>v.id),errors:r.errors}))));
})().catch(e=>{console.error(e);process.exitCode=1});

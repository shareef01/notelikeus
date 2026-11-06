const {chromium}=require(require('node:path').resolve('web/node_modules/@playwright/test'));const AxeBuilder=require(require('node:path').resolve('web/node_modules/@axe-core/playwright')).default;const fs=require('fs');const out='docs/audits/2026-11-06/web-ui-ux';const results=[];
const make=(i,extra={})=>({id:'surface-'+i,localId:i+1,title:['Weekend plans','Trip ideas','Trip ideas (conflicted copy)'][i],content:i===2?'A different plan from another device.':'A few ideas worth keeping.\nOne practical next step.',checklist:[],timestamp:1700000000000+i,serverUpdatedAt:null,position:i,color:i===0?0xffc8e6c9|0:0,isPinned:i===0,isArchived:false,isTrashed:false,reminderTimestamp:null,labels:[{id:'travel',name:'Travel'}],attachments:[],...extra});
(async()=>{const b=await chromium.launch();try{for(const size of [{width:1440,height:900},{width:320,height:568}]) for(const state of ['empty','notes','search','archive','trash','labels','settings','editor','options','reminder','sort','selection','import','load-error','sync-error','unreadable','offline','diagnostics','privacy','conflict']){
 const c=await b.newContext({viewport:size,hasTouch:size.width<768,isMobile:size.width<768});const p=await c.newPage();const errors=[];p.on('pageerror',e=>errors.push(e.message));
 try{await p.goto('http://127.0.0.1:4174/');await p.getByRole('button',{name:'Continue without an account'}).click();await p.getByRole('button',{name:'New note'}).first().waitFor();
 if(!['empty','archive','trash','load-error','import'].includes(state))await p.evaluate(notes=>window.__NOTELIKEUS_NOTES_STORE__.getState().setNotes(notes),[make(0),make(1),make(2)]);
 const settings=async()=>p.getByRole('button',{name:'Open settings',exact:true}).click();
 const nav=async name=>{if(size.width<768)await p.getByRole('button',{name:'Open menu'}).click();await p.getByRole('button',{name,exact:true}).click();};
 if(state==='search')await p.getByRole('searchbox').fill('unfindablexyz');
 if(state==='archive')await nav('Archive');if(state==='trash')await nav('Trash');if(state==='labels')await nav('Edit labels');
 if(['settings','diagnostics','privacy'].includes(state)){await settings();if(state==='diagnostics')await p.getByRole('button',{name:/Sync diagnostics/}).click();if(state==='privacy')await p.getByRole('button',{name:/Privacy policy/}).click();}
 if(['editor','options','reminder'].includes(state)){await p.locator('[data-note-open="surface-0"]').click();if(state==='options')await p.getByRole('button',{name:'More options'}).click();if(state==='reminder')await p.getByRole('button',{name:'Set reminder'}).click();}
 if(state==='sort')await p.getByRole('button',{name:/^Sort:/}).click();
 if(state==='selection')await p.getByRole('checkbox',{name:/Select Weekend plans/}).click({force:true});
 if(state==='import')await p.locator('input[type=file][accept*=json]').setInputFiles({name:'audit-backup.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({version:3,notes:[{title:'Import example',content:'Example',timestamp:1700000000}]}))});
 if(state==='load-error')await p.evaluate(()=>{const s=window.__NOTELIKEUS_NOTES_STORE__.getState();s.setStatus('error');s.setError('Local storage could not be read.');});
 if(state==='sync-error')await p.evaluate(()=>window.__NOTELIKEUS_NOTES_STORE__.getState().setSyncError('The server is temporarily unavailable.'));
 if(state==='unreadable')await p.evaluate(()=>window.__NOTELIKEUS_NOTES_STORE__.getState().setUnreadableNoteIds(['locked-note']));
 if(state==='offline')await c.setOffline(true);
 if(state==='conflict')await p.getByRole('button',{name:'Conflict copy: compare with the current note',exact:true}).click();
 await p.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'}).catch(()=>{});
 const scan=await new AxeBuilder({page:p}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();
 await p.screenshot({path:`${out}/evidence/surface-${state}-${size.width}.png`});
 results.push({state,width:size.width,errors,violations:scan.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.map(n=>({html:n.html,summary:n.failureSummary}))})),dialogs:await p.getByRole('dialog').evaluateAll(els=>els.map(e=>({name:e.getAttribute('aria-label')??e.getAttribute('aria-labelledby'),top:e.getBoundingClientRect().top,bottom:e.getBoundingClientRect().bottom,overflow:getComputedStyle(e).overflowY}))),overflow:await p.evaluate(()=>document.documentElement.scrollWidth-innerWidth)});
 }catch(e){results.push({state,width:size.width,error:String(e)});}finally{await c.close();}
 }}finally{await b.close();fs.writeFileSync(`${out}/surface-results.json`,JSON.stringify(results,null,2));}console.log(JSON.stringify(results.map(r=>({state:r.state,width:r.width,error:r.error,violations:r.violations?.map(v=>v.id),overflow:r.overflow}))));})().catch(e=>{console.error(e);process.exitCode=1});

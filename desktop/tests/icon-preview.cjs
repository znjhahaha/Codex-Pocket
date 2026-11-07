const {chromium}=require('@playwright/test');
const fs=require('node:fs/promises');
(async()=>{
  const browser=await chromium.launch({channel:'msedge'});
  try{
    const page=await browser.newPage({viewport:{width:1240,height:840},deviceScaleFactor:1});
    await page.setContent(await fs.readFile('../design/icons/preview.html','utf8'));
    await page.screenshot({path:'../design/icons/preview.png',fullPage:true});
  }finally{await browser.close()}
})();

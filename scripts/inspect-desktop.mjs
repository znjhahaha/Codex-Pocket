import fs from 'node:fs';
const path = process.argv[2];
const fd = fs.openSync(path, 'r');
const prefix = Buffer.alloc(16); fs.readSync(fd, prefix, 0, 16, 0);
const headerSize = prefix.readUInt32LE(12);
const buf = Buffer.alloc(headerSize); fs.readSync(fd, buf, 0, headerSize, 16);
const header = JSON.parse(buf.toString());
const base = 8 + prefix.readUInt32LE(4);
const files=[];
function walk(tree, p='') { for(const [name, value] of Object.entries(tree.files??{})) {
 const next=p ? `${p}/${name}`:name;
 if(value.files) walk(value,next); else if(/\.js$/.test(name)&&!value.unpacked) files.push([next,value]);
}}
walk(header);
for(const [name, entry] of files) {
 if(!name.startsWith('.vite/build/early-bootstrap')) continue;
 const b=Buffer.alloc(entry.size);fs.readSync(fd,b,0,b.length,base+Number(entry.offset));
 const s=b.toString();
 if(name.includes('early-bootstrap-824')) fs.writeFileSync('.research/desktop-transport.js',s);
 for(const term of process.argv.slice(3)) {
  let n=s.indexOf(term);if(n>=0) console.log(JSON.stringify({file:name,term,context:s.slice(Math.max(0,n-400),n+2200)}));
 }
}
fs.closeSync(fd);

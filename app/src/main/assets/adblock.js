// Sections start with "//#name". "util" is prepended to any scriptlet set; "proc" is the
// procedural cosmetic engine called as proc(rules). Keep ES2015, no arrow functions.
//#util
var W=window,D=document,MG='sv'+Math.random().toString(36).slice(2),abi=0;
function re(s){if(s==null||s==='')return /(?:)/;var m=/^\/(.+)\/([gimsuy]*)$/.exec(s);if(m){try{return new RegExp(m[1],m[2].replace('g',''))}catch(e){return /(?!)/}}return new RegExp(String(s).replace(/[.*+?^${}()|[\]\\]/g,'\\$&'))}
function nd(s){s=s==null?'':String(s);var n=s.charAt(0)==='!';if(n)s=s.slice(1);var r=re(s);return {e:s==='',t:function(x){return r.test(x)!==n}}}
function ab(){if(!abi){abi=1;var o=W.onerror;W.onerror=function(m){if(typeof m==='string'&&m.indexOf(MG)>=0)return true;if(typeof o==='function')return o.apply(this,arguments)}}return function(){throw new ReferenceError(MG)}}
function obj(v){return v!==null&&(typeof v==='object'||typeof v==='function')}
// Resolves a.b.c, deferring through setters until intermediate objects exist.
function trap(o,c,f){var i=c.indexOf('.');if(i<0){try{f(o,c)}catch(e){}return}
var p=c.slice(0,i),r=c.slice(i+1),v=o[p];if(obj(v)){trap(v,r,f);return}
var d=Object.getOwnPropertyDescriptor(o,p);if(d&&d.set&&d.set._q){d.set._q.push([r,f]);return}
if(d&&(d.set||d.get||d.configurable===false))return;
var q=[[r,f]],s=function(a){v=a;if(obj(a))for(var j=0;j<q.length;j++)trap(a,q[j][0],q[j][1])};s._q=q;
try{Object.defineProperty(o,p,{configurable:true,enumerable:true,get:function(){return v},set:s})}catch(e){}}
function wrap(o,c,chk){trap(o,c,function(o,p){var d=Object.getOwnPropertyDescriptor(o,p)||{value:o[p]};if(d.configurable===false)return;var v=d.value,g=d.get,s=d.set;
Object.defineProperty(o,p,{configurable:true,get:function(){chk();return g?g.call(this):v},set:function(x){chk();if(s)s.call(this,x);else v=x}})})}
function tif(k,n,dl){var t=nd(n);dl=dl==null?'':String(dl);var dn=dl.charAt(0)==='!';if(dn)dl=dl.slice(1);if(t.e&&dl==='')return;var dv=parseInt(dl,10);
W[k]=new Proxy(W[k],{apply:function(f,th,a){var x=true;if(!t.e)x=t.t(String(a[0]));if(x&&dl!==''){var b=a[1]===undefined?NaN:+a[1];x=(b===dv||isNaN(b)&&isNaN(dv))!==dn}if(x)a[0]=function(){};return Reflect.apply(f,th,a)}})}
function adj(k,n,dl,bo){var r=re(n),d=dl==null||dl===''?1000:dl==='*'?-1:parseInt(dl,10),b=parseFloat(bo);if(isNaN(d))return;if(!isFinite(b))b=0.05;b=Math.min(50,Math.max(0.001,b));
W[k]=new Proxy(W[k],{apply:function(f,th,a){if((d<0||+a[1]===d)&&r.test(String(a[0])))a[1]=(+a[1]||0)*b;return Reflect.apply(f,th,a)}})}
function dom(sel,fn,beh,af){beh=beh||'';var run=function(){try{var l=D.querySelectorAll(sel);for(var i=0;i<l.length;i++)fn(l[i])}catch(e){}};
var go=function(){run();if(beh.indexOf('stay')<0)return;var p=0;new MutationObserver(function(){if(p)return;p=1;setTimeout(function(){p=0;run()},50)}).observe(D,{childList:true,subtree:true,attributes:true,attributeFilter:af})};
if(beh.indexOf('complete')>=0){if(D.readyState==='complete')go();else W.addEventListener('load',go)}else if(D.readyState==='loading')D.addEventListener('DOMContentLoaded',go);else go()}
function pm(ps){var L=[],K=/^(url|method|body|mode|credentials|cache|redirect|referrer|referrerPolicy|integrity|keepalive|headers|priority):(.*)$/;if(ps!=='*')String(ps).split(/\s+/).forEach(function(x){if(!x)return;var m=K.exec(x);L.push(m?[m[1],re(m[2])]:['url',re(x)])});
return function(d){for(var i=0;i<L.length;i++)if(!L[i][1].test(d[L[i][0]]==null?'':String(d[L[i][0]])))return false;return true}}
function body(b){return b==='emptyObj'?'{}':b==='emptyArr'?'[]':b==='true'?Math.random().toString(36).slice(2):''}
//#set-constant
S['set-constant']=function(c,raw){if(!c)return;var v,k=raw==null?'':String(raw);switch(k){case 'undefined':v=void 0;break;case 'false':v=false;break;case 'true':v=true;break;case 'null':v=null;break;
case '':case "''":case '""':case 'emptyStr':v='';break;case 'noopFunc':v=function(){};break;case 'trueFunc':v=function(){return true};break;case 'falseFunc':v=function(){return false};break;
case 'throwFunc':v=function(){throw new Error()};break;case 'noopCallbackFunc':v=function(){return function(){}};break;case 'emptyObj':v={};break;case 'emptyArr':v=[];break;
case 'noopPromiseResolve':v=function(){return Promise.resolve()};break;case 'noopPromiseReject':v=function(){return Promise.reject()};break;case 'yes':case 'no':v=k;break;
default:if(!/^-?\d+(\.\d+)?$/.test(k)||Math.abs(+k)>0x7FFF)return;v=+k}
trap(W,c,function(o,p){var d=Object.getOwnPropertyDescriptor(o,p);if(d&&d.configurable===false)return;var pv=d&&'value' in d?d.value:void 0;
if(pv!=null&&v!=null&&typeof pv!==typeof v)return;Object.defineProperty(o,p,{configurable:true,enumerable:true,get:function(){return v},set:function(){}})})};
//#abort-on-property-read
S['abort-on-property-read']=function(c){if(!c)return;var a=ab();trap(W,c,function(o,p){Object.defineProperty(o,p,{configurable:true,get:a,set:function(){}})})};
//#abort-on-property-write
S['abort-on-property-write']=function(c){if(!c)return;var a=ab();trap(W,c,function(o,p){var d=Object.getOwnPropertyDescriptor(o,p);if(d&&d.configurable===false)return;var v=o[p];Object.defineProperty(o,p,{configurable:true,get:function(){return v},set:a})})};
//#abort-current-script
S['abort-current-script']=function(c,n,cx){if(!c)return;var a=ab(),r=re(n),rc=cx?re(cx):null,me=D.currentScript;
wrap(W,c,function(){var e=D.currentScript;if(!(e instanceof HTMLScriptElement)||e===me)return;if(rc&&!rc.test(e.src))return;if(n&&!r.test(e.src?'':e.textContent))return;a()})};
//#abort-on-stack-trace
S['abort-on-stack-trace']=function(c,n){if(!c)return;var a=ab(),t=nd(n);wrap(W,c,function(){if(t.t(new Error().stack||''))a()})};
//#no-setTimeout-if
S['no-setTimeout-if']=function(n,d){tif('setTimeout',n,d)};
//#no-setInterval-if
S['no-setInterval-if']=function(n,d){tif('setInterval',n,d)};
//#adjust-setTimeout
S['adjust-setTimeout']=function(n,d,b){adj('setTimeout',n,d,b)};
//#adjust-setInterval
S['adjust-setInterval']=function(n,d,b){adj('setInterval',n,d,b)};
//#prevent-addEventListener
S['prevent-addEventListener']=function(ty,n){var rt=nd(ty),rn=nd(n);if(rt.e&&rn.e)return;var P=EventTarget.prototype;
P.addEventListener=new Proxy(P.addEventListener,{apply:function(f,th,a){var h='';try{h=typeof a[1]==='function'?String(a[1]):a[1]&&a[1].handleEvent?String(a[1].handleEvent):String(a[1])}catch(e){}
if((rt.e||rt.t(String(a[0])))&&(rn.e||rn.t(h)))return;return Reflect.apply(f,th,a)}})};
//#json-prune
S['json-prune']=function(pr,rq){var P=String(pr||'').split(/ +/).filter(Boolean),Q=String(rq||'').split(/ +/).filter(Boolean);if(!P.length)return;
function walk(o,path,del){if(!obj(o))return false;var i=path.indexOf('.'),k=i<0?path:path.slice(0,i),r=i<0?'':path.slice(i+1),f=false,ks=k==='*'||k==='[]'?Object.keys(o):[k];
for(var j=0;j<ks.length;j++){if(!Object.prototype.hasOwnProperty.call(o,ks[j]))continue;if(r===''){f=true;if(del)delete o[ks[j]]}else if(walk(o[ks[j]],r,del))f=true}return f}
function pru(o){try{for(var i=0;i<Q.length;i++)if(!walk(o,Q[i],false))return o;for(i=0;i<P.length;i++)walk(o,P[i],true)}catch(e){}return o}
JSON.parse=new Proxy(JSON.parse,{apply:function(f,th,a){return pru(Reflect.apply(f,th,a))}});
if(W.Response)Response.prototype.json=new Proxy(Response.prototype.json,{apply:function(f,th,a){return Reflect.apply(f,th,a).then(pru)}})};
//#remove-attr
S['remove-attr']=function(t,s,b){var A=String(t||'').split(/\s*\|\s*/).filter(Boolean);if(!A.length)return;
dom(s||A.map(function(x){return '['+x+']'}).join(','),function(e){for(var i=0;i<A.length;i++)e.removeAttribute(A[i])},b,A)};
//#remove-class
S['remove-class']=function(t,s,b){var C=String(t||'').split(/\s*\|\s*/).filter(Boolean);if(!C.length)return;
dom(s||C.map(function(x){return '.'+x}).join(','),function(e){for(var i=0;i<C.length;i++)e.classList.remove(C[i])},b,['class'])};
//#no-window-open-if
S['no-window-open-if']=function(n,dl){var t=nd(n),no=function(){};W.open=new Proxy(W.open,{apply:function(f,th,a){if(!t.t(Array.prototype.join.call(a,' ')))return Reflect.apply(f,th,a);
if(dl==null||dl==='')return null;var w={closed:false,opener:W,location:{href:'',assign:no,replace:no},document:{write:no,writeln:no,open:no,close:no},focus:no,blur:no,postMessage:no,close:function(){w.closed=true}};return w}})};
//#prevent-fetch
S['prevent-fetch']=function(ps,b,ty){if(!ps||!W.fetch)return;var m=pm(ps);W.fetch=new Proxy(W.fetch,{apply:function(f,th,a){var d={method:'GET'};
try{var q=a[0];if(q instanceof Request)['url','method','mode','credentials','cache','redirect','referrer','referrerPolicy','integrity','keepalive'].forEach(function(k){d[k]=q[k]});else d.url=String(q);
if(obj(a[1]))for(var k in a[1])d[k]=a[1][k]}catch(e){}if(!m(d))return Reflect.apply(f,th,a);
var r=new Response(body(b),{status:200,statusText:'OK'});try{Object.defineProperty(r,'url',{value:d.url});Object.defineProperty(r,'type',{value:ty||'basic'})}catch(e){}return Promise.resolve(r)}})};
//#prevent-xhr
S['prevent-xhr']=function(ps,b){var X=W.XMLHttpRequest;if(!ps||!X)return;var m=pm(ps),P=X.prototype,op=P.open,sd=P.send,H=new WeakMap();
P.open=function(me,u){try{var d={method:String(me),url:String(u)};if(m(d))H.set(this,d.url);else H.delete(this)}catch(e){}return op.apply(this,arguments)};
P.send=function(){if(!H.has(this))return sd.apply(this,arguments);var x=this,t=body(b),c=function(v){return {value:v,configurable:true}};
try{Object.defineProperties(x,{readyState:c(4),status:c(200),statusText:c('OK'),responseURL:c(H.get(x)),responseText:c(t),response:c(x.responseType==='json'?(t?JSON.parse(t):null):t)})}catch(e){}
setTimeout(function(){['readystatechange','load','loadend'].forEach(function(e){try{x.dispatchEvent(new Event(e))}catch(_){}})},1)}};
//#set-cookie
S['set-cookie']=function(n,v,p){if(!n)return;v=v==null?'':String(v);if(v==='emptyArr')v='[]';else if(v==='emptyObj')v='{}';
else if(!/^(true|false|yes|y|no|n|ok|on|off|accept|accepted|reject|rejected|allow|allowed|deny|denied|necessary|required|essential|nonessential|hide|hidden|checked|unchecked|forbidden|forever|-?\d{1,15})?$/i.test(v))return;
var c=encodeURIComponent(n)+'='+encodeURIComponent(v);try{if((';'+D.cookie.replace(/ /g,'')+';').indexOf(';'+c+';')>=0)return;D.cookie=c+(p==='none'?'':'; path=/')}catch(e){}};
//#set-local-storage-item
S['set-local-storage-item']=function(k,v){if(!k)return;v=v==null?'':String(v);try{var s=W.localStorage;if(v==='$remove$'){s.removeItem(k);return}
var x=/^(undefined|null|false|true|on|off|yes|no|y|n|ok|accept|accepted|reject|rejected|allow|allowed|deny|denied|-?\d{1,8})$/i.test(v)?v:v==='emptyArr'?'[]':v==='emptyObj'?'{}':v===''||v==="''"||v==='""'||v==='emptyStr'?'':null;
if(x!==null)s.setItem(k,x)}catch(e){}};
//#noeval-if
S['noeval-if']=function(n){var t=nd(n);W.eval=new Proxy(W.eval,{apply:function(f,th,a){if(t.t(String(a[0])))return;return Reflect.apply(f,th,a)}})};
//#disable-newtab-links
S['disable-newtab-links']=function(){D.addEventListener('click',function(ev){for(var t=ev.target;t;t=t.parentNode){if(t.localName==='a'&&t.hasAttribute('target')){ev.stopPropagation();ev.preventDefault();break}}},true)};
//#proc
(function(R){if(window.__svP)return;window.__svP=1;
var D=document,M='data-svlo-p',NH=false,pend=0,last=0,mo=null;try{NH=CSS.supports('selector(:has(*))')}catch(e){}
function A(l){return Array.prototype.slice.call(l)}
function uq(a){var s=new Set();return a.filter(function(e){if(!e||s.has(e))return false;s.add(e);return true})}
function txt(p){var m=/^\/(.+)\/([imsu]*)$/.exec(p),r;if(m){try{r=new RegExp(m[1],m[2])}catch(e){r=/(?!)/}return function(t){return r.test(t)}}return function(t){return t.indexOf(p)>=0}}
// c starts with ' ' or '>' (inside e), '+'/'~' (siblings) or is a compound applied to e itself.
function rel(e,c,one){var k=c.charAt(0),p;if(k===' '||k==='>'){if(one)return e.querySelector(':scope'+c)?[e]:[];return A(e.querySelectorAll(':scope'+c))}
p=e.parentElement;if(!p)return [];e.setAttribute(M,'');try{return A(p.querySelectorAll(':scope>['+M+']'+c))}finally{e.removeAttribute(M)}}
function run(s,l){for(var i=1;i<s.length&&l.length;i++)l=task(s[i],l);return l}
function task(t,l){var f;switch(t[0]){
case 't':f=t.f||(t.f=txt(t[1]));return l.filter(function(e){return f(e.textContent)});
case 'p':f=t.f||(t.f=txt(t[1]));return f(location.pathname+location.search)?l:[];
case 'l':return l.filter(function(e){return e.textContent.length>=t[1]});
case 'h':return l.filter(function(e){return typeof t[1]==='string'?rel(e,t[1],1).length>0:run(t[1],rel(e,t[1][0])).length>0});
case 'n':return l.filter(function(e){return run(t[1],!t[1][0]||e.matches(t[1][0])?[e]:[]).length===0});
case 'c':f=t.f||(t.f=new RegExp(t[3],t[4]));return l.filter(function(e){return f.test(getComputedStyle(e,t[1]||null).getPropertyValue(t[2]))});
case 'u':return uq(l.map(function(e){if(typeof t[1]==='string')return e.parentElement&&e.parentElement.closest(t[1]);for(var i=0;e&&i<t[1];i++)e=e.parentElement;return e}));
case 'x':return uq([].concat.apply([],l.map(function(e){var r=D.evaluate(t[1],e,null,7,null),o=[];for(var i=0;i<r.snapshotLength;i++)if(r.snapshotItem(i).nodeType===1)o.push(r.snapshotItem(i));return o})));
case 's':return uq([].concat.apply([],l.map(function(e){return rel(e,t[1])})))}return []}
function sty(e,c){c.split(';').forEach(function(d){var i=d.indexOf(':');if(i<1)return;var v=d.slice(i+1).trim(),im=/!\s*important$/.test(v);e.style.setProperty(d.slice(0,i).trim(),v.replace(/!\s*important$/,'').trim(),im?'important':'')})}
function go(){pend=0;last=Date.now();for(var i=0;i<R.length;i++){var r=R[i],l;if(r.dead)continue;try{
if(NH&&r[3]){if(r[1]!==1)continue;l=A(D.querySelectorAll(r[3]))}else l=run(r[0],!r[0][0]?[D]:A(D.querySelectorAll(r[0][0])));
for(var j=0;j<l.length;j++){var e=l[j];if(e.nodeType!==1)continue;if(r[1]===1)e.remove();else if(r[1]===2)sty(e,r[2]);
else if(e.style.getPropertyValue('display')!=='none'||!e.style.getPropertyPriority('display'))e.style.setProperty('display','none','important')}}catch(x){r.dead=1}}
if(mo)mo.takeRecords()}
function sch(){if(pend)return;pend=1;setTimeout(function(){(window.requestAnimationFrame||setTimeout)(go)},Math.max(0,120-(Date.now()-last)))}
go();D.addEventListener('DOMContentLoaded',sch);window.addEventListener('load',sch);
try{mo=new MutationObserver(function(ms){for(var i=0;i<ms.length;i++)if(ms[i].addedNodes.length){sch();return}});mo.observe(D,{childList:true,subtree:true})}catch(e){}})
